#!/usr/bin/env python3
"""混合检索器：BM25 + 字符 n-gram 向量 + 类别加权。

设计要点（每一条都是针对本项目语料的实测结论）：

1. **语料严重不平衡**：8,893 条里设计规范占 99.0%、运维知识仅 0.1%。
   纯 BM25 排序会让运维内容被规范文本淹没，因此对自建运维语料做权重加成
   （见 CATEGORY_WEIGHT）。

2. **BM25 与向量互补**：
   - BM25 强于精确术语（"COP"、"GB50189"、"小温差"）；
   - 字符 n-gram TF-IDF 强于语素重叠但字面不同的表达
     （"夜间基载" ↔ "待机能耗" 共享 2-gram）。
   两路分数各自 min-max 归一化后加权融合。

3. **不生成、只检索**：本模块只负责找到证据片段及其出处。
   答案生成由 LLM 层负责（可选）。这样即使没有 LLM，系统也能
   给出「检索到的原文 + 出处」，可用于可溯源问答。

用法（作为库）：
    from retriever import Retriever
    r = Retriever()
    hits = r.search("小温差大流量有什么危害", top_k=5)
"""

from __future__ import annotations

import json
import pickle
import re
import threading
from dataclasses import dataclass, field
from pathlib import Path

import jieba
import numpy as np

RAG = Path(__file__).resolve().parents[1]
DATA = RAG / "data"

# 类别权重：补偿语料规模差异，让高价值语料能排上来
CATEGORY_WEIGHT = {
    "运维知识": 3.2,   # 本项目自建，直接回答运维问题，最强加成
    "数据字典": 1.8,
    "项目文档": 1.6,
    "设计规范": 1.0,
}

# 融合权重
W_BM25 = 0.55
W_VECTOR = 0.45

# 命中关键词时的额外加分（问句里的领域术语若出现在正文，可信度更高）
# ================================================================
# 领域闸门词表（宽口径）
#
# 与下面的 DOMAIN_TERMS 分工不同：
#   - DOMAIN_TERMS：**精确术语**，命中后给对应片段加分（影响排序）
#   - DOMAIN_WORDS：**宽口径领域词**，只用来判断「这个问题是否属于本领域」（影响是否作答）
#
# 【为什么需要闸门】实测发现 BM25 在中文上存在词面重叠误判：
#   「怎么做红烧肉」最高分 2.09，比「怎么降低运行成本」的 2.02 还高。
# 若单纯降低拒答阈值，红烧肉就会被答出来；单纯提高阈值，合法的运维问题又答不了。
# 因此把「是否属于本领域」和「相关度够不够」拆成两道独立判断。
# ================================================================
DOMAIN_WORDS = [
    # 建筑与系统
    "建筑", "楼", "园区", "楼宇", "物业", "设施",
    # 暖通空调
    "空调", "制冷", "冷机", "冷水机", "离心", "螺杆", "冷冻", "冷却", "采暖", "供暖",
    "锅炉", "热泵", "换热", "风机", "水泵", "风柜", "AHU", "VAV", "末端", "盘管",
    "新风", "回风", "送风", "风量", "水量", "水阀", "旁通", "水力", "管路",
    "供回水", "温差", "流量", "冷量", "热量", "负荷", "机组", "COP", "能效", "喘振",
    "结垢", "冷凝", "蒸发", "冷媒", "变频",
    # 能源与计量
    "能耗", "用电", "电量", "耗电", "电费", "电价", "峰谷", "蓄冷", "分项", "计量",
    "表计", "读数", "需量", "功率", "碳", "排放", "节能", "能耗强度", "EUI",
    # 运维与数据
    "运维", "运行", "检修", "维护", "保养", "故障", "报警", "异常", "诊断", "排查",
    "参数", "设定值", "温度", "湿度", "舒适", "数据", "导入", "清洗", "缺失",
    "单位", "时间格式", "追溯", "回滚", "基线", "评级", "统计", "报表", "趋势",
    # 成本口径（老师关心的方向）
    "成本", "费用", "省钱", "降本", "经济",
]


def in_domain(question: str) -> bool:
    """判断问题是否属于本系统领域（宽口径）。

    用于「拒答闸门」：不属于本领域的问题，无论检索得分多少都直接拒答，
    避免答非所问。英文按小写比较（COP / AHU / VAV / EUI 等）。
    """
    q = (question or "").lower()
    return any(w.lower() in q for w in DOMAIN_WORDS)


DOMAIN_TERMS = [
    "小温差", "大流量", "基载", "待机", "COP", "能效", "供回水温差", "供水温度",
    "结垢", "冷凝", "蒸发", "冷媒", "喘振", "负荷率", "分项计量", "异常",
    "排查", "诊断", "归因", "评级", "EUI", "缺失率", "数据质量", "表计卡死",
    "夜间", "变频", "水力失衡", "旁通",
]


@dataclass
class Hit:
    chunk_id: str
    title: str
    content: str
    category: str
    source: str
    source_type_cn: str
    confidence: str
    tags: list[str] = field(default_factory=list)
    note: str = ""
    bm25_score: float = 0.0
    vector_score: float = 0.0
    final_score: float = 0.0

    def to_dict(self) -> dict:
        return {
            "chunkId": self.chunk_id,
            "title": self.title,
            "content": self.content,
            "category": self.category,
            "source": self.source,
            "sourceType": self.source_type_cn,
            "confidence": self.confidence,
            "tags": self.tags,
            "note": self.note,
            "score": round(self.final_score, 4),
            "bm25Score": round(self.bm25_score, 4),
            "vectorScore": round(self.vector_score, 4),
        }


class Retriever:
    """线程安全的懒加载检索器（FastAPI 多线程下共享单例）"""

    _instance = None
    _lock = threading.Lock()

    def __init__(self) -> None:
        self.chunks: list[dict] = []
        self.bm25 = None
        self.mat = None
        self.vectorizer = None
        self.vector_backend = "unknown"
        self.meta: dict = {}
        self._load()

    # ---------------------------------------------------------- 单例
    @classmethod
    def instance(cls) -> "Retriever":
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = cls()
        return cls._instance

    # ---------------------------------------------------------- 加载
    def _load(self) -> None:
        with open(DATA / "chunks.jsonl", encoding="utf-8") as f:
            self.chunks = [json.loads(l) for l in f if l.strip()]
        with open(DATA / "bm25.pkl", "rb") as f:
            self.bm25 = pickle.load(f)["bm25"]
        from scipy.sparse import load_npz  # type: ignore
        self.mat = load_npz(DATA / "vectors.npz")
        with open(DATA / "vectorizer.pkl", "rb") as f:
            self.vectorizer = pickle.load(f)
        self.meta = json.loads((DATA / "index_meta.json").read_text(encoding="utf-8"))
        self.vector_backend = self.meta.get("vector_backend", "unknown")

    # ---------------------------------------------------------- 向量
    def _vector_query(self, q: str):
        from scipy.sparse import csr_matrix  # type: ignore
        if self.vector_backend == "sklearn-tfidf":
            return self.vectorizer.transform([q])
        # 自实现 n-gram 降级路径
        vocab: dict = self.vectorizer
        text = re.sub(r"\s+", "", q.lower())
        grams: dict[str, float] = {}
        for n in range(2, 5):
            for i in range(len(text) - n + 1):
                grams[text[i:i + n]] = grams.get(text[i:i + n], 0) + 1
        cols, vals = [], []
        for k, v in grams.items():
            if k in vocab:
                cols.append(vocab[k])
                vals.append(1.0 + np.log(v))
        if not cols:
            return csr_matrix((1, self.mat.shape[1]))
        norm = np.sqrt(sum(x * x for x in vals)) or 1.0
        return csr_matrix(([x / norm for x in vals], ([0] * len(cols), cols)),
                          shape=(1, self.mat.shape[1]))

    # ---------------------------------------------------------- 检索
    def search(self, query: str, top_k: int = 5,
               categories: list[str] | None = None,
               min_score: float = 0.0) -> list[Hit]:
        q = (query or "").strip()
        if not q:
            return []

        # ---- BM25 分数
        bm_scores = np.asarray(self.bm25.get_scores(list(jieba.cut(q))), dtype=float)

        # ---- 向量分数（余弦相似度，矩阵已 L2 归一化）
        qv = self._vector_query(q)
        vec_scores = np.asarray((self.mat @ qv.T).todense()).ravel()

        # ---- min-max 归一化（两路量纲不同，必须各自归一化后再融合）
        def norm(a: np.ndarray) -> np.ndarray:
            lo, hi = float(a.min()), float(a.max())
            return (a - lo) / (hi - lo) if hi > lo else np.zeros_like(a)

        bm_n, vec_n = norm(bm_scores), norm(vec_scores)

        # ---- 领域术语命中加分
        term_bonus = np.zeros(len(self.chunks))
        q_terms = [t for t in DOMAIN_TERMS if t.lower() in q.lower()]
        if q_terms:
            for i, c in enumerate(self.chunks):
                text = c["title"] + c["content"]
                hit = sum(1 for t in q_terms if t.lower() in text.lower())
                term_bonus[i] = min(hit / max(len(q_terms), 1), 1.0) * 0.25

        # ---- 融合 + 类别加权
        final = (W_BM25 * bm_n + W_VECTOR * vec_n + term_bonus)
        for i, c in enumerate(self.chunks):
            final[i] *= CATEGORY_WEIGHT.get(c["category"], 1.0)

        # ---- 类别过滤
        order = np.argsort(-final)
        out: list[Hit] = []
        for i in order:
            c = self.chunks[int(i)]
            if categories and c["category"] not in categories:
                continue
            if final[i] < min_score:
                continue
            out.append(Hit(
                chunk_id=c["id"], title=c["title"], content=c["content"],
                category=c["category"], source=c["source"],
                source_type_cn=c.get("source_type_cn", ""),
                confidence=c.get("confidence", "medium"),
                tags=c.get("tags", []), note=c.get("note", ""),
                bm25_score=float(bm_scores[int(i)]),
                vector_score=float(vec_scores[int(i)]),
                final_score=float(final[int(i)]),
            ))
            if len(out) >= top_k:
                break
        return out

    # ---------------------------------------------------------- 统计
    def stats(self) -> dict:
        return {
            "total_chunks": len(self.chunks),
            "categories": self.meta.get("categories", {}),
            "vector_backend": self.vector_backend,
            "built_at": self.meta.get("built_at", ""),
        }


if __name__ == "__main__":
    import sys
    r = Retriever()
    print(json.dumps(r.stats(), ensure_ascii=False, indent=2))
    print()
    for q in (sys.argv[1:] or ["小温差大流量有什么危害"]):
        print(f"Q: {q}")
        for h in r.search(q, top_k=4):
            print(f"  [{h.final_score:6.3f}] ({h.category}/{h.source_type_cn}) {h.title[:48]}")
            print(f"          {h.content[:90]}...")
        print()

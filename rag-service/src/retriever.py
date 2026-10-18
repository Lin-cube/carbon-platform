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

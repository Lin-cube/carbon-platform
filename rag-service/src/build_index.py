#!/usr/bin/env python3
"""构建 RAG 知识库索引。

把四类语料统一成可检索的 chunk 集合：
  1. 设计规范 QA（sikong_knowledge_base.txt，8805 条）—— 标注为「设计规范」类
  2. 暖通运维知识（corpus/hvac_operations.json）—— 本项目自建，标注来源与可信度
  3. 项目数据字典（docs/data_dictionary.md）—— 自动拆分为字段说明 chunk
  4. 项目算法与评测结论（docs/eval/*.md）—— 自动拆分

输出（rag-service/data/）：
  chunks.jsonl   统一 chunk 集合（含元数据）
  bm25.pkl       BM25 索引（jieba 分词）
  vectors.npz    字符 n-gram TF-IDF 向量矩阵（用于语义近似检索）
  index_meta.json 索引元信息（条数、构建时间、各来源占比）

为什么用「BM25 + 字符 n-gram TF-IDF」而不是神经向量模型：
  本机 16GB 内存需同时运行 MySQL、Spring Boot、Node 与 RAG 服务，
  引入 sentence-transformers（约 400MB 权重 + 推理内存）会挤占资源。
  字符 n-gram 对中文有天然优势：它能捕捉「夜间基载」与「待机能耗」这类
  词面不同但共享语素的表达，且零模型下载、内存占用可控。
  检索接口预留了 embedding 后端插槽，后续可替换为神经向量模型。

用法：
    .venv/bin/python rag-service/src/build_index.py
"""

from __future__ import annotations

import json
import pickle
import re
import sys
import time
from collections import Counter
from datetime import datetime
from pathlib import Path

import jieba
import numpy as np

REPO = Path(__file__).resolve().parents[2]
RAG = REPO / "rag-service"
CORPUS = RAG / "corpus"
DATA = RAG / "data"
SIKONG = REPO.parent / "sikong_knowledge_base.txt"

# 字符 n-gram 配置
NGRAM_MIN, NGRAM_MAX = 2, 4
MAX_FEATURES = 60000


# ============================================================ 语料加载
def load_hvac_corpus() -> list[dict]:
    """自建运维知识（corpus/ 下全部 JSON 文件）。

    按主题拆成多个文件便于维护：
      hvac_operations.json        核心运维概念（冷冻水/COP/夜间基载/评级）
      chiller_cooling.json        冷机与冷却水系统
      water_air_system.json       水系统输配与空气侧末端
      heating_operation.json      热源供暖与运行管理
      diagnosis_dataquality.json  故障诊断与数据质量
      comfort_metrics.json        舒适性与能效指标

    所有条目统一打成「运维知识」类别并给予检索加权（×3.2），
    因为这类语料条数远少于设计规范，不加权会被淹没。
    """
    out: list[dict] = []
    files = sorted(CORPUS.glob("*.json"))
    if not files:
        return []
    per_file: list[str] = []
    for f in files:
        try:
            obj = json.loads(f.read_text(encoding="utf-8"))
        except Exception as e:  # noqa: BLE001
            print(f"    ⚠️ 跳过 {f.name}：{e}")
            continue
        n_before = len(out)
        for e in obj.get("entries", []):
            st = e.get("source_type", "practice")
            out.append({
                "id": e["id"],
                "category": "运维知识",
                "title": e["title"],
                "content": e["content"],
                "tags": e.get("tags", []),
                "source": f"本项目自建语料 {e['id']}（{f.stem}）",
                "source_type": st,
                "source_type_cn": {"principle": "物理原理", "practice": "行业实践",
                                   "project": "本项目文档",
                                   "standard_ref": "标准指引"}.get(st, st),
                "confidence": e.get("confidence", "medium"),
                "note": e.get("note", ""),
                "weight": 1.5,   # 自建运维语料质量最高，检索加权
            })
        per_file.append(f"{f.stem}={len(out)-n_before}")
    print(f"  [1/4] 运维知识          {len(out):>6} 条  ({', '.join(per_file)})")
    return out


def load_sikong_corpus() -> list[dict]:
    """设计规范 QA。文本已脱敏无关内容，直接按 QA 对切分。"""
    if not SIKONG.exists():
        print(f"  [2/4] 设计规范 QA        跳过（未找到 {SIKONG.name}）")
        return []
    txt = SIKONG.read_text(encoding="utf-8", errors="ignore")
    pairs = re.findall(r"【问题】(.+?)\n【答案】(.+?)(?=\n【问题】|\Z)", txt, re.S)
    out = []
    for i, (q, a) in enumerate(pairs):
        q = q.strip()
        a = a.replace("<br />", " ").replace("<br/>", " ").strip()
        if len(a) < 10:
            continue
        std = ""
        m = re.search(r"(《[^》]+》|(?:GB|JGJ|CJJ|DB)\s?/?T?\s?\d{4,5}[-—]?\d{0,4})", a)
        if m:
            std = m.group(1).replace(" ", "")
        out.append({
            "id": f"SK-{i:05d}",
            "category": "设计规范",
            "title": q,
            "content": a,
            "tags": [std] if std else [],
            "source": f"司空知识库 · {std}" if std else "司空知识库",
            "source_type": "standard_qa",
            "source_type_cn": "设计规范问答",
            "confidence": "high",
            "note": "内容为建筑设计规范问答，可用于设计依据查询；非运维操作指导。",
            "weight": 0.9,   # 与运维问题相关性较低，略降权
        })
    print(f"  [2/4] 设计规范 QA        {len(out):>6} 条")
    return out


def load_data_dictionary() -> list[dict]:
    """项目数据字典：按字段拆成 chunk，供「某字段是什么/缺失率多少」类问题检索"""
    f = REPO / "docs" / "data_dictionary.md"
    if not f.exists():
        print("  [3/4] 项目数据字典        跳过（未找到）")
        return []
    txt = f.read_text(encoding="utf-8")
    out = []
    # 按 Markdown 表格行提取字段说明
    for line in txt.splitlines():
        if not line.strip().startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 3 or set(cells[0]) <= set("-: "):
            continue
        field = cells[0].strip("`")
        if not re.match(r"^[a-z_][a-z0-9_]*$", field):
            continue
        desc = " | ".join(cells[1:])
        out.append({
            "id": f"DD-{field}",
            "category": "数据字典",
            "title": f"字段 {field} 的含义与口径",
            "content": f"字段名：{field}\n说明：{desc}",
            "tags": [field],
            "source": "docs/data_dictionary.md",
            "source_type": "project",
            "source_type_cn": "本项目文档",
            "confidence": "high",
            "note": "",
            "weight": 1.3,
        })
    print(f"  [3/4] 项目数据字典        {len(out):>6} 条")
    return out


def load_project_docs() -> list[dict]:
    """项目算法与评测结论：从评测报告中提取结论性段落"""
    out = []
    files = [
        (REPO / "docs" / "eval" / "anomaly_eval_report.md", "异常识别评测"),
        (REPO / "docs" / "eval" / "ai_qa_eval_report.md", "AI 问答评测"),
        (REPO / "docs" / "deliverables" / "06-技术栈审计报告.md", "技术栈审计"),
    ]
    for f, label in files:
        if not f.exists():
            continue
        txt = f.read_text(encoding="utf-8")
        # 按二级标题切段
        for sec in re.split(r"\n(?=## )", txt):
            sec = sec.strip()
            if len(sec) < 80:
                continue
            head = sec.split("\n", 1)[0].lstrip("# ").strip()
            # 过滤"元内容"：纯表格/清单页（逐题结果、复现方法、命令块）不构成知识，
            # 检索时只会挤占引用位。判据：表格行占比过高，或标题属于结构性章节。
            body = sec[len(head):]
            nonempty = [l for l in body.splitlines() if l.strip()]
            tbl_ratio = (sum(1 for l in nonempty if l.lstrip().startswith("|"))
                         / max(len(nonempty), 1))
            if tbl_ratio > 0.45:
                continue
            if re.search(r"(逐题结果|复现方法|复现命令|命令速查|附录|目录|清单|索引)", head):
                continue
            out.append({
                "id": f"DOC-{label}-{len(out):03d}",
                "category": "项目文档",
                "title": f"{label}：{head}",
                "content": sec[:2000],
                "tags": [label],
                "source": str(f.relative_to(REPO)),
                "source_type": "project",
                "source_type_cn": "本项目文档",
                "confidence": "high",
                "note": "",
                "weight": 1.2,
            })
    print(f"  [4/4] 项目文档            {len(out):>6} 条")
    return out


# ============================================================ 索引构建
def build_bm25(chunks: list[dict]):
    from rank_bm25 import BM25Okapi

    corpus_tokens = []
    for c in chunks:
        # 标题权重更高：重复标题词以提升其影响力
        text = (c["title"] + " ") * 3 + c["content"] + " " + " ".join(c.get("tags", []))
        corpus_tokens.append(list(jieba.cut(text)))
    return BM25Okapi(corpus_tokens), corpus_tokens


def char_ngrams(text: str) -> Counter:
    """字符 n-gram（中文无需分词，能捕捉共享语素）"""
    t = re.sub(r"\s+", "", text)
    grams: Counter = Counter()
    for n in range(NGRAM_MIN, NGRAM_MAX + 1):
        for i in range(len(t) - n + 1):
            grams[t[i:i + n]] += 1
    return grams


def build_vectors(chunks: list[dict]):
    """字符 n-gram TF-IDF 向量矩阵（稀疏存储，L2 归一化便于余弦相似度）"""
    from scipy.sparse import csr_matrix, lil_matrix  # type: ignore
    from sklearn.feature_extraction.text import TfidfVectorizer  # type: ignore

    texts = [(c["title"] + " ") * 2 + c["content"] + " " + " ".join(c.get("tags", []))
             for c in chunks]
    vec = TfidfVectorizer(analyzer="char_wb", ngram_range=(NGRAM_MIN, NGRAM_MAX),
                          max_features=MAX_FEATURES, sublinear_tf=True)
    try:
        mat = vec.fit_transform(texts)
        return mat, vec
    except Exception as e:  # noqa: BLE001
        print(f"    ⚠️ sklearn 不可用（{e}），改用自实现 n-gram 相似度")
        return None, None


def build_vectors_fallback(chunks: list[dict]):
    """不依赖 sklearn 的降级方案：稀疏 n-gram 计数矩阵"""
    docs = []
    for c in chunks:
        text = (c["title"] + " ") * 2 + c["content"] + " " + " ".join(c.get("tags", []))
        g = char_ngrams(text.lower())
        # 子线性缩放抑制高频 n-gram
        docs.append({k: 1.0 + np.log(v) for k, v in g.items()})
    # 构建词汇表
    vocab: dict[str, int] = {}
    for d in docs:
        for k in d:
            if k not in vocab:
                vocab[k] = len(vocab)
    rows, cols, vals = [], [], []
    for i, d in enumerate(docs):
        norm = np.sqrt(sum(v * v for v in d.values())) or 1.0
        for k, v in d.items():
            rows.append(i); cols.append(vocab[k]); vals.append(v / norm)
    from scipy.sparse import csr_matrix  # type: ignore
    return csr_matrix((vals, (rows, cols)), shape=(len(docs), len(vocab))), vocab


def main() -> int:
    t0 = time.time()
    print("=" * 74)
    print("构建 RAG 索引")
    print("=" * 74)

    chunks: list[dict] = []
    chunks += load_hvac_corpus()
    chunks += load_sikong_corpus()
    chunks += load_data_dictionary()
    chunks += load_project_docs()

    if not chunks:
        print("[错误] 未加载到任何语料")
        return 1

    print(f"\n  语料合计：{len(chunks)} 条")
    cat = Counter(c["category"] for c in chunks)
    for k, v in cat.most_common():
        print(f"    {k:<12} {v:>6} 条 ({v/len(chunks)*100:>5.1f}%)")

    # ---- BM25
    print("\n  构建 BM25 索引（jieba 分词）...")
    bm25, tokens = build_bm25(chunks)
    avg = sum(len(t) for t in tokens) / len(tokens)
    print(f"    完成，平均文档长度 {avg:.0f} 词")

    # ---- 向量
    print("  构建字符 n-gram 向量索引...")
    mat, vec = build_vectors(chunks)
    backend = "sklearn-tfidf"
    if mat is None:
        mat, vec = build_vectors_fallback(chunks)
        backend = "selfbuilt-ngram"
    print(f"    完成，后端={backend}，矩阵={mat.shape[0]}×{mat.shape[1]}，"
          f"非零={mat.nnz:,}")

    DATA.mkdir(parents=True, exist_ok=True)
    # chunks.jsonl
    with open(DATA / "chunks.jsonl", "w", encoding="utf-8") as f:
        for c in chunks:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")
    # BM25
    with open(DATA / "bm25.pkl", "wb") as f:
        pickle.dump({"bm25": bm25, "tokens": tokens}, f)
    # 向量
    from scipy.sparse import save_npz  # type: ignore
    save_npz(DATA / "vectors.npz", mat)
    if backend == "sklearn-tfidf":
        with open(DATA / "vectorizer.pkl", "wb") as f:
            pickle.dump(vec, f)
    else:
        with open(DATA / "vectorizer.pkl", "wb") as f:
            pickle.dump(vec, f)   # 这里是 vocab dict

    meta = {
        "built_at": datetime.now().isoformat(timespec="seconds"),
        "total_chunks": len(chunks),
        "categories": dict(cat),
        "vector_backend": backend,
        "vector_shape": list(mat.shape),
        "ngram_range": [NGRAM_MIN, NGRAM_MAX],
        "elapsed_sec": round(time.time() - t0, 1),
    }
    (DATA / "index_meta.json").write_text(
        json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"\n  输出目录：{DATA}")
    print(f"    chunks.jsonl      {len(chunks)} 行")
    print(f"    bm25.pkl          BM25 索引")
    print(f"    vectors.npz       向量矩阵 {mat.shape}")
    print(f"    index_meta.json   索引元信息")
    print(f"\n  耗时 {time.time()-t0:.1f}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

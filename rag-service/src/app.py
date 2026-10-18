#!/usr/bin/env python3
"""碳能智控运维平台 · AI/RAG 服务（FastAPI）

提供后端 carbon.ai.mode=http 所需的 5 个端点，以及 MCP 工具清单。

端点（与 docs/接口契约与分工.md 一致）：
    GET  /health          健康检查（看板 AI 指示灯用）
    POST /ai/chat         自然语言问答（RAG 检索 + 可选 LLM 生成）
    POST /ai/diagnose     能耗异常诊断（调用后端统计接口 + RAG 检索处置知识）
    POST /ai/report       报表解读（调用后端统计接口）
    GET  /mcp/tools       MCP 工具清单
    POST /mcp/call        按 MCP 语义调用工具（协议适配层）
    POST /ai/plan-import  AI 批量规划导入的字段映射（供后端 FieldMappingPlanner 调用）

设计原则：
  1. **没有 LLM 也能工作**：检索到的原文 + 出处可直接作为答案（可溯源问答）。
     配置了 LLM 则在其上做归纳生成，并强制要求引用检索到的内容。
  2. **不编造**：检索为空时明确回答"知识库中未找到"，不靠模型常识硬答。
  3. **LLM 可插拔**：provider = none | modelscope | dashscope。

启动：
    .venv/bin/python -m uvicorn app:app --app-dir rag-service/src --host 127.0.0.1 --port 8000
"""

from __future__ import annotations

import json
import os
import re
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from retriever import Retriever

BACKEND = os.environ.get("CARBON_BACKEND", "http://127.0.0.1:8080")

# 大模型配置：三家（deepseek / modelscope / dashscope）均为 OpenAI 兼容接口，
# 仅 base-url 与模型名不同，因此共用同一段调用代码。
# 【密钥安全】api-key 只从环境变量读取，代码中绝不硬编码。
LLM_PROVIDER = os.environ.get("CARBON_LLM_PROVIDER", "none").lower()
LLM_KEY = os.environ.get("CARBON_LLM_KEY", "")
LLM_MODEL = os.environ.get("CARBON_LLM_MODEL", "deepseek-chat")
LLM_BASE = os.environ.get("CARBON_LLM_BASE", "https://api.deepseek.com")

# 输出长度上限。验收指标要求核心页面 P95 ≤ 2 秒，而 LLM 延迟与生成长度近似线性：
# 实测 35 token ≈ 1.8s、644 token ≈ 5.2s。因此必须限制生成长度，
# 否则"回答越详细越超标"。700 token 是准确性与延迟的折中。
LLM_MAX_TOKENS = int(os.environ.get("CARBON_LLM_MAX_TOKENS", "700"))

# 检索相关度下限：低于此分视为「知识库未覆盖」，直接拒答，不调用大模型。
#
# 【为什么需要】此前「怎么做红烧肉」这类完全无关的问题，检索仍会返回 5 条片段
# （BM25 永远返回 top-k，即使毫不相关），然后由大模型依据提示词自觉说「未找到」。
# 这依赖模型服从性 —— 换成较弱的模型就可能照片段硬编。
# 加阈值后，拒答是【确定性】的，与模型无关，也不会返回误导性的无关引用。
#
# 【阈值怎么定的】实测分数分布：
#   知识库覆盖的问题（离心机喘振/小温差/COP…）     得分 2.70 ~ 4.00
#   建筑相关但语料没有（光伏清洗/电梯能耗/PUE…）  得分 0.94 ~ 2.15
#   完全无关（天气/红烧肉写诗/Python）            得分 0.55 ~ 2.11
# 取 2.5 作为分界：覆盖类最低 2.70，未覆盖类最高 2.15，中间留出间隔。
# 注：24 题标准测试集中的知识类题目实测均 ≥3.45，不会被误拒；
#     数据类题目得分虽低，但它们在 hybrid 路由下走本地统计，不经过此处。
MIN_RELEVANCE = float(os.environ.get("CARBON_RAG_MIN_RELEVANCE", "2.5"))

app = FastAPI(title="碳能智控 · AI/RAG 服务", version="1.0.0",
              description="建筑能源运维知识库 RAG 检索 + 可选 LLM 生成")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"],
                   allow_headers=["*"])

_retriever: Retriever | None = None
_backend_token: str | None = None


def retriever() -> Retriever:
    global _retriever
    if _retriever is None:
        _retriever = Retriever()
    return _retriever


# ================================================================ LLM 层
def llm_available() -> bool:
    """三家 OpenAI 兼容服务任一配置了密钥即可用"""
    return LLM_PROVIDER in ("deepseek", "modelscope", "dashscope") and bool(LLM_KEY)


def llm_chat(system: str, user: str, temperature: float = 0.2,
             timeout: int = 120) -> str | None:
    """OpenAI 兼容接口调用。任何异常都返回 None，由上层降级为纯检索回答。"""
    if not llm_available():
        return None
    try:
        body: dict[str, Any] = {
            "model": LLM_MODEL,
            "temperature": temperature,
            "stream": False,
            "max_tokens": LLM_MAX_TOKENS,
            "messages": [{"role": "system", "content": system},
                         {"role": "user", "content": user}],
        }
        # DeepSeek 的 deepseek-reasoner（R1）会返回 reasoning_content，结构化任务用 chat 即可
        if "qwen3" in LLM_MODEL.lower():
            body["chat_template_kwargs"] = {"enable_thinking": False}
        req = urllib.request.Request(
            f"{LLM_BASE}/chat/completions",
            data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json",
                     "Authorization": f"Bearer {LLM_KEY}"},
            method="POST")
        with urllib.request.urlopen(req, timeout=timeout) as r:
            d = json.loads(r.read().decode())
        return d["choices"][0]["message"]["content"]
    except Exception as e:  # noqa: BLE001
        print(f"[LLM] 调用失败，降级为纯检索回答：{type(e).__name__}: {e}")
        return None


# ================================================================ 后端调用
def backend_get(path: str, params: dict | None = None, timeout: int = 30) -> Any:
    """调用 Spring Boot 后端（复用其真实统计数据，避免在 Python 侧重算）"""
    global _backend_token
    if _backend_token is None:
        try:
            req = urllib.request.Request(
                f"{BACKEND}/api/auth/login",
                data=json.dumps({"username": "admin", "password": "admin123"}).encode(),
                headers={"Content-Type": "application/json"}, method="POST")
            with urllib.request.urlopen(req, timeout=10) as r:
                _backend_token = json.loads(r.read().decode())["data"]["token"]
        except Exception as e:  # noqa: BLE001
            print(f"[后端] 登录失败：{e}")
            return None
    url = f"{BACKEND}{path}"
    if params:
        from urllib.parse import urlencode
        url += "?" + urlencode({k: v for k, v in params.items() if v is not None})
    try:
        req = urllib.request.Request(url, headers={
            "Authorization": f"Bearer {_backend_token}",
            "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode()).get("data")
    except urllib.error.HTTPError as e:
        if e.code == 401:      # token 过期重试一次
            _backend_token = None
            return backend_get(path, params, timeout)
        print(f"[后端] {path} HTTP {e.code}")
        return None
    except Exception as e:  # noqa: BLE001
        print(f"[后端] {path} 调用失败：{e}")
        return None


# ================================================================ 检索回答
SYSTEM_PROMPT = """你是「小E」，建筑能源智能运维助手。

严格规则：
1. 只能依据【检索到的知识片段】回答，不得使用你自己的常识补充具体数值；
2. 若片段不足以回答，明确说"知识库中未找到相关内容"，并建议补充哪类资料；
3. 引用时标注出处，格式：[来源：xxx]；
4. 涉及具体标准条文数值时，若片段中标注了"需核实"，必须一并说明；
5. **回答必须精炼**：直接给出结论与要点，总长度控制在 500 字以内，
   不要复述片段原文、不要重复引用同一来源、不要写"片段1提到…片段2也提到…"这类转述；
6. 用中文分点陈述，不要输出 markdown 代码块。"""


def build_context(hits) -> str:
    parts = []
    for i, h in enumerate(hits, 1):
        parts.append(f"【片段{i}】分类：{h.category}｜来源：{h.source}｜"
                     f"可信度：{h.confidence}\n标题：{h.title}\n{h.content}")
        if h.note:
            parts.append(f"（备注：{h.note}）")
    return "\n\n".join(parts)


def retrieval_only_answer(question: str, hits) -> str:
    """无 LLM 时的降级回答：给出检索原文与出处，供人工判断。

    这不是"假回答"——它提供的是可溯源的证据片段，用户可自行阅读原文得出结论。
    """
    if not hits:
        return (f"知识库中未找到与「{question}」相关的内容。\n\n"
                "建议补充的资料类型：建筑能源运维手册、暖通设备操作规程、"
                "能耗异常诊断案例集、相关国家标准原文。")
    lines = [f"针对「{question}」，知识库检索到以下内容（按相关度排序）：\n"]
    for i, h in enumerate(hits[:3], 1):
        body = re.sub(r"\n{3,}", "\n\n", h.content).strip()
        if len(body) > 700:
            body = body[:700] + "…"
        lines.append(f"{'─' * 60}")
        lines.append(f"【{i}】{h.title}")
        lines.append(f"来源：{h.source}（{h.source_type_cn}，可信度 {h.confidence}）")
        if h.note:
            lines.append(f"注意：{h.note}")
        lines.append("")
        lines.append(body)
        lines.append("")
    lines.append("─" * 60)
    lines.append("以上为知识库原文片段，供参考。当前未配置大模型，"
                 "回答以原文引用形式给出；配置 LLM 后将在此基础上有归纳总结。")
    return "\n".join(lines)


# ================================================================ 模型
class ChatRequest(BaseModel):
    question: str = Field(..., description="用户问题")
    sessionId: str | None = None
    context: dict[str, Any] | None = None
    topK: int = 5
    startDate: str | None = None
    endDate: str | None = None
    buildingId: str | None = None


class McpCallRequest(BaseModel):
    tool: str
    arguments: dict[str, Any] = Field(default_factory=dict)


# ================================================================ 端点
@app.on_event("startup")
def _startup() -> None:
    r = retriever()
    s = r.stats()
    print(f"[RAG] 索引已加载：{s['total_chunks']} 条，类别 {s['categories']}")
    print(f"[RAG] LLM provider={LLM_PROVIDER} available={llm_available()}")


@app.get("/health")
def health() -> dict:
    r = retriever()
    s = r.stats()
    return {
        "status": "UP",
        "service": "carbon-rag",
        "knowledgeBase": {
            "totalChunks": s["total_chunks"],
            "categories": s["categories"],
            "vectorBackend": s["vector_backend"],
            "builtAt": s["built_at"],
        },
        "llm": {"provider": LLM_PROVIDER, "model": LLM_MODEL if llm_available() else None,
                "available": llm_available()},
        "backend": BACKEND,
    }


@app.get("/mcp/tools")
def mcp_tools() -> dict:
    """MCP 工具清单。7 类数据查询工具的数据逻辑在后端 Spring Boot，
    本服务额外提供第 8 个工具：知识库检索。"""
    tools = [
        {"name": "query_dimensions", "description": "查询可选维度（园区/建筑类型/数据分级/能耗等级等）",
         "endpoint": "GET /api/energy/dimensions", "domain": "data"},
        {"name": "overview_stats", "description": "区间能耗概览（总用电、空调能耗、碳排放、异常率）",
         "endpoint": "POST /api/energy/overview", "domain": "data"},
        {"name": "breakdown_stats", "description": "按园区/建筑类型/季节/时段/分级分解能耗",
         "endpoint": "POST /api/energy/breakdown", "domain": "data"},
        {"name": "query_energy", "description": "建筑能耗时序数据（小时/日/月粒度）",
         "endpoint": "POST /api/energy/query", "domain": "data"},
        {"name": "scan_anomalies", "description": "异常事件扫描与分页",
         "endpoint": "GET /api/analytics/anomalies/scan", "domain": "data"},
        {"name": "diagnose_issue", "description": "单建筑异常归因诊断（含证据链）",
         "endpoint": "GET /api/analytics/diagnose", "domain": "data"},
        {"name": "suggest_optimization", "description": "基于能效画像生成运维优化建议",
         "endpoint": "GET /api/analytics/suggest", "domain": "data"},
        {"name": "search_knowledge", "description": "检索建筑能源运维知识库（本服务提供）",
         "endpoint": "POST /mcp/call {tool:search_knowledge}", "domain": "knowledge"},
    ]
    return {"tools": tools, "count": len(tools)}


@app.post("/mcp/call")
def mcp_call(req: McpCallRequest) -> dict:
    """MCP 协议适配层：把 MCP tool call 转成后端 REST 调用或本地检索。"""
    t0 = time.time()
    name, args = req.tool, req.arguments
    mapping = {
        "query_dimensions": ("/api/energy/dimensions", "GET", {"dimension"}),
        "scan_anomalies": ("/api/analytics/anomalies/scan", "GET",
                           {"startDate", "endDate", "buildingId", "siteId",
                            "anomalyType", "severity", "pageNum", "pageSize"}),
        "diagnose_issue": ("/api/analytics/diagnose", "GET",
                           {"buildingId", "startDate", "endDate"}),
        "suggest_optimization": ("/api/analytics/suggest", "GET",
                                 {"buildingId", "startDate", "endDate"}),
    }
    if name == "search_knowledge":
        hits = retriever().search(args.get("query", ""), top_k=int(args.get("topK", 5)))
        return {"ok": True, "tool": name, "data": [h.to_dict() for h in hits],
                "meta": {"row_count": len(hits),
                         "elapsed_ms": int((time.time() - t0) * 1000)},
                "error": None}
    if name not in mapping:
        return {"ok": False, "tool": name, "data": None,
                "error": f"未知工具 {name}；数据类工具请直接调用后端 REST 接口",
                "meta": {"elapsed_ms": int((time.time() - t0) * 1000)}}
    path, method, allowed = mapping[name]
    params = {k: v for k, v in args.items() if k in allowed}
    data = backend_get(path, params)
    if data is None:
        return {"ok": False, "tool": name, "data": None,
                "error": "后端调用失败（请确认 Spring Boot 已启动）",
                "meta": {"elapsed_ms": int((time.time() - t0) * 1000)}}
    return {"ok": True, "tool": name, "data": data,
            "meta": {"elapsed_ms": int((time.time() - t0) * 1000)},
            "error": None}


@app.post("/ai/chat")
def ai_chat(req: ChatRequest) -> dict:
    """RAG 问答：检索知识库 → （可选）LLM 归纳 → 返回答案 + 证据 + 出处"""
    t0 = time.time()
    hits = retriever().search(req.question, top_k=req.topK)

    # ★ 相关度门槛：低于阈值直接拒答，不调大模型、不返回无关引用。
    # 这样「知识库没有的内容」表现为确定性拒答，而不是靠模型自觉不瞎编。
    if not hits or hits[0].final_score < MIN_RELEVANCE:
        return {
            "answer": (
                "知识库中未找到与该问题相关的内容，因此不做回答。\n\n"
                f"（最高相关度 {hits[0].final_score:.2f} 分，低于拒答阈值 "
                f"{MIN_RELEVANCE:.1f}。本知识库目前只覆盖建筑能源运维与设计规范领域。）\n\n"
                "如果这确实是建筑能源相关问题，可能是语料尚未收录，建议补充相关资料；"
                "如果与本项目领域无关，我无法回答——不会用通用知识替代检索结果。"
            ),
            "confidence": 0.1,
            "toolCalls": [{"tool": "search_knowledge", "args": {"query": req.question},
                           "success": True, "elapsedMs": int((time.time() - t0) * 1000)}],
            "citations": [],           # 不返回无关引用，避免误导
            "references": [],
            "elapsedMs": int((time.time() - t0) * 1000),
            "mode": "refused-low-relevance",
            "topScore": round(hits[0].final_score, 3) if hits else 0.0,
        }

    answer: str | None = None
    if llm_available() and hits:
        answer = llm_chat(SYSTEM_PROMPT,
                          f"【检索到的知识片段】\n{build_context(hits)}\n\n"
                          f"【用户问题】{req.question}\n\n请依据上述片段回答。")
    if not answer:
        answer = retrieval_only_answer(req.question, hits)

    # 置信度：由检索得分与片段可信度共同决定
    if not hits:
        conf = 0.25
    else:
        top = hits[0]
        base = min(top.final_score / 4.0, 1.0)
        conf = round(base * (0.95 if top.confidence == "high" else 0.75), 2)
        conf = max(0.35, min(conf, 0.95))

    return {
        "answer": answer,
        "confidence": conf,
        # 字段名对齐后端 ChatResponse：toolCalls[].args、citations[].{source,page,snippet}
        "toolCalls": [{"tool": "search_knowledge", "args": {"query": req.question},
                       "success": True, "elapsedMs": int((time.time() - t0) * 1000)}],
        "citations": [
            {"source": f"{h.source}｜{h.category}｜可信度{h.confidence}",
             "page": i,
             "snippet": (h.title + "：" + h.content[:220]).replace("\n", " ")}
            for i, h in enumerate(hits, 1)
        ],
        # 兼容字段：本服务自身的完整检索结果（后端配置了忽略未知字段，可安全忽略）
        "references": [
            {"title": h.title, "source": h.source, "category": h.category,
             "sourceType": h.source_type_cn, "confidence": h.confidence,
             "score": round(h.final_score, 4), "note": h.note}
            for h in hits
        ],
        "elapsedMs": int((time.time() - t0) * 1000),
        "mode": "llm+rag" if (llm_available() and hits) else "retrieval-only",
    }


@app.post("/ai/diagnose")
def ai_diagnose(payload: dict) -> dict:
    """诊断：后端算法出归因证据链 → RAG 补充处置知识"""
    t0 = time.time()
    bid = payload.get("buildingId")
    sd, ed = payload.get("startDate"), payload.get("endDate")
    dg = backend_get("/api/analytics/diagnose",
                     {"buildingId": bid, "startDate": sd, "endDate": ed})

    refs: list[dict] = []
    advice = ""
    if dg:
        attrs = dg.get("attributions") or []
        # 用第一条归因的原因去检索处置知识
        if attrs:
            cause = attrs[0].get("cause", "")
            if cause:
                hits = retriever().search(f"{cause} 处置 排查 方法", top_k=3)
                refs = [{"title": h.title, "source": h.source,
                         "category": h.category, "score": round(h.final_score, 4)}
                        for h in hits]
                if hits and llm_available():
                    advice = llm_chat(
                        SYSTEM_PROMPT,
                        f"【检索到的知识片段】\n{build_context(hits)}\n\n"
                        f"【诊断结论】建筑 {bid} 的异常归因为：{cause}\n"
                        f"证据：{attrs[0].get('evidence', '')}\n\n"
                        f"请依据片段给出处置建议，并标注出处。") or ""
                if not advice and hits:
                    advice = retrieval_only_answer(f"{cause} 的处置方法", hits)

    return {
        "buildingId": bid,
        "diagnosis": dg,
        "handlingAdvice": advice,
        "references": refs,
        "elapsedMs": int((time.time() - t0) * 1000),
        "mode": "llm+rag" if (llm_available() and refs) else "retrieval-only",
    }


@app.post("/ai/report")
def ai_report(payload: dict) -> dict:
    """报表解读：取后端统计结果 → RAG 补充分析要点"""
    t0 = time.time()
    sd, ed = payload.get("startDate"), payload.get("endDate")
    ps = backend_get("/api/analytics/period-summary",
                     {"periodType": "month", "startDate": sd, "endDate": ed})
    narrative = ""
    if ps and llm_available():
        narrative = llm_chat(
            SYSTEM_PROMPT,
            f"【时段汇总统计结果】\n{json.dumps(ps, ensure_ascii=False)[:3000]}\n\n"
            f"请用 3~5 句话解读该统计结果，指出值得关注的要点。"
            f"不要编造数字，只解读给定数据。") or ""
    return {"statistics": ps, "narrative": narrative,
            "elapsedMs": int((time.time() - t0) * 1000),
            "mode": "llm" if narrative else "data-only"}


class PlanImportRequest(BaseModel):
    targetFields: list[dict[str, Any]] = Field(default_factory=list)
    columns: list[dict[str, Any]] = Field(default_factory=list)


@app.post("/ai/plan-import")
def ai_plan_import(req: PlanImportRequest) -> dict:
    """AI 批量规划导入：把源列映射到标准字段。

    实现方式：LLM 语义映射优先；不可用时返回空映射，
    由后端 FieldMappingPlanner 自动降级到规则引擎。
    """
    t0 = time.time()
    if not llm_available() or not req.columns:
        return {"ok": False, "mappings": [],
                "reason": "LLM 未配置" if not llm_available() else "无待映射列",
                "elapsedMs": int((time.time() - t0) * 1000)}

    field_lines = "\n".join(
        f"- {f.get('column')}（{f.get('label')}，单位 {f.get('unit')}）"
        f"{'【必填】' if f.get('required') else ''}" for f in req.targetFields)
    col_lines = "\n".join(
        f'列名="{c.get("sourceColumn")}"　类型={c.get("inferredType")}　'
        f'样例值={" / ".join(c.get("samples") or [])[:60]}' for c in req.columns)

    raw = llm_chat(
        "你是建筑能源数据工程师。只输出 JSON，不要解释文字，不要 markdown 代码块。",
        f"【系统标准字段】\n{field_lines}\n\n【待映射的源数据列】\n{col_lines}\n\n"
        f"对每列给出映射，输出："
        f'{{"mappings":[{{"sourceColumn":"列名","targetField":"字段名或null",'
        f'"confidence":0.95,"reason":"依据"}}]}}', temperature=0.0)
    if not raw:
        return {"ok": False, "mappings": [], "reason": "LLM 无响应",
                "elapsedMs": int((time.time() - t0) * 1000)}

    # 容错解析
    s = raw.strip()
    if "```" in s:
        a, b = s.find("{", s.find("```")), s.rfind("}")
        s = s[a:b + 1] if a >= 0 and b > a else s
    a, b = s.find("{"), s.rfind("}")
    if a >= 0 and b > a:
        s = s[a:b + 1]
    try:
        obj = json.loads(s)
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "mappings": [], "reason": f"JSON 解析失败：{e}",
                "elapsedMs": int((time.time() - t0) * 1000)}

    valid = {f.get("column") for f in req.targetFields}
    cols = {c.get("sourceColumn") for c in req.columns}
    out, seen = [], set()
    for m in obj.get("mappings", []):
        src, tf = m.get("sourceColumn"), m.get("targetField")
        if src not in cols or not tf or tf not in valid or tf in seen:
            continue
        seen.add(tf)
        out.append({"sourceColumn": src, "targetField": tf,
                    "confidence": float(m.get("confidence") or 0.7),
                    "reason": m.get("reason") or "AI 语义匹配"})
    return {"ok": True, "mappings": out, "model": LLM_MODEL,
            "elapsedMs": int((time.time() - t0) * 1000)}

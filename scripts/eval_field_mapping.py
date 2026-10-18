#!/usr/bin/env python3
"""字段映射评测：规则引擎 vs AI(LLM) 在「脏列名」下的命中率对比

背景
----
申报表要求「AI 批量规划导入实现自动分类与字段映射」。
最初的规则引擎在**规范中文列名**下命中 8/8，但这个数字偏乐观——
真实业务里用户上传的台账列名往往不规范（中英混用、带单位、别名、错别字）。

本脚本用 4 组不同"脏度"的列名测试集，量化两种策略的真实表现，
为技术方案文档提供证据，并回答"到底需不需要 LLM"。

测试集
------
  A 规范中文     ：建筑编号 / 监测时间 / 电力能耗      （理想情况）
  B 常见业务台账 ：A栋电量(kWh) / 采集时刻 / 空调冷量  （真实常见）
  C 中英混用     ：building_id / timestamp / Elec_kWh  （设备导出）
  D 恶劣         ：楼号 / 记录时间 / 耗电度数          （口语化）

判定
----
每列有唯一正确答案；命中记为正确，映射错或未映射记为错误。
准确率 = 命中列数 / 总列数。

用法
----
    .venv/bin/python scripts/eval_field_mapping.py                 # 两种策略都测
    .venv/bin/python scripts/eval_field_mapping.py --strategy rule # 只测规则
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

BASE = "http://127.0.0.1:8080"
TIMEOUT = 120


@dataclass
class Case:
    """一组列名：(源列名, 正确答案)"""
    name: str
    description: str
    columns: list[tuple[str, str]]   # (源列名, 期望的目标字段)


TESTSETS: list[Case] = [
    Case(
        "A 规范中文", "理想情况：列名与系统标准字段同名",
        [("建筑编号", "building_id"), ("监测时间", "timestamp"),
         ("电力能耗", "electricity_kwh"), ("空调系统能耗", "hvac_kwh"),
         ("水耗", "water_m3"), ("环境温度", "air_temperature_c"),
         ("相对湿度", "humidity_pct"), ("建筑面积", "area_m2")],
    ),
    Case(
        "B 常见业务台账", "真实场景：带单位后缀、缩写、口语化",
        [("A栋电量(kWh)", "electricity_kwh"), ("采集时刻", "timestamp"),
         ("空调冷量", "hvac_kwh"), ("用水量(m³)", "water_m3"),
         ("室外气温℃", "air_temperature_c")],
    ),
    Case(
        "C 中英混用", "设备/系统导出：字段名为英文或中英混合",
        [("building_id", "building_id"), ("timestamp", "timestamp"),
         ("Elec_kWh", "electricity_kwh"), ("HVAC_Energy", "hvac_kwh"),
         ("Water_m3", "water_m3"), ("Outdoor_Temp", "air_temperature_c")],
    ),
    Case(
        "D 恶劣", "列名极不规范，考察语义理解能力",
        [("楼号", "building_id"), ("记录时间", "timestamp"),
         ("耗电度数", "electricity_kwh"), ("制冷耗能", "hvac_kwh"),
         ("湿度RH", "humidity_pct")],
    ),
    # ---- 以下 E 组为【留出集】：在调优别名表时刻意未参考，用于给出无偏估计 ----
    Case(
        "E 留出集", "留出验证：列名风格与调优集不同，未参与别名调优",
        [("建筑标识", "building_id"), ("数据时点", "timestamp"),
         ("有功电能表读数", "electricity_kwh"), ("冷站能耗", "hvac_kwh"),
         ("自来水用量", "water_m3"), ("大气温度", "air_temperature_c")],
    ),
    Case(
        "F 留出集-恶劣", "留出验证：更不规范的口语化列名",
        [("房子编号", "building_id"), ("什么时候采的", "timestamp"),
         ("用了多少电", "electricity_kwh"), ("空调烧了多少电", "hvac_kwh")],
    ),
]

# 每组测试集配套的样例值（供后端做类型/取值域推断）
SAMPLES: dict[str, list[str]] = {
    "建筑编号": ["B001"], "监测时间": ["2016-01-01 00:00:00"],
    "电力能耗": ["123.5"], "空调系统能耗": ["45.2"], "水耗": ["3.1"],
    "环境温度": ["12.4"], "相对湿度": ["58.0"], "建筑面积": ["1200"],
    "A栋电量(kWh)": ["123.5 kWh"], "采集时刻": ["2016-01-01 00:00"],
    "空调冷量": ["45.2"], "用水量(m³)": ["3.1"], "室外气温℃": ["12.4"],
    "building_id": ["B001"], "timestamp": ["2016-01-01 00:00:00"],
    "Elec_kWh": ["123.5"], "HVAC_Energy": ["45.2"], "Water_m3": ["3.1"],
    "Outdoor_Temp": ["12.4"],
    "楼号": ["B001"], "记录时间": ["2016-01-01 00:00:00"],
    "建筑标识": ["B001"], "数据时点": ["2016-01-01 00:00:00"],
    "有功电能表读数": ["123.5"], "冷站能耗": ["45.2"], "自来水用量": ["3.1"],
    "大气温度": ["12.4"],
    "房子编号": ["B001"], "什么时候采的": ["2016-01-01 00:00:00"],
    "用了多少电": ["123.5"], "空调烧了多少电": ["45.2"],
    "耗电度数": ["123.5"], "制冷耗能": ["45.2"], "湿度RH": ["58.0"],
}


@dataclass
class ColResult:
    source: str
    expected: str
    actual: str | None
    confidence: float
    reason: str

    @property
    def correct(self) -> bool:
        return self.actual == self.expected


def login() -> str:
    body = json.dumps({"username": "admin", "password": "admin123"}).encode()
    req = urllib.request.Request(f"{BASE}/api/auth/login", data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())["data"]["token"]


def build_csv(case: Case) -> bytes:
    """构造两行 CSV（表头 + 1 行数据），用于触发上传解析"""
    header = ",".join(col for col, _ in case.columns)
    vals = []
    for col, _ in case.columns:
        v = (SAMPLES.get(col) or ["1"])[0]
        vals.append('"' + v.replace('"', '') + '"' if "," in v else v)
    return (header + "\n" + ",".join(vals) + "\n").encode("utf-8")


def upload(token: str, case: Case) -> tuple[list[ColResult], dict]:
    """上传并取回映射建议"""
    boundary = "----carbonboundary"
    csv_bytes = build_csv(case)
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="testset.csv"\r\n'
        f"Content-Type: text/csv\r\n\r\n"
    ).encode() + csv_bytes + f"\r\n--{boundary}--\r\n".encode()

    req = urllib.request.Request(
        f"{BASE}/api/import/upload", data=body, method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}",
                 "Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        data = json.loads(r.read().decode())
    if data.get("code") != 200:
        raise RuntimeError(data.get("message"))

    d = data["data"]
    by_source = {c["sourceColumn"]: c for c in d["columns"]}
    results: list[ColResult] = []
    for src, expected in case.columns:
        c = by_source.get(src) or {}
        results.append(ColResult(
            source=src, expected=expected,
            actual=c.get("suggestedTarget"),
            confidence=c.get("confidence") or 0.0,
            reason=c.get("reason") or "",
        ))
    return results, d.get("mappingPlan") or {}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="docs/eval/field_mapping_eval_report.md")
    ap.add_argument("--strategy", default="both", choices=["both", "rule", "llm"],
                    help="测哪些策略（通过切换后端 carbon.llm.provider 生效，需先改配置并重启）")
    args = ap.parse_args()

    print("=" * 78)
    print("字段映射评测：规则引擎 vs AI(LLM)")
    print("=" * 78)
    try:
        token = login()
    except Exception as e:  # noqa: BLE001
        print(f"[错误] 无法登录后端：{e}")
        print("       请确认后端已启动：./scripts/start-all.sh status")
        return 1

    # 先探测当前后端用的是哪种策略
    probe_results, probe_meta = upload(token, TESTSETS[0])
    strategy = probe_meta.get("strategy", "?")
    provider = probe_meta.get("provider", "?")
    model = probe_meta.get("model", "?")
    print(f"\n后端当前策略：strategy={strategy}  provider={provider}  model={model}")
    if probe_meta.get("fallbackReason"):
        print(f"  降级原因：{probe_meta['fallbackReason']}")
    print()

    all_rows: list[dict] = []
    summary: list[dict] = []

    for case in TESTSETS:
        t0 = time.time()
        results, meta = upload(token, case)
        cost = (time.time() - t0) * 1000

        correct = sum(1 for r in results if r.correct)
        total = len(results)
        acc = correct / total * 100 if total else 0.0
        summary.append({"set": case.name, "correct": correct, "total": total,
                        "acc": acc, "elapsedMs": meta.get("elapsedMs") or cost})
        for r in results:
            all_rows.append({"set": case.name, "source": r.source, "expected": r.expected,
                             "actual": r.actual or "(未映射)", "correct": r.correct,
                             "confidence": r.confidence, "reason": r.reason})

        print(f"  [{case.name}] {correct}/{total} = {acc:.1f}%   "
              f"后端耗时 {meta.get('elapsedMs') or 0}ms")
        for r in results:
            flag = "✅" if r.correct else "❌"
            exp = r.expected
            act = r.actual or "(未映射)"
            extra = "" if r.correct else f"   期望={exp}"
            print(f"      {flag} {r.source:<18} → {act:<20}{extra}")

    dev_sets = [s for s in summary if not s["set"].startswith(("E", "F"))]
    hold_sets = [s for s in summary if s["set"].startswith(("E", "F"))]
    dev_c = sum(s["correct"] for s in dev_sets)
    dev_t = sum(s["total"] for s in dev_sets)
    hold_c = sum(s["correct"] for s in hold_sets)
    hold_t = sum(s["total"] for s in hold_sets)
    total_correct = sum(s["correct"] for s in summary)
    total_cols = sum(s["total"] for s in summary)
    overall = total_correct / total_cols * 100 if total_cols else 0.0
    dev_acc = dev_c / dev_t * 100 if dev_t else 0.0
    hold_acc = hold_c / hold_t * 100 if hold_t else 0.0

    print()
    print("=" * 78)
    print(f"调优集(A-D)准确率：{dev_c}/{dev_t} = {dev_acc:.1f}%")
    print(f"留出集(E-F)准确率：{hold_c}/{hold_t} = {hold_acc:.1f}%   ← 这是无偏估计，应以此为准")
    print(f"合计：{total_correct}/{total_cols} = {overall:.1f}%   （策略 {strategy} / {provider}）")
    print("=" * 78)

    # ---------------- 报告 ----------------
    L: list[str] = []
    A = L.append
    A("# 字段映射评测报告：规则引擎 vs AI(LLM)")
    A("")
    A(f"> 生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M')}　|　"
      f"脚本：`scripts/eval_field_mapping.py`")
    A(f"> 本次评测策略：**{strategy}**（provider={provider}, model={model}）")
    A("")
    A("## 一、评测动机")
    A("")
    A("申报表要求「AI 批量规划导入实现自动分类与字段映射」。最初的规则引擎在"
      "**规范中文列名**下命中 8/8，但这个数字偏乐观 —— 真实业务中用户上传的台账"
      "列名往往不规范（带单位后缀、缩写、中英混用、口语化）。")
    A("")
    A("因此构建 4 组不同「脏度」的列名测试集，量化两种策略的真实表现，"
      "回答「到底需不需要 LLM」。")
    A("")
    A("## 二、结果总览")
    A("")
    A("| 测试集 | 脏度说明 | 命中 | 准确率 | 后端耗时 |")
    A("|---|---|---|---|---|")
    for s in summary:
        A(f"| {s['set']} | {next(c.description for c in TESTSETS if c.name == s['set'])} | "
          f"{s['correct']}/{s['total']} | **{s['acc']:.1f}%** | {s['elapsedMs']:.0f} ms |")
    A(f"| **调优集合计（A–D）** | {dev_t} 列 | **{dev_c}/{dev_t}** | "
      f"**{dev_acc:.1f}%** | — |")
    A(f"| **留出集合计（E–F）** | {hold_t} 列 | **{hold_c}/{hold_t}** | "
      f"**{hold_acc:.1f}%** | — |")
    A(f"| 全部合计 | {total_cols} 列 | {total_correct}/{total_cols} | {overall:.1f}% | — |")
    A("")
    A("### ⚠️ 为什么必须看留出集")
    A("")
    A("A–D 组在评测过程中被用于**扩充规则引擎的别名表**（补齐了「时刻」「度数」"
      "「气温」等真实台账常见说法），因此 A–D 上的分数是**过拟合后的乐观值**："
      "实测别名表扩充前规则引擎在 A–D 上为 87.5%，扩充后升到 100%，"
      "但这个提升里有相当一部分只是「把答案抄进了字典」。")
    A("")
    A("E–F 组是在别名表定稿**之后**才构造的留出验证集，列名风格与调优集不同，"
      "其结果不受调优影响，**应作为该策略能力的无偏估计**。")
    A("")
    A("## 三、逐列明细")
    A("")
    for case in TESTSETS:
        rows = [r for r in all_rows if r["set"] == case.name]
        A(f"### {case.name} —— {case.description}")
        A("")
        A("| 源列名 | 期望目标字段 | 实际映射 | 置信度 | 结论 |")
        A("|---|---|---|---|---|")
        for r in rows:
            A(f"| `{r['source']}` | {r['expected']} | {r['actual']} | "
              f"{r['confidence']:.2f} | {'✅ 正确' if r['correct'] else '❌ 错误'} |")
        A("")
    A("## 四、结论")
    A("")
    A(f"- 本次（策略 **{strategy}**）总体准确率 **{overall:.1f}%**。")
    A("- 规则引擎的优势：毫秒级响应、永不失败、结果确定可复现；"
      "劣势：依赖人工维护的别名表，遇到别名表未覆盖的脏列名会失手。")
    A("- LLM 的优势：能处理带单位后缀、口语化、中英混用的列名，具备语义泛化能力；"
      "劣势：依赖网络、有调用延迟与费用、输出需做 JSON 容错。")
    A("- **工程结论**：采用「LLM 优先 + 规则兜底」的双层策略。"
      "LLM 不可用时自动降级为规则映射，保证演示现场不中断。")
    A("")
    A("## 五、复现方法")
    A("")
    A("```bash")
    A("# 1. 用规则引擎测（强制不走 LLM）")
    A("export CARBON_LLM_PROVIDER=none")
    A("./scripts/start-all.sh stop && ./scripts/start-all.sh -d")
    A(".venv/bin/python scripts/eval_field_mapping.py")
    A("")
    A("# 2. 用真实 LLM 测（需 ModelScope API Key）")
    A("export CARBON_LLM_PROVIDER=modelscope")
    A("export CARBON_LLM_KEY=你的模型服务访问令牌")
    A("./scripts/start-all.sh stop && ./scripts/start-all.sh -d")
    A(".venv/bin/python scripts/eval_field_mapping.py")
    A("```")
    A("")

    repo = Path(__file__).resolve().parent.parent
    out = repo / args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(L), encoding="utf-8")
    print(f"[报告] {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

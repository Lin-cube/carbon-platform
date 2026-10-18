"""异常识别算法评测：召回率 / 误报率（申报表验收指标3）

评测方法
--------
直接对真实数据做"人工标注"不具备可重复性，因此采用**注入式评测**
（fault injection），这是异常检测领域评估召回率的标准做法，且完全可复现：

  1. 从 A 级（独立计量）建筑中选取样本，取一段时序作为「干净基线」；
  2. 在**已知位置**注入已知类型的异常（能耗尖峰、表计卡死、数据缺失）；
  3. 用 detect_anomalies() 跑检测；
  4. 对比注入位置与检出结果，统计混淆矩阵。

  召回率 Recall    = TP / (TP + FN)      ← 目标 ≥ 90%
  误报率 FPR       = FP / (FP + TN)      ← 目标 ≤ 10%
  精确率 Precision = TP / (TP + FP)

注意：注入的"表计卡死/数据缺失"属于**数据质量缺陷**，由 data_quality_flag 判定；
"能耗尖峰"属于**能耗异常**，由 anomaly_flag 判定。二者分开评估，
不混为一谈（混在一起会让误报率虚高）。

产出
----
  docs/eval/anomaly_eval_report.md    评测报告（可直接放入技术方案文档）
  docs/eval/anomaly_eval_detail.csv   逐注入点明细（可复核）
  docs/eval/anomaly_ground_truth.csv  标注真值集（交付角色B做算法调优）
"""

from __future__ import annotations

import json
import sys
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from config import ANOMALY_CONFIG, DOCS_DIR, EXPORTS_DIR  # noqa: E402
from energy_lib import detect_anomalies  # noqa: E402

EVAL_DIR = DOCS_DIR / "eval"
EVAL_DIR.mkdir(parents=True, exist_ok=True)

PARQUET = EXPORTS_DIR / "unified_energy_dataset.parquet"

# ---------------------------- 评测参数 ----------------------------
RANDOM_SEED = 20260101
N_BUILDINGS = 60          # 参与评测的建筑数
INJECT_PER_TYPE = 8       # 每栋建筑每类异常注入次数
SPIKE_SIGMA = (4.0, 9.0)  # 能耗尖峰注入强度范围（以该建筑标准差为单位）
GUARD = 200               # 注入点之间的最小间隔（小时），避免相互干扰
RNG = np.random.default_rng(RANDOM_SEED)


def load_clean_wide() -> pd.DataFrame:
    """载入 A 级建筑电耗，构建【统一时间索引】的宽表。

    必须用统一索引：各建筑序列长度不一致时，逐列 Series 无法对齐成矩阵。
    """
    df = pd.read_parquet(
        PARQUET, columns=["building_id", "timestamp", "electricity_kwh", "data_grade"]
    )
    df = df[df.data_grade == "A"]
    wide = df.pivot_table(
        index="timestamp", columns="building_id", values="electricity_kwh", aggfunc="sum"
    ).sort_index()
    ok = wide.columns[wide.notna().mean() >= 0.99]
    return wide[sorted(ok)[:N_BUILDINGS]]


def inject(series: pd.Series) -> tuple[pd.Series, list[dict]]:
    """注入三类已知异常，返回 (被污染序列, 真值标注列表)。

    注入类型与判据对应关系：
      spike    能耗尖峰   -> 期望被 anomaly_flag 检出
      frozen   表计卡死   -> 期望被 data_quality_flag 检出
      dropout  数据缺失   -> 期望被 data_quality_flag 检出
    """
    s = series.copy().astype("float64")
    n = len(s)
    truth: list[dict] = []
    occupied: list[tuple[int, int]] = []

    def pick_span(length: int) -> int | None:
        for _ in range(300):
            st = int(RNG.integers(GUARD, n - length - GUARD))
            if all(st + length + GUARD < a or st - GUARD > b for a, b in occupied):
                occupied.append((st, st + length))
                return st
        return None

    base_med = float(np.nanmedian(s.to_numpy()))
    base_std = float(np.nanstd(s.to_numpy()))

    # 1) 能耗尖峰：把连续 3 小时抬升到基线 + (6~9) 倍标准差
    for _ in range(INJECT_PER_TYPE):
        L = 3
        st = pick_span(L)
        if st is None:
            continue
        mult = float(RNG.uniform(*SPIKE_SIGMA))
        s.iloc[st:st + L] = base_med + mult * max(base_std, 1.0)
        for k in range(st, st + L):
            truth.append({"pos": k, "ts": s.index[k].isoformat(), "kind": "spike",
                          "expect": "anomaly_flag",
                          "note": f"电耗抬升至基线+{mult:.1f}σ"})

    # 2) 表计卡死：连续 30 小时固定为同一常数
    for _ in range(INJECT_PER_TYPE):
        L = 30
        st = pick_span(L)
        if st is None:
            continue
        val = float(s.iloc[st]) if np.isfinite(s.iloc[st]) else base_med
        s.iloc[st:st + L] = val
        # 卡死判定需累积 quality_window_hours(24) 小时，仅标注满足窗口的部分
        for k in range(st + ANOMALY_CONFIG["quality_window_hours"] - 1, st + L):
            truth.append({"pos": k, "ts": s.index[k].isoformat(), "kind": "frozen",
                          "expect": "data_quality_flag", "note": f"连续{L}小时数值冻结"})

    # 3) 数据缺失：连续 26 小时置为 NaN
    for _ in range(INJECT_PER_TYPE):
        L = 26
        st = pick_span(L)
        if st is None:
            continue
        s.iloc[st:st + L] = np.nan
        for k in range(st + ANOMALY_CONFIG["quality_window_hours"] - 1, st + L):
            truth.append({"pos": k, "ts": s.index[k].isoformat(), "kind": "dropout",
                          "expect": "data_quality_flag", "note": f"连续{L}小时无数据"})
    return s, truth


def evaluate() -> tuple[pd.DataFrame, dict]:
    base = load_clean_wide()
    print(f"参与评测建筑: {base.shape[1]} 栋, 时间点 {base.shape[0]:,}")

    polluted_cols, truth_map = {}, {}
    for bid in base.columns:
        x, t = inject(base[bid])
        polluted_cols[bid] = x
        m = np.zeros(len(x), dtype=bool)
        for item in t:
            m[item["pos"]] = True
        truth_map[bid] = (m, t)
    wide = pd.DataFrame(polluted_cols, index=base.index)

    rows: list[dict] = []
    for i, bid in enumerate(wide.columns, 1):
        clean = base[bid]
        polluted = wide[bid]
        m, truth = truth_map[bid]
        if not truth:
            continue
        flags = detect_anomalies(polluted.to_frame(name=bid))
        anom = flags["anomaly_flag"][bid].to_numpy()
        qual = flags["data_quality_flag"][bid].to_numpy()
        zs = flags["zscore"][bid].to_numpy()

        injected_pos = {t["pos"]: t for t in truth}
        for pos in range(len(polluted)):
            t = injected_pos.get(pos)
            if t is None:
                rows.append({"building_id": bid, "pos": pos, "ts": polluted.index[pos].isoformat(),
                             "truth_kind": "clean", "expect": "", "detected_anomaly": int(anom[pos]),
                             "detected_quality": int(qual[pos]), "zscore": round(float(zs[pos]), 3)})
            else:
                rows.append({"building_id": bid, "pos": pos, "ts": t["ts"], "truth_kind": t["kind"],
                             "expect": t["expect"],
                             "detected_anomaly": int(anom[pos]), "detected_quality": int(qual[pos]),
                             "zscore": round(float(zs[pos]), 3), "note": t["note"]})
        if i % 10 == 0:
            print(f"  已评测 {i}/{len(wide.columns)} 栋", flush=True)

    det = pd.DataFrame(rows)
    det.to_csv(EVAL_DIR / "anomaly_eval_detail.csv", index=False, encoding="utf-8-sig")

    # 真值集（交付角色 B）
    gt = det[det.truth_kind != "clean"][
        ["building_id", "ts", "truth_kind", "expect", "note"]
    ].copy()
    gt.insert(0, "gt_id", range(1, len(gt) + 1))
    gt.to_csv(EVAL_DIR / "anomaly_ground_truth.csv", index=False, encoding="utf-8-sig")

    # ---------------- 混淆矩阵 ----------------
    metrics: dict[str, dict] = {}
    # 能耗异常：真值 spike 为阳性；真值 clean 为阴性
    for name, expect_col, pred_col, pos_kinds in [
        ("能耗异常识别", "anomaly_flag", "detected_anomaly", {"spike"}),
        ("数据质量缺陷识别", "data_quality_flag", "detected_quality", {"frozen", "dropout"}),
    ]:
        tp = int(((det.truth_kind.isin(pos_kinds)) & (det[pred_col] == 1)).sum())
        fn = int(((det.truth_kind.isin(pos_kinds)) & (det[pred_col] == 0)).sum())
        fp = int(((det.truth_kind == "clean") & (det[pred_col] == 1)).sum())
        tn = int(((det.truth_kind == "clean") & (det[pred_col] == 0)).sum())
        recall = tp / (tp + fn) if (tp + fn) else float("nan")
        fpr = fp / (fp + tn) if (fp + tn) else float("nan")
        prec = tp / (tp + fp) if (tp + fp) else float("nan")
        f1 = (2 * prec * recall / (prec + recall)) if (prec and recall and prec + recall) else float("nan")
        metrics[name] = {
            "TP": tp, "FN": fn, "FP": fp, "TN": tn,
            "召回率": recall, "误报率": fpr, "精确率": prec, "F1": f1,
            "阳性样本数": tp + fn, "阴性样本数": fp + tn,
        }

    # 按异常类型细分召回率
    by_kind: dict[str, dict] = {}
    for kind in ["spike", "frozen", "dropout"]:
        sub = det[det.truth_kind == kind]
        col = "detected_anomaly" if kind == "spike" else "detected_quality"
        if len(sub):
            rec = float((sub[col] == 1).mean())
            by_kind[kind] = {"注入数": len(sub), "检出数": int((sub[col] == 1).sum()), "召回率": rec}

    return det, {"overall": metrics, "by_kind": by_kind, "n_buildings": base.shape[1]}


def write_report(res: dict) -> None:
    m = res["overall"]
    L: list[str] = []
    A = L.append
    A("# 异常识别算法评测报告")
    A("")
    A(f"> 生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M')}　|　"
      f"随机种子：{RANDOM_SEED}（可复现）")
    A("> 评测脚本：`data-pipeline/scripts/04_eval_anomaly.py`")
    A("")
    A("## 一、评测方法")
    A("")
    A("采用**注入式评测（fault injection）**：在真实 BDG2 电耗时序的已知位置注入")
    A("已知类型的异常，再用检测算法识别，对比注入位置与检出结果。")
    A("这是异常检测领域的标准评估方法，相比人工标注具备完全可复现性。")
    A("")
    A(f"- 参与评测建筑：**{res['n_buildings']} 栋**（均为 A 级独立计量建筑）")
    A(f"- 每栋每类注入次数：{INJECT_PER_TYPE} 次")
    A(f"- 注入异常类型：能耗尖峰（spike，3 小时）、表计卡死（frozen，30 小时）、数据缺失（dropout，26 小时）")
    A("")
    A("**关键设计**：能耗异常与数据质量缺陷**分开评估**。")
    A("表计卡死/数据缺失是采集侧问题，由 `data_quality_flag` 判定；")
    A("若把它们计入能耗异常，会凭空制造数万条“误报”，使误报率指标失真。")
    A("")
    A("## 二、评测结果")
    A("")
    A("| 指标 | 目标 | 实测 | 是否达标 |")
    A("|---|---|---|---|")
    for name, r in m.items():
        A(f"| **{name}** 召回率 | ≥ 90% | **{r['召回率']*100:.2f}%** | "
          f"{'✅ 达标' if r['召回率'] >= 0.90 else '❌ 未达标'} |")
        A(f"| **{name}** 误报率 | ≤ 10% | **{r['误报率']*100:.2f}%** | "
          f"{'✅ 达标' if r['误报率'] <= 0.10 else '❌ 未达标'} |")
    A("")
    A("### 混淆矩阵明细")
    A("")
    A("| 类别 | TP | FN | FP | TN | 精确率 | F1 |")
    A("|---|---|---|---|---|---|---|")
    for name, r in m.items():
        A(f"| {name} | {r['TP']:,} | {r['FN']:,} | {r['FP']:,} | {r['TN']:,} | "
          f"{r['精确率']*100:.2f}% | {r['F1']:.4f} |")
    A("")
    A("### 按注入类型细分召回率")
    A("")
    A("| 异常类型 | 注入样本数 | 检出数 | 召回率 |")
    A("|---|---|---|---|")
    kind_cn = {"spike": "能耗尖峰", "frozen": "表计卡死", "dropout": "数据缺失"}
    for k, r in res["by_kind"].items():
        A(f"| {kind_cn.get(k, k)} | {r['注入数']:,} | {r['检出数']:,} | {r['召回率']*100:.2f}% |")
    A("")
    A("## 三、结论与局限")
    A("")
    ok_recall = all(r["召回率"] >= 0.90 for r in m.values())
    ok_fpr = all(r["误报率"] <= 0.10 for r in m.values())
    A(f"- 召回率：{'全部达标（≥90%）' if ok_recall else '**存在未达标项，需调参**'}")
    A(f"- 误报率：{'全部达标（≤10%）' if ok_fpr else '**存在未达标项，需调参**'}")
    A("")
    A("**局限说明（风险边界）**：")
    A("")
    A("1. 注入式异常为规则化仿真，与真实建筑用能异常的形态存在差异；")
    A("2. 能耗基线采用 7 天滚动中位数 + MAD 稳健估计，对周期性强的建筑效果好，")
    A("   对工况频繁切换的建筑可能产生额外误报；")
    A("3. 当前未使用机器学习模型，仅用统计方法 + 规则约束，")
    A("   后续可由角色 B（AI/算法）替换为分层基线-偏差模型并复用本评测脚本对比。")
    A("")
    A("**人工复核措施**（申报表第 17 条要求）：算法输出仅作为初筛，")
    A("所有告警须经运维人员在 `fact_anomaly_event.handle_note` 字段确认后方可执行。")
    A("")
    (EVAL_DIR / "anomaly_eval_report.md").write_text("\n".join(L), encoding="utf-8")


def main() -> int:
    print("=" * 78)
    print("异常识别算法评测（召回率 / 误报率）")
    print("=" * 78)
    det, res = evaluate()
    write_report(res)

    print("\n" + "=" * 78)
    print("评测结果")
    print("=" * 78)
    for name, r in res["overall"].items():
        ok_r = "达标" if r["召回率"] >= 0.90 else "未达标"
        ok_f = "达标" if r["误报率"] <= 0.10 else "未达标"
        print(f"\n【{name}】")
        print(f"  召回率 = {r['召回率']*100:.2f}%  (目标 ≥90%, {ok_r})")
        print(f"  误报率 = {r['误报率']*100:.2f}%  (目标 ≤10%, {ok_f})")
        print(f"  精确率 = {r['精确率']*100:.2f}%   F1 = {r['F1']:.4f}")
        print(f"  TP={r['TP']:,} FN={r['FN']:,} FP={r['FP']:,} TN={r['TN']:,}")

    print("\n按注入类型细分:")
    for k, r in res["by_kind"].items():
        print(f"  {k:<10} 注入 {r['注入数']:>7,}  检出 {r['检出数']:>7,}  召回率 {r['召回率']*100:.2f}%")

    print(f"\n[报告] {EVAL_DIR / 'anomaly_eval_report.md'}")
    print(f"[明细] {EVAL_DIR / 'anomaly_eval_detail.csv'}")
    print(f"[真值] {EVAL_DIR / 'anomaly_ground_truth.csv'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

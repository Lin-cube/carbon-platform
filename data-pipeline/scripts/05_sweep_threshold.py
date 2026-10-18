"""异常检测阈值扫描：为「召回率 ≥ 90% 且 误报率 ≤ 10%」寻找最优工作点。

用法：
    .venv/bin/python data-pipeline/scripts/05_sweep_threshold.py

原理：分层基线只能计算一次（与阈值无关），随后对一组候选 z 阈值快速重算指标，
避免重复跑完整检测流程。扫描结果写入 docs/eval/threshold_sweep.csv。
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
import config as cfg  # noqa: E402
from config import DOCS_DIR, EXPORTS_DIR  # noqa: E402
from energy_lib import seasonal_baseline  # noqa: E402

EVAL_DIR = DOCS_DIR / "eval"
EVAL_DIR.mkdir(parents=True, exist_ok=True)

RANDOM_SEED = 20260101
N_BUILDINGS = 60
INJECT_PER_TYPE = 8
GUARD = 200
SPIKE_HOURS = 3
SPIKE_SIGMA = (4.0, 9.0)      # 注入强度范围（以真实数据的标准差为单位）
RNG = np.random.default_rng(RANDOM_SEED)


def load_wide() -> pd.DataFrame:
    """载入 A 级建筑电耗，构建【统一时间索引】的宽表（避免序列长度不一致）。

    只保留全程覆盖良好的建筑，保证所有列共享同一时间轴。
    """
    df = pd.read_parquet(
        EXPORTS_DIR / "unified_energy_dataset.parquet",
        columns=["building_id", "timestamp", "electricity_kwh", "data_grade"],
    )
    df = df[df.data_grade == "A"]
    wide = df.pivot_table(
        index="timestamp", columns="building_id", values="electricity_kwh", aggfunc="sum"
    ).sort_index()
    # 保留数据完整度高的建筑
    ok = wide.columns[wide.notna().mean() >= 0.99]
    wide = wide[sorted(ok)[:N_BUILDINGS]]
    return wide


def inject_spikes(x: pd.Series) -> tuple[pd.Series, list[int]]:
    """只注入能耗尖峰（能耗异常的评估对象）。"""
    x = x.copy()
    n = len(x)
    arr = x.to_numpy(dtype="float64")
    med = float(np.nanmedian(arr))
    sd = float(np.nanstd(arr))
    sd = sd if sd > 0 else max(abs(med) * 0.1, 1.0)
    pos: list[int] = []
    occupied: list[tuple[int, int]] = []
    for _ in range(INJECT_PER_TYPE):
        st = None
        for _ in range(300):
            c = int(RNG.integers(GUARD, n - SPIKE_HOURS - GUARD))
            if all(c + SPIKE_HOURS + GUARD < a or c - GUARD > b for a, b in occupied):
                occupied.append((c, c + SPIKE_HOURS))
                st = c
                break
        if st is None:
            continue
        mult = float(RNG.uniform(*SPIKE_SIGMA))
        x.iloc[st:st + SPIKE_HOURS] = med + mult * sd
        pos.extend(range(st, st + SPIKE_HOURS))
    return x, pos


def main() -> int:
    base_wide = load_wide()
    print(f"参与扫描建筑: {base_wide.shape[1]} 栋, 时间点 {base_wide.shape[0]:,}")

    # 逐列注入，构造"注入后矩阵"与真值掩码（保持统一索引）
    polluted = {}
    truth = {}
    for bid in base_wide.columns:
        x, pos = inject_spikes(base_wide[bid])
        polluted[bid] = x
        m = np.zeros(len(x), dtype=bool)
        m[pos] = True
        truth[bid] = m
    wide = pd.DataFrame(polluted, index=base_wide.index)
    truth_df = pd.DataFrame(truth, index=base_wide.index)
    n_injected = int(truth_df.to_numpy().sum())
    print(f"注入尖峰样本: {n_injected:,}")

    truth_np = truth_df.to_numpy()
    valid = ~np.isnan(wide.to_numpy())
    pos_mask = truth_np & valid
    neg_mask = (~truth_np) & valid
    n_pos, n_neg = int(pos_mask.sum()), int(neg_mask.sum())
    print(f"正样本 {n_pos:,}  负样本 {n_neg:,}")

    rows = []
    for q in [0.90, 0.95, 0.98, 0.99, 0.995, 0.999]:
        print(f"计算分层基线 (quantile={q}) ...", flush=True)
        baseline, threshold = seasonal_baseline(
            wide, n_weeks=cfg.ANOMALY_CONFIG["baseline_weeks"], quantile=q
        )
        floor = (baseline.abs() * cfg.ANOMALY_CONFIG["thr_floor_ratio"]).clip(
            lower=cfg.ANOMALY_CONFIG["thr_floor_abs"]
        )
        threshold = threshold.clip(lower=floor)
        resid = (wide - baseline).to_numpy()
        thr_np = threshold.to_numpy()
        # 分层基线不可用的时刻（序列起始前几周）不参与评估
        usable = ~np.isnan(thr_np) & ~np.isnan(resid)
        p_ok = pos_mask & usable
        n_ok = neg_mask & usable
        hit = (resid > thr_np) & usable
        tp = int((hit & p_ok).sum()); fp = int((hit & n_ok).sum())
        npos, nneg = int(p_ok.sum()), int(n_ok.sum())
        recall = tp / npos if npos else np.nan
        fpr = fp / nneg if nneg else np.nan
        prec = tp / (tp + fp) if (tp + fp) else np.nan
        f1 = 2 * prec * recall / (prec + recall) if (prec and recall and prec + recall) else np.nan
        rows.append({"分位数": q, "召回率": round(recall, 4), "误报率": round(fpr, 4),
                     "精确率": round(prec, 4), "F1": round(f1, 4),
                     "达标": "✅" if (recall >= 0.90 and fpr <= 0.10) else ""})
        print(f"    召回 {recall*100:.2f}%  误报 {fpr*100:.2f}%")
        continue
    for thr in []:
        hit = None
        tp = int((hit & pos_mask).sum())
        fp = int((hit & neg_mask).sum())
        fn = n_pos - tp
        recall = tp / n_pos if n_pos else np.nan
        fpr = fp / n_neg if n_neg else np.nan
        prec = tp / (tp + fp) if (tp + fp) else np.nan
        f1 = 2 * prec * recall / (prec + recall) if (prec and recall and prec + recall) else np.nan
        rows.append({"z阈值": thr, "召回率": round(recall, 4), "误报率": round(fpr, 4),
                     "精确率": round(prec, 4), "F1": round(f1, 4),
                     "达标": "✅" if (recall >= 0.90 and fpr <= 0.10) else ""})

    res = pd.DataFrame(rows)
    res.to_csv(EVAL_DIR / "threshold_sweep.csv", index=False, encoding="utf-8-sig")
    print("\n" + "=" * 74)
    print("分位数阈值扫描结果（目标：召回率 ≥90% 且 误报率 ≤10%）")
    print("=" * 74)
    print(res.to_string(index=False))

    ok = res[(res.召回率 >= 0.90) & (res.误报率 <= 0.10)]
    if len(ok):
        best = ok.sort_values("F1", ascending=False).iloc[0]
        print(f"\n✅ 推荐分位数 = {best['分位数']}  "
              f"(召回 {best['召回率']*100:.2f}%, 误报 {best['误报率']*100:.2f}%, F1 {best['F1']:.4f})")
    else:
        # 无同时达标点时，给出满足召回率前提下误报率最低的阈值
        cand = res[res.召回率 >= 0.90]
        if len(cand):
            best = cand.sort_values("误报率").iloc[0]
            print(f"\n⚠️ 无同时达标点。满足召回率≥90% 时误报率最低: 分位数={best['分位数']} "
                  f"(召回 {best['召回率']*100:.2f}%, 误报 {best['误报率']*100:.2f}%)")
        else:
            print("\n❌ 所有阈值均无法达到召回率 90%，需改进算法")
    print(f"\n[写出] {EVAL_DIR / 'threshold_sweep.csv'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""分层基线参数二维扫描：回看周数 × 残差分位数

在【干净数据】上测误报率，在【注入尖峰数据】上测召回率，同时报告两者，
从而选出满足申报表（召回 ≥90%、误报 ≤10%）的参数组合。

背景：建筑能耗具有强季节性（非平稳），用较短的 4 周历史估计阈值时，
历史窗口无法代表当前季节，实际超限率远高于分位数的理论值
（实测：4 周回看下理论 1% 对应实际 23%）。因此必须按【实际超限率】调参，
不能只按理论分位数。

用法：
    .venv/bin/python data-pipeline/scripts/06_sweep_baseline.py
产出：
    docs/eval/baseline_sweep.csv
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from config import DOCS_DIR, EXPORTS_DIR  # noqa: E402
from energy_lib import seasonal_baseline  # noqa: E402

EVAL_DIR = DOCS_DIR / "eval"
EVAL_DIR.mkdir(parents=True, exist_ok=True)

RANDOM_SEED = 20260101
N_BUILDINGS = 60
INJECT_PER_TYPE = 10
GUARD = 200
SPIKE_HOURS = 3
SPIKE_SIGMA = (4.0, 9.0)
RNG = np.random.default_rng(RANDOM_SEED)

LOOKBACK = [4, 8, 13, 26, 52]
QUANTILES = [0.95, 0.98, 0.99, 0.995, 0.999]


def load_wide() -> pd.DataFrame:
    df = pd.read_parquet(
        EXPORTS_DIR / "unified_energy_dataset.parquet",
        columns=["building_id", "timestamp", "electricity_kwh", "data_grade"],
    )
    df = df[df.data_grade == "A"]
    wide = df.pivot_table(
        index="timestamp", columns="building_id", values="electricity_kwh", aggfunc="sum"
    ).sort_index()
    ok = wide.columns[wide.notna().mean() >= 0.99]
    return wide[sorted(ok)[:N_BUILDINGS]]


def inject(wide: pd.DataFrame) -> tuple[pd.DataFrame, np.ndarray]:
    """逐列注入能耗尖峰，返回 (污染矩阵, 真值布尔矩阵)。"""
    x = wide.copy()
    truth = np.zeros(x.shape, dtype=bool)
    n = len(x)
    for j, bid in enumerate(x.columns):
        col = x[bid].to_numpy(dtype="float64").copy()
        med = float(np.nanmedian(col))
        sd = float(np.nanstd(col))
        sd = sd if sd > 0 else max(abs(med) * 0.1, 1.0)
        occupied: list[tuple[int, int]] = []
        for _ in range(INJECT_PER_TYPE):
            for _ in range(300):
                c = int(RNG.integers(GUARD, n - SPIKE_HOURS - GUARD))
                if all(c + SPIKE_HOURS + GUARD < a or c - GUARD > b for a, b in occupied):
                    occupied.append((c, c + SPIKE_HOURS))
                    mult = float(RNG.uniform(*SPIKE_SIGMA))
                    col[c:c + SPIKE_HOURS] = med + mult * sd
                    truth[c:c + SPIKE_HOURS, j] = True
                    break
        x[bid] = col
    return x, truth


def main() -> int:
    clean = load_wide()
    print(f"建筑 {clean.shape[1]} 栋, 时间点 {clean.shape[0]:,}")
    dirty, truth = inject(clean)
    print(f"注入尖峰样本 {int(truth.sum()):,}")

    rows = []
    for nw in LOOKBACK:
        for q in QUANTILES:
            res = {}
            for tag, mat in (("clean", clean), ("dirty", dirty)):
                bl, thr = seasonal_baseline(mat, n_weeks=nw, quantile=q)
                resid = (mat - bl).to_numpy()
                t = thr.to_numpy()
                m = ~np.isnan(t) & ~np.isnan(resid)
                res[tag] = (resid > t) & m, m
            hit_c, mc = res["clean"]
            hit_d, md = res["dirty"]
            fpr = float((hit_c & mc).sum() / mc.sum()) if mc.sum() else np.nan
            pos = truth & md
            tp = int((hit_d & pos).sum())
            npos = int(pos.sum())
            recall = tp / npos if npos else np.nan
            fp = int((hit_d & md & ~truth).sum())
            nneg = int((md & ~truth).sum())
            prec = tp / (tp + fp) if (tp + fp) else np.nan
            rows.append({
                "回看周数": nw, "分位数": q,
                "召回率": round(recall, 4), "误报率": round(fpr, 4), "精确率": round(prec, 4),
                "达标": "✅" if (recall >= 0.90 and fpr <= 0.10) else "",
            })
            print(f"  回看 {nw:>2} 周 分位 {q:<6} 召回 {recall*100:>6.2f}%  误报 {fpr*100:>6.2f}%"
                  f"  精确 {prec*100:>5.2f}%  {'✅' if (recall>=0.90 and fpr<=0.10) else ''}")

    df = pd.DataFrame(rows)
    df.to_csv(EVAL_DIR / "baseline_sweep.csv", index=False, encoding="utf-8-sig")
    print("\n" + "=" * 74)
    print("二维扫描汇总（目标：召回 ≥90% 且 误报 ≤10%）")
    print("=" * 74)
    print(df.to_string(index=False))

    ok = df[df.达标 == "✅"]
    if len(ok):
        # 在达标组合中选精确率最高者
        best = ok.sort_values("精确率", ascending=False).iloc[0]
        print(f"\n✅ 推荐参数：回看 {int(best['回看周数'])} 周，分位数 {best['分位数']} "
              f"(召回 {best['召回率']*100:.2f}%, 误报 {best['误报率']*100:.2f}%, "
              f"精确 {best['精确率']*100:.2f}%)")
    else:
        cand = df[df.召回率 >= 0.90]
        if len(cand):
            b = cand.sort_values("误报率").iloc[0]
            print(f"\n⚠️ 无同时达标组合。召回≥90% 时误报最低：回看 {int(b['回看周数'])} 周，"
                  f"分位数 {b['分位数']} (召回 {b['召回率']*100:.2f}%, 误报 {b['误报率']*100:.2f}%)")
        else:
            print("\n❌ 无组合达到召回率 90%")
    print(f"\n[写出] {EVAL_DIR / 'baseline_sweep.csv'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

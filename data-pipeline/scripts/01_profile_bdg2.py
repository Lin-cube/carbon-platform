"""BDG2 数据测绘：统计每个表计的覆盖率、每站点可用表计数量。

产出：
  - data/processed/profile_meters.csv  逐表计明细（覆盖率、站点、建筑、表计类型）
  - config/meter_selection.json        各站点/表计类型的可用表计清单（ETL 选表依据）
  - 控制台摘要                          供人工判断数据量是否满足申报表要求

关键洞察（已实测）：BDG2 的 cleaned 文件并非"整文件干净"——列级覆盖率差异极大
（electricity_cleaned 整体缺失率 15.4%，但其中 1318/1578 个表计为两年全覆盖）。
因此选表必须按【单表计】粒度评估覆盖率，不能按文件粒度。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

# 脚本位于 data-pipeline/scripts/，模块位于 data-pipeline/src/
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from config import (  # noqa: E402
    CONFIG_DIR,
    EXPECTED_HOURS,
    METADATA_CSV,
    METER_FILE_PATTERN,
    METER_TYPES,
    METERS_CLEANED,
    METERS_RAW,
    PROCESSED_DIR,
)

# 覆盖率分层阈值
COVERAGE_TIERS = {"full": 0.99, "good": 0.80, "usable": 0.50, "poor": 0.0}


def build_building_index() -> pd.DataFrame:
    """建筑主数据索引：building_id -> 站点、类型、面积。"""
    md = pd.read_csv(METADATA_CSV, low_memory=False)
    md["site_id"] = md["building_id"].astype(str).str.split("_").str[0]
    md["meter_type_clean"] = md["building_id"].astype(str).str.split("_").str[1]
    return md


def profile_meter_file(meter_type: str, use_cleaned: bool = True) -> pd.DataFrame:
    """测绘单个表计文件，返回逐表计覆盖率明细。"""
    base = METERS_CLEANED if use_cleaned else METERS_RAW
    kind = "cleaned" if use_cleaned else "raw"
    path = base / METER_FILE_PATTERN[kind].format(type=meter_type)
    if not path.exists():
        print(f"  [skip] {path.name} 不存在")
        return pd.DataFrame()

    print(f"  读取 {path.name} ...", flush=True)
    # 只读表头以获取列名，避免一次性载入宽表
    header = pd.read_csv(path, nrows=0)
    cols = list(header.columns[1:])
    n_cols = len(cols)

    # 分块统计非空数量，控制内存峰值（electricity 宽表 1579 列）
    nonnull = pd.Series(0, index=cols, dtype="int64")
    total = 0
    for chunk in pd.read_csv(path, usecols=cols, chunksize=2000, low_memory=False):
        nonnull = nonnull.add(chunk.notna().sum(), fill_value=0)
        total += len(chunk)
        if total % 6000 == 0:
            print(f"    ...{total}/{EXPECTED_HOURS} 行", flush=True)

    cov = (nonnull / max(total, 1)).rename("coverage")
    df = cov.reset_index().rename(columns={"index": "meter_id"})
    df["meter_type"] = meter_type
    df["site_id"] = df["meter_id"].str.split("_").str[0]
    df["meter_subtype"] = df["meter_id"].str.split("_").str[1]
    df["nonnull_hours"] = df["meter_id"].map(nonnull)
    df["total_hours"] = total
    df["source"] = "cleaned" if use_cleaned else "raw"
    return df


def main() -> int:
    print("=" * 78)
    print("BDG2 数据测绘")
    print("=" * 78)

    md = build_building_index()
    print(f"\n建筑主数据: {len(md)} 栋, {md.site_id.nunique()} 个站点")
    print(md.site_id.value_counts().to_string())

    frames = []
    for mt in METER_TYPES:
        df = profile_meter_file(mt, use_cleaned=True)
        if not df.empty:
            frames.append(df)

    prof = pd.concat(frames, ignore_index=True)
    prof = prof.sort_values(["site_id", "meter_type", "coverage"], ascending=[True, True, False])

    out_csv = PROCESSED_DIR / "profile_meters.csv"
    prof.to_csv(out_csv, index=False, encoding="utf-8-sig")
    print(f"\n[写出] 逐表计明细 -> {out_csv}  ({len(prof)} 行)")

    # ---------------- 覆盖率分层汇总 ----------------
    print("\n" + "=" * 78)
    print("覆盖率分层（按表计类型）")
    print("=" * 78)
    print(f"{'表计类型':<16}{'总数':>7}{'full≥99%':>10}{'good≥80%':>10}{'usable≥50%':>12}{'poor<50%':>10}")
    for mt in METER_TYPES:
        sub = prof[prof.meter_type == mt]
        if sub.empty:
            continue
        c = sub.coverage
        print(
            f"{mt:<16}{len(sub):>7}"
            f"{int((c >= 0.99).sum()):>10}{int(((c >= 0.80) & (c < 0.99)).sum()):>10}"
            f"{int(((c >= 0.50) & (c < 0.80)).sum()):>12}{int((c < 0.50).sum()):>10}"
        )

    # ---------------- 选表策略 ----------------
    # 优先 full 覆盖率表计；不足则在核心类型上放宽到 good
    CORE_TYPES = ["electricity", "chilledwater"]
    selection: dict[str, dict[str, list[str]]] = {}
    for site, grp in prof.groupby("site_id"):
        site_sel: dict[str, list[str]] = {}
        for mt in METER_TYPES:
            sub = grp[grp.meter_type == mt]
            if sub.empty:
                continue
            tier = 0.99
            picked = sub[sub.coverage >= tier]
            if picked.empty and mt in CORE_TYPES:
                picked = sub[sub.coverage >= 0.80]   # 核心表计放宽
            if picked.empty:
                picked = sub[sub.coverage >= 0.50]   # 仍不足则再放宽
            if not picked.empty:
                site_sel[mt] = sorted(
                    picked.sort_values("coverage", ascending=False).meter_id.tolist()
                )
        if site_sel:
            selection[site] = site_sel

    sel_path = CONFIG_DIR / "meter_selection.json"
    with open(sel_path, "w", encoding="utf-8") as f:
        json.dump(selection, f, ensure_ascii=False, indent=2)
    print(f"\n[写出] 选表配置 -> {sel_path}")

    # ---------------- 数据量估算 ----------------
    print("\n" + "=" * 78)
    print("统一数据集规模估算（选表后）")
    print("=" * 78)
    total_meters = sum(len(v) for s in selection.values() for v in s.values())
    print(f"入选表计总数: {total_meters}")
    print(f"\n{'站点':<12}{'电':>5}{'冷':>5}{'蒸汽':>5}{'热水':>5}{'气':>5}{'水':>5}{'预估行数':>12}")
    grand = 0
    for site, sel in sorted(selection.items()):
        est = sum(len(v) for v in sel.values()) * EXPECTED_HOURS
        grand += est
        print(
            f"{site:<12}{len(sel.get('electricity', [])):>5}{len(sel.get('chilledwater', [])):>5}"
            f"{len(sel.get('steam', [])):>5}{len(sel.get('hotwater', [])):>5}"
            f"{len(sel.get('gas', [])):>5}{len(sel.get('water', [])):>5}{est:>12,}"
        )
    print(f"\n{'合计':<12}{'':>5}{'':>5}{'':>5}{'':>5}{'':>5}{'':>5}{grand:>12,}")
    print(f"\n申报表要求 ≥ 1,000 条记录 → 预估 {grand:,} 条，余量 {grand / 1000:.0f}x")
    print("\n提示：全量{:,} 条对演示环境偏重，建议按【站点抽样 + 建筑数控制】降到 50-300 万条。".format(grand))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

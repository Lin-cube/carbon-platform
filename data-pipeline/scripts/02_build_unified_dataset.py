"""BDG2 -> 统一能耗数据集 ETL（宽表 -> 长表）

产出（data/exports/）：
  unified_energy_dataset.csv.gz       全量统一能耗数据集（申报表口径）
  unified_energy_dataset_sample.csv   小样本（队友快速上手 / 导入验证）
  unified_energy_dataset.parquet      列式存储（后端批量导入 / 分析用）
  unified_energy_dataset.json         行式 JSON（MCP 接口联调样例）

产出（data/processed/）：
  dim_building.csv     建筑维表（面积、楼层、EUI、能耗评级）
  quality_report.csv   逐建筑数据质量与异常统计

设计要点：
  1. BDG2 表计数值为【每小时区间消耗量】，直接 SUM，禁止 diff（已实测验证）。
  2. 选表按【单表计覆盖率】而非整文件覆盖率（见 01_profile_bdg2.py）。
  3. 建筑级优先用【该建筑自己的表计】；无独立表计时回退到站点级聚合，
     并以 meter_source 字段明确标注，避免把站点值伪装成建筑实测值。
  4. 逐站点、分块读取宽表，控制内存峰值（electricity 宽表 1579 列）。
  5. 每个字段标注数据来源：实测 / 推算 / 仿真 / 标注，写入数据字典。
"""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from config import (  # noqa: E402
    BUILDING_TYPE_CN,
    BUILD_SCOPE,
    CONFIG_DIR,
    EXPORTS_DIR,
    METADATA_CSV,
    METER_FILE_PATTERN,
    METER_PLAUSIBILITY,
    METER_TYPES,
    METERS_CLEANED,
    PROCESSED_DIR,
    WEATHER_CSV,
)
from energy_lib import (  # noqa: E402
    convert_units,
    detect_anomalies,
    dewpoint_to_rh,
    estimate_cop,
    grade_energy_intensity,
    synth_chw_temperatures,
    synth_occupancy_density,
)

CHUNK = 2000
CARBON_FACTOR_KG_PER_KWH = 0.5810   # 生态环境部 2023 公布全国电网平均排放因子

FULL_INDEX = pd.date_range("2016-01-01", "2017-12-31 23:00:00", freq="h", name="timestamp")


# ======================================================================
# 维表
# ======================================================================


def load_buildings() -> pd.DataFrame:
    md = pd.read_csv(METADATA_CSV, low_memory=False)
    md["site_id"] = md["building_id"].astype(str).str.split("_").str[0]
    md["building_type_cn"] = md["primaryspaceusage"].map(BUILDING_TYPE_CN).fillna("其他")
    md["area_m2"] = pd.to_numeric(md["sqm"], errors="coerce")
    md["occupants_num"] = pd.to_numeric(md["occupants"], errors="coerce")
    md["floors"] = pd.to_numeric(md["numberoffloors"], errors="coerce")
    md["year_built"] = pd.to_numeric(md["yearbuilt"], errors="coerce")
    return md


# ======================================================================
# 表计读取
# ======================================================================


def read_meter_file(meter_type: str, meters: list[str]) -> pd.DataFrame:
    """读取某表计类型的指定表计列，返回【逐表计】宽表（未聚合）。

    保持表计粒度是必须的：BDG2 中同一站点可能同时存在"建筑级分表"与
    "冷站级总表"，把全站点表计直接求和会得到量级失真的结果
    （实测：Eagle 站点冷量求和为电量的 1900 倍，物理不可能）。
    """
    path = METERS_CLEANED / METER_FILE_PATTERN["cleaned"].format(type=meter_type)
    if not path.exists() or not meters:
        return pd.DataFrame()

    pieces: list[pd.DataFrame] = []
    for chunk in pd.read_csv(path, usecols=["timestamp"] + meters, chunksize=CHUNK, low_memory=False):
        chunk["timestamp"] = pd.to_datetime(chunk["timestamp"])
        pieces.append(chunk.set_index("timestamp")[meters].astype("float32"))
        del chunk

    if not pieces:
        return pd.DataFrame()

    df = pd.concat(pieces)
    df = df[~df.index.duplicated(keep="first")].sort_index()
    # 【关键】BDG2 为每小时区间消耗量，直接使用，不做 diff
    return convert_units(df.astype("float64"), meter_type).astype("float32")


def meters_to_buildings(per_meter: pd.DataFrame) -> pd.DataFrame:
    """表计列 -> 建筑列聚合。

    【重要】BDG2 中表计列名【就是】metadata.building_id，二者 100% 精确匹配
    （实测：electricity 1578/1578、chilledwater 555/555、water 146/146 全部命中）。
    因此这里不做任何字符串解析——building_id 形如 <Site>_<Type>_<Name>，
    按 "_" 切分会把同站点同类型的建筑全部合并成一个，是严重错误。
    同一 building_id 出现多个表计时（极少）按求和聚合。
    """
    if per_meter.empty:
        return per_meter
    if per_meter.columns.has_duplicates:
        return per_meter.T.groupby(level=0).sum().T
    return per_meter


def load_weather_site(site_id: str, index: pd.DatetimeIndex) -> pd.DataFrame:
    """载入站点天气、对齐时间索引，并由露点推算相对湿度。"""
    w = pd.read_csv(
        WEATHER_CSV,
        usecols=["timestamp", "site_id", "airTemperature", "dewTemperature", "windSpeed", "cloudCoverage"],
    )
    w = w[w.site_id == site_id].copy()
    out = pd.DataFrame(index=index)
    if w.empty:
        return out
    w["timestamp"] = pd.to_datetime(w["timestamp"])
    w = w.set_index("timestamp").sort_index()
    w = w[~w.index.duplicated(keep="first")].reindex(index)

    out["air_temperature_c"] = w["airTemperature"].astype("float32")
    out["humidity_pct"] = dewpoint_to_rh(
        w["airTemperature"].astype("float64"), w["dewTemperature"].astype("float64")
    ).astype("float32").round(1)
    out["wind_speed_ms"] = w["windSpeed"].astype("float32")
    out["cloud_cover"] = w["cloudCoverage"].astype("float32")
    return out


def select_buildings(
    site_bld: pd.DataFrame,
    meter_coverage: dict[str, float],
    min_coverage: float | None = None,
) -> pd.DataFrame:
    """挑选纳入数据集的建筑。

    策略（按数据质量优先）：
      1. A 级：拥有独立电表**且覆盖率达标**的建筑，按覆盖率降序 —— 真正可用的一手实测数据；
      2. B 级：其余建筑按面积降序补足到 max_buildings_per_site —— 仅用于补覆盖度。

    关键教训：早期版本按【面积】挑选建筑，导致选中了一批覆盖率仅 17% 的"有表计但几乎无数据"
    的建筑。这些建筑既做不了 A 级（数据太稀），又因为"有表计"而被排除在站点残差之外，
    最终拿到接近 0 的分摊值——整栋楼的电耗被静默清零。
    """
    if min_coverage is None:
        min_coverage = BUILD_SCOPE.get("min_meter_coverage", 0.50)

    b = site_bld.copy()
    b["meter_coverage"] = b["building_id"].map(meter_coverage).fillna(0.0)
    b["has_own_meter"] = b["meter_coverage"] > 0
    b["is_grade_a"] = b["meter_coverage"] >= min_coverage

    a = b[b["is_grade_a"]].sort_values("meter_coverage", ascending=False)
    rest = b[~b["is_grade_a"]].sort_values("area_m2", ascending=False)

    cap = BUILD_SCOPE["max_buildings_per_site"]
    if not cap:
        return pd.concat([a, rest], ignore_index=True)

    keep_a = a.head(cap)
    if len(keep_a) < BUILD_SCOPE.get("min_metered_per_site", 0):
        keep_a = a
    keep_b = rest.head(max(cap - len(keep_a), 0))
    return pd.concat([keep_a, keep_b], ignore_index=True)


def allocate_to_buildings(
    per_building: pd.DataFrame,
    site_total: pd.Series,
    bld: pd.DataFrame,
    index: pd.DatetimeIndex,
    label: str,
    report: dict,
    grade_a_ids: set[str] | None = None,
) -> pd.DataFrame:
    """把逐建筑表计数据分配到每栋建筑。

    A 级建筑（有独立表计且读数有效）-> 用实测值；
    B 级建筑 -> 按建筑面积占比，分摊【站点剩余量】= 站点合计 − A 级实测合计。

    ⚠️ 两个必须避免的陷阱（均已在实测中踩过）：
      1. `site_total` 必须是该表计类型的**站点全部表计合计**，不能用
         "本次纳入建筑"的合计。否则 site_total 恒等于 A 级合计，
         残差永远为 0，B 级建筑的电耗会被静默清零。
      2. 残差可能为负（A 级合计 > 站点合计，说明站点存在上级总表），
         需截断并记录，供人工核查计量口径。
    """
    b_ids = bld["building_id"].tolist()
    areas = bld["area_m2"].fillna(0).clip(lower=0).to_numpy(dtype="float64")

    direct_ids = set(grade_a_ids) if grade_a_ids is not None else set(per_building.columns)
    direct_ids &= set(b_ids)
    direct_cols = [c for c in per_building.columns if c in direct_ids]

    direct_sum = (
        per_building[direct_cols].sum(axis=1).reindex(index) if direct_cols
        else pd.Series(0.0, index=index)
    )
    tot = site_total.reindex(index).astype("float64")

    raw_residual = tot - direct_sum
    clipped_hours = int((raw_residual < -1e-6).sum())
    residual = raw_residual.clip(lower=0.0)

    unmet = [bid for bid in b_ids if bid not in direct_ids]
    share = pd.Series(0.0, index=b_ids, dtype="float64")
    if unmet:
        w = np.array([areas[b_ids.index(bid)] for bid in unmet], dtype="float64")
        wsum = w.sum()
        share.loc[unmet] = (w / wsum) if wsum > 0 else (1.0 / len(unmet))

    out = pd.DataFrame(index=index)
    for bid in b_ids:
        if bid in direct_ids and bid in per_building.columns:
            out[bid] = per_building[bid].reindex(index)
        else:
            out[bid] = residual * share.get(bid, 0.0)

    report[label] = {
        "A级实测建筑数": len(direct_ids),
        "B级分摊建筑数": len(unmet),
        "分配方式": "A级实测 + B级按面积占比分摊站点剩余量",
        "残差为负小时数": clipped_hours,
    }
    return out


def build_site(
    site_id: str,
    sel: dict[str, list[str]],
    bld: pd.DataFrame,
    elec_cov: dict[str, float],
) -> tuple[pd.DataFrame, dict]:
    """构建单站点统一能耗长表（建筑级行）。"""
    n_hours = BUILD_SCOPE["max_hours"]
    index = FULL_INDEX if not n_hours else FULL_INDEX[:n_hours]
    report: dict = {}
    chosen_ids = set(bld["building_id"])
    min_cov = BUILD_SCOPE.get("min_meter_coverage", 0.50)

    # ---- 读取各表计（保持逐表计粒度）----
    per_building: dict[str, pd.DataFrame] = {}
    site_totals: dict[str, pd.Series] = {}
    for mt in METER_TYPES:
        meters = sel.get(mt)
        if not meters:
            continue
        t0 = time.time()
        pm = read_meter_file(mt, meters)
        if pm.empty:
            continue
        pb = meters_to_buildings(pm)
        # 站点全部表计合计（分配残差的基准，必须用「站点」而非「纳入建筑」口径）
        site_totals[mt] = pb.sum(axis=1).reindex(index)
        # 只保留本次纳入的建筑；若该表计类型一个都没命中（如冷站级总表），
        # 则保留全部表计，交由后续面积分摊逻辑处理
        hit = [c for c in pb.columns if c in chosen_ids]
        per_building[mt] = pb[hit]
        print(
            f"    {mt:<14} {len(meters):>4} 表 -> {len(hit):>3} 建筑命中  "
            f"站点合计 {site_totals[mt].sum():>14,.0f}  ({time.time() - t0:.1f}s)",
            flush=True,
        )
        del pm

    if not per_building:
        return pd.DataFrame(), report

    # ---- 表计分类（必须在分配【之前】完成）----
    # BDG2 的表计并非都可用，实测发现三类问题：
    #   ① 覆盖率不足：如 Bobcat_education_Emile 覆盖率仅 17%；
    #   ② 有覆盖无读数：覆盖率 99% 但读数恒为 0；
    #   ③ 量级失真：如 Eagle 冷冻水表推得 EUI 达 33 万 kWh/m²·年
    #      （真实公共建筑为 20~300），属计量口径或单位错误。
    #
    # 采用【逐表计】校验；不做站点合计校验（园区常只有部分建筑装表，
    # 用站点总面积摊总算强度会得出假异常，见 config 中的说明）。
    # 校验不通过的表计整列剔除，且其能量【不参与】后续分摊，
    # 避免把失真数据摊到同园区其他建筑上。
    n_years_frac = max(len(index), 1) / 8760.0
    area_map = bld.set_index("building_id")["area_m2"].fillna(0)
    eui_limits: dict[str, float] = METER_PLAUSIBILITY["max_eui_kwh_m2_year"]
    water_limit = METER_PLAUSIBILITY["max_water_m3_m2_year"]
    min_area = METER_PLAUSIBILITY["min_area_m2"]
    min_median = METER_PLAUSIBILITY["min_median_kwh"]

    usable: set[str] = set()
    grade_a: dict[str, set[str]] = {}
    dropped: dict[str, list[str]] = {}
    for mt, pb in per_building.items():
        limit = water_limit if mt in ("water", "irrigation") else eui_limits.get(mt, 600.0)
        good: set[str] = set()
        bad: list[str] = []
        for c in pb.columns:
            if mt == "electricity" and elec_cov.get(c, 0.0) < min_cov:
                bad.append(c)                      # ① 覆盖率不足
                continue
            s = pb[c].dropna()
            if len(s) < 100 or float(s.median()) <= min_median:
                bad.append(c)                      # ② 有覆盖无有效读数
                continue
            ar = float(area_map.get(c, 0.0) or 0.0)
            if ar < min_area:
                bad.append(c)                      # 面积缺失，无法校验强度
                continue
            if float(s.sum()) / n_years_frac / ar > limit:
                bad.append(c)                      # ③ 量级失真
                continue
            good.add(c)

        grade_a[mt] = good
        if good:
            usable.add(mt)
        if bad:
            dropped[mt] = sorted(bad)
        per_building[mt] = pb[sorted(good)] if good else pb.iloc[:, :0]
        # 【关键】站点合计必须同步收敛到"通过校验的表计"合计。
        # 若仍用全部表计合计，被剔除表计的能量会进入残差并被分摊出去，
        # 造成重复计量（实测：Fox_education_Willis 分摊后中位值被抬高 4.6 倍）。
        site_totals[mt] = pb[sorted(good)].sum(axis=1).reindex(index) if good else pd.Series(0.0, index=index)

    if dropped:
        report["不可用表计（已剔除）"] = {k: len(v) for k, v in dropped.items()}
        print("    [表计分类] 剔除不可用表计: " +
              ", ".join(f"{k} {len(v)} 个" for k, v in dropped.items()), flush=True)

    # ---- 分配到建筑（仅对通过校验的表计类型）----
    alloc: dict[str, pd.DataFrame] = {}
    for mt in usable:
        alloc[mt] = allocate_to_buildings(
            per_building[mt], site_totals[mt], bld, index, mt, report, grade_a_ids=grade_a[mt]
        )

    # ---- 天气 ----
    weather = load_weather_site(site_id, index)

    # ---- 逐建筑组装 ----
    all_bids = bld["building_id"].tolist()
    n_years_real = max(index.year.nunique(), 1)

    def series_for(mt: str, bid: str) -> pd.Series:
        a = alloc.get(mt)
        if a is None or bid not in a.columns:
            return pd.Series(np.nan, index=index, dtype="float32")
        return a[bid].reindex(index)

    # 冷量：用于供回水温度与 COP 建模
    chw_all = alloc.get("chilledwater")
    frames = []
    for bid in all_bids:
        d = pd.DataFrame(index=index)
        elec = series_for("electricity", bid)
        chw = series_for("chilledwater", bid)
        steam = series_for("steam", bid)
        hot = series_for("hotwater", bid)

        hvac = None
        for s in (chw, steam, hot):
            if s.notna().any():
                hvac = s if hvac is None else hvac.add(s, fill_value=0)

        d["electricity_kwh"] = elec
        d["hvac_kwh"] = hvac if hvac is not None else np.nan
        d["water_m3"] = series_for("water", bid)
        d["gas_kwh"] = series_for("gas", bid)
        d["solar_kwh"] = series_for("solar", bid)
        for c in ("air_temperature_c", "humidity_pct", "wind_speed_ms", "cloud_cover"):
            if c in weather:
                d[c] = weather[c]

        # ---- 供回水温度与 COP（仿真，仅在建筑有冷量数据时生成）----
        if chw.notna().any() and chw.sum() > 0:
            ref = float(np.nanpercentile(chw.dropna().to_numpy(), 95))
            if ref > 0:
                load_ratio = (chw / ref).clip(lower=0.0, upper=1.5)
                air = d["air_temperature_c"].astype("float64").interpolate(limit_direction="both")
                supply, ret = synth_chw_temperatures(air, pd.Series(index.hour, index=index), load_ratio)
                d["chw_supply_temp_c"] = supply
                d["chw_return_temp_c"] = ret
                d["cop"] = estimate_cop(air, load_ratio)
                d["hvac_electricity_kwh"] = (chw / d["cop"]).round(4)

        d["building_id"] = bid
        d["site_id"] = site_id
        cov = elec_cov.get(bid, 0.0)
        # 与分配逻辑保持同一个判据：在 grade_a["electricity"] 中才是 A 级实测
        is_own = bid in grade_a.get("electricity", set())
        d["meter_source"] = "建筑独立表计" if is_own else "站点分摊"
        # A 级 = 独立计量且读数有效（可参与指标评估）；B 级 = 分摊估算（仅补覆盖度）
        d["data_grade"] = "A" if is_own else "B"
        d["meter_coverage"] = round(cov, 4)
        frames.append(d.reset_index())

    out = pd.concat(frames, ignore_index=True)

    # 建筑属性
    meta = bld.set_index("building_id")
    for col in ("building_type_cn", "area_m2", "occupants_num", "floors", "year_built", "primaryspaceusage"):
        out[col] = out["building_id"].map(meta[col])

    out["occupancy_density"] = synth_occupancy_density(
        out["timestamp"], out["building_type_cn"], out["area_m2"], out["occupants_num"]
    )
    return out, report


# ======================================================================
# 主流程
# ======================================================================


def main() -> int:
    t_start = time.time()
    print("=" * 78)
    print("统一能耗数据集 ETL")
    print("=" * 78)
    print(f"规模控制: 每站点 ≤{BUILD_SCOPE['max_buildings_per_site']} 栋, "
          f"时间窗 {BUILD_SCOPE['max_hours']} 小时")

    sel_path = CONFIG_DIR / "meter_selection.json"
    if not sel_path.exists():
        print(f"[错误] 未找到选表配置 {sel_path}，请先运行 01_profile_bdg2.py")
        return 1
    selection: dict[str, dict[str, list[str]]] = json.loads(sel_path.read_text(encoding="utf-8"))

    bld = load_buildings()
    bld_by_site = {s: g for s, g in bld.groupby("site_id", observed=True)}

    # 电表覆盖率映射（来自 01_profile_bdg2.py），用于按数据质量挑选建筑
    prof_path = PROCESSED_DIR / "profile_meters.csv"
    if not prof_path.exists():
        print(f"[错误] 未找到 {prof_path}，请先运行 01_profile_bdg2.py")
        return 1
    prof = pd.read_csv(prof_path)
    elec_cov = (
        prof[prof.meter_type == "electricity"]
        .set_index("meter_id")["coverage"]
        .to_dict()
    )
    print(f"电表覆盖率映射: {len(elec_cov)} 个表计")

    all_frames: list[pd.DataFrame] = []
    site_reports: dict[str, dict] = {}
    for i, (site_id, sel) in enumerate(sorted(selection.items()), 1):
        site_bld = bld_by_site.get(site_id, pd.DataFrame())
        if site_bld.empty:
            continue
        chosen = select_buildings(site_bld, elec_cov)
        n_a = int((chosen["meter_coverage"] >= BUILD_SCOPE.get("min_meter_coverage", 0.5)).sum())
        print(f"\n[{i}/{len(selection)}] 站点 {site_id}: 候选 {len(site_bld)} 栋 -> "
              f"纳入 {len(chosen)} 栋（A级 {n_a} / B级 {len(chosen)-n_a}）")
        site_df, rep = build_site(site_id, sel, chosen, elec_cov)
        if site_df.empty:
            print("    [跳过] 无可用数据")
            continue
        site_reports[site_id] = rep
        all_frames.append(site_df)
        print(f"    -> {len(site_df):,} 行")

    if not all_frames:
        print("[错误] 未生成任何数据")
        return 1

    print(f"\n合并 {len(all_frames)} 个站点 ...", flush=True)
    df = pd.concat(all_frames, ignore_index=True)
    del all_frames

    # ---------------- 字段规范化 ----------------
    print("字段规范化与派生 ...", flush=True)
    df = df.rename(columns={"primaryspaceusage": "building_type"})
    df["timestamp"] = pd.to_datetime(df["timestamp"])
    df["meter_id"] = df["building_id"].astype(str)
    df["resolution"] = "hourly"

    ts = df["timestamp"]
    df["date"] = ts.dt.date
    df["year"] = ts.dt.year.astype("int16")
    df["month"] = ts.dt.month.astype("int8")
    df["day"] = ts.dt.day.astype("int8")
    df["hour"] = ts.dt.hour.astype("int8")
    df["weekday"] = ts.dt.dayofweek.astype("int8")
    df["is_weekend"] = (ts.dt.dayofweek >= 5).astype("int8")
    df["season"] = np.select(
        [ts.dt.month.isin([3, 4, 5]), ts.dt.month.isin([6, 7, 8]), ts.dt.month.isin([9, 10, 11])],
        ["春季", "夏季", "秋季"], default="冬季",
    )

    float_cols = [
        "electricity_kwh", "water_m3", "hvac_kwh", "gas_kwh", "solar_kwh",
        "chw_supply_temp_c", "chw_return_temp_c", "cop", "hvac_electricity_kwh",
        "air_temperature_c", "humidity_pct", "wind_speed_ms", "cloud_cover",
        "occupancy_density", "area_m2", "occupants_num", "floors",
    ]
    for c in float_cols:
        if c in df.columns:
            df[c] = pd.to_numeric(df[c], errors="coerce").astype("float32")

    # ---------------- 异常检测（仅对 A 级独立计量建筑） ----------------
    # B 级（站点分摊）建筑的 electricity_kwh 是估算值，对其做异常检测无意义，
    # 且会污染召回率/误报率评估。因此只对 A 级建筑检测，其余置空。
    print("基线-偏差异常检测（仅 A 级独立计量建筑）...", flush=True)
    t0 = time.time()

    target = df[df["data_grade"] == "A"]
    if target.empty:
        target = df
    wide = target.pivot_table(index="timestamp", columns="building_id", values="electricity_kwh", aggfunc="sum")
    print(f"  参与检测矩阵: {wide.shape[0]} 小时 × {wide.shape[1]} 栋 A 级建筑")
    flags = detect_anomalies(wide)
    for name, mat in flags.items():
        stacked = mat.stack(future_stack=True).rename(name)
        stacked.index.names = ["timestamp", "building_id"]
        df = df.merge(stacked.reset_index(), on=["timestamp", "building_id"], how="left")
    print(f"  完成，用时 {time.time() - t0:.1f}s")
    del wide, flags, target

    # A 级补齐默认值；B 级保持缺失（不可评估）
    is_a = df["data_grade"] == "A"
    df.loc[is_a, "equipment_status"] = df.loc[is_a, "equipment_status"].fillna("正常")
    df.loc[is_a, "anomaly_type"] = df.loc[is_a, "anomaly_type"].fillna("正常")
    df.loc[is_a, "severity"] = df.loc[is_a, "severity"].fillna("无")
    df.loc[is_a, "anomaly_flag"] = df.loc[is_a, "anomaly_flag"].fillna(0).astype("int8")
    df.loc[is_a, "data_quality_type"] = df.loc[is_a, "data_quality_type"].fillna("正常")
    df.loc[is_a, "data_quality_flag"] = df.loc[is_a, "data_quality_flag"].fillna(0).astype("int8")
    df["equipment_status"] = df["equipment_status"].fillna("未评估")
    df["anomaly_type"] = df["anomaly_type"].fillna("未评估")
    df["severity"] = df["severity"].fillna("未评估")
    df["data_quality_type"] = df["data_quality_type"].fillna("未评估")
    df["anomaly_flag"] = df["anomaly_flag"].fillna(-1).astype("int8")       # -1 = 未评估
    df["data_quality_flag"] = df["data_quality_flag"].fillna(-1).astype("int8")

    # ---------------- 碳排放 ----------------
    # 范围一+范围二简化口径：外购电（含空调电耗）+ 燃气，乘以电网平均排放因子
    scope2 = df["electricity_kwh"].fillna(0)
    if "hvac_electricity_kwh" in df.columns:
        scope2 = scope2 + df["hvac_electricity_kwh"].fillna(0)
    scope1 = df["gas_kwh"].fillna(0) if "gas_kwh" in df.columns else 0
    df["carbon_kg"] = ((scope2 + scope1) * CARBON_FACTOR_KG_PER_KWH).astype("float32").round(4)

    # ---------------- 列顺序 ----------------
    ordered = [
        "building_id", "site_id", "meter_id", "building_type", "building_type_cn",
        "resolution", "timestamp", "date", "year", "month", "day", "hour",
        "weekday", "is_weekend", "season",
        "electricity_kwh", "water_m3", "hvac_kwh", "gas_kwh", "solar_kwh",
        "hvac_electricity_kwh", "carbon_kg", "cop", "chw_supply_temp_c", "chw_return_temp_c",
        "air_temperature_c", "humidity_pct", "occupancy_density", "wind_speed_ms", "cloud_cover",
        "equipment_status", "anomaly_flag", "anomaly_type", "severity",
        "data_quality_flag", "data_quality_type",
        "baseline", "zscore",
        "meter_source", "data_grade", "meter_coverage", "area_m2", "floors", "year_built", "occupants_num",
    ]
    df = df[[c for c in ordered if c in df.columns]]
    df = df.sort_values(["building_id", "timestamp"]).reset_index(drop=True)

    print(f"\n统一数据集: {df.shape[0]:,} 行 × {df.shape[1]} 列")
    print(f"建筑数: {df.building_id.nunique():,}   站点数: {df.site_id.nunique()}")

    # ---------------- 写出 ----------------
    print("\n写出产物 ...", flush=True)
    out_csv = EXPORTS_DIR / "unified_energy_dataset.csv.gz"
    df.to_csv(out_csv, index=False, compression="gzip")
    print(f"  [1/4] {out_csv.name}  ({out_csv.stat().st_size / 1e6:.1f} MB)")

    sample = df.head(5000)
    out_sample = EXPORTS_DIR / "unified_energy_dataset_sample.csv"
    sample.to_csv(out_sample, index=False, encoding="utf-8-sig")
    print(f"  [2/4] {out_sample.name}  ({out_sample.stat().st_size / 1e6:.2f} MB)")

    try:
        out_pq = EXPORTS_DIR / "unified_energy_dataset.parquet"
        df.to_parquet(out_pq, index=False, compression="zstd")
        print(f"  [3/4] {out_pq.name}  ({out_pq.stat().st_size / 1e6:.1f} MB)")
    except Exception as e:
        print(f"  [3/4] parquet 跳过（{type(e).__name__}: {e}）")

    out_json = EXPORTS_DIR / "unified_energy_dataset.json"
    sample.head(500).to_json(out_json, orient="records", force_ascii=False, indent=1, date_format="iso")
    print(f"  [4/4] {out_json.name}  ({out_json.stat().st_size / 1e3:.0f} KB, 500 条样例)")

    # ---------------- 维表 ----------------
    # 异常小时数只统计 A 级（anomaly_flag 为 0/1），B 级为 -1 不计入
    df["_anom"] = df["anomaly_flag"].clip(lower=0)
    df["_qual"] = df["data_quality_flag"].clip(lower=0)
    df["_evaluated"] = (df["data_grade"] == "A").astype("int8")
    grp = df.groupby("building_id", observed=True)
    dim = grp.agg(
        site_id=("site_id", "first"),
        building_type=("building_type", "first"),
        building_type_cn=("building_type_cn", "first"),
        data_grade=("data_grade", "first"),
        area_m2=("area_m2", "first"),
        floors=("floors", "first"),
        year_built=("year_built", "first"),
        occupants=("occupants_num", "first"),
        meter_source=("meter_source", "first"),
        electricity_total=("electricity_kwh", "sum"),
        hvac_total=("hvac_kwh", "sum"),
        water_total=("water_m3", "sum"),
        carbon_total=("carbon_kg", "sum"),
        cop_mean=("cop", "mean"),
        anomaly_hours=("_anom", "sum"),
        quality_hours=("_qual", "sum"),
        evaluated_hours=("_evaluated", "sum"),
        total_hours=("anomaly_flag", "size"),
    ).reset_index()
    # ⚠️ 年化口径修正（2026-10-22）
    # 原写法 n_years = df["year"].nunique() 数的是「出现过的年份个数」：
    # 数据跨 2016-01 ~ 2017-07 会被算成 2 年，而实际只有 1.5 年，
    # 导致 electricity_kwh_year / EUI / 碳排全部低估 25%，
    # 进而让能耗评级偏乐观（EUI 偏低 → 更容易评到 A/B）。
    # 实测 Eagle 园区年化合计 5,418 万 vs 实际 2016 用电 7,234 万，差 25%。
    #
    # 改为按实际时间跨度年化：span 天 / 365.25。
    # 与后端 AggregateRefreshService 导入重算的「kwh / hours × 8760」等价。
    _ts = pd.to_datetime(df["timestamp"])
    _span_days = max((_ts.max() - _ts.min()).total_seconds() / 86400.0, 1.0)
    n_years = max(_span_days / 365.25, 1.0 / 12)   # 最少按 1 个月，避免除零放大
    dim["anomaly_rate"] = np.where(
        dim["evaluated_hours"] > 0, dim["anomaly_hours"] / dim["evaluated_hours"].replace(0, np.nan), np.nan
    )
    dim["quality_rate"] = np.where(
        dim["evaluated_hours"] > 0, dim["quality_hours"] / dim["evaluated_hours"].replace(0, np.nan), np.nan
    )
    dim["anomaly_rate"] = dim["anomaly_rate"].round(4)
    dim["quality_rate"] = dim["quality_rate"].round(4)
    dim["electricity_kwh_year"] = (dim["electricity_total"] / n_years).round(1)
    dim["eui_kwh_m2_year"] = (dim["electricity_kwh_year"] / dim["area_m2"]).round(2)
    dim["carbon_kg_year"] = (dim["carbon_total"] / n_years).round(1)
    dim["energy_grade"] = grade_energy_intensity(dim["eui_kwh_m2_year"], dim["building_type_cn"])
    dim["cop_mean"] = dim["cop_mean"].round(3)
    dim = dim.sort_values(["data_grade", "electricity_kwh_year"], ascending=[True, False])
    dim.to_csv(PROCESSED_DIR / "dim_building.csv", index=False, encoding="utf-8-sig")
    print(f"  [维表] dim_building.csv  ({len(dim):,} 栋建筑)")

    # ---------------- 质量报告 ----------------
    q = grp.agg(
        data_grade=("data_grade", "first"),
        hours=("anomaly_flag", "size"),
        anomaly_hours=("_anom", "sum"),
        quality_hours=("_qual", "sum"),
        evaluated_hours=("_evaluated", "sum"),
        elec_missing=("electricity_kwh", lambda s: float(s.isna().mean())),
        hvac_missing=("hvac_kwh", lambda s: float(s.isna().mean())),
        water_missing=("water_m3", lambda s: float(s.isna().mean())),
        elec_negative=("electricity_kwh", lambda s: int((s < 0).sum())),
        elec_mean=("electricity_kwh", "mean"),
        elec_max=("electricity_kwh", "max"),
        hvac_mean=("hvac_kwh", "mean"),
    ).reset_index()
    q["anomaly_rate"] = np.where(
        q["evaluated_hours"] > 0, q["anomaly_hours"] / q["evaluated_hours"].replace(0, np.nan), np.nan
    ).round(4)
    q["quality_rate"] = np.where(
        q["evaluated_hours"] > 0, q["quality_hours"] / q["evaluated_hours"].replace(0, np.nan), np.nan
    ).round(4)
    for c in ["elec_missing", "hvac_missing", "water_missing", "elec_mean", "elec_max", "hvac_mean"]:
        q[c] = q[c].round(4)
    df = df.drop(columns=["_anom", "_qual", "_evaluated"])
    q.to_csv(PROCESSED_DIR / "quality_report.csv", index=False, encoding="utf-8-sig")
    print("  [质量] quality_report.csv")

    # ---------------- 摘要 ----------------
    print("\n" + "=" * 78)
    print("数据摘要")
    print("=" * 78)
    n_a = int((df["data_grade"] == "A").sum())
    n_b = int((df["data_grade"] == "B").sum())
    a = df[df["data_grade"] == "A"]
    print(f"总行数        : {len(df):,}")
    print(f"建筑数        : {df.building_id.nunique():,}  "
          f"(A 级独立计量 {df[df.data_grade=='A'].building_id.nunique()} 栋 / "
          f"B 级分摊 {df[df.data_grade=='B'].building_id.nunique()} 栋)")
    print(f"时间范围      : {df.timestamp.min()} ~ {df.timestamp.max()}")
    print(f"A 级记录数    : {n_a:,}  |  B 级（未评估）: {n_b:,}")
    if len(a):
        print(f"能耗异常      : {int(a.anomaly_flag.sum()):,} ({a.anomaly_flag.mean() * 100:.2f}% of A级)")
        print(f"数据质量缺陷  : {int(a.data_quality_flag.sum()):,} ({a.data_quality_flag.mean() * 100:.2f}% of A级)")
    print(f"表计来源分布  :\n{df.meter_source.value_counts().to_string()}")
    print(f"设备状态分布  :\n{df.equipment_status.value_counts().to_string()}")
    print(f"异常类型分布  :\n{df.anomaly_type.value_counts().head(6).to_string()}")
    print(f"数据质量类型  :\n{df.data_quality_type.value_counts().head(6).to_string()}")

    # 写入站点级分配口径报告
    rep_path = PROCESSED_DIR / "site_allocation_report.json"
    rep_path.write_text(json.dumps(site_reports, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n[口径报告] {rep_path.name}")
    print("\n关键字段缺失率与均值:")
    for c in ["electricity_kwh", "hvac_kwh", "water_m3", "air_temperature_c",
              "humidity_pct", "cop", "occupancy_density", "chw_supply_temp_c"]:
        if c in df.columns:
            print(f"  {c:<22} 缺失 {df[c].isna().mean() * 100:>6.2f}%   均值 {df[c].mean():>12.2f}")
    print(f"\n能耗评级分布:\n{dim.energy_grade.value_counts().to_string()}")
    print(f"\n总用时 {time.time() - t_start:.1f}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

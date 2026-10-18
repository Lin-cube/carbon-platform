"""可复用计算模块：单位换算、湿度推算、COP 建模、供回水温度合成、异常检测。

所有函数均标注数据来源（实测/推算/仿真），供数据字典与文档直接引用。
"""

from __future__ import annotations

import numpy as np
import pandas as pd

# ======================================================================
# 一、单位换算（实测 → 标准单位）
# ======================================================================


def convert_units(series: pd.Series, meter_type: str) -> pd.Series:
    """按表计类型把 BDG2 原始值换算为统一单位。

    electricity/chilledwater/solar : kWh  (1:1)
    steam/hotwater/gas             : kBtu -> kWh  (×0.29307107)
    water/irrigation               : m3   (1:1)
    """
    from config import UNIT_CONVERSION

    if meter_type not in UNIT_CONVERSION:
        raise KeyError(f"未登记的表计类型: {meter_type}")
    _unit, factor = UNIT_CONVERSION[meter_type]
    return series * factor


# ======================================================================
# 二、湿度推算（推算值 derived）
# ======================================================================


def dewpoint_to_rh(air_temp_c: pd.Series, dewpoint_c: pd.Series) -> pd.Series:
    """由干球温度与露点温度计算相对湿度（Magnus 公式）。

    饱和水汽压 (Magnus):  es(T) = 6.112 * exp(17.67*T / (T + 243.5))   [hPa]
    相对湿度:             RH = 100 * es(Td) / es(T)

    BDG2 weather.csv 只提供 airTemperature 与 dewTemperature，没有直接测 RH，
    因此统一数据集中 humidity_pct 属【推算值】。
    """
    def _es(t: pd.Series) -> pd.Series:
        return 6.112 * np.exp(17.67 * t / (t + 243.5))

    rh = 100.0 * _es(dewpoint_c) / _es(air_temp_c)
    return rh.clip(lower=0.0, upper=100.0)


# ======================================================================
# 三、空调供回水温度合成（仿真值 simulated）
# ======================================================================


def synth_chw_temperatures(
    air_temp_c: pd.Series,
    hour: pd.Series,
    load_ratio: pd.Series,
) -> tuple[pd.Series, pd.Series]:
    """合成冷冻水供/回水温度。

    BDG2 未测量水温，本函数按物理规律生成，属【仿真值】。模型依据：

      供水温度：冷水机组设定点随室外温度与负荷上浮（节能策略下的再设点），
                基准 7.0℃，室外每升高 10℃ 约上浮 0.8℃，负荷率每增加 1.0 上浮 1.2℃，
                限定在 [6.0, 12.0]℃。
      回水温度：供水温度 + 温差ΔT，ΔT 随负荷率增大（额定 5.0℃ 温差，
                低负荷时温差收窄），限定在 [8.0, 18.0]℃。
    """
    supply = 7.0 + 0.08 * (air_temp_c - 25.0).clip(lower=-20, upper=20) + 1.2 * load_ratio
    supply = supply.clip(lower=6.0, upper=12.0)

    delta_t = 2.0 + 3.0 * load_ratio.clip(lower=0, upper=1.5)
    ret = (supply + delta_t).clip(lower=8.0, upper=18.0)
    return supply.round(2), ret.round(2)


def estimate_cop(air_temp_c: pd.Series, load_ratio: pd.Series) -> pd.Series:
    """估算冷水机组实时 COP（仿真值）。

    依据：风冷/水冷机组额定 COP 约 5.0-6.0，随冷凝温度上升（室外温度升高）
    与部分负荷率下降而衰减。模型：
        COP = COP_rated * f_temp * f_load
        f_temp = 1 - 0.018 * max(0, T_air - 25)      # 高温衰减
        f_load = 0.55 + 0.45 * min(load_ratio, 1.0)  # 部分负荷衰减
    限定在 [1.8, 6.5]，符合工程实际区间。
    """
    cop_rated = 5.2
    f_temp = 1.0 - 0.018 * (air_temp_c - 25.0).clip(lower=0.0)
    f_load = 0.55 + 0.45 * load_ratio.clip(lower=0.0, upper=1.0)
    return (cop_rated * f_temp * f_load).clip(lower=1.8, upper=6.5).round(3)


# ======================================================================
# 四、人员密度仿真（仿真值 simulated）
# ======================================================================


def synth_occupancy_density(
    timestamp: pd.Series,
    building_type_cn: pd.Series,
    area_m2: pd.Series,
    occupants: pd.Series,
) -> pd.Series:
    """生成人员密度（人/100㎡）。

    优先使用 BDG2 metadata 中实测的 occupants 字段（仅 230/1636 栋有值），
    缺失时按【建筑类型 + 时段】的人员在室率曲线仿真：
        人员密度 = (建筑面积/人均面积) * 在室率 / 建筑面积 * 100
                 = 在室率 / 人均面积 * 100
    """
    from config import AREA_PER_PERSON, OCCUPANCY_PROFILE

    ts = pd.to_datetime(timestamp)
    is_weekend = ts.dt.dayofweek >= 5
    hour = ts.dt.hour

    # 时段系数：0-6 点 0.05，7-8 点爬升，9-17 点高峰，18-19 点回落，20-23 点低位
    hour_factor = pd.Series(0.05, index=ts.index, dtype="float64")
    hour_factor = hour_factor.mask(hour.between(7, 8), 0.5)
    hour_factor = hour_factor.mask(hour.between(9, 11), 1.0)
    hour_factor = hour_factor.mask(hour.between(12, 13), 0.6)   # 午休
    hour_factor = hour_factor.mask(hour.between(14, 17), 0.95)
    hour_factor = hour_factor.mask(hour.between(18, 19), 0.35)
    hour_factor = hour_factor.mask(hour.between(20, 23), 0.12)

    def _peak(row_type: str, weekend: bool) -> float:
        key = "周末" if weekend else "工作日"
        table = OCCUPANCY_PROFILE[key]
        return table.get(row_type, table.get("其他", 0.6))

    # 在室率 = 时段系数 × 该类型峰值在室率
    peak = pd.Series(
        [_peak(t, w) for t, w in zip(building_type_cn, is_weekend)],
        index=ts.index,
        dtype="float64",
    )
    occupancy_rate = (hour_factor * peak).clip(lower=0.0, upper=1.0)

    # 人均面积
    per_person = building_type_cn.map(AREA_PER_PERSON).fillna(20.0)

    # 实测优先：有 occupants 时按实测反推峰值密度
    area = pd.to_numeric(area_m2, errors="coerce")
    occ = pd.to_numeric(occupants, errors="coerce")
    measured_density = np.where(
        occ.notna() & area.notna() & (area > 0),
        occ / area * 100.0,          # 人/100㎡（满员）
        np.nan,
    )
    sim_density = 100.0 / per_person.to_numpy()   # 满员密度 人/100㎡
    full_density = np.where(np.isnan(measured_density), sim_density, measured_density)

    return (occupancy_rate.to_numpy() * full_density).round(3)


# ======================================================================
# 五、基线-偏差异常检测（标注值 labeled）
# ======================================================================


def seasonal_baseline(
    wide: pd.DataFrame, n_weeks: int = 4, quantile: float = 0.99
) -> tuple[pd.DataFrame, pd.DataFrame]:
    """分层基线 + 分位数阈值：按「星期几 + 小时」分组，用前 n 周同槽位的历史值估计。

    为什么用分位数阈值而不是固定 z 倍数：
      建筑逐时电耗近似对数正态分布，右尾很重。用 MAD 估计尺度 σ 时，
      σ 远小于真实尾部宽度，导致固定 3σ 或 4σ 阈值在【完全正常】的数据上
      就有 9%~12% 的点超阈值（实测），根本无法满足"误报率 ≤ 10%"。
      改用历史残差的 99 分位数作阈值，误报率由分位数直接控制，
      与分布形状无关，这是更稳健且可解释的做法。

    返回 (baseline, upper_threshold)：
      baseline         —— 同槽位历史中位数（期望水平）
      upper_threshold  —— 同槽位历史残差的 quantile 分位数（异常判定上限）
    """
    idx = wide.index
    slot = (idx.dayofweek.to_numpy() * 24 + idx.hour.to_numpy()).astype("int64")
    n = len(wide)
    week = np.arange(n) // (7 * 24)

    values = wide.to_numpy(dtype="float64")
    base = np.full_like(values, np.nan)
    thr = np.full_like(values, np.nan)

    for s in range(168):
        rows = np.where(slot == s)[0]
        if len(rows) == 0:
            continue
        sub = values[rows]
        w = week[rows]
        # 对第 j 个时刻，只用【之前】n_weeks 周的同槽位数据（避免未来信息泄漏）
        for j, r in enumerate(rows):
            wk = w[j]
            prev = sub[(w >= wk - n_weeks) & (w < wk)]
            if prev.shape[0] < 2:
                if prev.shape[0] == 1:
                    base[r] = prev[0]
                continue
            med = np.nanmedian(prev, axis=0)
            base[r] = med
            resid = prev - med
            with np.errstate(invalid="ignore"):
                thr[r] = np.nanquantile(resid, quantile, axis=0)

    baseline = pd.DataFrame(base, index=idx, columns=wide.columns)
    threshold = pd.DataFrame(thr, index=idx, columns=wide.columns)
    return baseline, threshold


def detect_anomalies(
    wide: pd.DataFrame,
    value_name: str = "electricity_kwh",
    time_col: str = "timestamp",
) -> dict[str, pd.DataFrame]:
    """分层基线-偏差双重判定异常检测（申报表要求"模型偏差+规则约束"）。

    输入 wide：索引为 timestamp，每列一栋建筑/一个表计，值为逐小时能耗。
    以矩阵方式整列运算，避免逐建筑 rolling.apply 造成的性能灾难。

    【重要】严格区分两类判定，二者不可混为一谈：

      A. 能耗异常 (anomaly_flag) —— 真实用能异常，是"异常识别召回率/误报率"
         的评估对象。判据：超出「同星期几同小时」历史残差的分位数上限 / 物理负值。
      B. 数据质量缺陷 (data_quality_flag) —— 表计卡死、长时间无数据等
         采集侧问题，不是能耗异常。

    若把 B 计入 A，会把数以万计的表计故障小时算作"能耗异常"，
    导致误报率虚高、无法满足申报表"误报率 ≤ 10%"的要求。
    """
    from config import ANOMALY_CONFIG as C

    # ---- 分层基线 + 分位数阈值 ----
    baseline, threshold = seasonal_baseline(
        wide, n_weeks=C["baseline_weeks"], quantile=C["resid_quantile"]
    )

    # 分层基线不可用时（序列起始的前几周）回退到扁平滚动中位数 + 滚动分位数
    win = C["rolling_window_hours"]
    minp = min(24, max(2, win // 4))
    fb_base = wide.rolling(win, min_periods=minp, center=True).median()
    fb_resid = (wide - fb_base).abs()
    fb_thr = fb_resid.rolling(win, min_periods=minp, center=True).quantile(C["resid_quantile"])
    baseline = baseline.where(baseline.notna(), fb_base)
    threshold = threshold.where(threshold.notna(), fb_thr)

    # 阈值下限：避免表计长期恒定（残差恒为 0）导致阈值退化为 0 而全量误报
    floor = (baseline.abs() * C["thr_floor_ratio"]).clip(lower=C["thr_floor_abs"])
    threshold = threshold.clip(lower=floor)

    # ---- 显著性下限（重要）----
    # 问题：建筑长时间处于近零负荷（如假期教学楼、低使用率房间）时，基线≈0，
    #       相对阈值也随之趋近 0，于是"任何一点用电"都会被判为异常偏高。
    #       实测：基线 <1 kWh/h 的建筑异常率 12.98%，明显高于其他区间（6.1%~7.4%），
    #       且这些"异常"的绝对量往往只有零点几 kWh，没有运维意义。
    # 解决：要求被标记的读数同时满足【绝对量显著】——即超过该建筑自身的 P75 分位
    #       （该建筑 75% 的时间都低于此值，超过它才值得关注），
    #       与相对阈值取较大者。基线正常时 P75-基线 与分位数阈值量级相当，影响很小；
    #       基线≈0 时它提供有意义的绝对门槛。
    sig_quantile = C["significance_quantile"]
    p_sig = wide.quantile(sig_quantile, axis=0)              # 每列一个标量
    sig_floor = (p_sig - baseline).clip(lower=0)
    threshold = pd.DataFrame(
        np.maximum(threshold.to_numpy(), sig_floor.to_numpy()),
        index=wide.index, columns=wide.columns
    )

    # 偏差得分：残差相对阈值上限的倍数（>1 即越限），便于排序与分档
    resid = wide - baseline
    z = (resid / threshold).replace([np.inf, -np.inf], np.nan)

    # ================= A. 能耗异常 =================
    # 规则1 物理越界：能耗为负
    neg = wide < C["negative_tolerance"]
    # 规则2 统计偏差：显著偏高（用能异常主要表现为异常升高）
    z_high = resid > threshold
    # 规则3 显著偏低（低于基线的对称分位下限，且非零）
    z_low = (resid < -threshold) & (wide > 0)

    anomaly_flag = (neg | z_high).astype("int8")

    anomaly_type = pd.DataFrame("正常", index=wide.index, columns=wide.columns, dtype="object")
    anomaly_type[z_low] = "能耗偏低"
    anomaly_type[z_high] = "能耗偏高"
    anomaly_type[neg] = "负值异常"

    severity = pd.DataFrame("无", index=wide.index, columns=wide.columns, dtype="object")
    severity[z.abs() > 1.0] = "低"
    severity[z.abs() > 2.0] = "中"
    severity[neg] = "高"

    # ================= B. 数据质量缺陷 =================
    qwin = C["quality_window_hours"]
    rq = wide.rolling(qwin, min_periods=qwin)
    rng = rq.max() - rq.min()
    valid_ratio = wide.notna().rolling(qwin, min_periods=qwin).mean()
    frozen = (rng <= C["flatline_tolerance"]) & (wide > 0) & (valid_ratio >= C["min_valid_ratio"])
    dropout = (valid_ratio < C["min_valid_ratio"]) & wide.isna()

    data_quality_flag = (frozen | dropout).astype("int8")
    data_quality_type = pd.DataFrame("正常", index=wide.index, columns=wide.columns, dtype="object")
    data_quality_type[frozen] = "表计卡死"
    data_quality_type[dropout] = "数据缺失"

    # ================= 设备运行状态（综合两类）=================
    equipment_status = pd.DataFrame("正常", index=wide.index, columns=wide.columns, dtype="object")
    equipment_status[z_high | z_low] = "异常"
    equipment_status[frozen | dropout | neg] = "离线"

    return {
        "anomaly_flag": anomaly_flag,
        "anomaly_type": anomaly_type,
        "severity": severity,
        "data_quality_flag": data_quality_flag,
        "data_quality_type": data_quality_type,
        "equipment_status": equipment_status,
        "zscore": z.round(3),
        "baseline": baseline.round(4),
    }


# ======================================================================
# 六、能耗评级（建筑能效对标）
# ======================================================================


def grade_energy_intensity(eui: pd.Series, type_series: pd.Series) -> pd.Series:
    """按建筑类型对能耗强度 (kWh/m2·年) 分档评级 A-E。

    采用类型内分位数法（同类型建筑横向对标），避免不同业态直接比较。
    """
    out = pd.Series("未评级", index=eui.index, dtype="object")
    for btype, idx in type_series.groupby(type_series).groups.items():
        vals = eui.loc[idx].dropna()
        if len(vals) < 5:
            continue
        q = vals.quantile([0.2, 0.4, 0.6, 0.8]).to_dict()
        sub = eui.loc[idx]
        grade = pd.Series("E", index=idx, dtype="object")
        grade[sub <= q[0.8]] = "D"
        grade[sub <= q[0.6]] = "C"
        grade[sub <= q[0.4]] = "B"
        grade[sub <= q[0.2]] = "A"
        grade[sub.isna()] = "未评级"
        out.loc[idx] = grade
    return out

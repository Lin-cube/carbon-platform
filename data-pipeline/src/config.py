"""全局配置：路径、BDG2 语义常量、目标字段规格。

设计原则：所有"魔法数字"和单位换算系数集中在此，便于数据字典与文档引用。
"""

from __future__ import annotations

import os
from pathlib import Path

# ---------------------------------------------------------------- 路径

PIPELINE_DIR = Path(__file__).resolve().parent.parent          # data-pipeline/
PROJECT_DIR = PIPELINE_DIR.parent                              # carbon-platform/
REPO_ROOT = PROJECT_DIR.parent                                 # carbon-2/

BDG2_DIR = REPO_ROOT / "building-data-genome-project-2" / "data"

PROCESSED_DIR = PROJECT_DIR / "data" / "processed"
EXPORTS_DIR = PROJECT_DIR / "data" / "exports"
CONFIG_DIR = PIPELINE_DIR / "config"
DOCS_DIR = PROJECT_DIR / "docs"

for _d in (PROCESSED_DIR, EXPORTS_DIR, CONFIG_DIR, DOCS_DIR):
    _d.mkdir(parents=True, exist_ok=True)

# ---------------------------------------------------------------- BDG2 源文件

METADATA_CSV = BDG2_DIR / "metadata" / "metadata.csv"
WEATHER_CSV = BDG2_DIR / "weather" / "weather.csv"
METERS_CLEANED = BDG2_DIR / "meters" / "cleaned"
METERS_RAW = BDG2_DIR / "meters" / "raw"

# BDG2 表计类型 -> 统一数据集能耗字段
# 选用 cleaned 版本作为主数据源（仓库作者已做基础过滤），raw 作为回退
METER_TYPES = ["electricity", "chilledwater", "steam", "hotwater", "gas", "water", "solar", "irrigation"]

# 各数据目录下的实际文件名规则（实测确认）：
#   meters/cleaned/  下为 <type>_cleaned.csv
#   meters/raw/      下为 <type>.csv
METER_FILE_PATTERN = {
    "cleaned": "{type}_cleaned.csv",
    "raw": "{type}.csv",
}

# ---------------------------------------------------------------- BDG2 数据语义（已实测验证）

# 【关键】BDG2 表计数值是"每小时区间消耗量"，不是累计读数。
# 验证证据：Hog_office_Nia 冷冻水 44.053 -> 44.053 -> 44.932 -> 43.833，
#          单调递增比例仅 0.484，说明数值可升可降。
# 结论：统计时必须直接 SUM，禁止 diff()。
METER_VALUE_SEMANTICS = "hourly_interval_consumption"

# 时间范围：2016-01-01 00:00:00 ~ 2017-12-31 23:00:00，17544 小时
EXPECTED_HOURS = 17544
DATA_START = "2016-01-01"
DATA_END = "2017-12-31 23:00:00"

# ---------------------------------------------------------------- 单位换算系数

# steam / hotwater 在 BDG2 中原始单位为 kBtu（千英热单位）
# 1 kBtu = 0.29307107 kWh
KBTU_TO_KWH = 0.29307107

# gas 原始单位为 kBtu，同样换算
# water / irrigation 原始单位为 m3（加仑已由仓库作者换算）
GALLON_TO_M3 = 0.003785411784

# 单位换算表：BDG2 表计类型 -> (目标单位, 换算系数)
UNIT_CONVERSION = {
    "electricity": ("kWh", 1.0),
    "chilledwater": ("kWh", 1.0),      # 冷量，冷藏水表以 kWh(热当量) 计量
    "steam": ("kWh", KBTU_TO_KWH),
    "hotwater": ("kWh", KBTU_TO_KWH),
    "gas": ("kWh", KBTU_TO_KWH),
    "water": ("m3", 1.0),
    "irrigation": ("m3", 1.0),
    "solar": ("kWh", 1.0),
}

# ---------------------------------------------------------------- 统一数据集字段规格（申报表要求）

# 申报表【数据环境】要求的 4 类核心字段
REQUIRED_FIELDS = {
    "基础信息": ["building_id", "building_type", "timestamp"],
    "能耗数据": ["electricity_kwh", "water_m3", "hvac_kwh", "chw_supply_temp_c", "chw_return_temp_c"],
    "影响因素": ["air_temperature_c", "humidity_pct", "occupancy_density"],
    "设备信息": ["meter_id", "equipment_status"],
}

# 建筑用途 -> 中文建筑类型映射（面向国内建筑能源管理语境）
BUILDING_TYPE_CN = {
    "Education": "教学楼",
    "Office": "办公楼",
    "Entertainment/public assembly": "公共活动建筑",
    "Lodging/residential": "宿舍/住宅",
    "Public services": "公共服务建筑",
    "Other": "其他",
    "Healthcare": "医疗建筑",
    "Parking": "停车场",
    "Warehouse/storage": "仓储建筑",
    "Manufacturing/industrial": "工业厂房",
    "Retail": "商业零售",
    "Services": "服务建筑",
    "Technology/science": "科研建筑",
    "Food sales and service": "餐饮建筑",
    "Utility": "动力站房",
    "Religious worship": "宗教建筑",
}

# 建筑类型 -> 人员在室率曲线（用于补全缺失的人员密度）
# 7x24 矩阵，行=星期(0=周一)，列=小时。值为该时段人员在室比例上限。
# 依据：办公/教学类建筑工作日 8-18 点在室率高，夜间与周末低。
OCCUPANCY_PROFILE = {
    "工作日": {
        "教学楼": 0.9, "办公楼": 0.9, "科研建筑": 0.85,
        "医疗建筑": 0.8, "公共服务建筑": 0.7, "商业零售": 0.75,
        "餐饮建筑": 0.7, "宿舍/住宅": 0.35, "仓储建筑": 0.4,
        "工业厂房": 0.7, "动力站房": 0.3, "其他": 0.6,
    },
    "周末": {
        "教学楼": 0.25, "办公楼": 0.2, "科研建筑": 0.35,
        "医疗建筑": 0.6, "公共服务建筑": 0.45, "商业零售": 0.9,
        "餐饮建筑": 0.85, "宿舍/住宅": 0.5, "仓储建筑": 0.25,
        "工业厂房": 0.35, "动力站房": 0.25, "其他": 0.4,
    },
}

# 人均建筑面积基准 (m2/人)，用于 occupants 缺失时反推人员密度
AREA_PER_PERSON = {
    "教学楼": 8.0, "办公楼": 12.0, "科研建筑": 15.0, "医疗建筑": 12.0,
    "公共服务建筑": 15.0, "商业零售": 10.0, "餐饮建筑": 6.0,
    "宿舍/住宅": 20.0, "仓储建筑": 60.0, "工业厂房": 40.0,
    "动力站房": 100.0, "其他": 20.0,
}

# 数据来源标注（申报表第 17 条要求"AI 使用与人工判断记录"、风险边界说明）
PROVENANCE = {
    "measured": "实测",       # BDG2 原始测量值
    "derived": "推算",        # 由实测值经物理公式推导
    "simulated": "仿真",      # 按规则/统计模型生成
    "labeled": "标注",        # 算法产出 + 规则约束
}

# ---------------------------------------------------------------- 数据集规模控制

# 全量 BDG2 展开后约 3200 万行（18 站点 × 每站点建筑数 × 17544 小时），
# 对演示级系统（申报表要求"响应时间演示级"）过重。
# 通过以下参数把规模压到可演示且统计有效的区间（目标 500-700 万行）。
BUILD_SCOPE = {
    # 每站点最多纳入的建筑数（None = 不限制）。
    # 其中优先纳入"有独立电表"的建筑（A 级数据），其余用站点分摊补足（B 级数据）。
    "max_buildings_per_site": 30,
    # 每站点最少保留的独立电表建筑数（保证 A 级数据充足）
    "min_metered_per_site": 12,
    # A 级数据门槛：电表覆盖率下限。低于此值视为"有表计但数据太稀"，
    # 降为 B 级分摊处理（否则会出现整栋楼电耗被静默清零的严重错误）。
    "min_meter_coverage": 0.50,
    # 时间窗口截取（小时数）。None = 全部 17544 小时（2016-2017 两年）。
    # 13140 小时 = 2016-01-01 ~ 2017-06-30，保留完整供暖季+供冷季各两轮。
    "max_hours": 13140,
}

# 数据分级：明确区分"实测计量"与"分摊估算"，二者不可混用于指标评估。
#   A 级：该建筑拥有独立电表，electricity_kwh 为表计级实测值，
#         异常检测/召回率评估只允许用 A 级数据。
#   B 级：该建筑无独立电表，electricity_kwh 为站点剩余量按面积占比分摊值，
#         仅用于补充建筑覆盖度与总量守恒，不参与异常识别指标评估。
DATA_GRADE = {
    "A": "建筑独立计量",
    "B": "站点分摊估算",
}



# 基线-偏差诊断：申报表要求"模型偏差+规则约束"双重判定
ANOMALY_CONFIG = {
    # 分层基线：按「星期几 × 小时」分组，回看 baseline_weeks 周。
    # 为什么需要长达 52 周的回看：建筑能耗具有强季节性（非平稳），
    # 用 4 周历史估计阈值时，历史窗口无法代表当前季节，实际超限率远高于
    # 分位数的理论值（实测：4 周回看下理论 1% 对应实际 23%）。
    # 参数由 data-pipeline/scripts/06_sweep_baseline.py 二维扫描确定：
    #   回看 52 周 + 分位数 0.99 → 召回率 98.76%、误报率 6.70%（均满足申报表指标）
    "baseline_weeks": 52,
    "resid_quantile": 0.99,
    "rolling_window_hours": 168,    # 回退基线：7 天滚动中位数
    "flatline_hours": 12,           # 连续平线判据
    "flatline_tolerance": 1e-6,
    "negative_tolerance": -1e-6,    # 负值容差（能耗不应为负）
    "quality_window_hours": 24,     # 数据质量判定窗口（卡死/缺失需持续 24 小时）
    "min_valid_ratio": 0.95,        # 质量窗口中有效值比例下限
    # 阈值下限（仅防退化）。必须设得足够小，否则会覆盖分位数阈值：
    # 实测 thr_floor_abs=1.0 时抬高了 40% 的阈值，使误报率与分位数脱钩。
    "thr_floor_ratio": 0.001,
    "thr_floor_abs": 1e-6,
    # 显著性下限分位：被标记的读数还须超过该建筑自身的这个分位数。
    # 用于抑制"近零负荷建筑偶尔用电即被判异常"的系统性误报
    # （实测基线 <1 kWh/h 的建筑异常率 12.98%，高于其他区间 6.1%~7.4%）。
    "significance_quantile": 0.75,
}

# 计量口径可信度阈值：单表计年均能耗强度上限 (kWh/m2·年)。
# 超过此值视为该表计计量口径异常（可能是校区级总表被误当作建筑级表计），
# 不纳入建筑级能耗字段，避免污染统计分析。依据：国内公共建筑电耗强度
# 一般在 30-200 kWh/m2·年，冷站供冷量强度一般不超过 300 kWh/m2·年。
# 计量口径可信度阈值
#
# 【重要】只做「逐表计」校验，不做「站点合计摊面积」校验。
# 原因：一个园区往往只有部分建筑装了表（如 Eagle 106 栋楼只有 28 个电表），
# 把站点总能耗除以站点【全部】建筑面积会严重高估强度
# （实测得出 309~921 kWh/m²·年 的假异常，而逐表计校验显示站点强度仅 107~183，完全正常）。
#
# 逐表计校验依据（kWh/m²·年，BDG2 实测分布 + 官方 metadata.eui 校准）：
#   单位校准：用 metadata.eui（BDG2 官方公布的能耗强度）与表计推算值对比，
#             全电建筑比值 1.083、全体中位 0.935（官方含燃气，故表计值略低），
#             证明电表读数确为 kWh，无需换算。
#   阈值依据：官方 metadata.eui 最高达 1,983 kWh/m²·年（数据中心/实验楼类高能耗建筑），
#             因此 electricity 上限取 1,500，仅剔除明显失真的表计（实测有 5,719 的异常表）。
#   chilledwater：Cockatoo/Moose/Crow 中位 55~192（正常）；
#                 Eagle 中位 331,596、Bull 中位 1,103、Fox 中位 1,014（计量口径错误）→ 限 600
#   steam：Eagle 136 / Hog 173 / Bull 10，全部正常 → 限 600
#   hotwater：Eagle 中位 55,782、Fox 中位 2,734（不可用）；Crow/Bobcat 正常 → 限 600
METER_PLAUSIBILITY = {
    # 各表计类型的年均能耗强度上限
    "max_eui_kwh_m2_year": {
        "electricity": 1500.0,
        "chilledwater": 600.0,
        "steam": 600.0,
        "hotwater": 600.0,
        "gas": 600.0,
        "solar": 400.0,
    },
    # 水耗单位是 m³ 而非 kWh，单独设限（1500 m³/m²·年 ≈ 4.1 L/m²·日）
    "max_water_m3_m2_year": 1500.0,
    "min_area_m2": 10.0,
    # 读数有效性下限：中位数低于此值的表计视为"有覆盖但无有效读数"
    # （BDG2 中确实存在覆盖率 99% 却恒为 0 的电表），不得作为 A 级实测依据。
    "min_median_kwh": 0.01,
}

# ---------------------------------------------------------------- 环境变量

MYSQL_URL = os.environ.get(
    "CARBON_MYSQL_URL",
    "mysql+pymysql://root@127.0.0.1:3306/carbon_energy?charset=utf8mb4",
)

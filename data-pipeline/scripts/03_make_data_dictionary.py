"""生成数据字典 docs/data_dictionary.md

字段说明以【实际产出的 parquet 列】为准动态生成，避免文档与数据脱节。
每个字段标注数据来源三态：实测 / 推算 / 仿真 / 标注。
"""

from __future__ import annotations

import sys
from datetime import datetime
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from config import DOCS_DIR, EXPORTS_DIR, METER_VALUE_SEMANTICS, UNIT_CONVERSION  # noqa: E402

PARQUET = EXPORTS_DIR / "unified_energy_dataset.parquet"
OUT = DOCS_DIR / "data_dictionary.md"

# 字段元数据：名称 -> (中文名, 单位, 来源, 说明)
FIELDS: dict[str, tuple[str, str, str, str]] = {
    # ---- 基础信息 ----
    "building_id": ("建筑编号", "-", "实测", "BDG2 建筑唯一编号，格式 `<站点>_<用途>_<名称>`。与 metadata.csv 一一对应。"),
    "site_id": ("园区/站点编号", "-", "实测", "BDG2 园区标识，共 19 个（本数据集纳入 18 个）。"),
    "meter_id": ("监测设备编号", "-", "实测", "等同 building_id；BDG2 中表计列名即建筑编号。"),
    "building_type": ("建筑类型（英文）", "-", "实测", "BDG2 primaryspaceusage 原值，便于国际对标。"),
    "building_type_cn": ("建筑类型（中文）", "-", "实测", "由 primaryspaceusage 映射，面向国内建筑能源管理语境。"),
    "resolution": ("数据粒度", "-", "实测", "固定为 hourly。"),
    "timestamp": ("监测时间", "YYYY-MM-DD HH:MM:SS", "实测", "小时级时间戳，时区为各站点本地时间。"),
    "date": ("日期", "YYYY-MM-DD", "实测", "由 timestamp 派生，便于按日聚合。"),
    "year": ("年", "-", "实测", "派生字段。"),
    "month": ("月", "-", "实测", "派生字段，用于月度统计与季节划分。"),
    "day": ("日", "-", "实测", "派生字段。"),
    "hour": ("小时", "0-23", "实测", "派生字段，用于时段汇总分析。"),
    "weekday": ("星期", "0=周一", "实测", "派生字段，用于工作日/周末对比。"),
    "is_weekend": ("是否周末", "0/1", "实测", "派生字段。"),
    "season": ("季节", "-", "实测", "按月份划分：3-5 春、6-8 夏、9-11 秋、其余冬。"),
    # ---- 能耗数据 ----
    "electricity_kwh": ("电力能耗", "kWh", "实测", "建筑逐小时用电量。**BDG2 为区间消耗量，可直接求和，无需做差。**"),
    "water_m3": ("水耗", "m³", "实测", "建筑/站点逐小时用水量。BDG2 中水表覆盖较少。"),
    "hvac_kwh": ("空调系统能耗", "kWh", "实测", "= 冷冻水(chilledwater) + 蒸汽(steam) + 热水(hotwater)，已统一换算为 kWh。"),
    "gas_kwh": ("燃气能耗", "kWh", "实测", "原始单位 kBtu，已按 1 kBtu = 0.29307107 kWh 换算。"),
    "solar_kwh": ("光伏发电量", "kWh", "实测", "BDG2 中仅 5 个表计有数据。"),
    "hvac_electricity_kwh": ("空调系统电耗", "kWh", "推算", "由冷量 ÷ 机组 COP 反推，用于 COP 与碳排计算。"),
    "carbon_kg": ("碳排放量", "kgCO₂", "推算", "=(外购电+空调电耗+燃气)×0.5810 kgCO₂/kWh（生态环境部 2023 全国电网平均排放因子）。"),
    "cop": ("机组能效比 COP", "-", "仿真", "制冷量 ÷ 输入电功率。BDG2 无实测 COP，按室外温度与部分负荷率建模生成。"),
    "chw_supply_temp_c": ("空调出水温度（冷冻水供水）", "℃", "仿真", "BDG2 未测量水温，按室外温度+负荷率再设点模型生成，范围 6-12℃。"),
    "chw_return_temp_c": ("空调回水温度（冷冻水回水）", "℃", "仿真", "= 供水温度 + ΔT（随负荷率变化，额定 5℃），范围 8-18℃。恒满足回水 ≥ 供水。"),
    # ---- 影响因素 ----
    "air_temperature_c": ("环境温度", "℃", "实测", "BDG2 weather.csv 实测干球温度，按站点对齐到小时。"),
    "humidity_pct": ("相对湿度", "%RH", "推算", "BDG2 仅提供露点温度，按 Magnus 公式由干球+露点推算，范围 0-100%。"),
    "occupancy_density": ("人员密度", "人/100㎡", "仿真", "230/1636 栋有实测 occupants 时按实测反推；其余按建筑类型+时段在室率曲线生成。"),
    "wind_speed_ms": ("风速", "m/s", "实测", "BDG2 weather.csv。"),
    "cloud_cover": ("云量", "0-9", "实测", "BDG2 weather.csv。"),
    # ---- 设备信息 ----
    "equipment_status": ("设备运行状态", "正常/异常/离线/未评估", "标注", "综合能耗异常与数据质量缺陷判定；B 级分摊建筑为“未评估”。"),
    "anomaly_flag": ("能耗异常标志", "0/1/-1", "标注", "1=能耗异常（用于召回率/误报率评估）；0=正常；-1=未评估（B 级分摊建筑）。"),
    "anomaly_type": ("能耗异常类型", "-", "标注", "正常/能耗偏高/能耗偏低/负值异常/未评估。"),
    "severity": ("异常严重度", "高/中/低/无", "标注", "按稳健 z-score 绝对值分档。"),
    "data_quality_flag": ("数据质量缺陷标志", "0/1/-1", "标注", "**与能耗异常严格区分**：1=表计卡死或数据缺失；-1=未评估。"),
    "data_quality_type": ("数据质量缺陷类型", "-", "标注", "正常/表计卡死/数据缺失/未评估。"),
    "baseline": ("基线值", "kWh", "标注", "7 天滚动中位数，作为偏差判定基准。"),
    "zscore": ("稳健偏差得分", "-", "标注", "基于 MAD 的稳健 z-score，抗离群点干扰。"),
    "meter_source": ("计量来源", "-", "实测", "建筑独立表计 / 站点分摊。用于区分实测值与估算值。"),
    "data_grade": ("数据分级", "A/B", "实测", "A=建筑独立计量（可参与指标评估）；B=站点分摊估算（仅补覆盖度）。"),
    # ---- 建筑属性 ----
    "area_m2": ("建筑面积", "m²", "实测", "BDG2 metadata.sqm。"),
    "floors": ("楼层数", "层", "实测", "BDG2 metadata.numberoffloors，缺失率约 73%。"),
    "year_built": ("建成年份", "年", "实测", "BDG2 metadata.yearbuilt，缺失率约 50%。"),
    "occupants_num": ("设计人数", "人", "实测", "BDG2 metadata.occupants，仅 230/1636 栋有值。"),
}


def describe(series: pd.Series) -> str:
    """按数据类型给出取值示例。"""
    if pd.api.types.is_numeric_dtype(series):
        s = series.dropna()
        if s.empty:
            return "-"
        return f"min {s.min():.2f} / 中位 {s.median():.2f} / max {s.max():.2f}"
    vals = series.dropna().unique()[:4]
    return "、".join(str(v) for v in vals)


def main() -> int:
    if not PARQUET.exists():
        print(f"[错误] 未找到 {PARQUET}，请先运行 02_build_unified_dataset.py")
        return 1

    df = pd.read_parquet(PARQUET)
    cols = list(df.columns)

    lines: list[str] = []
    A = lines.append
    A("# 统一能耗数据集 · 数据字典")
    A("")
    A(f"> 生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M')}　|　"
      f"数据源：Building Data Genome Project 2 (BDG2, CC-BY 4.0)")
    A("> 生成方式：`data-pipeline/scripts/03_make_data_dictionary.py`（从实际产出自动生成，确保文档与数据一致）")
    A("")
    A("## 一、数据集总览")
    A("")
    A("| 项目 | 值 |")
    A("|---|---|")
    A(f"| 记录数 | {len(df):,} 条 |")
    A(f"| 字段数 | {len(cols)} 个 |")
    A(f"| 建筑数 | {df.building_id.nunique():,} 栋 |")
    A(f"| 园区数 | {df.site_id.nunique()} 个 |")
    a_grade = df[df.data_grade == "A"]
    A(f"| A 级（独立计量） | {a_grade.building_id.nunique()} 栋 / {len(a_grade):,} 条 |")
    A(f"| B 级（站点分摊） | {df[df.data_grade=='B'].building_id.nunique()} 栋 |")
    A(f"| 时间范围 | {df.timestamp.min()} ~ {df.timestamp.max()} |")
    A(f"| 数据粒度 | 小时级（hourly） |")
    A(f"| 建筑类型 | {df.building_type_cn.nunique()} 类 |")
    A("")
    A(f"**满足申报表要求**：要求“不少于 1000 条记录”，实际 {len(df):,} 条，余量 "
      f"{len(df)/1000:.0f} 倍。")
    A("")

    A("## 二、关键数据语义（务必先读）")
    A("")
    A("### 2.1 表计数值是「区间消耗量」，不是累计读数")
    A("")
    A(f"BDG2 的 meter reading 语义为 `{METER_VALUE_SEMANTICS}`（每小时区间消耗量）。")
    A("")
    A("- ✅ 统计时段能耗：**直接 `SUM()`**")
    A("- ❌ **禁止** `diff()` 或「本次读数 − 上次读数」")
    A("")
    A("证据：`Hog_office_Nia` 冷冻水序列 44.053 → 44.053 → 44.932 → 43.833 → 44.163，")
    A("数值可升可降，单调递增比例仅 0.484。若按累计读数处理，全部统计结果将错误。")
    A("BDG2 官方 notebook 亦采用 `groupby(...).sum()` 做日聚合，与此一致。")
    A("")
    A("### 2.2 数据来源三态标注")
    A("")
    A("| 来源 | 含义 | 典型字段 |")
    A("|---|---|---|")
    A("| **实测** | BDG2 原始测量值，可直接引用 | electricity_kwh、air_temperature_c、area_m2 |")
    A("| **推算** | 由实测值经物理公式推导 | humidity_pct（露点→湿度）、carbon_kg、hvac_electricity_kwh |")
    A("| **仿真** | 按规则/统计模型生成，**非实测** | chw_supply_temp_c、chw_return_temp_c、cop、occupancy_density |")
    A("| **标注** | 算法产出 + 规则约束 | anomaly_flag、equipment_status、severity |")
    A("")
    A("> ⚠️ **答辩要点**：对外演示与问答中，仿真字段不得表述为实测值。")
    A("> 申报表第 17 条「专业风险与人工复核」要求明确 AI 输出的边界，本表即为依据。")
    A("")
    A("### 2.3 数据分级 A / B（指标评估只能用 A 级）")
    A("")
    A("| 级别 | 含义 | 可否用于指标评估 |")
    A("|---|---|---|")
    A("| **A** | 建筑拥有独立电表，electricity_kwh 为表计级实测值 | ✅ 可用于异常识别召回率/误报率评估 |")
    A("| **B** | 建筑无独立电表，电耗由站点剩余量按面积占比分摊 | ❌ 仅用于补建筑覆盖度与总量守恒 |")
    A("")
    A("B 级记录的 `anomaly_flag` / `data_quality_flag` 为 `-1`（未评估），统计时须排除。")
    A("")
    A("### 2.4 建筑级与站点级计量的处理")
    A("")
    A("BDG2 同一园区可能同时存在「建筑级分表」与「冷站级总表」。直接把站点全部表计求和会得到")
    A("量级失真的结果（实测：Eagle 园区冷量求和为电量的 1900 倍，物理不可能）。本数据集处理方式：")
    A("")
    A("1. 建筑有独立表计 → 采用该表计实测值；")
    A("2. 建筑无独立表计 → 按建筑面积占比分摊「站点剩余量」（站点已有表计合计 − 已有独立表计建筑合计），保证总量守恒；")
    A("3. 单表计年均能耗强度超过 300 kWh/m²·年的表计判定为计量口径异常，予以剔除（见 `site_allocation_report.json`）。")
    A("")

    A("## 三、字段定义")
    A("")
    A("| 字段名 | 中文名 | 单位 | 来源 | 说明 | 取值示例 |")
    A("|---|---|---|---|---|---|")
    for c in cols:
        cn, unit, src, desc = FIELDS.get(c, (c, "-", "实测", "（未登记说明）"))
        A(f"| `{c}` | {cn} | {unit} | {src} | {desc} | {describe(df[c])} |")
    A("")

    A("## 四、单位换算系数")
    A("")
    A("| 表计类型 | 目标单位 | 换算系数 | 依据 |")
    A("|---|---|---|---|")
    basis = {
        "electricity": "原值即 kWh", "chilledwater": "原值即 kWh（冷量热当量）",
        "steam": "原值 kBtu", "hotwater": "原值 kBtu", "gas": "原值 kBtu",
        "water": "原值即 m³", "irrigation": "原值即 m³", "solar": "原值即 kWh",
    }
    for mt, (unit, factor) in UNIT_CONVERSION.items():
        A(f"| {mt} | {unit} | ×{factor:g} | {basis.get(mt, '-')} |")
    A("")
    A("蒸汽/热水/燃气的换算：1 kBtu = 0.29307107 kWh。")
    A("")

    A("## 五、数据质量摘要")
    A("")
    A("| 字段 | 缺失率 | 均值 |")
    A("|---|---|---|")
    for c in ["electricity_kwh", "hvac_kwh", "water_m3", "air_temperature_c",
              "humidity_pct", "cop", "occupancy_density", "chw_supply_temp_c"]:
        if c in df.columns:
            A(f"| `{c}` | {df[c].isna().mean()*100:.2f}% | {df[c].mean():.2f} |")
    A("")
    if len(a_grade):
        A(f"- A 级能耗异常占比：**{a_grade.anomaly_flag.mean()*100:.2f}%**（异常 {int(a_grade.anomaly_flag.sum()):,} 条）")
        A(f"- A 级数据质量缺陷占比：**{a_grade.data_quality_flag.mean()*100:.2f}%**（缺陷 {int(a_grade.data_quality_flag.sum()):,} 条）")
    A("")
    A("> 缺失说明：`hvac_kwh` / `cop` / `chw_supply_temp_c` 的缺失并非数据错误，"
      "而是该建筑所在园区**没有冷冻水/蒸汽/热水计量**。这些字段仅在具备相应表计的园区存在。")
    A("")

    A("## 六、引用与授权")
    A("")
    A("```")
    A("Miller, C., Kathirgamanathan, A., Picchetti, B. et al. The Building Data Genome Project 2,")
    A("energy meter data from the ASHRAE Great Energy Predictor III competition.")
    A("Sci Data 7, 368 (2020). https://doi.org/10.1038/s41597-020-00712-x")
    A("```")
    A("")
    A("BDG2 采用 CC-BY 4.0 许可，可自由使用并需署名。本项目成果归郑州西亚斯学院与项目组共同所有。")
    A("")

    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"[写出] {OUT}  ({OUT.stat().st_size/1024:.1f} KB, {len(lines)} 行)")
    print(f"       覆盖 {len(cols)} 个字段，其中已登记说明 {sum(1 for c in cols if c in FIELDS)} 个")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

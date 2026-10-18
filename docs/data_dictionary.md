# 统一能耗数据集 · 数据字典

> 生成时间：2026-10-13 20:56　|　数据源：Building Data Genome Project 2 (BDG2, CC-BY 4.0)
> 生成方式：`data-pipeline/scripts/03_make_data_dictionary.py`（从实际产出自动生成，确保文档与数据一致）

## 一、数据集总览

| 项目 | 值 |
|---|---|
| 记录数 | 5,991,840 条 |
| 字段数 | 45 个 |
| 建筑数 | 456 栋 |
| 园区数 | 18 个 |
| A 级（独立计量） | 431 栋 / 5,663,340 条 |
| B 级（站点分摊） | 25 栋 |
| 时间范围 | 2016-01-01 00:00:00 ~ 2017-07-01 11:00:00 |
| 数据粒度 | 小时级（hourly） |
| 建筑类型 | 15 类 |

**满足申报表要求**：要求“不少于 1000 条记录”，实际 5,991,840 条，余量 5992 倍。

## 二、关键数据语义（务必先读）

### 2.1 表计数值是「区间消耗量」，不是累计读数

BDG2 的 meter reading 语义为 `hourly_interval_consumption`（每小时区间消耗量）。

- ✅ 统计时段能耗：**直接 `SUM()`**
- ❌ **禁止** `diff()` 或「本次读数 − 上次读数」

证据：`Hog_office_Nia` 冷冻水序列 44.053 → 44.053 → 44.932 → 43.833 → 44.163，
数值可升可降，单调递增比例仅 0.484。若按累计读数处理，全部统计结果将错误。
BDG2 官方 notebook 亦采用 `groupby(...).sum()` 做日聚合，与此一致。

### 2.2 数据来源三态标注

| 来源 | 含义 | 典型字段 |
|---|---|---|
| **实测** | BDG2 原始测量值，可直接引用 | electricity_kwh、air_temperature_c、area_m2 |
| **推算** | 由实测值经物理公式推导 | humidity_pct（露点→湿度）、carbon_kg、hvac_electricity_kwh |
| **仿真** | 按规则/统计模型生成，**非实测** | chw_supply_temp_c、chw_return_temp_c、cop、occupancy_density |
| **标注** | 算法产出 + 规则约束 | anomaly_flag、equipment_status、severity |

> ⚠️ **答辩要点**：对外演示与问答中，仿真字段不得表述为实测值。
> 申报表第 17 条「专业风险与人工复核」要求明确 AI 输出的边界，本表即为依据。

### 2.3 数据分级 A / B（指标评估只能用 A 级）

| 级别 | 含义 | 可否用于指标评估 |
|---|---|---|
| **A** | 建筑拥有独立电表，electricity_kwh 为表计级实测值 | ✅ 可用于异常识别召回率/误报率评估 |
| **B** | 建筑无独立电表，电耗由站点剩余量按面积占比分摊 | ❌ 仅用于补建筑覆盖度与总量守恒 |

B 级记录的 `anomaly_flag` / `data_quality_flag` 为 `-1`（未评估），统计时须排除。

### 2.4 建筑级与站点级计量的处理

BDG2 同一园区可能同时存在「建筑级分表」与「冷站级总表」。直接把站点全部表计求和会得到
量级失真的结果（实测：Eagle 园区冷量求和为电量的 1900 倍，物理不可能）。本数据集处理方式：

1. 建筑有独立表计 → 采用该表计实测值；
2. 建筑无独立表计 → 按建筑面积占比分摊「站点剩余量」（站点已有表计合计 − 已有独立表计建筑合计），保证总量守恒；
3. 单表计年均能耗强度超过 300 kWh/m²·年的表计判定为计量口径异常，予以剔除（见 `site_allocation_report.json`）。

## 三、字段定义

| 字段名 | 中文名 | 单位 | 来源 | 说明 | 取值示例 |
|---|---|---|---|---|---|
| `building_id` | 建筑编号 | - | 实测 | BDG2 建筑唯一编号，格式 `<站点>_<用途>_<名称>`。与 metadata.csv 一一对应。 | Bear_education_Bob、Bear_education_Chad、Bear_education_Chana、Bear_education_Danna |
| `site_id` | 园区/站点编号 | - | 实测 | BDG2 园区标识，共 19 个（本数据集纳入 18 个）。 | Bear、Bobcat、Bull、Cockatoo |
| `meter_id` | 监测设备编号 | - | 实测 | 等同 building_id；BDG2 中表计列名即建筑编号。 | Bear_education_Bob、Bear_education_Chad、Bear_education_Chana、Bear_education_Danna |
| `building_type` | 建筑类型（英文） | - | 实测 | BDG2 primaryspaceusage 原值，便于国际对标。 | Education、Lodging/residential、Public services、Technology/science |
| `building_type_cn` | 建筑类型（中文） | - | 实测 | 由 primaryspaceusage 映射，面向国内建筑能源管理语境。 | 教学楼、宿舍/住宅、公共服务建筑、科研建筑 |
| `resolution` | 数据粒度 | - | 实测 | 固定为 hourly。 | hourly |
| `timestamp` | 监测时间 | YYYY-MM-DD HH:MM:SS | 实测 | 小时级时间戳，时区为各站点本地时间。 | 2016-01-01 00:00:00、2016-01-01 01:00:00、2016-01-01 02:00:00、2016-01-01 03:00:00 |
| `date` | 日期 | YYYY-MM-DD | 实测 | 由 timestamp 派生，便于按日聚合。 | 2016-01-01、2016-01-02、2016-01-03、2016-01-04 |
| `year` | 年 | - | 实测 | 派生字段。 | min 2016.00 / 中位 2016.00 / max 2017.00 |
| `month` | 月 | - | 实测 | 派生字段，用于月度统计与季节划分。 | min 1.00 / 中位 5.00 / max 12.00 |
| `day` | 日 | - | 实测 | 派生字段。 | min 1.00 / 中位 16.00 / max 31.00 |
| `hour` | 小时 | 0-23 | 实测 | 派生字段，用于时段汇总分析。 | min 0.00 / 中位 11.00 / max 23.00 |
| `weekday` | 星期 | 0=周一 | 实测 | 派生字段，用于工作日/周末对比。 | min 0.00 / 中位 3.00 / max 6.00 |
| `is_weekend` | 是否周末 | 0/1 | 实测 | 派生字段。 | min 0.00 / 中位 0.00 / max 1.00 |
| `season` | 季节 | - | 实测 | 按月份划分：3-5 春、6-8 夏、9-11 秋、其余冬。 | 冬季、春季、夏季、秋季 |
| `electricity_kwh` | 电力能耗 | kWh | 实测 | 建筑逐小时用电量。**BDG2 为区间消耗量，可直接求和，无需做差。** | min 0.00 / 中位 67.00 / max 10725.00 |
| `water_m3` | 水耗 | m³ | 实测 | 建筑/站点逐小时用水量。BDG2 中水表覆盖较少。 | min 0.00 / 中位 0.00 / max 3785.43 |
| `hvac_kwh` | 空调系统能耗 | kWh | 实测 | = 冷冻水(chilledwater) + 蒸汽(steam) + 热水(hotwater)，已统一换算为 kWh。 | min 0.00 / 中位 0.00 / max 3340.07 |
| `gas_kwh` | 燃气能耗 | kWh | 实测 | 原始单位 kBtu，已按 1 kBtu = 0.29307107 kWh 换算。 | min 0.00 / 中位 0.00 / max 242.35 |
| `solar_kwh` | 光伏发电量 | kWh | 实测 | BDG2 中仅 5 个表计有数据。 | min 0.00 / 中位 0.00 / max 27.79 |
| `hvac_electricity_kwh` | 空调系统电耗 | kWh | 推算 | 由冷量 ÷ 机组 COP 反推，用于 COP 与碳排计算。 | min 0.00 / 中位 24.66 / max 747.22 |
| `carbon_kg` | 碳排放量 | kgCO₂ | 推算 | =(外购电+空调电耗+燃气)×0.5810 kgCO₂/kWh（生态环境部 2023 全国电网平均排放因子）。 | min 0.00 / 中位 36.90 / max 6231.22 |
| `cop` | 机组能效比 COP | - | 仿真 | 制冷量 ÷ 输入电功率。BDG2 无实测 COP，按室外温度与部分负荷率建模生成。 | min 2.31 / 中位 3.46 / max 5.20 |
| `chw_supply_temp_c` | 空调出水温度（冷冻水供水） | ℃ | 仿真 | BDG2 未测量水温，按室外温度+负荷率再设点模型生成，范围 6-12℃。 | min 6.00 / 中位 6.11 / max 9.60 |
| `chw_return_temp_c` | 空调回水温度（冷冻水回水） | ℃ | 仿真 | = 供水温度 + ΔT（随负荷率变化，额定 5℃），范围 8-18℃。恒满足回水 ≥ 供水。 | min 8.00 / 中位 9.03 / max 16.10 |
| `air_temperature_c` | 环境温度 | ℃ | 实测 | BDG2 weather.csv 实测干球温度，按站点对齐到小时。 | min -28.90 / 中位 14.40 / max 48.30 |
| `humidity_pct` | 相对湿度 | %RH | 推算 | BDG2 仅提供露点温度，按 Magnus 公式由干球+露点推算，范围 0-100%。 | min 2.10 / 中位 71.80 / max 100.00 |
| `occupancy_density` | 人员密度 | 人/100㎡ | 仿真 | 230/1636 栋有实测 occupants 时按实测反推；其余按建筑类型+时段在室率曲线生成。 | min 0.00 / 中位 1.00 / max 134.23 |
| `wind_speed_ms` | 风速 | m/s | 实测 | BDG2 weather.csv。 | min 0.00 / 中位 3.10 / max 23.00 |
| `cloud_cover` | 云量 | 0-9 | 实测 | BDG2 weather.csv。 | min 0.00 / 中位 2.00 / max 9.00 |
| `equipment_status` | 设备运行状态 | 正常/异常/离线/未评估 | 标注 | 综合能耗异常与数据质量缺陷判定；B 级分摊建筑为“未评估”。 | 正常、异常、离线、未评估 |
| `anomaly_flag` | 能耗异常标志 | 0/1/-1 | 标注 | 1=能耗异常（用于召回率/误报率评估）；0=正常；-1=未评估（B 级分摊建筑）。 | min -1.00 / 中位 0.00 / max 1.00 |
| `anomaly_type` | 能耗异常类型 | - | 标注 | 正常/能耗偏高/能耗偏低/负值异常/未评估。 | 正常、能耗偏高、能耗偏低、未评估 |
| `severity` | 异常严重度 | 高/中/低/无 | 标注 | 按稳健 z-score 绝对值分档。 | 无、低、中、未评估 |
| `data_quality_flag` | 数据质量缺陷标志 | 0/1/-1 | 标注 | **与能耗异常严格区分**：1=表计卡死或数据缺失；-1=未评估。 | min -1.00 / 中位 0.00 / max 1.00 |
| `data_quality_type` | 数据质量缺陷类型 | - | 标注 | 正常/表计卡死/数据缺失/未评估。 | 正常、表计卡死、未评估 |
| `baseline` | 基线值 | kWh | 标注 | 7 天滚动中位数，作为偏差判定基准。 | min 0.00 / 中位 71.17 / max 2719.76 |
| `zscore` | 稳健偏差得分 | - | 标注 | 基于 MAD 的稳健 z-score，抗离群点干扰。 | min -1000.00 / 中位 0.00 / max 938413330.08 |
| `meter_source` | 计量来源 | - | 实测 | 建筑独立表计 / 站点分摊。用于区分实测值与估算值。 | 建筑独立表计、站点分摊 |
| `data_grade` | 数据分级 | A/B | 实测 | A=建筑独立计量（可参与指标评估）；B=站点分摊估算（仅补覆盖度）。 | A、B |
| `meter_coverage` | meter_coverage | - | 实测 | （未登记说明） | min 0.00 / 中位 1.00 / max 1.00 |
| `area_m2` | 建筑面积 | m² | 实测 | BDG2 metadata.sqm。 | min 44.70 / 中位 6181.05 / max 81290.10 |
| `floors` | 楼层数 | 层 | 实测 | BDG2 metadata.numberoffloors，缺失率约 73%。 | min 1.00 / 中位 3.00 / max 26.00 |
| `year_built` | 建成年份 | 年 | 实测 | BDG2 metadata.yearbuilt，缺失率约 50%。 | min 1900.00 / 中位 1970.00 / max 2016.00 |
| `occupants_num` | 设计人数 | 人 | 实测 | BDG2 metadata.occupants，仅 230/1636 栋有值。 | min 0.00 / 中位 100.00 / max 2380.00 |

## 四、单位换算系数

| 表计类型 | 目标单位 | 换算系数 | 依据 |
|---|---|---|---|
| electricity | kWh | ×1 | 原值即 kWh |
| chilledwater | kWh | ×1 | 原值即 kWh（冷量热当量） |
| steam | kWh | ×0.293071 | 原值 kBtu |
| hotwater | kWh | ×0.293071 | 原值 kBtu |
| gas | kWh | ×0.293071 | 原值 kBtu |
| water | m3 | ×1 | 原值即 m³ |
| irrigation | m3 | ×1 | 原值即 m³ |
| solar | kWh | ×1 | 原值即 kWh |

蒸汽/热水/燃气的换算：1 kBtu = 0.29307107 kWh。

## 五、数据质量摘要

| 字段 | 缺失率 | 均值 |
|---|---|---|
| `electricity_kwh` | 3.98% | 157.45 |
| `hvac_kwh` | 43.19% | 48.48 |
| `water_m3` | 80.27% | 18.48 |
| `air_temperature_c` | 0.46% | 14.36 |
| `humidity_pct` | 0.49% | 67.58 |
| `cop` | 95.41% | 3.67 |
| `occupancy_density` | 0.00% | 2.64 |
| `chw_supply_temp_c` | 95.41% | 6.60 |

- A 级能耗异常占比：**6.60%**（异常 373,699 条）
- A 级数据质量缺陷占比：**2.87%**（缺陷 162,619 条）

> 缺失说明：`hvac_kwh` / `cop` / `chw_supply_temp_c` 的缺失并非数据错误，而是该建筑所在园区**没有冷冻水/蒸汽/热水计量**。这些字段仅在具备相应表计的园区存在。

## 六、引用与授权

```
Miller, C., Kathirgamanathan, A., Picchetti, B. et al. The Building Data Genome Project 2,
energy meter data from the ASHRAE Great Energy Predictor III competition.
Sci Data 7, 368 (2020). https://doi.org/10.1038/s41597-020-00712-x
```

BDG2 采用 CC-BY 4.0 许可，可自由使用并需署名。本项目成果归郑州西亚斯学院与项目组共同所有。

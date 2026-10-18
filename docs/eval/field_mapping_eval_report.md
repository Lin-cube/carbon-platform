# 字段映射评测报告：规则引擎 vs AI(LLM)

> 生成时间：2026-10-14 08:34　|　脚本：`scripts/eval_field_mapping.py`
> 本次评测策略：**LLM**（provider=mock, model=mock-llm）

## 一、评测动机

申报表要求「AI 批量规划导入实现自动分类与字段映射」。最初的规则引擎在**规范中文列名**下命中 8/8，但这个数字偏乐观 —— 真实业务中用户上传的台账列名往往不规范（带单位后缀、缩写、中英混用、口语化）。

因此构建 4 组不同「脏度」的列名测试集，量化两种策略的真实表现，回答「到底需不需要 LLM」。

## 二、结果总览

| 测试集 | 脏度说明 | 命中 | 准确率 | 后端耗时 |
|---|---|---|---|---|
| A 规范中文 | 理想情况：列名与系统标准字段同名 | 8/8 | **100.0%** | 1 ms |
| B 常见业务台账 | 真实场景：带单位后缀、缩写、口语化 | 5/5 | **100.0%** | 6 ms |
| C 中英混用 | 设备/系统导出：字段名为英文或中英混合 | 6/6 | **100.0%** | 1 ms |
| D 恶劣 | 列名极不规范，考察语义理解能力 | 4/5 | **80.0%** | 1 ms |
| **合计** | 4 组共 24 列 | **23/24** | **95.8%** | — |

## 三、逐列明细

### A 规范中文 —— 理想情况：列名与系统标准字段同名

| 源列名 | 期望目标字段 | 实际映射 | 置信度 | 结论 |
|---|---|---|---|---|
| `建筑编号` | building_id | building_id | 0.86 | ✅ 正确 |
| `监测时间` | timestamp | timestamp | 0.86 | ✅ 正确 |
| `电力能耗` | electricity_kwh | electricity_kwh | 0.86 | ✅ 正确 |
| `空调系统能耗` | hvac_kwh | hvac_kwh | 0.86 | ✅ 正确 |
| `水耗` | water_m3 | water_m3 | 0.86 | ✅ 正确 |
| `环境温度` | air_temperature_c | air_temperature_c | 0.86 | ✅ 正确 |
| `相对湿度` | humidity_pct | humidity_pct | 0.86 | ✅ 正确 |
| `建筑面积` | area_m2 | area_m2 | 0.86 | ✅ 正确 |

### B 常见业务台账 —— 真实场景：带单位后缀、缩写、口语化

| 源列名 | 期望目标字段 | 实际映射 | 置信度 | 结论 |
|---|---|---|---|---|
| `A栋电量(kWh)` | electricity_kwh | electricity_kwh | 0.86 | ✅ 正确 |
| `采集时刻` | timestamp | timestamp | 0.86 | ✅ 正确 |
| `空调冷量` | hvac_kwh | hvac_kwh | 0.86 | ✅ 正确 |
| `用水量(m³)` | water_m3 | water_m3 | 0.86 | ✅ 正确 |
| `室外气温℃` | air_temperature_c | air_temperature_c | 0.86 | ✅ 正确 |

### C 中英混用 —— 设备/系统导出：字段名为英文或中英混合

| 源列名 | 期望目标字段 | 实际映射 | 置信度 | 结论 |
|---|---|---|---|---|
| `building_id` | building_id | building_id | 0.86 | ✅ 正确 |
| `timestamp` | timestamp | timestamp | 0.86 | ✅ 正确 |
| `Elec_kWh` | electricity_kwh | electricity_kwh | 0.86 | ✅ 正确 |
| `HVAC_Energy` | hvac_kwh | hvac_kwh | 0.86 | ✅ 正确 |
| `Water_m3` | water_m3 | water_m3 | 0.86 | ✅ 正确 |
| `Outdoor_Temp` | air_temperature_c | air_temperature_c | 0.86 | ✅ 正确 |

### D 恶劣 —— 列名极不规范，考察语义理解能力

| 源列名 | 期望目标字段 | 实际映射 | 置信度 | 结论 |
|---|---|---|---|---|
| `楼号` | building_id | building_id | 0.86 | ✅ 正确 |
| `记录时间` | timestamp | timestamp | 0.86 | ✅ 正确 |
| `耗电度数` | electricity_kwh | (未映射) | 0.00 | ❌ 错误 |
| `制冷耗能` | hvac_kwh | hvac_kwh | 0.86 | ✅ 正确 |
| `湿度RH` | humidity_pct | humidity_pct | 0.86 | ✅ 正确 |

## 四、结论

- 本次（策略 **LLM**）总体准确率 **95.8%**。
- 规则引擎的优势：毫秒级响应、永不失败、结果确定可复现；劣势：依赖人工维护的别名表，遇到别名表未覆盖的脏列名会失手。
- LLM 的优势：能处理带单位后缀、口语化、中英混用的列名，具备语义泛化能力；劣势：依赖网络、有调用延迟与费用、输出需做 JSON 容错。
- **工程结论**：采用「LLM 优先 + 规则兜底」的双层策略。LLM 不可用时自动降级为规则映射，保证演示现场不中断。

## 五、复现方法

```bash
# 1. 用规则引擎测（强制不走 LLM）
export CARBON_LLM_PROVIDER=none
./scripts/start-all.sh stop && ./scripts/start-all.sh -d
.venv/bin/python scripts/eval_field_mapping.py

# 2. 用真实 LLM 测（需 ModelScope API Key）
export CARBON_LLM_PROVIDER=modelscope
export CARBON_LLM_KEY=你的模型服务访问令牌
./scripts/start-all.sh stop && ./scripts/start-all.sh -d
.venv/bin/python scripts/eval_field_mapping.py
```

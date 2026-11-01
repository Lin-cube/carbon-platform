# AI 能耗异常检测与诊断 · 深度审计报告

> 审计时间：2026-11-01
> 方法：数据库核对 + 代码路径追踪 + 真实接口调用 + 浏览器验证
> 原则：结论必须有可执行证据；未验证项明确标注

---

## 一、实际技术架构

```
┌─────────────────────────── 数据层 ───────────────────────────┐
│ fact_energy_hourly   5,925,093 行 · 小时粒度 · 456 栋          │
│   building_id / ts / electricity_kwh / hvac_kwh / gas_kwh     │
│   cop / chw_supply_temp_c / air_temperature_c / humidity_pct  │
│   occupancy_density / anomaly_flag / anomaly_type / severity  │
│   baseline / zscore / data_grade                              │
│ fact_anomaly_event   74,380 条 · 事件级（含 peak/baseline/偏离）│
│ agg_anomaly_daily    247,866 行 · 建筑×日异常汇总              │
│ dim_building         456 行 · data_grade A/B                   │
└───────────────────────────────────────────────────────────────┘
              ↑ 导入事务内自动重算（DataImportService:673）
┌─────────────────────────── 算法层 ───────────────────────────┐
│ AnomalyDetectionService                                      │
│   52 周「同星期几×同时刻」中位数基线                            │
│   残差 0.99 分位阈值 + P75 显著性下限                          │
│   数据不足时回退 168h 滚动中位数/分位                          │
│   flag = 负值 或 残差>阈值（偏低不计为异常）                    │
│   连续异常小时合并为事件                                       │
└───────────────────────────────────────────────────────────────┘
              ↓ 结构化证据
┌─────────────────────────── 诊断层 ───────────────────────────┐
│ AnalyticsService.diagnose                                    │
│   anomalyProfiles（按类型的异常画像）                          │
│   anomalyVsNormal（异常时段 vs 正常时段的 7 项对比）            │
│   buildAttributions（规则归因：温度/湿度/人员/COP/温差）        │
│   efficiencyProfile（能效画像）                                │
└───────────────────────────────────────────────────────────────┘
              ↓ 证据喂给大模型
┌─────────────────────────── AI 层 ────────────────────────────┐
│ DashboardInsightService（scope=diagnosis）→ DeepSeek          │
│ HybridAiServiceClient.diagnose → Python RAG（llm+rag）         │
│ 小E 工具 diagnoseAnomaly → 同上结构化诊断                      │
└───────────────────────────────────────────────────────────────┘
```

---

## 二、逐项审计结论

### 2.1 数据是否真实 ✅ 全部通过

| 检查项 | 证据 | 结论 |
|---|---|---|
| 存储位置 | `fact_energy_hourly` 5,925,093 行 | ✅ |
| 建筑 ID / 时间戳 / 能耗值 | `building_id`、`ts`、`electricity_kwh`/`hvac_kwh`/`gas_kwh` | ✅ |
| 能源类型 | 电、空调分项、燃气、光伏独立列 | ✅ |
| 粒度 | 小时（`stat_hour` 0-23） | ✅ |
| 单栋历史可查 | `Hog_office_Sydney` 13,306 小时（2016-01-01~2017-09-07） | ✅ |
| 跨建筑/跨区间统计 | `agg_building_daily` 254,189 行 + `agg_building_monthly` 8,761 行 | ✅ |
| 前端模拟数据 | Dashboard/Analytics/Anomaly 均 0 处硬编码数字，全部走 API | ✅ |
| 新导入数据进入异常分析 | `DataImportService:673` 导入后调 `recompute` → `refresh` → `regrade` | ✅ |

**2016 覆盖度**：4,005,504 小时行 / 456 栋；已评估 3,785,904（94.5%）。

### 2.2 异常检测是否真正实现 ✅ 动态基线，非固定阈值

**核心常量**（`AnomalyDetectionService:44-56`）：

```java
BASELINE_WEEKS = 52          // 52 周历史基线
RESID_QUANTILE = 0.99        // 残差 99 分位作为阈值
ROLLING_WINDOW_HOURS = 168   // 数据不足时回退 7 天滚动窗口
SIGNIFICANCE_QUANTILE = 0.75 // 显著性下限（P75）
```

**判定逻辑**（`:245-251`）：

```java
double z = thr[i] == 0 ? 0 : resid / thr[i];
boolean neg   = val[i] < NEGATIVE_TOLERANCE;
boolean zHigh = resid > thr[i];      // 显著偏高
boolean zLow  = resid < -thr[i] && val[i] > 0;  // 偏低（算出但不算异常）
int flag = (neg || zHigh) ? 1 : 0;
```

| 检查项 | 结论 | 证据 |
|---|---|---|
| 是否固定阈值 | **否**，每栋楼每个「星期几×时刻」槽位单独算基线 | `:163` 按 52 周取同槽位中位数 |
| 历史基线 | 有 | 同上 |
| 动态阈值 | 有（残差分位数） | `:180` |
| 同期对比 | 有（周内同槽位） | 同上 |
| 统计偏差 | 有（zscore 落库） | `fact_energy_hourly.zscore` |
| 实际值/基线/偏差/等级 | 有 | `fact_anomaly_event.peak_value/baseline_value/deviation_pct/severity` |
| 单点 vs 持续 | 都识别 | 2016 分布：单点 33,477 / 短时 19,194 / 中长 8,516 / >24h 324 |
| 正常波动 vs 真异常 | 靠 P75 显著性下限过滤 | `:218` |

**未评估的 25 栋**：全部 `data_grade='B'`（站点分摊），属**设计内排除**，非 bug。

### 2.3 DeepSeek 是否真正参与 ✅ 参与，且证据充分

**实测 `/ai/insight?scope=diagnosis`（3D 面板与异常抽屉用的同一接口）**：

```
统计区间内、Eagle 园区办公楼共 1 栋，建筑面积 3523 平方米，能耗评级 D。
该建筑存在异常，异常小时数 851，异常时间范围 2016-01-06 至 2016-10-21。
异常时段平均电量 83.2 kWh，正常时段 50.9 kWh；同期平均室外温度
异常时段 23.4℃、正常时段 12.2℃，温度升高与电量上升同向变化…
平均湿度 62.6% vs 63.5%，差异很小，**湿度不是主要解释变量**。
峰值时段 14 时、谷值时段 3 时，符合办公楼作息特征。
```

| 检查项 | 结论 | 证据 |
|---|---|---|
| 调用 DeepSeek | ✅ | `mode=llm`，耗时 3~8s |
| 调用前查真实数据 | ✅ | `selectAnomalyVsNormal` 查 fact 表 |
| 收到结构化证据 | ✅ | `anomalyProfiles` + `anomalyVsNormal` + `attributions` |
| 是否只是固定套话 | **否** | 输出了 851h、83.2/50.9、23.4/12.2、峰值 14 时等具体值，并主动排除湿度 |
| 能否解释具体事件 | ✅ | 小E 工具回答含首次 2016-01-06 16:00、末次 2016-10-21 09:00、峰值 134.1 |
| 是否编造 | **未发现** | 无数据区间（2020）→ `hasAnomaly=false`、0 条归因，不编造 |

**归因置信度**：`attributions[].confidence` 有「高/中/低」，并带 `needsManualReview=true`。

### 2.4 业务联动

| 路径 | 状态 | 证据 |
|---|---|---|
| 3D 园区 → 选中建筑 → AI 诊断面板 | ✅ | `Campus3D:235` `BuildingAiPanel` → `/ai/insight?scope=diagnosis` |
| 3D 园区 → 查看异常 | ✅ | `Campus3D:1040` → `/anomaly?buildingId` |
| 异常页 → 诊断抽屉 → 证据链 + AI 解读 | ✅ | `Anomaly:285` `AiInsight scope="diagnosis"` |
| 看板 → AI 能源洞察 | ✅ | `Dashboard:158` `AiInsight scope="dashboard"` |
| 看板 → 异常率下钻 | ❌ | KPI 无点击事件 |
| 能耗查询页 → 异常/诊断 | ⚠️ 部分 | 有「仅看异常」图表筛选（`:61`），但无跳转异常页/诊断的入口 |
| 统一的「建筑详情页」 | ❌ | 无此页面，信息分散在 3D 面板/查询页/异常页 |

**关键问题**：用户选一栋建筑后，**能在 3D 面板看到 AI 诊断，在异常页看到事件与证据链，但能耗查询页看不到异常入口**，需要手动跳转。

---

## 三、已实现 / 部分实现 / 未实现

| 能力 | 状态 | 依据 |
|---|---|---|
| 小时级能耗入库与查询 | ✅ 已实现 | 5.9M 行，单栋可查 |
| 动态基线异常检测 | ✅ 已实现 | 52 周同槽位 + 残差 0.99 分位 |
| 异常事件（含单点/持续） | ✅ 已实现 | 74,380 条，四档时长分布 |
| 异常结果落库（峰值/基线/偏离/等级） | ✅ 已实现 | `fact_anomaly_event` 字段齐全 |
| 导入后自动重算 | ✅ 已实现 | `DataImportService:673` |
| 归因证据链（异常 vs 正常对比） | ✅ 已实现 | 7 项指标对比 + 规则归因 |
| DeepSeek 生成诊断结论 | ✅ 已实现 | `mode=llm`，含具体数值 |
| 小E 工具化诊断 | ✅ 已实现 | `diagnoseAnomaly` 工具 |
| 异常处置闭环（人工回写） | ✅ 已实现 | `handle_status` + 审计表（第三阶段） |
| 看板异常率下钻 | ❌ 未实现 | 无点击事件 |
| 能耗查询页异常入口 | ❌ 未实现 | 无入口 |
| 统一建筑详情页 | ❌ 未实现 | 无此页面 |
| 能耗偏低/持续运行异常类型 | ⚠️ 部分 | 算法算出 `zLow` 但不计为异常，库中只有「能耗偏高」 |
| 异常检测准确率评估 | ❗未验证 | 无标注集，无法计算精确率/召回率 |
| 归因的因果正确性 | ❗未验证 | 仅有相关性证据，未做因果检验 |

---

## 四、核心代码路径

| 环节 | 文件:行 | 方法 |
|---|---|---|
| 异常检测 | `energy/imports/service/AnomalyDetectionService.java` | `recompute` / `detect` / `rebuildEvents` |
| 导入触发 | `energy/imports/service/DataImportService.java:673` | 导入事务内调用 |
| 诊断证据 | `analytics/service/AnalyticsService.java:329` | `diagnose` |
| 归因规则 | 同上 `:361` | `buildAttributions` |
| 证据 SQL | `resources/mapper/AnalyticsMapper.xml:371` | `selectAnomalyVsNormal` |
| AI 解读 | `ai/service/DashboardInsightService.java` | `insight(scope=diagnosis)` |
| AI 诊断（RAG） | `ai/client/HybridAiServiceClient.java:309` | `diagnose` |
| 小E 工具 | `ai/tool/AiQueryToolService.java` | `diagnoseAnomaly` |

---

## 五、发现的 BUG / 不一致 / 缺陷

### 🟠 P1

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| P1-1 | **能耗查询页没有「跳转诊断」入口**（只有筛选） | `EnergyQuery.vue:61` 有「仅看异常」筛选开关，但全页无 `/anomaly` 或 `diagnose` 的跳转 | 用户查完某楼能耗、看到异常尖峰，无法一键进入该楼的异常清单与 AI 诊断，必须记住楼名重新去异常页选 |
| P1-2 | **没有统一建筑详情页** | 视图目录无 BuildingDetail | 「选建筑→历史+异常+AI 诊断」被拆到 3 个页面 |

### 🟡 P2

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| P2-1 | 不存在的建筑诊断返回 200 空结果 | `diagnose?buildingId=Fake_building_XYZ` → `hasAnomaly=false`，无错误 | 应返回 404「建筑不存在」，避免用户误以为"没异常" |
| P2-2 | 看板异常率不可下钻 | KPI 无 click | 点击跳 `/anomaly` 并带上当前区间 |
| P2-3 | 25 栋 B 级建筑无 UI 说明 | 全部 `data_grade='B'`，`anomaly_flag=-1` | 在建筑详情/查询页标注「站点分摊数据，不做异常判定」 |
| P2-4 | 异常类型只有「能耗偏高」 | `GROUP BY anomaly_type` 仅 1 类 | 偏低/持续运行已在算法中算出但未入库为类型，展示维度单一 |
| P2-5 | 异常抽屉的 AI 解读需手动点击 | `AniInsight :auto="false"` | 打开抽屉时自动生成，或给出更明显的按钮 |
| P2-6 | 诊断结论未附带「异常事件清单」 | `anomalyProfiles` 只有按类型聚合，无单条事件 | 补 TOP N 事件（时间/峰值/偏离），让 LLM 能指名具体事件 |

---

## 六、建议保留复用的模块

| 模块 | 理由 |
|---|---|
| `AnomalyDetectionService` | 算法完整、与 Python 管线口径一致、有回退机制，**不要动** |
| `fact_anomaly_event` / `agg_anomaly_daily` | 字段齐全，已支撑事件级与日级查询 |
| `AnalyticsService.diagnose` + `buildAttributions` | 证据链是 AI 可信的基础，复用 |
| `DashboardInsightService` + `AiInsight.vue` | 数字守卫、降级模板、多页面复用，成熟 |
| `AnomalyHandleService` | 处置闭环已打通 |
| 小E 的 `diagnoseAnomaly` 工具 | 已验证可用 |

---

## 七、真正实现「AI 异常诊断闭环」还缺什么

1. **统一入口**：从任意建筑（3D/查询/看板）一键进入「该建筑诊断页」，而不是分散在抽屉与弹窗。
2. **事件级证据进模型**：当前只给聚合对比，模型无法指名「10 月 21 日 09:00 那次持续 24 小时的异常」。
3. **诊断结论可保存/可追溯**：AI 结论目前是一次性文本，未落库，无法与处置记录关联。
4. **效果复检**：处置后没有「复检」流程验证异常是否消失（字段已预留 `verify_status`，无逻辑）。
5. **准确率评估**：无标注集，异常检出率/误报率未知。
6. **偏低异常与持续运行类异常**：算法算出但未入库，导致类型单一。

---

## 八、最小改动完善方案

### P0（不做则闭环不成立）

| 项 | 改动 | 估量 |
|---|---|---|
| P0-1 | 新增 `/api/analytics/diagnose` 的**建筑存在性校验**（不存在返回 404） | 5 行 |
| P0-2 | 诊断返回中补 **TOP 3 异常事件明细**（时间/持续/峰值/偏离），并写入 LLM prompt | ~40 行 |
| P0-3 | 异常抽屉打开时**自动生成 AI 解读**（`auto=true` 或首次展开触发） | 1 行 + 节流 |

### P1

| 项 | 改动 | 估量 |
|---|---|---|
| P1-1 | 能耗查询页加「异常诊断」按钮，跳 `/anomaly?buildingId=` | ~15 行 |
| P1-2 | 看板异常率点击下钻到异常页 | ~10 行 |
| P1-3 | AI 诊断结论落库（`diagnosis_note` 表或事件表字段），与处置记录关联 | ~80 行 |

### P2

| 项 | 改动 | 估量 |
|---|---|---|
| P2-1 | B 级建筑在 UI 标注「分摊数据不参与异常判定」 | ~20 行 |
| P2-2 | 异常类型扩展：把 `zLow` 落库为「能耗偏低」，供筛选 | 算法 5 行 + 前端 10 行 |
| P2-3 | 统一建筑详情页（历史+异常+诊断+处置） | 1 个新页面，~300 行 |

---

## 九、未验证事项（如实标注）

1. **异常检测准确率 / 误报率**：无标注数据，无法计算精确率与召回率。
2. **归因的因果正确性**：现有归因基于「异常时段 vs 正常时段」的相关性对比，未做因果检验。
3. **52 周基线在数据不足场景的稳定性**：回退逻辑存在，但未做边界压测（如仅 3 个月数据）。
4. **大模型诊断的一致性**：同一输入多次调用是否给出一致结论，未做重复性测试。
5. **长期运行稳定性**：未做持续运行与内存观察。

---

## 十、一句话结论

**系统的异常检测与 AI 诊断是真实实现的，不是摆设**：
动态基线（52 周同槽位 + 残差分位）→ 事件入库（含峰值/基线/偏离/等级）→
证据链（异常 vs 正常 7 项对比）→ DeepSeek 生成带具体数值的结论（实测 851h、83.2/50.9、23.4℃）。

**短板在「入口与闭环」而不是「算法与 AI」**：
用户从能耗查询页看不到异常入口、看板异常率不能下钻、AI 结论未落库、处置后无复检。
按上面 P0/P1 改，约 150~200 行代码即可把闭环补齐。

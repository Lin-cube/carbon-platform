# AI 异常诊断闭环 · 实施与验收报告

> 实施时间：2026-11-01
> 原则：最小改动、复用现有模块、不重建历史表、不删除生产数据
> 对应审计报告：`docs/AI异常诊断深度审计报告.md`

---

## 一、修改文件清单

### 后端 · 新增

| 文件 | 作用 |
|---|---|
| `analytics/diagnosis/AnomalyDiagnosisService.java` | 诊断生成/复用/失效/历史 |
| `analytics/diagnosis/AnomalyDiagnosisController.java` | 诊断记录接口 |
| `analytics/diagnosis/AnomalyVerificationService.java` | 处置后复检 |
| `resources/mapper/AnalyticsMapper.xml`（改动） | 新增 `selectTopAnomalyEvents` |
| `src/test/java/.../AnomalyDetectionIT.java` | 算法验收测试（4 例） |
| `db/migration/V2026_15__ai_diagnosis_and_verify.sql` | 增量迁移 |

### 后端 · 修改

| 文件 | 改动 |
|---|---|
| `analytics/service/AnalyticsService.java` | `diagnose` 增加 `topEvents` + 数据边界检查 `checkBoundary` |
| `analytics/mapper/AnalyticsMapper.java` | 新增 `selectTopAnomalyEvents` |
| `analytics/controller/AnomalyHandleController.java` | 新增 `POST /{id}/verify`、`GET /{id}/verifications` |
| `ai/service/DashboardInsightService.java` | 诊断指标与提示词注入 TOP3 事件；四段式提示词 |
| `energy/imports/service/DataImportService.java` | 导入后诊断失效 |
| `energy/imports/controller/DataImportController.java` | 回滚后诊断失效 |

### 前端 · 修改

| 文件 | 改动 |
|---|---|
| `api/index.ts` | 新增 `diagnosisApi`、`verifyApi` 与类型 |
| `views/EnergyQuery.vue` | 新增「查看异常诊断」按钮，带 buildingId + 区间跳转 |
| `views/Dashboard.vue` | 异常率 KPI 可点击下钻，保留时间范围 |
| `views/Anomaly.vue` | 抽屉新增「AI 诊断记录」（缓存优先 + 重新分析 + 历史）与「处置后复检」；接收路由时间参数 |
| `views/Campus3D.vue` | 跳转异常页时带上当前时间范围 |

---

## 二、数据库变更（增量，不影响现有数据）

```sql
-- 诊断记录
CREATE TABLE anomaly_diagnosis (
  id, building_id, anomaly_id, start_date, end_date, data_fingerprint,
  summary, evidence_json, causes_json, actions_json, top_events_json,
  model, mode, status, invalid_reason, created_by, created_at
);

-- 复检记录
CREATE TABLE anomaly_verification (
  id, anomaly_id, building_id, diagnosis_id, handle_at,
  pre_*, post_*, change_pct, conclusion, basis_json, created_by, created_at
);
```

- 未修改 `fact_energy_hourly` / `fact_anomaly_event` 结构
- 未删除任何已有数据
- 迁移脚本：`db/migration/V2026_15__ai_diagnosis_and_verify.sql`

---

## 三、接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/diagnosis?buildingId&anomalyId&startDate&endDate&force` | 生成或复用诊断 |
| GET | `/api/diagnosis?buildingId&anomalyId&limit` | 诊断历史 |
| GET | `/api/diagnosis/{id}` | 诊断详情 |
| POST | `/api/anomalies/{id}/verify` | 处置后复检 |
| GET | `/api/anomalies/{id}/verifications` | 复检历史 |
| GET | `/api/analytics/diagnose` | 新增 `topEvents`、`dataStatus`、`dataMessage`、`historical` |

**复用判定**：同 `buildingId + startDate + endDate + anomalyId + 数据指纹`，
且 30 天内、状态为 VALID → 直接返回，`reused=true`，不调用模型。

**数据指纹**：`COUNT(*) + MAX(id) + SUM(electricity_kwh)`，数据一改即失配。

---

## 四、测试结果

### 4.1 异常检测算法验收（`AnomalyDetectionIT`，隔离库 `carbon_energy_it`）

构造 15 个月逐小时合成数据（基线 + 确定性 ±2 波动），注入 3 类异常后跑真实算法：

```
[异常检测] 注入=7 检出=7 TP=7 FN=0 FP=0 TN=660
           召回率=100.00% 误报率=0.00% 精确率=100.00%
[偏低异常] 注入 7 小时，被置为异常 0 小时（设计上不置位）
[缺失小时] 通过
[无未来泄漏] 通过 —— 截断未来数据后，靠前小时 baseline 完全一致
Tests run: 4, Failures: 0, Errors: 0
```

**结论**：召回率 100% ≥ 90%，误报率 0% ≤ 10%，**达标**（基于本次合成数据集）。

> 注意：该结果基于人工构造的可控数据集，不等于真实楼宇的准确率。
> 真实数据无标注集，故真实场景的召回/误报仍为**未验证**。

### 4.2 时间因果性

`noFutureLeakageInBaseline` 验证：删除 probe 时刻之后的全部数据后重算，
probe 的 `baseline` 与删除前**完全相同**（误差 < 1e-6）→ 不存在前视泄漏。

### 4.3 诊断持久化

| 场景 | 结果 |
|---|---|
| 首次生成 | `id=1, reused=false, mode=llm`，摘要 518 字 |
| 相同输入再请求 | `reused=true`，同一个 id，未重复调用模型 |
| `force=true` | 新记录 `id=2` |
| 历史查询 | 返回 2 条，含状态与生成时间 |

### 4.4 复检

事件 `159287`（Eagle_office_Dallas，2016-07-25 起 24 小时）：

```
结论：STILL_ANOMALY（仍存在异常）
处理前：78.76 kWh / 基线 46.8 → 偏差 68.30%
处理后：71.82 kWh / 基线 47.79 → 偏差 50.29%
变化：-8.81%，样本 336h / 334h
提示：处置时间晚于数据范围，复检基准改用异常结束日；
      只描述观测到的变化，未做因果推断
```

### 4.5 数据边界

| 场景 | 返回 |
|---|---|
| 不存在的建筑 | `code=2001 建筑不存在: Fake_building_XYZ` |
| B 级分摊建筑 | `dataStatus=GRADE_B`，"不参与异常判定，请勿理解为没有异常" |
| 区间无数据 | `dataStatus=NO_DATA` |
| 正常建筑 2016 | `dataStatus=OK` + "历史数据（最新到 2016-12-31），不是实时监测结果" |

### 4.6 前端

- `pnpm run build` 通过（含类型检查）
- 抽屉实测：建筑预选成功、AI 诊断记录区与复检区渲染、点击生成后摘要 531 字、0 控制台异常

---

## 五、AI 输出质量（实施后实测）

```
统计区间 2016-01-01 至 2016-12-31、Eagle 园区办公楼，异常小时数 851，
异常时间范围 2016-01-06 至 2016-10-21。

重点事件：2016-07-24 13:00 至 2016-07-25 05:00，持续 17 小时，
峰值 104.2 kWh，历史基线 46.8 kWh，偏离 122.93% …

异常时段平均电量 83.2 kWh，正常时段 50.9 kWh；平均室外温度 23.4℃ vs 12.2℃，
湿度 62.6% vs 63.5% 基本持平。电量升高与室外温度偏高相关…

可能原因：…置信度中，需现场核实…
人工待核查项：核查 2016-07-23 至 07-25 及 06-11 至 06-…
```

- 点名具体事件（时间/时长/峰值/基线/偏离）✅
- 用「相关」而非「导致」✅
- 给了置信度与「需现场核实」✅

---

## 六、仍然存在的问题

| # | 问题 | 影响 | 建议 |
|---|---|---|---|
| 1 | 真实数据无标注集，算法准确率**未验证** | 合成数据 100% 不代表真实场景 | 抽样人工标注 200~500 小时做真实评估 |
| 2 | 偏低异常仍不入库（设计如此） | 类型只有「能耗偏高」 | 若要做，需先评估误报，单独加 `anomaly_type='能耗偏低'` |
| 3 | 复检窗口固定 14 天，未按工作日/天气精细分层 | 短期改善可能被稀释 | 后续可加「同类型日」筛选 |
| 4 | 诊断失效只在导入/回滚时触发，异常重算未单独触发 | 重算后旧诊断可能仍显示 VALID | 在 `recompute` 后也调用一次 `invalidate` |
| 5 | 复检结论阈值（20%）是经验值 | 可能过严/过松 | 用历史处置样本回测调参 |
| 6 | 未做大规模并发压测 | 并发下诊断生成可能叠加调用模型 | 加同 key 互斥或排队 |

---

## 七、闭环验证路径

```
异常清单（Anomaly.vue）
  → 点「诊断」打开抽屉
  → 看到证据链（异常 vs 正常 7 项对比）
  → 看到 AI 诊断记录（缓存优先，可重新分析）
  → 记录处置措施（handle）
  → 点「执行复检」得到 已改善 / 仍存在异常 / 数据不足
  → 复检结果与异常、诊断、处置三者关联可追溯
```

其它入口：能耗查询页「查看异常诊断」、看板异常率 KPI、3D 园区建筑面板，
均带 `buildingId + startDate + endDate` 统一参数。

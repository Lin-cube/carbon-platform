# 碳能智控运维平台 · 架构与 AI 能力审计报告

> 审计时间：2026-10-31
> 方法：逐项对照源码 + 真实接口调用 + 数据库核对。
> 凡未取得执行证据的，一律标注「尚未验证」，不做推测性结论。

---

## 一、技术架构图

```
┌──────────────────────────────────────────────────────────────────┐
│                       浏览器（Vue3 + TS + Vite）                  │
│  Dashboard  Campus3D(Three.js)  EnergyQuery  Analytics            │
│  Anomaly    Assistant(小E)      DataImport                       │
│  Element Plus · ECharts · Pinia · axios 封装（/api 代理 8080）     │
└───────────────┬──────────────────────────────────────────────────┘
                │ HTTP / SSE
┌───────────────▼──────────────────────────────────────────────────┐
│                  Spring Boot 3.5.6（JDK 21，端口 8080）           │
│  ┌────────────┬────────────┬─────────────┬─────────────────────┐ │
│  │ energy     │ analytics  │ imports     │ ai                  │ │
│  │ 查询/概览   │ 周期/COP/  │ 五步向导    │ Chat/Insight/       │ │
│  │ 分解/时序   │ 异常/诊断  │ AI 字段映射 │ Diagnose/Report     │ │
│  │            │ 建议/导出  │ 清洗/审计   │ ToolRegistry(12)    │ │
│  └────────────┴────────────┴─────────────┴─────────────────────┘ │
│  MyBatis-Plus Mapper ── JDBC ──► MySQL 8.4（端口 3307）           │
└───────┬───────────────────────────────┬──────────────────────────┘
        │ REST（/ai/*, /mcp/*）          │ HTTP（OpenAI 兼容）
┌───────▼──────────────────┐    ┌───────▼──────────────────────────┐
│ Python RAG 服务(8000)     │    │ DeepSeek API（api.deepseek.com）  │
│ FastAPI + sklearn TF-IDF  │    │ deepseek-chat · function calling  │
│ 8,931 知识切片            │    └──────────────────────────────────┘
│ /ai/chat /ai/diagnose     │    ┌──────────────────────────────────┐
│ /mcp/tools /mcp/call      │    │ 腾讯元宝搜索（联网，可选）        │
└───────────────────────────┘    └──────────────────────────────────┘
```

---

## 二、Three.js 建筑数据来源分析

### 2.1 代码路径

| 环节 | 文件 | 函数/常量 | 行号 |
|---|---|---|---|
| 页面入口 | `frontend/src/views/Campus3D.vue` | — | — |
| 数据加载 | 同上 | `rebuild()` | 1105 |
| 建筑列表 API | 同上 | `energyApi.buildings({ siteId, limit: 300 })` | 1114 |
| 场景构建 | 同上 | `buildBuildings()` | 755 |
| 单体建模 | `frontend/src/three/buildingModel.ts` | `createBuildingModel()` | — |
| 环境（地面/道路/树） | `frontend/src/three/environment.ts` | `createEnvironment()` | — |
| 后端 SQL | `backend/.../EnergyQueryMapper.xml` | `selectBuildings` | 332 |

### 2.2 判定结论

| 问题 | 结论 | 证据 |
|---|---|---|
| 是否固定数组/Mock？ | **否** | 全文件无硬编码建筑数组；`buildBuildings` 遍历 `buildings.value`（API 返回） |
| 是否硬编码建筑数量/ID？ | **否** | 布局按 `Math.ceil(Math.sqrt(list.length))` 动态算行列 |
| 是否按数据库动态生成？ | **是** | `selectBuildings` 查 `dim_building`，返回 `buildingId/type/area/EUI/grade/cop/anomalyRate` |
| 导入新建筑能否出现在 3D？ | **能，但需手动刷新** | 导入时 `registerNewBuildings()` 写 `dim_building`；3D 只在该页 `rebuild()` 时重新拉取 |
| 已有建筑导入新能耗后评级会更新？ | **能（数据层）** | 导入后 `regrade()` 重算 `dim_building.energy_grade`；3D 需重新 `rebuild()` 才取到新值 |
| 支持多园区/新园区？ | **是** | `sites` 来自 `energyApi.sites()`；`<el-select @change="rebuild">` 切换即重建 |
| 是否存在缓存导致不更新？ | **是，已确认问题** | 见下 |

### 2.3 已确认的缓存问题（P0）

```
MainLayout.vue:55   <keep-alive :max="6">   ← 6 个页面被缓存
grep onActivated  →  Dashboard / Campus3D / EnergyQuery / Analytics / Anomaly / Assistant 全部为 0
grep dataVersion  →  仅 AiInsight.vue 一个组件 watch 了 app.dataVersion
```

**后果**：导入成功后 `app.bumpDataVersion()` 只让 AI 解读组件刷新；
看板 KPI、3D 建筑、能耗查询、统计页、异常页**全部停留在旧数据**，
直到用户手动点「刷新」或整页重载。

### 2.4 其它发现

- `limit: 300` 是硬上限。当前最大园区 30 栋，**暂不会截断**；但若导入某园区 >300 栋，3D 会少渲染。
- 无 `energy_grade` 的建筑渲染为中性灰（未评级）。导入不足 30 天的建筑即属此类（`MIN_GRADE_HOURS=720`）。
- 3D 是**网格示意布局**，非真实地理坐标（代码注释已声明，未冒充数字孪生）。

---

## 三、数据导入完整调用链

### 3.1 上传（步骤①）

```
POST /api/import/upload  (multipart)
└─ DataImportController.upload
   └─ DataImportService.upload(file)
      ├─ 按扩展名分流：.xlsx/.xls → EasyExcel；.csv/.txt → CSV 解析
      ├─ 解析表头与预览行（前 N 行）
      ├─ FieldMappingPlanner.plan(file)
      │   └─ LlmClient.chat(SYSTEM_PROMPT, …)   ← DeepSeek
      │      失败 → 规则引擎兜底（列名关键词匹配）
      └─ 返回 UploadResultVO{ fileId, columns[], 自动映射建议, mappingPlan{provider, elapsedMs} }
```
**实测**：8 列全部自动映射，`provider=deepseek`，置信度 0.96~0.99。

### 3.2 执行（步骤②~⑤）

```
POST /api/import/execute  (ImportRequest{fileId, mappings[], cleanRules, dryRun})
└─ DataImportService.execute(req)
   ├─ 逐行解析：时间（多格式回退）、数值（parseNumber 剥单位）
   ├─ 清洗：负值剔除 / 单位一致性（≥300 倍判混用）/ 缺失策略 / 建筑+时间去重
   ├─ dryRun=true  → 只返回统计与错误明细，不落库
   └─ dryRun=false →
      ├─ L552  if (!dryRun && !batch.isEmpty())
      ├─ L574  jdbcTemplate.batchUpdate("INSERT INTO fact_energy_hourly …")   ← 事实表
      ├─ L595  registerNewBuildings(batch, bldType, bldArea)
      │         ├─ dim_building 不存在 → INSERT（新建筑登记）
      │         ├─ 已存在 → UPDATE site_id / building_type_cn / area_m2（仅补空值）
      │         └─ dim_site 不存在 → INSERT（新园区登记）
      ├─ L629  anomalyDetectionService.recompute(buildings, minDate, maxDate)
      │         ├─ 52 周分层基线 + 残差 0.99 分位阈值 + P75 显著性下限
      │         ├─ 写回 fact_energy_hourly.anomaly_flag/type/severity/baseline/zscore
      │         ├─ 重建 fact_anomaly_event（连续异常小时合并）
      │         └─ 重建 agg_anomaly_daily（含 event_count）
      ├─ L635  aggregateRefreshService.refresh(buildings, minDate, maxDate)
      │         └─ agg_building_daily / agg_building_monthly / agg_site_hourly / agg_time_hourly
      ├─ L638  aggregateRefreshService.regrade(buildings)
      │         └─ dim_building.eui_kwh_m2_year / energy_grade（<720h 不评级）
      └─ 写 data_import_log（批次号、字段映射、清洗规则、错误明细、dry_run、operator）
```

### 3.3 前端收尾

```
DataImport.vue: after execute
├─ app.bumpDataVersion()        ← 仅 AiInsight 响应
└─ autoSwitchRange(result)      ← 若不重叠则 app.setRange(新区间)，并询问是否前往看板
```

### 3.4 判定结论

| 检查点 | 结论 | 证据 |
|---|---|---|
| 字段映射 | ✅ LLM + 规则兜底 | `FieldMappingPlanner:88` |
| 建筑 ID 解析 | ✅ | `ImportTargetField.BUILDING_ID` |
| **新建筑是否更新主数据表** | **✅ 会** | `registerNewBuildings` L767，INSERT/UPDATE `dim_building` |
| 新旧建筑识别 | ✅ | `SELECT COUNT(*) FROM dim_building WHERE building_id=?` |
| 园区归属 | ✅ 新园区也登记 | `dim_site` INSERT |
| 能耗记录写入 | ✅ 批量 INSERT | L574 |
| **导入批次事务** | **❌ 无 @Transactional** | `execute()` 无注解；每条 JdbcTemplate 各自自动提交，中途失败会留下半成品 |
| 统计刷新 | ✅ | `refresh()` |
| 异常刷新 | ✅（本次新增） | `recompute()` |
| 3D 刷新 | ⚠️ 数据已更新，页面不自动刷新 | 见 2.3 |

---

## 四、AI 真实能力核查

### 4.1 逐项验证

| 能力 | 结论 | 源码/执行证据 |
|---|---|---|
| 用哪个大模型 | **DeepSeek `deepseek-chat`** | `OpenAiCompatibleLlmClient`，`/ai/health` 返回 `provider=deepseek` |
| 是否支持 Tool Calling | **是**（含流式） | `chatWithTools` / `chatStream` 解析 `tool_calls`；`AiToolSchema` 12 个函数定义 |
| 是否注册并执行 MCP 工具 | **部分** | Python 侧有 `GET /mcp/tools`、`POST /mcp/call`（REST 模拟 MCP 语义）；Java 仅 `HttpAiServiceClient:105` 调 `/mcp/tools` **取清单**。**真正的工具执行在 Java 侧 `AiQueryToolService`，不经过 Python MCP** |
| 工具能否读真实 MySQL | **能** | `AiQueryToolService` 直接调 `energyQueryService.overviewStats` / `EnergyQueryMapper.selectTopBuildingsByEnergy` / `analyticsService.anomalyAnalysis` 等 11 处 |
| 是否存在硬编码/模板回答 | **存在，但仅作降级** | `MockAiServiceClient` 为模板引擎；DeepSeek 不可用时才走它（`HybridAiServiceClient` 有明确降级分支与日志） |
| 异常分析与归因 | **能** | `/analytics/diagnose` 证据链：温度/人员密度/COP/供回水温差对比；`/ai/diagnose` 由 Python 先调后端取证据再补 RAG 知识 |
| 节能建议是否有依据 | **有依据，但非大模型生成** | `AnalyticsService.suggestOptimization` 中 `llmClient` 引用数 = **0**，是规则引擎基于真实画像生成（每条含 依据/措施/原理） |
| 多轮上下文 | **有** | `DeepSeekToolCallingService.sessionHistory`，按 sessionId 保留最近 6 条 |
| 是否接通 RAGFlow | **未接入** | 全仓库无 RAGFlow 依赖；实际是自建 FastAPI + sklearn TF-IDF（`rag-service/src/app.py`），注释亦写明「尚未接入 RAGFlow」 |
| 是否接通元宝搜索 | **是（已实测）** | `YuanbaoSearchService` 调 `api.wsa.cloud.tencent.com/SearchPro`，返回真实结果（发改委/生态环境部链接） |
| 来源引用/文献追溯 | **部分** | RAG 返回 `{source, page, snippet}`，`source` 形如 `CHL-004（chiller_cooling）｜运维知识`；联网搜索返回可点击 `url`。**无原文页码级定位**（page 是命中序号，非文档页码） |

### 4.2 AI 能力矩阵

| 能力 | 状态 | 说明 |
|---|---|---|
| 自然语言问答（数据类） | ✅ 已实现 | DeepSeek + 12 工具 + 流式 |
| 自然语言问答（知识类） | ✅ 已实现 | 自建 RAG + LLM 归纳 |
| 联网搜索 | ✅ 已实现 | 元宝 SearchPro，可点击来源 |
| 工具自动选择 | ✅ 已实现 | function calling，`tool_choice=auto` |
| 参数校验 | ✅ 已实现 | `AiQueryToolService` 二次校验（ID 正则/日期跨度/limit） |
| 看板 AI 解读 | ✅ 已实现 | `/ai/insight` scope=dashboard/campus/diagnosis |
| 报表叙述 | ✅ 已实现 | `reportNarrative` + 数字/日期守卫 |
| 异常归因 | ✅ 已实现 | 规则证据链 + RAG 知识 |
| 节能建议 | ⚠️ 部分 | 规则引擎生成，**未用 LLM 生成话术** |
| 多轮对话 | ✅ 已实现 | 最近 6 条 |
| 主动预警/预测 | ❌ 未实现 | 无预测、无定时巡检 |
| 处理闭环 | ❌ 未实现 | 见第五节 |

---

## 五、业务闭环核查

| 环节 | 状态 | 证据 |
|---|---|---|
| 数据导入 | ✅ 已实现 | 五步向导 + 清洗 + 审计 |
| 建筑新增 | ✅ 已实现 | `registerNewBuildings` → `dim_building` / `dim_site` |
| 3D 渲染 | ⚠️ 部分实现 | 动态生成，但 keep-alive 不刷新（P0） |
| 能耗统计 | ✅ 已实现 | 聚合表 + 4.74% 口径统一 |
| 异常检测 | ✅ 已实现 | 导入后自动重算（本次补齐） |
| 小E 诊断 | ✅ 已实现 | 工具调用 + RAG + 归因证据链 |
| 运维建议 | ✅ 已实现 | 规则引擎，含依据 |
| **处理记录** | **❌ 未实现** | 全仓库**无任何写 `handled` / `handle_note` 的代码**；`fact_anomaly_event.handled` 实测全为 0，`handle_note` 全为 NULL |

> 结论：闭环断在最后一环。「建议 → 人去处理 → 记录回写」没有落地，
> 因此系统无法沉淀处置经验，也无法回答「这类问题上次怎么解决的」。

---

## 六、代码级问题证据

| # | 问题 | 证据（文件:行） |
|---|---|---|
| 1 | 导入无事务 | `DataImportService.java:279 execute()` 无 `@Transactional` |
| 2 | 导入后页面不刷新 | `MainLayout.vue:55 keep-alive`；各页 `onActivated=0`；仅 `AiInsight.vue:169` watch `dataVersion` |
| 3 | 处理记录不可写 | 全仓库 grep `handle_note` 无 UPDATE；表内 `handled` 恒 0 |
| 4 | 3D 建筑上限 | `Campus3D.vue:1114 limit: 300` |
| 5 | 节能建议未用 LLM | `AnalyticsService` 中 `llmClient` 引用数 = 0 |
| 6 | RAGFlow 未接入 | 全仓库无依赖；`MockAiServiceClient.java:367` 注释确认 |
| 7 | MCP 名义化 | Python 有 `/mcp/*`，Java 只取清单不执行 |
| 8 | 异常类型单一 | `SELECT COUNT(DISTINCT anomaly_type)` = 1（仅「能耗偏高」） |
| 9 | 未评级建筑 | `MIN_GRADE_HOURS=720`，不足 30 天不评级（设计如此，需 UI 说明） |
| 10 | 知识问答非流式 | 仅数据类走 SSE；RAG 仍整段返回 |

---

## 七、问题优先级

### P0（影响闭环正确性，必须修）
1. **处理记录缺失** —— 闭环断点，且导致「经验沉淀」永远做不了。
2. **导入无事务** —— 中途失败会留下 `fact` 有数据、`dim`/`agg` 未更新的半成品。
3. **keep-alive 导致导入后不刷新** —— 用户会误以为导入失败（本次已两次被误判）。

### P1（影响体验/可信度）
4. 3D `limit: 300` 上限（当前不触发，但应去掉或分页）。
5. 节能建议未用 LLM —— 与「AI 平台」定位不匹配（但规则版更可控，需权衡）。
6. 异常类型单一 —— 数据侧限制，答辩需主动说明。
7. 知识问答非流式。

### P2（优化项）
8. `page` 语义不是真实文档页码，文献追溯粒度粗。
9. 未评级建筑缺 UI 说明。
10. MCP 端点名义化 —— 若申报要求真实 MCP 协议，需要补 stdio/SSE 传输层。

---

## 八、最值得开发的三个 AI 创新功能

### ① 节能潜力测算（What-if）—— 最值得做
- **现状**：建议是定性的「建议清洗冷凝器」，没有量化收益。
- **做什么**：给定假设（夜间基线降 10% / COP 3.4→4.0 / 峰谷转移），算年省电量、碳减排、费用。
- **数据**：现成（真实用电 + COP + 峰谷电价 + 因子 0.5810）。
- **为什么算创新**：把「建议」变成「投资回报」，是运维负责人真正关心的语言。
- **成本**：2~3 天。

### ② 能耗预测与超额预警
- **现状**：只能看历史，不能回答「明天会不会超」。
- **做什么**：同建筑×同星期×同时刻历史中位数 + 气温修正，预测未来 24h，超 P90 预警。
- **数据**：5,991,840 逐小时 + 气温（实测）。
- **为什么算创新**：从「事后统计」变「事前预警」。
- **成本**：3~5 天。

### ③ 每日自动巡检简报（Agent 化）
- **现状**：全是被动查询。
- **做什么**：每日定时扫全部建筑，按风险排序，生成「今日关注这 3 栋」简报推送到看板。
- **数据**：异常 + 趋势 + 评级变化。
- **为什么算创新**：从「人找问题」变「系统找人」。
- **成本**：3~4 天。

---

## 九、建议修改的具体文件

| 优先级 | 文件 | 改什么 |
|---|---|---|
| P0 | 新增 `AnomalyHandleController` + `AnomalyHandleService` | 提供 `POST /api/analytics/anomalies/{id}/handle` 写 `handled`/`handle_note` |
| P0 | `DataImportService.java` | `execute()` 加 `@Transactional`（或拆分事务边界） |
| P0 | `Dashboard.vue` / `Campus3D.vue` / `EnergyQuery.vue` / `Analytics.vue` / `Anomaly.vue` | 加 `watch(() => app.dataVersion, …)` 或 `onActivated` |
| P1 | `Campus3D.vue` | 去掉 `limit: 300`，改分页或全量 |
| P1 | `AnalyticsService.java` | 若要 LLM 生成建议：注入 `LlmClient`，复用 `reportNarrative` 的守卫 |
| P2 | `rag-service/src/app.py` | citations 增加真实文档名/页码 |
| P2 | `AiController.java` | 知识问答也走 SSE |

---

## 十、分阶段开发与验收计划

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| **阶段 1（P0 闭环）** | 异常处理接口 + 导入事务 + 页面 dataVersion 刷新 | ① 任意异常可标记已处理并写备注，列表可见；② 导入中途失败回滚干净；③ 导入成功后切回看板/3D/查询页，数据自动是新的 |
| **阶段 2（P1 体验）** | 3D 去 300 上限；建议接 LLM；知识问答流式 | ① 任意规模园区建筑全渲染；② 建议含 LLM 叙述且数字经守卫；③ 知识问答逐字出现 |
| **阶段 3（创新）** | 节能测算 → 预测预警 → 每日简报 | ① 输入假设输出年省电/碳/费用；② 预测 24h 并预警；③ 每日自动生成简报 |
| **阶段 4（可信度）** | 文献追溯粒度、未评级说明、MCP 协议化 | 按需 |

---

## 十一、尚未验证的事项

1. **多用户并发导入**是否会互相干扰（未做并发压测）。
2. **大文件导入**（如 20 万行）的耗时与内存占用（未实测上限）。
3. RAG 检索的**准确率/召回率**（仅有构建时的分数分布，未做标注集评估）。
4. 3D 在**低端设备**上的帧率。
5. 申报材料是否**强制要求 RAGFlow**（若强制，自建 RAG 需替换或包装）。

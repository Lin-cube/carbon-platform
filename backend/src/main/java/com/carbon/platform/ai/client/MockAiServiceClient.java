package com.carbon.platform.ai.client;

import com.carbon.platform.ai.dto.ChatRequest;
import com.carbon.platform.ai.dto.ChatResponse;
import com.carbon.platform.ai.dto.DiagnoseRequest;
import com.carbon.platform.ai.dto.ReportRequest;
import com.carbon.platform.analytics.service.AnalyticsService;
import com.carbon.platform.energy.dto.EnergyQueryRequest;
import com.carbon.platform.energy.service.EnergyQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.*;

/**
 * Mock AI 客户端。
 *
 * <p>设计要点：<b>Mock 不是返回假数据，而是用真实的后端统计能力组装回答</b>。
 * 这样在角色 B 的 RAG/MCP 服务就绪前：
 * <ul>
 *   <li>前端「小E」助手可以完整联调（含引用、工具链、置信度、响应耗时）；</li>
 *   <li>后排的 20+ 标准问答测试集可以先行跑通流程；</li>
 *   <li>切换到真实服务后，前端与测试集无需任何改动。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockAiServiceClient implements AiServiceClient {

    private final AnalyticsService analyticsService;
    private final EnergyQueryService energyQueryService;

    @Override
    public String mode() {
        return "mock";
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        long t0 = System.currentTimeMillis();
        ChatResponse resp = new ChatResponse();
        String q = request.getQuestion() == null ? "" : request.getQuestion();

        List<ChatResponse.ToolCall> calls = new ArrayList<>();
        Map<String, Object> evidence = new LinkedHashMap<>();

        // 意图识别（关键词路由）——真实实现由大模型完成，这里用规则模拟同一接口形态
        String intent = classify(q);
        String answer;

        try {
            answer = switch (intent) {
                case "anomaly" -> {
                    LocalDate[] range = resolveRange(request);
                    var b = analyticsService.anomalyAnalysis(range[0], range[1], null, null);
                    calls.add(tool("scan_anomalies", Map.of("startDate", range[0].toString(),
                            "endDate", range[1].toString()), 0L));
                    evidence.put("evaluatedHours", b.getEvaluatedHours());
                    evidence.put("anomalyHours", b.getAnomalyHours());
                    evidence.put("anomalyRate", b.getAnomalyRate());
                    evidence.put("eventCount", b.getEventCount());
                    yield String.format(
                            "在 %s ~ %s 区间内，系统共评估 %s 小时数据，识别出 %s 小时能耗异常"
                                    + "（异常率 %s%%），聚合为 %s 个异常事件，平均持续 %s 小时。"
                                    + "异常主要集中在能耗偏高类型。建议优先核查异常小时数最多的建筑。"
                                    + "\n\n【数据来源】基于 BDG2 实测电耗 + 分层基线-偏差算法标注。"
                                    + "\n【注意】以上为算法初筛结果，具体处置需运维人员现场复核。",
                            range[0], range[1], num(b.getEvaluatedHours()), num(b.getAnomalyHours()),
                            b.getAnomalyRate(), num(b.getEventCount()), b.getAvgDurationHours());
                }
                case "cop" -> {
                    if (!StringUtils.hasText(request.getBuildingId())) {
                        yield "COP 分析需要指定具体建筑。请告诉我要查询哪一栋建筑（例如建筑编号），"
                                + "我再为您计算机组能效比、供回水温差与能效评级。";
                    }
                    LocalDate[] range = resolveRange(request);
                    var c = analyticsService.copAnalysis(range[0], range[1], request.getBuildingId());
                    calls.add(tool("query_energy", Map.of("buildingId", request.getBuildingId(),
                            "metric", "cop"), 0L));
                    evidence.put("copAvg", c.getCopAvg());
                    evidence.put("copGrade", c.getCopGrade());
                    evidence.put("deltaTAvg", c.getDeltaTAvg());
                    if (c.getCopAvg() == null) {
                        yield String.format("建筑 %s 在 %s ~ %s 区间内没有可用冷冻水计量数据，"
                                + "无法计算 COP。原因：BDG2 数据集中多数园区缺少计量口径可用的冷冻水表计。",
                                request.getBuildingId(), range[0], range[1]);
                    }
                    yield String.format(
                            "建筑 %s 在 %s ~ %s 区间：平均 COP 为 %s（评级：%s），"
                                    + "供回水温差平均 %s℃，制冷量合计 %s kWh。\n\n%s",
                            request.getBuildingId(), range[0], range[1], c.getCopAvg(), c.getCopGrade(),
                            c.getDeltaTAvg(), num(c.getCoolingKwh()), c.getGradeReason());
                }
                case "summary" -> {
                    LocalDate[] range = resolveRange(request);
                    var p = analyticsService.periodSummary("month", range[0], range[1],
                            null, request.getContext() == null ? null
                                    : str(request.getContext().get("siteId")), null);
                    calls.add(tool("overview_stats", Map.of("startDate", range[0].toString(),
                            "endDate", range[1].toString()), 0L));
                    evidence.put("total", p.getTotal());
                    evidence.put("peakLabel", p.getPeakLabel());
                    yield String.format(
                            "统计区间 %s ~ %s：总用电量 %s kWh，峰值出现在 %s，"
                                    + "谷值出现在 %s，峰谷差率 %s%%。\n\n分时占比方面，尖峰与高峰时段合计占比较高，"
                                    + "若将可转移负荷（如蓄冷、部分空调预冷）调整至低谷时段，具备节能降费空间。",
                            range[0], range[1], num(p.getTotal()), p.getPeakLabel(),
                            p.getValleyLabel(), p.getPeakValleyRatio());
                }

                case "dimension" -> {
                    // 维度类问题：区分"问园区"、"问建筑类型"、"问整体数据情况"
                    // 注意判断顺序：问"某园区有哪些建筑类型"时问的是【类型】而非【园区列表】，
                    // 所以必须先判断"类型"，否则会被"园区"二字提前截获（实测踩过）。
                    String dim = q.contains("类型") || q.contains("业态") ? "building_type"
                            : q.contains("园区") || q.contains("站点") ? "site"
                            : "all";
                    var dv = energyQueryService.queryDimensions(dim);
                    calls.add(tool("query_dimensions", Map.of("dimension", dim), 0L));
                    StringBuilder sb = new StringBuilder();
                    if ("site".equals(dim)) {
                        sb.append(String.format("系统当前纳入 %d 个园区：\n", dv.getTotal()));
                        dv.getOptions().stream().limit(20).forEach(o ->
                                sb.append(String.format("· %s：%s 栋建筑\n", o.getLabel(),
                                        o.getCount())));
                    } else if ("building_type".equals(dim)) {
                        sb.append(String.format("系统当前覆盖 %d 类建筑类型：\n", dv.getTotal()));
                        dv.getOptions().stream().limit(20).forEach(o ->
                                sb.append(String.format("· %s：%s 栋\n", o.getLabel(),
                                        o.getCount())));
                    } else {
                        sb.append("系统数据维度概览：\n");
                        dv.getOptions().forEach(o ->
                                sb.append(String.format("· %s（%s）\n", o.getLabel(), o.getValue())));
                        sb.append("\n数据集基于 BDG2 开源数据集构建，时间范围 2016-01-01 ~ 2017-07-01，"
                                + "包含 5,991,840 条逐小时记录、456 栋建筑、18 个园区。");
                    }
                    yield sb.toString();
                }

                case "breakdown" -> {
                    LocalDate[] range = resolveRange(request);
                    var bd = energyQueryService.breakdownStats(
                            buildReq(range), "building_type");
                    calls.add(tool("breakdown_stats", Map.of("groupBy", "building_type"), 0L));
                    evidence.put("total", bd.getTotal());
                    StringBuilder sb = new StringBuilder(String.format(
                            "按建筑类型分解用电量（%s ~ %s，合计 %s kWh）：\n",
                            range[0], range[1], num(bd.getTotal())));
                    bd.getItems().stream().filter(i -> i.getValue() != null
                                    && i.getValue().doubleValue() > 0)
                            .limit(8).forEach(i -> sb.append(String.format("· %s：%s kWh，占 %s%%（%s 栋）\n",
                                    i.getLabel(), num(i.getValue()), i.getPercentage(), i.getCount())));
                    yield sb.toString();
                }

                case "tariff" -> {
                    LocalDate[] range = resolveRange(request);
                    var p = analyticsService.periodSummary("month", range[0], range[1],
                            null, null, null);
                    calls.add(tool("period-summary", Map.of("periodType", "month"), 0L));
                    StringBuilder sb = new StringBuilder(String.format(
                            "分时电价时段用电占比（%s ~ %s）：\n", range[0], range[1]));
                    for (var t : p.getTariffBuckets()) {
                        sb.append(String.format("· %s（%s）：%s kWh，占 %s%%，平均每小时 %s kWh\n",
                                t.getName(), t.getHours(), num(t.getElectricityKwh()),
                                t.getPercentage(), t.getAvgHourlyKwh()));
                    }
                    sb.append(String.format("%n区间总用电量 %s kWh，峰谷差率 %s%%。"
                                    + "尖峰与高峰合计占比越高，通过负荷转移降费的潜力越大。",
                            num(p.getTotal()), p.getPeakValleyRatio()));
                    yield sb.toString();
                }

                case "carbon" -> {
                    LocalDate[] range = resolveRange(request);
                    var ov = energyQueryService.overviewStats(buildReq(range));
                    calls.add(tool("overview_stats", Map.of("startDate", range[0].toString(),
                            "endDate", range[1].toString()), 0L));
                    evidence.put("carbonKg", ov.getCarbonKg());
                    yield String.format(
                            "统计区间 %s ~ %s：碳排放总量为 %s kgCO₂（约 %s 吨）。%n"
                                    + "核算口径：外购电（含空调电耗）+ 燃气，乘以电网平均排放因子 "
                                    + "0.5810 kgCO₂/kWh（生态环境部 2023 年公布值）。%n"
                                    + "同期用电量 %s kWh、空调系统能耗 %s kWh。",
                            range[0], range[1], num(ov.getCarbonKg()),
                            num(ov.getCarbonKg() == null ? null : ov.getCarbonKg().divide(java.math.BigDecimal.valueOf(1000), 1, java.math.RoundingMode.HALF_UP)),
                            num(ov.getElectricityKwh()), num(ov.getHvacKwh()));
                }

                case "rank" -> {
                    LocalDate[] range = resolveRange(request);
                    StringBuilder sb = new StringBuilder();
                    if (q.contains("时段") || q.contains("几点")) {
                        // 问"哪个时段用电最高"→ 实际数据来自时段汇总（峰谷定位），
                        // 因此登记 period-summary 为调用的工具，而非异常扫描（保持可解释性准确）
                        var p = analyticsService.periodSummary("month", range[0], range[1],
                                null, null, null);
                        calls.add(tool("period-summary", Map.of("periodType", "month",
                                "startDate", range[0].toString(),
                                "endDate", range[1].toString()), 0L));
                        sb.append(String.format("用电量最高的时段为 %s（%s kWh），最低为 %s（%s kWh），"
                                        + "峰谷差率 %s%%。",
                                p.getPeakLabel(), num(p.getPeak()), p.getValleyLabel(),
                                num(p.getValley()), p.getPeakValleyRatio()));
                    } else {
                        var an = analyticsService.anomalyAnalysis(range[0], range[1], null, null);
                        calls.add(tool("scan_anomalies", Map.of("startDate", range[0].toString(),
                                "endDate", range[1].toString()), 0L));
                        sb.append(String.format("异常小时数排名（%s ~ %s）：\n", range[0], range[1]));
                        an.getTopBuildings().stream().limit(8).forEach(b ->
                                sb.append(String.format("· %s（%s）：%s 小时，异常率 %s%%\n",
                                        b.getBuildingId(), b.getBuildingTypeCn(),
                                        num(b.getAnomalyHours()),
                                        b.getAnomalyRate() == null ? "-"
                                                : b.getAnomalyRate().multiply(java.math.BigDecimal.valueOf(100))))); 
                    }
                    yield sb.toString();
                }

                case "anomalyRank" -> {
                    LocalDate[] range = resolveRange(request);
                    var an = analyticsService.anomalyAnalysis(range[0], range[1], null, null);
                    calls.add(tool("scan_anomalies", Map.of("startDate", range[0].toString(),
                            "endDate", range[1].toString()), 0L));
                    evidence.put("eventCount", an.getEventCount());
                    StringBuilder sb = new StringBuilder(String.format(
                            "异常小时数最多的建筑（%s ~ %s，共 %s 个异常事件）：\n",
                            range[0], range[1], num(an.getEventCount())));
                    an.getTopBuildings().stream().limit(8).forEach(b ->
                            sb.append(String.format("· %s（%s）：%s 小时，异常率 %s%%，峰值偏离 %s%%\n",
                                    b.getBuildingId(), b.getBuildingTypeCn(),
                                    num(b.getAnomalyHours()),
                                    b.getAnomalyRate() == null ? "-" : b.getAnomalyRate(),
                                    b.getPeakDeviationPct())));
                    yield sb.toString();
                }

                case "diagnose" -> {
                    LocalDate[] range = resolveRange(request);
                    String bid = extractBuildingId(q, request.getBuildingId());
                    if (bid == null) {
                        yield "请问您想诊断哪一栋建筑？请提供建筑编号（例如 Hog_office_Sydney），"
                                + "我会给出异常时段与正常时段的对比证据，并给出可能原因与处置建议。";
                    }
                    var dg = analyticsService.diagnose(range[0], range[1], List.of(bid));
                    calls.add(tool("diagnose_issue", Map.of("buildingId", bid), 0L));
                    @SuppressWarnings("unchecked")
                    var attrs = (List<Map<String, Object>>) dg.get("attributions");
                    StringBuilder sb = new StringBuilder(String.format(
                            "建筑 %s 在 %s ~ %s 的诊断结果：\n", bid, range[0], range[1]));
                    if (attrs == null || attrs.isEmpty()) {
                        sb.append("该区间内未发现显著异常，或异常无法由温度/人员/能效等因素解释。\n");
                    } else {
                        sb.append("\n【归因分析】\n");
                        for (Map<String, Object> a : attrs) {
                            sb.append(String.format("◆ %s（置信度 %s）\n  证据：%s\n  建议：%s\n",
                                    a.get("cause"), a.get("confidence"),
                                    a.get("evidence"), a.get("suggestion")));
                        }
                    }
                    @SuppressWarnings("unchecked")
                    var cmp = (List<Map<String, Object>>) dg.get("anomalyVsNormal");
                    if (cmp != null && cmp.size() >= 2) {
                        sb.append("\n【异常时段 vs 正常时段对比】\n");
                        for (Map<String, Object> g : cmp) {
                            sb.append(String.format("· %s：%s 小时，均电耗 %s kWh，室外温度 %s℃，"
                                            + "人员密度 %s，平均 COP %s，供回水温差 %s℃\n",
                                    g.get("group"), g.get("hours"), g.get("avgKwh"),
                                    g.get("avgTemp"), g.get("avgOccupancy"),
                                    g.get("avgCop"), g.get("avgDeltaT")));
                        }
                    }
                    sb.append("\n【注意】以上为算法依据实测数据生成的初筛结论，"
                            + "实施前须经运维人员结合现场情况复核。");
                    yield sb.toString();
                }

                case "suggest" -> {
                    LocalDate[] range = resolveRange(request);
                    String bid = extractBuildingId(q, request.getBuildingId());
                    if (bid == null) {
                        yield "请问您想为哪一栋建筑生成优化建议？请提供建筑编号"
                                + "（例如 Hog_office_Sydney），我会基于该建筑的 COP、"
                                + "供回水温差、夜间基载等指标给出针对性建议。";
                    }
                    var sg = analyticsService.suggestOptimization(range[0], range[1], bid);
                    calls.add(tool("suggest_optimization", Map.of("buildingId", bid), 0L));
                    @SuppressWarnings("unchecked")
                    var list = (List<Map<String, Object>>) sg.get("suggestions");
                    StringBuilder sb = new StringBuilder(String.format(
                            "建筑 %s 的运维优化建议：\n", bid));
                    if (list != null) {
                        for (Map<String, Object> x : list) {
                            sb.append(String.format("%n◆【%s优先级】%s%n  依据：%s%n  措施：%s%n",
                                    x.get("priority"), x.get("title"),
                                    x.get("evidence"), x.get("actions")));
                        }
                    }
                    sb.append("\n").append(sg.get("disclaimer"));
                    yield sb.toString();
                }

                case "qualityConcept" -> {
                    calls.add(tool("scan_anomalies", Map.of("note", "概念说明"), 0L));
                    yield """
                            这是两个必须区分的概念，本系统在数据模型层面就做了分离：

                            【能耗异常】anomaly_flag = 1
                            · 含义：真实用能行为偏离同期历史基线（主要是显著偏高）
                            · 判据：分层基线（同星期几同小时历史中位数）+ 残差分位数阈值 + 显著性下限
                            · 用途：用于「异常识别召回率 / 误报率」指标评估
                            · 示例：某教学楼周三 14 点用电量是同期基线的 3 倍

                            【数据质量缺陷】data_quality_flag = 1
                            · 含义：采集侧问题，不是用能问题
                            · 类型：① 表计卡死——连续 24 小时读数完全不变；② 数据缺失——连续 24 小时无上报
                            · 用途：提示运维人员检查表计或通信链路

                            【为什么要分开】
                            若把表计卡死这类采集故障计入"能耗异常"，会凭空产生数万条"误报"，
                            使误报率指标失真（实测分开统计前误报率虚高至 16.9%，分开后为 3.29%）。

                            【当前数据情况】
                            · A 级建筑（431 栋，独立计量）：参与异常评估
                            · B 级建筑（25 栋，站点分摊估算）：anomaly_flag = -1，标记为「未评估」，
                              因其电耗是估算值，做异常检测没有意义，也会污染指标评估

                            您可以在「异常诊断」页面按异常类型筛选查看，表计卡死/数据缺失会单独列出。
                            """;
                }

                case "report" -> {
                    LocalDate[] range = resolveRange(request);
                    var p = analyticsService.periodSummary("month", range[0], range[1],
                            null, null, null);
                    calls.add(tool("period-summary", Map.of("periodType", "month"), 0L));
                    evidence.put("total", p.getTotal());
                    yield String.format(
                            "已为您准备报表数据（%s ~ %s）：总用电量 %s kWh，"
                                    + "峰值时段 %s，峰谷差率 %s%%。%n%n"
                                    + "请在「统计分析」页面点击「导出报表」按钮，"
                                    + "系统将生成包含 5 个工作表的 Excel 文件："
                                    + "概览 / 时段汇总 / 分时占比 / 异常分析 / 异常建筑排名。%n"
                                    + "也可直接调用接口：GET /api/analytics/report/export",
                            range[0], range[1], num(p.getTotal()), p.getPeakLabel(),
                            p.getPeakValleyRatio());
                }

                default -> {
                    calls.add(tool("query_dimensions", Map.of("dimension", "all"), 0L));
                    StringBuilder sb = new StringBuilder(
                            "您好，我是「小E」建筑能源智能运维助手。我可以帮您：\n"
                            + "① 查询建筑/园区的能耗数据（用电、用水、空调能耗、碳排放）\n"
                            + "② 分析机组 COP 能效与供回水温差\n"
                            + "③ 扫描并诊断能耗异常（含归因证据）\n"
                            + "④ 生成能耗统计报表\n"
                            + "⑤ 给出针对性运维优化建议\n\n");
                    // 针对运维知识类提问，明确说明知识库状态，避免"编造规范"
                    if (containsAny(q.replace(" ", ""),
                            "什么是", "是什么", "危害", "标准步骤", "规范", "要求", "一般是多少")) {
                        sb.append("您的问题涉及运维专业规范。当前系统运行在**本地联调模式**，"
                                + "尚未接入 RAGFlow 建筑能源知识库，因此无法引用运维手册与行业规范作答。\n\n"
                                + "接入知识库后（角色 B 的 RAG 服务上线），此类问题将基于：\n"
                                + "· 建筑能源管理业务流程说明\n· 设备运维手册\n"
                                + "· 能耗数据字典\n· 行业规范文件\n"
                                + "给出可溯源的专业回答，并标注引用出处。\n\n"
                                + "目前我可以为您提供**基于实测数据**的分析，例如：\n"
                                + "· 「某栋楼为什么能耗偏高」（归因证据链）\n"
                                + "· 「该建筑的机组能效如何」（COP 分析）\n"
                                + "· 「有哪些能耗优化空间」（优化建议）");
                    } else {
                        sb.append("请告诉我具体想了解哪方面的信息，例如"
                                + "「Eagle 园区 2016 年 7 月的用电情况」或「某建筑为什么能耗偏高」。");
                    }
                    yield sb.toString();
                }
            };
        } catch (Exception e) {
            log.warn("Mock AI 处理失败", e);
            resp.setOk(false);
            resp.setError(e.getMessage());
            answer = "处理您的问题时出现异常：" + e.getMessage();
        }

        resp.setAnswer(answer);
        resp.setToolCalls(calls);
        resp.setDataEvidence(evidence);
        resp.setConfidence(evidence.isEmpty() ? 0.5 : 0.85);
        resp.setElapsedMs(System.currentTimeMillis() - t0);
        resp.setDataSource(evidence.isEmpty() ? "无" : "混合（实测+标注）");
        resp.setCitations(mockCitations(intent));
        return resp;
    }

    @Override
    public Map<String, Object> diagnose(DiagnoseRequest request) {
        LocalDate[] range = resolveRange(request.getStartDate(), request.getEndDate());
        List<String> ids = StringUtils.hasText(request.getBuildingId())
                ? List.of(request.getBuildingId()) : null;
        Map<String, Object> result = new LinkedHashMap<>(analyticsService.diagnose(range[0], range[1], ids));
        result.put("aiNarrative", "以上归因由分层基线-偏差算法基于实测数据自动生成，"
                + "每条结论均附证据与置信度，须经运维人员复核后执行。");
        result.put("mode", "mock");
        return result;
    }

    @Override
    public Map<String, Object> generateReport(ReportRequest request) {
        LocalDate[] range = resolveRange(request.getStartDate(), request.getEndDate());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportType", request.getReportType());
        out.put("timeRange", range[0] + " ~ " + range[1]);
        out.put("overview", analyticsService.periodSummary("month", range[0], range[1],
                request.getBuildingIds(), request.getSiteId(), null));
        out.put("anomaly", analyticsService.anomalyAnalysis(range[0], range[1], request.getSiteId(),
                request.getBuildingIds()));
        if (Boolean.TRUE.equals(request.getWithNarrative())) {
            out.put("narrative", "本期报表显示园区用电整体平稳，"
                    + "异常以能耗偏高为主且已聚合为可追溯事件。"
                    + "建议对异常小时数排名靠前的建筑安排现场核查。");
        }
        out.put("mode", "mock");
        return out;
    }

    @Override
    public Map<String, Object> listTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(toolDef("query_dimensions", "维度查询", "获取可选建筑、园区、类型等维度值",
                List.of("dimension")));
        tools.add(toolDef("overview_stats", "概览统计", "总量、碳排、异常率、平均COP",
                List.of("startDate", "endDate", "siteId")));
        tools.add(toolDef("breakdown_stats", "状态分解", "按园区/建筑类型/季节/时段分解能耗",
                List.of("groupBy", "metric", "startDate", "endDate")));
        tools.add(toolDef("query_energy", "能耗查询", "按建筑+时间+参数精准查询时序数据",
                List.of("buildingId", "startDate", "endDate", "granularity", "metric")));
        tools.add(toolDef("scan_anomalies", "异常扫描", "分页列出异常事件并支持筛选",
                List.of("startDate", "endDate", "anomalyType", "severity", "minDuration")));
        tools.add(toolDef("diagnose_issue", "问题诊断", "异常归因：温度/人员/COP/温差对比证据链",
                List.of("buildingId", "startDate", "endDate")));
        tools.add(toolDef("suggest_optimization", "优化建议", "基于能效画像给出运维优化建议",
                List.of("buildingId", "startDate", "endDate")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "mock");
        out.put("count", tools.size());
        out.put("tools", tools);
        out.put("note", "以上 7 类工具与 P04 申报表要求一致；"
                + "真实实现由角色 B 的 Python MCP Server 提供，"
                + "后端 REST 形态见 /api/energy 与 /api/analytics。");
        return out;
    }

    @Override
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("mode", "mock");
        m.put("message", "当前为本地联调模式（carbon.ai.mode=mock）。"
                + "接入角色 B 的 RAGFlow/FastAPI 服务后，把 carbon.ai.mode 改为 http 即可。");
        return m;
    }

    // ================================================================
    // 内部
    // ================================================================

    private String classify(String q) {
        // 归一化：去掉空格便于关键词匹配
        String s = q.replace(" ", "");

        // 优先级从"最具体"到"最泛化"，避免被泛化关键词提前截获
        if (containsAny(s, "为什么", "原因", "怎么回事", "为何", "咋回事", "排查",
                "怎么处理", "如何处置", "差别", "差异", "对比", "相比", "比较")) {
            return "diagnose";
        }
        if (containsAny(s, "优化建议", "节能建议", "怎么优化", "如何优化", "节能改造", "有什么建议")) {
            return "suggest";
        }
        if (containsAny(s, "报表", "导出", "生成报告", "统计表")) {
            return "report";
        }
        if (containsAny(s, "COP", "cop", "能效", "温差", "机组")) {
            return "cop";
        }
        // 概念辨析类问题（能耗异常 vs 数据质量缺陷）优先于具体查询
        if (containsAny(s, "数据质量", "表计卡死", "数据缺失", "未评估")
                && containsAny(s, "算不算", "是不是", "算", "区别", "差异")) {
            return "qualityConcept";
        }
        if (containsAny(s, "异常", "告警", "报警", "偏高", "偏低", "误报")) {
            // "哪些建筑异常最多"属于排行；"哪个时段"属于时段汇总，不应在此截获
            if (containsAny(s, "时段", "几点", "什么时候")) {
                return "rank";
            }
            if (containsAny(s, "最多", "最高", "排名", "top", "前几", "哪几栋")) {
                return "anomalyRank";
            }
            return "anomaly";
        }
        if (containsAny(s, "尖峰", "高峰", "平段", "低谷", "峰谷", "分时")) {
            return "tariff";
        }
        if (containsAny(s, "占比", "结构", "分解", "分项", "构成")) {
            return "breakdown";
        }
        if (containsAny(s, "碳排放", "碳排", "co2", "CO₂")) {
            return "carbon";
        }
        if (containsAny(s, "最高", "最低", "峰值", "排名", "top", "哪几栋", "前几")) {
            return "rank";
        }
        if (containsAny(s, "有哪些园区", "哪些园区", "哪些建筑类型", "有哪些建筑类型",
                "有哪些建筑", "维度", "覆盖", "时间范围", "数据量", "多少条",
                "有什么数据", "数据集")) {
            return "dimension";
        }
        if (containsAny(s, "统计", "汇总", "总用电", "用电量", "用电情况", "趋势")) {
            return "summary";
        }
        return "general";
    }

    private boolean containsAny(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) {
                return true;
            }
        }
        return false;
    }


    /** 由统计区间构造能耗查询条件（供 breakdown / overview 复用） */
    private EnergyQueryRequest buildReq(java.time.LocalDate[] range) {
        EnergyQueryRequest r = new EnergyQueryRequest();
        r.setStartDate(range[0]);
        r.setEndDate(range[1]);
        return r;
    }

    /** 从问题文本或显式入参中提取建筑编号 */
    private String extractBuildingId(String question, String explicit) {
        if (StringUtils.hasText(explicit)) {
            return explicit.trim();
        }
        var m = BUILDING_ID.matcher(question == null ? "" : question);
        return m.find() ? m.group() : null;
    }

    /** 从问题文本中提取建筑编号（形如 Site_type_Name，含两个下划线） */
    private static final java.util.regex.Pattern BUILDING_ID =
            java.util.regex.Pattern.compile("[A-Za-z]+_[A-Za-z]+_[A-Za-z]+");

    private List<ChatResponse.Citation> mockCitations(String intent) {
        if ("general".equals(intent)) {
            return List.of();
        }
        List<ChatResponse.Citation> cs = new ArrayList<>();
        cs.add(citation("建筑能源管理业务规范（知识库待接入）", 1,
                "能效应结合室外气象参数与人员在室率综合判断，避免单一指标误判。"));
        if ("anomaly".equals(intent)) {
            cs.add(citation("能耗异常诊断作业指导书（知识库待接入）", 3,
                    "能耗异常优先排查：设备运行策略变更、设定值偏移、表计故障、临时用电接入。"));
        }
        return cs;
    }

    private ChatResponse.Citation citation(String source, int page, String snippet) {
        ChatResponse.Citation c = new ChatResponse.Citation();
        c.setSource(source);
        c.setPage(page);
        c.setSnippet(snippet);
        return c;
    }

    private ChatResponse.ToolCall tool(String name, Map<String, Object> args, Long ms) {
        ChatResponse.ToolCall t = new ChatResponse.ToolCall();
        t.setTool(name);
        t.setArgs(args);
        t.setElapsedMs(ms);
        t.setSuccess(true);
        return t;
    }

    private Map<String, Object> toolDef(String name, String title, String desc, List<String> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("title", title);
        m.put("description", desc);
        m.put("parameters", params);
        m.put("restEndpoint", restEndpoint(name));
        return m;
    }

    private String restEndpoint(String tool) {
        return switch (tool) {
            case "query_dimensions" -> "GET /api/energy/dimensions";
            case "overview_stats" -> "POST /api/energy/overview";
            case "breakdown_stats" -> "POST /api/energy/breakdown";
            case "query_energy" -> "POST /api/energy/query";
            case "scan_anomalies" -> "GET /api/analytics/anomalies/scan";
            case "diagnose_issue" -> "GET /api/analytics/diagnose";
            case "suggest_optimization" -> "GET /api/analytics/suggest";
            default -> "-";
        };
    }

    private LocalDate[] resolveRange(ChatRequest req) {
        return resolveRange(req.getStartDate(), req.getEndDate());
    }

    private LocalDate[] resolveRange(String start, String end) {
        LocalDate e = StringUtils.hasText(end) ? LocalDate.parse(end) : LocalDate.of(2016, 12, 31);
        LocalDate s = StringUtils.hasText(start) ? LocalDate.parse(start) : e.minusMonths(6);
        return new LocalDate[]{s, e};
    }

    private String num(Object v) {
        if (v == null) {
            return "0";
        }
        if (v instanceof Number n) {
            return String.format("%,.0f", n.doubleValue());
        }
        return v.toString();
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}

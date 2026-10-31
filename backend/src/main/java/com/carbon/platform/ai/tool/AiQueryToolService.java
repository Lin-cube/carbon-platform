package com.carbon.platform.ai.tool;

import com.carbon.platform.analytics.dto.AnomalyAnalysisVO;
import com.carbon.platform.analytics.mapper.AnalyticsMapper;
import com.carbon.platform.analytics.service.AnalyticsService;
import com.carbon.platform.common.exception.BizException;
import com.carbon.platform.common.result.ResultCode;
import com.carbon.platform.energy.dto.EnergyQueryRequest;
import com.carbon.platform.energy.dto.OverviewVO;
import com.carbon.platform.energy.mapper.EnergyQueryMapper;
import com.carbon.platform.energy.service.EnergyQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * AI 可调用的内部数据查询工具（低 Token 消耗）。
 *
 * <h3>设计目标</h3>
 * 为 DeepSeek Tool Calling 提供<b>受控的结构化查询能力</b>，同时<b>绝不把
 * 599 万条逐小时记录送进模型上下文</b>：
 * <ul>
 *   <li>普通汇总查询只返回统计指标（数字 + 单位 + 业务含义）；</li>
 *   <li>排名查询默认返回前 10 条，上限 50 条；</li>
 *   <li>月度趋势优先返回 12 个月；</li>
 *   <li>小时级明细不出现在工具返回里（前端走 /api/energy/query 自行展示）。</li>
 * </ul>
 *
 * <h3>复用原则</h3>
 * 全部复用 {@link AnalyticsService} 与 {@link EnergyQueryService}，
 * 它们内部已走预聚合表（agg_building_daily / agg_building_monthly / …），
 * 本类只做「取数 → 抽摘要 → 校验 → 组返回」，不重复实现统计口径。
 *
 * <h3>数据安全</h3>
 * <ul>
 *   <li>无任何拼接 SQL：所有查询走参数化 Mapper；</li>
 *   <li>建筑/园区编号、日期范围、结果条数全部白名单校验；</li>
 *   <li>本服务不暴露 JDBC/任意 SQL 能力。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiQueryToolService {

    private static final Pattern SITE_ID = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");
    private static final Pattern BUILDING_ID =
            Pattern.compile("^[A-Za-z0-9]+_[A-Za-z0-9]+_[A-Za-z0-9]+$");
    /**
     * 日期跨度上限（天）。与 EnergyQueryServiceImpl 的
     * carbon.query.max-time-range-days（默认 366）保持一致，
     * 超过则拒绝，避免大范围扫描影响业务系统。
     */
    private static final long MAX_RANGE_DAYS = 366;
    /** 排行默认条数 / 上限 */
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;
    /** 电网平均排放因子 kgCO₂/kWh（生态环境部 2023） */
    private static final BigDecimal CARBON_FACTOR = new BigDecimal("0.5810");

    private final AnalyticsService analyticsService;
    /** 历史处置案例（只读） */
    private final com.carbon.platform.analytics.service.AnomalyHandleService anomalyHandleService;
    /** 节能情景模拟（只读分析，不写真实统计） */
    private final com.carbon.platform.analytics.saving.service.SavingSimulationService savingSimulationService;
    private final EnergyQueryService energyQueryService;
    private final EnergyQueryMapper energyQueryMapper;
    private final AnalyticsMapper analyticsMapper;

    // ================================================================ 分发

    /** 按工具名分发执行（供 /api/ai/tools/call 调用） */
    public Map<String, Object> call(String tool, Map<String, Object> args) {
        if (!StringUtils.hasText(tool)) {
            throw new BizException(ResultCode.BAD_REQUEST, "缺少 tool 参数");
        }
        Map<String, Object> a = args == null ? Map.of() : args;
        return switch (tool) {
            case "getParkEnergySummary" ->
                    parkEnergySummary(str(a, "siteId"), str(a, "startDate"), str(a, "endDate"));
            case "getBuildingEnergySummary" ->
                    buildingEnergySummary(str(a, "buildingId"), str(a, "startDate"), str(a, "endDate"));
            case "getBuildingEnergyTrend" ->
                    buildingEnergyTrend(str(a, "buildingId"), str(a, "startDate"), str(a, "endDate"));
            case "getTopEnergyBuildings" ->
                    topEnergyBuildings(str(a, "siteId"), str(a, "startDate"), str(a, "endDate"), intArg(a, "limit", DEFAULT_LIMIT));
            case "getBuildingCOP" ->
                    buildingCop(str(a, "buildingId"), str(a, "startDate"), str(a, "endDate"));
            case "getBuildingAnomalies" ->
                    buildingAnomalies(str(a, "buildingId"), str(a, "startDate"), str(a, "endDate"), intArg(a, "limit", DEFAULT_LIMIT));
            case "simulateEnergySaving" -> simulateEnergySaving(a);
            case "getAnomalyHandleHistory" ->
                    anomalyHandleHistory(str(a, "buildingId"), str(a, "anomalyType"),
                            intArg(a, "limit", 5));
            case "diagnoseAnomaly" ->
                    diagnoseAnomaly(str(a, "buildingId"), str(a, "startDate"), str(a, "endDate"));
            case "getOptimizationAdvice" ->
                    optimizationAdvice(str(a, "buildingId"), str(a, "startDate"), str(a, "endDate"));
            case "getCarbonEmissionSummary" ->
                    carbonEmissionSummary(str(a, "siteId"), str(a, "startDate"), str(a, "endDate"));
            case "getTopAnomalyBuildings" ->
                    topAnomalyBuildings(str(a, "siteId"), str(a, "startDate"), str(a, "endDate"), intArg(a, "limit", DEFAULT_LIMIT));
            case "getAnomalyOverview" ->
                    anomalyOverview(str(a, "siteId"), str(a, "startDate"), str(a, "endDate"));
            case "listSites" -> listSites();
            case "getTopSites" ->
                    topSites(str(a, "startDate"), str(a, "endDate"), intArg(a, "limit", DEFAULT_LIMIT));
            default -> throw new BizException(ResultCode.BAD_REQUEST, "未知工具: " + tool);
        };
    }

    // ================================================================ 工具实现

    /** ① 园区能耗汇总：总量 / 空调 / 碳排 / COP / 异常 / 峰谷 */
    public Map<String, Object> parkEnergySummary(String siteId, String startDate, String endDate) {
        // siteId 为空表示「全部园区」
        if (StringUtils.hasText(siteId)) {
            validateSiteId(siteId);
        }
        LocalDate[] r = parseRange(startDate, endDate);
        EnergyQueryRequest req = req(r[0], r[1], StringUtils.hasText(siteId) ? siteId : null, null);
        OverviewVO ov = energyQueryService.overviewStats(req);
        var peak = analyticsService.periodSummary("month", r[0], r[1], null, siteId, null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("siteId", StringUtils.hasText(siteId) ? siteId : "全部园区");
        out.put("buildingCount", ov.getBuildingCount());
        out.put("electricityKwh", ov.getElectricityKwh());
        out.put("hvacKwh", ov.getHvacKwh());
        out.put("waterM3", ov.getWaterM3());
        out.put("carbonKg", ov.getCarbonKg());
        out.put("copAvg", ov.getCopAvg());
        out.put("anomalyHours", ov.getAnomalyHours());
        out.put("anomalyRatePct", toPct(ov.getAnomalyRate()));
        out.put("peakKwh", peak.getPeak());
        out.put("peakLabel", peak.getPeakLabel());
        out.put("valleyKwh", peak.getValley());
        out.put("valleyLabel", peak.getValleyLabel());
        out.put("peakValleyRatioPct", peak.getPeakValleyRatio());
        out.put("unit", "electricity/hvac/carbon 单位分别为 kWh / kWh / kgCO₂");
        return out;
    }

    /** ② 建筑能耗汇总 */
    public Map<String, Object> buildingEnergySummary(String buildingId, String startDate, String endDate) {
        validateBuildingId(buildingId);
        LocalDate[] r = parseRange(startDate, endDate);
        EnergyQueryRequest req = req(r[0], r[1], null, buildingId);
        OverviewVO ov = energyQueryService.overviewStats(req);
        Map<String, Object> det = energyQueryService.buildingDetail(buildingId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buildingId", buildingId);
        out.put("buildingTypeCn", det.get("building_type_cn"));
        out.put("siteId", det.get("site_id"));
        out.put("areaM2", det.get("area_m2"));
        out.put("energyGrade", det.get("energy_grade"));
        out.put("electricityKwh", ov.getElectricityKwh());
        out.put("hvacKwh", ov.getHvacKwh());
        out.put("carbonKg", ov.getCarbonKg());
        out.put("copAvg", ov.getCopAvg());
        out.put("anomalyHours", ov.getAnomalyHours());
        out.put("anomalyRatePct", toPct(ov.getAnomalyRate()));
        return out;
    }

    /** ③ 建筑月度能耗趋势（最多 12 个月） */
    public Map<String, Object> buildingEnergyTrend(String buildingId, String startDate, String endDate) {
        validateBuildingId(buildingId);
        LocalDate[] r = parseRange(startDate, endDate);
        EnergyQueryRequest req = req(r[0], r[1], null, buildingId);
        req.setGranularity("monthly");
        req.setMetric("all");
        var ts = energyQueryService.queryEnergy(req);

        List<Map<String, Object>> months = new ArrayList<>();
        for (var p : ts.getPoints()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", p.getTime());
            m.put("electricityKwh", p.getElectricityKwh());
            m.put("hvacKwh", p.getHvacKwh());
            m.put("carbonKg", p.getCarbonKg());
            months.add(m);
        }
        // 月度趋势优先返回 12 个月，超出的只保留最近 12 个月
        if (months.size() > 12) {
            months = months.subList(months.size() - 12, months.size());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buildingId", buildingId);
        out.put("granularity", "month");
        out.put("months", months);
        out.put("note", "如需小时级明细，请前端直接调用 /api/energy/query，不在模型上下文返回。");
        return out;
    }

    /** ④ 建筑用电量排行（默认前 10，上限 50） */
    public Map<String, Object> topEnergyBuildings(String siteId, String startDate, String endDate, int limit) {
        if (StringUtils.hasText(siteId)) {
            validateSiteId(siteId);
        }
        LocalDate[] r = parseRange(startDate, endDate);
        int n = validateLimit(limit);
        List<Map<String, Object>> rows = energyQueryMapper.selectTopBuildingsByEnergy(
                r[0], r[1], StringUtils.hasText(siteId) ? siteId : null, n);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topBuildings", rows);
        out.put("count", rows.size());
        out.put("limit", n);
        out.put("unit", "electricityKwh 单位 kWh");
        return out;
    }

    /** ⑤ 建筑 COP */
    public Map<String, Object> buildingCop(String buildingId, String startDate, String endDate) {
        validateBuildingId(buildingId);
        LocalDate[] r = parseRange(startDate, endDate);
        var c = analyticsService.copAnalysis(r[0], r[1], buildingId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buildingId", buildingId);
        out.put("copAvg", c.getCopAvg());
        out.put("copMax", c.getCopMax());
        out.put("copMin", c.getCopMin());
        out.put("copGrade", c.getCopGrade());
        out.put("gradeReason", c.getGradeReason());
        out.put("deltaTAvgC", c.getDeltaTAvg());
        out.put("coolingKwh", c.getCoolingKwh());
        out.put("supplyTempAvgC", c.getSupplyTempAvg());
        out.put("returnTempAvgC", c.getReturnTempAvg());
        return out;
    }

    /** ⑥ 建筑异常事件（含持续时长排行） */
    public Map<String, Object> buildingAnomalies(String buildingId, String startDate, String endDate, int limit) {
        validateBuildingId(buildingId);
        LocalDate[] r = parseRange(startDate, endDate);
        int n = validateLimit(limit);
        AnomalyAnalysisVO an = analyticsService.anomalyAnalysis(r[0], r[1], null, List.of(buildingId));
        var scan = analyticsService.scanAnomalies(r[0], r[1], List.of(buildingId),
                null, null, null, null, 1, n);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buildingId", buildingId);
        out.put("eventCount", an.getEventCount());
        out.put("anomalyHours", an.getAnomalyHours());
        out.put("anomalyRatePct", an.getAnomalyRate());
        out.put("avgDurationHours", an.getAvgDurationHours());
        out.put("maxDurationHours", an.getMaxDurationHours());
        out.put("events", scan.get("events"));
        return out;
    }

    /**
     * ⑯ 节能情景模拟（理论测算，只读分析）。
     *
     * <p>AI 侧的模拟不落库（save=false），避免把对话里的试探性测算写成正式记录。
     */
    public Map<String, Object> simulateEnergySaving(Map<String, Object> args) {
        var req = new com.carbon.platform.analytics.saving.dto.SavingSimulationRequest();
        req.setBuildingId(str(args, "buildingId"));
        req.setStartDate(str(args, "startDate"));
        req.setEndDate(str(args, "endDate"));
        req.setScenario(str(args, "scenario"));
        Object rr = args.get("reduceRatio");
        if (rr instanceof Number n) {
            req.setReduceRatio(n.doubleValue());
        }
        Object ch = args.get("hourStart");
        if (ch instanceof Number n) {
            req.setHourStart(n.intValue());
        }
        Object ce = args.get("hourEnd");
        if (ce instanceof Number n) {
            req.setHourEnd(n.intValue());
        }
        Object cr = args.get("copImproveRatio");
        if (cr instanceof Number n) {
            req.setCopImproveRatio(n.doubleValue());
        }
        Object up = args.get("unitPrice");
        if (up instanceof Number n) {
            req.setUnitPrice(n.doubleValue());
        }
        req.setSave(false);

        var vo = savingSimulationService.simulate(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", vo.getScenario());
        out.put("scenarioName", vo.getScenarioName());
        out.put("sufficient", vo.isSufficient());
        out.put("insufficiencyReason", vo.getInsufficiencyReason());
        out.put("params", vo.getParams());
        out.put("baseline", vo.getBaseline());
        out.put("result", vo.getResult());
        out.put("insufficient", vo.getInsufficient());
        out.put("disclaimer", "模拟结果为理论节能潜力，不是已实现的节能效果；方案落地需运维人员人工审核。");
        return out;
    }

    /**
     * ⑮ 历史处置案例（只读）。
     *
     * <p>把人工填写过的处理备注作为「真实处置经验」提供给模型参考，
     * 而不是让模型凭空编造运维方案。该工具不提供任何写能力。
     */
    public Map<String, Object> anomalyHandleHistory(String buildingId, String anomalyType, int limit) {
        if (buildingId != null && !buildingId.isBlank()) {
            validateBuildingId(buildingId);
        }
        int n = Math.max(1, Math.min(limit, 20));
        java.util.List<Map<String, Object>> cases =
                anomalyHandleService.handledCases(buildingId, anomalyType, n);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", cases.size());
        out.put("cases", cases);
        out.put("note", cases.isEmpty()
                ? "暂无历史处置记录；不得据此编造处置经验。"
                : "以上为运维人员人工填写的历史处置记录，仅供参考，不代表当前异常的处置结论。");
        return out;
    }

    /**
     * ⑬ 异常归因诊断：异常时段 vs 正常时段的客观对比证据链。
     *
     * <p>与 /api/analytics/diagnose 同源，只输出有数据支撑的成因假设。
     */
    public Map<String, Object> diagnoseAnomaly(String buildingId, String startDate, String endDate) {
        validateBuildingId(buildingId);
        LocalDate[] r = parseRange(startDate, endDate);
        return analyticsService.diagnose(r[0], r[1], List.of(buildingId));
    }

    /**
     * ⑭ 优化建议：基于能效画像给出「依据 / 措施 / 原理」。
     *
     * <p>与 /api/analytics/suggest 同源，避免模型自行编造节能百分比。
     */
    public Map<String, Object> optimizationAdvice(String buildingId, String startDate, String endDate) {
        validateBuildingId(buildingId);
        LocalDate[] r = parseRange(startDate, endDate);
        return analyticsService.suggestOptimization(r[0], r[1], buildingId);
    }

    /** ⑦ 碳排放汇总 */
    public Map<String, Object> carbonEmissionSummary(String siteId, String startDate, String endDate) {
        if (StringUtils.hasText(siteId)) {
            validateSiteId(siteId);
        }
        LocalDate[] r = parseRange(startDate, endDate);
        OverviewVO ov = energyQueryService.overviewStats(req(r[0], r[1],
                StringUtils.hasText(siteId) ? siteId : null, null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("siteId", StringUtils.hasText(siteId) ? siteId : "全部园区");
        out.put("carbonKg", ov.getCarbonKg());
        if (ov.getCarbonKg() != null) {
            out.put("carbonTon", ov.getCarbonKg().divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP));
        }
        out.put("electricityKwh", ov.getElectricityKwh());
        out.put("hvacKwh", ov.getHvacKwh());
        out.put("factorKgPerKwh", CARBON_FACTOR);
        out.put("note", "核算口径：外购电 × 电网平均排放因子 " + CARBON_FACTOR + " kgCO₂/kWh。");
        return out;
    }

    /** ⑧ 异常持续时长排行（含异常事件数、累计异常小时） */
    public Map<String, Object> topAnomalyBuildings(String siteId, String startDate, String endDate, int limit) {
        if (StringUtils.hasText(siteId)) {
            validateSiteId(siteId);
        }
        LocalDate[] r = parseRange(startDate, endDate);
        int n = validateLimit(limit);
        List<Map<String, Object>> rows = analyticsMapper.selectTopAnomalyDuration(
                r[0], r[1], StringUtils.hasText(siteId) ? siteId : null, n);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topBuildings", rows);
        out.put("count", rows.size());
        out.put("limit", n);
        out.put("note", "按单次异常最长持续小时降序，duration 单位为小时。");
        return out;
    }

    /**
     * ⑪ 异常总览：异常率/异常小时/事件数 + 异常类型分布。
     *
     * <p>回答「有哪些能耗异常」「异常情况怎么样」「异常率多少」这类<b>概览型</b>问题。
     * 与 getBuildingAnomalies（单栋明细）、getTopAnomalyBuildings（时长排行）区分。
     */
    public Map<String, Object> anomalyOverview(String siteId, String startDate, String endDate) {
        if (StringUtils.hasText(siteId)) {
            validateSiteId(siteId);
        }
        LocalDate[] r = parseRange(startDate, endDate);
        AnomalyAnalysisVO an = analyticsService.anomalyAnalysis(
                r[0], r[1], StringUtils.hasText(siteId) ? siteId : null, null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("siteId", StringUtils.hasText(siteId) ? siteId : "全部园区");
        out.put("evaluatedHours", an.getEvaluatedHours());
        out.put("anomalyHours", an.getAnomalyHours());
        out.put("anomalyRatePct", an.getAnomalyRate());
        out.put("eventCount", an.getEventCount());
        out.put("avgDurationHours", an.getAvgDurationHours());
        out.put("maxDurationHours", an.getMaxDurationHours());

        List<Map<String, Object>> types = new ArrayList<>();
        if (an.getByType() != null) {
            for (AnomalyAnalysisVO.ByType t : an.getByType()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", t.getType());
                m.put("count", t.getCount());
                m.put("percentage", t.getPercentage());
                m.put("description", t.getDescription());
                types.add(m);
            }
        }
        out.put("byType", types);

        List<Map<String, Object>> top = new ArrayList<>();
        if (an.getTopBuildings() != null) {
            int n = 0;
            for (AnomalyAnalysisVO.ByBuilding b : an.getTopBuildings()) {
                if (n++ >= 3) {
                    break;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("buildingId", b.getBuildingId());
                m.put("buildingTypeCn", b.getBuildingTypeCn());
                m.put("siteId", b.getSiteId());
                m.put("anomalyHours", b.getAnomalyHours());
                m.put("dominantType", b.getDominantType());
                top.add(m);
            }
        }
        out.put("topBuildings", top);
        out.put("note", "byType 是异常类型分布（回答「有哪些异常」用它）；"
                + "topBuildings 是异常小时最多的前 3 栋；percentage 单位为 %。");
        return out;
    }

    /** ⑨ 全部园区列表（含建筑数与年度用电），供模型知道有哪些园区可选 */
    public Map<String, Object> listSites() {
        List<Map<String, Object>> sites = energyQueryService.listSites();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sites", sites);
        out.put("count", sites == null ? 0 : sites.size());
        out.put("note", "electricityKwhYear 为各园区年度基准用电，单位 kWh。");
        return out;
    }

    /** ⑩ 园区用电量排行（一次调用返回园区维度排名，避免逐园区循环） */
    public Map<String, Object> topSites(String startDate, String endDate, int limit) {
        LocalDate[] r = parseRange(startDate, endDate);
        int n = validateLimit(limit);
        EnergyQueryRequest req = req(r[0], r[1], null, null);
        req.setMetric("electricity");
        var b = energyQueryService.breakdownStats(req, "site");
        List<Map<String, Object>> items = new ArrayList<>();
        if (b != null && b.getItems() != null) {
            for (var it : b.getItems()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("siteId", it.getKey());
                m.put("electricityKwh", it.getValue());
                m.put("percentage", it.getPercentage());
                m.put("buildingCount", it.getCount());
                items.add(m);
            }
            items.sort((x, y) -> {
                BigDecimal a1 = (BigDecimal) x.get("electricityKwh");
                BigDecimal b1 = (BigDecimal) y.get("electricityKwh");
                return b1.compareTo(a1);
            });
        }
        if (items.size() > n) {
            items = new ArrayList<>(items.subList(0, n));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topSites", items);
        out.put("count", items.size());
        out.put("totalElectricityKwh", b == null ? null : b.getTotal());
        out.put("unit", "electricityKwh 单位 kWh，percentage 为占全部园区比例");
        return out;
    }

    // ================================================================ 校验

    private void validateSiteId(String siteId) {
        if (siteId == null || !SITE_ID.matcher(siteId).matches()) {
            throw new BizException(ResultCode.BAD_REQUEST, "非法园区编号: " + siteId);
        }
    }

    private void validateBuildingId(String buildingId) {
        if (buildingId == null || !BUILDING_ID.matcher(buildingId).matches()) {
            throw new BizException(ResultCode.BAD_REQUEST, "非法建筑编号: " + buildingId);
        }
    }

    private LocalDate[] parseRange(String startDate, String endDate) {
        LocalDate s;
        LocalDate e;
        try {
            s = LocalDate.parse(startDate);
            e = LocalDate.parse(endDate);
        } catch (Exception ex) {
            throw new BizException(ResultCode.BAD_REQUEST, "日期格式须为 YYYY-MM-DD");
        }
        if (s.isAfter(e)) {
            throw new BizException(ResultCode.BAD_REQUEST, "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(s, e) > MAX_RANGE_DAYS) {
            throw new BizException(ResultCode.BAD_REQUEST, "查询时间跨度超过 " + MAX_RANGE_DAYS + " 天上限，请缩小范围或分段查询");
        }
        return new LocalDate[]{s, e};
    }

    private int validateLimit(int limit) {
        if (limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private EnergyQueryRequest req(LocalDate s, LocalDate e, String siteId, String buildingId) {
        EnergyQueryRequest r = new EnergyQueryRequest();
        r.setStartDate(s);
        r.setEndDate(e);
        r.setSiteId(siteId);
        r.setBuildingId(buildingId);
        return r;
    }

    /**
     * 归一化异常率为百分数。
     * OverviewVO.anomalyRate 是「比率」（0.0411 = 4.11%），
     * AnomalyAnalysisVO.anomalyRate 已是百分数。这里统一输出百分数，
     * 避免 AI 拿到 0.0411 却标成 % 造成 100 倍偏差。
     */
    private BigDecimal toPct(BigDecimal ratio) {
        if (ratio == null) {
            return null;
        }
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private int intArg(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v));
            } catch (NumberFormatException ignore) {
                // fallthrough
            }
        }
        return def;
    }
}

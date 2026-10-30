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
            case "getCarbonEmissionSummary" ->
                    carbonEmissionSummary(str(a, "siteId"), str(a, "startDate"), str(a, "endDate"));
            case "getTopAnomalyBuildings" ->
                    topAnomalyBuildings(str(a, "siteId"), str(a, "startDate"), str(a, "endDate"), intArg(a, "limit", DEFAULT_LIMIT));
            default -> throw new BizException(ResultCode.BAD_REQUEST, "未知工具: " + tool);
        };
    }

    // ================================================================ 工具实现

    /** ① 园区能耗汇总：总量 / 空调 / 碳排 / COP / 异常 / 峰谷 */
    public Map<String, Object> parkEnergySummary(String siteId, String startDate, String endDate) {
        validateSiteId(siteId);
        LocalDate[] r = parseRange(startDate, endDate);
        EnergyQueryRequest req = req(r[0], r[1], siteId, null);
        OverviewVO ov = energyQueryService.overviewStats(req);
        var peak = analyticsService.periodSummary("month", r[0], r[1], null, siteId, null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("siteId", siteId);
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

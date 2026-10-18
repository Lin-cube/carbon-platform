package com.carbon.platform.analytics.service;

import com.carbon.platform.analytics.dto.AnomalyAnalysisVO;
import com.carbon.platform.analytics.dto.CopAnalysisVO;
import com.carbon.platform.analytics.dto.PeriodSummaryVO;
import com.carbon.platform.analytics.mapper.AnalyticsMapper;
import com.carbon.platform.common.exception.BizException;
import com.carbon.platform.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 统计分析服务：实现申报表要求的 3 类核心统计分析。
 *
 * <ol>
 *   <li><b>时段汇总</b> —— 年/季/月/周/日/分时(尖峰平谷) 多级汇总 + 峰谷特征</li>
 *   <li><b>COP 计算</b> —— 机组能效比、供回水温差、随室外温度的衰减关系</li>
 *   <li><b>数据异常分析</b> —— 能耗异常与数据质量缺陷分开统计</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final AnalyticsMapper mapper;

    /** 允许的时段类型 -> 分组 SQL 表达式（白名单，杜绝 SQL 注入） */
    private static final Map<String, String> PERIOD_EXPR = Map.of(
            "year", "stat_year",
            "quarter", "CONCAT(stat_year, '-Q', QUARTER(stat_date))",
            "month", "DATE_FORMAT(stat_date, '%Y-%m')",
            "week", "CONCAT(stat_year, '-W', LPAD(WEEK(stat_date, 1), 2, '0'))",
            "day", "DATE_FORMAT(stat_date, '%Y-%m-%d')"
    );

    // ================================================================
    // 统计① 时段汇总
    // ================================================================
    public PeriodSummaryVO periodSummary(String periodType, LocalDate startDate, LocalDate endDate,
                                         List<String> buildingIds, String siteId, String buildingType) {
        String pt = StringUtils.hasText(periodType) ? periodType.toLowerCase() : "month";
        if (!PERIOD_EXPR.containsKey(pt)) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "不支持的时段类型: " + periodType + "，可选: " + PERIOD_EXPR.keySet());
        }

        List<Map<String, Object>> rows = mapper.selectPeriodBuckets(
                PERIOD_EXPR.get(pt), startDate, endDate, buildingIds, siteId, buildingType);

        List<PeriodSummaryVO.Bucket> buckets = new ArrayList<>();
        BigDecimal prev = null;
        BigDecimal total = BigDecimal.ZERO;

        for (Map<String, Object> r : rows) {
            PeriodSummaryVO.Bucket b = new PeriodSummaryVO.Bucket();
            b.setLabel(str(r.get("label")));
            b.setElectricityKwh(dec(r.get("electricityKwh")));
            b.setHvacKwh(dec(r.get("hvacKwh")));
            b.setWaterM3(dec(r.get("waterM3")));
            b.setCarbonKg(dec(r.get("carbonKg")));
            b.setCopAvg(dec(r.get("copAvg")));
            b.setAirTempAvg(dec(r.get("airTempAvg")));
            b.setAnomalyHours(lng(r.get("anomalyHours")));
            b.setDays(lng(r.get("days")));

            // 环比：以上一时段为基准
            if (prev != null && prev.compareTo(BigDecimal.ZERO) != 0 && b.getElectricityKwh() != null) {
                b.setMomChange(b.getElectricityKwh().subtract(prev)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(prev, 2, RoundingMode.HALF_UP));
            }
            prev = b.getElectricityKwh();
            if (b.getElectricityKwh() != null) {
                total = total.add(b.getElectricityKwh());
            }
            buckets.add(b);
        }

        PeriodSummaryVO vo = new PeriodSummaryVO();
        vo.setPeriodType(pt);
        vo.setStartDate(startDate.toString());
        vo.setEndDate(endDate.toString());
        vo.setBuckets(buckets);
        vo.setTotal(total.setScale(2, RoundingMode.HALF_UP));

        // 峰谷分析：按小时定位
        List<Map<String, Object>> hours = mapper.selectHourTotals(startDate, endDate, buildingIds, siteId);
        if (!hours.isEmpty()) {
            Map<String, Object> peak = hours.stream()
                    .max(Comparator.comparing(m -> dec(m.get("kwh")))).orElse(null);
            Map<String, Object> valley = hours.stream()
                    .min(Comparator.comparing(m -> dec(m.get("kwh")))).orElse(null);
            if (peak != null) {
                vo.setPeak(dec(peak.get("kwh")));
                vo.setPeakLabel(String.format("%02d:00", lng(peak.get("hour"))));
            }
            if (valley != null) {
                vo.setValley(dec(valley.get("kwh")));
                vo.setValleyLabel(String.format("%02d:00", lng(valley.get("hour"))));
            }
            if (vo.getValley() != null && vo.getValley().compareTo(BigDecimal.ZERO) != 0 && vo.getPeak() != null) {
                vo.setPeakValleyRatio(vo.getPeak().subtract(vo.getValley())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(vo.getValley(), 2, RoundingMode.HALF_UP));
            }
            vo.setAverage(total.divide(BigDecimal.valueOf(buckets.size() == 0 ? 1 : buckets.size()),
                    2, RoundingMode.HALF_UP));
        }

        // 分时电价时段统计
        List<Map<String, Object>> tariffs = mapper.selectTariffBuckets(startDate, endDate, buildingIds, siteId);
        BigDecimal tariffTotal = tariffs.stream()
                .map(m -> dec(m.get("electricityKwh"))).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<PeriodSummaryVO.TariffBucket> tbs = new ArrayList<>();
        Map<String, String> tariffHours = Map.of(
                "尖峰", "10-12, 14-17", "高峰", "8-9, 13, 18-19", "平段", "7, 20-22", "低谷", "23-6");
        for (Map<String, Object> t : tariffs) {
            PeriodSummaryVO.TariffBucket tb = new PeriodSummaryVO.TariffBucket();
            String name = str(t.get("name"));
            tb.setName(name);
            tb.setHours(tariffHours.getOrDefault(name, "-"));   // 该字段是时段划分描述，非小时数
            tb.setElectricityKwh(dec(t.get("electricityKwh")));
            Long hrs = lng(t.get("hourCount"));   // SQL 别名为 hourCount，避免与描述性 hours 冲突
            if (hrs != null && hrs > 0 && tb.getElectricityKwh() != null) {
                tb.setAvgHourlyKwh(tb.getElectricityKwh()
                        .divide(BigDecimal.valueOf(hrs), 2, RoundingMode.HALF_UP));
            }
            if (tariffTotal.compareTo(BigDecimal.ZERO) != 0 && tb.getElectricityKwh() != null) {
                tb.setPercentage(tb.getElectricityKwh().multiply(BigDecimal.valueOf(100))
                        .divide(tariffTotal, 2, RoundingMode.HALF_UP));
            }
            tbs.add(tb);
        }
        vo.setTariffBuckets(tbs);
        return vo;
    }

    // ================================================================
    // 统计② COP 计算
    // ================================================================
    public CopAnalysisVO copAnalysis(LocalDate startDate, LocalDate endDate, String buildingId) {
        if (!StringUtils.hasText(buildingId)) {
            throw new BizException(ResultCode.BAD_REQUEST, "COP 分析必须指定建筑（buildingId）");
        }
        List<String> ids = List.of(buildingId.trim());

        Map<String, Object> s = mapper.selectCopSummary(startDate, endDate, ids);
        CopAnalysisVO vo = new CopAnalysisVO();
        vo.setBuildingId(buildingId);
        vo.setStartDate(startDate.toString());
        vo.setEndDate(endDate.toString());

        long sample = s == null || lng(s.get("sampleHours")) == null ? 0 : lng(s.get("sampleHours"));
        if (s == null || sample == 0) {
            vo.setGradeReason("该建筑在查询区间内无冷冻水计量数据，无法计算 COP。"
                    + "原因：BDG2 中多数园区缺少可用的冷冻水表计（详见数据字典 §2.6）。");
            vo.setCopGrade("无数据");
            return vo;
        }

        vo.setCopAvg(dec(s.get("copAvg")));
        vo.setCopMax(dec(s.get("copMax")));
        vo.setCopMin(dec(s.get("copMin")));
        vo.setCopStd(dec(s.get("copStd")));
        vo.setCoolingKwh(dec(s.get("coolingKwh")));
        vo.setHvacElectricityKwh(dec(s.get("hvacElectricityKwh")));
        vo.setSupplyTempAvg(dec(s.get("supplyTempAvg")));
        vo.setReturnTempAvg(dec(s.get("returnTempAvg")));
        vo.setDeltaTAvg(dec(s.get("deltaTAvg")));

        // 系统能效比 = 制冷量 / 空调电耗
        if (vo.getCoolingKwh() != null && vo.getHvacElectricityKwh() != null
                && vo.getHvacElectricityKwh().compareTo(BigDecimal.ZERO) != 0) {
            vo.setSystemCop(vo.getCoolingKwh().divide(vo.getHvacElectricityKwh(), 3, RoundingMode.HALF_UP));
        }

        // 能效评级：以离心/螺杆机组工程经验区间为基准
        BigDecimal avg = vo.getCopAvg() == null ? BigDecimal.ZERO : vo.getCopAvg();
        if (avg.compareTo(new BigDecimal("4.5")) >= 0) {
            vo.setCopGrade("优秀");
            vo.setGradeReason(String.format("平均 COP %.2f ≥ 4.5，机组处于高效运行区间。", avg));
        } else if (avg.compareTo(new BigDecimal("3.8")) >= 0) {
            vo.setCopGrade("良好");
            vo.setGradeReason(String.format("平均 COP %.2f 处于 3.8~4.5，运行正常但仍有优化空间。", avg));
        } else if (avg.compareTo(new BigDecimal("3.0")) >= 0) {
            vo.setCopGrade("一般");
            vo.setGradeReason(String.format("平均 COP %.2f 处于 3.0~3.8，建议核查供回水温差与负荷率匹配情况。", avg));
        } else {
            vo.setCopGrade("偏低");
            vo.setGradeReason(String.format("平均 COP %.2f < 3.0，能效偏低，"
                    + "建议检查冷凝侧散热、是否存在小温差大流量、以及机组是否长期部分负荷运行。", avg));
        }

        // COP 随室外温度变化
        List<CopAnalysisVO.TempCopRelation> rels = new ArrayList<>();
        for (Map<String, Object> r : mapper.selectCopByTemperature(startDate, endDate, ids)) {
            CopAnalysisVO.TempCopRelation rel = new CopAnalysisVO.TempCopRelation();
            rel.setTempRange(str(r.get("tempRange")));
            rel.setCopAvg(dec(r.get("copAvg")));
            rel.setHours(lng(r.get("hours")));
            rels.add(rel);
        }
        vo.setTempRelations(rels);

        // COP 分布
        List<Map<String, Object>> dist = mapper.selectCopDistribution(startDate, endDate, ids);
        long totalHours = dist.stream().map(m -> lng(m.get("hours")))
                .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        List<CopAnalysisVO.Distribution> ds = new ArrayList<>();
        for (Map<String, Object> r : dist) {
            CopAnalysisVO.Distribution d = new CopAnalysisVO.Distribution();
            d.setRange(str(r.get("range")));
            d.setHours(lng(r.get("hours")));
            if (totalHours > 0 && d.getHours() != null) {
                d.setPercentage(BigDecimal.valueOf(d.getHours())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalHours), 2, RoundingMode.HALF_UP));
            }
            ds.add(d);
        }
        vo.setDistribution(ds);
        return vo;
    }

    // ================================================================
    // 统计③ 数据异常分析
    // ================================================================
    public AnomalyAnalysisVO anomalyAnalysis(LocalDate startDate, LocalDate endDate,
                                             String siteId, List<String> buildingIds) {
        Map<String, Object> s = mapper.selectAnomalySummary(startDate, endDate, siteId, buildingIds);
        AnomalyAnalysisVO vo = new AnomalyAnalysisVO();
        vo.setStartDate(startDate.toString());
        vo.setEndDate(endDate.toString());

        long evaluated = s == null || lng(s.get("evaluatedHours")) == null ? 0 : lng(s.get("evaluatedHours"));
        long anomaly = s == null || lng(s.get("anomalyHours")) == null ? 0 : lng(s.get("anomalyHours"));
        long quality = s == null || lng(s.get("qualityHours")) == null ? 0 : lng(s.get("qualityHours"));

        vo.setEvaluatedHours(evaluated);
        vo.setAnomalyHours(anomaly);
        vo.setQualityHours(quality);
        if (evaluated > 0) {
            vo.setAnomalyRate(BigDecimal.valueOf(anomaly)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(evaluated), 4, RoundingMode.HALF_UP));
            vo.setQualityRate(BigDecimal.valueOf(quality)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(evaluated), 4, RoundingMode.HALF_UP));
        }

        // 事件级统计：在 SQL 内聚合，避免把数千行事件拉进内存（曾导致接口超 2 秒）
        Map<String, Object> dur = mapper.selectAnomalyEventDuration(startDate, endDate,
                buildingIds, siteId);
        if (dur != null) {
            vo.setEventCount(lng(dur.get("eventCount")) == null ? 0L : lng(dur.get("eventCount")));
            vo.setAvgDurationHours(dec(dur.get("avgDurationHours")));
            Integer mx = lng(dur.get("maxDurationHours")) == null
                    ? null : lng(dur.get("maxDurationHours")).intValue();
            vo.setMaxDurationHours(mx);
        } else {
            vo.setEventCount(0L);
        }

        // 按类型分布（附中文解释，便于 AI 直接引用）
        Map<String, String> typeDesc = Map.of(
                "能耗偏高", "用能显著高于同期历史基线，可能存在设备低效运行、设定值不当或负荷异常增加",
                "能耗偏低", "用能显著低于基线，可能是停用、表计异常或数据缺失",
                "负值异常", "能耗读数为负，属数据采集异常",
                "表计卡死", "连续 24 小时读数完全不变，属表计或通信故障",
                "数据缺失", "连续时段无数据上报"
        );
        List<Map<String, Object>> byType = mapper.selectAnomalyByType(startDate, endDate, siteId, buildingIds);
        long typeTotal = byType.stream().map(m -> lng(m.get("count")))
                .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        List<AnomalyAnalysisVO.ByType> ts = new ArrayList<>();
        for (Map<String, Object> r : byType) {
            AnomalyAnalysisVO.ByType t = new AnomalyAnalysisVO.ByType();
            String type = str(r.get("type"));
            t.setType(type);
            t.setCount(lng(r.get("count")));
            t.setDescription(typeDesc.getOrDefault(type, "-"));
            if (typeTotal > 0 && t.getCount() != null) {
                t.setPercentage(BigDecimal.valueOf(t.getCount())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(typeTotal), 2, RoundingMode.HALF_UP));
            }
            ts.add(t);
        }
        vo.setByType(ts);

        List<AnomalyAnalysisVO.ByBuilding> bs = new ArrayList<>();
        for (Map<String, Object> r : mapper.selectAnomalyTopBuildings(startDate, endDate, siteId, 20)) {
            AnomalyAnalysisVO.ByBuilding b = new AnomalyAnalysisVO.ByBuilding();
            b.setBuildingId(str(r.get("buildingId")));
            b.setSiteId(str(r.get("siteId")));
            b.setBuildingTypeCn(str(r.get("buildingTypeCn")));
            b.setAnomalyHours(lng(r.get("anomalyHours")));
            b.setAnomalyRate(dec(r.get("anomalyRate")));
            b.setPeakDeviationPct(dec(r.get("peakDeviationPct")));
            b.setDominantType(str(r.get("dominantType")));
            bs.add(b);
        }
        vo.setTopBuildings(bs);

        List<AnomalyAnalysisVO.ByDate> ds = new ArrayList<>();
        for (Map<String, Object> r : mapper.selectAnomalyTrend(startDate, endDate, siteId)) {
            AnomalyAnalysisVO.ByDate d = new AnomalyAnalysisVO.ByDate();
            d.setDate(str(r.get("date")));
            d.setAnomalyHours(lng(r.get("anomalyHours")));
            d.setEventCount(lng(r.get("eventCount")));
            ds.add(d);
        }
        vo.setTrend(ds);
        return vo;
    }

    // ================================================================
    // MCP 工具 6：问题诊断（证据链）
    // ================================================================
    public Map<String, Object> diagnose(LocalDate startDate, LocalDate endDate, List<String> buildingIds) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buildingIds", buildingIds);
        out.put("timeRange", startDate + " ~ " + endDate);

        // 异常画像：按 建筑 × 异常类型 分组的多行结果
        List<Map<String, Object>> profiles = mapper.selectDiagnoseProfiles(buildingIds, startDate, endDate);
        out.put("anomalyProfiles", profiles);
        out.put("hasAnomaly", !profiles.isEmpty());
        out.put("anomalyTypeCount", profiles.size());

        // 异常时段 vs 正常时段对比：归因核心证据
        List<Map<String, Object>> cmp = mapper.selectAnomalyVsNormal(buildingIds, startDate, endDate);
        out.put("anomalyVsNormal", cmp);

        // 自动归因：基于对比数据推导可能原因
        out.put("attributions", buildAttributions(cmp));

        // 能效画像
        if (buildingIds != null && !buildingIds.isEmpty()) {
            Map<String, Object> profile = mapper.selectEfficiencyProfile(buildingIds, startDate, endDate);
            out.put("efficiencyProfile", profile);
        }
        return out;
    }

    /**
     * 基于「异常时段 vs 正常时段」的客观对比自动生成归因假设。
     *
     * <p>设计原则：只输出<b>有数据支撑</b>的假设，并标注证据与置信度；
     * 不做无依据的猜测。所有结论均需运维人员复核（申报表第 17 条）。
     */
    private List<Map<String, Object>> buildAttributions(List<Map<String, Object>> cmp) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (cmp == null || cmp.size() < 2) {
            return result;
        }
        Map<String, Object> abn = cmp.stream()
                .filter(m -> "异常时段".equals(str(m.get("group")))).findFirst().orElse(null);
        Map<String, Object> nor = cmp.stream()
                .filter(m -> "正常时段".equals(str(m.get("group")))).findFirst().orElse(null);
        if (abn == null || nor == null) {
            return result;
        }

        BigDecimal abnKwh = dec(abn.get("avgKwh"));
        BigDecimal norKwh = dec(nor.get("avgKwh"));
        BigDecimal abnTemp = dec(abn.get("avgTemp"));
        BigDecimal norTemp = dec(nor.get("avgTemp"));
        BigDecimal abnOcc = dec(abn.get("avgOccupancy"));
        BigDecimal norOcc = dec(nor.get("avgOccupancy"));
        BigDecimal abnCop = dec(abn.get("avgCop"));
        BigDecimal norCop = dec(nor.get("avgCop"));
        BigDecimal abnDt = dec(abn.get("avgDeltaT"));
        BigDecimal norDt = dec(nor.get("avgDeltaT"));

        // 归因 1：室外温度
        if (abnTemp != null && norTemp != null) {
            BigDecimal d = abnTemp.subtract(norTemp);
            if (d.compareTo(new BigDecimal("3.0")) >= 0) {
                result.add(attr("室外温度升高", "高",
                        String.format("异常时段平均室外温度 %.1f℃，比正常时段高 %.1f℃，"
                                + "冷负荷增加可解释用电上升。", abnTemp, d),
                        "建议核查冷站是否已启用高温天气运行策略（提高供回水温差、优化机组加载）"));
            }
        }
        // 归因 2：人员密度
        if (abnOcc != null && norOcc != null) {
            BigDecimal d = abnOcc.subtract(norOcc);
            if (abnOcc.compareTo(BigDecimal.ZERO) > 0
                    && d.divide(norOcc.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ONE : norOcc,
                    2, RoundingMode.HALF_UP).compareTo(new BigDecimal("0.2")) >= 0) {
                result.add(attr("人员在室率上升", "中",
                        String.format("异常时段人员密度 %.2f 人/100㎡，高于正常时段 %.2f，"
                                + "新风与照明负荷随之上升。", abnOcc, norOcc),
                        "属于正常用能增长，建议关注是否与大型活动/考试周重合，无需处置"));
            }
        }
        // 归因 3：机组能效下降
        if (abnCop != null && norCop != null) {
            BigDecimal d = norCop.subtract(abnCop);
            if (d.compareTo(new BigDecimal("0.3")) >= 0) {
                result.add(attr("机组能效下降", "高",
                        String.format("异常时段平均 COP %.2f，比正常时段低 %.2f，"
                                + "同样制冷量下电耗上升。", abnCop, d),
                        "建议检查冷凝器结垢、冷却塔散热效果、冷媒量，以及是否存在低负荷频繁启停"));
            }
        }
        // 归因 4：供回水温差偏小（小温差大流量）
        if (abnDt != null && abnDt.compareTo(new BigDecimal("4.0")) < 0) {
            result.add(attr("供回水温差偏小", "中",
                    String.format("异常时段平均供回水温差仅 %.2f℃，低于经济运行的 5℃ 参考值，"
                            + "存在\u201c小温差大流量\u201d嫌疑，水泵能耗偏高。", abnDt),
                    "建议核查冷冻水流量是否过大、末端阀门是否全开，考虑变流量控制或水泵变频改造"));
        }
        // 归因 5：能耗变化无法由上述因素解释
        if (result.isEmpty() && abnKwh != null && norKwh != null
                && norKwh.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal pct = abnKwh.subtract(norKwh).multiply(BigDecimal.valueOf(100))
                    .divide(norKwh, 1, RoundingMode.HALF_UP);
            result.add(attr("原因待查", "低",
                    String.format("异常时段平均电耗 %.1f kWh，比正常时段高 %s%%，"
                            + "但温度、人员、COP、温差等可比因素均无显著差异。", abnKwh, pct),
                    "建议现场核查该时段是否有临时用电设备接入、是否存在表计异常或未记录的运行变更"));
        }
        return result;
    }

    private Map<String, Object> attr(String cause, String confidence, String evidence, String suggestion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cause", cause);
        m.put("confidence", confidence);
        m.put("evidence", evidence);
        m.put("suggestion", suggestion);
        m.put("needsManualReview", true);
        return m;
    }

    // ================================================================
    // MCP 工具 5：异常扫描
    // ================================================================
    public Map<String, Object> scanAnomalies(LocalDate startDate, LocalDate endDate,
                                             List<String> buildingIds, String siteId,
                                             String anomalyType, String severity,
                                             Integer minDuration, int pageNum, int pageSize) {
        int offset = Math.max(0, (Math.max(1, pageNum) - 1) * Math.max(1, pageSize));
        int limit = Math.min(Math.max(1, pageSize), 500);

        Long total = mapper.countAnomalyEvents(startDate, endDate, buildingIds, siteId,
                anomalyType, severity, minDuration);
        List<Map<String, Object>> events = mapper.selectAnomalyEvents(startDate, endDate, buildingIds,
                siteId, anomalyType, severity, minDuration, offset, limit);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0L : total);
        out.put("pageNum", pageNum);
        out.put("pageSize", limit);
        out.put("events", events);
        out.put("severitySummary", mapper.selectAnomalyByType(startDate, endDate, siteId, buildingIds));
        return out;
    }


    // ================================================================
    // MCP 工具 7：优化建议
    // ================================================================
    public Map<String, Object> suggestOptimization(LocalDate startDate, LocalDate endDate,
                                                   String buildingId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buildingId", buildingId);
        out.put("timeRange", startDate + " ~ " + endDate);

        Map<String, Object> profile = mapper.selectEfficiencyProfile(List.of(buildingId), startDate, endDate);
        if (profile == null || profile.isEmpty()) {
            out.put("suggestions", List.of());
            out.put("note", "该建筑在查询区间内无有效数据，无法给出优化建议。");
            return out;
        }
        out.put("efficiencyProfile", profile);

        List<Map<String, Object>> suggestions = new ArrayList<>();
        BigDecimal copAvg = dec(profile.get("copAvg"));
        BigDecimal copMin = dec(profile.get("copMin"));
        BigDecimal dt = dec(profile.get("deltaTAvg"));
        BigDecimal supply = dec(profile.get("supplyTempAvg"));
        BigDecimal anomalyHours = dec(profile.get("anomalyHours"));
        Long evaluated = lng(profile.get("evaluatedHours"));
        BigDecimal dayKwh = dec(profile.get("daytimeAvgKwh"));
        BigDecimal nightKwh = dec(profile.get("nightAvgKwh"));

        // 建议 1：机组能效
        if (copAvg != null && copAvg.compareTo(new BigDecimal("3.8")) < 0) {
            suggestions.add(sugg("提升机组能效", "高",
                    String.format("区间平均 COP 为 %.2f，低于良好水平 3.8；最低 COP 为 %s。",
                            copAvg, copMin),
                    "① 检查冷凝器/蒸发器换热面结垢情况并安排清洗；"
                            + "② 核查冷却塔布水与风机运行，确保散热充分；"
                            + "③ 检查冷媒充注量；"
                            + "④ 优化机组加减载逻辑，避免长期低负荷频繁启停。",
                    "冷凝温度每降低 1℃，离心机 COP 约提升 2%~3%"));
        }
        // 建议 2：供回水温差
        if (dt != null && dt.compareTo(new BigDecimal("5.0")) < 0) {
            suggestions.add(sugg("改善小温差大流量", "高",
                    String.format("平均供回水温差仅 %.2f℃，低于经济运行的 5℃ 参考值，"
                            + "说明水系统流量偏大、输配能耗偏高。", dt),
                    "① 核查末端阀门开度与旁通阀是否误开；"
                            + "② 采用变流量控制（变频水泵 + 压差/温差控制）；"
                            + "③ 复核水泵选型是否过大；"
                            + "④ 平衡各支路水力工况。",
                    "温差由 2.5℃ 提升到 5℃ 可使冷冻水输配能耗下降约 40%（相似定律）"));
        }
        // 建议 3：供水温度设定
        if (supply != null && supply.compareTo(new BigDecimal("7.5")) < 0) {
            suggestions.add(sugg("优化冷冻水供水温度设定", "中",
                    String.format("平均供水温度 %.2f℃，设定偏低。"
                            + "在除湿需求可控的前提下，提高供水温度可显著提升机组 COP。", supply),
                    "① 按室外湿球温度分档动态调整供水温度设定（如 7℃→9℃）；"
                            + "② 与末端除湿需求校核，避免影响舒适度；"
                            + "③ 结合自控系统做再设点（reset）策略。",
                    "供水温度每提高 1℃，机组 COP 约提升 2%~4%"));
        }
        // 建议 4：异常时段治理
        if (anomalyHours != null && evaluated != null && evaluated > 0
                && anomalyHours.divide(BigDecimal.valueOf(evaluated), 4, RoundingMode.HALF_UP)
                .compareTo(new BigDecimal("0.05")) > 0) {
            suggestions.add(sugg("治理高频异常时段", "中",
                    String.format("区间内共 %s 小时被判为能耗异常，占评估小时数的 %.1f%%。",
                            anomalyHours, anomalyHours.divide(BigDecimal.valueOf(evaluated), 4,
                                    RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))),
                    "① 结合 /api/analytics/diagnose 的归因结果定位主要成因；"
                            + "② 核查异常时段的运行策略变更记录；"
                            + "③ 如集中在夜间，排查是否存在设备未按计划停机。",
                    "已识别的异常均可在异常事件表中追溯并登记处理结论"));
        }
        // 建议 5：夜间基载
        if (nightKwh != null && dayKwh != null && dayKwh.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal ratio = nightKwh.divide(dayKwh, 3, RoundingMode.HALF_UP);
            if (ratio.compareTo(new BigDecimal("0.6")) > 0) {
                suggestions.add(sugg("核查夜间基载偏高", "中",
                        String.format("夜间（0-6 时）平均电耗 %.1f kWh，为日间（10-17 时）的 %.0f%%，"
                                + "夜间基载偏高通常意味着非必要设备长期运行。", nightKwh,
                                ratio.multiply(BigDecimal.valueOf(100))),
                        "① 排查夜间应停未停的设备（照明、新风、末端风机）；"
                                + "② 制定并下发分时段启停计划表；"
                                + "③ 对夜间基载做趋势监控，异常上升及时告警。",
                        "夜间基载是公共建筑最容易忽视也最容易见效的节能点"));
            }
        }
        if (suggestions.isEmpty()) {
            suggestions.add(sugg("维持现有运行策略", "低",
                    "各项能效指标均处于合理区间，未发现明显优化空间。",
                    "建议保持现有运行策略，并持续关注 COP 与供回水温差的趋势变化。",
                    "能效指标正常"));
        }

        out.put("suggestions", suggestions);
        out.put("disclaimer", "以上建议由算法基于实测数据生成，实施前须经运维人员结合现场情况复核"
                + "（申报表第 17 条：AI 输出不直接作为最终决策依据）。");
        return out;
    }

    private Map<String, Object> sugg(String title, String priority, String evidence,
                                     String actions, String basis) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("priority", priority);
        m.put("evidence", evidence);
        m.put("actions", actions);
        m.put("basis", basis);
        m.put("needsManualReview", true);
        return m;
    }

    // ================================================================
    // 内部工具
    // ================================================================
    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static BigDecimal dec(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal bd) {
            return bd;
        }
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long lng(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

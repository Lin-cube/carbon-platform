package com.carbon.platform.energy.service.impl;

import com.carbon.platform.common.exception.BizException;
import com.carbon.platform.common.result.ResultCode;
import com.carbon.platform.energy.dto.*;
import com.carbon.platform.energy.mapper.EnergyQueryMapper;
import com.carbon.platform.energy.service.EnergyQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 能耗查询服务实现。
 *
 * <p>约束设计：
 * <ul>
 *   <li>时间范围强制校验（默认近 90 天，上限可配置），避免全表扫描拖慢响应；</li>
 *   <li>小时粒度仅允许单建筑查询，防止一次拉回千万行；</li>
 *   <li>粒度与数据源自动路由：日/月走预聚合表，小时走事实表。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnergyQueryServiceImpl implements EnergyQueryService {

    private static final Set<String> VALID_GRANULARITY = Set.of("hourly", "daily", "monthly");
    private static final Set<String> VALID_GROUP_BY =
            Set.of("site", "building_type", "season", "hour", "data_grade");

    private final EnergyQueryMapper mapper;

    @Value("${carbon.query.max-time-range-days:366}")
    private int maxRangeDays;

    @Value("${carbon.query.max-page-size:500}")
    private int maxPageSize;

    // ================================================================
    // MCP 工具 1：维度查询
    // ================================================================
    @Override
    public DimensionVO queryDimensions(String dimension) {
        String dim = StringUtils.hasText(dimension) ? dimension : "all";
        DimensionVO vo = new DimensionVO();
        vo.setDimension(dim);
        List<DimensionVO.Option> options = mapper.selectDimensionOptions(dim);
        vo.setOptions(options);
        vo.setTotal(options.size());
        return vo;
    }

    // ================================================================
    // MCP 工具 2：概览统计
    // ================================================================
    @Override
    public OverviewVO overviewStats(EnergyQueryRequest req) {
        LocalDate[] range = resolveRange(req);
        OverviewVO vo = mapper.selectOverview(range[0], range[1], req.getSiteId(), req.getBuildingId());
        if (vo == null) {
            vo = new OverviewVO();
        }
        if (vo.getAnomalyHours() == null) {
            vo.setAnomalyHours(0L);
        }
        if (vo.getTotalHours() == null) {
            vo.setTotalHours(0L);
        }
        return vo;
    }

    // ================================================================
    // MCP 工具 3：分解统计
    // ================================================================
    @Override
    public BreakdownVO breakdownStats(EnergyQueryRequest req, String groupBy) {
        String gb = StringUtils.hasText(groupBy) ? groupBy : "site";
        if (!VALID_GROUP_BY.contains(gb)) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "不支持的分组维度: " + gb + "，可选: " + VALID_GROUP_BY);
        }
        LocalDate[] range = resolveRange(req);
        String metric = normalizeMetric(req.getMetric());

        List<Map<String, Object>> rows = switch (gb) {
            case "building_type" -> mapper.selectBreakdownByType(range[0], range[1], metric);
            case "season" -> mapper.selectBreakdownBySeason(range[0], range[1], metric);
            case "hour" -> mapper.selectBreakdownByHour(range[0], range[1], metric);
            case "data_grade" -> mapper.selectBreakdownByGrade(range[0], range[1], metric);
            default -> mapper.selectBreakdownBySite(range[0], range[1], metric);
        };

        BigDecimal total = rows.stream()
                .map(r -> toDecimal(r.get("value")))
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<BreakdownVO.Item> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BreakdownVO.Item item = new BreakdownVO.Item();
            item.setKey(str(r.get("key")));
            item.setLabel(str(r.get("label")));
            item.setValue(toDecimal(r.get("value")));
            item.setCount(toLong(r.get("count")));
            if (total.compareTo(BigDecimal.ZERO) != 0 && item.getValue() != null) {
                item.setPercentage(item.getValue()
                        .multiply(BigDecimal.valueOf(100))
                        .divide(total, 2, RoundingMode.HALF_UP));
            } else {
                item.setPercentage(BigDecimal.ZERO);
            }
            items.add(item);
        }

        BreakdownVO vo = new BreakdownVO();
        vo.setGroupBy(gb);
        vo.setMetric(metric);
        vo.setItems(items);
        vo.setTotal(total.setScale(3, RoundingMode.HALF_UP));
        return vo;
    }

    // ================================================================
    // MCP 工具 4：能耗查询（时序）
    // ================================================================
    @Override
    public TimeSeriesVO queryEnergy(EnergyQueryRequest req) {
        LocalDate[] range = resolveRange(req);
        String granularity = normalizeGranularity(req.getGranularity());
        List<String> buildingIds = splitIds(req.getBuildingId());

        // 小时粒度限制单建筑，避免返回量爆炸
        if ("hourly".equals(granularity) && (buildingIds == null || buildingIds.size() != 1)) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "小时粒度查询请指定且仅指定 1 个建筑（buildingId），否则数据量过大；"
                            + "多建筑对比请使用 daily 或 monthly 粒度");
        }

        boolean onlyAnomaly = Boolean.TRUE.equals(req.getOnlyAnomaly());
        List<TimeSeriesVO.Point> points = switch (granularity) {
            case "hourly" -> mapper.selectHourlySeries(range[0], range[1], buildingIds,
                    onlyAnomaly, 20000);
            case "monthly" -> mapper.selectMonthlySeries(range[0], range[1], buildingIds,
                    req.getSiteId(), req.getBuildingType(), onlyAnomaly);
            default -> mapper.selectDailySeries(range[0], range[1], buildingIds,
                    req.getSiteId(), req.getBuildingType(), onlyAnomaly);
        };

        List<TimeSeriesVO.Summary> summary = mapper.selectBuildingSummary(
                range[0], range[1], buildingIds, req.getSiteId(), req.getBuildingType(), 50);

        TimeSeriesVO vo = new TimeSeriesVO();
        vo.setGranularity(granularity);
        vo.setMetric(normalizeMetric(req.getMetric()));
        vo.setPoints(points);
        vo.setSummary(summary);
        vo.setBuildings(summary.stream().map(TimeSeriesVO.Summary::getBuildingId).toList());
        if (points.isEmpty()) {
            log.info("查询区间内无数据: {} ~ {}", range[0], range[1]);
        }
        return vo;
    }

    // ================================================================
    // 维表查询
    // ================================================================
    @Override
    public List<Map<String, Object>> listBuildings(String siteId, String buildingType,
                                                   String keyword, int limit) {
        return mapper.selectBuildings(siteId, buildingType, keyword, clampLimit(limit));
    }

    @Override
    public Map<String, Object> buildingDetail(String buildingId) {
        Map<String, Object> detail = mapper.selectBuildingDetail(buildingId);
        if (detail == null || detail.isEmpty()) {
            throw new BizException(ResultCode.BUILDING_NOT_FOUND, "建筑不存在: " + buildingId);
        }
        return detail;
    }

    @Override
    public List<Map<String, Object>> listSites() {
        return mapper.selectSites();
    }

    // ================================================================
    // 内部工具
    // ================================================================

    /** 时间范围校验：默认近 90 天，超上限报错 */
    private LocalDate[] resolveRange(EnergyQueryRequest req) {
        LocalDate end = req.getEndDate() != null ? req.getEndDate() : LocalDate.of(2017, 6, 30);
        LocalDate start = req.getStartDate() != null ? req.getStartDate() : end.minusDays(90);
        if (start.isAfter(end)) {
            throw new BizException(ResultCode.BAD_REQUEST, "开始日期不能晚于结束日期");
        }
        long days = ChronoUnit.DAYS.between(start, end);
        if (days > maxRangeDays) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    String.format("查询时间跨度 %d 天超过上限 %d 天，请缩小范围", days, maxRangeDays));
        }
        return new LocalDate[]{start, end};
    }

    private String normalizeGranularity(String g) {
        String v = StringUtils.hasText(g) ? g.toLowerCase() : "daily";
        if (!VALID_GRANULARITY.contains(v)) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "不支持的时间粒度: " + g + "，可选: hourly / daily / monthly");
        }
        return v;
    }

    private String normalizeMetric(String m) {
        if (!StringUtils.hasText(m)) {
            return "electricity";
        }
        return switch (m.toLowerCase()) {
            case "hvac" -> "hvac";
            case "water" -> "water";
            case "carbon" -> "carbon";
            case "cop" -> "cop";
            default -> "electricity";
        };
    }

    private List<String> splitIds(String csv) {
        if (!StringUtils.hasText(csv)) {
            return null;
        }
        List<String> ids = Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        return ids.isEmpty() ? null : ids;
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return 200;
        }
        return Math.min(limit, maxPageSize);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static BigDecimal toDecimal(Object o) {
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

    private static Long toLong(Object o) {
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

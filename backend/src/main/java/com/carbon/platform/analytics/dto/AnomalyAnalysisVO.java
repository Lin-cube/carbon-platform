package com.carbon.platform.analytics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 核心统计分析 ③：数据异常分析。
 *
 * <p>严格区分「能耗异常」与「数据质量缺陷」两类判定（详见数据字典 §2）。
 */
@Data
@Schema(description = "异常分析统计")
public class AnomalyAnalysisVO {

    private String startDate;
    private String endDate;

    @Schema(description = "评估小时数（仅 A 级建筑）")
    private Long evaluatedHours;
    @Schema(description = "能耗异常小时数")
    private Long anomalyHours;
    @Schema(description = "能耗异常率")
    private BigDecimal anomalyRate;
    @Schema(description = "数据质量缺陷小时数（表计卡死/缺失，非能耗异常）")
    private Long qualityHours;
    @Schema(description = "数据质量缺陷率")
    private BigDecimal qualityRate;

    @Schema(description = "异常事件数（连续异常已聚合）")
    private Long eventCount;
    @Schema(description = "平均事件持续小时")
    private BigDecimal avgDurationHours;
    @Schema(description = "最长事件持续小时")
    private Integer maxDurationHours;

    @Schema(description = "按异常类型分布")
    private List<ByType> byType;
    @Schema(description = "异常 TOP 建筑")
    private List<ByBuilding> topBuildings;
    @Schema(description = "异常随时间趋势")
    private List<ByDate> trend;

    @Data
    public static class ByType {
        private String type;
        private Long count;
        private BigDecimal percentage;
        private String description;
    }

    @Data
    public static class ByBuilding {
        private String buildingId;
        private String buildingTypeCn;
        private String siteId;
        private Long anomalyHours;
        private BigDecimal anomalyRate;
        private BigDecimal peakDeviationPct;
        private String dominantType;
    }

    @Data
    public static class ByDate {
        private String date;
        private Long anomalyHours;
        private Long eventCount;
    }
}

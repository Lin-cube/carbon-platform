package com.carbon.platform.analytics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 核心统计分析 ①：时段汇总。
 *
 * <p>支持按 年/季/月/周/日/时段(尖峰平谷) 多级汇总，
 * 并给出同比/环比与峰谷特征。
 */
@Data
@Schema(description = "时段汇总统计")
public class PeriodSummaryVO {

    @Schema(description = "汇总口径：year/quarter/month/week/day/holiday")
    private String periodType;
    private String startDate;
    private String endDate;

    private List<Bucket> buckets;

    @Schema(description = "区间合计")
    private BigDecimal total;
    @Schema(description = "区间均值")
    private BigDecimal average;
    @Schema(description = "峰值")
    private BigDecimal peak;
    @Schema(description = "峰值出现时间")
    private String peakLabel;
    @Schema(description = "谷值")
    private BigDecimal valley;
    private String valleyLabel;
    @Schema(description = "峰谷差率 %")
    private BigDecimal peakValleyRatio;

    @Schema(description = "尖峰平谷分时统计")
    private List<TariffBucket> tariffBuckets;

    @Data
    public static class Bucket {
        private String label;
        private BigDecimal electricityKwh;
        private BigDecimal hvacKwh;
        private BigDecimal waterM3;
        private BigDecimal carbonKg;
        private BigDecimal copAvg;
        private BigDecimal airTempAvg;
        private Long anomalyHours;
        private Long days;
        /** 环比变化 % */
        private BigDecimal momChange;
    }

    /** 分时电价时段划分（尖峰/峰/平/谷），用于节能策略分析 */
    @Data
    public static class TariffBucket {
        private String name;
        private String hours;
        private BigDecimal electricityKwh;
        private BigDecimal percentage;
        private BigDecimal avgHourlyKwh;
    }
}

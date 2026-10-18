package com.carbon.platform.energy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 时序查询返回（能耗查询 / 看板趋势图共用）。
 */
@Data
@Schema(description = "能耗时序数据")
public class TimeSeriesVO {

    private String granularity;
    private String metric;
    private List<String> buildings;

    @Schema(description = "数据点列表")
    private List<Point> points;

    @Schema(description = "各建筑合计，用于对比图")
    private List<Summary> summary;

    @Data
    public static class Point {
        @Schema(description = "时间标签")
        private String time;
        private String buildingId;
        private BigDecimal electricityKwh;
        private BigDecimal hvacKwh;
        private BigDecimal waterM3;
        private BigDecimal carbonKg;
        private BigDecimal cop;
        private BigDecimal airTemperatureC;
        @Schema(description = "是否含异常：0 正常 1 异常")
        private Integer anomalyFlag;
    }

    @Data
    public static class Summary {
        private String buildingId;
        private String buildingTypeCn;
        private BigDecimal electricityKwh;
        private BigDecimal hvacKwh;
        private BigDecimal carbonKg;
        private BigDecimal copAvg;
        private BigDecimal eui;
    }
}

package com.carbon.platform.energy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * MCP 工具 2 `overview_stats` 的返回：概览统计指标卡。
 */
@Data
@Schema(description = "能耗概览统计")
public class OverviewVO {

    private Long buildingCount;
    private Long siteCount;
    private Long totalHours;

    @Schema(description = "总用电量 kWh")
    private BigDecimal electricityKwh;
    @Schema(description = "总空调能耗 kWh")
    private BigDecimal hvacKwh;
    @Schema(description = "总水耗 m³")
    private BigDecimal waterM3;
    @Schema(description = "总碳排放 kgCO₂")
    private BigDecimal carbonKg;

    @Schema(description = "平均 COP")
    private BigDecimal copAvg;

    @Schema(description = "异常小时数")
    private Long anomalyHours;
    @Schema(description = "异常率")
    private BigDecimal anomalyRate;

    @Schema(description = "统计时间范围")
    private String timeRange;
}

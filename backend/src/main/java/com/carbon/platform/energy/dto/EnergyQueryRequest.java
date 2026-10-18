package com.carbon.platform.energy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/**
 * 多条件能耗查询请求（申报表"按建筑、时间、监测参数等条件精准查询"）。
 */
@Data
@Schema(description = "能耗查询条件")
public class EnergyQueryRequest {

    @Schema(description = "建筑编号，多个用逗号分隔", example = "Eagle_education_Peter")
    private String buildingId;

    @Schema(description = "园区编号，多个用逗号分隔", example = "Eagle")
    private String siteId;

    @Schema(description = "建筑类型（中文）", example = "教学楼")
    private String buildingType;

    @Schema(description = "数据分级 A/B", example = "A")
    private String dataGrade;

    @Schema(description = "开始日期", example = "2016-06-01")
    private LocalDate startDate;

    @Schema(description = "结束日期（含）", example = "2016-08-31")
    private LocalDate endDate;

    @Schema(description = "时间粒度：hourly/daily/monthly", example = "daily")
    private String granularity = "daily";

    @Schema(description = "统计参数：electricity/hvac/water/carbon/cop/all", example = "all")
    private String metric = "all";

    @Schema(description = "是否只返回异常记录", example = "false")
    private Boolean onlyAnomaly = false;

    @Schema(description = "页码（明细模式生效）", example = "1")
    private Integer pageNum = 1;

    @Schema(description = "每页条数", example = "200")
    private Integer pageSize = 200;
}

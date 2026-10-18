package com.carbon.platform.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/** AI 报表生成请求 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportRequest {
    /** 报表类型：energy_summary 能耗汇总 / anomaly_report 异常报告 / cop_report 能效报告 */
    private String reportType = "energy_summary";
    private String startDate;
    private String endDate;
    private List<String> buildingIds;
    private String siteId;
    /** 是否由 AI 生成解读文字 */
    private Boolean withNarrative = true;
}

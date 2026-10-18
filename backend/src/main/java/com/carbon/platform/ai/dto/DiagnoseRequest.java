package com.carbon.platform.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** AI 诊断请求 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DiagnoseRequest {
    private String buildingId;
    private String startDate;
    private String endDate;
    /** 用户补充描述，可选 */
    private String remark;
}

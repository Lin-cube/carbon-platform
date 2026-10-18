package com.carbon.platform.ai.dto;

import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Map;

/** AI 问答请求（与接口契约 §4 一致） */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatRequest {

    @NotBlank(message = "问题不能为空")
    private String question;

    /** 会话 ID，用于多轮上下文与审计追踪 */
    private String sessionId;

    /** 上下文：如当前选中的建筑、用户角色 */
    private Map<String, Object> context;

    private String buildingId;
    private String startDate;
    private String endDate;
}

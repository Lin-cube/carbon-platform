package com.carbon.platform.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** AI 问答响应（与接口契约 §4 一致）
 *
 * <p>加 {@code ignoreUnknown=true}：AI 服务（Python）可能返回契约之外的附加字段，
 * 若不容错会导致整条问答链路解析失败。契约演进时保持向后兼容。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatResponse {

    private Boolean ok = true;

    private String answer;

    /** 知识库引用（RAG 溯源），用于准确率评估与可解释性 */
    private List<Citation> citations;

    /** 本次调用的 MCP 工具链，用于可解释性与指标评测 */
    private List<ToolCall> toolCalls;

    /** 数据证据（支撑回答的关键数字） */
    private Map<String, Object> dataEvidence;

    private Double confidence;

    private Long elapsedMs;

    /** 数据来源标注：实测 / 推算 / 仿真 / 混合 */
    private String dataSource;

    private String error;

    /** Token 用量（仅 DeepSeek 工具调用路径返回，用于成本统计） */
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    @Data
    public static class Citation {
        private String source;
        private Integer page;
        private String snippet;
    }

    @Data
    public static class ToolCall {
        private String tool;
        private Map<String, Object> args;
        private Long elapsedMs;
        private Boolean success;
    }
}

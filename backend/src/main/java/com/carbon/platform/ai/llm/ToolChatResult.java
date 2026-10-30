package com.carbon.platform.ai.llm;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次大模型「工具调用」请求的返回结果。
 *
 * <p>区别于 {@link LlmClient#chat} 只返回文本，本结构同时携带：
 * <ul>
 *   <li>模型请求调用的工具（{@code tool_calls}）；</li>
 *   <li>当模型直接给出最终答案时的文本（{@code content}）；</li>
 *   <li>Token 用量（prompt / completion / total），用于成本与用量统计。</li>
 * </ul>
 */
@Data
public class ToolChatResult {

    /** 模型直接给出的最终文本；当本轮需要调用工具时为 null */
    private String content;

    /** 模型请求调用的工具（可能为空） */
    private List<ToolCall> toolCalls = new ArrayList<>();

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private long elapsedMs;

    /** 是否请求了工具调用（需要执行工具后继续对话） */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    @Data
    public static class ToolCall {
        private String id;
        private String name;
        /** 工具参数 JSON 字符串 */
        private String arguments;
    }
}

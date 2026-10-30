package com.carbon.platform.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * AI 工具调用用量记录器。
 *
 * <p>把每次 DeepSeek 工具调用的「问题 / 工具名 / 参数 / 结果行数 / 耗时 / Token 用量」
 * 写入 {@code ai_tool_call_log}，用于成本统计与调用审计。
 *
 * <p>【最小权限】仅 INSERT 到日志表，不做任何查询/删除/更新，不影响业务数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiToolUsageRecorder {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 记录一次工具调用（每轮每次工具各一条） */
    public void record(String sessionId, String question, String toolName,
                       Map<String, Object> args, int resultRows, long elapsedMs,
                       Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO ai_tool_call_log(session_id, question, tool_name, tool_args, "
                            + "tool_result_rows, elapsed_ms, prompt_tokens, completion_tokens, "
                            + "total_tokens, created_at) VALUES (?,?,?,?,?,?,?,?,?,NOW())",
                    substr(sessionId, 64), substr(question, 1000), substr(toolName, 64),
                    toJson(args), resultRows, elapsedMs,
                    nz(promptTokens), nz(completionTokens), nz(totalTokens));
        } catch (Exception e) {
            // 日志写入失败不影响主流程
            log.debug("AI 工具调用记录失败：{}", e.getMessage());
        }
    }

    private String substr(String s, int max) {
        if (!StringUtils.hasText(s)) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private int nz(Integer i) {
        return i == null ? 0 : i;
    }

    /** 参数序列化为合法 JSON（tool_args 列是 json 类型，不能用 Map.toString()） */
    private String toJson(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(args);
        } catch (Exception e) {
            return "{}";
        }
    }
}

package com.carbon.platform.ai.controller;

import com.carbon.platform.ai.tool.AiQueryToolService;
import com.carbon.platform.ai.tool.AiToolSchema;
import com.carbon.platform.common.annotation.OperLog;
import com.carbon.platform.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI 内部数据查询工具（DeepSeek Tool Calling 准备）。
 *
 * <p>提供：
 * <ul>
 *   <li>{@code GET /api/ai/tools/schema} —— 标准化的函数调用工具定义（JSON Schema）；</li>
 *   <li>{@code POST /api/ai/tools/call} —— 按 {tool, arguments} 分发执行并返回摘要结果。</li>
 * </ul>
 * 所有查询复用 AnalyticsService / EnergyQueryService 的预聚合表统计口径。
 */
@Tag(name = "AI 内部查询工具")
@RestController
@RequestMapping("/api/ai/tools")
@RequiredArgsConstructor
public class AiToolController {

    private final AiQueryToolService toolService;
    private final AiToolSchema toolSchema;

    @Operation(summary = "标准化工具定义（DeepSeek Tool Calling 用）")
    @GetMapping("/schema")
    @OperLog(title = "AI-工具定义", businessType = OperLog.BusinessType.QUERY)
    public R<List<Map<String, Object>>> schema() {
        return R.ok(toolSchema.toolDefinitions());
    }

    @Operation(summary = "执行 AI 查询工具（受控，仅返回统计摘要）")
    @PostMapping("/call")
    @OperLog(title = "AI-工具调用", businessType = OperLog.BusinessType.QUERY)
    public R<Map<String, Object>> call(@RequestBody Map<String, Object> body) {
        String tool = body == null ? null : String.valueOf(body.get("tool"));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = body == null ? Map.of()
                : (Map<String, Object>) body.getOrDefault("arguments", Map.of());
        return R.ok(toolService.call(tool, args));
    }
}

package com.carbon.platform.ai.controller;

import com.carbon.platform.ai.client.AiServiceClient;
import com.carbon.platform.ai.dto.ChatRequest;
import com.carbon.platform.ai.dto.ChatResponse;
import com.carbon.platform.ai.dto.DiagnoseRequest;
import com.carbon.platform.ai.dto.ReportRequest;
import com.carbon.platform.common.annotation.OperLog;
import com.carbon.platform.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 「小E」AI 助手接口。
 *
 * <p>后端通过 {@link AiServiceClient} 对接角色 B 的 AI 服务，
 * 当前 MOCK 模式下由后端统计能力驱动，接口形态与真实服务完全一致。
 */
@Tag(name = "04-AI助手", description = "小E 智能问答、诊断、报表（对接角色 B 的 RAG/MCP 服务）")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiServiceClient aiServiceClient;
    private final com.carbon.platform.ai.service.DashboardInsightService dashboardInsightService;

    @Operation(summary = "自然语言问答（小E 助手）")
    @PostMapping("/chat")
    @OperLog(title = "AI-问答", businessType = OperLog.BusinessType.QUERY)
    public R<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return R.ok(aiServiceClient.chat(request));
    }

    @Operation(summary = "AI 文字解读（看板 / 3D园区 / 异常诊断，只送聚合指标）")
    @PostMapping("/insight")
    @OperLog(title = "AI-看板解读", businessType = OperLog.BusinessType.QUERY)
    public R<Map<String, Object>> insight(@RequestBody Map<String, String> body) {
        // refresh=1：用户点了「重新生成」，清掉缓存再生成。
        // 用可选参数而不是新增接口，保持向后兼容（不传时行为与原来完全一致）。
        if ("1".equals(body.get("refresh"))) {
            dashboardInsightService.invalidate();
        }
        return R.ok(dashboardInsightService.insight(
                body.getOrDefault("scope", "dashboard"),
                body.getOrDefault("startDate", "2016-01-01"),
                body.getOrDefault("endDate", "2016-12-31"),
                body.get("siteId"),
                body.get("buildingId"),
                body.get("buildingType")));
    }

    @Operation(summary = "能耗异常智能诊断")
    @PostMapping("/diagnose")
    @OperLog(title = "AI-诊断", businessType = OperLog.BusinessType.QUERY)
    public R<Map<String, Object>> diagnose(@RequestBody DiagnoseRequest request) {
        return R.ok(aiServiceClient.diagnose(request));
    }

    @Operation(summary = "自动化报表生成")
    @PostMapping("/report")
    @OperLog(title = "AI-报表", businessType = OperLog.BusinessType.EXPORT)
    public R<Map<String, Object>> report(@RequestBody ReportRequest request) {
        return R.ok(aiServiceClient.generateReport(request));
    }

    @Operation(summary = "列出可用 MCP 工具（7 类）")
    @GetMapping("/tools")
    @OperLog(title = "AI-工具清单", businessType = OperLog.BusinessType.QUERY)
    public R<Map<String, Object>> tools() {
        return R.ok(aiServiceClient.listTools());
    }

    @Operation(summary = "AI 服务健康状态（看板指示灯）")
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        return R.ok(aiServiceClient.health());
    }

    @Operation(summary = "当前 AI 模式（mock/http）")
    @GetMapping("/mode")
    public R<String> mode() {
        return R.ok(aiServiceClient.mode());
    }
}

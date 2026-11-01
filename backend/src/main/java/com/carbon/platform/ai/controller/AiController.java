package com.carbon.platform.ai.controller;

import com.carbon.platform.ai.client.AiServiceClient;
import com.carbon.platform.ai.dto.ChatRequest;
import com.carbon.platform.ai.search.YuanbaoSearchService;
import com.carbon.platform.ai.dto.ChatResponse;
import com.carbon.platform.ai.dto.DiagnoseRequest;
import com.carbon.platform.ai.dto.ReportRequest;
import com.carbon.platform.common.annotation.OperLog;
import com.carbon.platform.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final YuanbaoSearchService yuanbaoSearchService;

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

    @Operation(summary = "联网搜索（元宝搜索，独立于内部能源查询）")
    @PostMapping("/search/web")
    @OperLog(title = "AI-联网搜索", businessType = OperLog.BusinessType.QUERY)
    public R<Map<String, Object>> webSearch(@RequestBody Map<String, Object> body) {
        String query = body == null ? null : String.valueOf(body.get("query"));
        int limit = body == null || body.get("limit") == null ? 5
                : Integer.parseInt(String.valueOf(body.get("limit")));
        Map<String, Object> result = yuanbaoSearchService.search(query, limit);
        if (result == null) {
            return R.fail(500, "联网搜索不可用：未配置元宝搜索凭证（YUANBAO_SECRET_ID/KEY 或 YUANBAO_SEARCH_API_KEY）或调用失败");
        }
        return R.ok(result);
    }

    /** 流式问答线程池：SSE 必须异步写，不能占用 Tomcat 工作线程 */
    private static final java.util.concurrent.ExecutorService STREAM_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("ai-stream");
                return t;
            });

    /**
     * 流式问答（SSE）。
     *
     * <p>事件类型：
     * <ul>
     *   <li>{@code delta} —— 正文增量 {text}</li>
     *   <li>{@code tool}  —— 某次工具执行完成（ChatResponse.ToolCall）</li>
     *   <li>{@code done}  —— 完整 ChatResponse（含 toolCalls / dataEvidence / tokens）</li>
     *   <li>{@code error} —— {message}</li>
     * </ul>
     */
    @Operation(summary = "流式自然语言问答（SSE）")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @OperLog(title = "AI-问答(流式)", businessType = OperLog.BusinessType.QUERY)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(180_000L);
        STREAM_POOL.execute(() -> {
            try {
                ChatResponse resp = aiServiceClient.chatStream(
                        request,
                        delta -> sendEvent(emitter, "delta", java.util.Map.of("text", delta)),
                        tool -> sendEvent(emitter, "tool", tool));
                sendEvent(emitter, "done", resp);
            } catch (Exception e) {
                sendEvent(emitter, "error",
                        java.util.Map.of("message", String.valueOf(e.getMessage())));
            } finally {
                // 收尾：先发一个注释行再 complete。
                // 实测只调 complete() 时，curl 会报
                // "transfer closed with outstanding read data remaining"（(18)）——
                // 连接在没有干净结束块的情况下被关闭。补一个注释事件后连接可正常收尾。
                try {
                    emitter.send(SseEmitter.event().comment("stream-end"));
                } catch (Exception ignore) {
                    // 客户端已断开属正常
                }
                try {
                    emitter.complete();
                } catch (Exception ignore) {
                    // 重复 complete 或已超时，忽略
                }
            }
        });
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception ignore) {
            // 连接已断开，忽略
        }
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

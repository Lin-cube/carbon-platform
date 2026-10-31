package com.carbon.platform.ai.client;

import com.carbon.platform.ai.dto.ChatRequest;
import com.carbon.platform.ai.dto.ChatResponse;
import com.carbon.platform.ai.dto.DiagnoseRequest;
import com.carbon.platform.ai.dto.ReportRequest;

import java.util.Map;

/**
 * AI 服务客户端抽象。
 *
 * <p>对应 {@code docs/接口契约与分工.md} §6「Mock 优先原则」：
 * <ul>
 *   <li>{@link MockAiServiceClient} —— 不依赖外部服务，供全栈侧先把链路跑通；</li>
 *   <li>{@link HttpAiServiceClient} —— 真实调用角色 B（AI/算法）的 FastAPI 服务。</li>
 * </ul>
 * 通过配置 {@code carbon.ai.mode=mock|http} 切换，切换成本为零。
 */
public interface AiServiceClient {

    /** 自然语言问答 */
    ChatResponse chat(ChatRequest request);

    /** 能耗异常诊断 */
    Map<String, Object> diagnose(DiagnoseRequest request);

    /** 自动化报表生成 */
    Map<String, Object> generateReport(ReportRequest request);

    /** 列出 AI 侧可用的 MCP 工具 */
    Map<String, Object> listTools();

    /**
     * 流式问答：正文逐段回调 {@code onDelta}，工具执行完回调 {@code onTool}。
     *
     * <p>默认实现退化为「一次性拿到完整答案后回调一次」，
     * 保证非流式实现（mock / http）也能被流式接口复用。
     */
    default ChatResponse chatStream(ChatRequest request,
                                    java.util.function.Consumer<String> onDelta,
                                    java.util.function.Consumer<ChatResponse.ToolCall> onTool) {
        ChatResponse r = chat(request);
        if (r != null && r.getAnswer() != null && onDelta != null) {
            onDelta.accept(r.getAnswer());
        }
        return r;
    }

    /** 健康检查：返回服务状态，供看板"AI 服务"指示灯使用 */
    Map<String, Object> health();

    /** 当前实现模式：mock / http */
    String mode();
}

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

    /** 健康检查：返回服务状态，供看板"AI 服务"指示灯使用 */
    Map<String, Object> health();

    /** 当前实现模式：mock / http */
    String mode();
}

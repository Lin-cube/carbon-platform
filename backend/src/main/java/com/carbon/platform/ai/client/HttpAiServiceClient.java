package com.carbon.platform.ai.client;

import com.carbon.platform.ai.dto.ChatRequest;
import com.carbon.platform.ai.dto.ChatResponse;
import com.carbon.platform.ai.dto.DiagnoseRequest;
import com.carbon.platform.ai.dto.ReportRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP AI 客户端：调用角色 B（AI/算法）的 FastAPI 服务。
 *
 * <p>端点契约见 {@code docs/接口契约与分工.md} §4。
 * 启用方式：{@code carbon.ai.mode=http} 并配置 {@code carbon.ai.base-url}。
 *
 * <p>容错设计：AI 服务不可用时<b>不抛异常给前端</b>，而是返回结构化降级响应，
 * 保证"演示过程中无崩溃"（申报表系统稳定性要求）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "carbon.ai.mode", havingValue = "http")
public class HttpAiServiceClient implements AiServiceClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpAiServiceClient(
            @Value("${carbon.ai.base-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${carbon.ai.connect-timeout-ms:3000}") int connectTimeout,
            @Value("${carbon.ai.read-timeout-ms:60000}") int readTimeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofMillis(connectTimeout));
        f.setReadTimeout(Duration.ofMillis(readTimeout));
        this.restTemplate = new RestTemplate(f);
    }

    @Override
    public String mode() {
        return "http";
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        long t0 = System.currentTimeMillis();
        try {
            ResponseEntity<ChatResponse> resp = restTemplate.postForEntity(
                    baseUrl + "/ai/chat", jsonEntity(request), ChatResponse.class);
            ChatResponse body = resp.getBody();
            if (body == null) {
                return degraded("AI 服务返回空响应", t0);
            }
            if (body.getElapsedMs() == null) {
                body.setElapsedMs(System.currentTimeMillis() - t0);
            }
            return body;
        } catch (RestClientException e) {
            log.error("调用 AI 服务失败: {}", e.getMessage());
            return degraded("AI 服务暂不可用：" + e.getMessage(), t0);
        }
    }

    @Override
    public Map<String, Object> diagnose(DiagnoseRequest request) {
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    baseUrl + "/ai/diagnose", jsonEntity(request), Map.class);
            return resp.getBody() == null ? Map.of("ok", false) : resp.getBody();
        } catch (RestClientException e) {
            log.error("调用 AI 诊断失败: {}", e.getMessage());
            return Map.of("ok", false, "mode", "http",
                    "error", "AI 服务暂不可用：" + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> generateReport(ReportRequest request) {
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    baseUrl + "/ai/report", jsonEntity(request), Map.class);
            return resp.getBody() == null ? Map.of("ok", false) : resp.getBody();
        } catch (RestClientException e) {
            log.error("调用 AI 报表生成失败: {}", e.getMessage());
            return Map.of("ok", false, "mode", "http",
                    "error", "AI 服务暂不可用：" + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> listTools() {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(baseUrl + "/mcp/tools", Map.class);
            return resp.getBody() == null ? Map.of("ok", false) : resp.getBody();
        } catch (RestClientException e) {
            return Map.of("ok", false, "mode", "http", "count", 0,
                    "tools", java.util.List.of(),
                    "error", "AI 服务暂不可用：" + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> health() {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(baseUrl + "/health", Map.class);
            Map<String, Object> body = resp.getBody() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(resp.getBody());
            body.put("mode", "http");
            body.put("baseUrl", baseUrl);
            body.putIfAbsent("status", "UP");
            return body;
        } catch (RestClientException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "DOWN");
            m.put("mode", "http");
            m.put("baseUrl", baseUrl);
            m.put("message", "AI 服务不可达：" + e.getMessage());
            return m;
        }
    }

    private HttpEntity<Object> jsonEntity(Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(body, h);
    }

    private ChatResponse degraded(String msg, long t0) {
        ChatResponse r = new ChatResponse();
        r.setOk(false);
        r.setError(msg);
        r.setAnswer("抱歉，AI 服务当前不可用，请稍后重试。"
                + "您仍可使用能耗查询、统计分析与报表导出等基础功能。");
        r.setElapsedMs(System.currentTimeMillis() - t0);
        r.setConfidence(0.0);
        r.setDataSource("无");
        return r;
    }
}

package com.carbon.platform.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * OpenAI 兼容大模型客户端（当前用于 DeepSeek）。
 *
 * <p>DeepSeek、魔搭云 ModelScope、阿里云 DashScope 三家的对话接口
 * 都是 OpenAI 兼容格式（{@code POST /chat/completions} + {@code Authorization: Bearer}），
 * 仅 base-url 与模型名不同。因此共用一个实现，切换只改配置，无需改代码。
 *
 * <table border="1">
 *   <tr><th>provider</th><th>base-url</th><th>model</th></tr>
 *   <tr><td>deepseek（当前）</td><td>https://api.deepseek.com</td><td>deepseek-chat</td></tr>
 *   <tr><td>modelscope</td><td>https://api-inference.modelscope.cn/v1</td><td>Qwen/Qwen2.5-72B-Instruct</td></tr>
 *   <tr><td>dashscope</td><td>https://dashscope.aliyuncs.com/compatible-mode/v1</td><td>qwen-plus</td></tr>
 * </table>
 *
 * <p>实测 DeepSeek 端点在本机连通（握手约 0.6s）。若网络不可达或未配置密钥，
 * {@link #available()} 返回 false，上层自动降级到规则映射 / 纯检索回答。
 *
 * <p>关于 Qwen3：该系列默认开启思考模式，会返回 {@code reasoning_content} 字段，
 * 导致响应变慢且 JSON 解析更易失败，本实现对 Qwen3 显式关闭思考模式。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "carbon.llm.provider",
        havingValue = "deepseek")
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String providerName;
    /** 输出长度上限：LLM 延迟与生成长度近似线性，字段映射等结构化任务须限制长度 */
    private final int maxTokens;

    public OpenAiCompatibleLlmClient(
            @Value("${carbon.llm.api-key:}") String apiKey,
            @Value("${carbon.llm.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${carbon.llm.model:deepseek-chat}") String model,
            @Value("${carbon.llm.provider:deepseek}") String providerName,
            @Value("${carbon.llm.connect-timeout-ms:5000}") int connectTimeout,
            @Value("${carbon.llm.max-tokens:2000}") int maxTokens,
            @Value("${carbon.llm.read-timeout-ms:120000}") int readTimeout) {
        this.maxTokens = maxTokens;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.providerName = providerName;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofMillis(connectTimeout));
        f.setReadTimeout(Duration.ofMillis(readTimeout));
        this.restTemplate = new RestTemplate(f);
        if (!StringUtils.hasText(apiKey)) {
            log.warn("未配置大模型 API Key（carbon.llm.api-key），"
                    + "AI 字段映射将自动降级为规则引擎");
        }
    }

    @Override
    public boolean available() {
        return StringUtils.hasText(apiKey);
    }

    @Override
    public String provider() {
        return providerName;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, double temperature) {
        return chat(systemPrompt, userPrompt, temperature, maxTokens);
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, double temperature, int limit) {
        if (!available()) {
            return null;
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", temperature);
            body.put("stream", false);
            body.put("max_tokens", Math.max(limit, 64));
            // Qwen3 系列需要显式关闭思考模式，否则响应含 reasoning_content 且变慢
            if (model.toLowerCase().contains("qwen3")) {
                body.putObject("chat_template_kwargs").put("enable_thinking", false);
            }
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            long t0 = System.currentTimeMillis();
            ResponseEntity<String> resp = restTemplate.exchange(
                    baseUrl + "/chat/completions", HttpMethod.POST,
                    new HttpEntity<>(mapper.writeValueAsString(body), headers), String.class);
            long cost = System.currentTimeMillis() - t0;

            JsonNode root = mapper.readTree(resp.getBody());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("大模型返回无 choices：{}", abbreviate(resp.getBody()));
                return null;
            }
            String content = choices.get(0).path("message").path("content").asText(null);
            log.info("大模型调用成功：provider={} model={} 耗时={}ms 回复长度={}",
                    providerName, model, cost, content == null ? 0 : content.length());
            return content;
        } catch (RestClientException e) {
            log.error("大模型调用失败（将降级为规则映射）：{}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("大模型响应解析失败（将降级为规则映射）：{}", e.getMessage());
            return null;
        }
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}

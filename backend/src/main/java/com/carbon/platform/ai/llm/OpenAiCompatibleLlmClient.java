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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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

    // ================================================================ 工具调用

    /**
     * 带工具的对话（DeepSeek function calling）。
     *
     * <p>与 {@link #chat} 的区别：
     * <ul>
     *   <li>请求体携带 {@code tools} + {@code tool_choice:"auto"}；</li>
     *   <li>解析 {@code message.tool_calls} 与 {@code usage}（Token 用量）；</li>
     *   <li>返回值是 {@link ToolChatResult}，而非纯文本。</li>
     * </ul>
     */
    @Override
    public ToolChatResult chatWithTools(java.util.List<java.util.Map<String, Object>> messages,
                                        java.util.List<java.util.Map<String, Object>> tools,
                                        int maxTokens) {
        ToolChatResult result = new ToolChatResult();
        if (!available()) {
            return null;
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0.0);      // 工具选择与参数生成用低温，减少幻觉
            body.put("stream", false);
            body.put("max_tokens", Math.max(maxTokens, 64));
            body.put("tool_choice", "auto");

            ArrayNode msgs = body.putArray("messages");
            for (java.util.Map<String, Object> m : messages) {
                msgs.add(mapper.valueToTree(m));
            }
            ArrayNode toolsArr = body.putArray("tools");
            for (java.util.Map<String, Object> t : tools) {
                toolsArr.add(mapper.valueToTree(t));
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            long t0 = System.currentTimeMillis();
            ResponseEntity<String> resp = restTemplate.exchange(
                    baseUrl + "/chat/completions", HttpMethod.POST,
                    new HttpEntity<>(mapper.writeValueAsString(body), headers), String.class);
            result.setElapsedMs(System.currentTimeMillis() - t0);

            JsonNode root = mapper.readTree(resp.getBody());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("大模型返回无 choices：{}", abbreviate(resp.getBody()));
                return null;
            }
            JsonNode message = choices.get(0).path("message");
            result.setContent(message.path("content").asText(null));

            JsonNode tcs = message.path("tool_calls");
            if (tcs.isArray()) {
                for (JsonNode tc : tcs) {
                    ToolChatResult.ToolCall call = new ToolChatResult.ToolCall();
                    call.setId(tc.path("id").asText(null));
                    call.setName(tc.path("function").path("name").asText(null));
                    call.setArguments(tc.path("function").path("arguments").asText(null));
                    result.getToolCalls().add(call);
                }
            }

            JsonNode usage = root.path("usage");
            result.setPromptTokens(usage.path("prompt_tokens").asInt());
            result.setCompletionTokens(usage.path("completion_tokens").asInt());
            result.setTotalTokens(usage.path("total_tokens").asInt());
            return result;
        } catch (Exception e) {
            log.error("大模型工具调用失败（将降级）：{}", e.getMessage());
            return null;
        }
    }

    // ================================================================ 流式

    /**
     * 流式对话：解析 OpenAI 兼容的 SSE 响应，正文逐段回调。
     *
     * <p>SSE 每行形如 {@code data: {"choices":[{"delta":{"content":"你"}}]}}，
     * 结束时为 {@code data: [DONE]}。工具调用通过 {@code delta.tool_calls}
     * 分片下发，按 {@code index} 累积 id / name / arguments。
     */
    @Override
    public ToolChatResult chatStream(List<Map<String, Object>> messages,
                                     List<Map<String, Object>> tools,
                                     int maxTokens,
                                     Consumer<String> onDelta) {
        if (!available()) {
            return null;
        }
        ToolChatResult result = new ToolChatResult();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0.0);
            body.put("stream", true);
            body.put("max_tokens", Math.max(maxTokens, 64));
            body.put("tool_choice", "auto");
            body.putObject("stream_options").put("include_usage", true);
            ArrayNode msgs = body.putArray("messages");
            for (Map<String, Object> m : messages) {
                msgs.add(mapper.valueToTree(m));
            }
            if (tools != null && !tools.isEmpty()) {
                ArrayNode ta = body.putArray("tools");
                for (Map<String, Object> t : tools) {
                    ta.add(mapper.valueToTree(t));
                }
            }

            StringBuilder content = new StringBuilder();
            Map<Integer, ToolChatResult.ToolCall> calls = new LinkedHashMap<>();
            long t0 = System.currentTimeMillis();

            restTemplate.execute(baseUrl + "/chat/completions", HttpMethod.POST,
                    req -> {
                        req.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        req.getHeaders().setBearerAuth(apiKey);
                        req.getHeaders().set("Accept", "text/event-stream");
                        req.getBody().write(mapper.writeValueAsBytes(body));
                    },
                    resp -> {
                        try (BufferedReader br = new BufferedReader(
                                new InputStreamReader(resp.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                if (!line.startsWith("data:")) {
                                    continue;
                                }
                                String data = line.substring(5).trim();
                                if ("[DONE]".equals(data)) {
                                    break;
                                }
                                JsonNode chunk = mapper.readTree(data);

                                JsonNode usage = chunk.path("usage");
                                if (usage.isObject()) {
                                    result.setPromptTokens(usage.path("prompt_tokens").asInt());
                                    result.setCompletionTokens(usage.path("completion_tokens").asInt());
                                    result.setTotalTokens(usage.path("total_tokens").asInt());
                                }

                                JsonNode choices = chunk.path("choices");
                                if (!choices.isArray() || choices.isEmpty()) {
                                    continue;
                                }
                                JsonNode delta = choices.get(0).path("delta");
                                String piece = delta.path("content").asText(null);
                                if (piece != null && !piece.isEmpty()) {
                                    content.append(piece);
                                    if (onDelta != null) {
                                        onDelta.accept(piece);
                                    }
                                }
                                JsonNode tcs = delta.path("tool_calls");
                                if (tcs.isArray()) {
                                    for (JsonNode tc : tcs) {
                                        int i = tc.path("index").asInt(0);
                                        ToolChatResult.ToolCall call =
                                                calls.computeIfAbsent(i, k -> new ToolChatResult.ToolCall());
                                        String id = tc.path("id").asText(null);
                                        if (id != null && !id.isEmpty()) {
                                            call.setId(id);
                                        }
                                        String name = tc.path("function").path("name").asText(null);
                                        if (name != null && !name.isEmpty()) {
                                            call.setName(name);
                                        }
                                        String args = tc.path("function").path("arguments").asText(null);
                                        if (args != null) {
                                            call.setArguments((call.getArguments() == null ? "" : call.getArguments()) + args);
                                        }
                                    }
                                }
                            }
                        }
                        return null;
                    });

            result.setContent(content.length() == 0 ? null : content.toString());
            result.getToolCalls().addAll(calls.values());
            result.setElapsedMs(System.currentTimeMillis() - t0);
            return result;
        } catch (Exception e) {
            log.error("大模型流式调用失败（将降级）：{}", e.getMessage());
            return null;
        }
    }
}

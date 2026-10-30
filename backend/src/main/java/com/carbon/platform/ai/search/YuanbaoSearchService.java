package com.carbon.platform.ai.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 腾讯元宝搜索（联网搜索）适配器。
 *
 * <p><b>鉴权方式判定</b>：密钥以 {@code sk-} 前缀，属于元宝搜索的
 * 「<b>服务 API KEY</b>」方式（Bearer 密钥），<b>不是</b>腾讯云
 * SecretId/SecretKey（后者为 {@code AKID...} 格式）。参考：
 * <a href="https://cloud.tencent.cn/document/product/1806/130615">服务 API KEY 方式</a>、
 * <a href="https://cloud.tencent.cn/document/product/1806/121802">腾讯云标准方式</a>。
 *
 * <p><b>独立性</b>：联网搜索与内部能源查询完全独立。本服务只调用外部搜索接口，
 * 不访问 MySQL，不调用内部查询工具；反之内部工具也不走联网搜索。
 *
 * <p><b>密钥安全</b>：密钥从环境变量 {@code YUANBAO_SEARCH_API_KEY} 读取，
 * 不硬编码、不交给前端。端点与模型名均可配置，便于按官方文档调整。
 *
 * <p><b>注意</b>：腾讯云官方文档为 JS 渲染、无法稳定抓取，具体请求路径可能需按
 * 官方控制台/文档核对；本适配器默认端点与 Header 均已做成可配置项。
 */
@Slf4j
@Service
public class YuanbaoSearchService {

    private final String apiKey;
    private final String baseUrl;
    private final String endpoint;
    private final String model;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public YuanbaoSearchService(
            @Value("${carbon.search.yuanbao.api-key:${YUANBAO_SEARCH_API_KEY:}}") String apiKey,
            @Value("${carbon.search.yuanbao.base-url:${YUANBAO_SEARCH_BASE_URL:https://api.yuanbao.tencent.com}}") String baseUrl,
            @Value("${carbon.search.yuanbao.endpoint:${YUANBAO_SEARCH_ENDPOINT:/v1/search}}") String endpoint,
            @Value("${carbon.search.yuanbao.model:${YUANBAO_SEARCH_MODEL:yuanbao-search}}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        this.model = model;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofMillis(5000));
        f.setReadTimeout(Duration.ofMillis(30000));
        this.restTemplate = new RestTemplate(f);
        if (!StringUtils.hasText(apiKey)) {
            log.info("未配置元宝搜索 API Key（YUANBAO_SEARCH_API_KEY），联网搜索不可用");
        }
    }

    public boolean available() {
        return StringUtils.hasText(apiKey);
    }

    /**
     * 联网搜索。
     *
     * @param query 检索词
     * @param limit 结果条数（1~20）
     * @return {query, count, results:[{title,url,snippet}]}；不可用/失败返回 null
     */
    public Map<String, Object> search(String query, int limit) {
        if (!available() || !StringUtils.hasText(query)) {
            return null;
        }
        int n = Math.max(1, Math.min(limit, 20));
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("query", query);
            body.put("limit", n);
            ArrayNode msgs = body.putArray("messages");
            msgs.addObject().put("role", "user").put("content", query);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            ResponseEntity<String> resp = restTemplate.exchange(
                    baseUrl + endpoint, HttpMethod.POST,
                    new HttpEntity<>(mapper.writeValueAsString(body), headers), String.class);

            JsonNode root = mapper.readTree(resp.getBody());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("query", query);
            List<Map<String, Object>> results = extractResults(root);
            out.put("count", results.size());
            out.put("results", results);
            return out;
        } catch (Exception e) {
            log.warn("元宝搜索失败（不影响内部能源查询）：{}", e.getMessage());
            return null;
        }
    }

    /** 从响应里提取结果列表（尽量兼容多种字段命名） */
    private List<Map<String, Object>> extractResults(JsonNode root) {
        List<Map<String, Object>> results = new ArrayList<>();
        JsonNode items = root.path("results").isArray() ? root.path("results")
                : root.path("data").isArray() ? root.path("data")
                : root.path("choices").path(0).path("message").path("search_results");
        if (!items.isArray()) {
            return results;
        }
        for (JsonNode it : items) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("title", it.path("title").asText(it.path("name").asText("")));
            r.put("url", it.path("url").asText(it.path("link").asText("")));
            r.put("snippet", it.path("snippet").asText(it.path("content").asText("")));
            results.add(r);
        }
        return results;
    }
}

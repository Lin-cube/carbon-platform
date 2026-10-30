package com.carbon.platform.ai.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 腾讯元宝搜索（联网搜索）适配器。
 *
 * <p><b>两种接入方式</b>（同一 SearchPro 接口）：
 * <ol>
 *   <li><b>腾讯云标准方式（SecretId/SecretKey）</b>：POST {@code https://wsa.tencentcloudapi.com}，
 *       用 TC3-HMAC-SHA256 签名（本类实现），Header 带 X-TC-Action=SearchPro；</li>
 *   <li><b>服务 API KEY 方式</b>：POST {@code https://api.wsa.cloud.tencent.com/SearchPro}，
 *       {@code Authorization: Bearer <sk-key>}，无需签名。</li>
 * </ol>
 * 两者二选一：配置了 SecretId/SecretKey 走标准方式，否则用服务 API KEY。
 *
 * <p><b>请求</b>：{@code {"Query":"...","Limit":N,...}}（Mode/Site/freshness 等按官方参数透传）。
 * <p><b>响应</b>：{@code {"Response":{"Pages":[{title,date,url,site,score,passage}]}}}，
 * 每条即一条可点击的来源，前端渲染为链接。
 *
 * <p><b>独立性</b>：联网搜索与内部能源查询完全独立，本服务不访问 MySQL。
 *
 * <p><b>密钥安全</b>：SecretId/SecretKey/服务 KEY 均从环境变量读取，不硬编码、不交前端。
 */
@Slf4j
@Service
public class YuanbaoSearchService {

    private final String secretId;
    private final String secretKey;
    private final String bearerKey;
    /** 标准方式域名 wsa.tencentcloudapi.com；服务 KEY 域名 api.wsa.cloud.tencent.com */
    private final String stdHost;
    private final String bearerBaseUrl;
    private final String action;
    private final String version;
    private final String region;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public YuanbaoSearchService(
            @Value("${carbon.search.yuanbao.secret-id:${YUANBAO_SECRET_ID:}}") String secretId,
            @Value("${carbon.search.yuanbao.secret-key:${YUANBAO_SECRET_KEY:}}") String secretKey,
            @Value("${carbon.search.yuanbao.api-key:${YUANBAO_SEARCH_API_KEY:}}") String bearerKey,
            @Value("${carbon.search.yuanbao.std-host:${YUANBAO_SEARCH_STD_HOST:wsa.tencentcloudapi.com}}") String stdHost,
            @Value("${carbon.search.yuanbao.base-url:${YUANBAO_SEARCH_BASE_URL:https://api.wsa.cloud.tencent.com}}") String bearerBaseUrl,
            @Value("${carbon.search.yuanbao.action:${YUANBAO_SEARCH_ACTION:SearchPro}}") String action,
            @Value("${carbon.search.yuanbao.version:${YUANBAO_SEARCH_VERSION:2024-10-15}}") String version,
            @Value("${carbon.search.yuanbao.region:${YUANBAO_SEARCH_REGION:ap-guangzhou}}") String region) {
        this.secretId = secretId;
        this.secretKey = secretKey;
        this.bearerKey = bearerKey;
        this.stdHost = stdHost;
        this.bearerBaseUrl = bearerBaseUrl.endsWith("/")
                ? bearerBaseUrl.substring(0, bearerBaseUrl.length() - 1) : bearerBaseUrl;
        this.action = action;
        this.version = version;
        this.region = region;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofMillis(5000));
        f.setReadTimeout(Duration.ofMillis(30000));
        this.restTemplate = new RestTemplate(f);
        if (!available()) {
            log.info("未配置元宝搜索凭证（YUANBAO_SECRET_ID/KEY 或 YUANBAO_SEARCH_API_KEY），联网搜索不可用");
        }
    }

    /** 是否可用（配了标准凭证或服务 KEY） */
    public boolean available() {
        return (StringUtils.hasText(secretId) && StringUtils.hasText(secretKey))
                || StringUtils.hasText(bearerKey);
    }

    /** 当前鉴权方式（standard=腾讯云标准 / bearer=服务 API KEY / none） */
    public String authMode() {
        if (StringUtils.hasText(secretId) && StringUtils.hasText(secretKey)) {
            return "standard";
        }
        if (StringUtils.hasText(bearerKey)) {
            return "bearer";
        }
        return "none";
    }

    /**
     * 联网搜索。
     *
     * @return {query, count, results:[{title,url,snippet,date,site}]}；不可用/失败返回 null
     */
    public Map<String, Object> search(String query, int limit) {
        if (!available() || !StringUtils.hasText(query)) {
            return null;
        }
        int n = Math.max(1, Math.min(limit, 20));
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("Query", query);
            body.put("Limit", n);

            String respBody;
            if ("standard".equals(authMode())) {
                respBody = callStandard(body.toString());
            } else {
                respBody = callBearer(body.toString());
            }

            JsonNode root = mapper.readTree(respBody);
            List<Map<String, Object>> results = extractPages(root);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("query", query);
            out.put("count", results.size());
            out.put("results", results);
            return out;
        } catch (Exception e) {
            log.warn("元宝搜索失败（不影响内部能源查询）：{}", e.getMessage());
            return null;
        }
    }

    // ================================================================ 标准方式（TC3 签名）

    private String callStandard(String payload) {
        long timestamp = Instant.now().getEpochSecond();
        String host = stdHost;
        String service = host.substring(0, host.indexOf('.'));
        String contentType = "application/json; charset=utf-8";

        try {
            // ① CanonicalRequest
            String canonicalUri = "/";
            String canonicalQuery = "";
            String canonicalHeaders = "content-type:" + contentType + "\nhost:" + host + "\n";
            String signedHeaders = "content-type;host";
            String hashedPayload = sha256Hex(payload);
            String canonicalRequest = "POST\n" + canonicalUri + "\n" + canonicalQuery + "\n"
                    + canonicalHeaders + "\n" + signedHeaders + "\n" + hashedPayload;

            // ② StringToSign
            String date = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                    .withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(timestamp));
            String credentialScope = date + "/" + service + "/tc3_request";
            String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + credentialScope
                    + "\n" + sha256Hex(canonicalRequest);

            // ③ Signature
            byte[] secretDate = hmacSHA256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
            byte[] secretService = hmacSHA256(secretDate, service);
            byte[] secretSigning = hmacSHA256(secretService, "tc3_request");
            String signature = hex(hmacSHA256(secretSigning, stringToSign));

            String authorization = "TC3-HMAC-SHA256 Credential=" + secretId + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", authorization);
            headers.set("Host", host);
            headers.set("X-TC-Action", action);
            headers.set("X-TC-Version", version);
            headers.set("X-TC-Timestamp", String.valueOf(timestamp));
            headers.set("X-TC-Region", region);

            ResponseEntity<String> resp = restTemplate.exchange(
                    "https://" + host + "/", HttpMethod.POST,
                    new HttpEntity<>(payload, headers), String.class);
            return resp.getBody();
        } catch (Exception e) {
            throw new IllegalStateException("元宝搜索标准方式调用失败：" + e.getMessage(), e);
        }
    }

    private String callBearer(String payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearerKey);
        ResponseEntity<String> resp = restTemplate.exchange(
                bearerBaseUrl + "/" + action, HttpMethod.POST,
                new HttpEntity<>(payload, headers), String.class);
        return resp.getBody();
    }

    // ================================================================ 结果提取

    /** 从响应提取结果：兼容 Response.Pages / Response.SearchResults / Response.Results */
    private List<Map<String, Object>> extractPages(JsonNode root) {
        List<Map<String, Object>> results = new ArrayList<>();
        JsonNode resp = root.path("Response");
        JsonNode pages = resp.path("Pages").isArray() ? resp.path("Pages")
                : resp.path("SearchResults").isArray() ? resp.path("SearchResults")
                : resp.path("Results").isArray() ? resp.path("Results")
                : root.path("Pages").isArray() ? root.path("Pages")
                : root.path("results").isArray() ? root.path("results") : null;
        if (pages == null || !pages.isArray()) {
            return results;
        }
        for (JsonNode p : pages) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("title", p.path("title").asText(p.path("Title").asText("")));
            r.put("url", p.path("url").asText(p.path("Url").asText(p.path("link").asText(""))));
            r.put("snippet", p.path("passage").asText(p.path("content").asText(p.path("snippet").asText(""))));
            r.put("date", p.path("date").asText(p.path("Date").asText("")));
            r.put("site", p.path("site").asText(p.path("Site").asText("")));
            if (p.has("score") || p.has("Score")) {
                r.put("score", p.path("score").asText(p.path("Score").asText("")));
            }
            results.add(r);
        }
        return results;
    }

    // ================================================================ 签名工具

    private String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return hex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] hmacSHA256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

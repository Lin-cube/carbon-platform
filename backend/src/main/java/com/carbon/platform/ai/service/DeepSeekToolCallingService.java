package com.carbon.platform.ai.service;

import com.carbon.platform.ai.dto.ChatResponse;
import com.carbon.platform.ai.llm.LlmClient;
import com.carbon.platform.ai.llm.ToolChatResult;
import com.carbon.platform.ai.tool.AiQueryToolService;
import com.carbon.platform.ai.tool.AiToolSchema;
import com.carbon.platform.ai.search.YuanbaoSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * DeepSeek 工具调用编排器。
 *
 * <h3>职责</h3>
 * 让 DeepSeek 根据用户问题自动选择并调用内部能源查询工具，再用自然语言解释结果。
 *
 * <h3>流程（最多 3 轮）</h3>
 * <ol>
 *   <li>把「系统提示 + 裁剪后的历史 + 用户问题 + 上下文」发给 DeepSeek（携带 8 个工具定义）；</li>
 *   <li>若模型返回 tool_calls：逐个经 {@link AiQueryToolService} 执行（参数已校验），
 *       把结果作为 tool 消息回填，继续下一轮；</li>
 *   <li>若模型直接返回文本：作为最终回答。</li>
 * </ol>
 *
 * <h3>Token 优化</h3>
 * <ul>
 *   <li>系统提示词只讲角色与约束，不含任何数据库/建筑/时序数据；</li>
 *   <li>历史消息裁剪到最近 6 条；</li>
 *   <li>工具只回统计摘要（见 AiQueryToolService），小时级明细不回模型。</li>
 * </ul>
 *
 * <h3>安全</h3>
 * 模型只能调用白名单工具，无法访问 MySQL 或执行任意 SQL；工具参数在 AiQueryToolService 内二次校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepSeekToolCallingService {

    private static final int MAX_ROUNDS = 3;
    /** 单轮最多执行多少个工具调用（防止模型逐园区循环导致超时/失败） */
    private static final int MAX_TOOL_CALLS_PER_ROUND = 10;
    private static final int MAX_HISTORY_MESSAGES = 6;
    private static final String SYSTEM_PROMPT = """
            你是「碳能智控运维平台」的 AI 助手小E。用中文简洁回答建筑能源问题。
            当用户询问实际用电量、COP、碳排放、异常事件、排名或趋势时，必须调用工具查询真实数据库，不得凭空编造任何数值。
            当用户询问运维建议、行业最佳实践、政策法规、标准规范、技术原理等通用知识时，必须调用 webSearch 联网搜索获取真实资料，
            并在回答中给出可点击的来源链接，不要凭空作答。
            只有问题缺少关键信息且无法合理推断时，才反问澄清；能合理推断的对象不要反复追问。
            回答要包含园区/建筑名称、统计时间、单位和数据来源。工具无结果时明确说明「无数据」，不得编造。
            禁止输出未经数据验证的节能百分比。回答控制在 300 字以内。""";

    private final LlmClient llmClient;
    private final AiQueryToolService toolService;
    private final AiToolSchema toolSchema;
    private final AiToolUsageRecorder usageRecorder;
    private final YuanbaoSearchService yuanbaoSearch;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 会话历史（sessionId → 最近消息），仅内存、按会话裁剪，不落库不增长无界 */
    private final Map<String, java.util.ArrayDeque<Map<String, String>>> sessionHistory =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_SESSIONS = 500;

    /** 工具调用是否可用（DeepSeek 已配置密钥） */
    public boolean available() {
        return llmClient != null && llmClient.available();
    }

    /**
     * 执行一轮完整的工具调用编排。
     *
     * @param question 用户当前问题
     * @param history  历史对话（[{role, content}]，旧在前），内部会裁剪
     * @param siteId / buildingId / startDate / endDate 上下文（可为空）
     * @return ChatResponse；DeepSeek 不可用或失败返回 null，由上层降级
     */
    public ChatResponse answer(String sessionId, String question,
                               String siteId, String buildingId,
                               String startDate, String endDate) {
        return run(sessionId, question, siteId, buildingId, startDate, endDate, false, null, null);
    }

    /**
     * 流式版：正文逐段回调 {@code onDelta}，每次工具执行完回调 {@code onTool}。
     *
     * <p>与 {@link #answer} 共用同一套编排逻辑，仅模型调用换成 SSE 流式，
     * 前端可边生成边显示，首字延迟从 2~3 秒降到数百毫秒。
     */
    public ChatResponse answerStream(String sessionId, String question,
                                     String siteId, String buildingId,
                                     String startDate, String endDate,
                                     java.util.function.Consumer<String> onDelta,
                                     java.util.function.Consumer<ChatResponse.ToolCall> onTool) {
        return run(sessionId, question, siteId, buildingId, startDate, endDate, true, onDelta, onTool);
    }

    private ChatResponse run(String sessionId, String question,
                             String siteId, String buildingId,
                             String startDate, String endDate,
                             boolean stream,
                             java.util.function.Consumer<String> onDelta,
                             java.util.function.Consumer<ChatResponse.ToolCall> onTool) {
        long t0 = System.currentTimeMillis();
        if (!available()) {
            return null;
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", SYSTEM_PROMPT, null, null));

        // 裁剪历史：只保留最近 6 条（服务端内存，按会话）
        List<Map<String, String>> history = sessionHistory(sessionId);
        for (Map<String, String> h : history) {
            String role = h.getOrDefault("role", "user");
            String content = h.getOrDefault("content", "");
            if (StringUtils.hasText(content)) {
                messages.add(msg(role, content, null, null));
            }
        }

        // 当前问题 + 上下文
        StringBuilder q = new StringBuilder(question);
        if (StringUtils.hasText(siteId) || StringUtils.hasText(buildingId)
                || StringUtils.hasText(startDate) || StringUtils.hasText(endDate)) {
            q.append("\n\n可用上下文（若与问题相关）：");
            if (StringUtils.hasText(siteId)) q.append("\n- 园区：").append(siteId);
            if (StringUtils.hasText(buildingId)) q.append("\n- 建筑：").append(buildingId);
            if (StringUtils.hasText(startDate) || StringUtils.hasText(endDate)) {
                q.append("\n- 时间范围：").append(nz(startDate)).append(" ~ ").append(nz(endDate));
            }
        }
        messages.add(msg("user", q.toString(), null, null));

        List<ChatResponse.ToolCall> allCalls = new ArrayList<>();
        List<ChatResponse.Citation> webCitations = new ArrayList<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        int promptTokens = 0, completionTokens = 0, totalTokens = 0;
        String finalAnswer = null;

        List<Map<String, Object>> tools = toolSchema.toolDefinitions();

        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                ToolChatResult r = stream
                        ? llmClient.chatStream(messages, tools, 800, onDelta)
                        : llmClient.chatWithTools(messages, tools, 800);
                if (r == null) {
                    log.warn("DeepSeek 工具调用返回空，第 {} 轮", round + 1);
                    return null;
                }
                promptTokens += nz(r.getPromptTokens());
                completionTokens += nz(r.getCompletionTokens());
                totalTokens += nz(r.getTotalTokens());

                if (r.hasToolCalls()) {
                    // 回填 assistant 的 tool_calls 消息
                    messages.add(assistantToolCallsMessage(r));
                    // 执行工具并回填 tool 消息
                    int executed = 0;
                    for (ToolChatResult.ToolCall tc : r.getToolCalls()) {
                        if (++executed > MAX_TOOL_CALLS_PER_ROUND) {
                            log.warn("单轮工具调用超上限({})，忽略后续调用", MAX_TOOL_CALLS_PER_ROUND);
                            break;
                        }
                        ChatResponse.ToolCall call = new ChatResponse.ToolCall();
                        call.setTool(tc.getName());
                        call.setElapsedMs(0L);
                        long ts = System.currentTimeMillis();
                        String result;
                        Map<String, Object> args = Map.of();
                        try {
                            args = parseArgs(tc.getArguments());
                            call.setArgs(args);
                            Map<String, Object> out;
                            if ("webSearch".equals(tc.getName())) {
                                // 联网搜索：服务层独立，不经 AiQueryToolService（不碰数据库）
                                out = yuanbaoSearch.search(String.valueOf(args.getOrDefault("query", "")),
                                        intArg(args, "limit", 5));
                                collectWebCitations(out, webCitations);
                            } else {
                                out = toolService.call(tc.getName(), args);
                            }
                            result = mapper.writeValueAsString(out);
                            if (out != null && !out.isEmpty()) {
                                evidence.put(tc.getName(), out);
                            }
                            call.setSuccess(true);
                        } catch (Exception e) {
                            result = "{\"error\":\"" + escape(e.getMessage()) + "\"}";
                            call.setSuccess(false);
                        }
                        call.setElapsedMs(System.currentTimeMillis() - ts);
                        usageRecorder.record(sessionId, question, tc.getName(), args,
                                resultRows(evidence.get(tc.getName())), call.getElapsedMs(),
                                r.getPromptTokens(), r.getCompletionTokens(), r.getTotalTokens());
                        allCalls.add(call);
                        if (onTool != null) {
                            onTool.accept(call);
                        }
                        messages.add(msg("tool", result, tc.getId(), null));
                    }
                } else {
                    finalAnswer = r.getContent();
                    break;
                }
            }
        } catch (Exception e) {
            log.error("DeepSeek 工具调用编排失败", e);
            return null;
        }

        if (finalAnswer == null || finalAnswer.isBlank()) {
            // 多轮后仍未得到文本（罕见），给一个明确降级说明
            finalAnswer = "抱歉，我暂时无法根据工具查询结果组织回答，请换个问法或稍后重试。";
        }

        ChatResponse resp = new ChatResponse();
        resp.setOk(true);
        resp.setAnswer(finalAnswer);
        resp.setToolCalls(allCalls);
        resp.setDataEvidence(evidence);
        resp.setConfidence(evidence.isEmpty() ? 0.5 : 0.9);
        resp.setElapsedMs(System.currentTimeMillis() - t0);
        if (!webCitations.isEmpty()) {
            resp.setCitations(webCitations);
        }
        resp.setDataSource(evidence.isEmpty() ? "无工具调用" : "数据库工具（预聚合统计）");
        resp.setPromptTokens(promptTokens);
        resp.setCompletionTokens(completionTokens);
        resp.setTotalTokens(totalTokens);

        // 记录本轮问答到会话历史（用于多轮上下文裁剪）
        appendHistory(sessionId, "user", question);
        appendHistory(sessionId, "assistant", finalAnswer);
        return resp;
    }

    /** 取会话历史（去旧保新，最多 6 条），并做会话数上限清理 */
    private List<Map<String, String>> sessionHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        if (sessionHistory.size() > MAX_SESSIONS) {
            sessionHistory.clear();
        }
        var dq = sessionHistory.computeIfAbsent(sessionId, k -> new java.util.ArrayDeque<>());
        return new ArrayList<>(dq);
    }

    private void appendHistory(String sessionId, String role, String content) {
        if (sessionId == null || sessionId.isBlank() || !StringUtils.hasText(content)) {
            return;
        }
        var dq = sessionHistory.computeIfAbsent(sessionId, k -> new java.util.ArrayDeque<>());
        dq.addLast(Map.of("role", role, "content", content));
        while (dq.size() > MAX_HISTORY_MESSAGES) {
            dq.removeFirst();
        }
    }

    /**
     * 把联网搜索结果整理成一段连贯的中文回答（而不是罗列原文）。
     * 仅使用资料中的信息，资料不足时明确说明。
     *
     * @return 整理后的回答；LLM 不可用或失败返回 null（上层回退到原始罗列）
     */
    public String synthesizeFromWeb(String question, List<Map<String, Object>> results) {
        if (!available() || results == null || results.isEmpty()) {
            return null;
        }
        StringBuilder material = new StringBuilder();
        int i = 0;
        for (Map<String, Object> r : results) {
            i++;
            String title = String.valueOf(r.getOrDefault("title", ""));
            String snippet = String.valueOf(r.getOrDefault("snippet", ""));
            String site = String.valueOf(r.getOrDefault("site", ""));
            material.append(i).append(". ").append(title);
            if (!site.isBlank() && !"null".equals(site)) {
                material.append("（").append(site).append("）");
            }
            material.append("\n   ").append(snippet).append("\n");
        }
        String system = """
                你是碳能智控运维平台的 AI 助手小E。请根据下面提供的联网搜索资料回答用户问题。
                要求：
                1. 直接回答，条理清晰（可分 2~4 点），不要罗列原文，不要复述"资料1、资料2"；
                2. 只使用资料中的信息，不确定的内容不要编造；
                3. 若资料与问题无关或不足以回答，直接说明"未找到可靠资料"，不要硬凑；
                4. 回答 250 字以内，用中文。""";
        String user = "用户问题：" + question + "\n\n联网搜索资料：\n" + material;
        try {
            return llmClient.chat(system, user, 0.3, 600);
        } catch (Exception e) {
            log.warn("联网资料整理失败：{}", e.getMessage());
            return null;
        }
    }

    // ================================================================ 内部

    private Map<String, Object> msg(String role, String content, String toolCallId,
                                    List<Map<String, Object>> toolCalls) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        if (content != null) {
            m.put("content", content);
        } else {
            m.put("content", null);
        }
        if (toolCallId != null) {
            m.put("tool_call_id", toolCallId);
        }
        if (toolCalls != null) {
            m.put("tool_calls", toolCalls);
        }
        return m;
    }

    private Map<String, Object> assistantToolCallsMessage(ToolChatResult r) {
        List<Map<String, Object>> tcs = new ArrayList<>();
        for (ToolChatResult.ToolCall tc : r.getToolCalls()) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tc.getName());
            fn.put("arguments", tc.getArguments() == null ? "{}" : tc.getArguments());
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", tc.getId());
            c.put("type", "function");
            c.put("function", fn);
            tcs.add(c);
        }
        return msg("assistant", null, null, tcs);
    }

    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = mapper.readValue(json, Map.class);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("\n", " ");
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    private int nz(Integer i) {
        return i == null ? 0 : i;
    }

    /** 把联网搜索结果转成可点击的引用（前端渲染为链接） */
    @SuppressWarnings("unchecked")
    private void collectWebCitations(Map<String, Object> out, List<ChatResponse.Citation> target) {
        if (out == null) {
            return;
        }
        Object raw = out.get("results");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ChatResponse.Citation existing : target) {
            seen.add(existing.getUrl());
        }
        for (Object o : list) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) o;
            String url = String.valueOf(m.getOrDefault("url", ""));
            String title = String.valueOf(m.getOrDefault("title", ""));
            String snippet = String.valueOf(m.getOrDefault("snippet", ""));
            if (url.isBlank() || "null".equals(url) || !seen.add(url)) {
                continue;   // 去重：同一 URL 只保留一次
            }
            ChatResponse.Citation c = new ChatResponse.Citation();
            c.setSource(title);
            c.setUrl(url);
            c.setSnippet(snippet);
            target.add(c);
        }
    }

    private int intArg(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? def : Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    /** 估算工具结果行数：取结果里第一个 list 的长度，否则按 1 计 */
    private int resultRows(Object out) {
        if (out instanceof Map<?, ?> m) {
            for (Object v : m.values()) {
                if (v instanceof java.util.List<?> list) {
                    return list.size();
                }
            }
        }
        return 1;
    }
}

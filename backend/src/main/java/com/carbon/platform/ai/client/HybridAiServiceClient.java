package com.carbon.platform.ai.client;

import com.carbon.platform.ai.dto.ChatRequest;
import com.carbon.platform.ai.service.DeepSeekToolCallingService;
import com.carbon.platform.ai.search.YuanbaoSearchService;
import com.carbon.platform.ai.service.DashboardInsightService;
import com.carbon.platform.ai.tool.AiToolSchema;
import com.carbon.platform.ai.dto.DiagnoseRequest;
import com.carbon.platform.ai.dto.ReportRequest;
import com.carbon.platform.ai.dto.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 混合 AI 客户端（{@code carbon.ai.mode=hybrid}，推荐用于本项目）。
 *
 * <h3>为什么需要它</h3>
 * 本项目有两个性质完全不同的问答引擎，各自有明确的能力边界：
 * <table border="1">
 *   <tr><th>引擎</th><th>擅长</th><th>不擅长</th></tr>
 *   <tr><td>{@link MockAiServiceClient}（本地统计）</td>
 *       <td>「Eagle 园区 2016 年用电量多少」这类需要查数据库的问题</td>
 *       <td>「小温差大流量有什么危害」这类需要专业知识的问题</td></tr>
 *   <tr><td>{@link HttpAiServiceClient}（RAG 知识库）</td>
 *       <td>运维知识、规范条文、处置方法（可溯源引用）</td>
 *       <td>具体数值统计（它没有数据库访问能力）</td></tr>
 * </table>
 *
 * <p>实测教训：如果只配 {@code mode=http}，那么「统计 Eagle 园区用电量」也会被路由到
 * 知识库检索，返回一堆与问题无关的规范条文 —— 数据类问题的准确率会崩掉。
 * 反之只配 {@code mode=mock}，知识类问题无法回答。因此需要按问题类型路由。
 *
 * <h3>路由规则</h3>
 * 用关键词判断问题是否属于「知识类」。判据偏向保守：<b>只有明确的知识类特征才走 RAG</b>，
 * 其余一律走本地统计，保证数据类问题（占测试集 22/24）不退化。
 *
 * <h3>降级链</h3>
 * <pre>
 *   hybrid ──知识类──▶ RAG 服务 ──不可用──▶ 本地统计（并说明知识库暂不可用）
 *          └─数据类──▶ 本地统计（始终可用）
 * </pre>
 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "carbon.ai.mode", havingValue = "hybrid")
public class HybridAiServiceClient implements AiServiceClient {

    private final MockAiServiceClient localEngine;
    private final HttpAiServiceClient ragEngine;
    private final DeepSeekToolCallingService toolCalling;
    private final YuanbaoSearchService yuanbaoSearch;
    private final DashboardInsightService dashboardInsightService;
    private final AiToolSchema aiToolSchema;

    /** 知识类问题的特征词。命中任一即判定为知识类，路由到 RAG。 */
    private static final List<String> KNOWLEDGE_MARKERS = List.of(
            "什么是", "是什么", "含义", "概念", "原理", "为什么", "原因",
            "危害", "影响", "作用", "怎么处理", "如何处理", "处置", "措施", "办法",
            "标准步骤", "步骤", "流程", "规范", "标准", "要求", "规定",
            "一般是多少", "应该是多少", "多少合适", "怎么设", "如何设", "设定值",
            "排查", "诊断", "检修", "维护", "保养", "选型", "设计",
            "区别", "差异", "怎样", "如何", "怎么",
            // ── 疑问句式（2026-10 补充）──
            // 实测踩过的坑：用户问「系统能发现吗」「能识别吗」「导错了能撤销吗」，
            // 原词表没有这些句式，问题被判为「非知识类」→ 落到打招呼兜底回复，
            // 0 引用、0ms，看起来像系统答不上来。
            "能否", "是否", "能不能", "会不会", "可不可以", "可以吗", "支持吗",
            "能发现", "能识别", "能判断", "能查", "能看到", "能追溯", "能撤销",
            "有没有", "存在吗", "在哪", "哪里看", "怎么查", "如何查",
            // 时间与生效（问「多久能看到」「什么时候生效」）
            "多久", "何时", "什么时候", "生效", "可见", "能看到吗", "延迟",
            // 数据来源真实性（红队高频问题）
            "实测", "推算", "仿真", "数据来源", "真的吗", "真实", "可信",
            // 边界与异常值
            "为空", "缺失怎么", "异常值",
            // ── 数据治理域词（这些问的是机制，不是数值）──
            "单位不一致", "单位混用", "时间格式", "时间缺失", "清洗规则", "去重",
            "追溯", "回滚", "撤销", "诊断依据", "数据质量", "导入失败", "导入报错"
    );

    /**
     * 数据类问题的强特征词：一旦命中，无论是否含知识类特征词，都强制走本地统计。
     * 因为这些问题必须以数据库数值作答，任何知识库内容都无法替代。
     */
    private static final List<String> DATA_MARKERS = List.of(
            "总用电", "用电量", "用电情况", "能耗量", "多少度", "碳排放量", "碳排总量",
            "占比", "统计", "汇总", "报表", "趋势", "排名", "哪几栋", "哪些建筑",
            "异常率", "误报率", "召回率", "缺失率", "有多少", "数据量", "时间范围",
            "COP 能效", "cop能效", "能效怎么样", "都有哪些园区", "哪些园区", "哪些建筑类型",
            "异常小时数", "事件数", "峰值", "哪个时段", "几点"
    );

    public HybridAiServiceClient(
            MockAiServiceClient localEngine,
            DeepSeekToolCallingService toolCalling,
            YuanbaoSearchService yuanbaoSearch,
            DashboardInsightService dashboardInsightService,
            AiToolSchema aiToolSchema,
            @Value("${carbon.ai.base-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${carbon.ai.connect-timeout-ms:3000}") int connectTimeout,
            @Value("${carbon.ai.read-timeout-ms:60000}") int readTimeout) {
        this.localEngine = localEngine;
        this.toolCalling = toolCalling;
        this.yuanbaoSearch = yuanbaoSearch;
        this.dashboardInsightService = dashboardInsightService;
        this.aiToolSchema = aiToolSchema;
        // 手工构造而非注入：HttpAiServiceClient 带 @ConditionalOnProperty(mode=http)，
        // 在 hybrid 模式下不会被注册，但本类需要复用它。
        this.ragEngine = new HttpAiServiceClient(baseUrl, connectTimeout, readTimeout);
    }

    @Override
    public String mode() {
        return "hybrid";
    }

    /**
     * 建筑编号模式：形如 {@code Hog_office_Sydney}（两段下划线连接三个单词）。
     *
     * <p>一旦问题里出现具体建筑编号，它就是在问「这栋楼」的数据，必须走本地统计引擎。
     * 实测踩坑：「Hog_office_Sydney 这栋楼为什么能耗偏高」含「为什么」，
     * 会被知识类规则命中而路由到 RAG，但知识库无法回答具体建筑的诊断。
     */
    private static final java.util.regex.Pattern BUILDING_ID =
            java.util.regex.Pattern.compile("[A-Za-z]{2,}_[A-Za-z]{2,}_[A-Za-z]{2,}");

    /** 判断是否知识类问题 */
    boolean isKnowledge(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.replace(" ", "");
        // ① 含具体建筑编号 → 一定是在问数据/诊断，不是问知识
        if (BUILDING_ID.matcher(question).find()) {
            return false;
        }
        for (String m : DATA_MARKERS) {
            if (q.contains(m.replace(" ", ""))) {
                return false;   // 数据类强特征优先，先排除
            }
        }
        for (String m : KNOWLEDGE_MARKERS) {
            if (q.contains(m.replace(" ", ""))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        boolean knowledge = isKnowledge(request.getQuestion());
        if (knowledge) {
            ChatResponse r = ragEngine.chat(request);
            // RAG 不可用、检索为空，或明确拒答（「知识库中未找到」）时，回退联网搜索
            if (isRagMiss(r)) {
                log.warn("知识类问题 RAG 未返回有效答案，尝试联网搜索：{}", request.getQuestion());
                ChatResponse web = webSearchFallback(request);
                if (web != null) {
                    return web;
                }
                log.warn("联网搜索不可用，回退本地统计引擎：{}", request.getQuestion());
                ChatResponse fb = localEngine.chat(request);
                if (fb != null && fb.getAnswer() != null) {
                    fb.setAnswer("【知识库与联网搜索暂不可用，以下为系统数据分析能力可提供的内容】\n\n"
                            + fb.getAnswer());
                }
                return fb;
            }
            return r;
        }
        log.debug("数据类问题，优先走 DeepSeek 工具调用：{}", request.getQuestion());
        ChatResponse tc = toolCalling.answer(
                request.getSessionId(), request.getQuestion(),
                siteId(request), request.getBuildingId(),
                request.getStartDate(), request.getEndDate());
        if (tc != null && tc.getAnswer() != null && !tc.getAnswer().isBlank()) {
            return tc;
        }
        log.warn("DeepSeek 工具调用不可用，降级本地统计引擎：{}", request.getQuestion());
        ChatResponse local = localEngine.chat(request);

        // ★ 兜底升级：本地引擎识别不出意图时，不要直接返回"我能帮你做…"的问候，
        // 而是再问一次 RAG。RAG 有领域闸门：领域内能答就答，领域外给出明确拒绝理由。
        // 【为什么改】实测「帮我写一首诗」「股票会涨吗」原本返回能力菜单，
        // 用户观感是"什么都不答"；改后要么给出答案，要么给出清楚的拒绝原因。
        if (isNoIntentGreeting(local)) {
            log.info("本地引擎未识别意图，转 RAG 判定：{}", request.getQuestion());
            ChatResponse rag = ragEngine.chat(request);
            if (rag != null && Boolean.TRUE.equals(rag.getOk())
                    && rag.getAnswer() != null && !rag.getAnswer().isBlank()) {
                return rag;
            }
        }
        return local;
    }

    /** 判断 RAG 是否未命中（含拒答话术），命中则不应直接返回 */
    private boolean isRagMiss(ChatResponse r) {
        if (r == null || !Boolean.TRUE.equals(r.getOk())
                || r.getAnswer() == null || r.getAnswer().isBlank()) {
            return true;
        }
        String a = r.getAnswer();
        return a.contains("知识库中未找到") || a.contains("未找到与该问题相关")
                || a.contains("不做回答") || a.contains("语料尚未收录");
    }

    /** 联网搜索兜底：RAG 未覆盖时用元宝搜索，结果带可点击来源链接 */
    private ChatResponse webSearchFallback(ChatRequest request) {
        if (!yuanbaoSearch.available()) {
            return null;
        }
        long t0 = System.currentTimeMillis();
        try {
            Map<String, Object> res = yuanbaoSearch.search(request.getQuestion(), 5);
            if (res == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) res.get("results");
            if (results == null || results.isEmpty()) {
                return null;
            }
            ChatResponse out = new ChatResponse();
            out.setOk(true);
            List<ChatResponse.Citation> cites = new ArrayList<>();
            for (Map<String, Object> r : results) {
                String title = String.valueOf(r.getOrDefault("title", ""));
                String url = String.valueOf(r.getOrDefault("url", ""));
                String snip = String.valueOf(r.getOrDefault("snippet", ""));
                ChatResponse.Citation c = new ChatResponse.Citation();
                c.setSource(title);
                c.setUrl(url);
                c.setSnippet(snip);
                c.setPage(null);
                cites.add(c);
            }

            // 优先让大模型把检索资料整理成连贯回答；模型不可用时回退到朴素罗列
            String synthesized = toolCalling.synthesizeFromWeb(request.getQuestion(), results);
            String answer;
            if (synthesized != null && !synthesized.isBlank()) {
                answer = synthesized
                        + "\n\n（来源：腾讯元宝搜索 · 联网检索，可点击下方参考来源查看原文）";
            } else {
                StringBuilder sb = new StringBuilder("我通过联网搜索找到以下资料：\n");
                int i = 0;
                for (ChatResponse.Citation c : cites) {
                    i++;
                    sb.append(i).append(". ").append(c.getSource()).append("\n");
                    if (org.springframework.util.StringUtils.hasText(c.getSnippet())) {
                        sb.append("   ").append(c.getSnippet()).append("\n");
                    }
                }
                answer = sb.toString()
                        + "\n（来源：腾讯元宝搜索 · 联网检索，可点击下方参考来源查看原文）";
            }
            out.setAnswer(answer);
            out.setCitations(cites);
            out.setDataSource("联网搜索（元宝 SearchPro）");
            out.setConfidence(0.6);
            out.setElapsedMs(System.currentTimeMillis() - t0);
            return out;
        } catch (Exception e) {
            log.warn("联网搜索兜底失败：{}", e.getMessage());
            return null;
        }
    }

    /** 从请求上下文取 siteId（前端放在 context.siteId） */
    private String siteId(ChatRequest r) {
        if (r.getContext() != null && r.getContext().get("siteId") != null) {
            return String.valueOf(r.getContext().get("siteId"));
        }
        return null;
    }

    /**
     * 判断本地引擎的返回是否为「未识别意图的问候」。
     *
     * <p>特征：答案里含固定的问候开场语，且工具链只有 query_dimensions
     * （问候分支会挂一个查询维度清单的工具调用，所以不能只看 toolCalls 是否为空）。
     */
    private boolean isNoIntentGreeting(ChatResponse r) {
        if (r == null || r.getAnswer() == null) {
            return true;
        }
        String a = r.getAnswer();
        if (!a.contains("我可以帮您") && !a.contains("我是「小E」")) {
            return false;
        }
        if (r.getToolCalls() == null || r.getToolCalls().isEmpty()) {
            return true;
        }
        return r.getToolCalls().stream()
                .allMatch(t -> "query_dimensions".equals(t.getTool()));
    }

    @Override
    public Map<String, Object> diagnose(DiagnoseRequest request) {
        // 诊断需要数据库归因（本地引擎强），处置建议需要知识库（RAG 强）。
        // RAG 服务内部会先调后端 diagnose 接口再补知识，因此优先用它的合并结果；
        // 不可用时退回本地引擎，保证诊断功能不中断。
        Map<String, Object> r = ragEngine.diagnose(request);
        if (r != null && !r.isEmpty() && r.get("diagnosis") != null) {
            return r;
        }
        return localEngine.diagnose(request);
    }

    @Override
    public Map<String, Object> generateReport(ReportRequest request) {
        // 结构化统计由本地引擎算（真实数据），叙述改由大模型生成
        Map<String, Object> report = localEngine.generateReport(request);
        if (Boolean.TRUE.equals(request.getWithNarrative())) {
            Map<String, Object> llm = dashboardInsightService.reportNarrative(report);
            if (llm != null && llm.get("narrative") != null) {
                report.put("narrative", llm.get("narrative"));
                report.put("mode", "llm");
                report.put("narrativeAttempt", llm.get("attempt"));
            }
        }
        return report;
    }

    @Override
    public ChatResponse chatStream(ChatRequest request,
                                   java.util.function.Consumer<String> onDelta,
                                   java.util.function.Consumer<ChatResponse.ToolCall> onTool) {
        // 知识类 / 兜底路径本身不是流式，走原逻辑后一次性回调
        if (isKnowledge(request.getQuestion())) {
            ChatResponse r = chat(request);
            if (r != null && r.getAnswer() != null && onDelta != null) {
                onDelta.accept(r.getAnswer());
            }
            return r;
        }
        // 数据类问题：DeepSeek 工具调用 + 流式正文
        ChatResponse tc = toolCalling.answerStream(
                request.getSessionId(), request.getQuestion(),
                siteId(request), request.getBuildingId(),
                request.getStartDate(), request.getEndDate(), onDelta, onTool);
        if (tc != null && tc.getAnswer() != null && !tc.getAnswer().isBlank()) {
            return tc;
        }
        log.warn("DeepSeek 流式调用不可用，降级本地统计引擎：{}", request.getQuestion());
        ChatResponse local = localEngine.chat(request);
        if (local != null && local.getAnswer() != null && onDelta != null) {
            onDelta.accept(local.getAnswer());
        }
        return local;
    }

    @Override
    public Map<String, Object> listTools() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        Map<String, Object> local = localEngine.listTools();
        Map<String, Object> rag = ragEngine.listTools();

        // ★ 主清单 = 大模型真正能调用的工具（与 /ai/tools/schema 同源），
        //   避免出现 /ai/tools 报 7 个、/ai/tools/schema 报 12 个的口径不一致。
        java.util.List<Map<String, Object>> callable = new java.util.ArrayList<>();
        for (Map<String, Object> def : aiToolSchema.toolDefinitions()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fn = (Map<String, Object>) def.get("function");
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("name", fn.get("name"));
            item.put("desc", fn.get("description"));
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) fn.get("parameters");
            item.put("params", params.get("required"));
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) params.get("properties");
            item.put("properties", new java.util.ArrayList<>(props.keySet()));
            callable.add(item);
        }

        m.put("mode", "hybrid");
        m.put("count", callable.size());
        m.put("tools", callable);
        // 申报表口径的 7 类 MCP 工具保留为辅：它们是契约描述，不是当前实际调用面
        Map<String, Object> legacy = new java.util.LinkedHashMap<>();
        legacy.put("count", local.get("count"));
        legacy.put("tools", local.get("tools"));
        legacy.put("note", "申报表约定的 7 类 MCP 工具（概念口径）；"
                + "当前实际可调用的是上面 tools 中的 " + callable.size() + " 个");
        m.put("legacyMcp", legacy);
        m.put("ragCount", rag.get("count"));
        return m;
    }

    @Override
    public Map<String, Object> health() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("status", "UP");
        m.put("mode", "hybrid");
        m.put("local", "UP（本地统计引擎，始终可用）");
        Map<String, Object> ragHealth = ragEngine.health();
        m.put("rag", ragHealth);
        m.put("routing", "知识类问题 → RAG 知识库；数据类问题 → 本地统计");
        return m;
    }
}

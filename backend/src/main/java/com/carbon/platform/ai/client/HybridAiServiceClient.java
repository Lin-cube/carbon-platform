package com.carbon.platform.ai.client;

import com.carbon.platform.ai.dto.ChatRequest;
import com.carbon.platform.ai.dto.DiagnoseRequest;
import com.carbon.platform.ai.dto.ReportRequest;
import com.carbon.platform.ai.dto.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

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

    /** 知识类问题的特征词。命中任一即判定为知识类，路由到 RAG。 */
    private static final List<String> KNOWLEDGE_MARKERS = List.of(
            "什么是", "是什么", "含义", "概念", "原理", "为什么", "原因",
            "危害", "影响", "作用", "怎么处理", "如何处理", "处置", "措施", "办法",
            "标准步骤", "步骤", "流程", "规范", "标准", "要求", "规定",
            "一般是多少", "应该是多少", "多少合适", "怎么设", "如何设", "设定值",
            "排查", "诊断", "检修", "维护", "保养", "选型", "设计",
            "区别", "差异", "怎样", "如何", "怎么"
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
            @Value("${carbon.ai.base-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${carbon.ai.connect-timeout-ms:3000}") int connectTimeout,
            @Value("${carbon.ai.read-timeout-ms:60000}") int readTimeout) {
        this.localEngine = localEngine;
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
            // RAG 不可用或检索为空时，回退本地统计并说明原因，避免返回空答案
            if (r == null || !Boolean.TRUE.equals(r.getOk())
                    || r.getAnswer() == null || r.getAnswer().isBlank()) {
                log.warn("知识类问题 RAG 未返回有效答案，回退本地统计引擎：{}",
                        request.getQuestion());
                ChatResponse fb = localEngine.chat(request);
                if (fb != null && fb.getAnswer() != null) {
                    fb.setAnswer("【知识库暂不可用，以下为系统数据分析能力可提供的内容】\n\n"
                            + fb.getAnswer());
                }
                return fb;
            }
            return r;
        }
        log.debug("数据类问题，走本地统计引擎：{}", request.getQuestion());
        return localEngine.chat(request);
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
        // 报表是数据统计任务，本地引擎直接可算，无需知识库
        return localEngine.generateReport(request);
    }

    @Override
    public Map<String, Object> listTools() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        Map<String, Object> local = localEngine.listTools();
        Map<String, Object> rag = ragEngine.listTools();
        m.put("mode", "hybrid");
        m.put("localTools", local.get("tools"));
        m.put("ragTools", rag.get("tools"));
        m.put("localCount", local.get("count"));
        m.put("ragCount", rag.get("count"));
        m.put("tools", local.get("tools"));   // 7 类数据工具为契约中的主体
        m.put("count", local.get("count"));
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

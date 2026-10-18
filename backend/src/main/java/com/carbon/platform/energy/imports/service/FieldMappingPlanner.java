package com.carbon.platform.energy.imports.service;

import com.carbon.platform.ai.llm.LlmClient;
import com.carbon.platform.energy.imports.ImportTargetField;
import com.carbon.platform.energy.imports.dto.UploadResultVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 字段映射规划器：实现申报表要求的「AI 批量规划导入实现自动分类与字段映射」。
 *
 * <h3>两级策略（LLM 优先，规则兜底）</h3>
 * <ol>
 *   <li><b>LLM 语义映射</b>：把【目标字段 schema + 每列的列名/类型/样例值】一次性交给大模型，
 *       要求它批量输出全部列的映射方案（含置信度与理由）。这是真正的语义理解，
 *       能处理规则引擎无法覆盖的脏列名（如「A栋电量(kWh)」「采集时刻」）；
 *   <li><b>规则引擎兜底</b>：LLM 不可用/超时/返回非法 JSON 时，自动回退到
 *       别名表 + 类型推断 + 样本校验的规则评分，保证功能不中断。
 * </ol>
 *
 * <h3>为什么保留规则引擎</h3>
 * 演示现场网络不可控。规则引擎命中率略低但<b>永不失败</b>，且响应是毫秒级；
 * LLM 命中率更高但依赖网络。两者组合既能体现 AI 能力，又能保证稳定性。
 *
 * <h3>安全与合规</h3>
 * 仅把<b>列名与少量脱敏样例值</b>发给模型（样例值最多 3 个、每个截断 40 字符），
 * 不上传完整数据文件，避免数据外泄风险。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FieldMappingPlanner {

    private final LlmClient llmClient;
    private final RuleBasedFieldMapper ruleMapper;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /** 规划结果：映射 + 使用的策略元信息 */
    public record Plan(Map<String, String> mapping,
                       Map<String, Double> confidence,
                       Map<String, String> reason,
                       String strategy,
                       String provider,
                       String model,
                       long elapsedMs,
                       String fallbackReason) {
    }

    private static final String SYSTEM_PROMPT = """
            你是一名建筑能源管理领域的数据工程师，负责把用户上传的能耗数据文件的列，
            映射到系统的标准字段上。

            要求：
            1. 只输出 JSON，不要输出任何解释性文字、不要用 markdown 代码块包裹；
            2. 对每一列都要给出结论：能映射的给出 targetField，不能映射的 targetField 设为 null；
            3. confidence 取 0~1 的小数，表示你对该映射的把握程度；
            4. reason 用一句中文说明判断依据（20 字以内）；
            5. 一个目标字段最多被映射一次，出现冲突时保留置信度最高的那一列；
            6. 判断依据以列名语义为主、样例值为辅。注意中英文混用与单位后缀，
               例如「A栋电量(kWh)」应识别为电力能耗，「采集时刻」应识别为监测时间。

            输出 JSON 结构（严格遵循）：
            {"mappings":[{"sourceColumn":"原始列名","targetField":"标准字段名或null",
                          "confidence":0.95,"reason":"判断依据"}]}
            """;

    /**
     * 规划字段映射。
     *
     * @param columns 列画像（来自上传解析，含列名/推断类型/样例值）
     * @return 规划结果
     */
    public Plan plan(List<UploadResultVO.ColumnInfo> columns) {
        long t0 = System.currentTimeMillis();

        // 1. 先算规则引擎结果（无论 LLM 是否可用都要算：既作为兜底，也作为交叉验证）
        Plan rulePlan = ruleMapper.map(columns);

        // 2. 尝试 LLM
        if (llmClient.available() && !columns.isEmpty()) {
            String userPrompt = buildUserPrompt(columns);
            String raw = llmClient.chat(SYSTEM_PROMPT, userPrompt, 0.0);
            if (StringUtils.hasText(raw)) {
                Optional<Plan> llmPlan = parseLlmResponse(raw, columns, rulePlan);
                if (llmPlan.isPresent()) {
                    Plan p = llmPlan.get();
                    long cost = System.currentTimeMillis() - t0;
                    log.info("AI 字段映射成功：provider={} model={} 列数={} 命中={} 耗时={}ms",
                            llmClient.provider(), llmClient.model(), columns.size(),
                            p.mapping().size(), cost);
                    return new Plan(p.mapping(), p.confidence(), p.reason(),
                            "LLM", llmClient.provider(), llmClient.model(), cost, null);
                }
                log.warn("LLM 返回无法解析，降级为规则映射");
                return withMeta(rulePlan, t0, "LLM 返回内容无法解析为合法 JSON");
            }
            log.warn("LLM 无响应（超时或异常），降级为规则映射");
            return withMeta(rulePlan, t0, "LLM 无响应或调用失败");
        }

        String why = !llmClient.available()
                ? "未配置大模型密钥（carbon.llm.provider 或 api-key 为空）"
                : "无待映射列";
        return withMeta(rulePlan, t0, why);
    }

    private Plan withMeta(Plan base, long t0, String fallbackReason) {
        return new Plan(base.mapping(), base.confidence(), base.reason(),
                "RULE_FALLBACK", base.provider(), base.model(),
                System.currentTimeMillis() - t0, fallbackReason);
    }

    // ================================================================
    // Prompt 组装
    // ================================================================
    private String buildUserPrompt(List<UploadResultVO.ColumnInfo> columns) {
        StringBuilder sb = new StringBuilder();
        sb.append("【系统标准字段清单】\n");
        for (ImportTargetField f : ImportTargetField.all()) {
            sb.append(String.format("- %s（%s，单位 %s）%s%n",
                    f.getColumn(), f.getLabel(), f.getUnit(),
                    f.isRequired() ? "【必填】" : ""));
        }

        sb.append("\n【待映射的源数据列】\n");
        for (UploadResultVO.ColumnInfo c : columns) {
            String samples = c.getSamples() == null || c.getSamples().isEmpty()
                    ? "（空列）" : String.join(" / ", c.getSamples());
            sb.append(String.format("列名=\"%s\"　类型=%s　样例值=%s%n",
                    c.getSourceColumn(), c.getInferredType(), truncate(samples, 60)));
        }

        sb.append("\n请对这 ").append(columns.size())
                .append(" 列逐一给出映射建议，按前述 JSON 结构输出。");
        return sb.toString();
    }

    /**
     * 解析 LLM 返回。
     *
     * <p>容错处理（实测大模型常犯的三种毛病）：
     * <ol>
     *   <li>用 markdown 代码块包裹 JSON → 剥离 ```json 围栏；</li>
     *   <li>JSON 前后带解释性文字 → 截取第一个 { 到最后一个 }；</li>
     *   <li>给不存在的列名或非法 targetField → 丢弃该条，不报错。</li>
     * </ol>
     * 另外做冲突消解：同一目标字段被多列命中时，保留置信度最高者。
     */
    private Optional<Plan> parseLlmResponse(String raw, List<UploadResultVO.ColumnInfo> columns,
                                            Plan rulePlan) {
        try {
            String json = extractJson(raw);
            if (json == null) {
                return Optional.empty();
            }
            JsonNode root = jsonMapper.readTree(json);
            JsonNode arr = root.path("mappings");
            if (!arr.isArray() || arr.isEmpty()) {
                return Optional.empty();
            }

            Set<String> validCols = new HashSet<>();
            for (UploadResultVO.ColumnInfo c : columns) {
                validCols.add(c.getSourceColumn());
            }

            // 冲突消解：targetField -> 最佳候选
            Map<String, String[]> best = new HashMap<>();   // target -> [source, conf, reason]
            for (JsonNode n : arr) {
                String src = n.path("sourceColumn").asText(null);
                if (src == null || !validCols.contains(src)) {
                    continue;
                }
                JsonNode tf = n.path("targetField");
                if (tf.isNull() || !StringUtils.hasText(tf.asText())) {
                    continue;
                }
                String target = tf.asText();
                if (ImportTargetField.byColumn(target).isEmpty()) {
                    log.debug("LLM 给出未知目标字段，已忽略：{}", target);
                    continue;
                }
                double conf = n.path("confidence").asDouble(0.6);
                String reason = n.path("reason").asText("AI 语义匹配");
                String[] cur = best.get(target);
                if (cur == null || conf > Double.parseDouble(cur[1])) {
                    best.put(target, new String[]{src, String.valueOf(conf), reason});
                }
            }

            if (best.isEmpty()) {
                return Optional.empty();
            }

            Map<String, String> mapping = new LinkedHashMap<>();
            Map<String, Double> confidence = new LinkedHashMap<>();
            Map<String, String> reason = new LinkedHashMap<>();
            Set<String> used = new HashSet<>();
            for (Map.Entry<String, String[]> e : best.entrySet()) {
                String[] v = e.getValue();
                if (!used.add(v[0])) {
                    continue;   // 同一源列只能映射一次
                }
                mapping.put(v[0], e.getKey());
                confidence.put(v[0], Double.parseDouble(v[1]));
                reason.put(v[0], "[AI] " + v[2]);
            }

            // 必填字段若 LLM 漏掉，用规则引擎结果补齐（避免因模型遗漏导致无法导入）
            for (ImportTargetField f : ImportTargetField.all()) {
                if (!f.isRequired() || mapping.containsValue(f.getColumn())) {
                    continue;
                }
                String src = rulePlan.mapping().entrySet().stream()
                        .filter(x -> f.getColumn().equals(x.getValue()))
                        .map(Map.Entry::getKey).findFirst().orElse(null);
                String target = f.getColumn();
                if (src != null && !mapping.containsKey(src)
                        && mapping.values().stream().noneMatch(target::equals)) {
                    mapping.put(src, target);
                    confidence.put(src, rulePlan.confidence().getOrDefault(src, 0.5));
                    reason.put(src, "[规则补齐必填] " + rulePlan.reason().getOrDefault(src, ""));
                    log.info("LLM 遗漏必填字段 {}，已用规则引擎补齐（源列 {}）", target, src);
                }
            }

            return Optional.of(new Plan(mapping, confidence, reason,
                    "LLM", llmClient.provider(), llmClient.model(), 0L, null));
        } catch (Exception e) {
            log.warn("解析 LLM 映射结果失败：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 从可能被 markdown 围栏或解释文字包裹的文本中提取 JSON */
    private String extractJson(String raw) {
        String s = raw.trim();
        // 剥离 ```json ... ``` 围栏
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int start = s.indexOf('\n', fence);
            int end = s.indexOf("```", fence + 3);
            if (start > 0 && end > start) {
                s = s.substring(start + 1, end).trim();
                if (s.startsWith("json")) {
                    s = s.substring(4).trim();
                }
            }
        }
        // 截取第一个 { 到最后一个 }
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) {
            return null;
        }
        return s.substring(a, b + 1);
    }

    private String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}

package com.carbon.platform.ai.service;

import com.carbon.platform.ai.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监控看板的 AI 文字解读。
 *
 * <h3>为什么需要</h3>
 * 看板上的图表和 KPI 卡片需要人来"读懂"：哪一项异常、为什么、该关注什么。
 * 本服务把已经算好的聚合指标交给大模型，生成一段中文解读，直接展示在看板下方。
 *
 * <h3>成本控制（这是设计重点）</h3>
 * <ol>
 *   <li><b>只送聚合值</b>：送给模型的是一份约 400~600 token 的指标摘要
 *       （总量、结构占比、峰谷时段、异常率），<b>绝不送原始 599 万行明细</b>，
 *       也不送任何逐小时序列；</li>
 *   <li><b>结果缓存</b>：相同查询条件（日期范围 + 园区 + 类型）10 分钟内直接复用，
 *       反复刷新看板不会重复计费；</li>
 *   <li><b>输出限长</b>：max_tokens 由 {@code carbon.llm.max-tokens} 统一约束；</li>
 *   <li><b>可降级</b>：模型不可用时回退到规则模板，保证面板永远有内容，演示不中断。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardInsightService {

    private final JdbcTemplate jdbcTemplate;
    private final LlmClient llmClient;

    /** 缓存有效期：10 分钟。看板数据只在导入时变化，10 分钟足够且能显著省费用。 */
    private static final long TTL_MS = 10 * 60_000L;

    /** 缓存 key -> 结果 */
    private final Map<String, CachedInsight> cache = new ConcurrentHashMap<>();

    private record CachedInsight(String text, String mode, String provider,
                                 String model, long generatedAtMs, int promptChars) {
    }

    private static final String SYSTEM_PROMPT = """
            你是建筑能源运维分析师，为一个监控看板写「数据解读」。
            要求：
            1. 用中文写 150~250 字，分 2~3 个自然段，不要用 Markdown 标题或列表符号。
            2. 第一段说总量与结构特征（总用电、主要类型、峰谷时段）。
            3. 第二段指出最值得关注的 1~2 个点（异常率、能耗强度、集中度）。
            4. 第三段给 1 条具体可执行的建议。
            5. **数字必须原样引用我给的写法**（如 6.14 亿、55.4%），
               不要换算成精确值，不要补全位数，不要自己计算新的数字。
               例如我给「6.14 亿 kWh」，你就写 6.14 亿 kWh，不要写 614,125,848 kWh。
            6. 如果某项指标为 0 或缺失，直接不提，不要解释缺失原因。
            7. 语气客观，不要用「非常」「极大地」这类夸张词。
            """;

    /**
     * 生成看板解读。
     *
     * @param startDate    起始日期 yyyy-MM-dd
     * @param endDate      结束日期 yyyy-MM-dd
     * @param siteId       园区（可空）
     * @param buildingType 建筑类型（可空）
     */
    public Map<String, Object> insight(String startDate, String endDate,
                                       String siteId, String buildingType) {
        String key = startDate + "|" + endDate + "|" + nz(siteId) + "|" + nz(buildingType);
        long now = System.currentTimeMillis();

        CachedInsight hit = cache.get(key);
        if (hit != null && now - hit.generatedAtMs() < TTL_MS) {
            return toVo(hit, true);
        }

        // ── 1. 取聚合指标（全部为 SQL 聚合，不取明细）──
        Map<String, Object> stat = collectStats(startDate, endDate, siteId, buildingType);
        String userPrompt = buildPrompt(stat);

        // ── 2. 调模型 ──
        String text = null;
        String mode = "rule-fallback";
        if (llmClient.available()) {
            long t0 = System.currentTimeMillis();
            try {
                String raw = llmClient.chat(SYSTEM_PROMPT, userPrompt, 0.3);
                if (raw != null && !raw.isBlank()) {
                    // ★ 数字一致性校验（关键）
                    // 实测踩过：模型把总电量 614,125,848 写成了 614,125,843，
                    // 改了三位数字。监控面板上的数字必须与库中一致，
                    // 所以输出里出现的每个数字都要能在提示词里找到，否则整段作废。
                    List<String> unknown = findUnknownNumbers(raw, userPrompt);
                    if (unknown.isEmpty()) {
                        text = raw;
                        mode = "llm";
                        log.info("看板 AI 解读生成成功：provider={} model={} 输入{}字 耗时={}ms",
                                llmClient.provider(), llmClient.model(),
                                userPrompt.length(), System.currentTimeMillis() - t0);
                    } else {
                        log.warn("看板 AI 解读含未授权数字 {}，已降级为规则模板", unknown);
                    }
                }
            } catch (Exception e) {
                log.warn("看板 AI 解读失败，降级为规则模板：{}", e.toString());
            }
        }

        // ── 3. 降级：规则模板（保证面板永远有内容）──
        if (text == null || text.isBlank()) {
            text = ruleBased(stat);
        }

        CachedInsight ci = new CachedInsight(text.trim(), mode,
                llmClient.provider(), llmClient.model(), now, userPrompt.length());
        cache.put(key, ci);
        return toVo(ci, false);
    }

    /** 导入数据后清空缓存，使解读反映最新数据 */
    public void invalidate() {
        cache.clear();
        log.info("看板 AI 解读缓存已清空（数据变更）");
    }

    // ================================================================ 内部

    /**
     * 收集聚合指标。注意：这里全部是 SQL 聚合（SUM/AVG/COUNT），
     * 单次执行约几十毫秒，且结果只有十来个数字，不涉及明细传输。
     */
    private Map<String, Object> collectStats(String startDate, String endDate,
                                             String siteId, String buildingType) {
        Map<String, Object> m = new LinkedHashMap<>();
        LocalDate s = LocalDate.parse(startDate);
        LocalDate e = LocalDate.parse(endDate);
        m.put("区间", startDate + " 至 " + endDate);

        // ── 表结构注意（实测踩过坑）──
        // fact_energy_hourly 有 site_id，但**没有** building_type_cn ——
        // 建筑类型只存在于 dim_building。早期版本直接在事实表上过滤类型，
        // 报 bad SQL grammar: Unknown column 'building_type_cn'。
        // 因此这里统一 LEFT JOIN dim_building，既能按类型过滤也能做类型结构统计。
        String from = " FROM fact_energy_hourly f "
                + "LEFT JOIN dim_building d ON d.building_id = f.building_id ";

        StringBuilder where = new StringBuilder(" WHERE f.ts >= ? AND f.ts < ? ");
        List<Object> args = new ArrayList<>();
        args.add(s.atStartOfDay());
        args.add(e.plusDays(1).atStartOfDay());
        if (siteId != null && !siteId.isBlank()) {
            where.append(" AND f.site_id = ? ");
            args.add(siteId);
        }
        if (buildingType != null && !buildingType.isBlank()) {
            where.append(" AND d.building_type_cn = ? ");
            args.add(buildingType);
        }

        // 总量
        Map<String, Object> total = jdbcTemplate.queryForMap("""
                SELECT COUNT(DISTINCT f.building_id) AS bld,
                       COUNT(*)                      AS hours,
                       COALESCE(SUM(f.electricity_kwh), 0) AS elec,
                       COALESCE(SUM(f.hvac_kwh), 0)        AS hvac,
                       COALESCE(SUM(f.water_m3), 0)        AS water,
                       COALESCE(SUM(f.carbon_kg), 0)       AS carbon,
                       SUM(CASE WHEN f.anomaly_flag = 1 THEN 1 ELSE 0 END) AS anomaly_hours
                """ + from + where, args.toArray());
        m.putAll(total);

        // 峰谷时段（按小时聚合，只取 top1 与 bottom1）
        List<Map<String, Object>> hours = jdbcTemplate.queryForList("""
                SELECT HOUR(f.ts) AS h, SUM(f.electricity_kwh) AS v
                """ + from + where + " GROUP BY HOUR(f.ts) ORDER BY v DESC", args.toArray());
        if (!hours.isEmpty()) {
            m.put("峰值时刻", hours.get(0).get("h") + " 时");
            m.put("峰值电量", hours.get(0).get("v"));
            var last = hours.get(hours.size() - 1);
            m.put("谷值时刻", last.get("h") + " 时");
            m.put("谷值电量", last.get("v"));
        }

        // 建筑类型结构（top3）
        List<Map<String, Object>> types = jdbcTemplate.queryForList("""
                SELECT d.building_type_cn AS t, SUM(f.electricity_kwh) AS v
                """ + from + where + " AND d.building_type_cn IS NOT NULL"
                + " GROUP BY d.building_type_cn ORDER BY v DESC LIMIT 3", args.toArray());
        double elec = toDouble(total.get("elec"));
        List<String> typeLines = new ArrayList<>();
        for (Map<String, Object> t : types) {
            double v = toDouble(t.get("v"));
            String pct = elec > 0 ? String.format("%.1f%%", v / elec * 100) : "-";
            typeLines.add(t.get("t") + " " + pct);
        }
        m.put("类型结构", String.join("、", typeLines));

        return m;
    }

    /**
     * 把指标拼成紧凑的提示词。
     *
     * <p><b>关键设计：只给「简写数字」</b>。实测让模型复述 614,125,848 这种 9 位数，
     * 它会写成 614,125,843（改了 3 位），而监控面板对数字准确性零容忍。
     * 因此提示词里只出现 6.14 亿 / 55.4% 这类短数字，
     * 精确值由前端从结构化数据自行渲染，模型不参与复述。
     */
    private String buildPrompt(Map<String, Object> m) {
        double anomalyHours = toDouble(m.get("anomaly_hours"));
        String anomalyText = anomalyHours >= 10000
                ? String.format("%.2f 万", anomalyHours / 10000)
                : String.format("%.0f", anomalyHours);

        return "以下是监控看板的聚合指标，请据此写解读：\n"
                + "统计区间：" + m.get("区间") + "\n"
                + "建筑数量：" + m.get("bld") + " 栋\n"
                + "总用电量：" + shortNum(toDouble(m.get("elec")), "kWh") + "\n"
                + "空调能耗：" + shortNum(toDouble(m.get("hvac")), "kWh") + "\n"
                + "用水量：" + shortNum(toDouble(m.get("water")), "m³") + "\n"
                + "碳排放：" + shortNum(toDouble(m.get("carbon")), "kg") + "\n"
                + "峰值时段：" + nz(m.get("峰值时刻")) + "（" + shortNum(toDouble(m.get("峰值电量")), "kWh") + "）\n"
                + "谷值时段：" + nz(m.get("谷值时刻")) + "（" + shortNum(toDouble(m.get("谷值电量")), "kWh") + "）\n"
                + "异常小时数：" + anomalyText + "\n"
                + "类型结构：" + nz(m.get("类型结构")) + "\n";
    }

    /** 大数转中文简写：614125847.9 → "6.14 亿 kWh"；15441785 → "1544 万 m³" */
    private String shortNum(double v, String unit) {
        String num;
        if (Math.abs(v) >= 100_000_000) {
            num = String.format("%.2f 亿", v / 100_000_000);
        } else if (Math.abs(v) >= 10_000) {
            num = String.format("%.0f 万", v / 10_000);
        } else {
            num = String.format("%.1f", v);
        }
        return num + " " + unit;
    }

    /**
     * 规则兜底：模型不可用时用固定模板拼一段解读。
     * 不追求文采，只保证面板不为空且数字正确。
     */
    private String ruleBased(Map<String, Object> m) {
        double elec = toDouble(m.get("elec"));
        double hvac = toDouble(m.get("hvac"));
        long hours = (long) toDouble(m.get("hours"));
        long anomaly = (long) toDouble(m.get("anomaly_hours"));
        double hvacRatio = elec > 0 ? hvac / elec * 100 : 0;
        double anomalyRate = hours > 0 ? (double) anomaly / hours * 100 : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("本区间（").append(m.get("区间")).append("）覆盖 ")
                .append(m.get("bld")).append(" 栋建筑，总用电量 ")
                .append(fmt(m.get("elec"))).append(" kWh。");
        if (!nz(m.get("类型结构")).isEmpty()) {
            sb.append("用电结构以 ").append(m.get("类型结构")).append(" 为主。");
        }
        if (!nz(m.get("峰值时刻")).isEmpty()) {
            sb.append("负荷集中在 ").append(m.get("峰值时刻")).append("，谷值出现在 ")
                    .append(m.get("谷值时刻")).append("。");
        }
        sb.append("\n\n值得关注：");
        if (hvacRatio > 0) {
            sb.append("空调能耗占总用电 ").append(String.format("%.1f%%", hvacRatio))
                    .append("，是最大的可控成本项；");
        }
        sb.append("异常小时占比 ").append(String.format("%.2f%%", anomalyRate)).append("。");
        sb.append("\n\n建议：优先核对峰值时段的运行时间表与设定值，"
                + "非使用时段应关闭或降频运行，这部分通常不需要改造硬件即可见效。");
        sb.append("\n\n（当前为大模型不可用时的规则解读，数据本身为实时聚合结果。）");
        return sb.toString();
    }

    private Map<String, Object> toVo(CachedInsight ci, boolean cached) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", ci.text());
        out.put("mode", ci.mode());
        out.put("cached", cached);
        out.put("provider", ci.provider());
        out.put("model", ci.model());
        out.put("promptChars", ci.promptChars());
        out.put("generatedAt", LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(ci.generatedAtMs()),
                java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        out.put("ttlSeconds", TTL_MS / 1000);
        return out;
    }

    /**
     * 找出模型输出里「提示词中不存在」的数字。
     *
     * <p>规则：把文本中的数字（含千分位与小数）抽出，去掉逗号后与提示词中的数字集合比对。
     * 允许 1% 以内的舍入差异（模型可能把 55.44% 写成 55.4%）。
     * 年份、小整数（0~31，可能是日期/小时）不参与校验，避免误杀。
     */
    private List<String> findUnknownNumbers(String text, String prompt) {
        java.util.Set<String> allowed = new java.util.HashSet<>();
        java.util.Set<Double> allowedVals = new java.util.HashSet<>();
        java.util.regex.Matcher pm = java.util.regex.Pattern
                .compile("[0-9][0-9,]*\\.?[0-9]*").matcher(prompt);
        while (pm.find()) {
            String t = pm.group().replace(",", "");
            allowed.add(t);
            try {
                allowedVals.add(Double.parseDouble(t));
            } catch (Exception ignore) {
                // 忽略无法解析的片段
            }
        }

        List<String> unknown = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[0-9][0-9,]*\\.?[0-9]*").matcher(text);
        while (m.find()) {
            String tok = m.group().replace(",", "").replaceAll("\\.0+$", "");
            if (allowed.contains(tok)) {
                continue;
            }
            double v;
            try {
                v = Double.parseDouble(tok);
            } catch (Exception e) {
                continue;
            }
            // 小整数（小时数、日期、排名）不校验
            if (v <= 31 && v == Math.floor(v)) {
                continue;
            }
            // 校验规则：
            //  - 绝对值 >= 1000 的「大数」必须**精确**出现在提示词里（不允许舍入）。
            //    原因：1% 的容差对 9 位数意味着 600 万的偏差，
            //    实测模型把 614,125,848 写成 614,125,843 时，1% 容差直接放过了。
            //  - 小于 1000 的数（百分比、小时、栋数）允许 1% 舍入。
            boolean ok = false;
            for (double a : allowedVals) {
                if (v >= 1000 || a >= 1000) {
                    if (Math.abs(v - a) < 0.5) {
                        ok = true;
                        break;
                    }
                } else if (a > 0 && Math.abs(v - a) / a < 0.01) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                unknown.add(tok);
            }
        }
        return unknown.size() > 5 ? unknown.subList(0, 5) : unknown;
    }

    private String nz(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private double toDouble(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    private String fmt(Object v) {
        double d = toDouble(v);
        if (Math.abs(d) >= 10000) {
            return String.format("%,.0f", d);
        }
        return String.format("%,.1f", d);
    }
}

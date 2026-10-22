package com.carbon.platform.ai.service;

import com.carbon.platform.ai.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 文字解读服务，覆盖三个页面场景。
 *
 * <table>
 *   <tr><th>scope</th><th>页面</th><th>解读对象</th></tr>
 *   <tr><td>dashboard</td><td>监控看板</td><td>全局或筛选后的聚合指标</td></tr>
 *   <tr><td>campus</td><td>3D 园区</td><td>当前园区的指标 + 园区内异常建筑</td></tr>
 *   <tr><td>diagnosis</td><td>异常诊断抽屉</td><td>单栋建筑：异常时段 vs 正常时段的证据对比</td></tr>
 * </table>
 *
 * <h3>成本控制（设计重点）</h3>
 * <ol>
 *   <li><b>只送聚合值</b>：提示词 200~400 字，绝不送明细行；</li>
 *   <li><b>只给中文简写数字</b>（6.14 亿 / 55.4%），精确值由前端渲染，
 *       避免模型抄错长数字；</li>
 *   <li><b>输出数字校验</b>：{@code ≥1000} 的数字必须与提示词精确一致，
 *       否则整段作废、降级规则模板；</li>
 *   <li><b>结果缓存</b>：相同 scope + 筛选条件 10 分钟内复用。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardInsightService {

    private final JdbcTemplate jdbcTemplate;
    private final LlmClient llmClient;

    private static final long TTL_MS = 10 * 60_000L;
    private final Map<String, CachedInsight> cache = new ConcurrentHashMap<>();

    private record CachedInsight(String text, String mode, String provider, String model,
                                 long generatedAtMs, int promptChars,
                                 List<Map<String, Object>> focusBuildings) {
    }

    // ================================================================ 提示词

    private static final String COMMON_RULES = """
            要求：
            1. 用中文写 150~250 字，分 2~3 个自然段，不要用 Markdown 标题或列表符号。
            2. 数字必须原样引用我给的写法（如 6.14 亿、55.4%），
               不要换算成精确值，不要补全位数，不要自己计算新的数字。
            3. 只能使用我给的数字，不得编造任何数据或单位。
            4. 某项指标为 0 或缺失时直接不提，不要解释缺失原因。
            5. 语气客观，不要用「非常」「极大地」这类夸张词。
            """;

    private static final String PROMPT_DASHBOARD = """
            你是建筑能源运维分析师，为一个监控看板写「数据解读」。
            第一段说总量与结构特征；第二段指出最值得关注的 1~2 个点；
            第三段给 1 条具体可执行的建议。
            """ + COMMON_RULES;

    private static final String PROMPT_CAMPUS = """
            你是建筑能源运维分析师，为一个 3D 园区的当前园区写「数据解读」。
            第一段说该园区的规模与用能特征；第二段指出园区内值得重点关注的事项
            （若给了异常建筑清单，要点名说哪几栋）；第三段给 1 条针对该园区的建议。
            """ + COMMON_RULES;

    private static final String PROMPT_DIAGNOSIS = """
            你是建筑能源运维诊断专家，为一栋建筑的异常诊断写「证据链解读」。
            第一段说该建筑是否异常、异常多少小时、集中在什么时段；
            第二段逐条解释归因证据（对比异常时段与正常时段的数值差异），
            说明证据如何支撑这个结论；第三段给处置建议并指出需要人工复核的点。
            """ + COMMON_RULES;

    // ================================================================ 入口

    /**
     * @param scope      dashboard / campus / diagnosis
     * @param startDate  起始日期
     * @param endDate    结束日期
     * @param siteId     园区（campus 必传）
     * @param buildingId 建筑（diagnosis 必传）
     */
    public Map<String, Object> insight(String scope, String startDate, String endDate,
                                       String siteId, String buildingId) {
        String sc = (scope == null || scope.isBlank()) ? "dashboard" : scope.trim();
        String key = sc + "|" + startDate + "|" + endDate + "|" + nz(siteId) + "|" + nz(buildingId);
        long now = System.currentTimeMillis();

        CachedInsight hit = cache.get(key);
        if (hit != null && now - hit.generatedAtMs() < TTL_MS) {
            return toVo(hit, true);
        }

        Map<String, Object> stat = switch (sc) {
            case "campus" -> collectCampusStats(startDate, endDate, siteId);
            case "diagnosis" -> collectDiagnosisStats(startDate, endDate, buildingId);
            default -> collectDashboardStats(startDate, endDate, siteId, buildingId);
        };
        String systemPrompt = switch (sc) {
            case "campus" -> PROMPT_CAMPUS;
            case "diagnosis" -> PROMPT_DIAGNOSIS;
            default -> PROMPT_DASHBOARD;
        };

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> focus = (List<Map<String, Object>>) stat.remove("_focusBuildings");

        String userPrompt = buildPrompt(sc, stat, focus);

        String text = null;
        String mode = "rule-fallback";
        if (llmClient.available()) {
            long t0 = System.currentTimeMillis();
            try {
                String raw = llmClient.chat(systemPrompt, userPrompt, 0.3);
                if (raw != null && !raw.isBlank()) {
                    // 数字一致性校验：输出里出现的每个数字都要能在提示词里找到
                    List<String> unknown = findUnknownNumbers(raw, userPrompt);
                    if (unknown.isEmpty()) {
                        text = raw;
                        mode = "llm";
                        log.info("AI 解读成功：scope={} provider={} model={} 输入{}字 耗时={}ms",
                                sc, llmClient.provider(), llmClient.model(),
                                userPrompt.length(), System.currentTimeMillis() - t0);
                    } else {
                        log.warn("AI 解读含未授权数字 {}，降级规则模板", unknown);
                    }
                }
            } catch (Exception e) {
                log.warn("AI 解读失败，降级规则模板：{}", e.toString());
            }
        }
        if (text == null || text.isBlank()) {
            text = ruleBased(sc, stat);
        }

        CachedInsight ci = new CachedInsight(text.trim(), mode, llmClient.provider(),
                llmClient.model(), now, userPrompt.length(), focus);
        cache.put(key, ci);
        return toVo(ci, false);
    }

    /** 数据变更后清空全部解读缓存 */
    public void invalidate() {
        cache.clear();
        log.info("AI 解读缓存已清空（数据变更）");
    }

    // ================================================================ 取数

    /** fact 与 dim 的公共 FROM 片段。注意 fact 表没有 building_type_cn，必须 JOIN。 */
    private String fromClause() {
        return " FROM fact_energy_hourly f LEFT JOIN dim_building d "
                + "ON d.building_id = f.building_id ";
    }

    private Map<String, Object> collectDashboardStats(String startDate, String endDate,
                                                      String siteId, String buildingId) {
        return baseStats(startDate, endDate, siteId, buildingId, true);
    }

    private Map<String, Object> collectCampusStats(String startDate, String endDate, String siteId) {
        Map<String, Object> m = baseStats(startDate, endDate, siteId, null, true);
        if (siteId != null && !siteId.isBlank()) {
            m.put("园区", siteId);
        }
        return m;
    }

    private Map<String, Object> collectDiagnosisStats(String startDate, String endDate,
                                                      String buildingId) {
        Map<String, Object> m = baseStats(startDate, endDate, null, buildingId, false);
        if (buildingId == null || buildingId.isBlank()) {
            return m;
        }
        // 建筑档案
        try {
            Map<String, Object> info = jdbcTemplate.queryForMap(
                    "SELECT building_type_cn AS t, area_m2 AS area, energy_grade AS grade "
                            + "FROM dim_building WHERE building_id = ?", buildingId);
            m.put("建筑类型", info.get("t"));
            if (info.get("area") != null) {
                m.put("建筑面积", String.format("%.0f 平方米", toDouble(info.get("area"))));
            }
            m.put("能耗评级", info.get("grade"));
        } catch (Exception ignored) {
            // 档案缺失不影响解读
        }

        // 异常 vs 正常时段对比（证据链核心）
        try {
            Map<String, Object> cmp = jdbcTemplate.queryForMap("""
                    SELECT
                      SUM(CASE WHEN anomaly_flag = 1 THEN 1 ELSE 0 END) AS ah,
                      AVG(CASE WHEN anomaly_flag = 1 THEN electricity_kwh END) AS a_kwh,
                      AVG(CASE WHEN anomaly_flag = 1 THEN air_temperature_c END) AS a_temp,
                      AVG(CASE WHEN anomaly_flag = 1 THEN humidity_pct END) AS a_hum,
                      AVG(CASE WHEN anomaly_flag <> 1 THEN electricity_kwh END) AS n_kwh,
                      AVG(CASE WHEN anomaly_flag <> 1 THEN air_temperature_c END) AS n_temp,
                      AVG(CASE WHEN anomaly_flag <> 1 THEN humidity_pct END) AS n_hum,
                      MIN(CASE WHEN anomaly_flag = 1 THEN ts END) AS first_ts,
                      MAX(CASE WHEN anomaly_flag = 1 THEN ts END) AS last_ts
                    FROM fact_energy_hourly
                    WHERE building_id = ? AND ts >= ? AND ts < ?
                    """, buildingId, LocalDate.parse(startDate).atStartOfDay(),
                    LocalDate.parse(endDate).plusDays(1).atStartOfDay());

            double ah = toDouble(cmp.get("ah"));
            m.put("异常小时数", ah >= 10000 ? String.format("%.2f 万", ah / 10000)
                    : String.format("%.0f", ah));
            putIfPositive(m, "异常时段平均电量", cmp.get("a_kwh"));
            putIfPositive(m, "正常时段平均电量", cmp.get("n_kwh"));
            putIfPositive(m, "异常时段平均室外温度", cmp.get("a_temp"));
            putIfPositive(m, "正常时段平均室外温度", cmp.get("n_temp"));
            putIfPositive(m, "异常时段平均湿度", cmp.get("a_hum"));
            putIfPositive(m, "正常时段平均湿度", cmp.get("n_hum"));
            if (cmp.get("first_ts") != null && cmp.get("last_ts") != null) {
                m.put("异常时间范围", str(cmp.get("first_ts")).substring(0, 10)
                        + " 至 " + str(cmp.get("last_ts")).substring(0, 10));
            }
        } catch (Exception e) {
            log.debug("诊断对比取数失败：{}", e.toString());
        }
        return m;
    }

    /**
     * 公共统计：总量、峰谷、类型结构、异常建筑排行。
     *
     * @param withFocus 是否附带异常建筑清单（看板与园区需要，诊断不需要）
     */
    private Map<String, Object> baseStats(String startDate, String endDate,
                                          String siteId, String buildingId, boolean withFocus) {
        Map<String, Object> m = new LinkedHashMap<>();
        LocalDate s = LocalDate.parse(startDate);
        LocalDate e = LocalDate.parse(endDate);
        m.put("区间", startDate + " 至 " + endDate);

        StringBuilder where = new StringBuilder(" WHERE f.ts >= ? AND f.ts < ? ");
        List<Object> args = new ArrayList<>();
        args.add(s.atStartOfDay());
        args.add(e.plusDays(1).atStartOfDay());
        if (siteId != null && !siteId.isBlank()) {
            where.append(" AND f.site_id = ? ");
            args.add(siteId);
        }
        if (buildingId != null && !buildingId.isBlank()) {
            where.append(" AND f.building_id = ? ");
            args.add(buildingId);
        }

        Map<String, Object> total = jdbcTemplate.queryForMap("""
                SELECT COUNT(DISTINCT f.building_id) AS bld,
                       COUNT(*)                      AS hours,
                       COALESCE(SUM(f.electricity_kwh), 0) AS elec,
                       COALESCE(SUM(f.hvac_kwh), 0)        AS hvac,
                       COALESCE(SUM(f.water_m3), 0)        AS water,
                       COALESCE(SUM(f.carbon_kg), 0)       AS carbon,
                       SUM(CASE WHEN f.anomaly_flag = 1 THEN 1 ELSE 0 END) AS anomaly_hours
                """ + fromClause() + where, args.toArray());
        m.putAll(total);

        // 峰谷
        List<Map<String, Object>> hours = jdbcTemplate.queryForList("""
                SELECT HOUR(f.ts) AS h, SUM(f.electricity_kwh) AS v
                """ + fromClause() + where + " GROUP BY HOUR(f.ts) ORDER BY v DESC", args.toArray());
        if (!hours.isEmpty()) {
            m.put("峰值时刻", hours.get(0).get("h") + " 时");
            m.put("峰值电量", hours.get(0).get("v"));
            var last = hours.get(hours.size() - 1);
            m.put("谷值时刻", last.get("h") + " 时");
            m.put("谷值电量", last.get("v"));
        }

        // 类型结构 top3
        List<Map<String, Object>> types = jdbcTemplate.queryForList("""
                SELECT d.building_type_cn AS t, SUM(f.electricity_kwh) AS v
                """ + fromClause() + where + " AND d.building_type_cn IS NOT NULL"
                + " GROUP BY d.building_type_cn ORDER BY v DESC LIMIT 3", args.toArray());
        double elec = toDouble(total.get("elec"));
        List<String> typeLines = new ArrayList<>();
        for (Map<String, Object> t : types) {
            double v = toDouble(t.get("v"));
            String pct = elec > 0 ? String.format("%.1f%%", v / elec * 100) : "-";
            typeLines.add(t.get("t") + " " + pct);
        }
        m.put("类型结构", String.join("、", typeLines));

        if (withFocus) {
            m.put("_focusBuildings", focusBuildings(s, e, siteId, buildingId));
        }
        return m;
    }

    /**
     * 异常建筑清单：按异常小时数排序取前 5。
     *
     * <p>返回结构化数据而不是让模型复述建筑名，原因是：
     * 前端需要把建筑名渲染成可点击的链接（跳转到异常诊断页），
     * 由前端渲染比让模型输出 Markdown 链接更可靠。
     */
    private List<Map<String, Object>> focusBuildings(LocalDate s, LocalDate e,
                                                     String siteId, String buildingId) {
        StringBuilder where = new StringBuilder(
                " WHERE f.anomaly_flag = 1 AND f.ts >= ? AND f.ts < ? ");
        List<Object> args = new ArrayList<>();
        args.add(s.atStartOfDay());
        args.add(e.plusDays(1).atStartOfDay());
        if (siteId != null && !siteId.isBlank()) {
            where.append(" AND f.site_id = ? ");
            args.add(siteId);
        }
        if (buildingId != null && !buildingId.isBlank()) {
            where.append(" AND f.building_id = ? ");
            args.add(buildingId);
        }
        try {
            return jdbcTemplate.query("""
                    SELECT f.building_id AS buildingId,
                           d.building_type_cn AS buildingTypeCn,
                           COUNT(*) AS anomalyHours,
                           COALESCE(d.energy_grade, '-') AS energyGrade
                    """ + fromClause() + where
                    + " GROUP BY f.building_id, d.building_type_cn, d.energy_grade"
                    + " ORDER BY anomalyHours DESC LIMIT 5", (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("buildingId", rs.getString("buildingId"));
                row.put("buildingTypeCn", rs.getString("buildingTypeCn"));
                row.put("anomalyHours", rs.getInt("anomalyHours"));
                row.put("energyGrade", rs.getString("energyGrade"));
                return row;
            }, args.toArray());
        } catch (Exception ex) {
            log.debug("异常建筑清单取数失败：{}", ex.toString());
            return List.of();
        }
    }

    // ================================================================ 提示词拼装

    private String buildPrompt(String scope, Map<String, Object> m,
                               List<Map<String, Object>> focus) {
        StringBuilder sb = new StringBuilder("以下是").append(switch (scope) {
            case "campus" -> "当前园区的聚合指标，请据此写解读：\n";
            case "diagnosis" -> "该建筑的异常诊断数据，请据此写证据链解读：\n";
            default -> "监控看板的聚合指标，请据此写解读：\n";
        });

        if (m.get("园区") != null) {
            sb.append("园区：").append(m.get("园区")).append("\n");
        }
        sb.append("统计区间：").append(m.get("区间")).append("\n");
        sb.append("建筑数量：").append(m.get("bld")).append(" 栋\n");
        if (m.get("建筑类型") != null) {
            sb.append("建筑类型：").append(m.get("建筑类型")).append("\n");
        }
        if (m.get("建筑面积") != null) {
            sb.append("建筑面积：").append(m.get("建筑面积")).append("\n");
        }
        if (m.get("能耗评级") != null) {
            sb.append("能耗评级：").append(m.get("能耗评级")).append("\n");
        }
        sb.append("总用电量：").append(shortNum(toDouble(m.get("elec")), "kWh")).append("\n");
        sb.append("空调能耗：").append(shortNum(toDouble(m.get("hvac")), "kWh")).append("\n");
        sb.append("碳排放：").append(shortNum(toDouble(m.get("carbon")), "kg")).append("\n");

        if (m.get("峰值时刻") != null) {
            sb.append("峰值时段：").append(m.get("峰值时刻"))
                    .append("（").append(shortNum(toDouble(m.get("峰值电量")), "kWh")).append("）\n");
            sb.append("谷值时段：").append(m.get("谷值时刻"))
                    .append("（").append(shortNum(toDouble(m.get("谷值电量")), "kWh")).append("）\n");
        }
        dual(sb, m, "异常时段平均电量", "正常时段平均电量", "平均电量", "kWh");
        dual(sb, m, "异常时段平均室外温度", "正常时段平均室外温度", "平均室外温度", "℃");
        dual(sb, m, "异常时段平均湿度", "正常时段平均湿度", "平均湿度", "%");
        if (m.get("异常小时数") != null) {
            sb.append("异常小时数：").append(m.get("异常小时数")).append("\n");
        }
        if (m.get("异常时间范围") != null) {
            sb.append("异常发生时间：").append(m.get("异常时间范围")).append("\n");
        }
        if (m.get("类型结构") != null && !str(m.get("类型结构")).isBlank()) {
            sb.append("类型结构：").append(m.get("类型结构")).append("\n");
        }
        // 异常建筑清单：让模型能点名说「重点关注哪几栋」。
        // 数字（异常小时数）也一并给出，因此输出时能通过数字一致性校验。
        if (focus != null && !focus.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (Map<String, Object> b : focus) {
                lines.add(str(b.get("buildingId")) + " " + b.get("anomalyHours") + " 小时");
            }
            sb.append("异常小时数最多的建筑：").append(String.join("、", lines)).append("\n");
        }
        return sb.toString();
    }

    /** 写一对可对比的指标，例如「平均电量：异常时段 X，正常时段 Y」 */
    private void dual(StringBuilder sb, Map<String, Object> m,
                      String keyA, String keyB, String label, String unit) {
        if (m.get(keyA) == null && m.get(keyB) == null) {
            return;
        }
        sb.append(label).append("：异常时段 ")
                .append(shortNum(toDouble(m.get(keyA)), unit))
                .append("，正常时段 ")
                .append(shortNum(toDouble(m.get(keyB)), unit))
                .append("\n");
    }

    /** 规则兜底：模型不可用或输出不可信时使用 */
    private String ruleBased(String scope, Map<String, Object> m) {
        double elec = toDouble(m.get("elec"));
        double hvac = toDouble(m.get("hvac"));
        long hours = (long) toDouble(m.get("hours"));
        long anomaly = (long) toDouble(m.get("anomaly_hours"));
        double hvacRatio = elec > 0 ? hvac / elec * 100 : 0;
        double anomalyRate = hours > 0 ? (double) anomaly / hours * 100 : 0;

        StringBuilder sb = new StringBuilder();
        if ("diagnosis".equals(scope)) {
            sb.append("该建筑在 ").append(m.get("区间")).append(" 内异常 ")
                    .append(m.get("异常小时数")).append(" 小时。");
            if (m.get("异常时间范围") != null) {
                sb.append("异常发生时间为 ").append(m.get("异常时间范围")).append("。");
            }
            sb.append("\n\n证据对比：");
            if (m.get("异常时段平均电量") != null) {
                sb.append("异常时段平均电量 ")
                        .append(shortNum(toDouble(m.get("异常时段平均电量")), "kWh"))
                        .append("，正常时段 ")
                        .append(shortNum(toDouble(m.get("正常时段平均电量")), "kWh")).append("；");
            }
            if (m.get("异常时段平均室外温度") != null) {
                sb.append("异常时段平均室外温度 ")
                        .append(shortNum(toDouble(m.get("异常时段平均室外温度")), "℃"))
                        .append("，正常时段 ")
                        .append(shortNum(toDouble(m.get("正常时段平均室外温度")), "℃"))
                        .append("，温度升高可部分解释冷负荷增加。");
            }
            sb.append("\n\n建议核查该建筑在高负荷时段的运行策略与设备状态，"
                    + "并人工复核异常时段是否与特殊使用场景重合。");
        } else {
            sb.append("本区间（").append(m.get("区间")).append("）覆盖 ")
                    .append(m.get("bld")).append(" 栋建筑，总用电量 ")
                    .append(shortNum(elec, "kWh")).append("。");
            if (!str(m.get("类型结构")).isBlank()) {
                sb.append("用电结构以 ").append(m.get("类型结构")).append(" 为主。");
            }
            sb.append("\n\n值得关注：");
            if (hvacRatio > 0) {
                sb.append("空调能耗占总用电 ").append(String.format("%.1f%%", hvacRatio))
                        .append("；");
            }
            sb.append("异常小时占比 ").append(String.format("%.2f%%", anomalyRate)).append("。");
            sb.append("\n\n建议优先核对峰值时段的运行时间表与设定值，"
                    + "非使用时段应关闭或降频运行，通常不需要改造硬件即可见效。");
        }
        sb.append("\n\n（当前为大模型不可用时的规则解读，数字均为实时聚合结果。）");
        return sb.toString();
    }

    // ================================================================ 校验与工具

    /**
     * 找出模型输出里「提示词中不存在」的数字。
     *
     * <p>≥1000 的大数必须精确匹配：1% 容差对 9 位数意味着数百万的偏差，
     * 实测模型把 614,125,848 写成 614,125,843 时会被放过。
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
                // 忽略
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
            if (v <= 31 && v == Math.floor(v)) {
                continue;
            }
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

    private Map<String, Object> toVo(CachedInsight ci, boolean cached) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", ci.text());
        out.put("mode", ci.mode());
        out.put("cached", cached);
        out.put("provider", ci.provider());
        out.put("model", ci.model());
        out.put("promptChars", ci.promptChars());
        out.put("focusBuildings", ci.focusBuildings() == null ? List.of() : ci.focusBuildings());
        out.put("generatedAt", java.time.LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(ci.generatedAtMs()), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        out.put("ttlSeconds", TTL_MS / 1000);
        return out;
    }

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

    private void putIfPositive(Map<String, Object> m, String key, Object v) {
        double d = toDouble(v);
        if (d > 0) {
            m.put(key, d);
        }
    }

    private String nz(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private String str(Object v) {
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
}

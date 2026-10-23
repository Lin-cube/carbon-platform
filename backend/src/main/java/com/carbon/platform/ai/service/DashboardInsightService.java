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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /**
     * 聚合查询线程池（固定 4 线程）。
     *
     * <p>【为什么并行】一次解读要跑 4 个 SQL：总量、峰谷、类型结构、异常建筑清单。
     * 串行执行实测约 350ms；并行后由最慢的一条决定（约 150ms），
     * 在主线程等待 LLM 的 1 秒多里，这 200ms 的节省是明显的。
     */
    private final ExecutorService sqlPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "insight-sql");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, CachedInsight> cache = new ConcurrentHashMap<>();

    private record CachedInsight(String text, String mode, String provider, String model,
                                 long generatedAtMs, int promptChars,
                                 List<Map<String, Object>> focusBuildings) {
    }

    // ================================================================ 提示词

    private static final String COMMON_RULES = """
            要求：
            1. 篇幅：看板/园区概览用 90~130 字、2 个自然段；
               单栋诊断用 220~320 字、3 个自然段（证据链需要展开）。
               不要用 Markdown 标题或列表符号。
            2. **第一段开头必须点明本次统计的范围**，例如
               「统计区间 2016-01-01 至 2016-12-31、全部园区」或
               「统计区间 …、Eagle 园区」，让读者一眼看出这份解读覆盖什么。
            3. 数字必须原样引用我给的写法（如 6.14 亿、55.4%），
               不要换算成精确值，不要补全位数，不要自己计算新的数字。
            4. 只能使用我给的数字，不得编造任何数据或单位。
            5. 某项指标为 0 或缺失时直接不提，不要解释缺失原因。
            6. 语气客观，不要用「非常」「极大地」这类夸张词。
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
            第一段说该建筑是否异常、异常多少小时、异常分布在哪段时间；
            第二段逐条解释归因证据（对比异常时段与正常时段的数值差异），
            说明证据如何支撑这个结论；第三段给处置建议并指出需要人工复核的点。

            关于日期：**不要在正文里复述统计区间的起止日期**（界面已单独展示，
            模型复述容易与「异常时间范围」混淆 —— 实测出现过把异常结束日
            2016-12-01 写成统计区间结束日 2016-12-31 的情况）。
            只有当你想描述异常分布时，才引用「异常时间范围」的日期。

            关于时段措辞：我给了「异常时间跨度天数」和「异常占区间比例」。
            只有当跨度占区间比例较小（例如低于 30%）时才可以说「集中」；
            如果异常几乎铺满整个区间，必须改说「全区间均有分布」，
            不要写成「集中在 1 月 18 日至 12 月 1 日」这种等于没说的句子。
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
                                       String siteId, String buildingId, String buildingType) {
        String sc = (scope == null || scope.isBlank()) ? "dashboard" : scope.trim();
        String key = sc + "|" + startDate + "|" + endDate + "|" + nz(siteId)
                + "|" + nz(buildingId) + "|" + nz(buildingType);
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
        if (buildingType != null && !buildingType.isBlank()) {
            stat.put("建筑类型", buildingType);
        }
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
                // 输出上限按场景区分：
                // - 看板/园区概览：200 token（约 130 字），追求快，一句话结论即可
                // - 单栋诊断：520 token（约 330 字），需要展开证据链，太短会像模板
                int maxTokens = "diagnosis".equals(sc) ? 520 : 200;
                String raw = llmClient.chat(systemPrompt, userPrompt, 0.3, maxTokens);
                if (raw != null && !raw.isBlank()) {
                    // 数字一致性校验：输出里出现的每个数字都要能在提示词里找到
                    List<String> badDates = findUnknownDates(raw, userPrompt, startDate, endDate);
                    if (!badDates.isEmpty()) {
                        log.warn("AI 解读改写了日期 {}（提示词中不存在），降级规则模板", badDates);
                        List<String> unknown = List.of();
                        if (!unknown.isEmpty()) { /* 不会进入 */ }
                    }
                    List<String> unknown = findUnknownNumbers(raw, userPrompt);
                    boolean dateOk = badDates.isEmpty();
                    if (dateOk && unknown.isEmpty()) {
                        text = raw;
                        mode = "llm";
                        log.info("AI 解读成功：scope={} provider={} model={} 输入{}字 耗时={}ms",
                                sc, llmClient.provider(), llmClient.model(),
                                userPrompt.length(), System.currentTimeMillis() - t0);
                    } else if (unknown.isEmpty()) {
                        log.warn("AI 解读日期校验未通过，降级规则模板");
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

    /**
     * 取数策略：<b>优先用预聚合表</b>。
     *
     * <h3>为什么必须这样</h3>
     * 最初直接聚合 {@code fact_energy_hourly}（599 万行）。该表按
     * {@code (stat_year, stat_month)} RANGE 分区，而查询谓词在 {@code ts} 上，
     * <b>拿不到分区裁剪</b> → 全表扫描。
     *
     * <p>实测：同一次年度汇总，fact 全表扫描 <b>1340ms</b>，
     * {@code agg_building_daily}（25 万行）仅 <b>40ms</b>，快 33 倍。
     * 一次解读要跑 3~4 个聚合，串起来就是 4~5 秒——这才是「AI 解读很慢」的真实原因，
     * 不是大模型慢（模型只需 0.9~1.2 秒）。
     *
     * <h3>唯一例外</h3>
     * diagnosis 场景需要「异常时段 vs 正常时段」的均值对比，预聚合表按天汇总
     * 无法区分单日内的异常/正常小时，因此仍查 fact；
     * 但该查询带 {@code building_id = ?} 走索引，单栋一年仅 8760 行，约 30ms。
     */
    private Map<String, Object> collectDashboardStats(String startDate, String endDate,
                                                      String siteId, String buildingId) {
        Map<String, Object> m = baseStats(startDate, endDate, siteId, buildingId, true);
        if (siteId != null && !siteId.isBlank()) {
            m.put("园区", siteId);
        }
        m.remove("建筑类型");
        return m;
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
        m.put("_diagnosis", Boolean.TRUE);   // 见 buildPrompt：诊断场景不输出统计区间
        if (buildingId == null || buildingId.isBlank()) {
            return m;
        }
        try {
            Map<String, Object> info = jdbcTemplate.queryForMap(
                    "SELECT building_type_cn AS t, area_m2 AS area, energy_grade AS grade, "
                            + "site_id AS site "
                            + "FROM dim_building WHERE building_id = ?", buildingId);
            // 【修复】原来这里不设置「园区」，buildPrompt 就会落到 else 分支写成
            // "园区：全部园区"——而这栋楼明明属于某个具体园区，属于事实错误。
            if (info.get("site") != null) {
                m.put("园区", info.get("site"));
            }
            m.put("建筑类型", info.get("t"));
            if (info.get("area") != null) {
                m.put("建筑面积", String.format("%.0f 平方米", toDouble(info.get("area"))));
            }
            m.put("能耗评级", info.get("grade"));
        } catch (Exception ignored) {
            // 档案缺失不影响解读
        }

        // 异常 vs 正常时段对比（走 building_id 索引，单栋一年 8760 行）
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
                String first = str(cmp.get("first_ts")).substring(0, 10);
                String last = str(cmp.get("last_ts")).substring(0, 10);
                m.put("异常时间范围", first + " 至 " + last);
                // 跨度天数：让模型能判断"是否真的集中"。
                // 原来只说"集中在 X 至 Y"，当异常遍布整年时会写成
                // "集中在 2016-01-18 至 2016-12-01"，等于没说还误导。
                try {
                    long days = java.time.temporal.ChronoUnit.DAYS.between(
                            LocalDate.parse(first), LocalDate.parse(last)) + 1;
                    m.put("异常时间跨度天数", days);
                    long totalDays = java.time.temporal.ChronoUnit.DAYS.between(
                            LocalDate.parse(startDate), LocalDate.parse(endDate)) + 1;
                    m.put("异常占区间比例",
                            String.format("%.0f%%", days * 100.0 / Math.max(totalDays, 1)));
                } catch (Exception ignored) {
                    // 日期解析失败不影响主流程
                }
            }
        } catch (Exception e) {
            log.debug("诊断对比取数失败：{}", e.toString());
        }
        return m;
    }

    /**
     * 公共统计：全部基于预聚合表。
     *
     * @param withFocus 是否附带异常建筑清单（看板与园区需要，诊断不需要）
     */
    private Map<String, Object> baseStats(String startDate, String endDate,
                                          String siteId, String buildingId, boolean withFocus) {
        Map<String, Object> m = new LinkedHashMap<>();
        LocalDate s = LocalDate.parse(startDate);
        LocalDate e = LocalDate.parse(endDate);
        m.put("区间", startDate + " 至 " + endDate);

        // agg_building_daily 的谓词用 stat_date（该表有 date 索引）
        StringBuilder where = new StringBuilder(" WHERE a.stat_date >= ? AND a.stat_date < ? ");
        List<Object> args = new ArrayList<>();
        args.add(s);
        args.add(e.plusDays(1));
        if (siteId != null && !siteId.isBlank()) {
            where.append(" AND a.site_id = ? ");
            args.add(siteId);
        }
        if (buildingId != null && !buildingId.isBlank()) {
            where.append(" AND a.building_id = ? ");
            args.add(buildingId);
        }

        final String from = " FROM agg_building_daily a "
                + "LEFT JOIN dim_building d ON d.building_id = a.building_id ";
        final String whereSql = where.toString();
        final Object[] params = args.toArray();

        // 四个查询并行
        var fTotal = CompletableFuture.supplyAsync(() -> jdbcTemplate.queryForMap("""
                SELECT COUNT(DISTINCT a.building_id) AS bld,
                       COALESCE(SUM(a.electricity_kwh), 0) AS elec,
                       COALESCE(SUM(a.hvac_kwh), 0)        AS hvac,
                       COALESCE(SUM(a.water_m3), 0)        AS water,
                       COALESCE(SUM(a.carbon_kg), 0)       AS carbon,
                       COALESCE(SUM(a.anomaly_hours), 0)   AS anomaly_hours,
                       COALESCE(SUM(a.quality_hours), 0)   AS quality_hours
                """ + from + whereSql, params), sqlPool);

        // 峰谷：无园区过滤走 agg_time_hourly，有园区走 agg_site_hourly（该表带 site_id）
        var fHours = CompletableFuture.supplyAsync(() -> {
            if (siteId != null && !siteId.isBlank()) {
                return jdbcTemplate.queryForList(
                        "SELECT stat_hour AS h, SUM(electricity_kwh) AS v "
                                + "FROM agg_site_hourly WHERE site_id = ? "
                                + "AND stat_date >= ? AND stat_date < ? "
                                + "GROUP BY stat_hour ORDER BY v DESC",
                        siteId, s, e.plusDays(1));
            }
            return jdbcTemplate.queryForList(
                    "SELECT stat_hour AS h, SUM(electricity_kwh) AS v "
                            + "FROM agg_time_hourly WHERE stat_date >= ? AND stat_date < ? "
                            + "GROUP BY stat_hour ORDER BY v DESC", s, e.plusDays(1));
        }, sqlPool);

        var fTypes = CompletableFuture.supplyAsync(() -> jdbcTemplate.queryForList("""
                SELECT d.building_type_cn AS t, SUM(a.electricity_kwh) AS v
                """ + from + whereSql + " AND d.building_type_cn IS NOT NULL"
                + " GROUP BY d.building_type_cn ORDER BY v DESC LIMIT 3", params), sqlPool);

        var fFocus = withFocus
                ? CompletableFuture.supplyAsync(
                        () -> focusBuildings(s, e, siteId, buildingId), sqlPool)
                : CompletableFuture.completedFuture(List.<Map<String, Object>>of());

        Map<String, Object> total = fTotal.join();
        // 用「评估小时数」近似总小时：预聚合表没有 count(*)，用建筑数×天数不合适，
        // 这里以 quality_hours + anomaly_hours 不足时退回 building*24*天数
        long bld = (long) toDouble(total.get("bld"));
        long days = java.time.temporal.ChronoUnit.DAYS.between(s, e.plusDays(1));
        total.put("hours", bld * 24 * days);
        m.putAll(total);

        List<Map<String, Object>> hours = fHours.join();
        if (!hours.isEmpty()) {
            m.put("峰值时刻", hours.get(0).get("h") + " 时");
            m.put("峰值电量", hours.get(0).get("v"));
            var last = hours.get(hours.size() - 1);
            m.put("谷值时刻", last.get("h") + " 时");
            m.put("谷值电量", last.get("v"));
        }

        List<Map<String, Object>> types = fTypes.join();
        double elec = toDouble(total.get("elec"));
        List<String> typeLines = new ArrayList<>();
        for (Map<String, Object> t : types) {
            double v = toDouble(t.get("v"));
            String pct = elec > 0 ? String.format("%.1f%%", v / elec * 100) : "-";
            typeLines.add(t.get("t") + " " + pct);
        }
        m.put("类型结构", String.join("、", typeLines));

        if (withFocus) {
            m.put("_focusBuildings", fFocus.join());
        }
        return m;
    }

    /** 异常建筑清单：基于 agg_building_daily 的 anomaly_hours 汇总 */
    private List<Map<String, Object>> focusBuildings(LocalDate s, LocalDate e,
                                                     String siteId, String buildingId) {
        StringBuilder where = new StringBuilder(
                " WHERE a.anomaly_hours > 0 AND a.stat_date >= ? AND a.stat_date < ? ");
        List<Object> args = new ArrayList<>();
        args.add(s);
        args.add(e.plusDays(1));
        if (siteId != null && !siteId.isBlank()) {
            where.append(" AND a.site_id = ? ");
            args.add(siteId);
        }
        if (buildingId != null && !buildingId.isBlank()) {
            where.append(" AND a.building_id = ? ");
            args.add(buildingId);
        }
        try {
            return jdbcTemplate.query("""
                    SELECT a.building_id AS buildingId,
                           d.building_type_cn AS buildingTypeCn,
                           SUM(a.anomaly_hours) AS anomalyHours,
                           COALESCE(d.energy_grade, '-') AS energyGrade
                    FROM agg_building_daily a
                    LEFT JOIN dim_building d ON d.building_id = a.building_id
                    """ + where
                    + " GROUP BY a.building_id, d.building_type_cn, d.energy_grade"
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
        } else {
            sb.append("园区：全部园区\n");
        }
        if (m.get("建筑类型") != null) {
            sb.append("建筑类型筛选：").append(m.get("建筑类型")).append("\n");
        }
        // 诊断场景不把「统计区间」放进提示词：
        // 模型会把它与「异常时间范围」混淆（实测把异常结束日当成区间结束日）。
        // 区间信息由前端卡片单独展示，正文不需要复述。
        boolean diagnosisScope = m.get("_diagnosis") != null;
        if (!diagnosisScope) {
            sb.append("统计区间：").append(m.get("区间")).append("\n");
        }
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
        // 空调能耗为 0 时不要写进提示词：
        // 0 表示"原始数据没有冷暖分项表计"，不是"这栋楼不用空调"。
        // 写进提示词会让模型得出「该建筑无空调能耗」的错误结论。
        double hvacVal = toDouble(m.get("hvac"));
        if (hvacVal > 0) {
            sb.append("空调能耗：").append(shortNum(hvacVal, "kWh")).append("\n");
        }
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
     * 找出正文里「提示词没给过」的日期。
     *
     * <p>【为什么需要单独的日期校验】数字校验只拦 ≥1000 的数，两位数一律放过；
     * 而日期恰好由两位数字组成。实测模型会把 {@code 2016-12-31} 写成
     * {@code 2016-12-01}，数字校验完全看不见，读者却会以为统计区间变了。
     *
     * <p>判定很保守：正文里出现的每个 yyyy-MM-dd 必须在提示词原文里出现过。
     */
    private List<String> findUnknownDates(String text, String prompt, String... allowed) {
        List<String> bad = new ArrayList<>();
        if (text == null) return bad;
        Set<String> ok = new HashSet<>(List.of(allowed));
        Matcher m = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(text);
        Set<String> seen = new HashSet<>();
        while (m.find()) {
            String d = m.group();
            if (!seen.add(d)) continue;
            // 提示词里出现过，或本身就是本次查询区间的起止日，都算合法。
            // 诊断场景为了不让模型混淆，特意没把统计区间写进提示词，
            // 但模型提到"2016-01-01 和 2016-12-31"是完全正确的，不能当成编造。
            if (ok.contains(d)) continue;
            if (prompt != null && prompt.contains(d)) continue;
            bad.add(d);
        }
        return bad;
    }

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
            // 【分级容忍】
            // - 个位数（含 1.8 这类比例）：放行。模型由 91.1÷50.9 推导出「1.8 倍」
            //   属于正当推理，不是编造；卡住它只会频繁降级成规则模板，反而更差。
            // - 10 ~ 999：要求与提示词中的某个数 1% 内匹配。
            // - ≥1000：必须精确匹配（这类数字写错才是致命问题）。
            // - ≤31 的整数：日期/时段等，直接放行。
            if (v < 10) {
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

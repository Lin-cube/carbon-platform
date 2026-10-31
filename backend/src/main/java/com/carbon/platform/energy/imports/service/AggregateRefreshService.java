package com.carbon.platform.energy.imports.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导入后的预聚合表增量刷新。
 *
 * <h3>为什么必须有这个类</h3>
 * 系统的查询分两条路径：
 * <ul>
 *   <li><b>明细</b>（小时粒度）→ 读 {@code fact_energy_hourly}</li>
 *   <li><b>聚合</b>（日/月粒度、看板 KPI、园区/类型分解）→ 读 4 张预聚合表</li>
 * </ul>
 * 而导入只写事实表。因此<b>导入完成后如果不刷新预聚合表，会出现这样的怪现象</b>：
 * <pre>
 *   建筑列表：能看到新建筑 ✅
 *   小时粒度查询：能查到数据 ✅
 *   日粒度查询：查不到 ❌
 *   看板 KPI / 趋势图 / 园区分解：看不到 ❌   ← 用户最直观的界面
 *   3D 园区：楼栋能渲染，但能耗评级为空 ⚠️
 * </pre>
 * 换句话说：数据进了库，但用户在"看板"这个最主要的界面上看不到它。
 * 这是实测发现的功能缺口，不是性能问题。
 *
 * <h3>为什么是增量而不是全量重建</h3>
 * 全量重建要扫 600 万行，约 20 秒以上，用户点完"确认导入"要等很久。
 * 而一次导入通常只涉及少量建筑与少量日期，按 (建筑, 日期范围) 局部重算即可。
 * 实测导入 4 行数据的刷新耗时在毫秒级。
 *
 * <h3>四张表各自的刷新粒度</h3>
 * <table border="1">
 *   <tr><th>表</th><th>粒度</th><th>刷新方式</th></tr>
 *   <tr><td>{@code agg_building_daily}</td><td>建筑 × 日</td><td>按受影响建筑+日期重算</td></tr>
 *   <tr><td>{@code agg_building_monthly}</td><td>建筑 × 月</td><td>按受影响建筑+月份重算</td></tr>
 *   <tr><td>{@code agg_site_hourly}</td><td>园区 × 日 × 时</td><td>按受影响园区+日期重算</td></tr>
 *   <tr><td>{@code agg_time_hourly}</td><td>日期 × 时（全局）</td><td>按受影响日期重算（需跨全部建筑求和）</td></tr>
 * </table>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AggregateRefreshService {

    /** 评级所需的最小数据覆盖时长（小时）：30 天。低于此值年化 EUI 不可信 */
    private static final double MIN_GRADE_HOURS = 24 * 30;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 按本次导入涉及的建筑与日期范围，增量刷新 4 张预聚合表。
     *
     * @param buildings 受影响的建筑编号集合
     * @param minDate   涉及的最早日期
     * @param maxDate   涉及的最晚日期
     * @return 各表刷新行数的说明，用于审计日志与前端展示
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Map<String, Object> refresh(Set<String> buildings, LocalDate minDate, LocalDate maxDate) {
        Map<String, Object> report = new LinkedHashMap<>();
        if (buildings == null || buildings.isEmpty() || minDate == null || maxDate == null) {
            report.put("skipped", true);
            report.put("reason", "无受影响建筑或日期范围");
            return report;
        }

        long t0 = System.currentTimeMillis();
        // 园区集合需要从维表查（事实表里 site_id 可能为空，以维表为准）
        Set<String> sites = querySites(buildings);

        try {
            report.put("daily", refreshDaily(buildings, minDate, maxDate));
            report.put("monthly", refreshMonthly(buildings, minDate, maxDate));
            if (!sites.isEmpty()) {
                report.put("siteHourly", refreshSiteHourly(sites, minDate, maxDate));
            }
            // 全局小时表需要跨所有建筑重算受影响日期，放最后
            report.put("timeHourly", refreshTimeHourly(minDate, maxDate));
            report.put("elapsedMs", System.currentTimeMillis() - t0);
            report.put("buildings", buildings.size());
            report.put("dateRange", minDate + " ~ " + maxDate);
            log.info("预聚合表增量刷新完成：建筑 {} 栋，日期 {} ~ {}，耗时 {}ms",
                    buildings.size(), minDate, maxDate, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            // 刷新失败：4 张聚合表必须整体回滚，避免「日表更新了、月表还是旧的」这种撕裂。
            // 由调用方决定如何补偿（导入侧标记「派生数据待重算」，回滚侧忽略并继续）。
            report.put("error", e.getMessage());
            log.error("预聚合表刷新失败，本次聚合变更已整体回滚：{}", e.getMessage());
            throw new com.carbon.platform.common.exception.BizException(
                    com.carbon.platform.common.result.ResultCode.IMPORT_FAILED,
                    "预聚合刷新失败：" + e.getMessage());
        }
        return report;
    }

    // ================================================================ 内部实现

    private Set<String> querySites(Set<String> buildings) {
        Set<String> sites = new LinkedHashSet<>();
        try {
            String in = placeholders(buildings.size());
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT DISTINCT site_id FROM dim_building WHERE site_id IS NOT NULL "
                            + "AND building_id IN (" + in + ")",
                    String.class, buildings.toArray());
            sites.addAll(rows);
        } catch (Exception e) {
            log.warn("查询受影响园区失败：{}", e.getMessage());
        }
        return sites;
    }

    /** 建筑 × 日 */
    private int refreshDaily(Set<String> buildings, LocalDate from, LocalDate to) {
        String in = placeholders(buildings.size());
        Object[] base = buildings.toArray();
        jdbcTemplate.update(
                "DELETE FROM agg_building_daily WHERE stat_date BETWEEN ? AND ? "
                        + "AND building_id IN (" + in + ")",
                concat(new Object[]{java.sql.Date.valueOf(from), java.sql.Date.valueOf(to)}, base));
        return jdbcTemplate.update("""
                INSERT INTO agg_building_daily
                  (building_id, site_id, stat_date, stat_year, stat_month, is_weekend, season,
                   electricity_kwh, hvac_kwh, water_m3, carbon_kg, cop_avg, air_temp_avg,
                   humidity_avg, occupancy_avg, anomaly_hours, quality_hours, peak_hour_kwh)
                SELECT building_id, MAX(site_id), stat_date, stat_year, stat_month,
                       MAX(is_weekend), MAX(season),
                       SUM(electricity_kwh), SUM(hvac_kwh), SUM(water_m3), SUM(carbon_kg),
                       AVG(cop), AVG(air_temperature_c), AVG(humidity_pct), AVG(occupancy_density),
                       SUM(CASE WHEN anomaly_flag = 1 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN data_quality_flag = 1 THEN 1 ELSE 0 END),
                       MAX(electricity_kwh)
                FROM fact_energy_hourly
                WHERE %s
                  AND stat_date BETWEEN ? AND ? AND building_id IN (%s)
                GROUP BY building_id, stat_date, stat_year, stat_month
                """.formatted(partitionPredicate(from, to), in),
                concat(partitionParams(from, to, new Object[]{
                        java.sql.Date.valueOf(from), java.sql.Date.valueOf(to)}), base));
    }

    /** 建筑 × 月 */
    private int refreshMonthly(Set<String> buildings, LocalDate from, LocalDate to) {
        String in = placeholders(buildings.size());
        Object[] base = buildings.toArray();
        jdbcTemplate.update(
                "DELETE FROM agg_building_monthly WHERE " + partitionPredicate(from, to)
                        + " AND building_id IN (" + in + ")",
                concat(partitionParams(from, to, new Object[0]), base));
        // ★ 必须写入 building_type_cn：按建筑类型的分解查询
        //   （selectBreakdownByType）依赖本表该字段，缺了会导致
        //   「建筑类型能耗结构」全部落进 NULL 一个桶，图表显示灰色空环。
        return jdbcTemplate.update("""
                INSERT INTO agg_building_monthly
                  (building_id, site_id, building_type_cn, stat_year, stat_month,
                   electricity_kwh, hvac_kwh, water_m3, carbon_kg, cop_avg,
                   air_temp_avg, anomaly_hours, days)
                SELECT f.building_id, MAX(f.site_id), MAX(b.building_type_cn),
                       f.stat_year, f.stat_month,
                       SUM(f.electricity_kwh), SUM(f.hvac_kwh), SUM(f.water_m3), SUM(f.carbon_kg),
                       AVG(f.cop), AVG(f.air_temperature_c),
                       SUM(CASE WHEN f.anomaly_flag = 1 THEN 1 ELSE 0 END),
                       COUNT(DISTINCT f.stat_date)
                FROM fact_energy_hourly f
                LEFT JOIN dim_building b ON b.building_id = f.building_id
                WHERE %s
                  AND f.building_id IN (%s)
                GROUP BY f.building_id, f.stat_year, f.stat_month
                """.formatted(partitionPredicate(from, to), in),
                concat(partitionParams(from, to, new Object[0]), base));
    }

    /** 园区 × 日 × 时 */
    private int refreshSiteHourly(Set<String> sites, LocalDate from, LocalDate to) {
        String in = placeholders(sites.size());
        Object[] base = sites.toArray();
        jdbcTemplate.update(
                "DELETE FROM agg_site_hourly WHERE stat_date BETWEEN ? AND ? "
                        + "AND site_id IN (" + in + ")",
                concat(new Object[]{java.sql.Date.valueOf(from), java.sql.Date.valueOf(to)}, base));
        // 注：agg_site_hourly 未分区，删除无需分区谓词；下面从事实表重算时必须带
        return jdbcTemplate.update("""
                INSERT INTO agg_site_hourly
                  (site_id, stat_date, stat_hour, stat_year, stat_month, stat_weekday, is_weekend,
                   season, electricity_kwh, hvac_kwh, water_m3, carbon_kg, cop_sum, cop_cnt,
                   air_temp_sum, air_temp_cnt, anomaly_hours, quality_hours)
                SELECT site_id, stat_date, stat_hour, stat_year, stat_month, stat_weekday,
                       is_weekend, MAX(season),
                       SUM(electricity_kwh), SUM(hvac_kwh), SUM(water_m3), SUM(carbon_kg),
                       SUM(cop), COUNT(cop), SUM(air_temperature_c), COUNT(air_temperature_c),
                       SUM(CASE WHEN anomaly_flag = 1 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN data_quality_flag = 1 THEN 1 ELSE 0 END)
                FROM fact_energy_hourly
                WHERE %s
                  AND stat_date BETWEEN ? AND ? AND site_id IN (%s)
                GROUP BY site_id, stat_date, stat_hour, stat_year, stat_month,
                         stat_weekday, is_weekend
                """.formatted(partitionPredicate(from, to), in),
                concat(partitionParams(from, to, new Object[]{
                        java.sql.Date.valueOf(from), java.sql.Date.valueOf(to)}), base));
    }

    /** 日期 × 小时（全局，需跨全部建筑求和） */
    private int refreshTimeHourly(LocalDate from, LocalDate to) {
        jdbcTemplate.update(
                "DELETE FROM agg_time_hourly WHERE stat_date BETWEEN ? AND ?",
                java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        return jdbcTemplate.update("""
                INSERT INTO agg_time_hourly
                  (stat_date, stat_year, stat_month, stat_hour, stat_weekday, is_weekend, season,
                   electricity_kwh, hvac_kwh, water_m3, carbon_kg, cop_sum, cop_cnt,
                   temp_sum, temp_cnt, anomaly_hours, quality_hours)
                SELECT stat_date, stat_year, stat_month, stat_hour, stat_weekday, is_weekend,
                       MAX(season),
                       SUM(electricity_kwh), SUM(hvac_kwh), SUM(water_m3), SUM(carbon_kg),
                       SUM(cop), COUNT(cop), SUM(air_temperature_c), COUNT(air_temperature_c),
                       SUM(CASE WHEN anomaly_flag = 1 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN data_quality_flag = 1 THEN 1 ELSE 0 END)
                FROM fact_energy_hourly
                WHERE %s
                  AND stat_date BETWEEN ? AND ?
                GROUP BY stat_date, stat_year, stat_month, stat_hour, stat_weekday, is_weekend
                """.formatted(partitionPredicate(from, to)),
                partitionParams(from, to, new Object[]{
                        java.sql.Date.valueOf(from), java.sql.Date.valueOf(to)}));
    }

    /**
     * 为导入的建筑重算能耗评级（A–E）。
     *
     * <h3>为什么需要</h3>
     * 3D 园区的楼栋着色、建筑列表的评级标签、看板的「能耗评级分布」图
     * 都依赖 {@code dim_building.energy_grade}。导入的建筑若该项为 NULL，
     * 在 3D 里会显示为默认色、在看板评级分布里不计数 —— 用户会觉得"数据没进来"。
     *
     * <h3>算法与数据管道保持一致</h3>
     * 采用<b>同类型内分位数法</b>（与 {@code energy_lib.grade_energy_intensity} 相同）：
     * <ol>
     *   <li>计算该建筑的 EUI = 年电量 ÷ 建筑面积；</li>
     *   <li>取同建筑类型的全部已评级建筑 EUI 分布；</li>
     *   <li>按 0.2/0.4/0.6/0.8 分位切分为 A/B/C/D/E。</li>
     * </ol>
     * 不用绝对阈值，是因为不同业态（教学楼 vs 数据中心）能耗强度天然不同。
     *
     * <h3>边界处理</h3>
     * <ul>
     *   <li>无建筑面积 → 无法算 EUI → 保持 NULL（不臆造评级）；</li>
     *   <li>同类型样本少于 5 栋 → 分位数无统计意义 → 保持 NULL；</li>
     *   <li><b>数据覆盖不足 30 天 → 不评级</b>。原因：用 3 小时数据年化会得到
     *       荒谬的 EUI（实测 3 小时数据年化后 EUI=432，直接判为 E 级），
     *       这种评级是误导而非洞察。此时仍写入原始电量，但 EUI 与评级都留空。</li>
     * </ul>
     */
    public Map<String, Object> regrade(Set<String> buildings) {
        Map<String, Object> report = new LinkedHashMap<>();
        if (buildings == null || buildings.isEmpty()) {
            return report;
        }
        int graded = 0;
        int skippedNoArea = 0;
        int skippedSmallSample = 0;
        int skippedLowCoverage = 0;
        try {
            for (String bid : buildings) {
                Map<String, Object> row = null;
                try {
                    row = jdbcTemplate.queryForMap(
                            "SELECT b.building_type_cn, b.area_m2, "
                                    + "  (SELECT SUM(electricity_kwh) FROM fact_energy_hourly f "
                                    + "     WHERE f.building_id = b.building_id) AS kwh, "
                                    + "  (SELECT COUNT(*) FROM fact_energy_hourly f "
                                    + "     WHERE f.building_id = b.building_id) AS hours "
                                    + "FROM dim_building b WHERE b.building_id = ?", bid);
                } catch (Exception ignore) {
                    continue;
                }
                if (row == null) {
                    continue;
                }
                Object areaObj = row.get("area_m2");
                Object typeObj = row.get("building_type_cn");
                Object kwhObj = row.get("kwh");
                Object hoursObj = row.get("hours");
                if (areaObj == null || kwhObj == null || hoursObj == null
                        || ((Number) hoursObj).doubleValue() <= 0) {
                    skippedNoArea++;
                    continue;
                }
                double area = ((Number) areaObj).doubleValue();
                if (area <= 0) {
                    skippedNoArea++;
                    continue;
                }
                String type = typeObj == null ? "未知" : typeObj.toString();
                double kwh = ((Number) kwhObj).doubleValue();
                double hours = ((Number) hoursObj).doubleValue();

                // 覆盖度门槛：不足 30 天（720 小时）不评级。
                // 实测教训：3 小时数据年化后 EUI=432，被判为 E 级，属误导。
                if (hours < MIN_GRADE_HOURS) {
                    skippedLowCoverage++;
                    jdbcTemplate.update(
                            "UPDATE dim_building SET electricity_kwh_year = ?, "
                                    + "eui_kwh_m2_year = NULL, energy_grade = NULL "
                                    + "WHERE building_id = ?", round4(kwh), bid);
                    continue;
                }
                // 年化：按 8760 小时折算（数据不足一年时避免低估 EUI）
                double eui = kwh / area * (8760.0 / hours);

                // 取同类型其他建筑的 EUI 分布（用已算好的 eui_kwh_m2_year 字段）
                List<Double> peers = jdbcTemplate.queryForList(
                        "SELECT eui_kwh_m2_year FROM dim_building "
                                + "WHERE building_type_cn = ? AND eui_kwh_m2_year IS NOT NULL "
                                + "AND building_id <> ?",
                        Double.class, type, bid);
                if (peers.size() < 5) {
                    skippedSmallSample++;
                    // 仍写入 EUI，便于人工判断；评级保持 NULL
                    jdbcTemplate.update(
                            "UPDATE dim_building SET eui_kwh_m2_year = ?, "
                                    + "electricity_kwh_year = ? WHERE building_id = ?",
                            round4(eui), round4(kwh), bid);
                    continue;
                }
                peers.sort(Double::compareTo);
                String grade;
                if (eui <= quantile(peers, 0.2)) {
                    grade = "A";
                } else if (eui <= quantile(peers, 0.4)) {
                    grade = "B";
                } else if (eui <= quantile(peers, 0.6)) {
                    grade = "C";
                } else if (eui <= quantile(peers, 0.8)) {
                    grade = "D";
                } else {
                    grade = "E";
                }
                jdbcTemplate.update(
                        "UPDATE dim_building SET eui_kwh_m2_year = ?, electricity_kwh_year = ?, "
                                + "energy_grade = ? WHERE building_id = ?",
                        round4(eui), round4(kwh), grade, bid);
                graded++;
            }
            report.put("graded", graded);
            report.put("skippedNoArea", skippedNoArea);
            report.put("skippedSmallSample", skippedSmallSample);
            report.put("skippedLowCoverage", skippedLowCoverage);
        } catch (Exception e) {
            log.error("重算能耗评级失败（不影响导入）", e);
            report.put("error", e.getMessage());
        }
        return report;
    }

    private double quantile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        double pos = q * (sorted.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) {
            return sorted.get(lo);
        }
        return sorted.get(lo) + (pos - lo) * (sorted.get(hi) - sorted.get(lo));
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    // ================================================================ 工具

    /**
     * 生成分区裁剪谓词。
     *
     * <p><b>这是本项目性能上最容易踩的坑之一</b>：{@code fact_energy_hourly} 按
     * {@code (stat_year, stat_month)} 做 RANGE 分区，MySQL 只有看到<b>分区列上的直接谓词</b>
     * 才会裁剪分区。实测对比：
     * <pre>
     *   WHERE stat_date BETWEEN '2018-01-01' AND '2018-01-01'
     *     → 扫描全部 19 个分区，981 ms
     *   WHERE stat_year=2018 AND stat_month=1 AND stat_date BETWEEN ...
     *     → 只扫 pmax 分区，0.27 ms（快 3600 倍）
     * </pre>
     * 特别注意：写成表达式 {@code stat_year*100+stat_month BETWEEN ? AND ?}
     * <b>不会</b>触发裁剪，必须是列级的 {@code stat_year = ?} / {@code stat_year BETWEEN ? AND ?}。
     *
     * <p>同一年内用 {@code stat_year = ? AND stat_month BETWEEN ? AND ?} 可裁到月份级；
     * 跨年时退化为 {@code stat_year BETWEEN ? AND ?}（裁到年级，仍比不裁好得多）。
     */
    private String partitionPredicate(LocalDate from, LocalDate to) {
        if (from.getYear() == to.getYear()) {
            return "stat_year = ? AND stat_month BETWEEN ? AND ?";
        }
        return "stat_year BETWEEN ? AND ?";
    }

    /** 与 {@link #partitionPredicate} 配套的参数 */
    private Object[] partitionParams(LocalDate from, LocalDate to, Object[] rest) {
        Object[] head = from.getYear() == to.getYear()
                ? new Object[]{from.getYear(), from.getMonthValue(), to.getMonthValue()}
                : new Object[]{from.getYear(), to.getYear()};
        return concat(head, rest);
    }

    private String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("?");
        }
        return sb.toString();
    }

    private Object[] concat(Object[] a, Object[] b) {
        Object[] out = new Object[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}

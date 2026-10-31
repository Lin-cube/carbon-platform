package com.carbon.platform.energy.imports.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 异常检测（Java 移植版）。
 *
 * <p>算法与 {@code data-pipeline/src/energy_lib.py} 的
 * {@code seasonal_baseline} + {@code detect_anomalies} 保持一致，
 * 参数取自 {@code data-pipeline/src/config.py} 的 {@code ANOMALY_CONFIG}：
 * <ul>
 *   <li>分层基线：按「星期几 × 小时」分组，回看 <b>52 周</b>同槽位历史，取中位数</li>
 *   <li>阈值：同槽位历史<b>残差的 0.99 分位</b>（比固定 z 倍数更稳健）</li>
 *   <li>回退：前几周无历史时，用 7 天居中滚动中位数 + 滚动分位</li>
 *   <li>显著性下限：被标记读数还须超过该建筑自身 P75，抑制近零负荷建筑的误报</li>
 * </ul>
 *
 * <p><b>为什么后端需要它</b>：原始实现只在离线 Python 管道里跑，
 * 导致「导入新数据后异常诊断永远是空的」。本服务在导入后对受影响建筑重算，
 * 使 导入 → 异常诊断 真正闭环。
 *
 * <p><b>口径</b>：
 * <ul>
 *   <li>{@code anomaly_flag} = 负值 或 显著偏高（与 Python 一致，偏低不置 1）</li>
 *   <li>{@code evaluated_hours} = flag ∈ {0,1} 的小时数（-1 表示未评估）</li>
 *   <li>新建筑无历史 → 基线为空 → flag=-1，不产生假异常</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDetectionService {

    // ── 与 data-pipeline/src/config.py 的 ANOMALY_CONFIG 对齐 ──
    private static final int BASELINE_WEEKS = 52;
    private static final double RESID_QUANTILE = 0.99;
    private static final int ROLLING_WINDOW_HOURS = 168;
    private static final double NEGATIVE_TOLERANCE = -1e-6;
    private static final double THR_FLOOR_RATIO = 0.001;
    private static final double THR_FLOOR_ABS = 1e-6;
    private static final double SIGNIFICANCE_QUANTILE = 0.75;
    private static final int QUALITY_WINDOW_HOURS = 24;
    private static final double MIN_VALID_RATIO = 0.95;
    private static final double FLATLINE_TOLERANCE = 1e-6;

    /** 回看窗口：52 周 + 1 周余量 */
    private static final int LOOKBACK_DAYS = BASELINE_WEEKS * 7 + 7;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 对给定建筑、给定日期区间重算异常。
     *
     * @param buildingIds 受影响建筑
     * @param start       起始日（含）
     * @param end         结束日（含）
     * @return 统计报告
     */
    public Map<String, Object> recompute(Set<String> buildingIds, LocalDate start, LocalDate end) {
        Map<String, Object> report = new LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        if (buildingIds == null || buildingIds.isEmpty() || start == null || end == null) {
            report.put("skipped", true);
            return report;
        }
        int evaluated = 0;
        int anomaly = 0;
        int events = 0;
        int done = 0;
        List<String> failed = new ArrayList<>();
        for (String bid : buildingIds) {
            try {
                int[] r = recomputeOne(bid, start, end);
                evaluated += r[0];
                anomaly += r[1];
                events += r[2];
                done++;
            } catch (Exception e) {
                log.warn("异常重算失败 building={}: {}", bid, e.getMessage());
                failed.add(bid);
            }
        }
        report.put("buildings", done);
        report.put("failed", failed);
        report.put("evaluatedHours", evaluated);
        report.put("anomalyHours", anomaly);
        report.put("events", events);
        report.put("dateRange", start + " ~ " + end);
        report.put("elapsedMs", System.currentTimeMillis() - t0);
        return report;
    }

    // ================================================================ 单栋重算

    /** @return {evaluatedHours, anomalyHours, eventCount} */
    private int[] recomputeOne(String bid, LocalDate start, LocalDate end) {
        LocalDate histStart = start.minusDays(LOOKBACK_DAYS);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT ts, electricity_kwh FROM fact_energy_hourly "
                        + "WHERE building_id = ? AND ts >= ? AND ts <= ? ORDER BY ts",
                bid, Timestamp.valueOf(histStart.atStartOfDay()),
                Timestamp.valueOf(end.atTime(23, 59, 59)));
        if (rows.isEmpty()) {
            return new int[]{0, 0, 0};
        }

        // ① 连续小时网格（与 Python 的 wide DataFrame 对齐，缺失小时留空）
        LocalDateTime first = toLdt(rows.get(0).get("ts")).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime last = toLdt(rows.get(rows.size() - 1).get("ts")).truncatedTo(ChronoUnit.HOURS);
        int n = (int) ChronoUnit.HOURS.between(first, last) + 1;
        if (n <= 0) {
            return new int[]{0, 0, 0};
        }
        double[] val = new double[n];
        boolean[] has = new boolean[n];
        Arrays.fill(val, Double.NaN);
        for (Map<String, Object> row : rows) {
            LocalDateTime ts = toLdt(row.get("ts")).truncatedTo(ChronoUnit.HOURS);
            int idx = (int) ChronoUnit.HOURS.between(first, ts);
            Object v = row.get("electricity_kwh");
            if (idx >= 0 && idx < n && v != null) {
                val[idx] = ((Number) v).doubleValue();
                has[idx] = true;
            }
        }

        int[] slot = new int[n];
        int[] week = new int[n];
        for (int i = 0; i < n; i++) {
            LocalDateTime ts = first.plusHours(i);
            // Python 的 dayofweek：周一=0；Java 的 getValue()：周一=1
            slot[i] = (ts.getDayOfWeek().getValue() - 1) * 24 + ts.getHour();
            week[i] = i / 168;   // 与 Python np.arange(n)//(7*24) 一致
        }

        // ② 分层基线 + 残差分位阈值
        double[] base = new double[n];
        double[] thr = new double[n];
        Arrays.fill(base, Double.NaN);
        Arrays.fill(thr, Double.NaN);

        Map<Integer, List<Integer>> bySlot = new HashMap<>();
        for (int i = 0; i < n; i++) {
            bySlot.computeIfAbsent(slot[i], k -> new ArrayList<>()).add(i);
        }
        for (List<Integer> group : bySlot.values()) {
            for (int j = 0; j < group.size(); j++) {
                int r = group.get(j);
                int wk = week[r];
                List<Double> prev = new ArrayList<>();
                for (int k = 0; k < j; k++) {
                    int idx = group.get(k);
                    int w = week[idx];
                    if (w >= wk - BASELINE_WEEKS && w < wk && has[idx]) {
                        prev.add(val[idx]);
                    }
                }
                if (prev.size() < 2) {
                    if (prev.size() == 1) {
                        base[r] = prev.get(0);
                    }
                    continue;
                }
                double[] arr = prev.stream().mapToDouble(Double::doubleValue).toArray();
                double med = quantile(arr.clone(), 0.5);
                base[r] = med;
                double[] resid = new double[arr.length];
                for (int k = 0; k < arr.length; k++) {
                    resid[k] = arr[k] - med;
                }
                thr[r] = quantile(resid, RESID_QUANTILE);
            }
        }

        // ③ 回退：7 天居中滚动中位数 + 滚动分位
        int minp = Math.min(24, Math.max(2, ROLLING_WINDOW_HOURS / 4));
        double[] fbBase = rollingMedian(val, has, ROLLING_WINDOW_HOURS, minp);
        double[] fbResid = new double[n];
        boolean[] hasFbResid = new boolean[n];
        Arrays.fill(fbResid, Double.NaN);
        for (int i = 0; i < n; i++) {
            if (has[i] && !Double.isNaN(fbBase[i])) {
                fbResid[i] = Math.abs(val[i] - fbBase[i]);
                hasFbResid[i] = true;
            }
        }
        double[] fbThr = rollingQuantile(fbResid, hasFbResid, ROLLING_WINDOW_HOURS, minp, RESID_QUANTILE);
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(base[i]) && !Double.isNaN(fbBase[i])) {
                base[i] = fbBase[i];
            }
            if (Double.isNaN(thr[i]) && !Double.isNaN(fbThr[i])) {
                thr[i] = fbThr[i];
            }
        }

        // ④ 阈值下限（防退化）
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(base[i])) {
                continue;
            }
            double floor = Math.max(Math.abs(base[i]) * THR_FLOOR_RATIO, THR_FLOOR_ABS);
            if (Double.isNaN(thr[i]) || thr[i] < floor) {
                thr[i] = floor;
            }
        }

        // ⑤ 显著性下限：须超过该建筑自身 P75
        double p75 = percentileOfNonNaN(val, has, SIGNIFICANCE_QUANTILE);
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(base[i])) {
                continue;
            }
            double sigFloor = Math.max(0, p75 - base[i]);
            if (Double.isNaN(thr[i]) || thr[i] < sigFloor) {
                thr[i] = sigFloor;
            }
        }

        // ⑥ 判定并写回（只写 [start, end] 内的小时）
        int evaluated = 0;
        int anomaly = 0;
        List<Object[]> updates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            LocalDateTime ts = first.plusHours(i);
            LocalDate d = ts.toLocalDate();
            if (d.isBefore(start) || d.isAfter(end)) {
                continue;
            }
            if (!has[i] || Double.isNaN(base[i]) || Double.isNaN(thr[i])) {
                updates.add(new Object[]{-1, null, null, null, null, 0, "正常",
                        bid, Timestamp.valueOf(ts)});
                continue;
            }
            double resid = val[i] - base[i];
            double z = thr[i] == 0 ? 0 : resid / thr[i];
            boolean neg = val[i] < NEGATIVE_TOLERANCE;
            boolean zHigh = resid > thr[i];
            boolean zLow = resid < -thr[i] && val[i] > 0;

            int flag = (neg || zHigh) ? 1 : 0;
            String type = neg ? "负值异常" : (zHigh ? "能耗偏高" : (zLow ? "能耗偏低" : "正常"));
            String sev = "无";
            if (Math.abs(z) > 1.0) {
                sev = "低";
            }
            if (Math.abs(z) > 2.0) {
                sev = "中";
            }
            if (neg) {
                sev = "高";
            }
            int dq = dataQualityFlag(val, has, i, p75);
            String dqt = dq == 1 ? "表计卡死/数据缺失" : "正常";

            evaluated++;
            if (flag == 1) {
                anomaly++;
            }
            updates.add(new Object[]{flag, type, sev, round(base[i], 4), round(z, 3), dq, dqt,
                    bid, Timestamp.valueOf(ts)});
        }
        if (!updates.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    "UPDATE fact_energy_hourly SET anomaly_flag=?, anomaly_type=?, severity=?, "
                            + "baseline=?, zscore=?, data_quality_flag=?, data_quality_type=? "
                            + "WHERE building_id=? AND ts=?",
                    updates);
        }

        // ⑦ 先重建事件，再重建日聚合（日聚合要统计 event_count，顺序不能反）
        int events = rebuildEvents(bid, start, end);
        rebuildDailyAgg(bid, start, end);
        return new int[]{evaluated, anomaly, events};
    }

    // ================================================================ 聚合与事件重建

    /** 重建 agg_anomaly_daily；返回重建区间内的事件数 */
    private int rebuildDailyAgg(String bid, LocalDate start, LocalDate end) {
        String siteId = jdbcTemplate.query(
                "SELECT site_id FROM dim_building WHERE building_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, bid);

        jdbcTemplate.update(
                "DELETE FROM agg_anomaly_daily WHERE building_id = ? AND stat_date BETWEEN ? AND ?",
                bid, start, end);

        int eventTotal = 0;
        LocalDate d = start;
        while (!d.isAfter(end)) {
            Map<String, Object> agg = jdbcTemplate.queryForMap(
                    "SELECT "
                            + " SUM(CASE WHEN anomaly_flag >= 0 THEN 1 ELSE 0 END) AS eval, "
                            + " SUM(CASE WHEN anomaly_flag = 1 THEN 1 ELSE 0 END) AS anom, "
                            + " SUM(CASE WHEN data_quality_flag = 1 THEN 1 ELSE 0 END) AS qual "
                            + "FROM fact_energy_hourly WHERE building_id = ? AND ts >= ? AND ts < ?",
                    bid, Timestamp.valueOf(d.atStartOfDay()), Timestamp.valueOf(d.plusDays(1).atStartOfDay()));
            int eval = toInt(agg.get("eval"));
            int anom = toInt(agg.get("anom"));
            int qual = toInt(agg.get("qual"));
            int evCount = toInt(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM fact_anomaly_event WHERE building_id = ? "
                            + "AND start_ts >= ? AND start_ts < ?",
                    Integer.class, bid, Timestamp.valueOf(d.atStartOfDay()),
                    Timestamp.valueOf(d.plusDays(1).atStartOfDay())));
            eventTotal += evCount;
            jdbcTemplate.update(
                    "INSERT INTO agg_anomaly_daily(building_id, site_id, stat_date, stat_year, stat_month, "
                            + "evaluated_hours, anomaly_hours, quality_hours, event_count) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)",
                    bid, siteId, d, d.getYear(), d.getMonthValue(), eval, anom, qual, evCount);
            d = d.plusDays(1);
        }
        return eventTotal;
    }

    /** 用 anomaly_flag=1 的小时重建异常事件（连续小时合并为一次事件） */
    private int rebuildEvents(String bid, LocalDate start, LocalDate end) {
        LocalDate from = start.minusDays(1);
        LocalDate to = end.plusDays(1);
        String siteId = jdbcTemplate.query(
                "SELECT site_id FROM dim_building WHERE building_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, bid);

        jdbcTemplate.update(
                "DELETE FROM fact_anomaly_event WHERE building_id = ? AND start_ts >= ? AND start_ts < ?",
                bid, Timestamp.valueOf(from.atStartOfDay()), Timestamp.valueOf(to.plusDays(1).atStartOfDay()));

        List<Map<String, Object>> hits = jdbcTemplate.queryForList(
                "SELECT ts, electricity_kwh, baseline, anomaly_type, severity "
                        + "FROM fact_energy_hourly WHERE building_id = ? AND anomaly_flag = 1 "
                        + "AND ts >= ? AND ts < ? ORDER BY ts",
                bid, Timestamp.valueOf(from.atStartOfDay()),
                Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        if (hits.isEmpty()) {
            return 0;
        }

        List<Map<String, Object>> events = new ArrayList<>();
        List<Map<String, Object>> run = new ArrayList<>();
        LocalDateTime prevTs = null;
        for (Map<String, Object> row : hits) {
            LocalDateTime ts = toLdt(row.get("ts"));
            if (prevTs != null && ChronoUnit.HOURS.between(prevTs, ts) > 1) {
                events.add(buildEvent(bid, siteId, run));
                run = new ArrayList<>();
            }
            run.add(row);
            prevTs = ts;
        }
        if (!run.isEmpty()) {
            events.add(buildEvent(bid, siteId, run));
        }

        List<Object[]> batch = new ArrayList<>();
        for (Map<String, Object> e : events) {
            batch.add(new Object[]{e.get("buildingId"), e.get("siteId"), e.get("startTs"), e.get("endTs"),
                    e.get("duration"), e.get("type"), e.get("severity"), e.get("peak"),
                    e.get("baseline"), e.get("deviation"), 0});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO fact_anomaly_event(building_id, site_id, start_ts, end_ts, duration_hours, "
                        + "anomaly_type, severity, peak_value, baseline_value, deviation_pct, handled) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                batch);
        return batch.size();
    }

    private Map<String, Object> buildEvent(String bid, String siteId, List<Map<String, Object>> run) {
        LocalDateTime startTs = toLdt(run.get(0).get("ts"));
        LocalDateTime endTs = toLdt(run.get(run.size() - 1).get("ts"));

        double peak = Double.NEGATIVE_INFINITY;
        double peakBase = 0;
        Map<String, Integer> typeCount = new LinkedHashMap<>();
        String severity = "低";
        for (Map<String, Object> row : run) {
            double v = row.get("electricity_kwh") == null ? 0
                    : ((Number) row.get("electricity_kwh")).doubleValue();
            if (v > peak) {
                peak = v;
                peakBase = row.get("baseline") == null ? 0
                        : ((Number) row.get("baseline")).doubleValue();
            }
            String t = String.valueOf(row.get("anomaly_type"));
            typeCount.merge(t, 1, Integer::sum);
            severity = maxSeverity(severity, String.valueOf(row.get("severity")));
        }
        String type = typeCount.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("能耗偏高");
        double deviation = peakBase == 0 ? 0 : (peak - peakBase) / peakBase * 100.0;

        Map<String, Object> e = new LinkedHashMap<>();
        e.put("buildingId", bid);
        e.put("siteId", siteId);
        e.put("startTs", Timestamp.valueOf(startTs));
        e.put("endTs", Timestamp.valueOf(endTs));
        e.put("duration", (int) (ChronoUnit.HOURS.between(startTs, endTs) + 1));
        e.put("type", type);
        e.put("severity", severity);
        e.put("peak", round(peak, 4));
        e.put("baseline", round(peakBase, 4));
        e.put("deviation", round(deviation, 2));
        return e;
    }

    private String maxSeverity(String a, String b) {
        return severityRank(a) >= severityRank(b) ? a : b;
    }

    private int severityRank(String s) {
        if ("高".equals(s)) {
            return 3;
        }
        if ("中".equals(s)) {
            return 2;
        }
        if ("低".equals(s)) {
            return 1;
        }
        return 0;
    }

    // ================================================================ 统计工具

    /** 数据质量：24h 窗口内近乎恒定（表计卡死）或有效率不足 */
    private int dataQualityFlag(double[] val, boolean[] has, int i, double p75) {
        int from = Math.max(0, i - QUALITY_WINDOW_HOURS + 1);
        int cnt = 0;
        int valid = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int k = from; k <= i; k++) {
            cnt++;
            if (has[k]) {
                valid++;
                min = Math.min(min, val[k]);
                max = Math.max(max, val[k]);
            }
        }
        if (cnt < QUALITY_WINDOW_HOURS) {
            return 0;
        }
        double ratio = (double) valid / cnt;
        boolean frozen = valid >= QUALITY_WINDOW_HOURS && (max - min) <= FLATLINE_TOLERANCE
                && has[i] && val[i] > 0 && ratio >= MIN_VALID_RATIO;
        return frozen ? 1 : 0;
    }

    private double percentileOfNonNaN(double[] val, boolean[] has, double q) {
        List<Double> list = new ArrayList<>();
        for (int i = 0; i < val.length; i++) {
            if (has[i]) {
                list.add(val[i]);
            }
        }
        if (list.isEmpty()) {
            return 0;
        }
        double[] arr = list.stream().mapToDouble(Double::doubleValue).toArray();
        return quantile(arr, q);
    }

    /** 线性插值分位数（与 numpy.quantile 默认行为一致） */
    private double quantile(double[] arr, double q) {
        if (arr.length == 0) {
            return Double.NaN;
        }
        Arrays.sort(arr);
        if (arr.length == 1) {
            return arr[0];
        }
        double pos = q * (arr.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) {
            return arr[lo];
        }
        double frac = pos - lo;
        return arr[lo] * (1 - frac) + arr[hi] * frac;
    }

    /** 居中滚动中位数（窗口内忽略空值，有效值不足 minPeriods 则 NaN） */
    private double[] rollingMedian(double[] val, boolean[] has, int win, int minPeriods) {
        int n = val.length;
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        int half = win / 2;
        for (int i = 0; i < n; i++) {
            int from = Math.max(0, i - half);
            int to = Math.min(n - 1, i + half);
            List<Double> list = new ArrayList<>();
            for (int k = from; k <= to; k++) {
                if (has[k]) {
                    list.add(val[k]);
                }
            }
            if (list.size() >= minPeriods) {
                double[] arr = list.stream().mapToDouble(Double::doubleValue).toArray();
                out[i] = quantile(arr, 0.5);
            }
        }
        return out;
    }

    /** 居中滚动分位数 */
    private double[] rollingQuantile(double[] val, boolean[] has, int win, int minPeriods, double q) {
        int n = val.length;
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        int half = win / 2;
        for (int i = 0; i < n; i++) {
            int from = Math.max(0, i - half);
            int to = Math.min(n - 1, i + half);
            List<Double> list = new ArrayList<>();
            for (int k = from; k <= to; k++) {
                if (has[k]) {
                    list.add(val[k]);
                }
            }
            if (list.size() >= minPeriods) {
                double[] arr = list.stream().mapToDouble(Double::doubleValue).toArray();
                out[i] = quantile(arr, q);
            }
        }
        return out;
    }

    /** JDBC 驱动可能返回 LocalDateTime 或 Timestamp，统一转换 */
    private LocalDateTime toLdt(Object o) {
        if (o instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (o instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (o instanceof java.sql.Date d) {
            return d.toLocalDate().atStartOfDay();
        }
        return LocalDateTime.parse(String.valueOf(o).replace(' ', 'T'));
    }

    private int toInt(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private double round(double v, int scale) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0;
        }
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }
}

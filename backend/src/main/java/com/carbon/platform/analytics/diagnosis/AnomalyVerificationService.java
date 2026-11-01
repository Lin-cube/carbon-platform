package com.carbon.platform.analytics.diagnosis;

import com.carbon.platform.common.exception.BizException;
import com.carbon.platform.common.result.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 处置后复检：对比「处理前异常窗口」与「处理后同口径窗口」。
 *
 * <h3>可比性设计</h3>
 * 不直接拿处理后的全部数据去和处理前的异常小时比——那样几乎必然"改善"。
 * 这里保证两件事：
 * <ol>
 *   <li><b>同钟点窗口</b>：取原异常事件覆盖的钟点范围（如 13:00~05:00），
 *       处理前后都用这一组小时，避免拿白天比半夜；</li>
 *   <li><b>同长度窗口</b>：处理后的窗口天数 = 原异常事件跨度天数；</li>
 *   <li><b>温度可比性提示</b>：对比两窗口平均气温，差异 > 5℃ 时在结论里标注
 *       「气温差异较大，改善可能部分来自天气」，不把变化全部归因于处置措施。</li>
 * </ol>
 *
 * <h3>结论口径</h3>
 * <ul>
 *   <li>{@code IMPROVED}：处理后偏差回落到 20% 以内，且样本 ≥ 24 小时；</li>
 *   <li>{@code STILL_ANOMALY}：处理后偏差仍 > 20%；</li>
 *   <li>{@code INSUFFICIENT}：处理后样本不足 24 小时或没有数据——不判定已解决。</li>
 * </ul>
 *
 * <p>只陈述「观察到的变化」，不声称节省量由该处置措施引起（无因果证据）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyVerificationService {

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper json = new ObjectMapper();

    /** 处理后至少需要的小时数 */
    private static final int MIN_POST_HOURS = 24;
    /** 前后窗口最小天数：事件太短时也要有足够样本才能判改善 */
    private static final int MIN_WINDOW_DAYS = 14;
    /** 偏差回落到该比例以内视为改善 */
    private static final double IMPROVED_DEVIATION_PCT = 20.0;
    /** 前后气温差超过该值时提示不可比 */
    private static final double TEMP_DIFF_WARN = 5.0;

    public Map<String, Object> verify(long anomalyId, String operator) {
        Map<String, Object> ev = loadEvent(anomalyId);

        String buildingId = String.valueOf(ev.get("building_id"));
        LocalDateTime startTs = toLdt(ev.get("start_ts"));
        LocalDateTime endTs = toLdt(ev.get("end_ts"));
        int durationHours = ((Number) ev.get("duration_hours")).intValue();

        // 处理时间：优先用人工处置时间，其次用异常结束时间
        LocalDateTime handleAt = ev.get("handle_at") == null ? endTs : toLdt(ev.get("handle_at"));

        // ① 处理前窗口 = 异常事件本身
        LocalDate preStart = startTs.toLocalDate();
        LocalDate preEnd = endTs.toLocalDate();
        int hourFrom = startTs.getHour();
        int hourTo = endTs.getHour();
        boolean wrap = hourFrom > hourTo;   // 跨天事件：全天比较
        if (wrap) {
            hourFrom = 0;
            hourTo = 23;
        }

        // ② 处理前后各取「等长窗口」，长度 = max(事件跨度, 14 天)。
        //    踩过的坑：早期用「窗口长度 = 事件跨度」，一个 4 小时的事件
        //    处理后只有 4 小时样本，永远判不出结论（一直 INSUFFICIENT）。
        long spanDays = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(preStart, preEnd) + 1);
        int windowDays = (int) Math.max(spanDays, MIN_WINDOW_DAYS);

        // 处置时间可能被记成「今天」，而历史数据只到 2017 年：
        // 直接用会把复检窗口开到今天，取不到任何数据。
        LocalDate handleDate = handleAt.toLocalDate();
        String handleNote = null;
        try {
            LocalDate dataEnd = jdbcTemplate.queryForObject(
                    "SELECT MAX(stat_date) FROM fact_energy_hourly WHERE building_id = ?",
                    LocalDate.class, buildingId);
            if (dataEnd != null && handleDate.isAfter(dataEnd)) {
                handleDate = endTs.toLocalDate();
                handleNote = "处置时间 " + handleAt.toLocalDate()
                        + " 晚于该建筑数据范围（至 " + dataEnd + "），复检基准改用异常结束日 "
                        + endTs.toLocalDate() + "，结论针对历史区间";
            }
        } catch (Exception ignored) {
            // 取不到数据边界时按原处置时间处理
        }
        LocalDate preWinStart = handleDate.minusDays(windowDays);
        LocalDate preWinEnd = handleDate.minusDays(1);
        LocalDate postStart = handleDate.plusDays(1);
        LocalDate postEnd = handleDate.plusDays(windowDays);

        // 处理前窗口：处置日前 windowDays 天（含原异常），与处理后等长、同钟点
        Map<String, Object> pre = window(buildingId, preWinStart, preWinEnd, hourFrom, hourTo);
        Map<String, Object> post = window(buildingId, postStart, postEnd, hourFrom, hourTo);
        // 原异常事件本身的数值，仅作对照展示
        Map<String, Object> eventWin = window(buildingId, preStart, preEnd, hourFrom, hourTo);

        long preHours = lng(pre.get("hours"));
        long postHours = lng(post.get("hours"));

        double preAvg = dbl(pre.get("avg_kwh"));
        double preBase = dbl(pre.get("avg_baseline"));
        double postAvg = dbl(post.get("avg_kwh"));
        double postBase = dbl(post.get("avg_baseline"));

        BigDecimal preDev = deviation(preAvg, preBase);
        BigDecimal postDev = deviation(postAvg, postBase);
        BigDecimal changePct = preAvg > 0
                ? BigDecimal.valueOf((postAvg - preAvg) / preAvg * 100).setScale(2, RoundingMode.HALF_UP)
                : null;

        String conclusion;
        String reason;
        if (postHours < MIN_POST_HOURS || postBase <= 0) {
            conclusion = "INSUFFICIENT";
            reason = "处理后可用样本仅 " + postHours + " 小时（要求 ≥ " + MIN_POST_HOURS
                    + " 小时），无法确认是否改善";
        } else if (postDev != null && postDev.doubleValue() <= IMPROVED_DEVIATION_PCT) {
            conclusion = "IMPROVED";
            reason = String.format("处理后偏差回落至 %.2f%%（阈值 %.0f%%），观测到改善",
                    postDev.doubleValue(), IMPROVED_DEVIATION_PCT);
        } else {
            conclusion = "STILL_ANOMALY";
            reason = String.format("处理后偏差仍为 %.2f%%，超过 %.0f%% 阈值",
                    postDev == null ? 0 : postDev.doubleValue(), IMPROVED_DEVIATION_PCT);
        }

        // 可比性说明
        double preTemp = dbl(pre.get("avg_temp"));
        double postTemp = dbl(post.get("avg_temp"));
        List<String> caveats = new ArrayList<>();
        if (handleNote != null) {
            caveats.add(handleNote);
        }
        caveats.add("前后均取钟点 " + hourFrom + "~" + hourTo + " 时、各 " + windowDays
                + " 天，等长可比；原异常事件仅作对照");
        if (preTemp > 0 && postTemp > 0 && Math.abs(postTemp - preTemp) > TEMP_DIFF_WARN) {
            caveats.add(String.format("前后平均气温相差 %.1f℃（%.1f → %.1f），"
                    + "天气差异可能影响结果，不能把变化全部归因于处置措施",
                    Math.abs(postTemp - preTemp), preTemp, postTemp));
        }
        caveats.add("本结论只描述观测到的变化，未做因果推断，也不代表已核实的节能量");

        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("eventWindow", preStart + " ~ " + preEnd + "（原异常事件，仅供对照）");
        basis.put("eventAvgKwh", round(dbl(eventWin.get("avg_kwh"))));
        basis.put("eventDeviationPct", deviation(dbl(eventWin.get("avg_kwh")), dbl(eventWin.get("avg_baseline"))));
        basis.put("preWindow", preWinStart + " ~ " + preWinEnd + " 每日 " + hourFrom + "~" + hourTo + " 时");
        basis.put("postWindow", postStart + " ~ " + postEnd + " 每日 " + hourFrom + "~" + hourTo + " 时");
        basis.put("handleAt", handleAt.toString());
        basis.put("spanDays", spanDays);
        basis.put("preHours", preHours);
        basis.put("postHours", postHours);
        basis.put("preAvgTemp", preTemp);
        basis.put("postAvgTemp", postTemp);
        basis.put("preAnomalyHours", lng(pre.get("anomaly_hours")));
        basis.put("postAnomalyHours", lng(post.get("anomaly_hours")));
        basis.put("improvedThresholdPct", IMPROVED_DEVIATION_PCT);
        basis.put("caveats", caveats);

        long id = persist(anomalyId, buildingId, handleAt,
                preStart, preEnd, postStart, postEnd,
                preHours, preAvg, preBase, preDev,
                postHours, postAvg, postBase, postDev,
                changePct, conclusion, basis, operator);

        jdbcTemplate.update(
                "UPDATE fact_anomaly_event SET verify_status = '已复检', verify_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now()), anomalyId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("anomalyId", anomalyId);
        out.put("buildingId", buildingId);
        out.put("conclusion", conclusion);
        out.put("conclusionText", switch (conclusion) {
            case "IMPROVED" -> "已改善";
            case "STILL_ANOMALY" -> "仍存在异常";
            default -> "数据不足，无法确认";
        });
        out.put("reason", reason);
        out.put("preAvgKwh", round(preAvg));
        out.put("preBaseline", round(preBase));
        out.put("preDeviationPct", preDev);
        out.put("postAvgKwh", round(postAvg));
        out.put("postBaseline", round(postBase));
        out.put("postDeviationPct", postDev);
        out.put("changePct", changePct);
        out.put("basis", basis);
        return out;
    }

    /** 历史复检记录 */
    public List<Map<String, Object>> history(Long anomalyId, String buildingId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM anomaly_verification WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (anomalyId != null) {
            sql.append(" AND anomaly_id = ? ");
            args.add(anomalyId);
        }
        if (buildingId != null && !buildingId.isBlank()) {
            sql.append(" AND building_id = ? ");
            args.add(buildingId);
        }
        sql.append(" ORDER BY id DESC LIMIT ").append(Math.min(Math.max(limit, 1), 50));
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    // ================================================================ 内部

    private Map<String, Object> loadEvent(long anomalyId) {
        try {
            return jdbcTemplate.queryForMap(
                    "SELECT * FROM fact_anomaly_event WHERE id = ?", anomalyId);
        } catch (Exception e) {
            throw new BizException(ResultCode.NOT_FOUND, "异常事件不存在: " + anomalyId);
        }
    }

    /** 取某窗口内、指定钟点范围的真实聚合值 */
    private Map<String, Object> window(String buildingId, LocalDate from, LocalDate to,
                                       int hourFrom, int hourTo) {
        return jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS hours,
                       COALESCE(AVG(electricity_kwh), 0) AS avg_kwh,
                       COALESCE(AVG(baseline), 0)        AS avg_baseline,
                       COALESCE(AVG(air_temperature_c), 0) AS avg_temp,
                       COALESCE(SUM(CASE WHEN anomaly_flag = 1 THEN 1 ELSE 0 END), 0) AS anomaly_hours
                  FROM fact_energy_hourly
                 WHERE building_id = ? AND stat_date BETWEEN ? AND ?
                   AND stat_hour BETWEEN ? AND ?
                   AND electricity_kwh IS NOT NULL
                """, buildingId, from, to, hourFrom, hourTo);
    }

    private long persist(long anomalyId, String buildingId, LocalDateTime handleAt,
                         LocalDate preStart, LocalDate preEnd, LocalDate postStart, LocalDate postEnd,
                         long preHours, double preAvg, double preBase, BigDecimal preDev,
                         long postHours, double postAvg, double postBase, BigDecimal postDev,
                         BigDecimal changePct, String conclusion, Map<String, Object> basis,
                         String operator) {
        jdbcTemplate.update("""
                INSERT INTO anomaly_verification
                  (anomaly_id, building_id, handle_at, pre_start, pre_end, post_start, post_end,
                   pre_hours, pre_avg_kwh, pre_baseline, pre_deviation_pct,
                   post_hours, post_avg_kwh, post_baseline, post_deviation_pct,
                   change_pct, conclusion, basis_json, created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                anomalyId, buildingId, Timestamp.valueOf(handleAt),
                preStart, preEnd, postStart, postEnd,
                preHours, round(preAvg), round(preBase), preDev,
                postHours, round(postAvg), round(postBase), postDev,
                changePct, conclusion, writeJson(basis), operator);
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM anomaly_verification", Long.class);
        return id == null ? 0 : id;
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal deviation(double avg, double base) {
        if (base <= 0) {
            return null;
        }
        return BigDecimal.valueOf((avg - base) / base * 100).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private long lng(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private double dbl(Object o) {
        return o == null ? 0d : ((Number) o).doubleValue();
    }

    private LocalDateTime toLdt(Object o) {
        if (o instanceof LocalDateTime l) {
            return l;
        }
        if (o instanceof Timestamp t) {
            return t.toLocalDateTime();
        }
        if (o instanceof java.sql.Date d) {
            return d.toLocalDate().atStartOfDay();
        }
        return LocalDateTime.parse(String.valueOf(o).replace(' ', 'T'));
    }
}

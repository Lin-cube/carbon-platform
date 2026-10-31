package com.carbon.platform.analytics.service;

import com.carbon.platform.common.exception.BizException;
import com.carbon.platform.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异常人工处置服务：发现 → 诊断 → 人工处理 → 记录回写 → 历史追溯。
 *
 * <h3>状态机</h3>
 * <pre>
 *   未处理 ──→ 处理中 ──→ 已处理 ──→ 待复检 ──→ 已处理
 *      └──────────────→ 已忽略
 * </pre>
 * 允许跨状态修正（例如误标后改回），但「状态与备注都没变」的重复提交会被拒绝。
 *
 * <h3>与已有字段的关系</h3>
 * {@code handled} 仍是对外查询用的布尔口径，由 {@code handle_status} 派生：
 * 已处理 / 已忽略 / 待复检 → 1，其余 → 0。这样历史查询与统计口径完全不变。
 *
 * <h3>并发</h3>
 * 使用 {@code handle_version} 乐观锁：两个运维同时处理同一条异常时，
 * 后提交者会收到 409，提示刷新后重试，不会互相覆盖备注。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyHandleService {

    private final JdbcTemplate jdbcTemplate;

    /** 允许的处理状态 */
    public static final List<String> STATUSES =
            List.of("未处理", "处理中", "已处理", "已忽略", "待复检");

    /** 视为「已处置」的状态（与 handled=1 对齐） */
    private static final List<String> SETTLED = List.of("已处理", "已忽略", "待复检");

    private static final int NOTE_MAX = 512;

    private static final String EVENT_COLUMNS = """
            e.id, e.building_id AS buildingId, e.site_id AS siteId,
            DATE_FORMAT(e.start_ts, '%Y-%m-%d %H:%i') AS startTs,
            DATE_FORMAT(e.end_ts,   '%Y-%m-%d %H:%i') AS endTs,
            e.duration_hours AS durationHours, e.anomaly_type AS anomalyType,
            e.severity, e.peak_value AS peakValue, e.baseline_value AS baselineValue,
            e.deviation_pct AS deviationPct,
            e.handled, e.handle_note AS handleNote, e.handle_status AS handleStatus,
            e.handle_by AS handleBy,
            DATE_FORMAT(e.handle_at, '%Y-%m-%d %H:%i:%s') AS handleAt,
            e.verify_status AS verifyStatus, e.handle_version AS handleVersion
            """;

    // ================================================================ 查询

    /** 异常详情（含处理信息） */
    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT " + EVENT_COLUMNS + " FROM fact_anomaly_event e WHERE e.id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException(ResultCode.NOT_FOUND, "异常事件不存在: " + id);
        }
        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        out.put("history", history(id));
        out.put("allowedStatuses", STATUSES);
        return out;
    }

    /** 处理历史（按时间正序，便于展示处理链路） */
    public List<Map<String, Object>> history(long id) {
        return jdbcTemplate.queryForList("""
                SELECT id, action, from_status AS fromStatus, to_status AS toStatus,
                       note, operator,
                       DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt
                  FROM anomaly_handle_log
                 WHERE event_id = ?
                 ORDER BY id
                """, id);
    }

    /**
     * 历史处置案例（只读），供小E 在分析相似异常时参考真实处置经验。
     *
     * @param buildingId 可空：只看某栋楼
     * @param anomalyType 可空：只看某类异常
     */
    public List<Map<String, Object>> handledCases(String buildingId, String anomalyType, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.building_id AS buildingId, e.site_id AS siteId,
                       e.anomaly_type AS anomalyType, e.severity,
                       e.duration_hours AS durationHours,
                       DATE_FORMAT(e.start_ts, '%Y-%m-%d %H:%i') AS startTs,
                       e.handle_status AS handleStatus, e.handle_note AS handleNote,
                       e.handle_by AS handleBy,
                       DATE_FORMAT(e.handle_at, '%Y-%m-%d %H:%i') AS handleAt
                  FROM fact_anomaly_event e
                 WHERE e.handle_status IN ('已处理','已忽略')
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (buildingId != null && !buildingId.isBlank()) {
            sql.append(" AND e.building_id = ?");
            args.add(buildingId);
        }
        if (anomalyType != null && !anomalyType.isBlank()) {
            sql.append(" AND e.anomaly_type = ?");
            args.add(anomalyType);
        }
        int n = Math.max(1, Math.min(limit, 50));
        sql.append(" ORDER BY e.handle_at DESC LIMIT ").append(n);
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 某栋建筑的处理统计（未处理/处理中/已处理…） */
    public Map<String, Object> stats(String buildingId, java.time.LocalDate start, java.time.LocalDate end) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.handle_status AS handleStatus, COUNT(*) AS cnt
                  FROM fact_anomaly_event e
                 WHERE e.start_ts >= ? AND e.start_ts < DATE_ADD(?, INTERVAL 1 DAY)
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(java.sql.Date.valueOf(start));
        args.add(java.sql.Date.valueOf(end));
        if (buildingId != null && !buildingId.isBlank()) {
            sql.append(" AND e.building_id = ?");
            args.add(buildingId);
        }
        sql.append(" GROUP BY e.handle_status");
        Map<String, Object> out = new LinkedHashMap<>();
        long total = 0;
        for (Map<String, Object> r : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
            long c = ((Number) r.get("cnt")).longValue();
            out.put(String.valueOf(r.get("handleStatus")), c);
            total += c;
        }
        out.put("total", total);
        return out;
    }

    // ================================================================ 处置

    /**
     * 标记处理状态并回写备注（仅 admin / operator 可调用，由 Controller 的 @PreAuthorize 保证）。
     *
     * @param expectedVersion 客户端读到的 handle_version，用于乐观锁；为 null 时不做版本校验
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> handle(long id, String status, String note,
                                      Integer expectedVersion, String operator) {
        // ── 参数校验 ──
        if (status == null || !STATUSES.contains(status)) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "非法的处理状态：" + status + "，可选：" + String.join("/", STATUSES));
        }
        String trimmed = note == null ? "" : note.trim();
        if (trimmed.length() > NOTE_MAX) {
            throw new BizException(ResultCode.BAD_REQUEST, "处理备注不能超过 " + NOTE_MAX + " 字");
        }
        // 「已处理 / 已忽略」必须有备注：否则审计上无法解释为什么关闭
        if (SETTLED.contains(status) && trimmed.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "标记为「" + status + "」时必须填写处理备注");
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT handle_status AS handleStatus, handle_note AS handleNote,
                       handle_version AS handleVersion, handled
                  FROM fact_anomaly_event WHERE id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(ResultCode.NOT_FOUND, "异常事件不存在: " + id);
        }
        Map<String, Object> cur = rows.get(0);
        String fromStatus = String.valueOf(cur.get("handleStatus"));
        String curNote = cur.get("handleNote") == null ? "" : String.valueOf(cur.get("handleNote")).trim();
        int curVersion = ((Number) cur.get("handleVersion")).intValue();

        // ── 重复处理：状态与备注都没变 ──
        if (fromStatus.equals(status) && curNote.equals(trimmed)) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "该异常已处于「" + status + "」状态且备注未变化，无需重复处理");
        }
        // ── 乐观锁：别人已经改过 ──
        if (expectedVersion != null && expectedVersion != curVersion) {
            throw new BizException(ResultCode.CONFLICT,
                    "该异常已被其他人处理（当前版本 " + curVersion
                            + "，你读到的是 " + expectedVersion + "），请刷新后重试");
        }

        int handled = SETTLED.contains(status) ? 1 : 0;
        String where = expectedVersion != null ? " AND handle_version = ?" : "";
        Object[] args = expectedVersion != null
                ? new Object[]{status, trimmed.isEmpty() ? null : trimmed, operator,
                Timestamp.valueOf(LocalDateTime.now()), handled, id, expectedVersion}
                : new Object[]{status, trimmed.isEmpty() ? null : trimmed, operator,
                Timestamp.valueOf(LocalDateTime.now()), handled, id};

        int updated = jdbcTemplate.update("""
                UPDATE fact_anomaly_event
                   SET handle_status = ?, handle_note = ?, handle_by = ?, handle_at = ?,
                       handled = ?, handle_version = handle_version + 1
                 WHERE id = ?""" + where, args);
        if (updated == 0) {
            throw new BizException(ResultCode.CONFLICT,
                    "该异常已被其他人处理，请刷新后重试");
        }

        jdbcTemplate.update("""
                INSERT INTO anomaly_handle_log
                  (event_id, action, from_status, to_status, note, operator)
                VALUES (?,?,?,?,?,?)
                """, id, "HANDLE", fromStatus, status,
                trimmed.isEmpty() ? null : trimmed, operator);

        log.info("异常处置回写：event={} {} → {}，处理人={}", id, fromStatus, status, operator);
        return detail(id);
    }

    /** 由 SecurityUtils 提供操作人；抽成方法便于测试替换 */
    public String currentOperator() {
        return com.carbon.platform.common.util.SecurityUtils.currentUsername();
    }
}

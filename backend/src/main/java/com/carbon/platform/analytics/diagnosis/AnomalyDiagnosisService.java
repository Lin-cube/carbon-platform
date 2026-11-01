package com.carbon.platform.analytics.diagnosis;

import com.carbon.platform.ai.service.DashboardInsightService;
import com.carbon.platform.analytics.service.AnalyticsService;
import com.carbon.platform.common.exception.BizException;
import com.carbon.platform.common.result.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 诊断记录的生成、复用、失效与追溯。
 *
 * <h3>为什么要落库</h3>
 * 原来 AI 解读只存在于接口响应里，关掉页面就没了：
 * 无法追溯"上次诊断说了什么"，也无法与人工处置记录关联。
 *
 * <h3>缓存与复用</h3>
 * 同一建筑 + 同一区间 + 同一异常事件 + 数据指纹一致时，直接复用 30 天内的有效诊断，
 * 不重复消耗模型 token。数据一改（导入/回滚/重算），指纹变化即视为失效。
 *
 * <h3>数据指纹</h3>
 * {@code COUNT(*) + MAX(id) + SUM(electricity_kwh)}：轻量、能反映"数据变没变"，
 * 不需要对全表做哈希。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDiagnosisService {

    private final JdbcTemplate jdbcTemplate;
    private final AnalyticsService analyticsService;
    private final DashboardInsightService insightService;

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 复用窗口：30 天内同指纹的诊断视为有效 */
    private static final int REUSE_DAYS = 30;

    // ================================================================ 生成 / 复用

    /**
     * 生成或复用一条诊断。
     *
     * @param anomalyId 可空；为空表示对这一区间的整体诊断
     * @param force     true=强制重新调用模型（"重新分析"按钮）
     */
    public Map<String, Object> generate(String buildingId, Long anomalyId,
                                        LocalDate startDate, LocalDate endDate,
                                        boolean force, String operator) {
        assertBuildingExists(buildingId);
        if (startDate.isAfter(endDate)) {
            throw new BizException(ResultCode.BAD_REQUEST, "开始日期不能晚于结束日期");
        }
        String fingerprint = fingerprint(buildingId, startDate, endDate);

        if (!force) {
            Map<String, Object> cached = findReusable(buildingId, anomalyId, startDate, endDate, fingerprint);
            if (cached != null) {
                Map<String, Object> out = new LinkedHashMap<>(cached);
                out.put("reused", true);
                return out;
            }
        }

        // 取结构化证据
        Map<String, Object> diagnosis = analyticsService.diagnose(
                startDate, endDate, List.of(buildingId));
        boolean hasAnomaly = Boolean.TRUE.equals(diagnosis.get("hasAnomaly"));

        // 调用大模型（内部有 10 分钟缓存与数字守卫，失败会回退规则解读）
        Map<String, Object> insight = insightService.insight(
                "diagnosis", startDate.toString(), endDate.toString(),
                null, buildingId, null);
        // /ai/insight 返回的正文键是 text（不是 content），取错会存成空摘要
        Object body = insight.get("text");
        if (body == null) {
            body = insight.get("content");
        }
        String summary = body == null ? "" : String.valueOf(body);
        String mode = String.valueOf(insight.getOrDefault("mode", "rule"));
        String model = String.valueOf(insight.getOrDefault("model", ""));

        List<Map<String, Object>> attributions = castList(diagnosis.get("attributions"));
        List<Map<String, Object>> topEvents = castList(diagnosis.get("topEvents"));
        List<String> actions = new ArrayList<>();
        for (Map<String, Object> a : attributions) {
            Object s = a.get("suggestion");
            if (s != null && !String.valueOf(s).isBlank()) {
                actions.add(String.valueOf(s));
            }
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("anomalyVsNormal", diagnosis.get("anomalyVsNormal"));
        evidence.put("anomalyProfiles", diagnosis.get("anomalyProfiles"));
        evidence.put("efficiencyProfile", diagnosis.get("efficiencyProfile"));
        evidence.put("hasAnomaly", hasAnomaly);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("buildingId", buildingId);
        row.put("anomalyId", anomalyId);
        row.put("startDate", startDate.toString());
        row.put("endDate", endDate.toString());
        row.put("dataFingerprint", fingerprint);
        row.put("summary", summary);
        row.put("evidence", evidence);
        row.put("causes", attributions);
        row.put("actions", actions);
        row.put("topEvents", topEvents);
        row.put("model", model.isBlank() ? "deepseek" : model);
        row.put("mode", mode);
        row.put("status", "VALID");
        row.put("createdBy", operator);

        long id = persist(row);
        row.put("id", id);
        row.put("reused", false);
        return row;
    }

    // ================================================================ 查询

    public Map<String, Object> get(long id) {
        Map<String, Object> row = loadRow(id);
        return toDto(row);
    }

    public List<Map<String, Object>> history(String buildingId, Long anomalyId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM anomaly_diagnosis WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (buildingId != null && !buildingId.isBlank()) {
            sql.append(" AND building_id = ? ");
            args.add(buildingId);
        }
        if (anomalyId != null) {
            sql.append(" AND anomaly_id = ? ");
            args.add(anomalyId);
        }
        sql.append(" ORDER BY id DESC LIMIT ").append(Math.min(Math.max(limit, 1), 50));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(toDto(r));
        }
        return out;
    }

    // ================================================================ 失效

    /**
     * 数据变更后让相关诊断失效（导入、回滚、重算时调用）。
     * <p>只标记 INVALID，不删除——保留历史痕迹，前端展示时区分"已过期"。
     */
    public int invalidate(java.util.Set<String> buildings, LocalDate startDate,
                          LocalDate endDate, String reason) {
        if (buildings == null || buildings.isEmpty()) {
            return 0;
        }
        String in = String.join(",", java.util.Collections.nCopies(buildings.size(), "?"));
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(truncate(reason, 120));
        args.addAll(buildings);
        args.add(endDate);     // WHERE start_date <= endDate
        args.add(startDate);   // WHERE end_date   >= startDate
        return jdbcTemplate.update(
                "UPDATE anomaly_diagnosis SET status = 'INVALID', invalid_reason = ? "
                        + "WHERE status = 'VALID' AND building_id IN (" + in + ") "
                        + "AND start_date <= ? AND end_date >= ?",
                args.toArray());
    }

    // ================================================================ 内部

    private void assertBuildingExists(String buildingId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dim_building WHERE building_id = ?", Integer.class, buildingId);
        if (n == null || n == 0) {
            throw new BizException(ResultCode.BUILDING_NOT_FOUND, "建筑不存在: " + buildingId);
        }
    }

    private String fingerprint(String buildingId, LocalDate start, LocalDate end) {
        Map<String, Object> f = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS c, COALESCE(MAX(id),0) AS m,
                       COALESCE(ROUND(SUM(electricity_kwh), 1), 0) AS s
                  FROM fact_energy_hourly
                 WHERE building_id = ? AND stat_date BETWEEN ? AND ?
                """, buildingId, start, end);
        return f.get("c") + "-" + f.get("m") + "-" + f.get("s");
    }

    private Map<String, Object> findReusable(String buildingId, Long anomalyId,
                                             LocalDate start, LocalDate end, String fingerprint) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT * FROM anomaly_diagnosis
                     WHERE building_id = ? AND start_date = ? AND end_date = ?
                       AND status = 'VALID' AND data_fingerprint = ?
                       AND created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                       AND (? IS NULL OR anomaly_id = ?)
                     ORDER BY id DESC LIMIT 1
                    """, buildingId, start, end, fingerprint, REUSE_DAYS,
                    anomalyId, anomalyId);
            return rows.isEmpty() ? null : toDto(rows.get(0));
        } catch (Exception e) {
            return null;
        }
    }

    private long persist(Map<String, Object> row) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO anomaly_diagnosis
                      (building_id, anomaly_id, start_date, end_date, data_fingerprint,
                       summary, evidence_json, causes_json, actions_json, top_events_json,
                       model, mode, status, created_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    row.get("buildingId"), row.get("anomalyId"),
                    LocalDate.parse(String.valueOf(row.get("startDate"))),
                    LocalDate.parse(String.valueOf(row.get("endDate"))),
                    row.get("dataFingerprint"), row.get("summary"),
                    writeJson(row.get("evidence")), writeJson(row.get("causes")),
                    writeJson(row.get("actions")), writeJson(row.get("topEvents")),
                    row.get("model"), row.get("mode"),
                    row.getOrDefault("status", "VALID"), row.get("createdBy"));
            Long id = jdbcTemplate.queryForObject(
                    "SELECT MAX(id) FROM anomaly_diagnosis", Long.class);
            return id == null ? 0 : id;
        } catch (Exception e) {
            log.warn("诊断记录保存失败（不影响本次返回）：{}", e.getMessage());
            return 0;
        }
    }

    private Map<String, Object> loadRow(long id) {
        try {
            return jdbcTemplate.queryForMap("SELECT * FROM anomaly_diagnosis WHERE id = ?", id);
        } catch (Exception e) {
            throw new BizException(ResultCode.NOT_FOUND, "诊断记录不存在: " + id);
        }
    }

    private Map<String, Object> toDto(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("buildingId", row.get("building_id"));
        out.put("anomalyId", row.get("anomaly_id"));
        out.put("startDate", String.valueOf(row.get("start_date")));
        out.put("endDate", String.valueOf(row.get("end_date")));
        out.put("summary", row.get("summary"));
        out.put("evidence", readJson(row.get("evidence_json")));
        out.put("causes", readJson(row.get("causes_json")));
        out.put("actions", readJson(row.get("actions_json")));
        out.put("topEvents", readJson(row.get("top_events_json")));
        out.put("model", row.get("model"));
        out.put("mode", row.get("mode"));
        out.put("status", row.get("status"));
        out.put("invalidReason", row.get("invalid_reason"));
        out.put("createdBy", row.get("created_by"));
        out.put("createdAt", String.valueOf(row.get("created_at")));
        return out;
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private Object readJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return json.readValue(String.valueOf(o), Object.class);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object o) {
        if (o instanceof List<?> l) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object x : l) {
                if (x instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return List.of();
    }

    private String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}

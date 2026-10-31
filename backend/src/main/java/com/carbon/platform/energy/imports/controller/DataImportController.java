package com.carbon.platform.energy.imports.controller;

import com.carbon.platform.common.annotation.OperLog;
import com.carbon.platform.common.exception.BizException;
import com.carbon.platform.common.result.R;
import com.carbon.platform.common.result.ResultCode;
import com.carbon.platform.energy.imports.ImportTargetField;
import com.carbon.platform.energy.imports.dto.ImportRequest;
import com.carbon.platform.energy.imports.dto.ImportResultVO;
import com.carbon.platform.energy.imports.dto.UploadResultVO;
import com.carbon.platform.energy.imports.service.DataImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据导入与治理接口（五步向导）。
 *
 * <p>步骤：① 上传与列识别 → ② 字段映射（含自动建议）→ ③ 清洗规则 →
 * ④ 预览导入 → ⑤ 审计留痕查询。
 */
@Tag(name = "05-数据导入", description = "五步向导：数据源选择/字段映射/清洗规则/预览导入/审计留痕")
@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
public class DataImportController {

    private final DataImportService importService;
    private final JdbcTemplate jdbcTemplate;
    /** 回滚批次后需要刷新预聚合表，否则看板仍显示已删除的数据 */
    private final com.carbon.platform.energy.imports.service.AggregateRefreshService aggregateRefreshService;
    private final com.carbon.platform.energy.imports.service.ImportAuditExportService auditExportService;

    @Operation(summary = "步骤① 上传数据源并识别列（返回自动字段映射建议）")
    @PostMapping("/upload")
    @OperLog(title = "数据导入-上传", businessType = OperLog.BusinessType.IMPORT, saveParam = false)
    public R<UploadResultVO> upload(@RequestParam("file") MultipartFile file) {
        return R.ok(importService.upload(file));
    }

    @Operation(summary = "可映射的目标字段清单（前端下拉用）")
    @GetMapping("/target-fields")
    public R<List<Map<String, Object>>> targetFields() {
        List<Map<String, Object>> list = ImportTargetField.all().stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("column", f.getColumn());
            m.put("label", f.getLabel());
            m.put("required", f.isRequired());
            m.put("unit", f.getUnit());
            return m;
        }).toList();
        return R.ok(list);
    }

    @Operation(summary = "步骤③④ 提交清洗规则与字段映射（dryRun=true 仅预览）")
    @PostMapping("/execute")
    @OperLog(title = "数据导入-执行", businessType = OperLog.BusinessType.IMPORT)
    public R<ImportResultVO> execute(@Valid @RequestBody ImportRequest req) {
        return R.ok(importService.execute(req));
    }

    @Operation(summary = "步骤⑤ 导入审计留痕记录")
    @GetMapping("/logs")
    @OperLog(title = "数据导入-审计日志", businessType = OperLog.BusinessType.QUERY)
    public R<List<Map<String, Object>>> logs(@RequestParam(defaultValue = "50") int limit) {
        int n = Math.min(Math.max(limit, 1), 500);
        return R.ok(jdbcTemplate.queryForList("""
                SELECT l.id, l.batch_no AS batchNo, l.file_name AS fileName,
                       l.file_type AS fileType,
                       l.total_rows AS totalRows, l.success_rows AS successRows,
                       l.failed_rows AS failedRows, l.status,
                       l.dry_run AS dryRun,
                       l.operator,
                       DATE_FORMAT(l.created_at, '%Y-%m-%d %H:%i:%s') AS createdAt,
                       -- 库中现有行数：让「批次可追溯」在列表上即可验证
                       -- （导入后该值应等于成功行数；回滚后应变为 0）
                       (SELECT COUNT(*) FROM fact_energy_hourly f
                         WHERE f.import_batch_no = l.batch_no) AS rowsInFact
                FROM data_import_log l ORDER BY l.id DESC LIMIT ?
                """, n));
    }

    @Operation(summary = "步骤⑤ 审计记录详情（含字段映射与清洗规则）")
    @GetMapping("/logs/{id}")
    @OperLog(title = "数据导入-审计详情", businessType = OperLog.BusinessType.QUERY)
    public R<Map<String, Object>> logDetail(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, batch_no AS batchNo, file_name AS fileName, file_type AS fileType,
                       total_rows AS totalRows, success_rows AS successRows,
                       failed_rows AS failedRows, field_mapping AS fieldMapping,
                       clean_rules AS cleanRules, status, dry_run AS dryRun,
                       error_detail AS errorDetail,
                       operator, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt
                FROM data_import_log WHERE id = ?
                """, id);
        return R.ok(rows.isEmpty() ? Map.of() : rows.get(0));
    }

    @Operation(summary = "导出导入审计记录（Excel：导入批次 / 字段映射 / 清洗规则 三个 Sheet）")
    @GetMapping("/logs/export")
    @OperLog(title = "数据导入-审计记录导出", businessType = OperLog.BusinessType.EXPORT)
    public void exportLogs(@RequestParam(required = false) Integer limit,
                           HttpServletResponse response) throws java.io.IOException {
        auditExportService.export(response, limit);
    }

    // ================================================================
    // 批次追溯：按批次查数据 / 按批次回滚
    // ================================================================

    @Operation(summary = "批次追溯：本批次在事实表中实际存在的行数（含时间范围）")
    @GetMapping("/logs/{id}/trace")
    public R<Map<String, Object>> trace(@PathVariable Long id) {
        Map<String, Object> log = jdbcTemplate.queryForMap(
                "SELECT batch_no AS batchNo, status, dry_run AS dryRun FROM data_import_log WHERE id = ?", id);
        String batchNo = String.valueOf(log.get("batchNo"));

        Map<String, Object> out = new LinkedHashMap<>(log);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fact_energy_hourly WHERE import_batch_no = ?",
                Integer.class, batchNo);
        out.put("rowsInFact", rows == null ? 0 : rows);
        if (rows != null && rows > 0) {
            Map<String, Object> range = jdbcTemplate.queryForMap(
                    "SELECT MIN(ts) AS start, MAX(ts) AS end, "
                            + "COUNT(DISTINCT building_id) AS buildings "
                            + "FROM fact_energy_hourly WHERE import_batch_no = ?", batchNo);
            out.putAll(range);
        }
        return R.ok(out);
    }

    @Operation(summary = "批次追溯：查看本批次的部分数据行（最多 100 行）")
    @GetMapping("/logs/{id}/rows")
    public R<List<Map<String, Object>>> rows(@PathVariable Long id) {
        String batchNo = jdbcTemplate.queryForObject(
                "SELECT batch_no FROM data_import_log WHERE id = ?", String.class, id);
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT building_id AS buildingId, ts, electricity_kwh AS electricityKwh, "
                        + "hvac_kwh AS hvacKwh, water_m3 AS waterM3, air_temperature_c AS airTemperatureC "
                        + "FROM fact_energy_hourly WHERE import_batch_no = ? ORDER BY ts LIMIT 100",
                batchNo);
        return R.ok(list);
    }

    @Operation(summary = "按批次回滚：删除该批次写入的全部数据行，并刷新预聚合表")
    @PostMapping("/logs/{id}/rollback")
    public R<Map<String, Object>> rollback(@PathVariable Long id) {
        Map<String, Object> log = jdbcTemplate.queryForMap(
                "SELECT batch_no AS batchNo, dry_run AS dryRun, status FROM data_import_log WHERE id = ?", id);
        String batchNo = String.valueOf(log.get("batchNo"));
        if (isTrue(log.get("dryRun"))) {
            // 注：dry_run 是 TINYINT(1)，MySQL Connector/J 默认把它映射为 Boolean
            // （tinyInt1isBit=true）。早期写成 ((Number) x).intValue() 会抛
            // ClassCastException: Boolean cannot be cast to Number（实测踩过）。
            throw new BizException(ResultCode.BAD_REQUEST,
                    "该批次是「预览」批次，数据从未写入数据库，无需回滚");
        }

        // 记录受影响的建筑与时间范围，回滚后需要刷新预聚合表
        List<Map<String, Object>> affected = jdbcTemplate.queryForList(
                "SELECT DISTINCT building_id AS buildingId FROM fact_energy_hourly "
                        + "WHERE import_batch_no = ?", batchNo);
        Map<String, Object> range = null;
        try {
            range = jdbcTemplate.queryForMap(
                    "SELECT MIN(stat_date) AS minDate, MAX(stat_date) AS maxDate "
                            + "FROM fact_energy_hourly WHERE import_batch_no = ?", batchNo);
        } catch (Exception ignore) {
            // 无数据行
        }

        int deleted = jdbcTemplate.update(
                "DELETE FROM fact_energy_hourly WHERE import_batch_no = ?", batchNo);

        // 刷新预聚合（否则看板仍显示已删除的数据）
        java.util.Set<String> blds = new java.util.LinkedHashSet<>();
        for (Map<String, Object> m : affected) {
            Object b = m.get("buildingId");
            if (b != null) {
                blds.add(String.valueOf(b));
            }
        }
        Map<String, Object> aggReport = new LinkedHashMap<>();
        if (deleted > 0 && range != null && range.get("minDate") != null && !blds.isEmpty()) {
            java.time.LocalDate min = toLocalDate(range.get("minDate"));
            java.time.LocalDate max = toLocalDate(range.get("maxDate"));
            if (min != null && max != null) {
                aggReport = aggregateRefreshService.refresh(blds, min, max);
            }
        }

        // 清理本批次引入的异常聚合与事件（P0 重算新增的表，否则回滚后仍留全 0 行）
        if (range != null && range.get("minDate") != null && !blds.isEmpty()) {
            java.time.LocalDate min = toLocalDate(range.get("minDate"));
            java.time.LocalDate max = toLocalDate(range.get("maxDate"));
            if (min != null && max != null) {
                String in = placeholders(blds.size());
                Object[] base = blds.toArray();
                jdbcTemplate.update(
                        "DELETE FROM agg_anomaly_daily WHERE stat_date BETWEEN ? AND ? "
                                + "AND building_id IN (" + in + ")",
                        concat(new Object[]{java.sql.Date.valueOf(min), java.sql.Date.valueOf(max)}, base));
                jdbcTemplate.update(
                        "DELETE FROM fact_anomaly_event WHERE start_ts >= ? AND start_ts < ? "
                                + "AND building_id IN (" + in + ")",
                        concat(new Object[]{java.sql.Timestamp.valueOf(min.atStartOfDay()),
                                java.sql.Timestamp.valueOf(max.plusDays(1).atStartOfDay())}, base));
            }
        }

        // ★ 清理幽灵维表记录：本批次登记、且已无任何事实数据的建筑/园区。
        //   不清理的话，3D 园区与筛选下拉会残留一个没有数据的空建筑/空园区。
        if (!blds.isEmpty()) {
            String in = placeholders(blds.size());
            jdbcTemplate.update(
                    "DELETE FROM dim_building WHERE building_id IN (" + in + ") "
                            + "AND NOT EXISTS (SELECT 1 FROM fact_energy_hourly f "
                            + "WHERE f.building_id = dim_building.building_id)",
                    blds.toArray());
        }
        jdbcTemplate.update(
                "DELETE FROM dim_site WHERE NOT EXISTS "
                        + "(SELECT 1 FROM dim_building b WHERE b.site_id = dim_site.site_id)");

        // 审计：把该批次标记为已回滚（保留记录，不删除，符合审计要求）
        jdbcTemplate.update(
                "UPDATE data_import_log SET status = '已回滚' WHERE id = ?", id);
        healthControllerInvalidate();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchNo", batchNo);
        out.put("deletedRows", deleted);
        out.put("affectedBuildings", blds.size());
        out.put("aggregateRefresh", aggReport);
        return R.ok(out);
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

    /** 兼容 Boolean 与 Number 两种类型（TINYINT(1) 的驱动映射差异） */
    private boolean isTrue(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() == 1;
        }
        return false;
    }

    /** 把 MIN/MAX 日期结果转 LocalDate（驱动可能返回 Date 或 LocalDate） */
    private java.time.LocalDate toLocalDate(Object v) {
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (v instanceof java.time.LocalDate ld) {
            return ld;
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        }
        return null;
    }

    /** 回滚后失效看板概览缓存 */
    private void healthControllerInvalidate() {
        try {
            com.carbon.platform.common.controller.HealthController.invalidateOverviewCache();
        } catch (Exception ignore) {
            // 缓存失效失败不影响回滚
        }
    }
}

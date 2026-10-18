package com.carbon.platform.energy.imports.controller;

import com.carbon.platform.common.annotation.OperLog;
import com.carbon.platform.common.result.R;
import com.carbon.platform.energy.imports.ImportTargetField;
import com.carbon.platform.energy.imports.dto.ImportRequest;
import com.carbon.platform.energy.imports.dto.ImportResultVO;
import com.carbon.platform.energy.imports.dto.UploadResultVO;
import com.carbon.platform.energy.imports.service.DataImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
                SELECT id, batch_no AS batchNo, file_name AS fileName, file_type AS fileType,
                       total_rows AS totalRows, success_rows AS successRows,
                       failed_rows AS failedRows, status,
                       dry_run AS dryRun,
                       operator,
                       DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt
                FROM data_import_log ORDER BY id DESC LIMIT ?
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
}

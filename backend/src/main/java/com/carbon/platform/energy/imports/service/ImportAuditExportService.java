package com.carbon.platform.energy.imports.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.excel.write.metadata.style.WriteCellStyle;
import com.alibaba.excel.write.metadata.style.WriteFont;
import com.alibaba.excel.write.style.HorizontalCellStyleStrategy;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 导入审计记录导出。
 *
 * <h3>为什么需要</h3>
 * 审计留痕只存在数据库和界面上有几个问题：
 * <ol>
 *   <li>界面只在「导入向导第 5 步」可见，不导入就看不了历史；</li>
 *   <li>无法拷给他人（如给导师、给运维负责人）核对；</li>
 *   <li>审计场景通常需要**留档**，Excel 是最通用的交换格式。</li>
 * </ol>
 *
 * <h3>三个 Sheet</h3>
 * <ul>
 *   <li><b>导入批次</b>：每批次一行，含状态、是否入库、库中现有行数（可追溯性证据）；</li>
 *   <li><b>字段映射</b>：每批次的源列 → 目标字段对照（JSON 拆平成表格）；</li>
 *   <li><b>清洗规则</b>：每批次实际生效的清洗参数。</li>
 * </ul>
 *
 * <h3>与「批次追溯」的关系</h3>
 * 「库中行数」列由 {@code fact_energy_hourly.import_batch_no} 实时统计，
 * 因此导出的报表反映的是**导出那一刻**的真实数据状态：
 * 导入后等于成功行数，回滚后为 0，被其它方式删除后也会同步变化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportAuditExportService {

    private final JdbcTemplate jdbcTemplate;

    public void export(HttpServletResponse response, Integer limit) throws IOException {
        int n = (limit == null || limit <= 0) ? 500 : Math.min(limit, 5000);

        List<Map<String, Object>> logs = jdbcTemplate.queryForList("""
                SELECT l.id, l.batch_no, l.file_name, l.file_type,
                       l.total_rows, l.success_rows, l.failed_rows,
                       l.status, l.dry_run, l.operator,
                       DATE_FORMAT(l.created_at, '%Y-%m-%d %H:%i:%s') AS created_at,
                       l.field_mapping, l.clean_rules,
                       (SELECT COUNT(*) FROM fact_energy_hourly f
                         WHERE f.import_batch_no = l.batch_no) AS rows_in_fact
                FROM data_import_log l
                ORDER BY l.id DESC LIMIT ?
                """, n);

        // ---------------- Sheet 1 导入批次 ----------------
        List<List<String>> headBatch = List.of(
                List.of("批次号"), List.of("文件名"), List.of("类型"),
                List.of("总行数"), List.of("成功"), List.of("失败"),
                List.of("状态"), List.of("是否入库"), List.of("库中现有行数"),
                List.of("操作人"), List.of("导入时间"));
        List<List<Object>> rowsBatch = new ArrayList<>();
        for (Map<String, Object> r : logs) {
            boolean dry = isTrue(r.get("dry_run"));
            rowsBatch.add(List.of(
                    str(r.get("batch_no")), str(r.get("file_name")), str(r.get("file_type")),
                    num(r.get("total_rows")), num(r.get("success_rows")), num(r.get("failed_rows")),
                    str(r.get("status")),
                    dry ? "未入库（预览）" : "已入库",
                    dry ? "—" : num(r.get("rows_in_fact")),
                    str(r.get("operator")), str(r.get("created_at"))));
        }

        // ---------------- Sheet 2 字段映射 ----------------
        List<List<String>> headMapping = List.of(
                List.of("批次号"), List.of("源列名"), List.of("目标字段"));
        List<List<Object>> rowsMapping = new ArrayList<>();
        for (Map<String, Object> r : logs) {
            String batchNo = str(r.get("batch_no"));
            for (String[] pair : parseJsonMap(str(r.get("field_mapping")))) {
                rowsMapping.add(List.of(batchNo, pair[0], pair[1]));
            }
        }

        // ---------------- Sheet 3 清洗规则 ----------------
        List<List<String>> headRules = List.of(
                List.of("批次号"), List.of("清洗规则（原始 JSON）"));
        List<List<Object>> rowsRules = new ArrayList<>();
        for (Map<String, Object> r : logs) {
            rowsRules.add(List.of(str(r.get("batch_no")), str(r.get("clean_rules"))));
        }

        // ---------------- 写出 ----------------
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
        String rawName = "碳能智控_导入审计记录_" + ts + ".xlsx";
        String encoded = URLEncoder.encode(rawName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment;filename*=utf-8''" + encoded);

        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream())
                .registerWriteHandler(styleStrategy())
                .build()) {
            writer.write(rowsBatch, sheet(0, "导入批次", headBatch));
            writer.write(rowsMapping, sheet(1, "字段映射", headMapping));
            writer.write(rowsRules, sheet(2, "清洗规则", headRules));
            writer.finish();
        }
        log.info("导入审计记录导出完成: {} 个批次, {} 条映射", logs.size(), rowsMapping.size());
    }

    // ================================================================ 工具

    /** TINYINT(1) 可能被驱动映射成 Boolean，需兼容 */
    private boolean isTrue(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() == 1;
        }
        return false;
    }

    private String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private Object num(Object v) {
        return v == null ? 0 : v;
    }

    /**
     * 把 {"源列":"目标字段"} 这种 JSON 拆成 [源列, 目标字段] 对，
     * 供 Excel 表格展示（单元格里塞整段 JSON 不便阅读）。
     */
    private List<String[]> parseJsonMap(String json) {
        List<String[]> out = new ArrayList<>();
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return out;
        }
        String s = json.trim();
        if (s.startsWith("{")) {
            s = s.substring(1);
        }
        if (s.endsWith("}")) {
            s = s.substring(0, s.length() - 1);
        }
        // 简单解析（映射值都是简单字符串，不含嵌套对象）
        for (String part : s.split(",")) {
            String[] kv = part.split(":", 2);
            if (kv.length == 2) {
                out.add(new String[]{unquote(kv[0]), unquote(kv[1])});
            }
        }
        return out;
    }

    private String unquote(String v) {
        String t = v.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1);
        }
        return t.replace("\\\"", "\"");
    }

    private WriteSheet sheet(int index, String name, List<List<String>> head) {
        return EasyExcel.writerSheet(index, name).head(head).build();
    }

    private HorizontalCellStyleStrategy styleStrategy() {
        WriteCellStyle head = new WriteCellStyle();
        head.setFillPatternType(FillPatternType.SOLID_FOREGROUND);
        head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        head.setHorizontalAlignment(HorizontalAlignment.CENTER);
        head.setVerticalAlignment(VerticalAlignment.CENTER);
        head.setBorderBottom(BorderStyle.THIN);
        WriteFont font = new WriteFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        head.setWriteFont(font);

        WriteCellStyle body = new WriteCellStyle();
        body.setVerticalAlignment(VerticalAlignment.CENTER);
        body.setBorderBottom(BorderStyle.THIN);
        body.setBorderRight(BorderStyle.THIN);

        return new HorizontalCellStyleStrategy(head, body);
    }
}

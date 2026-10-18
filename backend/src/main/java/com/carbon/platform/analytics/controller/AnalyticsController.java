package com.carbon.platform.analytics.controller;

import com.carbon.platform.analytics.dto.AnomalyAnalysisVO;
import com.carbon.platform.analytics.dto.CopAnalysisVO;
import com.carbon.platform.analytics.dto.PeriodSummaryVO;
import com.carbon.platform.analytics.service.AnalyticsService;
import com.carbon.platform.analytics.service.ReportExportService;
import com.carbon.platform.common.annotation.OperLog;
import com.carbon.platform.common.exception.BizException;
import com.carbon.platform.common.result.R;
import com.carbon.platform.common.result.ResultCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 统计分析接口：申报表要求的 3 类核心统计分析 + MCP 工具 5-7。
 */
@Tag(name = "03-统计分析", description = "时段汇总 / COP 计算 / 异常分析（对应 MCP 工具 5-7）")
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final ReportExportService reportExportService;

    // ================================================================
    // 统计① 时段汇总
    // ================================================================
    @Operation(summary = "统计① 时段汇总（年/季/月/周/日 + 尖峰平谷分时）")
    @GetMapping("/period-summary")
    @OperLog(title = "统计-时段汇总", businessType = OperLog.BusinessType.QUERY)
    public R<PeriodSummaryVO> periodSummary(
            @RequestParam(defaultValue = "month") String periodType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) String siteId,
            @RequestParam(required = false) String buildingType) {
        return R.ok(analyticsService.periodSummary(periodType, startDate, endDate,
                splitIds(buildingId), siteId, buildingType));
    }

    // ================================================================
    // 统计② COP 计算
    // ================================================================
    @Operation(summary = "统计② COP 计算与能效分析")
    @GetMapping("/cop")
    @OperLog(title = "统计-COP分析", businessType = OperLog.BusinessType.QUERY)
    public R<CopAnalysisVO> cop(
            @RequestParam String buildingId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return R.ok(analyticsService.copAnalysis(startDate, endDate, buildingId));
    }

    // ================================================================
    // 统计③ 异常分析
    // ================================================================
    @Operation(summary = "统计③ 数据异常分析（能耗异常与数据质量缺陷分开统计）")
    @GetMapping("/anomaly")
    @OperLog(title = "统计-异常分析", businessType = OperLog.BusinessType.QUERY)
    public R<AnomalyAnalysisVO> anomaly(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String siteId,
            @RequestParam(required = false) String buildingId) {
        return R.ok(analyticsService.anomalyAnalysis(startDate, endDate, siteId, splitIds(buildingId)));
    }

    // ================================================================
    // MCP 工具 5：异常扫描
    // ================================================================
    @Operation(summary = "MCP-5 异常扫描：分页列出异常事件")
    @GetMapping("/anomalies/scan")
    @OperLog(title = "MCP-异常扫描", businessType = OperLog.BusinessType.QUERY)
    public R<Map<String, Object>> scanAnomalies(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) String siteId,
            @RequestParam(required = false) String anomalyType,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Integer minDuration,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(analyticsService.scanAnomalies(startDate, endDate, splitIds(buildingId),
                siteId, anomalyType, severity, minDuration, pageNum, pageSize));
    }

    // ================================================================
    // MCP 工具 6：问题诊断
    // ================================================================
    @Operation(summary = "MCP-6 问题诊断：异常归因证据链（温度/人员/COP/温差对比）")
    @GetMapping("/diagnose")
    @OperLog(title = "MCP-问题诊断", businessType = OperLog.BusinessType.QUERY)
    public R<Map<String, Object>> diagnose(
            @RequestParam String buildingId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<String> ids = splitIds(buildingId);
        if (ids == null || ids.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST, "问题诊断必须指定建筑（buildingId）");
        }
        return R.ok(analyticsService.diagnose(startDate, endDate, ids));
    }

    // ================================================================
    // MCP 工具 7：优化建议
    // ================================================================
    @Operation(summary = "MCP-7 优化建议：基于能效画像给出运维优化建议")
    @GetMapping("/suggest")
    @OperLog(title = "MCP-优化建议", businessType = OperLog.BusinessType.QUERY)
    public R<Map<String, Object>> suggest(
            @RequestParam String buildingId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return R.ok(analyticsService.suggestOptimization(startDate, endDate, buildingId));
    }

    // ================================================================
    // 统计报表导出
    // ================================================================
    @Operation(summary = "导出 Excel 统计报表（5 个 Sheet：概览/时段汇总/分时占比/异常分析/异常建筑排名）")
    @GetMapping("/report/export")
    @OperLog(title = "统计-报表导出", businessType = OperLog.BusinessType.EXPORT)
    public void exportReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String siteId,
            @RequestParam(required = false) String buildingType,
            @RequestParam(defaultValue = "month") String periodType,
            HttpServletResponse response) throws IOException {
        reportExportService.exportEnergyReport(response, startDate, endDate,
                siteId, buildingType, periodType);
    }

    private List<String> splitIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        List<String> ids = Arrays.stream(csv.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).distinct().toList();
        return ids.isEmpty() ? null : ids;
    }
}

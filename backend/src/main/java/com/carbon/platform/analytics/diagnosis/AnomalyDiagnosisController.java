package com.carbon.platform.analytics.diagnosis;

import com.carbon.platform.common.annotation.OperLog;
import com.carbon.platform.common.result.R;
import com.carbon.platform.common.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * AI 诊断记录：生成 / 复用 / 历史 / 详情。
 *
 * <p>路径用 {@code /api/diagnosis} 而不是 {@code /api/anomalies/diagnosis}，
 * 避免与 {@code /api/anomalies/{id}} 的路径变量产生歧义。
 */
@Tag(name = "06-AI诊断记录", description = "诊断生成、复用、历史追溯")
@RestController
@RequestMapping("/api/diagnosis")
@RequiredArgsConstructor
public class AnomalyDiagnosisController {

    private final AnomalyDiagnosisService diagnosisService;

    @Operation(summary = "生成或复用诊断（force=true 重新分析）")
    @PostMapping
    @OperLog(title = "AI诊断-生成", businessType = OperLog.BusinessType.QUERY)
    public R<Map<String, Object>> generate(
            @RequestParam String buildingId,
            @RequestParam(required = false) Long anomalyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "false") boolean force) {
        return R.ok(diagnosisService.generate(buildingId, anomalyId, startDate, endDate,
                force, SecurityUtils.currentUsername()));
    }

    @Operation(summary = "诊断历史")
    @GetMapping
    public R<List<Map<String, Object>>> history(
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) Long anomalyId,
            @RequestParam(defaultValue = "10") int limit) {
        return R.ok(diagnosisService.history(buildingId, anomalyId, limit));
    }

    @Operation(summary = "诊断详情")
    @GetMapping("/{id}")
    public R<Map<String, Object>> get(@PathVariable long id) {
        return R.ok(diagnosisService.get(id));
    }
}

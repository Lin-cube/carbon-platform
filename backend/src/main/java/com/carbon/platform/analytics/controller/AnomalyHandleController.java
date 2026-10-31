package com.carbon.platform.analytics.controller;

import com.carbon.platform.analytics.dto.AnomalyHandleRequest;
import com.carbon.platform.analytics.service.AnomalyHandleService;
import com.carbon.platform.common.annotation.OperLog;
import com.carbon.platform.common.result.R;
import com.carbon.platform.common.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 异常处置接口：异常发现 → 诊断 → 人工处理 → 记录回写。
 *
 * <h3>权限</h3>
 * 查询类对所有登录用户开放（viewer 可看）；
 * <b>写操作仅 admin / operator</b>，由 {@code @PreAuthorize} 强制，viewer 调用返回 403。
 *
 * <h3>AI 与人工的边界</h3>
 * 小E 只能通过只读工具查看历史处置案例，<b>没有任何工具能调用本控制器的写接口</b>，
 * 因此 AI 无法把异常标记为已处理 —— 处置结论必须由人签字。
 */
@Tag(name = "04-异常处置", description = "异常详情 / 处理状态回写 / 处理历史 / 历史案例")
@RestController
@RequestMapping("/api/anomalies")
@RequiredArgsConstructor
public class AnomalyHandleController {

    private final AnomalyHandleService handleService;

    @Operation(summary = "异常详情（含处理信息与历史）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable long id) {
        return R.ok(handleService.detail(id));
    }

    @Operation(summary = "异常处理历史")
    @GetMapping("/{id}/history")
    public R<List<Map<String, Object>>> history(@PathVariable long id) {
        return R.ok(handleService.history(id));
    }

    @Operation(summary = "标记异常处理状态（仅 admin / operator）")
    @PostMapping("/{id}/handle")
    @PreAuthorize("hasAnyRole('admin','operator')")
    @OperLog(title = "异常处置", businessType = OperLog.BusinessType.UPDATE)
    public R<Map<String, Object>> handle(@PathVariable long id,
                                         @RequestBody AnomalyHandleRequest req) {
        return R.ok(handleService.handle(id, req.getStatus(), req.getNote(),
                req.getVersion(), SecurityUtils.currentUsername()));
    }

    @Operation(summary = "历史处置案例（只读，供运维复盘与小E 参考）")
    @GetMapping("/handled")
    public R<List<Map<String, Object>>> handledCases(
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) String anomalyType,
            @RequestParam(defaultValue = "10") int limit) {
        return R.ok(handleService.handledCases(buildingId, anomalyType, limit));
    }

    @Operation(summary = "异常处理状态统计")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats(
            @RequestParam(required = false) String buildingId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return R.ok(handleService.stats(buildingId, startDate, endDate));
    }
}

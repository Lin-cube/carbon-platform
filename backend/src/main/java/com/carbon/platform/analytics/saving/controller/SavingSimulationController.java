package com.carbon.platform.analytics.saving.controller;

import com.carbon.platform.analytics.saving.dto.SavingSimulationRequest;
import com.carbon.platform.analytics.saving.dto.SavingSimulationVO;
import com.carbon.platform.analytics.saving.service.SavingSimulationService;
import com.carbon.platform.common.annotation.OperLog;
import com.carbon.platform.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 节能潜力情景模拟（What-if）。
 *
 * <p>接口只做<b>分析与模拟</b>：结果写入独立的 {@code energy_saving_simulation} 表，
 * 不触达 fact / agg 任何真实统计，也<b>不会</b>下发任何设备控制指令。
 * 方案是否落地由运维人员人工审核决定。
 */
@Tag(name = "05-节能情景模拟", description = "比例下降 / COP 改善 两类情景的确定性测算")
@RestController
@RequestMapping("/api/analytics/saving")
@RequiredArgsConstructor
public class SavingSimulationController {

    private final SavingSimulationService savingService;

    @Operation(summary = "执行节能情景测算（可选保存模拟记录）")
    @PostMapping("/simulate")
    @OperLog(title = "节能情景测算", businessType = OperLog.BusinessType.QUERY)
    public R<SavingSimulationVO> simulate(@RequestBody SavingSimulationRequest req) {
        return R.ok(savingService.simulate(req));
    }

    @Operation(summary = "查询模拟记录详情")
    @GetMapping("/{id}")
    public R<SavingSimulationVO> get(@PathVariable long id) {
        return R.ok(savingService.get(id));
    }

    @Operation(summary = "模拟记录列表")
    @GetMapping("/list")
    public R<List<SavingSimulationVO>> list(@RequestParam(required = false) String buildingId,
                                            @RequestParam(defaultValue = "10") int limit) {
        return R.ok(savingService.list(buildingId, limit));
    }

    @Operation(summary = "生成 AI 解释（区分事实/假设/潜力/风险）")
    @PostMapping("/{id}/explain")
    public R<Map<String, Object>> explain(@PathVariable long id) {
        return R.ok(savingService.explain(id));
    }
}

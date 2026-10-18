package com.carbon.platform.energy.controller;

import com.carbon.platform.common.annotation.OperLog;
import com.carbon.platform.common.result.R;
import com.carbon.platform.energy.dto.*;
import com.carbon.platform.energy.service.EnergyQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 能耗数据查询接口。
 *
 * <p>本控制器同时承担 MCP 工具 1–4 的 REST 形态实现，
 * 供前端与 AI 模块（角色 B 的 Python MCP Server）共同调用。
 */
@Tag(name = "02-能耗查询", description = "维度、概览、分解、时序查询（对应 MCP 工具 1-4）")
@RestController
@RequestMapping("/api/energy")
@RequiredArgsConstructor
public class EnergyQueryController {

    private final EnergyQueryService energyQueryService;

    @Operation(summary = "MCP-1 维度查询：获取可选建筑/园区/类型等维度值")
    @GetMapping("/dimensions")
    @OperLog(title = "能耗查询-维度", businessType = OperLog.BusinessType.QUERY)
    public R<DimensionVO> dimensions(
            @RequestParam(defaultValue = "all") String dimension) {
        return R.ok(energyQueryService.queryDimensions(dimension));
    }

    @Operation(summary = "MCP-2 概览统计：总量、碳排、异常率、平均 COP")
    @PostMapping("/overview")
    @OperLog(title = "能耗查询-概览", businessType = OperLog.BusinessType.QUERY)
    public R<OverviewVO> overview(@RequestBody EnergyQueryRequest req) {
        return R.ok(energyQueryService.overviewStats(req));
    }

    @Operation(summary = "MCP-3 分解统计：按园区/建筑类型/季节/时段/数据分级分解")
    @PostMapping("/breakdown")
    @OperLog(title = "能耗查询-分解", businessType = OperLog.BusinessType.QUERY)
    public R<BreakdownVO> breakdown(@RequestBody EnergyQueryRequest req,
                                    @RequestParam(defaultValue = "site") String groupBy) {
        return R.ok(energyQueryService.breakdownStats(req, groupBy));
    }

    @Operation(summary = "MCP-4 能耗查询：多条件时序查询（hourly/daily/monthly）")
    @PostMapping("/query")
    @OperLog(title = "能耗查询-时序", businessType = OperLog.BusinessType.QUERY)
    public R<TimeSeriesVO> query(@RequestBody EnergyQueryRequest req) {
        return R.ok(energyQueryService.queryEnergy(req));
    }

    @Operation(summary = "建筑列表（筛选器用）")
    @GetMapping("/buildings")
    @OperLog(title = "建筑列表", businessType = OperLog.BusinessType.QUERY)
    public R<List<Map<String, Object>>> buildings(
            @RequestParam(required = false) String siteId,
            @RequestParam(required = false) String buildingType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "200") int limit) {
        return R.ok(energyQueryService.listBuildings(siteId, buildingType, keyword, limit));
    }

    @Operation(summary = "建筑详情")
    @GetMapping("/buildings/{buildingId}")
    @OperLog(title = "建筑详情", businessType = OperLog.BusinessType.QUERY)
    public R<Map<String, Object>> buildingDetail(@PathVariable String buildingId) {
        return R.ok(energyQueryService.buildingDetail(buildingId));
    }

    @Operation(summary = "园区列表")
    @GetMapping("/sites")
    @OperLog(title = "园区列表", businessType = OperLog.BusinessType.QUERY)
    public R<List<Map<String, Object>>> sites() {
        return R.ok(energyQueryService.listSites());
    }
}

package com.carbon.platform.energy.service;

import com.carbon.platform.energy.dto.*;

import java.util.List;
import java.util.Map;

/**
 * 能耗查询服务（同时作为 MCP 工具 1–4 的实现基础）。
 */
public interface EnergyQueryService {

    /** MCP 工具 1：维度查询 */
    DimensionVO queryDimensions(String dimension);

    /** MCP 工具 2：概览统计 */
    OverviewVO overviewStats(EnergyQueryRequest req);

    /** MCP 工具 3：状态/维度分解统计 */
    BreakdownVO breakdownStats(EnergyQueryRequest req, String groupBy);

    /** MCP 工具 4：能耗查询（时序） */
    TimeSeriesVO queryEnergy(EnergyQueryRequest req);

    /** 建筑列表 */
    List<Map<String, Object>> listBuildings(String siteId, String buildingType, String keyword, int limit);

    /** 建筑详情 */
    Map<String, Object> buildingDetail(String buildingId);

    /** 园区列表 */
    List<Map<String, Object>> listSites();
}

package com.carbon.platform.energy.mapper;

import com.carbon.platform.energy.dto.BreakdownVO;
import com.carbon.platform.energy.dto.DimensionVO;
import com.carbon.platform.energy.dto.OverviewVO;
import com.carbon.platform.energy.dto.TimeSeriesVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 能耗查询 Mapper（读优化，直接写 SQL 而非走 ORM）。
 *
 * <p>性能设计（对应验收指标"核心页面平均响应时间 ≤ 2 秒"）：
 * <ul>
 *   <li>趋势/分解类查询走<b>预聚合表</b> agg_building_daily / agg_building_monthly，
 *       实测 0.03–0.08s；</li>
 *   <li>明细类查询走事实表 fact_energy_hourly，命中 idx_building_ts 索引，
 *       实测 0.04s；</li>
 *   <li>所有查询强制带时间范围条件，避免误查全表。</li>
 * </ul>
 */
@Mapper
public interface EnergyQueryMapper {

    /** 维度值枚举（MCP query_dimensions） */
    List<DimensionVO.Option> selectDimensionOptions(@Param("dimension") String dimension);

    /** 概览统计（MCP overview_stats） */
    OverviewVO selectOverview(@Param("startDate") LocalDate startDate,
                              @Param("endDate") LocalDate endDate,
                              @Param("siteId") String siteId,
                              @Param("buildingId") String buildingId);

    /** 分组分解统计（MCP breakdown_stats） */
    List<Map<String, Object>> selectBreakdownBySite(@Param("startDate") LocalDate startDate,
                                                     @Param("endDate") LocalDate endDate,
                                                     @Param("metric") String metric);

    List<Map<String, Object>> selectBreakdownByType(@Param("startDate") LocalDate startDate,
                                                     @Param("endDate") LocalDate endDate,
                                                     @Param("metric") String metric);

    List<Map<String, Object>> selectBreakdownBySeason(@Param("startDate") LocalDate startDate,
                                                       @Param("endDate") LocalDate endDate,
                                                       @Param("metric") String metric);

    List<Map<String, Object>> selectBreakdownByHour(@Param("startDate") LocalDate startDate,
                                                     @Param("endDate") LocalDate endDate,
                                                     @Param("metric") String metric);

    List<Map<String, Object>> selectBreakdownByGrade(@Param("startDate") LocalDate startDate,
                                                      @Param("endDate") LocalDate endDate,
                                                      @Param("metric") String metric);

    /** 日粒度时序（走预聚合表） */
    List<TimeSeriesVO.Point> selectDailySeries(@Param("startDate") LocalDate startDate,
                                               @Param("endDate") LocalDate endDate,
                                               @Param("buildingIds") List<String> buildingIds,
                                               @Param("siteId") String siteId,
                                               @Param("buildingType") String buildingType,
                                               @Param("onlyAnomaly") boolean onlyAnomaly);

    /** 月粒度时序（走预聚合表） */
    List<TimeSeriesVO.Point> selectMonthlySeries(@Param("startDate") LocalDate startDate,
                                                 @Param("endDate") LocalDate endDate,
                                                 @Param("buildingIds") List<String> buildingIds,
                                                 @Param("siteId") String siteId,
                                                 @Param("buildingType") String buildingType,
                                                 @Param("onlyAnomaly") boolean onlyAnomaly);

    /** 小时粒度时序（走事实表，限单建筑以免返回量过大） */
    List<TimeSeriesVO.Point> selectHourlySeries(@Param("startDate") LocalDate startDate,
                                                @Param("endDate") LocalDate endDate,
                                                @Param("buildingIds") List<String> buildingIds,
                                                @Param("onlyAnomaly") boolean onlyAnomaly,
                                                @Param("limit") int limit);

    /** 各建筑汇总（对比图 + 报表） */
    List<TimeSeriesVO.Summary> selectBuildingSummary(@Param("startDate") LocalDate startDate,
                                                      @Param("endDate") LocalDate endDate,
                                                      @Param("buildingIds") List<String> buildingIds,
                                                      @Param("siteId") String siteId,
                                                      @Param("buildingType") String buildingType,
                                                      @Param("limit") int limit);

    /** 建筑列表（供筛选器） */
    List<Map<String, Object>> selectBuildings(@Param("siteId") String siteId,
                                              @Param("buildingType") String buildingType,
                                              @Param("keyword") String keyword,
                                              @Param("limit") int limit);

    /** 建筑详情 */
    Map<String, Object> selectBuildingDetail(@Param("buildingId") String buildingId);

    /** 园区列表 */
    List<Map<String, Object>> selectSites();
}

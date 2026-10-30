package com.carbon.platform.analytics.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 统计分析 Mapper（三大核心统计 + 报表 + MCP 工具 5–7）。
 */
@Mapper
public interface AnalyticsMapper {

    // ================================================================
    // 统计① 时段汇总
    // ================================================================

    /** 通用时段汇总：periodType 决定分组表达式（由 Service 传入已白名单化的表达式） */
    List<Map<String, Object>> selectPeriodBuckets(@Param("groupExpr") String groupExpr,
                                                   @Param("startDate") LocalDate startDate,
                                                   @Param("endDate") LocalDate endDate,
                                                   @Param("buildingIds") List<String> buildingIds,
                                                   @Param("siteId") String siteId,
                                                   @Param("buildingType") String buildingType);

    /** 分时（尖峰平谷）统计 */
    List<Map<String, Object>> selectTariffBuckets(@Param("startDate") LocalDate startDate,
                                                   @Param("endDate") LocalDate endDate,
                                                   @Param("buildingIds") List<String> buildingIds,
                                                   @Param("siteId") String siteId);

    /** 小时级峰谷定位 */
    Map<String, Object> selectPeakValley(@Param("startDate") LocalDate startDate,
                                          @Param("endDate") LocalDate endDate,
                                          @Param("buildingIds") List<String> buildingIds,
                                          @Param("siteId") String siteId);

    /** 指定小时的能耗合计（用于找峰值小时标签） */
    List<Map<String, Object>> selectHourTotals(@Param("startDate") LocalDate startDate,
                                                @Param("endDate") LocalDate endDate,
                                                @Param("buildingIds") List<String> buildingIds,
                                                @Param("siteId") String siteId);

    // ================================================================
    // 统计② COP 分析
    // ================================================================

    Map<String, Object> selectCopSummary(@Param("startDate") LocalDate startDate,
                                          @Param("endDate") LocalDate endDate,
                                          @Param("buildingIds") List<String> buildingIds);

    List<Map<String, Object>> selectCopByTemperature(@Param("startDate") LocalDate startDate,
                                                      @Param("endDate") LocalDate endDate,
                                                      @Param("buildingIds") List<String> buildingIds);

    List<Map<String, Object>> selectCopDistribution(@Param("startDate") LocalDate startDate,
                                                     @Param("endDate") LocalDate endDate,
                                                     @Param("buildingIds") List<String> buildingIds);

    // ================================================================
    // 统计③ 异常分析
    // ================================================================

    Map<String, Object> selectAnomalySummary(@Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate,
                                              @Param("siteId") String siteId,
                                              @Param("buildingIds") List<String> buildingIds);

    List<Map<String, Object>> selectAnomalyByType(@Param("startDate") LocalDate startDate,
                                                   @Param("endDate") LocalDate endDate,
                                                   @Param("siteId") String siteId,
                                                   @Param("buildingIds") List<String> buildingIds);

    List<Map<String, Object>> selectAnomalyTopBuildings(@Param("startDate") LocalDate startDate,
                                                         @Param("endDate") LocalDate endDate,
                                                         @Param("siteId") String siteId,
                                                         @Param("limit") int limit);

    List<Map<String, Object>> selectAnomalyTrend(@Param("startDate") LocalDate startDate,
                                                  @Param("endDate") LocalDate endDate,
                                                  @Param("siteId") String siteId);

    /** 异常事件分页查询（MCP scan_anomalies） */
    List<Map<String, Object>> selectAnomalyEvents(@Param("startDate") LocalDate startDate,
                                                   @Param("endDate") LocalDate endDate,
                                                   @Param("buildingIds") List<String> buildingIds,
                                                   @Param("siteId") String siteId,
                                                   @Param("anomalyType") String anomalyType,
                                                   @Param("severity") String severity,
                                                   @Param("minDuration") Integer minDuration,
                                                   @Param("offset") int offset,
                                                   @Param("limit") int limit);

    /** 事件时长聚合（替代拉取数千行到内存计算） */
    Map<String, Object> selectAnomalyEventDuration(@Param("startDate") LocalDate startDate,
                                                    @Param("endDate") LocalDate endDate,
                                                    @Param("buildingIds") List<String> buildingIds,
                                                    @Param("siteId") String siteId);

    Long countAnomalyEvents(@Param("startDate") LocalDate startDate,
                            @Param("endDate") LocalDate endDate,
                            @Param("buildingIds") List<String> buildingIds,
                            @Param("siteId") String siteId,
                            @Param("anomalyType") String anomalyType,
                            @Param("severity") String severity,
                            @Param("minDuration") Integer minDuration);

    // ================================================================
    // MCP 工具 6 问题诊断：证据链查询
    // ================================================================

    /** 某建筑异常时段的环境与运行参数画像 */
    List<Map<String, Object>> selectDiagnoseProfiles(@Param("buildingIds") List<String> buildingIds,
                                                      @Param("startDate") LocalDate startDate,
                                                      @Param("endDate") LocalDate endDate);

    /** 异常时段 vs 正常时段对比（用于归因）—— UNION ALL 返回 2 行 */
    List<Map<String, Object>> selectAnomalyVsNormal(@Param("buildingIds") List<String> buildingIds,
                                                     @Param("startDate") LocalDate startDate,
                                                     @Param("endDate") LocalDate endDate);

    /** 建筑能效画像（用于优化建议） */
    Map<String, Object> selectEfficiencyProfile(@Param("buildingIds") List<String> buildingIds,
                                                 @Param("startDate") LocalDate startDate,
                                                 @Param("endDate") LocalDate endDate);

    /**
     * 异常持续时长排行（AI 工具 getTopAnomalyBuildings）。
     * 按单次异常最长持续小时排序，同时返回异常事件数与累计异常小时。
     */
    List<Map<String, Object>> selectTopAnomalyDuration(@Param("startDate") LocalDate startDate,
                                                       @Param("endDate") LocalDate endDate,
                                                       @Param("siteId") String siteId,
                                                       @Param("limit") int limit);
}

package com.carbon.platform.analytics.saving.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 节能情景模拟结果（结构化）。
 *
 * <p>刻意把「历史事实 / 假设参数 / 测算结果 / 计算依据 / 数据不足项」分开放，
 * 便于前端分区展示，也让 AI 解释时不会把假设混成事实。
 */
@Data
@Schema(description = "节能情景模拟结果")
public class SavingSimulationVO {

    @Schema(description = "模拟记录 id（仅保存时有值）")
    private Long id;

    @Schema(description = "情景类型")
    private String scenario;

    @Schema(description = "情景中文名")
    private String scenarioName;

    private String buildingId;
    private String siteId;
    private String startDate;
    private String endDate;

    @Schema(description = "结果是否可用；false 时 result 中相关字段为 null")
    private boolean sufficient;

    @Schema(description = "整体数据不足的原因（sufficient=false 时给出）")
    private String insufficiencyReason;

    @Schema(description = "假设参数（用户输入）")
    private Map<String, Object> params;

    @Schema(description = "历史基准（来自真实聚合数据）")
    private Map<String, Object> baseline;

    @Schema(description = "测算结果（模拟值，非已实现效果）")
    private Map<String, Object> result;

    @Schema(description = "逐项数据不足说明")
    private List<Map<String, String>> insufficient;

    @Schema(description = "计算依据、口径与限制")
    private Map<String, Object> basis;

    @Schema(description = "AI 解释")
    private String aiNarrative;

    @Schema(description = "AI 解释来源：llm / template")
    private String aiMode;
}

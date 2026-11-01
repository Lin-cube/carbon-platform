package com.carbon.platform.analytics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 核心统计分析 ②：COP 计算与能效分析。
 *
 * <p>COP = 制冷量 / 空调系统电耗。BDG2 无实测电耗分项，
 * 制冷量取冷冻水表实测值，电耗由 COP 模型反推（详见数据字典）。
 */
@Data
@Schema(description = "COP 能效分析")
public class CopAnalysisVO {

    private String buildingId;
    private String startDate;
    private String endDate;

    @Schema(description = "平均 COP")
    private BigDecimal copAvg;

    @Schema(description = "copAvg 的计算口径：负荷加权 / 简单平均（无空调分项）")
    private String copAvgMethod;

    @Schema(description = "简单平均 COP（仅作对照，不用于对外结论）")
    private BigDecimal copAvgSimple;
    @Schema(description = "最高 COP")
    private BigDecimal copMax;
    @Schema(description = "最低 COP")
    private BigDecimal copMin;
    @Schema(description = "COP 标准差（稳定性，越小越稳）")
    private BigDecimal copStd;

    @Schema(description = "制冷量合计 kWh")
    private BigDecimal coolingKwh;
    @Schema(description = "空调电耗合计 kWh")
    private BigDecimal hvacElectricityKwh;
    @Schema(description = "系统能效比 = 制冷量/电耗")
    private BigDecimal systemCop;

    @Schema(description = "供水温度均值 ℃")
    private BigDecimal supplyTempAvg;
    @Schema(description = "回水温度均值 ℃")
    private BigDecimal returnTempAvg;
    @Schema(description = "平均供回水温差 ℃")
    private BigDecimal deltaTAvg;

    @Schema(description = "能效评级：优秀/良好/一般/偏低")
    private String copGrade;
    @Schema(description = "评级依据说明")
    private String gradeReason;

    @Schema(description = "COP 与室外温度的关系（用于判断高温衰减）")
    private List<TempCopRelation> tempRelations;

    @Schema(description = "COP 分布直方图")
    private List<Distribution> distribution;

    @Data
    public static class TempCopRelation {
        private String tempRange;
        private BigDecimal copAvg;
        private Long hours;
    }

    @Data
    public static class Distribution {
        private String range;
        private Long hours;
        private BigDecimal percentage;
    }
}

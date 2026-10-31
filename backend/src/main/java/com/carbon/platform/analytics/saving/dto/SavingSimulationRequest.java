package com.carbon.platform.analytics.saving.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 节能潜力情景模拟请求。
 *
 * <p>所有假设参数都由用户显式给出，后端只做确定性计算，不猜测、不填补。
 */
@Data
@Schema(description = "节能情景模拟请求")
public class SavingSimulationRequest {

    @Schema(description = "建筑编号（必填）", example = "Hog_office_Sydney", requiredMode = Schema.RequiredMode.REQUIRED)
    private String buildingId;

    @Schema(description = "统计开始日期", example = "2016-01-01", requiredMode = Schema.RequiredMode.REQUIRED)
    private String startDate;

    @Schema(description = "统计结束日期", example = "2016-12-31", requiredMode = Schema.RequiredMode.REQUIRED)
    private String endDate;

    @Schema(description = "情景类型：REDUCE_RATIO=可调能耗按比例下降；COP_IMPROVE=制冷 COP 改善",
            example = "REDUCE_RATIO", requiredMode = Schema.RequiredMode.REQUIRED)
    private String scenario;

    // ── 情景一：比例下降 ──

    @Schema(description = "节能比例（0~0.9），如 0.1 表示下降 10%", example = "0.1")
    private Double reduceRatio;

    @Schema(description = "生效起始小时（0~23，含），默认 0", example = "18")
    private Integer hourStart;

    @Schema(description = "生效结束小时（0~23，含），默认 23", example = "23")
    private Integer hourEnd;

    // ── 情景二：COP 改善 ──

    @Schema(description = "COP 提升比例（0~0.5），如 0.15 表示 COP 提升 15%", example = "0.15")
    private Double copImproveRatio;

    // ── 通用 ──

    @Schema(description = "电价（元/kWh）。不提供则电费节约返回「数据不足」", example = "0.75")
    private Double unitPrice;

    @Schema(description = "是否保存到模拟历史（默认 true）", example = "true")
    private Boolean save;
}

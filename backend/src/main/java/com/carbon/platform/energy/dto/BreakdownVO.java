package com.carbon.platform.energy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * MCP 工具 3 `breakdown_stats` 的返回：状态/维度分解。
 */
@Data
@Schema(description = "能耗分解统计")
public class BreakdownVO {

    @Schema(description = "分组维度")
    private String groupBy;
    @Schema(description = "统计指标")
    private String metric;

    private List<Item> items;
    @Schema(description = "合计")
    private BigDecimal total;

    @Data
    public static class Item {
        private String key;
        private String label;
        @Schema(description = "该分组的值")
        private BigDecimal value;
        @Schema(description = "占比 %")
        private BigDecimal percentage;
        @Schema(description = "记录数/建筑数")
        private Long count;
    }
}

package com.carbon.platform.energy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * MCP 工具 1 `query_dimensions` 的返回：可选维度值。
 */
@Data
@Schema(description = "可选维度值")
public class DimensionVO {

    @Schema(description = "维度名称")
    private String dimension;

    @Schema(description = "可选值列表")
    private List<Option> options;

    @Schema(description = "可选值总数")
    private Integer total;

    @Data
    public static class Option {
        private String value;
        private String label;
        private Long count;
    }
}

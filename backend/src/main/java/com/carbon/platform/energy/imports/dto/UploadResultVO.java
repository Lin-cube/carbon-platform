package com.carbon.platform.energy.imports.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 步骤一：数据源上传结果（含列识别与自动字段映射建议）。
 */
@Data
public class UploadResultVO {

    /** 临时文件标识，后续步骤用它引用本次上传 */
    private String fileId;
    private String fileName;
    private String fileType;

    @Schema(description = "自动识别的数据源类型：ENERGY_HOURLY / BUILDING_META / UNKNOWN")
    private String detectedType;

    @Schema(description = "类型中文说明")
    private String detectedTypeLabel;
    private Long fileSize;

    private List<ColumnInfo> columns;
    private Integer totalRows;
    /** 前若干行数据，用于预览与用户确认 */
    private List<List<String>> previewRows;
    private List<String> headers;
    /** 解析告警（编码、空列、列数不一致等） */
    private List<String> warnings;

    /** 本次自动映射所用的策略（AI / 规则兜底），供前端提示与审计 */
    private MappingPlan mappingPlan;

    @Data
    public static class MappingPlan {
        /** LLM 或 RULE_FALLBACK */
        private String strategy;
        /** modelscope / mock / builtin */
        private String provider;
        private String model;
        private Long elapsedMs;
        /** 降级原因（strategy=RULE_FALLBACK 时有值） */
        private String fallbackReason;
        private Integer mappedCount;
        private Integer totalColumns;
    }

    @Data
    public static class ColumnInfo {
        private String sourceColumn;
        private Integer index;
        /** 推断的数据类型：string/number/datetime/empty */
        private String inferredType;
        private Integer sampleNonEmpty;
        private List<String> samples;
        /** AI 建议映射到的目标字段（null 表示忽略该列） */
        private String suggestedTarget;
        /** 映射置信度 0~1 */
        private Double confidence;
        private String reason;
    }
}

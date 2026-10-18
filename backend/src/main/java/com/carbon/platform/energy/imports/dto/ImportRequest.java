package com.carbon.platform.energy.imports.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 步骤三/四：清洗规则 + 字段映射，提交导入。
 */
@Data
public class ImportRequest {

    @NotBlank(message = "fileId 不能为空")
    private String fileId;

    /** 字段映射：源列名 -> 目标字段名 */
    private List<FieldMapping> mappings;

    private CleanRules cleanRules = new CleanRules();

    /** 仅预览不落库 */
    private Boolean dryRun = false;

    private String remark;

    @Data
    public static class FieldMapping {
        private String sourceColumn;
        /** 目标字段，取值见 ImportTargetField */
        private String targetField;
    }

    @Data
    public static class CleanRules {
        private Boolean trimString = true;
        /** 缺失值填充策略：none / zero / mean / drop */
        private String missingStrategy = "none";
        private Boolean dropNegative = true;
        /** 建筑编号前缀（源数据缺少园区前缀时自动补齐） */
        private String buildingIdPrefix;
        private String dateFormat;
        /** 电量单位：kwh / mwh */
        private String electricityUnit = "kwh";
        /** 去重策略：none / byBuildingTime */
        private String dedupStrategy = "byBuildingTime";
    }
}

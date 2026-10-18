package com.carbon.platform.energy.imports.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 步骤四/五：导入结果与审计留痕。
 */
@Data
public class ImportResultVO {

    private String batchNo;
    private String fileName;
    private Integer totalRows;
    private Integer successRows;
    private Integer failedRows;
    private Integer skippedRows;

    /** 本次导入覆盖的已存在记录数（按 building_id + ts 匹配后删除） */
    private Integer replacedRows = 0;

    /** 预聚合表刷新结果（导入后自动同步看板/统计用的聚合表） */
    private java.util.Map<String, Object> aggregateRefresh;
    private String status;

    private List<RowError> errors;
    private Map<String, Integer> fieldStats;
    private Boolean dryRun;

    @Data
    public static class RowError {
        private Integer rowNum;
        private String field;
        private String message;
        private String value;
    }
}

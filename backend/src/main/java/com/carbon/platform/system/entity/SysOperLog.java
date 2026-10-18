package com.carbon.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志（申报表"审计留痕"要求）。
 */
@Data
@TableName("sys_oper_log")
public class SysOperLog implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 模块标题 */
    private String title;
    /** 操作类型：查询/新增/修改/删除/导入/导出 */
    private String businessType;
    private String method;
    private String requestMethod;
    private String operName;
    private String operUrl;
    private String operIp;
    private String operParam;
    private String jsonResult;
    /** 0 正常 / 1 异常 */
    private Integer status;
    private String errorMsg;
    private Long costMs;
    private LocalDateTime operTime;
}

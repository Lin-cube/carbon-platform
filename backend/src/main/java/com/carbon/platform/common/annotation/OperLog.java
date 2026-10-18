package com.carbon.platform.common.annotation;

import java.lang.annotation.*;

/**
 * 操作日志注解（申报表"审计留痕"要求）。
 *
 * <p>标注在 Controller 方法上，由 {@link com.carbon.platform.common.aspect.OperLogAspect}
 * 自动记录操作人、参数、结果、耗时、异常信息到 {@code sys_oper_log}。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperLog {

    /** 模块标题，如"能耗查询"、"数据导入" */
    String title();

    /** 操作类型 */
    BusinessType businessType() default BusinessType.OTHER;

    /** 是否记录请求参数 */
    boolean saveParam() default true;

    /** 是否记录返回结果 */
    boolean saveResult() default false;

    enum BusinessType {
        QUERY("查询"), INSERT("新增"), UPDATE("修改"), DELETE("删除"),
        IMPORT("导入"), EXPORT("导出"), LOGIN("登录"), OTHER("其他");

        private final String label;

        BusinessType(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }
}

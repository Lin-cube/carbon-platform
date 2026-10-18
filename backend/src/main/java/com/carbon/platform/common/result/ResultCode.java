package com.carbon.platform.common.result;

/**
 * 统一业务状态码。
 * 与前端约定：code = 200 表示成功；其余为业务或系统错误。
 */
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),

    LOGIN_FAILED(1001, "用户名或密码错误"),
    ACCOUNT_DISABLED(1002, "账号已停用"),

    BUILDING_NOT_FOUND(2001, "建筑不存在"),
    NO_DATA(2002, "查询区间内无数据"),
    DATA_GRADE_B(2003, "该建筑为 B 级分摊数据，不支持异常分析"),

    AI_SERVICE_UNAVAILABLE(3001, "AI 服务暂不可用"),
    AI_SERVICE_ERROR(3002, "AI 服务返回错误"),

    IMPORT_FAILED(4001, "数据导入失败"),

    ERROR(500, "系统内部错误");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}

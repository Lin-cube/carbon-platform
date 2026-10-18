package com.carbon.platform.common.result;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 统一响应信封。
 *
 * <p>与 {@code docs/接口契约与分工.md} 中 MCP 工具的响应信封保持一致风格，
 * 便于前端与 AI 模块共用同一套解析逻辑。
 *
 * @param <T> 业务数据类型
 */
@Data
@Accessors(chain = true)
public class R<T> implements Serializable {

    private int code;
    private String message;
    private T data;
    /** 耗时（毫秒），用于验收指标"响应时间 ≤ 2 秒"的现场度量 */
    private Long costMs;
    private long timestamp = System.currentTimeMillis();

    public static <T> R<T> ok() {
        return new R<T>().setCode(ResultCode.SUCCESS.getCode())
                .setMessage(ResultCode.SUCCESS.getMessage());
    }

    public static <T> R<T> ok(T data) {
        return R.<T>ok().setData(data);
    }

    public static <T> R<T> ok(T data, String message) {
        return R.<T>ok().setData(data).setMessage(message);
    }

    public static <T> R<T> fail(ResultCode rc) {
        return new R<T>().setCode(rc.getCode()).setMessage(rc.getMessage());
    }

    public static <T> R<T> fail(ResultCode rc, String message) {
        return new R<T>().setCode(rc.getCode()).setMessage(message);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<T>().setCode(code).setMessage(message);
    }

    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }
}

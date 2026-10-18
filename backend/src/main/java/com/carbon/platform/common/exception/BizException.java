package com.carbon.platform.common.exception;

import com.carbon.platform.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常。由全局异常处理器统一转换为 {@link com.carbon.platform.common.result.R}。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ResultCode rc) {
        super(rc.getMessage());
        this.code = rc.getCode();
    }

    public BizException(ResultCode rc, String message) {
        super(message);
        this.code = rc.getCode();
    }

    public BizException(String message) {
        super(message);
        this.code = ResultCode.ERROR.getCode();
    }
}

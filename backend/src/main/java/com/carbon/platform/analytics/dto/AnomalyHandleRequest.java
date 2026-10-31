package com.carbon.platform.analytics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 异常处置请求。
 *
 * <p>{@code version} 是客户端读到的 {@code handle_version}，用于乐观锁；
 * 不传则不做版本校验（供脚本/兼容调用使用）。
 */
@Data
@Schema(description = "异常处置请求")
public class AnomalyHandleRequest {

    @Schema(description = "目标状态：未处理/处理中/已处理/已忽略/待复检", example = "已处理")
    private String status;

    @Schema(description = "处理备注（已处理/已忽略 必填，最长 512 字）",
            example = "已核查：冷凝器结垢，安排 11 月清洗，清洗前临时降低负荷")
    private String note;

    @Schema(description = "乐观锁版本号（读详情时返回的 handleVersion）", example = "0")
    private Integer version;
}

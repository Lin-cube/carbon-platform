package com.carbon.platform.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置。
 *
 * <p>生产环境务必通过环境变量覆盖 {@code carbon.jwt.secret}，
 * 不要使用仓库中的默认值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "carbon.jwt")
public class JwtProperties {

    /** 签名密钥（HS256 要求至少 32 字节） */
    private String secret = "carbon-platform-default-secret-please-override-in-production";
    /** 有效期（秒），默认 8 小时 */
    private long expireSeconds = 8 * 3600L;
    /** 请求头名称 */
    private String header = "Authorization";
    /** token 前缀 */
    private String prefix = "Bearer ";
}

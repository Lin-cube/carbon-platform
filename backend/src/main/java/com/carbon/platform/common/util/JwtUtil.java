package com.carbon.platform.common.util;

import com.carbon.platform.common.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * JWT 生成与解析工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_NICKNAME = "nickname";
    private static final String CLAIM_ROLES = "roles";

    private final JwtProperties props;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /** 生成 token */
    public String generate(Long userId, String username, String nickname, List<String> roles) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + props.getExpireSeconds() * 1000);
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_NICKNAME, nickname)
                .claim(CLAIM_ROLES, roles)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key())
                .compact();
    }

    /** 解析 token；非法或过期返回 null */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String raw = token.startsWith(props.getPrefix())
                ? token.substring(props.getPrefix().length())
                : token;
        try {
            return Jwts.parser().verifyWith(key()).build()
                    .parseSignedClaims(raw).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    public Long getUserId(Claims claims) {
        Object v = claims.get(CLAIM_USER_ID);
        return v == null ? null : Long.valueOf(v.toString());
    }

    public String getNickname(Claims claims) {
        Object v = claims.get(CLAIM_NICKNAME);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    public List<String> getRoles(Claims claims) {
        Object v = claims.get(CLAIM_ROLES);
        return v instanceof List ? (List<String>) v : List.of();
    }

    public Map<String, Object> describe() {
        return Map.of("expireSeconds", props.getExpireSeconds(), "header", props.getHeader());
    }
}

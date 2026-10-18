package com.carbon.platform.common.config;

import com.carbon.platform.common.util.JwtUtil;
import com.carbon.platform.system.entity.SysUser;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器：从请求头解析 token，构造认证上下文。
 *
 * <p>校验失败不直接抛异常，让请求继续走到过滤器链末端，
 * 由 {@code SecurityConfig} 的鉴权规则决定是否放行（如白名单接口）。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(jwtProperties.getHeader());
        Claims claims = jwtUtil.parse(header);

        if (claims != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            SysUser user = new SysUser();
            user.setUserId(jwtUtil.getUserId(claims));
            user.setUsername(claims.getSubject());
            user.setNickname(jwtUtil.getNickname(claims));
            List<String> roles = jwtUtil.getRoles(claims);
            user.setRoles(roles);

            List<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .toList();

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(user, null, authorities);
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }
}

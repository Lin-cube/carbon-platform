package com.carbon.platform.common.util;

import com.carbon.platform.system.entity.SysUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户上下文工具。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** 获取当前登录用户；未登录返回 null */
    public static SysUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof SysUser user)) {
            return null;
        }
        return user;
    }

    /** 当前登录名；未登录返回 "anonymous"（用于审计日志） */
    public static String currentUsername() {
        SysUser u = currentUser();
        return u == null ? "anonymous" : u.getUsername();
    }

    public static Long currentUserId() {
        SysUser u = currentUser();
        return u == null ? null : u.getUserId();
    }
}

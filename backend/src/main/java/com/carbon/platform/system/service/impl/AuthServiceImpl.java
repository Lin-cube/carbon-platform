package com.carbon.platform.system.service.impl;

import com.carbon.platform.common.config.JwtProperties;
import com.carbon.platform.common.exception.BizException;
import com.carbon.platform.common.result.ResultCode;
import com.carbon.platform.common.util.JwtUtil;
import com.carbon.platform.common.util.SecurityUtils;
import com.carbon.platform.system.dto.LoginRequest;
import com.carbon.platform.system.dto.LoginResponse;
import com.carbon.platform.system.dto.UserInfoVO;
import com.carbon.platform.system.entity.SysUser;
import com.carbon.platform.system.mapper.SysUserMapper;
import com.carbon.platform.system.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 认证服务。
 *
 * <p>安全设计：用户名不存在与密码错误返回<b>同一个错误码</b>，避免用户名枚举。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;

    @Override
    public LoginResponse login(LoginRequest request) {
        SysUser user = userMapper.selectByUsername(request.getUsername());
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("登录失败: username={}", request.getUsername());
            throw new BizException(ResultCode.LOGIN_FAILED);
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(ResultCode.ACCOUNT_DISABLED);
        }

        List<String> roles = userMapper.selectRoleKeys(user.getUserId());
        if (roles.isEmpty()) {
            roles = List.of("viewer");   // 兜底：未分配角色视为只读
        }

        String token = jwtUtil.generate(user.getUserId(), user.getUsername(),
                user.getNickname(), roles);

        LoginResponse resp = new LoginResponse();
        resp.setToken(token);
        resp.setUserId(user.getUserId());
        resp.setUsername(user.getUsername());
        resp.setNickname(user.getNickname());
        resp.setDept(user.getDept());
        resp.setRoles(roles);
        resp.setExpireSeconds(jwtProperties.getExpireSeconds());
        log.info("登录成功: {} roles={}", user.getUsername(), roles);
        return resp;
    }

    @Override
    public UserInfoVO currentUserInfo() {
        SysUser user = SecurityUtils.currentUser();
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        UserInfoVO vo = new UserInfoVO();
        vo.setUserId(user.getUserId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRoles(user.getRoles() == null ? List.of() : user.getRoles());
        vo.setPermissions(buildPermissions(vo.getRoles()));
        return vo;
    }

    /** 角色 -> 权限标识映射（按钮级控制用） */
    private List<String> buildPermissions(List<String> roles) {
        if (roles.contains("admin")) {
            return List.of("energy:read", "energy:export", "data:import",
                    "ai:chat", "system:user", "system:log");
        }
        if (roles.contains("operator")) {
            return List.of("energy:read", "energy:export", "data:import", "ai:chat");
        }
        return List.of("energy:read", "ai:chat");
    }
}

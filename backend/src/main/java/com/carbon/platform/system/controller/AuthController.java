package com.carbon.platform.system.controller;

import com.carbon.platform.common.annotation.OperLog;
import com.carbon.platform.common.result.R;
import com.carbon.platform.system.dto.LoginRequest;
import com.carbon.platform.system.dto.LoginResponse;
import com.carbon.platform.system.dto.UserInfoVO;
import com.carbon.platform.system.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口。
 */
@Tag(name = "01-认证", description = "登录、当前用户信息")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "登录")
    @PostMapping("/login")
    @OperLog(title = "用户登录", businessType = OperLog.BusinessType.LOGIN, saveParam = false)
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(authService.login(request), "登录成功");
    }

    @Operation(summary = "获取当前登录用户信息")
    @GetMapping("/me")
    @OperLog(title = "获取用户信息", businessType = OperLog.BusinessType.QUERY)
    public R<UserInfoVO> me() {
        return R.ok(authService.currentUserInfo());
    }

    @Operation(summary = "退出登录（前端删除 token 即可，此接口仅用于审计留痕）")
    @PostMapping("/logout")
    @OperLog(title = "用户登出", businessType = OperLog.BusinessType.LOGIN)
    public R<Void> logout() {
        return R.ok(null, "已退出登录");
    }
}

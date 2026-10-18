package com.carbon.platform.system.dto;

import lombok.Data;

import java.util.List;

@Data
public class LoginResponse {
    private String token;
    private Long userId;
    private String username;
    private String nickname;
    private String dept;
    private List<String> roles;
    /** token 有效期（秒） */
    private Long expireSeconds;
}

package com.carbon.platform.system.dto;

import lombok.Data;

import java.util.List;

@Data
public class UserInfoVO {
    private Long userId;
    private String username;
    private String nickname;
    private String dept;
    private List<String> roles;
    /** 权限标识，前端用于按钮级控制 */
    private List<String> permissions;
}

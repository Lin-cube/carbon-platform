package com.carbon.platform.system.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统用户。
 */
@Data
@TableName("sys_user")
public class SysUser implements Serializable {

    @TableId(value = "user_id", type = IdType.AUTO)
    private Long userId;

    private String username;
    private String nickname;
    /** BCrypt 密文，序列化时必须忽略 */
    @TableField(select = false)
    private String password;
    private String dept;
    private String phone;
    /** 1 正常 / 0 停用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 非数据库字段：角色标识集合 */
    @TableField(exist = false)
    private java.util.List<String> roles;
}

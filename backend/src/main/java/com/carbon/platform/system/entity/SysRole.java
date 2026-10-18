package com.carbon.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 系统角色。
 * <p>角色约定：admin（管理员）/ operator（运维人员）/ viewer（只读）
 */
@Data
@TableName("sys_role")
public class SysRole implements Serializable {

    @TableId(value = "role_id", type = IdType.AUTO)
    private Long roleId;
    private String roleKey;
    private String roleName;
    private String remark;
}

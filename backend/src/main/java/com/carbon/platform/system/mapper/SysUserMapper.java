package com.carbon.platform.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.platform.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /** 查询用户拥有的角色标识 */
    @Select("""
            SELECT r.role_key
            FROM sys_role r
            JOIN sys_user_role ur ON ur.role_id = r.role_id
            WHERE ur.user_id = #{userId}
            """)
    List<String> selectRoleKeys(@Param("userId") Long userId);

    @Select("""
            SELECT u.* FROM sys_user u WHERE u.username = #{username} LIMIT 1
            """)
    SysUser selectByUsername(@Param("username") String username);
}

package com.carbon.platform.common.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbon.platform.system.entity.SysRole;
import com.carbon.platform.system.entity.SysUser;
import com.carbon.platform.system.mapper.SysRoleMapper;
import com.carbon.platform.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 初始化 RBAC 基础数据（幂等）。
 *
 * <p>用代码而非 SQL 脚本初始化，是为了让 BCrypt 密文由 {@link PasswordEncoder}
 * 现场生成，避免在仓库里硬编码哈希值（不同 BCrypt 强度/盐值不可复用）。
 *
 * <p>⚠️ 默认口令仅用于开发与演示，正式部署必须修改。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DataInitializer {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    private record RoleSeed(String key, String name, String remark) {
    }

    private record UserSeed(String username, String password, String nickname,
                            String dept, String roleKey) {
    }

    private static final RoleSeed[] ROLES = {
            new RoleSeed("admin", "系统管理员", "全部权限，含用户与日志管理"),
            new RoleSeed("operator", "运维人员", "能耗查询、导入、导出、AI 问答"),
            new RoleSeed("viewer", "只读用户", "仅能耗查询与 AI 问答"),
    };

    private static final UserSeed[] USERS = {
            new UserSeed("admin", "admin123", "系统管理员", "科大讯飞人工智能学院", "admin"),
            new UserSeed("operator", "operator123", "运维人员", "建筑能源管理部", "operator"),
            new UserSeed("viewer", "viewer123", "只读用户", "教务处", "viewer"),
    };

    @Bean
    public ApplicationRunner initRbacData() {
        return args -> {
            // 1. 角色
            for (RoleSeed rs : ROLES) {
                Long exists = roleMapper.selectCount(
                        Wrappers.<SysRole>lambdaQuery().eq(SysRole::getRoleKey, rs.key()));
                if (exists == null || exists == 0) {
                    SysRole role = new SysRole();
                    role.setRoleKey(rs.key());
                    role.setRoleName(rs.name());
                    role.setRemark(rs.remark());
                    roleMapper.insert(role);
                    log.info("初始化角色: {} ({})", rs.name(), rs.key());
                }
            }

            // 2. 用户 + 角色关联
            for (UserSeed us : USERS) {
                SysUser exist = userMapper.selectByUsername(us.username());
                Long userId;
                if (exist == null) {
                    SysUser user = new SysUser();
                    user.setUsername(us.username());
                    user.setPassword(passwordEncoder.encode(us.password()));
                    user.setNickname(us.nickname());
                    user.setDept(us.dept());
                    user.setStatus(1);
                    userMapper.insert(user);
                    userId = user.getUserId();
                    log.warn("初始化默认用户: {} / {} （请在生产环境修改密码）",
                            us.username(), us.password());
                } else {
                    userId = exist.getUserId();
                }

                SysRole role = roleMapper.selectOne(
                        Wrappers.<SysRole>lambdaQuery().eq(SysRole::getRoleKey, us.roleKey()));
                if (role != null) {
                    Integer bound = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM sys_user_role WHERE user_id=? AND role_id=?",
                            Integer.class, userId, role.getRoleId());
                    if (bound == null || bound == 0) {
                        jdbcTemplate.update(
                                "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                                userId, role.getRoleId());
                        log.info("绑定角色: {} -> {}", us.username(), us.roleKey());
                    }
                }
            }
        };
    }
}

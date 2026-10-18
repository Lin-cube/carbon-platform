package com.carbon.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 碳能智控运维平台 后端服务入口。
 *
 * <p>模块划分：
 * <ul>
 *   <li>{@code common}    —— 统一响应、异常、审计切面、JWT、配置</li>
 *   <li>{@code system}    —— 用户 / 角色 / 权限 / 操作日志（RBAC + 审计留痕）</li>
 *   <li>{@code energy}    —— 统一能耗数据集查询、建筑与园区维表</li>
 *   <li>{@code analytics} —— 时段汇总、COP 计算、异常分析、报表导出</li>
 *   <li>{@code ai}        —— AI 服务客户端（Mock / HTTP 双实现，对接角色 B）</li>
 * </ul>
 *
 * <p>排除 {@link UserDetailsServiceAutoConfiguration}：本项目采用 JWT 无状态认证，
 * 不需要 Spring Security 默认的内存用户，否则启动时会打印随机生成密码造成误解。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@MapperScan("com.carbon.platform.**.mapper")
@EnableAsync
public class CarbonPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(CarbonPlatformApplication.class, args);
    }
}

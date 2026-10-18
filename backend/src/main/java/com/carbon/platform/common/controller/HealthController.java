package com.carbon.platform.common.controller;

import com.carbon.platform.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统健康与数据概览接口。
 */
@Tag(name = "00-系统", description = "健康检查、数据规模概览")
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    @Operation(summary = "健康检查")
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("time", LocalDateTime.now());
        m.put("service", "carbon-platform-backend");
        return R.ok(m);
    }

    /**
     * 数据规模概览的缓存。
     *
     * <h3>为什么需要缓存</h3>
     * 实测各查询耗时：{@code COUNT(*) fact_energy_hourly} 单项就需 <b>426ms</b>
     * （InnoDB 的 COUNT(*) 必须扫描索引，600 万行无法再快），
     * 而 MIN/MAX(ts) 仅 0.2ms、{@code COUNT(*)} anomaly_event 仅 4ms。
     * 该接口原耗时 1824ms，是看板首屏唯一的性能瓶颈。
     *
     * <h3>为什么不用 Redis</h3>
     * 这些统计值只在<b>导入数据时</b>变化，且本系统是单实例部署。
     * 进程内缓存即可达到同样效果，且零额外运维成本（无需多一个 Redis 进程）。
     * Redis 的适用场景是多实例共享缓存 / 需要跨重启持久化 / 分布式锁 —— 本项目都不涉及。
     *
     * <h3>失效策略</h3>
     * <b>主要靠主动失效</b>：导入完成后立即清除缓存（见 DataImportService 中的
     * {@code invalidateOverviewCache()}），因此导入后刷新看板立刻看到新数据。
     * <p><b>TTL 只作兜底</b>，设为 10 分钟。为什么不用短 TTL：这些统计值只在导入时变化，
     * 短 TTL 会让看板每隔几十秒就遇到一次 400ms+ 的冷查询，
     * 反而造成"时快时慢"的卡顿感（实测 60 秒 TTL 时，过期后首个请求需 434ms）。
     */
    private static final java.util.concurrent.atomic.AtomicReference<
            java.util.Map.Entry<Long, Map<String, Object>>> OVERVIEW_CACHE =
            new java.util.concurrent.atomic.AtomicReference<>();

    private static final long OVERVIEW_TTL_MS = 10 * 60_000L;

    /** 供导入流程调用：数据变更后主动清除概览缓存 */
    public static void invalidateOverviewCache() {
        OVERVIEW_CACHE.set(null);
    }

    @Operation(summary = "数据规模概览（用于看板顶部指标卡）")
    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        long now = System.currentTimeMillis();
        Map.Entry<Long, Map<String, Object>> cached = OVERVIEW_CACHE.get();
        if (cached != null && now - cached.getKey() < OVERVIEW_TTL_MS) {
            return R.ok(cached.getValue());
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("buildingCount", count("dim_building"));
        m.put("siteCount", count("dim_site"));
        // 这一项是耗时主因，但它是看板必须展示的真实数据规模，不能省略或改为估算值
        m.put("energyRows", count("fact_energy_hourly"));
        m.put("anomalyEvents", count("fact_anomaly_event"));
        m.put("gradeACount", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dim_building WHERE data_grade='A'", Long.class));
        m.put("gradeBCount", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dim_building WHERE data_grade='B'", Long.class));
        m.put("timeRange", jdbcTemplate.queryForMap(
                "SELECT MIN(ts) AS start, MAX(ts) AS end FROM fact_energy_hourly"));
        OVERVIEW_CACHE.set(new java.util.AbstractMap.SimpleImmutableEntry<>(now, m));
        return R.ok(m);
    }

    private Long count(String table) {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0L : n;
    }
}

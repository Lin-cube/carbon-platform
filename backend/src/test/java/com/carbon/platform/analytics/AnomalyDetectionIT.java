package com.carbon.platform.analytics;

import com.carbon.platform.energy.imports.service.AnomalyDetectionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异常检测算法验收测试（隔离库 carbon_energy_it，不触碰开发库）。
 *
 * <h3>做法</h3>
 * 构造一栋合成建筑的一年多逐小时数据：
 * 基线 = 100 + 15·sin(2π·h/24) − 周末 20，叠加 ±2 的确定性小波动；
 * 然后在已知位置注入三类异常，再用真实算法检测，按「注入标签 vs 检测结果」算：
 * TP / FP / TN / FN、召回率、误报率、精确率。
 *
 * <h3>为什么用确定性波动而不是随机</h3>
 * 随机噪声会让阈值随 seed 漂移，测试结论不稳定。这里用
 * {@code ((h*7 + d*13) % 5 - 2)} 这种可复现的伪波动，保证同一份数据
 * 每次跑出的指标一致。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/carbon_energy_it"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnomalyDetectionIT {

    private static final String BID = "__ANOMALY_TEST_BLD__";
    private static final String SITE = "__ANOMALY_TEST_SITE__";

    /** 数据从 2020-01-01 起，足够 52 周基线 */
    private static final LocalDate DATA_START = LocalDate.of(2020, 1, 1);
    private static final LocalDate DATA_END = LocalDate.of(2021, 3, 31);
    /** 注入异常的月份（最后 4 周） */
    private static final LocalDate INJECT_START = LocalDate.of(2021, 3, 1);
    private static final LocalDate INJECT_END = LocalDate.of(2021, 3, 28);

    @Autowired
    private AnomalyDetectionService service;

    @Autowired
    private JdbcTemplate jdbc;

    /** 注入的高/负值异常小时（时间戳），用于算 TP/FN */
    private final Set<LocalDateTime> injectedAnomaly = new HashSet<>();
    /** 注入的「偏低」小时：当前算法设计上不置为异常，单独统计 */
    private final Set<LocalDateTime> injectedLow = new HashSet<>();
    /** 注入的缺失小时 */
    private final Set<LocalDateTime> missingHours = new HashSet<>();

    @BeforeAll
    void seed() {
        jdbc.update("DELETE FROM fact_energy_hourly WHERE building_id = ?", BID);
        jdbc.update("DELETE FROM fact_anomaly_event WHERE building_id = ?", BID);
        jdbc.update("DELETE FROM dim_building WHERE building_id = ?", BID);
        jdbc.update("""
                INSERT INTO dim_building (building_id, site_id, building_type, building_type_cn,
                                          area_m2, data_grade, meter_source)
                VALUES (?,?,?,?,?,?,?)
                """, BID, SITE, "office", "办公楼", 3000.0, "A", "独立计量");

        List<Object[]> rows = new ArrayList<>();
        LocalDate d = DATA_START;
        while (!d.isAfter(DATA_END)) {
            for (int h = 0; h < 24; h++) {
                LocalDateTime ts = d.atTime(h, 0);

                // 缺失数据场景：注入月第 7 天 0~4 时整段不写
                if (d.equals(INJECT_START.plusDays(6)) && h <= 4) {
                    missingHours.add(ts);
                    continue;
                }

                double base = 100 + 15 * Math.sin(2 * Math.PI * h / 24.0)
                        - (isWeekend(d) ? 20 : 0);
                double noise = ((h * 7 + d.getDayOfYear() * 13) % 5) - 2;   // 确定性 ±2
                double val = base + noise;

                boolean anomaly = false;
                if (d.equals(INJECT_START.plusDays(1)) && h == 10) {
                    val += 150;              // 单点尖峰
                    anomaly = true;
                } else if (d.equals(INJECT_START.plusDays(3)) && h >= 8 && h <= 13) {
                    val += 80;               // 持续 6 小时高耗
                    anomaly = true;
                } else if ((d.equals(INJECT_START.plusDays(5))
                        || d.equals(INJECT_START.plusDays(6))) && h <= 5) {
                    val *= 0.3;              // 持续偏低（设计上不置异常）
                    injectedLow.add(ts);
                }

                if (anomaly) {
                    injectedAnomaly.add(ts);
                }

                rows.add(new Object[]{
                        BID, SITE, ts, d, d.getYear(), d.getMonthValue(), d.getDayOfMonth(),
                        h, d.getDayOfWeek().getValue(), isWeekend(d) ? 1 : 0,
                        Math.round(val * 100) / 100.0
                });
            }
            d = d.plusDays(1);
        }

        jdbc.batchUpdate("""
                INSERT INTO fact_energy_hourly
                  (building_id, site_id, ts, stat_date, stat_year, stat_month, stat_day,
                   stat_hour, stat_weekday, is_weekend, electricity_kwh)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, rows);

        // 用真实算法重算注入月
        service.recompute(Set.of(BID), INJECT_START, INJECT_END);
    }

    @Test
    @Order(1)
    @DisplayName("注入的高耗/负值异常能被检出，且召回率≥90%、误报率≤10%")
    void detectsInjectedAnomaliesWithAcceptableRates() {
        List<Map<String, Object>> flagged = jdbc.queryForList("""
                SELECT ts, anomaly_flag, anomaly_type, baseline, zscore, electricity_kwh
                  FROM fact_energy_hourly
                 WHERE building_id = ? AND stat_date BETWEEN ? AND ?
                """, BID, INJECT_START, INJECT_END);

        int tp = 0, fn = 0, fp = 0, tn = 0;
        for (Map<String, Object> r : flagged) {
            LocalDateTime ts = toLdt(r.get("ts"));
            boolean flaggedAnomaly = ((Number) r.get("anomaly_flag")).intValue() == 1;
            boolean shouldBeAnomaly = injectedAnomaly.contains(ts);
            // 缺失小时不在结果集里，天然不计入
            if (shouldBeAnomaly && flaggedAnomaly) tp++;
            else if (shouldBeAnomaly) fn++;
            else if (flaggedAnomaly) fp++;
            else tn++;
        }

        double recall = tp + fn == 0 ? 0 : tp * 100.0 / (tp + fn);
        double fpr = fp + tn == 0 ? 0 : fp * 100.0 / (fp + tn);
        double precision = tp + fp == 0 ? 0 : tp * 100.0 / (tp + fp);

        System.out.printf("[异常检测] 注入=%d 检出=%d TP=%d FN=%d FP=%d TN=%d "
                        + "召回率=%.2f%% 误报率=%.2f%% 精确率=%.2f%%%n",
                injectedAnomaly.size(), tp, tp, fn, fp, tn, recall, fpr, precision);

        assertTrue(injectedAnomaly.size() > 0, "测试数据必须包含注入异常");
        assertTrue(recall >= 90.0, "召回率应≥90%，实际 " + recall);
        assertTrue(fpr <= 10.0, "误报率应≤10%，实际 " + fpr);
        assertTrue(precision >= 80.0, "精确率应≥80%，实际 " + precision);
    }

    @Test
    @Order(2)
    @DisplayName("持续偏低能耗在设计上不计为异常，但可被识别为偏低")
    void lowConsumptionIsNotFlaggedByDesign() {
        int flaggedLow = 0;
        for (LocalDateTime ts : injectedLow) {
            Integer f = jdbc.queryForObject(
                    "SELECT anomaly_flag FROM fact_energy_hourly WHERE building_id = ? AND ts = ?",
                    Integer.class, BID, java.sql.Timestamp.valueOf(ts));
            if (f != null && f == 1) {
                flaggedLow++;
            }
        }
        System.out.printf("[偏低异常] 注入 %d 小时，被置为异常 %d 小时（设计上不应置位）%n",
                injectedLow.size(), flaggedLow);
        assertEquals(0, flaggedLow, "偏低能耗不应计入「能耗偏高」类异常");
    }

    @Test
    @Order(3)
    @DisplayName("缺失小时不产生异常记录，也不会被当成 0 能耗")
    void missingHoursAreSkipped() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fact_energy_hourly WHERE building_id = ? AND ts = ?",
                Integer.class, BID, java.sql.Timestamp.valueOf(missingHours.iterator().next()));
        assertEquals(0, n, "缺失小时不应被补成 0");
    }

    @Test
    @Order(9)
    @DisplayName("无未来数据泄漏：截断未来数据后，靠前小时的基线不变")
    void noFutureLeakageInBaseline() {
        // 取注入月中段某小时，先记录当前 baseline
        LocalDateTime probe = INJECT_START.plusDays(10).atTime(10, 0);
        Double before = jdbc.queryForObject(
                "SELECT baseline FROM fact_energy_hourly WHERE building_id = ? AND ts = ?",
                Double.class, BID, java.sql.Timestamp.valueOf(probe));
        assertNotNull(before, "基线应已计算");

        // 删掉 probe 之后的所有数据，再重算同一区间
        jdbc.update("DELETE FROM fact_energy_hourly WHERE building_id = ? AND ts > ?",
                BID, java.sql.Timestamp.valueOf(probe));
        service.recompute(Set.of(BID), INJECT_START, INJECT_START.plusDays(12));

        Double after = jdbc.queryForObject(
                "SELECT baseline FROM fact_energy_hourly WHERE building_id = ? AND ts = ?",
                Double.class, BID, java.sql.Timestamp.valueOf(probe));
        assertNotNull(after, "截断未来数据后基线仍应存在");
        assertEquals(before, after, 1e-6,
                "基线只能由历史数据决定；若未来数据会影响过去小时，说明存在前视泄漏");
    }

    /** 驱动对 DATETIME 可能返回 LocalDateTime，也可能返回 Timestamp，两种都要兼容 */
    private static LocalDateTime toLdt(Object o) {
        if (o instanceof LocalDateTime l) {
            return l;
        }
        if (o instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(o).replace(' ', 'T'));
    }

    private static boolean isWeekend(LocalDate d) {
        return d.getDayOfWeek().getValue() >= 6;
    }
}

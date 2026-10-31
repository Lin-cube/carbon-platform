package com.carbon.platform.imports;

import com.carbon.platform.energy.imports.controller.DataImportController;
import com.carbon.platform.energy.imports.dto.ImportRequest;
import com.carbon.platform.energy.imports.dto.ImportResultVO;
import com.carbon.platform.energy.imports.dto.UploadResultVO;
import com.carbon.platform.energy.imports.service.AggregateRefreshService;
import com.carbon.platform.energy.imports.service.DataImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * 导入事务与一致性集成测试。
 *
 * <p><b>隔离数据库</b>：连接 carbon_energy_it（由 mysqldump --no-data 从开发库复制表结构），
 * 不触碰开发/生产库。每个用例前清空相关表。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/carbon_energy_it"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true",
        "carbon.import.tmp-dir=target/it-import-tmp"
})
class ImportTransactionIT {

    @Autowired
    private DataImportService importService;

    @Autowired
    private DataImportController importController;

    @Autowired
    private JdbcTemplate jdbc;

    /** 用于注入「聚合刷新失败」故障 */
    @MockitoSpyBean
    private AggregateRefreshService aggregateRefreshService;

    private static final String[] TABLES = {
            "fact_energy_hourly", "fact_anomaly_event", "dim_building", "dim_site",
            "agg_building_daily", "agg_building_monthly", "agg_site_hourly",
            "agg_time_hourly", "agg_anomaly_daily", "data_import_log"
    };

    @BeforeEach
    void resetDatabase() {
        reset(aggregateRefreshService);
        for (String t : TABLES) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    // ================================================================ 用例

    @Test
    @DisplayName("① 正常导入：事实/维表/聚合/异常四层一致，批次状态为成功")
    void normalImportShouldBeConsistentAcrossLayers() {
        String csv = csv("T1", "SiteA", 24, 100.0);
        ImportResultVO r = doImport(csv);

        assertEquals(24, r.getSuccessRows(), "应写入 24 行");
        assertEquals("成功", r.getStatus());
        assertEquals("OK", r.getDerivedStatus());
        assertEquals(24, count("fact_energy_hourly"), "事实表行数");
        assertEquals(1, count("dim_building"), "维表应登记 1 栋建筑");
        assertEquals(1, count("dim_site"), "维表应登记 1 个园区");
        assertTrue(count("agg_building_daily") > 0, "日聚合应有数据");
        assertTrue(count("agg_building_monthly") > 0, "月聚合应有数据");

        // 批次记录完整可追溯
        var job = jdbc.queryForMap(
                "SELECT status, derived_status, start_date, end_date, file_id FROM data_import_log WHERE batch_no = ?",
                r.getBatchNo());
        assertEquals("成功", job.get("status"));
        assertEquals("OK", job.get("derived_status"));
        assertNotNull(job.get("start_date"));
        assertNotNull(job.get("file_id"));
    }

    @Test
    @DisplayName("② 重复导入同一文件：幂等，事实表不翻倍")
    void duplicateImportShouldBeIdempotent() {
        String csv = csv("T1", "SiteA", 24, 100.0);
        ImportResultVO first = doImport(csv);
        assertEquals(24, count("fact_energy_hourly"));

        ImportResultVO second = doImport(csv);
        assertEquals(24, count("fact_energy_hourly"), "重复导入后行数必须仍为 24");
        assertEquals(24, second.getReplacedRows(), "第二次应覆盖 24 行");
        assertEquals(24, second.getSuccessRows());
        assertEquals(2, count("data_import_log"), "两次导入各留一条审计记录");
        assertNotNull(first.getBatchNo());
    }

    @Test
    @DisplayName("③ 缺少必填字段映射：解析前拒绝，库中零写入")
    void missingRequiredMappingShouldRejectBeforeAnyWrite() {
        String csv = csv("T1", "SiteA", 24, 100.0);
        UploadResultVO up = upload(csv);

        ImportRequest req = request(up.getFileId(), false);
        // 去掉时间字段映射
        req.getMappings().removeIf(m -> "timestamp".equals(m.getTargetField()));

        assertThrows(Exception.class, () -> importService.execute(req));
        assertEquals(0, count("fact_energy_hourly"), "拒绝后不得有任何事实数据");
        assertEquals(0, count("data_import_log"), "拒绝后不得产生批次记录");
    }

    @Test
    @DisplayName("④ 脏数据：负值/空值/非数值被剔除并留诊断，正常行入库")
    void dirtyRowsShouldBeSkippedWithDiagnostics() {
        StringBuilder sb = new StringBuilder(header());
        LocalDateTime t = LocalDateTime.of(2024, 3, 1, 0, 0);
        for (int i = 0; i < 10; i++) {
            sb.append("T1,SiteA,办公楼,").append(fmt(t.plusHours(i))).append(",100.0,1000\n");
        }
        sb.append("T1,SiteA,办公楼,").append(fmt(t.plusHours(10))).append(",,1000\n");        // 空值
        sb.append("T1,SiteA,办公楼,").append(fmt(t.plusHours(11))).append(",-50,1000\n");      // 负值
        sb.append("T1,SiteA,办公楼,").append(fmt(t.plusHours(12))).append(",N/A,1000\n");      // 非数值

        ImportResultVO r = doImport(sb.toString());
        assertEquals(10, r.getSuccessRows(), "只有 10 行有效");
        assertEquals(3, r.getSkippedRows(), "3 行被剔除");
        assertEquals(0, r.getFailedRows());
        assertEquals(10, count("fact_energy_hourly"));
        assertTrue(r.getErrors().size() >= 3, "每条被剔除的行都应有诊断");
    }

    @Test
    @DisplayName("⑤ 写入中断：当前块事务回滚，原有数据不被删掉（核心原子性）")
    void chunkFailureShouldRollbackDeleteAndPreserveOriginalData() {
        // 预置一条「正确历史数据」
        jdbc.update("""
                INSERT INTO fact_energy_hourly
                  (building_id, site_id, ts, stat_date, stat_year, stat_month, stat_day,
                   stat_hour, stat_weekday, is_weekend, season, electricity_kwh,
                   anomaly_flag, anomaly_type, severity, data_quality_flag,
                   data_quality_type, data_grade, import_batch_no)
                VALUES ('T1','SiteA','2024-03-01 00:00:00','2024-03-01',2024,3,1,0,5,0,'春季',777.0,
                        -1,'未评估','未评估',-1,'未评估','A','SEED')
                """);
        assertEquals(1, count("fact_energy_hourly"));
        assertEquals(777.0, jdbc.queryForObject(
                "SELECT electricity_kwh FROM fact_energy_hourly WHERE building_id='T1'", Double.class));

        // 同一 (T1, 00:00) 会被 DELETE 后重插；同块内再放一条超长 building_id 触发插入失败
        String longId = "X".repeat(120);
        StringBuilder sb = new StringBuilder(header());
        sb.append("T1,SiteA,办公楼,2024-03-01T00:00:00,100.0,1000\n");
        sb.append(longId).append(",SiteA,办公楼,2024-03-01T01:00:00,100.0,1000\n");

        ImportRequest req = request(upload(sb.toString()).getFileId(), false);
        assertThrows(Exception.class, () -> importService.execute(req),
                "超长建筑编号应在写入时失败");

        // 关键断言：DELETE 被一起回滚，原始 777.0 仍在
        assertEquals(1, count("fact_energy_hourly"), "失败块不得留下半成品");
        assertEquals(777.0, jdbc.queryForObject(
                "SELECT electricity_kwh FROM fact_energy_hourly WHERE building_id='T1'", Double.class),
                "原有正确数据必须保持不变");
        assertEquals("失败", jdbc.queryForObject(
                "SELECT status FROM data_import_log ORDER BY id DESC LIMIT 1", String.class),
                "批次应标记为失败，便于排查与回滚");
    }

    @Test
    @DisplayName("⑥ 聚合失败：事实数据保留，批次标记待重算，可重算恢复")
    void derivedFailureShouldBeRecoverableByRecompute() {
        doThrow(new RuntimeException("模拟聚合刷新失败"))
                .when(aggregateRefreshService).refresh(any(), any(), any());

        ImportResultVO r = doImport(csv("T1", "SiteA", 24, 100.0));

        assertEquals(24, count("fact_energy_hourly"), "事实数据必须保留");
        assertEquals("FAILED", r.getDerivedStatus());
        assertTrue(r.getStatus().contains("派生数据待重算"), "状态应提示待重算");

        Long id = jdbc.queryForObject("SELECT id FROM data_import_log ORDER BY id DESC LIMIT 1", Long.class);
        assertEquals("FAILED", jdbc.queryForObject(
                "SELECT derived_status FROM data_import_log WHERE id = ?", String.class, id));

        // 恢复：放开故障后重算
        reset(aggregateRefreshService);
        importController.recompute(id);

        assertEquals("OK", jdbc.queryForObject(
                "SELECT derived_status FROM data_import_log WHERE id = ?", String.class, id));
        assertEquals("成功", jdbc.queryForObject(
                "SELECT status FROM data_import_log WHERE id = ?", String.class, id));
        assertTrue(count("agg_building_daily") > 0, "重算后聚合应有数据");
        assertEquals(24, count("fact_energy_hourly"), "重算不得重复写事实表");
    }

    @Test
    @DisplayName("⑦ 重复提交保护：存在在途批次时拒绝再次执行")
    void inflightJobShouldBlockDuplicateExecution() {
        String csv = csv("T1", "SiteA", 24, 100.0);
        UploadResultVO up = upload(csv);

        // 伪造一条「导入中」批次
        jdbc.update("""
                INSERT INTO data_import_log
                  (batch_no, file_id, file_name, file_type, total_rows, success_rows, failed_rows,
                   status, dry_run, derived_status, operator)
                VALUES ('IMP-INFLIGHT', ?, 'x.csv', 'CSV', 10, 0, 0, '导入中', 0, 'PENDING', 'test')
                """, up.getFileId());

        ImportRequest req = request(up.getFileId(), false);
        Exception e = assertThrows(Exception.class, () -> importService.execute(req));
        assertTrue(String.valueOf(e.getMessage()).contains("正在进行"), "应提示已有导入进行中");
        assertEquals(0, count("fact_energy_hourly"));
    }

    @Test
    @DisplayName("⑧ 并发导入同一文件：仅一个成功，另一个被闸门拒绝")
    void concurrentSameFileShouldAllowOnlyOne() throws Exception {
        String csv = csv("T1", "SiteA", 24, 100.0);
        String fileId = upload(csv).getFileId();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            tasks.add(() -> {
                barrier.await();
                try {
                    importService.execute(request(fileId, false));
                    ok.incrementAndGet();
                } catch (Exception e) {
                    rejected.incrementAndGet();
                }
                return null;
            });
        }
        for (Future<Void> f : pool.invokeAll(tasks)) {
            f.get();
        }
        pool.shutdown();

        assertEquals(1, ok.get(), "只允许一个成功");
        assertEquals(1, rejected.get(), "另一个应被拒绝");
        assertEquals(24, count("fact_energy_hourly"), "不得产生重复行");
    }

    @Test
    @DisplayName("⑨ 大文件：50000 行分批写入，总量与批次统计一致")
    void largeImportShouldBeConsistent() {
        int buildings = 50;
        int hours = 1000;
        StringBuilder sb = new StringBuilder(header());
        LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
        for (int b = 0; b < buildings; b++) {
            for (int h = 0; h < hours; h++) {
                sb.append("B").append(b).append(",SiteA,办公楼,")
                        .append(fmt(base.plusHours(h))).append(",100.0,1000\n");
            }
        }
        long t0 = System.currentTimeMillis();
        ImportResultVO r = doImport(sb.toString());
        long cost = System.currentTimeMillis() - t0;

        assertEquals(buildings * hours, r.getSuccessRows());
        assertEquals(buildings * hours, count("fact_energy_hourly"));
        assertEquals(buildings, count("dim_building"));
        assertEquals("成功", r.getStatus());
        System.out.printf("[大文件] %d 行导入完成，耗时 %d ms（%d 行/秒）%n",
                buildings * hours, cost, (buildings * hours * 1000L / Math.max(cost, 1)));
    }

    // ================================================================ 工具

    private static final java.time.format.DateTimeFormatter TS_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static String fmt(LocalDateTime t) {
        return t.format(TS_FMT);
    }

    private static String header() {
        return "建筑编号,园区编号,建筑类型,监测时间,电力能耗,建筑面积\n";
    }

    /** 生成 from 起点连续 hours 小时的 CSV */
    private static String csv(String buildingId, String siteId, int hours, double kwh) {
        StringBuilder sb = new StringBuilder(header());
        LocalDateTime t = LocalDateTime.of(2024, 3, 1, 0, 0);
        for (int i = 0; i < hours; i++) {
            sb.append(buildingId).append(",").append(siteId).append(",办公楼,")
                    .append(fmt(t.plusHours(i))).append(",").append(kwh).append(",1000\n");
        }
        return sb.toString();
    }

    private UploadResultVO upload(String csv) {
        MockMultipartFile f = new MockMultipartFile(
                "file", "it.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        return importService.upload(f);
    }

    private ImportRequest request(String fileId, boolean dryRun) {
        ImportRequest req = new ImportRequest();
        req.setFileId(fileId);
        req.setDryRun(dryRun);
        List<ImportRequest.FieldMapping> ms = new ArrayList<>();
        ms.add(mapping("建筑编号", "building_id"));
        ms.add(mapping("园区编号", "site_id"));
        ms.add(mapping("建筑类型", "building_type_cn"));
        ms.add(mapping("监测时间", "timestamp"));
        ms.add(mapping("电力能耗", "electricity_kwh"));
        ms.add(mapping("建筑面积", "area_m2"));
        req.setMappings(ms);
        ImportRequest.CleanRules rules = new ImportRequest.CleanRules();
        rules.setTrimString(true);
        rules.setMissingStrategy("drop");
        rules.setDropNegative(true);
        rules.setDedupStrategy("byBuildingTime");
        rules.setElectricityUnit("kwh");
        rules.setUnitConsistencyCheck(false);
        req.setCleanRules(rules);
        return req;
    }

    private ImportRequest.FieldMapping mapping(String src, String target) {
        ImportRequest.FieldMapping m = new ImportRequest.FieldMapping();
        m.setSourceColumn(src);
        m.setTargetField(target);
        return m;
    }

    private ImportResultVO doImport(String csv) {
        return importService.execute(request(upload(csv).getFileId(), false));
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}

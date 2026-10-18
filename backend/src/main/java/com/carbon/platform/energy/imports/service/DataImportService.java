package com.carbon.platform.energy.imports.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.carbon.platform.common.exception.BizException;
import com.carbon.platform.common.result.ResultCode;
import com.carbon.platform.energy.imports.ImportTargetField;
import com.carbon.platform.energy.imports.dto.ImportRequest;
import com.carbon.platform.energy.imports.dto.ImportResultVO;
import com.carbon.platform.energy.imports.dto.UploadResultVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 数据导入服务：五步向导的后端实现。
 *
 * <p>步骤：① 上传与列识别 → ② 自动字段映射建议 → ③ 清洗规则 →
 * ④ 预览导入（dryRun） → ⑤ 落库 + 审计留痕。
 *
 * <p>设计取舍：上传的临时文件落在项目内 {@code .import-tmp/} 目录，
 * 由 fileId 关联；导入完成后不立即删除（便于审计追溯），
 * 由定时清理或运维手工清理，避免长期占用磁盘。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataImportService {

    private final JdbcTemplate jdbcTemplate;
    /** AI 字段映射规划器：LLM 优先、规则兜底 */
    private final FieldMappingPlanner mappingPlanner;
    /** 导入后增量刷新预聚合表（否则看板看不到新数据） */
    private final AggregateRefreshService aggregateRefreshService;

    @Value("${carbon.import.tmp-dir:.import-tmp}")
    private String tmpDir;

    @Value("${carbon.import.max-rows:200000}")
    private int maxRows;

    private Path tmpPath;

    /** 预置的日期时间格式，按顺序尝试 */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyyMMddHH"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
    );

    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?$");

    @PostConstruct
    void init() throws IOException {
        Path base = Paths.get(tmpDir);
        tmpPath = base.isAbsolute() ? base : Paths.get(System.getProperty("user.dir")).resolve(base);
        Files.createDirectories(tmpPath);
        log.info("数据导入临时目录: {}", tmpPath.toAbsolutePath());
    }

    // ================================================================
    // 步骤一：上传 + 列识别 + 自动映射建议
    // ================================================================
    public UploadResultVO upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST, "上传文件为空");
        }
        String original = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "unnamed.csv";
        String lower = original.toLowerCase(Locale.ROOT);

        boolean isExcel = lower.endsWith(".xlsx") || lower.endsWith(".xls");
        boolean isCsv = lower.endsWith(".csv") || lower.endsWith(".txt");
        if (!isExcel && !isCsv) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "仅支持 CSV / XLSX / XLS 格式，当前文件：" + original);
        }

        String fileId = UUID.randomUUID().toString().replace("-", "");
        Path target = tmpPath.resolve(fileId + (isExcel ? ".xlsx" : ".csv"));

        try {
            file.transferTo(target.toFile());
        } catch (IOException e) {
            throw new BizException(ResultCode.IMPORT_FAILED, "文件保存失败：" + e.getMessage());
        }

        List<String> warnings = new ArrayList<>();
        List<String> headers;
        List<List<String>> rows;

        if (isExcel) {
            List<List<String>> all = readExcel(target, warnings);
            if (all.isEmpty()) {
                throw new BizException(ResultCode.IMPORT_FAILED, "Excel 文件内容为空");
            }
            headers = all.get(0);
            rows = all.subList(1, all.size());
        } else {
            List<List<String>> all = readCsv(target, warnings);
            if (all.isEmpty()) {
                throw new BizException(ResultCode.IMPORT_FAILED, "CSV 文件内容为空");
            }
            headers = all.get(0);
            // 检测"无表头"情形：首行存在非文本数据（纯数字/时间）
            if (looksLikeDataRow(headers)) {
                warnings.add("首行疑似数据而非表头，已自动生成列名（列1、列2…）");
                rows = all;
                headers = new ArrayList<>();
                for (int i = 0; i < (all.isEmpty() ? 0 : all.get(0).size()); i++) {
                    headers.add("列" + (i + 1));
                }
            } else {
                rows = all.subList(1, all.size());
            }
        }

        if (rows.size() > maxRows) {
            warnings.add(String.format("文件共 %d 行，超过单次导入上限 %d 行，"
                    + "本次仅解析前 %d 行（可分批导入）", rows.size(), maxRows, maxRows));
            rows = rows.subList(0, maxRows);
        }

        // 补齐表头（行比表头长时）
        int maxCols = Math.max(headers.size(),
                rows.stream().mapToInt(List::size).max().orElse(0));
        while (headers.size() < maxCols) {
            String generated = "列" + (headers.size() + 1);
            headers.add(generated);
            warnings.add("发现超出表头数量的列，已自动命名：" + generated);
        }

        UploadResultVO vo = new UploadResultVO();
        vo.setFileId(fileId);
        vo.setFileName(original);
        vo.setFileType(isExcel ? "EXCEL" : "CSV");
        vo.setFileSize(file.getSize());
        vo.setHeaders(headers);
        vo.setTotalRows(rows.size());
        vo.setWarnings(warnings);

        // 列画像 + 自动映射
        List<UploadResultVO.ColumnInfo> cols = new ArrayList<>();
        for (int c = 0; c < headers.size(); c++) {
            UploadResultVO.ColumnInfo ci = new UploadResultVO.ColumnInfo();
            ci.setIndex(c);
            ci.setSourceColumn(headers.get(c));
            List<String> samples = new ArrayList<>();
            int nonEmpty = 0;
            int numeric = 0;
            int datetime = 0;
            int scanned = 0;
            for (List<String> r : rows) {
                if (scanned >= 200) break;
                scanned++;
                String v = c < r.size() ? r.get(c) : null;
                if (!StringUtils.hasText(v)) continue;
                v = v.trim();
                nonEmpty++;
                if (samples.size() < 3) samples.add(v);
                if (NUMERIC.matcher(v).matches()) numeric++;
                else if (parseTimestamp(v) != null) datetime++;
            }
            ci.setSampleNonEmpty(nonEmpty);
            ci.setSamples(samples);
            ci.setInferredType(nonEmpty == 0 ? "empty"
                    : numeric >= nonEmpty * 0.8 ? "number"
                    : datetime >= nonEmpty * 0.6 ? "datetime" : "string");

            cols.add(ci);
        }

        // ★ AI 批量规划导入：一次把所有列交给规划器（LLM 优先，规则兜底）
        FieldMappingPlanner.Plan plan = mappingPlanner.plan(cols);
        for (UploadResultVO.ColumnInfo ci : cols) {
            String tgt = plan.mapping().get(ci.getSourceColumn());
            if (tgt != null) {
                ci.setSuggestedTarget(tgt);
                Double conf = plan.confidence().get(ci.getSourceColumn());
                ci.setConfidence(conf == null ? 0.6 : Math.round(conf * 100) / 100.0);
                ci.setReason(plan.reason().getOrDefault(ci.getSourceColumn(), "自动匹配"));
            } else {
                ci.setConfidence(0.0);
                ci.setReason("未匹配到目标字段，默认忽略该列");
            }
        }
        vo.setColumns(cols);

        // 把"用了哪种策略"透出给前端，让用户知道是 AI 还是规则给出的建议
        UploadResultVO.MappingPlan meta = new UploadResultVO.MappingPlan();
        meta.setStrategy(plan.strategy());
        meta.setProvider(plan.provider());
        meta.setModel(plan.model());
        meta.setElapsedMs(plan.elapsedMs());
        meta.setFallbackReason(plan.fallbackReason());
        meta.setMappedCount(plan.mapping().size());
        meta.setTotalColumns(cols.size());
        vo.setMappingPlan(meta);

        // 预览前 8 行
        List<List<String>> preview = new ArrayList<>();
        for (int i = 0; i < Math.min(8, rows.size()); i++) {
            List<String> r = rows.get(i);
            List<String> fixed = new ArrayList<>();
            for (int c = 0; c < headers.size(); c++) {
                fixed.add(c < r.size() ? r.get(c) : "");
            }
            preview.add(fixed);
        }
        vo.setPreviewRows(preview);
        return vo;
    }

    // ================================================================
    // 步骤四：预览导入 / 步骤五：落库 + 审计
    // ================================================================
    public ImportResultVO execute(ImportRequest req) {
        Path file = findFile(req.getFileId());
        boolean dryRun = Boolean.TRUE.equals(req.getDryRun());

        List<String> warnings = new ArrayList<>();
        List<List<String>> all = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")
                ? readExcel(file, warnings) : readCsv(file, warnings);
        if (all.isEmpty()) {
            throw new BizException(ResultCode.IMPORT_FAILED, "文件内容为空或已被清理，请重新上传");
        }
        List<String> headers = all.get(0);
        List<List<String>> rows = all.size() > 1 ? all.subList(1, all.size()) : List.of();

        // 源列名 -> 列索引
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            colIndex.put(headers.get(i), i);
        }

        // 目标字段 -> 列索引（保留首个匹配，重复映射给出告警）
        Map<ImportTargetField, Integer> mapping = new LinkedHashMap<>();
        if (req.getMappings() != null) {
            for (ImportRequest.FieldMapping fm : req.getMappings()) {
                if (!StringUtils.hasText(fm.getTargetField())) continue;
                Integer idx = colIndex.get(fm.getSourceColumn());
                if (idx == null) {
                    warnings.add("源列不存在，已跳过：" + fm.getSourceColumn());
                    continue;
                }
                ImportTargetField tf = ImportTargetField.byColumn(fm.getTargetField()).orElse(null);
                if (tf == null) {
                    warnings.add("未知目标字段，已跳过：" + fm.getTargetField());
                    continue;
                }
                if (mapping.containsKey(tf)) {
                    warnings.add("目标字段重复映射，仅保留首个：" + tf.getLabel());
                    continue;
                }
                mapping.put(tf, idx);
            }
        }

        // 必填字段校验
        for (ImportTargetField f : ImportTargetField.all()) {
            if (f.isRequired() && !mapping.containsKey(f)) {
                throw new BizException(ResultCode.BAD_REQUEST,
                        "缺少必填字段映射：" + f.getLabel() + "（" + f.getColumn() + "）");
            }
        }

        ImportRequest.CleanRules rules = req.getCleanRules() == null
                ? new ImportRequest.CleanRules() : req.getCleanRules();

        ImportResultVO result = new ImportResultVO();
        result.setBatchNo("IMP" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + req.getFileId().substring(0, 6));
        result.setFileName(file.getFileName().toString());
        result.setTotalRows(rows.size());
        result.setDryRun(dryRun);
        result.setErrors(new ArrayList<>());

        // 收集每栋建筑的静态属性（类型、面积），供导入后登记维表使用。
        // 【为什么需要】建筑类型影响 3D 着色、类型分解图、看板类型饼图；
        // 建筑面积是计算能耗强度 EUI 与能耗评级 A–E 的前提。
        // 若只写事实表而不回填这两项，导入的建筑在界面上会显示「未知类型、无评级」。
        Map<String, String> bldType = new LinkedHashMap<>();
        Map<String, Double> bldArea = new LinkedHashMap<>();

        Map<String, Integer> fieldStats = new LinkedHashMap<>();
        for (ImportTargetField f : mapping.keySet()) {
            fieldStats.put(f.getColumn(), 0);
        }

        List<Object[]> batch = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        int success = 0, failed = 0, skipped = 0;
        int rowNum = 1;

        for (List<String> row : rows) {
            rowNum++;
            try {
                Map<ImportTargetField, String> raw = new EnumMap<>(ImportTargetField.class);
                for (Map.Entry<ImportTargetField, Integer> e : mapping.entrySet()) {
                    int idx = e.getValue();
                    String v = idx < row.size() ? row.get(idx) : null;
                    if (v != null && Boolean.TRUE.equals(rules.getTrimString())) {
                        v = v.trim();
                    }
                    raw.put(e.getKey(), v);
                }

                // 必填值校验
                String buildingId = raw.get(ImportTargetField.BUILDING_ID);
                if (!StringUtils.hasText(buildingId)) {
                    failed++;
                    addError(result, rowNum, "building_id", "建筑编号为空", "");
                    continue;
                }
                if (StringUtils.hasText(rules.getBuildingIdPrefix())
                        && !buildingId.contains("_")) {
                    buildingId = rules.getBuildingIdPrefix() + "_" + buildingId;
                }
                LocalDateTime ts = parseTimestamp(raw.get(ImportTargetField.TS));
                if (ts == null) {
                    failed++;
                    addError(result, rowNum, "timestamp", "时间格式无法识别", raw.get(ImportTargetField.TS));
                    continue;
                }

                // 数值解析
                Double elec = parseNumber(raw.get(ImportTargetField.ELECTRICITY_KWH));
                Double hvac = parseNumber(raw.get(ImportTargetField.HVAC_KWH));
                Double water = parseNumber(raw.get(ImportTargetField.WATER_M3));
                Double gas = parseNumber(raw.get(ImportTargetField.GAS_KWH));
                Double airTemp = parseNumber(raw.get(ImportTargetField.AIR_TEMPERATURE_C));
                Double humidity = parseNumber(raw.get(ImportTargetField.HUMIDITY_PCT));
                Double supply = parseNumber(raw.get(ImportTargetField.CHW_SUPPLY_TEMP_C));
                Double ret = parseNumber(raw.get(ImportTargetField.CHW_RETURN_TEMP_C));
                Double occ = parseNumber(raw.get(ImportTargetField.OCCUPANCY_DENSITY));

                // 建筑静态属性（同一建筑多行取首个非空值）
                if (buildingId != null) {
                    String t = raw.get(ImportTargetField.BUILDING_TYPE_CN);
                    if (t != null && !t.isBlank()) {
                        bldType.putIfAbsent(buildingId, t.trim());
                    }
                    Double a = parseNumber(raw.get(ImportTargetField.AREA_M2));
                    if (a != null && a > 0) {
                        bldArea.putIfAbsent(buildingId, a);
                    }
                }
                // 注意：建筑面积(area_m2)、建筑类型(building_type_cn) 不在事实表中，
                // 由维表 dim_building 通过 building_id 关联提供，因此不写入导入批次。

                // 单位换算
                if ("mwh".equalsIgnoreCase(rules.getElectricityUnit())) {
                    if (elec != null) elec *= 1000;
                    if (hvac != null) hvac *= 1000;
                }

                // 清洗：负值
                if (Boolean.TRUE.equals(rules.getDropNegative())) {
                    if ((elec != null && elec < 0) || (hvac != null && hvac < 0)
                            || (water != null && water < 0)) {
                        skipped++;
                        addError(result, rowNum, "value", "能耗为负值，已按清洗规则剔除", String.valueOf(elec));
                        continue;
                    }
                }

                // 清洗：缺失值策略
                String ms = rules.getMissingStrategy();
                if ("drop".equalsIgnoreCase(ms) && elec == null) {
                    skipped++;
                    continue;
                }
                if ("zero".equalsIgnoreCase(ms) && elec == null) {
                    elec = 0.0;
                }

                // 去重
                if ("byBuildingTime".equalsIgnoreCase(rules.getDedupStrategy())) {
                    String key = buildingId + "|" + ts;
                    if (!seenKeys.add(key)) {
                        skipped++;
                        continue;
                    }
                }

                // 统计字段成功解析数
                // 统计所有已映射字段的解析成功数。
                // 【曾经的 Bug】原先只统计 electricity/hvac/water/air_temperature 四个值字段，
                // 导致 building_id、timestamp、humidity_pct、gas_kwh 在前端「字段解析成功数」
                // 表格里恒显示 0%，用户会误以为这些字段没导进来（实测截图中就有此现象）。
                // 实际数据是正常入库的，纯粹是统计漏算，必须补齐以免误导。
                if (buildingId != null && !buildingId.isBlank()) {
                    fieldStats.merge("building_id", 1, Integer::sum);
                }
                if (ts != null) {
                    fieldStats.merge("timestamp", 1, Integer::sum);
                }
                if (elec != null) fieldStats.merge("electricity_kwh", 1, Integer::sum);
                if (hvac != null) fieldStats.merge("hvac_kwh", 1, Integer::sum);
                if (water != null) fieldStats.merge("water_m3", 1, Integer::sum);
                if (gas != null) fieldStats.merge("gas_kwh", 1, Integer::sum);
                if (airTemp != null) fieldStats.merge("air_temperature_c", 1, Integer::sum);
                if (humidity != null) fieldStats.merge("humidity_pct", 1, Integer::sum);
                if (supply != null) fieldStats.merge("chw_supply_temp_c", 1, Integer::sum);
                if (ret != null) fieldStats.merge("chw_return_temp_c", 1, Integer::sum);
                if (occ != null) fieldStats.merge("occupancy_density", 1, Integer::sum);

                String siteId = raw.get(ImportTargetField.SITE_ID);
                if (!StringUtils.hasText(siteId)) {
                    int us = buildingId.indexOf('_');
                    siteId = us > 0 ? buildingId.substring(0, us) : "IMPORTED";
                }

                double carbon = ((elec == null ? 0 : elec) + (gas == null ? 0 : gas)) * 0.5810;

                batch.add(new Object[]{
                        buildingId, siteId, java.sql.Timestamp.valueOf(ts),
                        ts.toLocalDate(), ts.getYear(), ts.getMonthValue(), ts.getDayOfMonth(),
                        ts.getHour(), (ts.getDayOfWeek().getValue() - 1),
                        ts.getDayOfWeek().getValue() >= 6 ? 1 : 0,
                        seasonOf(ts.getMonthValue()),
                        elec, water, hvac, gas,
                        round(carbon, 4),
                        airTemp, humidity, supply, ret, occ,
                        // data_grade：用户导入的是实测数据，记为 A 级（有独立计量），
                        // 可参与异常检测。原先硬编码 'B' 会让导入数据被标记为「未评估」。
                        "A"
                });
                success++;
            } catch (Exception ex) {
                failed++;
                addError(result, rowNum, "-", "处理异常：" + ex.getMessage(), "");
            }
        }

        result.setSuccessRows(success);
        result.setFailedRows(failed);
        result.setSkippedRows(skipped);
        result.setFieldStats(fieldStats);

        // 落库
        if (!dryRun && !batch.isEmpty()) {
            // ★ 覆盖语义：先删除库中已存在的 (building_id, ts) 组合，再插入。
            //
            // 【为什么必须做】事实表主键是自增 id，(building_id, ts) 上只有普通索引，
            // 因此重复导入同一文件会【无脑追加】出重复行。
            // 实测：同一文件导入 3 次后，T1 + 2016-03-01 00:00 这行在库里变成 3 条，
            // SUM 统计直接翻 3 倍，异常检测与预聚合表全部失真。
            //
            // 【覆盖语义的边界】只删除本次导入涉及的 (building_id, ts) 组合，
            // 其它时间、其它建筑的数据完全不受影响。即：
            //   · 重复导入同一时间段 → 覆盖（幂等）
            //   · 导入新时间段       → 追加
            // 这是能源数据最符合直觉的语义。
            int replaced = deleteExistingRows(batch);
            if (replaced > 0) {
                log.info("本次导入覆盖已存在的 {} 条历史记录（按 building_id + ts 匹配）", replaced);
            }
            result.setReplacedRows(replaced);

            final int BATCH = 1000;
            for (int i = 0; i < batch.size(); i += BATCH) {
                List<Object[]> part = batch.subList(i, Math.min(i + BATCH, batch.size()));
                jdbcTemplate.batchUpdate("""
                        INSERT INTO fact_energy_hourly
                          (building_id, site_id, ts, stat_date, stat_year, stat_month, stat_day,
                           stat_hour, stat_weekday, is_weekend, season,
                           electricity_kwh, water_m3, hvac_kwh, gas_kwh, carbon_kg,
                           air_temperature_c, humidity_pct,
                           chw_supply_temp_c, chw_return_temp_c, occupancy_density,
                           equipment_status, anomaly_flag, anomaly_type, severity,
                           data_quality_flag, data_quality_type, data_grade)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,
                                '正常', -1, '未评估', '未评估', -1, '未评估', ?)
                        """, part);
            }
            result.setStatus(failed > 0 ? "部分成功" : "成功");
            // 注册新建建筑到维表。
            // 【为什么必须做】事实表有数据但 dim_building 没有记录时，
            // 建筑列表接口（读维表）查不到这些建筑，导致：
            //   · 能耗查询的建筑下拉框里选不到
            //   · 3D 园区模型里不显示
            //   · 看板筛选与统计按建筑类型分组时丢失
            // 即"数据进了库，但系统不认识这栋楼"。实测踩过这个坑。
            registerNewBuildings(batch, bldType, bldArea);

            // ★ 增量刷新预聚合表。
            // 【为什么必须做】看板 KPI、趋势图、园区/类型分解、日/月粒度查询
            // 全部读预聚合表；导入只写事实表，不刷新的话用户在"看板"这个最主要的
            // 界面上看不到新导入的数据（实测确认：日粒度查询返回 0 个数据点）。
            java.util.Set<String> affectedBuildings = new java.util.LinkedHashSet<>();
            java.time.LocalDate minDate = null;
            java.time.LocalDate maxDate = null;
            for (Object[] row : batch) {
                String bid = (String) row[0];
                if (bid != null) {
                    affectedBuildings.add(bid);
                }
                if (row[3] instanceof java.time.LocalDate d) {
                    if (minDate == null || d.isBefore(minDate)) {
                        minDate = d;
                    }
                    if (maxDate == null || d.isAfter(maxDate)) {
                        maxDate = d;
                    }
                }
            }
            if (minDate != null) {
                java.util.Map<String, Object> aggReport =
                        aggregateRefreshService.refresh(affectedBuildings, minDate, maxDate);
                // 预聚合刷新后再重算评级（评级依赖 fact 表已落库 + area_m2 已回填）
                java.util.Map<String, Object> gradeReport =
                        aggregateRefreshService.regrade(affectedBuildings);
                aggReport.put("regrade", gradeReport);
                result.setAggregateRefresh(aggReport);
            }

            // 数据变更后主动失效看板概览缓存，使导入结果立即反映到监控看板
            try {
                com.carbon.platform.common.controller.HealthController.invalidateOverviewCache();
            } catch (Exception ignore) {
                // 缓存失效失败不影响导入
            }
        } else {
            result.setStatus("预览通过");
        }

        // 审计留痕（无论预览与否都记录，符合"审计留痕"要求）
        writeAuditLog(result, req, rules, failed);
        return result;
    }

    // ================================================================
    // 内部工具
    // ================================================================

    private void writeAuditLog(ImportResultVO r, ImportRequest req,
                               ImportRequest.CleanRules rules, int failed) {
        try {
            String mappingJson = "{}";
            if (req.getMappings() != null) {
                StringBuilder sb = new StringBuilder("{");
                for (ImportRequest.FieldMapping fm : req.getMappings()) {
                    if (sb.length() > 1) sb.append(",");
                    sb.append("\"").append(fm.getSourceColumn()).append("\":\"")
                            .append(fm.getTargetField()).append("\"");
                }
                sb.append("}");
                mappingJson = sb.toString();
            }
            String rulesJson = String.format(
                    "{\"trim\":%s,\"missing\":\"%s\",\"dropNegative\":%s,\"unit\":\"%s\",\"dedup\":\"%s\"}",
                    rules.getTrimString(), rules.getMissingStrategy(), rules.getDropNegative(),
                    rules.getElectricityUnit(), rules.getDedupStrategy());

            jdbcTemplate.update("""
                    INSERT INTO data_import_log
                      (batch_no, file_name, file_type, total_rows, success_rows, failed_rows,
                       field_mapping, clean_rules, status, operator)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """,
                    r.getBatchNo(), r.getFileName(),
                    r.getFileName() != null && r.getFileName().toLowerCase(Locale.ROOT).endsWith(".xlsx")
                            ? "EXCEL" : "CSV",
                    r.getTotalRows(), r.getSuccessRows(), r.getFailedRows(),
                    mappingJson, rulesJson, r.getStatus(),
                    com.carbon.platform.common.util.SecurityUtils.currentUsername());
        } catch (Exception e) {
            log.error("导入审计日志写入失败（不影响导入结果）", e);
        }
    }

    /**
     * 删除库中已存在的、本次导入将要写入的 (building_id, ts) 组合。
     *
     * <p>实现「按建筑+时间覆盖」的导入语义，保证重复导入幂等。
     * 分批执行以避免单条 DELETE 语句过长（IN 列表上限）。
     *
     * @return 被删除的历史记录数
     */
    private int deleteExistingRows(List<Object[]> batch) {
        int total = 0;
        try {
            // 去重收集本次要写入的 (building_id, ts)
            java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
            Map<String, java.util.List<Object>> byKey = new LinkedHashMap<>();
            for (Object[] row : batch) {
                String bid = (String) row[0];
                Object ts = row[2];
                if (bid == null || ts == null) {
                    continue;
                }
                String key = bid + "\u0001" + ts;
                keys.add(key);
                byKey.put(key, java.util.List.of(bid, ts));
            }
            if (keys.isEmpty()) {
                return 0;
            }

            final int CHUNK = 500;
            java.util.List<String> keyList = new java.util.ArrayList<>(keys);
            for (int i = 0; i < keyList.size(); i += CHUNK) {
                java.util.List<String> part = keyList.subList(i, Math.min(i + CHUNK, keyList.size()));
                StringBuilder sql = new StringBuilder(
                        "DELETE FROM fact_energy_hourly WHERE ");
                java.util.List<Object> params = new java.util.ArrayList<>();
                for (int j = 0; j < part.size(); j++) {
                    if (j > 0) {
                        sql.append(" OR ");
                    }
                    sql.append("(building_id = ? AND ts = ?)");
                    java.util.List<Object> kv = byKey.get(part.get(j));
                    params.add(kv.get(0));
                    params.add(kv.get(1));
                }
                total += jdbcTemplate.update(sql.toString(), params.toArray());
            }
        } catch (Exception e) {
            // 删除失败不应阻断导入，但必须记录：这种情况下可能产生重复行
            log.error("覆盖已有数据失败（本次导入可能产生重复行，请检查）", e);
        }
        return total;
    }

    /**
     * 把本次导入涉及的新建筑登记到维表 {@code dim_building}，使它们能被系统的
     * 建筑列表 / 3D 园区 / 统计分组识别。
     *
     * <p>已存在的建筑只更新 {@code site_id}（若原先为空），不覆盖其他属性 ——
     * 避免导入动作把人工维护的建筑类型、面积、评级等信息冲掉。
     *
     * <p>建筑类型与面积若未在导入文件中提供，则填「未知」。这类建筑的
     * EUI 无法计算，因此不参与能耗评级（评级依赖 area_m2）。
     */
    private void registerNewBuildings(List<Object[]> batch,
                                      Map<String, String> bldType,
                                      Map<String, Double> bldArea) {
        try {
            // 收集本次批次里出现过的 (building_id, site_id)
            Map<String, String> seen = new LinkedHashMap<>();
            for (Object[] row : batch) {
                String bid = (String) row[0];
                String sid = (String) row[1];
                if (bid != null && !bid.isBlank()) {
                    seen.putIfAbsent(bid, sid);
                }
            }
            if (seen.isEmpty()) {
                return;
            }

            int inserted = 0;
            for (Map.Entry<String, String> e : seen.entrySet()) {
                Integer cnt = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dim_building WHERE building_id = ?",
                        Integer.class, e.getKey());
                if (cnt != null && cnt > 0) {
                    // 已存在：仅在 site_id 为空时补齐，不覆盖其他人工维护字段
                    if (e.getValue() != null && !e.getValue().isBlank()) {
                        jdbcTemplate.update(
                                "UPDATE dim_building SET site_id = COALESCE(site_id, ?) "
                                        + "WHERE building_id = ?", e.getValue(), e.getKey());
                    }
                    // 类型与面积同理：只在原值为空/未知时补齐，绝不覆盖已有的人工维护值
                    String typeCn2 = bldType.get(e.getKey());
                    Double area2 = bldArea.get(e.getKey());
                    if (typeCn2 != null) {
                        jdbcTemplate.update(
                                "UPDATE dim_building SET building_type_cn = ? "
                                        + "WHERE building_id = ? "
                                        + "AND (building_type_cn IS NULL OR building_type_cn IN ('未知',''))",
                                typeCn2, e.getKey());
                    }
                    if (area2 != null) {
                        jdbcTemplate.update(
                                "UPDATE dim_building SET area_m2 = ? "
                                        + "WHERE building_id = ? AND area_m2 IS NULL",
                                area2, e.getKey());
                    }
                    continue;
                }
                // 建筑类型与面积取 CSV 里提供的值；没有则填「未知」/ NULL，
                // 此时该建筑不参与能耗评级（评级依赖 area_m2）
                String typeCn = bldType.getOrDefault(e.getKey(), "未知");
                Double area = bldArea.get(e.getKey());
                jdbcTemplate.update("""
                        INSERT INTO dim_building
                          (building_id, site_id, building_type, building_type_cn,
                           data_grade, meter_source, area_m2)
                        VALUES (?,?,?,?,?,?,?)
                        """,
                        e.getKey(), e.getValue(), "unknown", typeCn,
                        // 导入数据为实测值，记为 A 级（可参与异常检测）
                        "A", "导入登记", area);
                inserted++;
                log.info("导入时登记新建筑到维表：building_id={}, site_id={}", e.getKey(), e.getValue());
            }
            if (inserted > 0) {
                log.info("本次导入共登记 {} 栋新建筑到 dim_building", inserted);
            }

            // ★ 同时登记新园区到 dim_site。
            // 【为什么必须做】3D 园区页面的"园区下拉框"读的是 dim_site（selectSites），
            // 若导入的建筑属于一个库里没有的园区，这栋楼虽然进了 dim_building，
            // 但在 3D 页面的园区下拉里选不到所属园区，等于看不到。
            // 实测：导入 site_id=TEST 的建筑后，dim_site 仍只有原 18 个园区。
            java.util.Set<String> siteIds = new java.util.LinkedHashSet<>();
            for (Map.Entry<String, String> e : seen.entrySet()) {
                if (e.getValue() != null && !e.getValue().isBlank()) {
                    siteIds.add(e.getValue());
                }
            }
            int newSites = 0;
            for (String sid : siteIds) {
                Integer c = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dim_site WHERE site_id = ?", Integer.class, sid);
                if (c != null && c > 0) {
                    continue;
                }
                jdbcTemplate.update(
                        "INSERT INTO dim_site (site_id, site_name_cn, building_count) VALUES (?,?,0)",
                        sid, sid);
                newSites++;
                log.info("导入时登记新园区到维表：site_id={}", sid);
            }
            if (newSites > 0) {
                log.info("本次导入共登记 {} 个新园区到 dim_site", newSites);
            }
            // 刷新园区建筑数（无论新旧都重算，保证数字准确）
            if (!siteIds.isEmpty()) {
                String in = siteIds.stream().map(x -> "?").collect(java.util.stream.Collectors.joining(","));
                jdbcTemplate.update(
                        "UPDATE dim_site s SET building_count = ("
                                + "SELECT COUNT(*) FROM dim_building b WHERE b.site_id = s.site_id) "
                                + "WHERE s.site_id IN (" + in + ")",
                        siteIds.toArray());
            }
        } catch (Exception ex) {
            // 维表登记失败不应导致导入整体失败：事实数据已落库，可人工补维表
            log.error("登记新建筑到维表失败（事实数据已入库，不影响导入结果）", ex);
        }
    }

    private void addError(ImportResultVO r, int rowNum, String field, String msg, String value) {
        if (r.getErrors().size() < 100) {
            ImportResultVO.RowError e = new ImportResultVO.RowError();
            e.setRowNum(rowNum);
            e.setField(field);
            e.setMessage(msg);
            e.setValue(value != null && value.length() > 60 ? value.substring(0, 60) : value);
            r.getErrors().add(e);
        }
    }

    private Path findFile(String fileId) {
        if (!StringUtils.hasText(fileId) || fileId.contains("/") || fileId.contains("..")) {
            throw new BizException(ResultCode.BAD_REQUEST, "非法 fileId");
        }
        for (String ext : new String[]{".csv", ".xlsx"}) {
            Path p = tmpPath.resolve(fileId + ext);
            if (Files.exists(p)) return p;
        }
        throw new BizException(ResultCode.IMPORT_FAILED, "上传文件不存在或已过期，请重新上传");
    }

    private List<List<String>> readExcel(Path file, List<String> warnings) {
        List<List<String>> out = new ArrayList<>();
        try {
            EasyExcel.read(file.toFile(), new AnalysisEventListener<Map<Integer, String>>() {
                @Override
                public void invoke(Map<Integer, String> data, AnalysisContext ctx) {
                    if (out.size() > maxRows + 1) return;
                    int maxIdx = data.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
                    List<String> row = new ArrayList<>();
                    for (int i = 0; i <= maxIdx; i++) {
                        row.add(data.get(i) == null ? "" : data.get(i));
                    }
                    out.add(row);
                }
                @Override
                public void doAfterAllAnalysed(AnalysisContext ctx) { }
            }).sheet().headRowNumber(0).doRead();
        } catch (Exception e) {
            throw new BizException(ResultCode.IMPORT_FAILED, "Excel 解析失败：" + e.getMessage());
        }
        return out;
    }

    /** 读取 CSV，自动处理 UTF-8 / UTF-8 BOM / GBK 编码 */
    private List<List<String>> readCsv(Path file, List<String> warnings) {
        List<List<String>> out = new ArrayList<>();
        Charset cs = detectCharset(file, warnings);
        try (BufferedReader reader = Files.newBufferedReader(file, cs)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (out.size() > maxRows + 1) break;
                if (first && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                first = false;
                if (line.isEmpty()) continue;
                out.add(parseCsvLine(line));
            }
        } catch (IOException e) {
            throw new BizException(ResultCode.IMPORT_FAILED, "CSV 读取失败：" + e.getMessage());
        }
        return out;
    }

    /** 检测编码：优先 UTF-8，失败则回退 GBK（国内导出的 CSV 常见） */
    private Charset detectCharset(Path file, List<String> warnings) {
        try {
            byte[] head = new byte[8192];
            int n;
            try (InputStream in = Files.newInputStream(file)) {
                n = in.read(head);
            }
            if (n <= 0) return StandardCharsets.UTF_8;
            String utf8 = new String(head, 0, n, StandardCharsets.UTF_8);
            // UTF-8 解码出现替换字符 → 极可能不是 UTF-8
            if (utf8.indexOf('\uFFFD') >= 0) {
                warnings.add("文件非 UTF-8 编码，已按 GBK 解析（如出现乱码请另存为 UTF-8 后重试）");
                return Charset.forName("GBK");
            }
            return StandardCharsets.UTF_8;
        } catch (IOException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** 解析一行 CSV（支持双引号包裹与转义引号） */
    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') inQuote = true;
                else if (c == ',') {
                    fields.add(cur.toString());
                    cur.setLength(0);
                } else cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields;
    }

    /** 首行是否像数据行（用于判断有无表头） */
    private boolean looksLikeDataRow(List<String> first) {
        long nonEmpty = first.stream().filter(StringUtils::hasText).count();
        if (nonEmpty == 0) return false;
        long numericOrDate = first.stream().filter(StringUtils::hasText)
                .filter(v -> NUMERIC.matcher(v.trim()).matches() || parseTimestamp(v) != null)
                .count();
        return numericOrDate >= nonEmpty * 0.6;
    }

    private LocalDateTime parseTimestamp(String v) {
        if (!StringUtils.hasText(v)) return null;
        String s = v.trim();
        // 纯数字时间戳（10 位秒 / 13 位毫秒）
        if (s.matches("^\\d{10}$")) {
            return LocalDateTime.ofEpochSecond(Long.parseLong(s), 0,
                    java.time.ZoneOffset.ofHours(8));
        }
        if (s.matches("^\\d{13}$")) {
            return LocalDateTime.ofEpochSecond(Long.parseLong(s) / 1000, 0,
                    java.time.ZoneOffset.ofHours(8));
        }
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDateTime.parse(s, f);
            } catch (Exception ignored) {
                // 继续尝试下一种格式
            }
        }
        try {
            return java.time.LocalDate.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd")).atStartOfDay();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Double parseNumber(String v) {
        if (!StringUtils.hasText(v)) return null;
        String s = v.trim().replace(",", "").replace(" ", "");
        // 处理 "12.3kWh" 这类带单位的写法
        s = s.replaceAll("[^0-9eE.+-]", "");
        if (s.isEmpty() || "-".equals(s) || ".".equals(s)) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    private String seasonOf(int month) {
        if (month >= 3 && month <= 5) return "春季";
        if (month >= 6 && month <= 8) return "夏季";
        if (month >= 9 && month <= 11) return "秋季";
        return "冬季";
    }

}

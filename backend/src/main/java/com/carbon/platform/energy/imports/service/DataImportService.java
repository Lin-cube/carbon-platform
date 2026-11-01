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
    /** 导入后重算异常（否则异常诊断页永远是旧数据） */
    private final AnomalyDetectionService anomalyDetectionService;
    /** 导入后需要清空看板 AI 解读缓存，否则解读仍基于旧数据 */
    private final com.carbon.platform.ai.service.DashboardInsightService dashboardInsightService;
    /** 编程式事务：用于「按块提交」，不用 @Transactional 把整次导入包成长事务 */
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /** 同一文件的并发导入闸门（进程内）；DB 侧另有在途批次检查作为兜底 */
    private static final java.util.Set<String> IMPORT_LOCKS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 在途批次超过该时长视为僵尸批次（进程被杀等），允许重新导入 */
    private static final int INFLIGHT_STALE_MINUTES = 30;

    @Value("${carbon.import.tmp-dir:.import-tmp}")
    private String tmpDir;

    @Value("${carbon.import.max-rows:200000}")
    private int maxRows;

    private Path tmpPath;

    /** 预置的日期时间格式，按顺序尝试 */
    /**
     * 支持的时间格式。
     *
     * <p>顺序很重要：必须**先尝试具体格式**，最后才把纯数字当 Unix 时间戳。
     * 实测踩过的坑：{@code 2018050106}（紧凑写法 yyyyMMddHH）是 10 位数字，
     * 若先走「10 位 = Unix 秒」分支，会被解析成 <b>2033-12-13</b>，
     * 数据静默错位 15 年且无任何提示。
     */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            // ISO 与常见分隔符
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            // 紧凑写法
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmm"),
            DateTimeFormatter.ofPattern("yyyyMMddHH"),
            DateTimeFormatter.ofPattern("yyyyMMdd HHmm"),
            // 中文写法
            DateTimeFormatter.ofPattern("yyyy年M月d日H时"),
            DateTimeFormatter.ofPattern("yyyy年M月d日 H时"),
            DateTimeFormatter.ofPattern("yyyy年M月d日HH时"),
            DateTimeFormatter.ofPattern("yyyy年M月d日"),
            // 仅日期
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd")
    );

    /**
     * 时间合理范围。解析结果超出该范围视为不可信（多半是格式误判），
     * 例如把 {@code 2018050106} 当 Unix 秒会得到 2033 年。
     */
    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;

    /**
     * 单位一致性阈值：同列内某值与列中位数相差超过该倍数，判定为「单位/量级不一致」。
     *
     * <p>kWh 与 MWh 恰好相差 1000 倍，取 300 可稳定捕获该错误，
     * 同时不会误伤正常的负荷波动（同一栋楼逐小时电量一般不会相差 300 倍）。
     */
    private static final double UNIT_OUTLIER_RATIO = 300.0;

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

        // 自动识别数据源类型（只看表头语义）
        String type = DataSourceClassifier.classify(headers);
        vo.setDetectedType(type);
        vo.setDetectedTypeLabel(DataSourceClassifier.label(type));
        return vo;
    }

    /**
     * 批量上传：一次接收多个文件，逐个识别列、自动映射并判定数据源类型。
     *
     * <p>单个文件失败不影响其它文件——失败的那条会带着错误说明返回，
     * 前端在列表里标红即可，不会让整批上传中断。
     */
    public List<UploadResultVO> uploadBatch(org.springframework.web.multipart.MultipartFile[] files) {
        List<UploadResultVO> out = new ArrayList<>();
        if (files == null || files.length == 0) {
            throw new BizException(ResultCode.BAD_REQUEST, "请至少选择一个文件");
        }
        if (files.length > 20) {
            throw new BizException(ResultCode.BAD_REQUEST, "单次最多上传 20 个文件");
        }
        for (org.springframework.web.multipart.MultipartFile f : files) {
            try {
                out.add(upload(f));
            } catch (Exception e) {
                log.warn("批量上传中文件失败：{} - {}", f.getOriginalFilename(), e.getMessage());
                UploadResultVO err = new UploadResultVO();
                err.setFileName(f.getOriginalFilename());
                err.setFileSize(f.getSize());
                err.setDetectedType(DataSourceClassifier.UNKNOWN);
                err.setDetectedTypeLabel("上传失败");
                err.setWarnings(List.of(String.valueOf(e.getMessage())));
                out.add(err);
            }
        }
        return out;
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

        // ★ 单位一致性预扫描：先算出每个数值列的「列中位数」，供主循环判定量级异常。
        // 【为什么需要】原实现只用一条全局规则（electricityUnit=kwh|mwh）对所有行做统一换算，
        // 无法发现「同一列里混用 kWh 与 MWh」的情况 —— 实测 0.118 与 115000 会被
        // 原样入库，造成 1000 倍误差且毫无提示，后续统计、异常检测、评级全部失真。
        Map<ImportTargetField, Double> columnMedian = computeColumnMedians(rows, mapping);
        Map<ImportTargetField, Integer> outlierCount = new LinkedHashMap<>();

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

                // ★ 单位/量级一致性校验
                // 若某值与所在列中位数相差超过 UNIT_OUTLIER_RATIO 倍，判定为「单位混用」。
                // 处理方式：跳过该行并给出可追溯的诊断依据（不猜测换算，避免制造假数据）。
                if (Boolean.TRUE.equals(rules.getUnitConsistencyCheck())) {
                    String unitHit = null;
                    ImportTargetField unitField = null;
                    for (Map.Entry<ImportTargetField, Double> me : columnMedian.entrySet()) {
                        ImportTargetField f = me.getKey();
                        Double med = me.getValue();
                        Double val = metricValue(f, elec, hvac, water, gas);
                        if (med == null || val == null || med <= 0 || val <= 0) {
                            continue;
                        }
                        double ratio = Math.max(val / med, med / val);
                        if (ratio >= UNIT_OUTLIER_RATIO) {
                            unitHit = String.format(
                                    "数值 %s 与同列中位数 %.3f 相差 %.0f 倍，疑似单位不一致"
                                            + "（如 kWh 与 MWh 混用）；已跳过该行，请核对源数据单位",
                                    String.valueOf(val), med, ratio);
                            unitField = f;
                            break;
                        }
                    }
                    if (unitHit != null) {
                        skipped++;
                        outlierCount.merge(unitField, 1, Integer::sum);
                        addError(result, rowNum, unitField.getColumn(), unitHit,
                                raw.get(unitField));
                        continue;
                    }
                }

                // 清洗：缺失值策略
                String ms = rules.getMissingStrategy();
                if ("drop".equalsIgnoreCase(ms) && elec == null) {
                    skipped++;
                    // 补诊断说明：原实现静默跳过，用户不知道这行为什么没进来
                    addError(result, rowNum, ImportTargetField.ELECTRICITY_KWH.getColumn(),
                            "电力能耗缺失，按「缺失值策略=剔除」跳过该行",
                            raw.get(ImportTargetField.ELECTRICITY_KWH));
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
                        addError(result, rowNum, "timestamp",
                                "与前面行重复（同一建筑同一时刻），按去重规则跳过", key);
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
                        "A",
                        // import_batch_no：写入批次号，使每行数据可追溯到具体批次，
                        // 从而支持「按批次查询」与「按批次回滚」。初始数据集该列为 NULL。
                        result.getBatchNo()
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

        // ================================================================
        // 落库（分批事务 + 批次状态机）
        // ================================================================
        //
        // 事务设计（刻意不把整个文件包进一个超长事务）：
        //   阶段 0  解析/清洗        —— 纯内存，零写入
        //   阶段 1  事实表分块写入    —— 每 1000 行一个事务：DELETE 同主键 + INSERT 原子提交
        //   阶段 2  维表登记          —— 独立事务（幂等 upsert）
        //   阶段 3  派生数据          —— 异常/聚合/评级，失败不回滚事实数据，标记为待重算
        //   阶段 4  收尾              —— 更新批次状态
        // 任一块失败只回滚该块；阶段 1 整体失败时按 batch_no 清理已写入行，不留半成品。
        if (dryRun || batch.isEmpty()) {
            result.setStatus("预览通过");
            writeAuditLog(result, req, rules, failed);
            return result;
        }

        // 同一文件并发落库闸门（进程内锁 + DB 在途检查）
        if (!IMPORT_LOCKS.add(req.getFileId())) {
            throw new BizException(ResultCode.BAD_REQUEST, "该文件正在导入中，请等待本次导入完成后再试");
        }
        boolean jobCreated = false;
        try {
            assertNoInflightJob(req.getFileId());
            createJob(result, req, rules);
            jobCreated = true;

            // ── 阶段 1：事实表分块写入（每块一个事务，删除与插入同生共死）──
            final int BATCH = 1000;
            int replacedTotal = 0;
            int written = 0;
            for (int i = 0; i < batch.size(); i += BATCH) {
                final java.util.List<Object[]> part = batch.subList(i, Math.min(i + BATCH, batch.size()));
                final int[] rep = {0};
                transactionTemplate.executeWithoutResult(st -> {
                    rep[0] = deleteExistingRows(part);
                    insertFactRows(part);
                });
                replacedTotal += rep[0];
                written += part.size();
                updateJobProgress(result.getBatchNo(), written);
            }
            result.setReplacedRows(replacedTotal);
            result.setStatus(failed > 0 ? "部分成功" : "成功");
            if (replacedTotal > 0) {
                log.info("本次导入覆盖已存在的 {} 条历史记录（按 building_id + ts 匹配）", replacedTotal);
            }

            // ── 阶段 2：维表登记（独立事务，幂等）──
            transactionTemplate.executeWithoutResult(st -> registerNewBuildings(batch, bldType, bldArea));

            // ── 阶段 3：派生数据（失败不回滚事实数据，标记待重算）──
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
                result.setStartDate(minDate.toString());
                result.setEndDate(maxDate == null ? minDate.toString() : maxDate.toString());
                java.util.Map<String, Object> aggReport = new java.util.LinkedHashMap<>();
                boolean derivedOk = true;
                String derivedErr = null;
                try {
                    // 顺序：异常 → 聚合 → 评级。先重算异常，聚合才有正确的 anomaly_hours。
                    aggReport.put("anomaly", anomalyDetectionService.recompute(affectedBuildings, minDate, maxDate));
                    aggReport.putAll(aggregateRefreshService.refresh(affectedBuildings, minDate, maxDate));
                    aggReport.put("regrade", aggregateRefreshService.regrade(affectedBuildings));
                    // refresh() 内部吞异常并写入 error，需要显式判定
                    if (aggReport.get("error") != null) {
                        derivedOk = false;
                        derivedErr = String.valueOf(aggReport.get("error"));
                    }
                } catch (Exception e) {
                    derivedOk = false;
                    derivedErr = String.valueOf(e.getMessage());
                    aggReport.put("error", derivedErr);
                    log.error("派生数据刷新失败（事实数据已落库，可重算）", e);
                }
                result.setAggregateRefresh(aggReport);
                result.setDerivedStatus(derivedOk ? "OK" : "FAILED");
                if (!derivedOk) {
                    // 保留基础状态（成功/部分成功）再追加后缀，
                    // 否则重算后去掉后缀会退化成「部分成功」（实测踩过）
                    result.setStatus(result.getStatus() + "（派生数据待重算）");
                    log.warn("批次 {} 派生数据待重算：{}", result.getBatchNo(), derivedErr);
                }
            }

            // ── 阶段 4：缓存失效 + 收尾 ──
            try {
                com.carbon.platform.common.controller.HealthController.invalidateOverviewCache();
                dashboardInsightService.invalidate();
            } catch (Exception ignore) {
                // 缓存失效失败不影响导入
            }
            finalizeJob(result, req, rules, failed);
            return result;
        } catch (Exception e) {
            // 失败：按批次号清理已写入的事实行，避免半成品；再标记批次失败
            if (jobCreated) {
                try {
                    int cleaned = jdbcTemplate.update(
                            "DELETE FROM fact_energy_hourly WHERE import_batch_no = ?", result.getBatchNo());
                    log.warn("导入失败，已清理本批次写入的 {} 条事实记录（batch={}）", cleaned, result.getBatchNo());
                } catch (Exception ce) {
                    log.error("清理失败批次数据时出错（请用回滚接口清理 batch={}）：{}", result.getBatchNo(), ce.getMessage());
                }
                failJob(result.getBatchNo(), e);
            }
            throw e;
        } finally {
            IMPORT_LOCKS.remove(req.getFileId());
        }
    }

    // ================================================================
    // 内部工具
    // ================================================================

    // ================================================================
    // 批次状态机（导入中 → 成功/部分成功/失败；派生数据 OK/FAILED）
    // ================================================================

    /** 事实表批量插入；必须在调用方事务内执行（与 deleteExistingRows 同生共死） */
    private void insertFactRows(java.util.List<Object[]> part) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO fact_energy_hourly
                  (building_id, site_id, ts, stat_date, stat_year, stat_month, stat_day,
                   stat_hour, stat_weekday, is_weekend, season,
                   electricity_kwh, water_m3, hvac_kwh, gas_kwh, carbon_kg,
                   air_temperature_c, humidity_pct,
                   chw_supply_temp_c, chw_return_temp_c, occupancy_density,
                   equipment_status, anomaly_flag, anomaly_type, severity,
                   data_quality_flag, data_quality_type, data_grade, import_batch_no)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,
                        '正常', -1, '未评估', '未评估', -1, '未评估', ?, ?)
                """, part);
    }

    /** 创建导入批次记录（status=导入中），使崩溃/失败可被发现，而不是查无此批次 */
    private void createJob(ImportResultVO r, ImportRequest req, ImportRequest.CleanRules rules) {
        jdbcTemplate.update("""
                INSERT INTO data_import_log
                  (batch_no, file_id, file_name, file_type, total_rows, success_rows, failed_rows,
                   field_mapping, clean_rules, status, dry_run, derived_status, operator)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                r.getBatchNo(), req.getFileId(), r.getFileName(), fileType(r.getFileName()),
                r.getTotalRows(), 0, 0,
                mappingJson(req), rulesJson(rules), "导入中", 0, "PENDING",
                com.carbon.platform.common.util.SecurityUtils.currentUsername());
    }

    /** 分块进度：让长时间导入在审计页面可见，而不是一直显示 0 行 */
    private void updateJobProgress(String batchNo, int written) {
        try {
            jdbcTemplate.update(
                    "UPDATE data_import_log SET success_rows = ? WHERE batch_no = ?", written, batchNo);
        } catch (Exception ignore) {
            // 进度更新失败不影响导入
        }
    }

    /** 成功收尾：写入统计、日期区间、覆盖行数、派生状态 */
    private void finalizeJob(ImportResultVO r, ImportRequest req,
                             ImportRequest.CleanRules rules, int failed) {
        jdbcTemplate.update("""
                UPDATE data_import_log
                   SET success_rows = ?, failed_rows = ?, status = ?,
                       start_date = ?, end_date = ?, replaced_rows = ?,
                       derived_status = ?, error_message = NULL
                 WHERE batch_no = ?
                """,
                r.getSuccessRows(), r.getFailedRows(), r.getStatus(),
                toDate(r.getStartDate()), toDate(r.getEndDate()),
                r.getReplacedRows() == null ? 0 : r.getReplacedRows(),
                r.getDerivedStatus() == null ? "PENDING" : r.getDerivedStatus(),
                r.getBatchNo());
    }

    /** 失败收尾：标记失败原因，保留批次号便于人工回滚残余行 */
    private void failJob(String batchNo, Exception e) {
        try {
            jdbcTemplate.update(
                    "UPDATE data_import_log SET status = '失败', error_message = ? WHERE batch_no = ?",
                    truncate(String.valueOf(e.getMessage()), 2000), batchNo);
        } catch (Exception ignore) {
            // 状态更新失败不应覆盖原始异常
        }
    }

    /**
     * 重复提交保护：同一 fileId 已有「导入中/聚合中」的批次时不重复执行。
     * 超过 {@link #INFLIGHT_STALE_MINUTES} 未更新的批次视为僵尸（进程被杀），允许重来。
     */
    private void assertNoInflightJob(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return;
        }
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM data_import_log
                 WHERE file_id = ? AND status IN ('导入中','聚合中')
                   AND (updated_at IS NULL OR updated_at > DATE_SUB(NOW(), INTERVAL ? MINUTE))
                """, Integer.class, fileId, INFLIGHT_STALE_MINUTES);
        if (n != null && n > 0) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "该文件已有导入任务正在进行（进程内或其它实例），请等待完成后再试");
        }
    }

    private String fileType(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx") ? "EXCEL" : "CSV";
    }

    private String mappingJson(ImportRequest req) {
        if (req.getMappings() == null) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (ImportRequest.FieldMapping fm : req.getMappings()) {
            if (sb.length() > 1) {
                sb.append(",");
            }
            sb.append("\"").append(fm.getSourceColumn()).append("\":\"")
                    .append(fm.getTargetField()).append("\"");
        }
        return sb.append("}").toString();
    }

    private String rulesJson(ImportRequest.CleanRules rules) {
        return String.format(
                "{\"trim\":%s,\"missing\":\"%s\",\"dropNegative\":%s,\"unit\":\"%s\",\"dedup\":\"%s\"}",
                rules.getTrimString(), rules.getMissingStrategy(), rules.getDropNegative(),
                rules.getElectricityUnit(), rules.getDedupStrategy());
    }

    private java.time.LocalDate toDate(String s) {
        try {
            return s == null || s.isBlank() ? null : java.time.LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

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
                       field_mapping, clean_rules, status, dry_run, operator)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    r.getBatchNo(), r.getFileName(),
                    r.getFileName() != null && r.getFileName().toLowerCase(Locale.ROOT).endsWith(".xlsx")
                            ? "EXCEL" : "CSV",
                    r.getTotalRows(), r.getSuccessRows(), r.getFailedRows(),
                    mappingJson, rulesJson, r.getStatus(),
                    // dry_run：明确记录本批次是否真正写入数据库。
                    // 【为什么需要】「预览通过」与「成功」在审计列表里视觉上很接近，
                    // 用户容易误以为预览的数据也入库了。加独立字段后前端可以显著标注。
                    Boolean.TRUE.equals(r.getDryRun()) ? 1 : 0,
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
            // ★ 不再吞异常：删除与插入在同一事务内，删除失败必须让整块回滚，
            //   否则会「只删不插」造成数据丢失。
            throw new BizException(ResultCode.IMPORT_FAILED,
                    "覆盖已有数据失败，本批次已回滚：" + e.getMessage());
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

    /**
     * 预扫描：计算每个数值列在整份文件中的中位数。
     *
     * <p>用中位数而非平均值，因为中位数对极端值（正是我们要检测的对象）不敏感，
     * 不会被少数 MWh 行拉偏。
     */
    private Map<ImportTargetField, Double> computeColumnMedians(
            List<List<String>> rows, Map<ImportTargetField, Integer> mapping) {
        Map<ImportTargetField, Double> medians = new LinkedHashMap<>();
        for (ImportTargetField f : List.of(ImportTargetField.ELECTRICITY_KWH,
                ImportTargetField.HVAC_KWH, ImportTargetField.WATER_M3,
                ImportTargetField.GAS_KWH)) {
            Integer idx = mapping.get(f);
            if (idx == null) {
                continue;
            }
            List<Double> vals = new ArrayList<>();
            for (List<String> row : rows) {
                String v = idx < row.size() ? row.get(idx) : null;
                Double d = parseNumber(v);
                if (d != null && d > 0) {
                    vals.add(d);
                }
            }
            if (vals.size() < 3) {
                continue;   // 样本太少，中位数没有统计意义
            }
            java.util.Collections.sort(vals);
            double med = vals.size() % 2 == 1
                    ? vals.get(vals.size() / 2)
                    : (vals.get(vals.size() / 2 - 1) + vals.get(vals.size() / 2)) / 2.0;
            medians.put(f, med);
        }
        return medians;
    }

    /** 按字段取对应的解析值，供量级一致性校验使用 */
    private Double metricValue(ImportTargetField f, Double elec, Double hvac,
                               Double water, Double gas) {
        return switch (f) {
            case ELECTRICITY_KWH -> elec;
            case HVAC_KWH -> hvac;
            case WATER_M3 -> water;
            case GAS_KWH -> gas;
            default -> null;
        };
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
        Charset cs = detectCharset(file, warnings);
        try {
            return readCsvStrict(file, cs);
        } catch (Exception first) {
            // 编码探测可能误判（典型：8192 字节处截断了多字节汉字）。依次回退重试。
            Charset fallback = StandardCharsets.UTF_8.equals(cs)
                    ? Charset.forName("GBK") : StandardCharsets.UTF_8;
            warnings.add("按 " + cs.name() + " 解析失败（" + first.getMessage()
                    + "），已回退 " + fallback.name() + " 重试");
            try {
                return readCsvStrict(file, fallback);
            } catch (Exception second) {
                warnings.add("严格解码均失败，已启用有损解码（替换非法字节），个别字符可能显示为「?」");
                return readCsvLenient(file);
            }
        }
    }

    /** 严格解码读取：遇到非法字节直接抛错，由调用方决定回退策略 */
    private List<List<String>> readCsvStrict(Path file, Charset cs) {
        try (BufferedReader reader = Files.newBufferedReader(file, cs)) {
            return readCsvLines(reader);
        } catch (IOException e) {
            throw new BizException(ResultCode.IMPORT_FAILED, "CSV 读取失败：" + e.getMessage());
        }
    }

    /** 有损解码：非法字节替换为 U+FFFD，保证导入不因个别坏字节整体失败 */
    private List<List<String>> readCsvLenient(Path file) {
        java.nio.charset.CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
        try (BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(Files.newInputStream(file), dec))) {
            return readCsvLines(reader);
        } catch (IOException e) {
            throw new BizException(ResultCode.IMPORT_FAILED, "CSV 读取失败：" + e.getMessage());
        }
    }

    private List<List<String>> readCsvLines(BufferedReader reader) throws IOException {
        List<List<String>> out = new ArrayList<>();
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
        return out;
    }

    /**
     * 检测编码：优先 UTF-8，失败则回退 GBK（国内导出的 CSV 常见）。
     *
     * <p>【为什么不能直接 new String(bytes, UTF_8) 判断】采样窗口固定 8192 字节，
     * 边界很可能正好切在一个 3 字节汉字的中间，解码后出现替换字符 U+FFFD，
     * 于是**合法的 UTF-8 文件被误判成 GBK**，后续按 GBK 读会抛
     * MalformedInputException（实测 5 万行文件必现，小文件因边界碰巧对齐而侥幸通过）。
     *
     * <p>改用严格解码器判定，并允许末尾最多 3 字节的不完整序列。
     */
    private Charset detectCharset(Path file, List<String> warnings) {
        try {
            byte[] head = new byte[8192];
            int n;
            try (InputStream in = Files.newInputStream(file)) {
                n = in.read(head);
            }
            if (n <= 0) {
                return StandardCharsets.UTF_8;
            }
            java.nio.charset.CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            for (int cut = 0; cut <= 3 && cut < n; cut++) {
                try {
                    dec.reset();
                    dec.decode(java.nio.ByteBuffer.wrap(head, 0, n - cut));
                    return StandardCharsets.UTF_8;
                } catch (java.nio.charset.CharacterCodingException ignore) {
                    // 可能是末尾截断，继续尝试更短的窗口
                }
            }
            warnings.add("文件非 UTF-8 编码，已按 GBK 解析（如出现乱码请另存为 UTF-8 后重试）");
            return Charset.forName("GBK");
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

    /**
     * 解析时间字符串。
     *
     * <p><b>关键顺序</b>：先按具体日期格式尝试，最后才把纯数字当 Unix 时间戳。
     * 反过来的话 {@code 2018050106}（yyyyMMddHH）会被当成 10 位 epoch 秒，
     * 解析成 2033 年，且不会有任何报错（实测踩过）。
     *
     * <p>解析结果还要通过<b>年份范围校验</b>：超出 2000~2100 视为格式误判，返回 null
     * 并让上层记录「时间格式无法识别」。
     */
    private LocalDateTime parseTimestamp(String v) {
        if (!StringUtils.hasText(v)) return null;
        String s = v.trim();

        // ① 先按具体格式尝试（含紧凑写法与中文写法）
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                LocalDateTime t = LocalDateTime.parse(s, f);
                if (inReasonableRange(t)) {
                    return t;
                }
            } catch (Exception ignored) {
                // 继续尝试下一种格式
            }
        }
        // 仅日期的格式补一次（LocalDateTime 解析不了 yyyy-MM-dd）
        for (DateTimeFormatter f : List.of(DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                DateTimeFormatter.ofPattern("yyyy/M/d"),
                DateTimeFormatter.ofPattern("yyyy年M月d日"))) {
            try {
                LocalDateTime t = java.time.LocalDate.parse(s, f).atStartOfDay();
                if (inReasonableRange(t)) {
                    return t;
                }
            } catch (Exception ignored) {
                // 继续
            }
        }

        // ② 最后才当 Unix 时间戳（13 位毫秒 / 10 位秒）
        if (s.matches("^\\d{13}$")) {
            LocalDateTime t = LocalDateTime.ofEpochSecond(Long.parseLong(s) / 1000, 0,
                    java.time.ZoneOffset.ofHours(8));
            return inReasonableRange(t) ? t : null;
        }
        if (s.matches("^\\d{10}$")) {
            LocalDateTime t = LocalDateTime.ofEpochSecond(Long.parseLong(s), 0,
                    java.time.ZoneOffset.ofHours(8));
            return inReasonableRange(t) ? t : null;
        }
        return null;
    }

    /** 年份是否在合理范围内，用于识别时间格式误判 */
    private boolean inReasonableRange(LocalDateTime t) {
        int y = t.getYear();
        return y >= MIN_YEAR && y <= MAX_YEAR;
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

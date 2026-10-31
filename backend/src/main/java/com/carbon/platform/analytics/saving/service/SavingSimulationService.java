package com.carbon.platform.analytics.saving.service;

import com.carbon.platform.ai.llm.LlmClient;
import com.carbon.platform.analytics.saving.dto.SavingSimulationRequest;
import com.carbon.platform.analytics.saving.dto.SavingSimulationVO;
import com.carbon.platform.common.exception.BizException;
import com.carbon.platform.common.result.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节能潜力情景测算（What-if）。
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li><b>确定性</b>：纯算术，无机器学习、无黑盒系数；同一输入必得同一结果。</li>
 *   <li><b>只读</b>：只查询事实表，<b>绝不写回</b> fact / agg 任何真实统计。</li>
 *   <li><b>可解释</b>：每个数字都能追到基准值、假设参数与公式。</li>
 *   <li><b>不编造</b>：缺 COP、缺电价、缺数据时明确返回「数据不足」，不做行业均值替代。</li>
 * </ul>
 *
 * <h3>两个情景</h3>
 * <pre>
 * ① REDUCE_RATIO  可调时段能耗按比例下降
 *      saved = 基准电量(时段) × 比例
 *
 * ② COP_IMPROVE   制冷负荷不变、COP 提升
 *      Q = E × COP 恒定  ⇒  E_new = E_old × COP_old / COP_new
 *      saved = E_hvac × (1 − COP_old / COP_new)
 *      ★ 只作用于空调分项能耗，不把整栋楼用电按 COP 比例换算
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SavingSimulationService {

    private final JdbcTemplate jdbcTemplate;
    private final LlmClient llmClient;

    /** 电网平均排放因子，与导入/查询口径一致（生态环境部 2023 公布值） */
    public static final BigDecimal CARBON_FACTOR = new BigDecimal("0.5810");

    private static final int MAX_RANGE_DAYS = 366;
    /** COP 上限：超过视为异常假设 */
    private static final BigDecimal COP_CEILING = new BigDecimal("8.0");

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // ================================================================ 主流程

    /** 执行测算；save=true 时落库为一条模拟记录（独立表，不影响真实统计） */
    public SavingSimulationVO simulate(SavingSimulationRequest req) {
        LocalDate start = parseDate(req.getStartDate(), "开始日期");
        LocalDate end = parseDate(req.getEndDate(), "结束日期");
        if (start.isAfter(end)) {
            throw new BizException(ResultCode.BAD_REQUEST, "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw new BizException(ResultCode.BAD_REQUEST, "测算区间不能超过 " + MAX_RANGE_DAYS + " 天");
        }
        String buildingId = req.getBuildingId() == null ? "" : req.getBuildingId().trim();
        if (buildingId.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST, "必须指定建筑");
        }
        assertBuildingExists(buildingId);

        String scenario = req.getScenario() == null ? "" : req.getScenario().trim().toUpperCase();
        SavingSimulationVO vo;
        if ("REDUCE_RATIO".equals(scenario)) {
            vo = reduceRatio(req, buildingId, start, end);
        } else if ("COP_IMPROVE".equals(scenario)) {
            vo = copImprove(req, buildingId, start, end);
        } else {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "不支持的情景：" + req.getScenario() + "，可选：REDUCE_RATIO / COP_IMPROVE");
        }

        if (!Boolean.FALSE.equals(req.getSave())) {
            persist(vo);
        }
        return vo;
    }

    // ================================================================ 情景一

    /** 情景一：指定时段的可调能耗按比例下降 */
    private SavingSimulationVO reduceRatio(SavingSimulationRequest req, String buildingId,
                                           LocalDate start, LocalDate end) {
        double ratio = req.getReduceRatio() == null ? -1 : req.getReduceRatio();
        if (ratio < 0 || ratio > 0.9) {
            throw new BizException(ResultCode.BAD_REQUEST, "节能比例必须在 0 ~ 0.9 之间（即 0% ~ 90%）");
        }
        int hs = req.getHourStart() == null ? 0 : req.getHourStart();
        int he = req.getHourEnd() == null ? 23 : req.getHourEnd();
        if (hs < 0 || hs > 23 || he < 0 || he > 23) {
            throw new BizException(ResultCode.BAD_REQUEST, "小时范围必须在 0 ~ 23");
        }
        if (hs > he) {
            throw new BizException(ResultCode.BAD_REQUEST, "起始小时不能大于结束小时");
        }
        BigDecimal price = validPrice(req.getUnitPrice());

        // 基准：整区间总电量 + 指定时段电量（都用真实事实表，不估算）
        Map<String, Object> total = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS hours, COALESCE(SUM(electricity_kwh),0) AS kwh
                  FROM fact_energy_hourly
                 WHERE building_id = ? AND stat_date BETWEEN ? AND ?
                """, buildingId, start, end);
        Map<String, Object> window = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS hours, COALESCE(SUM(electricity_kwh),0) AS kwh
                  FROM fact_energy_hourly
                 WHERE building_id = ? AND stat_date BETWEEN ? AND ?
                   AND stat_hour BETWEEN ? AND ?
                """, buildingId, start, end, hs, he);

        BigDecimal totalKwh = dec(total.get("kwh"));
        BigDecimal windowKwh = dec(window.get("kwh"));
        long totalHours = lng(total.get("hours"));
        long windowHours = lng(window.get("hours"));

        SavingSimulationVO vo = baseVo("REDUCE_RATIO", "可调时段能耗按比例下降",
                buildingId, start, end);

        boolean sufficient = totalHours > 0 && windowHours > 0 && windowKwh.signum() > 0;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reduceRatio", ratio);
        params.put("reducePercent", pct(ratio));
        params.put("hourStart", hs);
        params.put("hourEnd", he);
        params.put("hourWindow", String.format("%02d:00 ~ %02d:59", hs, he));
        params.put("unitPrice", price == null ? null : price.doubleValue());
        vo.setParams(params);

        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("totalElectricityKwh", scale(totalKwh, 2));
        baseline.put("windowElectricityKwh", scale(windowKwh, 2));
        baseline.put("totalHours", totalHours);
        baseline.put("windowHours", windowHours);
        baseline.put("windowShareOfTotal", totalKwh.signum() == 0 ? null
                : pct(windowKwh.divide(totalKwh, 6, RoundingMode.HALF_UP).doubleValue()));
        baseline.put("dataSource", "fact_energy_hourly（真实能耗，非估算）");
        vo.setBaseline(baseline);
        vo.setSufficient(sufficient);

        List<Map<String, String>> insufficient = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        if (!sufficient) {
            String reason = windowHours == 0
                    ? "该建筑在所选时段内没有能耗记录，无法测算"
                    : "该建筑在查询区间内没有能耗记录，无法测算";
            vo.setInsufficiencyReason(reason);
            result.put("savedKwh", null);
            result.put("simulatedWindowKwh", null);
            result.put("savedRatioOfTotal", null);
            result.put("carbonSavedKg", null);
            result.put("costSaved", null);
            insufficient.add(Map.of("metric", "全部", "reason", reason));
        } else {
            BigDecimal saved = windowKwh.multiply(BigDecimal.valueOf(ratio)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal simulatedWindow = windowKwh.subtract(saved).setScale(2, RoundingMode.HALF_UP);
            result.put("savedKwh", saved);
            result.put("simulatedWindowKwh", simulatedWindow);
            result.put("simulatedTotalKwh", scale(totalKwh.subtract(saved), 2));
            result.put("savedRatioOfWindow", pct(ratio));
            result.put("savedRatioOfTotal", totalKwh.signum() == 0 ? null
                    : pct(saved.divide(totalKwh, 6, RoundingMode.HALF_UP).doubleValue()));
            result.put("carbonSavedKg", saved.multiply(CARBON_FACTOR).setScale(2, RoundingMode.HALF_UP));
            if (price != null) {
                result.put("costSaved", saved.multiply(price).setScale(2, RoundingMode.HALF_UP));
                result.put("costBasis", "按用户提供电价 " + price + " 元/kWh 估算");
            } else {
                result.put("costSaved", null);
                result.put("costBasis", "未提供电价");
                insufficient.add(Map.of(
                        "metric", "电费节约",
                        "reason", "平台没有电价数据，需用户提供电价后才能估算"));
            }
        }
        vo.setResult(result);
        vo.setInsufficient(insufficient);
        vo.setBasis(basis("REDUCE_RATIO", price));
        return vo;
    }

    // ================================================================ 情景二

    /** 情景二：制冷负荷不变，COP 提升带来的空调电耗下降 */
    private SavingSimulationVO copImprove(SavingSimulationRequest req, String buildingId,
                                          LocalDate start, LocalDate end) {
        double improve = req.getCopImproveRatio() == null ? -1 : req.getCopImproveRatio();
        if (improve < 0 || improve > 0.5) {
            throw new BizException(ResultCode.BAD_REQUEST, "COP 提升比例必须在 0 ~ 0.5 之间（即 0% ~ 50%）");
        }
        BigDecimal price = validPrice(req.getUnitPrice());

        Map<String, Object> total = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS hours, COALESCE(SUM(electricity_kwh),0) AS kwh
                  FROM fact_energy_hourly
                 WHERE building_id = ? AND stat_date BETWEEN ? AND ?
                """, buildingId, start, end);

        // 只取「有 COP 且空调能耗 > 0」的小时作为有效样本
        Map<String, Object> copRow = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS samples,
                       COALESCE(SUM(hvac_kwh),0) AS hvac,
                       COALESCE(SUM(CASE WHEN cop > 0 THEN hvac_kwh / cop END),0) AS hvacOverCop,
                       AVG(cop) AS copAvgSimple
                  FROM fact_energy_hourly
                 WHERE building_id = ? AND stat_date BETWEEN ? AND ?
                   AND cop IS NOT NULL AND cop > 0 AND hvac_kwh > 0
                """, buildingId, start, end);

        long samples = lng(copRow.get("samples"));
        BigDecimal hvacKwh = dec(copRow.get("hvac"));
        BigDecimal hvacOverCop = dec(copRow.get("hvacOverCop"));
        BigDecimal totalKwh = dec(total.get("kwh"));
        long totalHours = lng(total.get("hours"));

        SavingSimulationVO vo = baseVo("COP_IMPROVE", "制冷 COP 改善（负荷不变）",
                buildingId, start, end);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("copImproveRatio", improve);
        params.put("copImprovePercent", pct(improve));
        params.put("premise", "制冷负荷不变、冷冻水系统水力工况不变");
        params.put("unitPrice", price == null ? null : price.doubleValue());
        vo.setParams(params);

        // 负荷加权平均 COP：ΣE_hvac / Σ(E_hvac/COP)，比简单平均更贴合实际
        BigDecimal copOld = hvacOverCop.signum() > 0
                ? hvacKwh.divide(hvacOverCop, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("totalElectricityKwh", scale(totalKwh, 2));
        baseline.put("totalHours", totalHours);
        baseline.put("hvacElectricityKwh", scale(hvacKwh, 2));
        baseline.put("copSamples", samples);
        baseline.put("copOldWeighted", scale(copOld, 4));
        baseline.put("copOldSimpleAvg", copRow.get("copAvgSimple") == null ? null
                : new BigDecimal(copRow.get("copAvgSimple").toString()).setScale(4, RoundingMode.HALF_UP));
        baseline.put("dataSource", "fact_energy_hourly（hvac_kwh + cop，仅含有效计量小时）");
        vo.setBaseline(baseline);

        List<Map<String, String>> insufficient = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        boolean sufficient = totalHours > 0 && samples > 0 && hvacKwh.signum() > 0 && copOld.signum() > 0;
        vo.setSufficient(sufficient);

        if (!sufficient) {
            String reason;
            if (totalHours == 0) {
                reason = "该建筑在查询区间内没有能耗记录，无法测算";
            } else if (samples == 0) {
                reason = "该建筑在查询区间内没有可用的 COP 计量样本（缺少冷冻水计量），无法测算";
            } else {
                reason = "该建筑空调分项能耗为 0 或无有效 COP 记录，无法测算";
            }
            vo.setInsufficiencyReason(reason);
            result.put("copNew", null);
            result.put("savedHvacKwh", null);
            result.put("savedKwh", null);
            result.put("savedRatioOfTotal", null);
            result.put("carbonSavedKg", null);
            result.put("costSaved", null);
            insufficient.add(Map.of("metric", "全部", "reason", reason));
        } else {
            BigDecimal copNew = copOld.multiply(BigDecimal.valueOf(1 + improve)).setScale(4, RoundingMode.HALF_UP);
            if (copNew.compareTo(COP_CEILING) > 0) {
                throw new BizException(ResultCode.BAD_REQUEST,
                        "提升后 COP 为 " + copNew + "，超过合理上限 " + COP_CEILING + "，请降低提升比例");
            }
            // 负荷固定：Q=E×COP ⇒ E_new = E_old × COP_old / COP_new
            BigDecimal ratioKeep = copOld.divide(copNew, 8, RoundingMode.HALF_UP);
            BigDecimal savedHvac = hvacKwh.subtract(
                    hvacKwh.multiply(ratioKeep)).setScale(2, RoundingMode.HALF_UP);

            result.put("copNew", scale(copNew, 4));
            result.put("savedHvacKwh", savedHvac);
            result.put("savedKwh", savedHvac);
            result.put("simulatedHvacKwh", scale(hvacKwh.subtract(savedHvac), 2));
            result.put("simulatedTotalKwh", scale(totalKwh.subtract(savedHvac), 2));
            result.put("savedRatioOfHvac", pct(BigDecimal.valueOf(improve)
                    .divide(BigDecimal.valueOf(1 + improve), 6, RoundingMode.HALF_UP).doubleValue()));
            result.put("savedRatioOfTotal", totalKwh.signum() == 0 ? null
                    : pct(savedHvac.divide(totalKwh, 6, RoundingMode.HALF_UP).doubleValue()));
            result.put("carbonSavedKg", savedHvac.multiply(CARBON_FACTOR).setScale(2, RoundingMode.HALF_UP));
            if (price != null) {
                result.put("costSaved", savedHvac.multiply(price).setScale(2, RoundingMode.HALF_UP));
                result.put("costBasis", "按用户提供电价 " + price + " 元/kWh 估算");
            } else {
                result.put("costSaved", null);
                result.put("costBasis", "未提供电价");
                insufficient.add(Map.of("metric", "电费节约",
                        "reason", "平台没有电价数据，需用户提供电价后才能估算"));
            }
        }
        vo.setResult(result);
        vo.setInsufficient(insufficient);
        vo.setBasis(basis("COP_IMPROVE", price));
        return vo;
    }

    // ================================================================ AI 解释

    /**
     * 生成 AI 解释。数字守卫：回答里出现的数字必须能在结构化结果中找到，
     * 否则视为编造，降级为模板叙述。
     */
    public Map<String, Object> explain(long id) {
        Map<String, Object> row = loadRow(id);
        String structured;
        try {
            structured = json.writeValueAsString(Map.of(
                    "params", row.get("params_json"),
                    "baseline", row.get("baseline_json"),
                    "result", row.get("result_json"),
                    "basis", row.get("basis_json")));
        } catch (Exception e) {
            structured = "{}";
        }
        String system = """
                你是建筑能源运维分析师。只依据给定数据解释节能情景模拟结果。
                必须严格区分四类内容，并在回答中用这四类小标题分段：
                ① 历史事实（来自基准数据）② 模拟假设 ③ 理论节能潜力 ④ 风险与建议
                禁止把模拟结果描述成「已经实现」或「已确认」的节能效果。
                只能使用给定数据中的数字，禁止自行推算或引入外部数值。
                回答控制在 300 字以内。""";
        String user = "以下是一次节能情景模拟的结构化结果（JSON）：\n" + structured;

        String narrative = null;
        String mode = "template";
        if (llmClient != null && llmClient.available()) {
            try {
                String raw = llmClient.chat(system, user, 0.2, 700);
                if (raw != null && !raw.isBlank() && numbersGrounded(raw, structured)) {
                    narrative = raw.trim();
                    mode = "llm";
                } else if (raw != null && !raw.isBlank()) {
                    log.warn("AI 解释出现未提供的数字，已降级模板");
                }
            } catch (Exception e) {
                log.warn("AI 解释生成失败，降级模板：{}", e.getMessage());
            }
        }
        if (narrative == null) {
            narrative = templateNarrative(row);
        }
        jdbcTemplate.update("UPDATE energy_saving_simulation SET ai_narrative = ?, ai_mode = ? WHERE id = ?",
                narrative, mode, id);
        return Map.of("id", id, "narrative", narrative, "mode", mode);
    }

    private String templateNarrative(Map<String, Object> row) {
        String scenario = String.valueOf(row.get("scenario"));
        boolean sufficient = Integer.valueOf(1).equals(row.get("sufficient"));
        if (!sufficient) {
            Map<String, Object> basis = parseJsonMap(row.get("basis_json"));
            Object reason = basis.get("insufficiencyReason");
            return "本情景因数据不足未能得出测算结果：" + (reason == null ? "缺少有效计量数据" : reason)
                    + "。建议先补齐计量数据或调整测算区间后再试。";
        }
        return "① 历史事实：基准区间为 " + row.get("start_date") + " ~ " + row.get("end_date")
                + "，数据取自平台事实表。② 模拟假设：情景为「"
                + ("REDUCE_RATIO".equals(scenario) ? "可调时段能耗按比例下降" : "制冷 COP 改善（负荷不变）")
                + "」，参数见结构化结果 params。③ 理论节能潜力：按结构化结果 result 中的 savedKwh 与"
                + " carbonSavedKg 计算，属理论估算，未经验证。④ 风险与建议：模拟结果不代表已实现效果，"
                + "实施前需现场核查设备工况与运行策略，并由运维人员审核确认。";
    }

    /** 数字守卫：回答中的数字必须能在结构化 JSON 里找到 */
    private boolean numbersGrounded(String text, String allowedJson) {
        java.util.Set<String> allowed = new java.util.HashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[0-9][0-9,]*\\.?[0-9]*").matcher(allowedJson);
        while (m.find()) {
            allowed.add(m.group().replace(",", "").replaceAll("\\.0+$", ""));
        }
        java.util.regex.Matcher t = java.util.regex.Pattern
                .compile("[0-9][0-9,]*\\.?[0-9]*").matcher(text);
        while (t.find()) {
            String n = t.group().replace(",", "").replaceAll("\\.0+$", "");
            if (n.isEmpty()) {
                continue;
            }
            double v;
            try {
                v = Double.parseDouble(n);
            } catch (Exception e) {
                continue;
            }
            // 允许小整数（序号、小时、百分比 0-100、年份）
            if (v <= 100 && v == Math.floor(v)) {
                continue;
            }
            if (!allowed.contains(n)) {
                return false;
            }
        }
        return true;
    }

    // ================================================================ 查询

    public SavingSimulationVO get(long id) {
        Map<String, Object> row = loadRow(id);
        return fromMap(row);
    }

    public List<SavingSimulationVO> list(String buildingId, int limit) {
        String sql = "SELECT * FROM energy_saving_simulation "
                + (buildingId == null || buildingId.isBlank() ? "" : "WHERE building_id = ? ")
                + "ORDER BY id DESC LIMIT ?";
        Object[] args = buildingId == null || buildingId.isBlank()
                ? new Object[]{Math.min(Math.max(limit, 1), 50)}
                : new Object[]{buildingId, Math.min(Math.max(limit, 1), 50)};
        return jdbcTemplate.query(sql, (rs, i) -> fromResultSet(rs), args);
    }

    private SavingSimulationVO fromMap(Map<String, Object> row) {
        SavingSimulationVO vo = new SavingSimulationVO();
        vo.setId(((Number) row.get("id")).longValue());
        vo.setBuildingId(str(row.get("building_id")));
        vo.setSiteId(str(row.get("site_id")));
        String scenario = str(row.get("scenario"));
        vo.setScenario(scenario);
        vo.setScenarioName(scenarioName(scenario));
        vo.setStartDate(String.valueOf(row.get("start_date")));
        vo.setEndDate(String.valueOf(row.get("end_date")));
        vo.setSufficient(Integer.valueOf(1).equals(row.get("sufficient")));
        vo.setParams(parseJsonMap(row.get("params_json")));
        vo.setBaseline(parseJsonMap(row.get("baseline_json")));
        vo.setResult(parseJsonMap(row.get("result_json")));
        Map<String, Object> basis = parseJsonMap(row.get("basis_json"));
        vo.setBasis(basis);
        vo.setInsufficiencyReason(basis.get("insufficiencyReason") == null
                ? null : String.valueOf(basis.get("insufficiencyReason")));
        vo.setAiNarrative(str(row.get("ai_narrative")));
        vo.setAiMode(str(row.get("ai_mode")));
        return vo;
    }

    private SavingSimulationVO fromResultSet(java.sql.ResultSet rs) throws java.sql.SQLException {
        SavingSimulationVO vo = new SavingSimulationVO();
        vo.setId(rs.getLong("id"));
        vo.setBuildingId(rs.getString("building_id"));
        vo.setSiteId(rs.getString("site_id"));
        String scenario = rs.getString("scenario");
        vo.setScenario(scenario);
        vo.setScenarioName(scenarioName(scenario));
        vo.setStartDate(String.valueOf(rs.getDate("start_date")));
        vo.setEndDate(String.valueOf(rs.getDate("end_date")));
        vo.setSufficient(rs.getInt("sufficient") == 1);
        vo.setParams(parseJsonMap(rs.getString("params_json")));
        vo.setBaseline(parseJsonMap(rs.getString("baseline_json")));
        vo.setResult(parseJsonMap(rs.getString("result_json")));
        Map<String, Object> basis = parseJsonMap(rs.getString("basis_json"));
        vo.setBasis(basis);
        vo.setInsufficiencyReason(basis.get("insufficiencyReason") == null
                ? null : String.valueOf(basis.get("insufficiencyReason")));
        vo.setAiNarrative(rs.getString("ai_narrative"));
        vo.setAiMode(rs.getString("ai_mode"));
        return vo;
    }

    private String scenarioName(String scenario) {
        return "REDUCE_RATIO".equals(scenario) ? "可调时段能耗按比例下降"
                : "COP_IMPROVE".equals(scenario) ? "制冷 COP 改善（负荷不变）" : scenario;
    }

    private Map<String, Object> loadRow(long id) {
        try {
            return jdbcTemplate.queryForMap("SELECT * FROM energy_saving_simulation WHERE id = ?", id);
        } catch (Exception e) {
            throw new BizException(ResultCode.NOT_FOUND, "模拟记录不存在: " + id);
        }
    }

    // ================================================================ 落库

    private void persist(SavingSimulationVO vo) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO energy_saving_simulation
                      (building_id, site_id, scenario, start_date, end_date, sufficient,
                       params_json, baseline_json, result_json, basis_json, created_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    vo.getBuildingId(), resolveSiteId(vo.getBuildingId()), vo.getScenario(),
                    LocalDate.parse(vo.getStartDate()), LocalDate.parse(vo.getEndDate()),
                    vo.isSufficient() ? 1 : 0,
                    json.writeValueAsString(vo.getParams()),
                    json.writeValueAsString(vo.getBaseline()),
                    json.writeValueAsString(vo.getResult()),
                    json.writeValueAsString(withReason(vo)),
                    com.carbon.platform.common.util.SecurityUtils.currentUsername());
            Long id = jdbcTemplate.queryForObject(
                    "SELECT MAX(id) FROM energy_saving_simulation WHERE created_by = ?",
                    Long.class, com.carbon.platform.common.util.SecurityUtils.currentUsername());
            vo.setId(id);
        } catch (Exception e) {
            // 保存失败不应影响本次测算结果的返回
            log.warn("模拟结果保存失败（不影响测算）：{}", e.getMessage());
        }
    }

    // ================================================================ 工具

    private SavingSimulationVO baseVo(String scenario, String name, String buildingId,
                                      LocalDate start, LocalDate end) {
        SavingSimulationVO vo = new SavingSimulationVO();
        vo.setScenario(scenario);
        vo.setScenarioName(name);
        vo.setBuildingId(buildingId);
        vo.setStartDate(start.toString());
        vo.setEndDate(end.toString());
        return vo;
    }

    private Map<String, Object> basis(String scenario, BigDecimal price) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("carbonFactor", CARBON_FACTOR);
        b.put("carbonFactorNote", "电网平均排放因子 0.5810 kgCO₂/kWh，与导入/查询口径一致");
        b.put("electricityUnit", "kWh");
        b.put("formula", "REDUCE_RATIO".equals(scenario)
                ? "saved = 时段基准电量 × 比例；simulated = 基准 − saved；carbon = saved × 0.5810"
                : "Q=E×COP 固定 ⇒ saved = E_hvac × (1 − COP_old / COP_new)；只作用于空调分项");
        b.put("copNote", "COP_IMPROVE".equals(scenario)
                ? "COP 取负荷加权平均：ΣE_hvac / Σ(E_hvac/COP)；仅统计有 COP 且空调能耗>0 的小时"
                : null);
        b.put("costNote", price == null
                ? "平台未配置电价，电费节约需用户提供电价；不提供则返回数据不足"
                : "电费按用户提供电价估算，非电网实际结算价");
        b.put("limitations", List.of(
                "模拟结果为理论潜力，不代表已实现节能效果",
                "未考虑设备改造投资、施工停机与运维成本",
                "COP 情景假设制冷负荷与水力工况不变，实际运行受负荷率影响",
                "COP 与冷冻水温度字段在部分数据源中为仿真值，使用前请核对数据字典"));
        b.put("dataSource", "fact_energy_hourly");
        return b;
    }

    /** 把数据不足原因一并写入 basis_json，便于列表页直接展示 */
    private Map<String, Object> withReason(SavingSimulationVO vo) {
        Map<String, Object> b = new LinkedHashMap<>(vo.getBasis() == null ? Map.of() : vo.getBasis());
        if (vo.getInsufficiencyReason() != null) {
            b.put("insufficiencyReason", vo.getInsufficiencyReason());
        }
        return b;
    }

    private String resolveSiteId(String buildingId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT site_id FROM dim_building WHERE building_id = ?", String.class, buildingId);
        } catch (Exception e) {
            return null;
        }
    }

    private void assertBuildingExists(String buildingId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dim_building WHERE building_id = ?", Integer.class, buildingId);
        if (n == null || n == 0) {
            throw new BizException(ResultCode.BUILDING_NOT_FOUND, "建筑不存在: " + buildingId);
        }
    }

    private BigDecimal validPrice(Double price) {
        if (price == null) {
            return null;
        }
        if (price <= 0 || price > 5) {
            throw new BizException(ResultCode.BAD_REQUEST, "电价必须大于 0 且不超过 5 元/kWh");
        }
        return BigDecimal.valueOf(price);
    }

    private LocalDate parseDate(String s, String label) {
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            throw new BizException(ResultCode.BAD_REQUEST, label + "格式须为 YYYY-MM-DD");
        }
    }

    private BigDecimal dec(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        return o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    private long lng(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private BigDecimal scale(BigDecimal v, int s) {
        return v == null ? null : v.setScale(s, RoundingMode.HALF_UP);
    }

    private double pct(double ratio) {
        return BigDecimal.valueOf(ratio * 100).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(Object o) {
        if (o == null) {
            return Map.of();
        }
        try {
            return json.readValue(String.valueOf(o), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}

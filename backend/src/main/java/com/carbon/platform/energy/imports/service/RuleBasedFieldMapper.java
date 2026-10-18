package com.carbon.platform.energy.imports.service;

import com.carbon.platform.energy.imports.ImportTargetField;
import com.carbon.platform.energy.imports.dto.UploadResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 规则引擎版字段映射（LLM 的兜底方案）。
 *
 * <p>评分依据三路证据加权：
 * <ol>
 *   <li><b>别名匹配</b>：列名归一化后与目标字段别名做「全等 / 包含」比较，
 *       全等得 1.0，包含按别名长度给 0.62~0.90（别名越长越可信，
 *       避免 "e" 这类短别名误命中）；</li>
 *   <li><b>类型线索</b>：时间字段偏好 datetime 列，能耗字段偏好 number 列；</li>
 *   <li><b>样本取值域</b>：湿度应在 0~100，环境温度应在 -40~60。</li>
 * </ol>
 * 低于 {@value #MIN_SCORE} 分视为无法判定，返回 null（前端显示"忽略该列"）。
 *
 * <p>局限（这也是引入 LLM 的原因）：规则依赖人工维护的别名表，
 * 遇到别名表未覆盖的脏列名（如「A栋电量(kWh)」「采集时刻」）会失手。
 */
@Slf4j
@Service
public class RuleBasedFieldMapper {

    /** 低于此分不给出建议 */
    private static final double MIN_SCORE = 0.55;

    private static final Pattern NUMERIC =
            Pattern.compile("^-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?$");

    /** 单个字段的映射结果 */
    public record Hit(String targetField, double confidence, String reason) {
    }

    /**
     * 对所有列执行规则映射。
     *
     * @return 规划结果（与 FieldMappingPlanner.Plan 结构对齐，但不含策略元信息，
     *         由 FieldMappingPlanner 包装）
     */
    public FieldMappingPlanner.Plan map(List<UploadResultVO.ColumnInfo> columns) {
        Map<String, String> mapping = new LinkedHashMap<>();
        Map<String, Double> confidence = new LinkedHashMap<>();
        Map<String, String> reason = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>();

        for (UploadResultVO.ColumnInfo c : columns) {
            Hit hit = suggest(c);
            if (hit == null) {
                continue;
            }
            // 一个目标字段只映射一次：保留首个（列顺序即优先级）
            if (!taken.add(hit.targetField())) {
                log.debug("目标字段 {} 已被占用，忽略源列 {}", hit.targetField(), c.getSourceColumn());
                continue;
            }
            mapping.put(c.getSourceColumn(), hit.targetField());
            confidence.put(c.getSourceColumn(), hit.confidence());
            reason.put(c.getSourceColumn(), hit.reason());
        }
        return new FieldMappingPlanner.Plan(mapping, confidence, reason,
                "RULE", "builtin", "rule-engine", 0L, null);
    }

    /** 对单列给出映射建议；无法判定返回 null */
    public Hit suggest(UploadResultVO.ColumnInfo column) {
        String col = normalize(column.getSourceColumn());
        if (col.isEmpty()) {
            return null;
        }

        ImportTargetField bestField = null;
        double bestScore = 0;
        String bestBasis = null;

        for (ImportTargetField f : ImportTargetField.all()) {
            double score = 0;
            String basis = null;

            for (String alias : f.getAliases()) {
                String a = normalize(alias);
                if (a.isEmpty()) {
                    continue;
                }
                if (col.equals(a)) {
                    if (1.0 > score) {
                        score = 1.0;
                        basis = String.format("列名与「%s」完全相同", f.getLabel());
                    }
                } else if (col.contains(a) || a.contains(col)) {
                    double s = 0.62 + Math.min(a.length(), 8) * 0.035;
                    if (s > score) {
                        score = s;
                        basis = String.format("列名含「%s」关键字", alias);
                    }
                }
            }

            String type = column.getInferredType();
            if (f == ImportTargetField.TS && "datetime".equals(type)) {
                score += 0.15;
                basis = basis == null ? "数据类型为时间" : basis + "，且为时间类型";
            }
            if (isEnergyField(f) && "number".equals(type)) {
                score += 0.05;
            }
            // 取值域校验
            if (!column.getSamples().isEmpty() && "number".equals(type)) {
                Double v0 = parseNumber(column.getSamples().get(0));
                if (v0 != null) {
                    if (f == ImportTargetField.HUMIDITY_PCT && v0 >= 0 && v0 <= 100) {
                        score += 0.04;
                    }
                    if (f == ImportTargetField.AIR_TEMPERATURE_C && v0 >= -40 && v0 <= 60) {
                        score += 0.04;
                    }
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestField = f;
                bestBasis = basis;
            }
        }

        if (bestField == null || bestScore < MIN_SCORE) {
            return null;
        }
        return new Hit(bestField.getColumn(), Math.min(bestScore, 1.0),
                bestBasis == null ? "按列名与数据类型匹配" : bestBasis);
    }

    private boolean isEnergyField(ImportTargetField f) {
        return f == ImportTargetField.ELECTRICITY_KWH || f == ImportTargetField.HVAC_KWH
                || f == ImportTargetField.WATER_M3 || f == ImportTargetField.GAS_KWH
                || f == ImportTargetField.AIR_TEMPERATURE_C;
    }

    /** 归一化：小写、去下划线/空格/连字符/括号单位，便于宽松比较 */
    private String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT)
                .replace("_", "").replace(" ", "").replace("-", "")
                .replace("（", "").replace("）", "")
                .replace("(", "").replace(")", "")
                .trim();
    }

    private Double parseNumber(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim().replace(",", "");
        if (!NUMERIC.matcher(s).matches()) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

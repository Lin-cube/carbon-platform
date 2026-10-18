package com.carbon.platform.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock 大模型客户端：不联网，按 Prompt 中的列名做确定性推断。
 *
 * <p>用途：在没有 API Key 的环境（如离线演示、CI）验证「AI 字段映射」整条链路
 * ——包括 Prompt 组装、JSON 解析、容错、降级、审计留痕。
 * 它<b>不是</b>规则引擎的替代品：规则引擎在 {@code FieldMappingPlanner} 内部，
 * 本类只是让"LLM 分支"能被测试到。
 *
 * <p>行为：解析 userPrompt 中形如 {@code 列名="xxx"} 的片段，
 * 用比规则引擎更宽松的包含关系给出 targetField，
 * 并刻意返回带 markdown 代码块包裹的 JSON，以验证解析器的容错能力。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "carbon.llm.provider", havingValue = "mock")
public class MockLlmClient implements LlmClient {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 关键词 -> 目标字段。匹配时按关键词长度降序，长关键词更具体、优先命中。 */
    private static final String[][] HINTS = {
            {"timestamp", "采集时间", "监测时间", "记录时间", "时间", "时刻", "日期",
                    "datetime", "date", "time", "timestamp"},
            {"building_id", "楼栋编号", "建筑编号", "建筑代码", "楼栋", "建筑", "楼号",
                    "building", "编号", "点位"},
            {"site_id", "园区", "站点", "校区", "site", "项目"},
            {"building_type_cn", "建筑类型", "类型", "用途", "业态", "type", "category", "性质"},
            {"electricity_kwh", "用电量", "用电", "电力", "电耗", "有功电能", "电量",
                    "electricity", "elec", "power", "kwh"},
            {"hvac_kwh", "空调能耗", "空调", "制冷", "采暖", "冷热", "hvac", "chiller"},
            {"water_m3", "水耗", "用水", "水量", "water", "水", "吨"},
            {"gas_kwh", "燃气", "天然气", "煤气", "gas", "气"},
            {"air_temperature_c", "室外温度", "环境温度", "干球温度", "温度", "气温",
                    "temperature", "temp", "摄氏"},
            {"humidity_pct", "相对湿度", "湿度", "humid", "rh"},
            {"occupancy_density", "人员密度", "人员在室", "人数", "人员", "occup"},
            {"area_m2", "建筑面积", "面积", "平米", "平方", "area", "sqm"},
    };

    /** 按关键词长度降序展开，保证"空调能耗"先于"电"被检查 */
    private static final List<String[]> HINT_RULES = buildRules();

    private static List<String[]> buildRules() {
        List<String[]> rules = new ArrayList<>();
        for (String[] pair : HINTS) {
            for (int i = 1; i < pair.length; i++) {
                rules.add(new String[]{pair[i], pair[0]});
            }
        }
        // 关键词越长越具体，排序后优先匹配
        rules.sort((a, b) -> Integer.compare(b[0].length(), a[0].length()));
        return rules;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public String model() {
        return "mock-llm";
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, double temperature) {
        try {
            ObjectNode root = mapper.createObjectNode();
            ArrayNode arr = root.putArray("mappings");
            Matcher m = Pattern.compile("列名=\"([^\"]*)\"").matcher(userPrompt);
            while (m.find()) {
                String col = m.group(1);
                String lower = col.toLowerCase();
                String hit = null;
                // 已按关键词长度降序，首个命中即最具体的匹配
                for (String[] rule : HINT_RULES) {
                    if (lower.contains(rule[0].toLowerCase())) {
                        hit = rule[1];
                        break;
                    }
                }
                ObjectNode item = arr.addObject();
                item.put("sourceColumn", col);
                if (hit == null) {
                    item.putNull("targetField");
                    item.put("confidence", 0.0);
                    item.put("reason", "语义上与任何目标字段均不匹配，建议忽略该列");
                } else {
                    item.put("targetField", hit);
                    item.put("confidence", 0.86);
                    item.put("reason", "根据列名语义与样例值判断应映射到 " + hit);
                }
            }
            // 刻意用 markdown 代码块包裹，验证解析器容错
            return "好的，已完成字段映射规划：\n\n```json\n"
                    + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                    + "\n```\n\n如需调整请告知。";
        } catch (Exception e) {
            log.warn("Mock LLM 生成失败", e);
            return null;
        }
    }
}

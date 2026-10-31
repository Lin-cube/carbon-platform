package com.carbon.platform.ai.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek（OpenAI 兼容）function calling 工具定义。
 *
 * <p>供两处共用：
 * <ul>
 *   <li>{@code GET /api/ai/tools/schema} 对外输出；</li>
 *   <li>{@code DeepSeekToolCallingService} 编排时作为 tools 参数发给模型。</li>
 * </ul>
 */
@Component
public class AiToolSchema {

    public List<Map<String, Object>> toolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(fn(
                "getParkEnergySummary", "查询某园区在日期范围内的能耗汇总：总用电、空调能耗、碳排放、平均COP、异常小时与峰谷时段。",
                List.of(
                        p("siteId", "string", "园区编号；不填表示全部园区；不确定有哪些园区时先调 listSites"),
                        p("startDate", "string", "开始日期，YYYY-MM-DD"),
                        p("endDate", "string", "结束日期，YYYY-MM-DD")),
                List.of("siteId", "startDate", "endDate")));

        tools.add(fn(
                "getBuildingEnergySummary", "查询单栋建筑在日期范围内的能耗汇总：类型、面积、评级、用电、空调、碳排、COP、异常小时。",
                List.of(
                        p("buildingId", "string", "建筑编号（形如 园区_类型_名称，可用 getTopEnergyBuildings 查候选）"),
                        p("startDate", "string", "开始日期，YYYY-MM-DD"),
                        p("endDate", "string", "结束日期，YYYY-MM-DD")),
                List.of("buildingId", "startDate", "endDate")));

        tools.add(fn(
                "getBuildingEnergyTrend", "查询单栋建筑的月度能耗趋势（用电/空调/碳排），最多返回 12 个月。",
                List.of(
                        p("buildingId", "string", "建筑编号"),
                        p("startDate", "string", "开始日期，YYYY-MM-DD"),
                        p("endDate", "string", "结束日期，YYYY-MM-DD")),
                List.of("buildingId", "startDate", "endDate")));

        tools.add(fn(
                "getTopEnergyBuildings", "查询日期范围内用电量最高的建筑排行，默认前 10，最多 50。",
                List.of(
                        p("siteId", "string", "园区编号，可空表示全部园区"),
                        p("startDate", "string", "开始日期，YYYY-MM-DD"),
                        p("endDate", "string", "结束日期，YYYY-MM-DD"),
                        p("limit", "integer", "返回条数，默认 10，最大 50")),
                List.of("startDate", "endDate")));

        tools.add(fn(
                "getBuildingCOP", "查询单栋建筑的机组能效：平均/最高/最低 COP、能效评级、供回水温差。无冷冻水计量的建筑返回无数据。",
                List.of(
                        p("buildingId", "string", "建筑编号"),
                        p("startDate", "string", "开始日期，YYYY-MM-DD"),
                        p("endDate", "string", "结束日期，YYYY-MM-DD")),
                List.of("buildingId", "startDate", "endDate")));

        tools.add(fn(
                "getBuildingAnomalies", "查询「单栋建筑」的异常事件明细：事件数、异常小时、平均/最长持续时长、事件列表。必须指定 buildingId；问全平台/全园区异常概览请用 getAnomalyOverview。",
                List.of(
                        p("buildingId", "string", "建筑编号"),
                        p("startDate", "string", "开始日期，YYYY-MM-DD"),
                        p("endDate", "string", "结束日期，YYYY-MM-DD"),
                        p("limit", "integer", "事件明细条数，默认 10，最大 50")),
                List.of("buildingId", "startDate", "endDate")));

        tools.add(fn(
                "getCarbonEmissionSummary", "查询日期范围内的碳排放汇总（kg/吨），按电网平均排放因子 0.5810 kgCO₂/kWh 核算。",
                List.of(
                        p("siteId", "string", "园区编号，可空表示全部园区"),
                        p("startDate", "string", "开始日期，YYYY-MM-DD"),
                        p("endDate", "string", "结束日期，YYYY-MM-DD")),
                List.of("startDate", "endDate")));

        tools.add(fn(
                "getTopAnomalyBuildings", "查询异常「持续时长」最长的建筑排行（单次最长持续小时、事件数、累计异常小时）。仅在用户明确问「哪栋楼异常持续时间最长/最久」时使用；问「有哪些异常」「异常率多少」请用 getAnomalyOverview。",
                List.of(
                        p("siteId", "string", "园区编号，可空表示全部园区"),
                        p("startDate", "string", "开始日期，YYYY-MM-DD"),
                        p("endDate", "string", "结束日期，YYYY-MM-DD"),
                        p("limit", "integer", "返回条数，默认 10，最大 50")),
                List.of("startDate", "endDate")));

        tools.add(fn(
                "getAnomalyOverview", "查询异常总体情况：异常率、异常小时、事件数、异常类型分布（能耗偏高/偏低/持续运行等各自占比）。"
                        + "回答「有哪些能耗异常」「异常情况如何」「异常率多少」这类概览问题必须用本工具。",
                List.of(
                        p("siteId", "string", "园区编号；不填表示全部园区"),
                        p("startDate", "string", "开始日期，YYYY-MM-DD"),
                        p("endDate", "string", "结束日期，YYYY-MM-DD")),
                List.of("startDate", "endDate")));

        tools.add(fn(
                "listSites", "列出平台全部园区（含建筑数与年度用电量）。不确定有哪些园区、或用户问「有哪些园区」时先调用本工具。",
                List.of(),
                List.of()));

        tools.add(fn(
                "getTopSites", "查询日期范围内各园区的用电量排行（一次返回园区维度排名，勿逐个园区循环调用）。",
                List.of(
                        p("startDate", "string", "开始日期，YYYY-MM-DD"),
                        p("endDate", "string", "结束日期，YYYY-MM-DD"),
                        p("limit", "integer", "返回条数，默认 10，最大 50")),
                List.of("startDate", "endDate")));

        // 第 9 个工具：联网搜索（外部通用知识/政策/标准/行业实践）。
        // 与内部能源查询工具保持服务层独立——只调元宝搜索，不碰数据库。
        tools.add(fn(
                "webSearch", "联网搜索外部公开资料（政策法规、行业标准、运维最佳实践、气候/双碳新闻等）。"
                        + "仅在问题需要外部通用知识、而内部能源数据无法回答时使用；"
                        + "实际用电量/COP/碳排放/异常等数据问题请用能源工具，不要用本工具。",
                List.of(
                        p("query", "string", "检索词，尽量具体，例如「离心机喘振处理 运维规范」"),
                        p("limit", "integer", "返回条数，默认 5，最大 10")),
                List.of("query")));

        return tools;
    }

    private Map<String, Object> fn(String name, String desc, List<Map<String, Object>> props,
                                   List<String> required) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map<String, Object> prop : props) {
            String key = (String) prop.remove("key");
            properties.put(key, prop);
        }
        parameters.put("properties", properties);
        parameters.put("required", required);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", desc);
        function.put("parameters", parameters);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private Map<String, Object> p(String name, String type, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", name);
        m.put("type", type);
        m.put("description", desc);
        return m;
    }
}

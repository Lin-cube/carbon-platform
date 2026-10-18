package com.carbon.platform.energy.imports;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 导入目标字段定义（与统一能耗数据集列名对齐）。
 *
 * <p>每个字段登记：列名、中文名、别名（用于自动映射）、是否必填、单位。
 * 自动映射靠别名做包含匹配 + 相似度打分，避免用户手工逐列挑选。
 */
@Getter
public enum ImportTargetField {

    BUILDING_ID("building_id", "建筑编号", true, "-",
            List.of("buildingid", "building", "建筑编号", "建筑", "楼栋", "楼号", "建筑代码", "building_id")),
    SITE_ID("site_id", "园区编号", false, "-",
            List.of("siteid", "site", "园区", "站点", "校区", "园区编号")),
    BUILDING_TYPE_CN("building_type_cn", "建筑类型", false, "-",
            List.of("buildingtype", "类型", "建筑类型", "用途", "业态")),
    TS("timestamp", "监测时间", true, "yyyy-MM-dd HH:mm",
            List.of("timestamp", "time", "datetime", "时间", "时刻", "监测时间", "采集时间",
                    "记录时间", "统计时间", "上报时间", "日期时间", "抄表时间")),
    ELECTRICITY_KWH("electricity_kwh", "电力能耗", false, "kWh",
            List.of("electricity", "elec", "power", "kwh", "电量", "电力", "用电", "电耗",
                    "耗电", "度数", "电度数", "耗电度数", "有功电能", "电能")),
    HVAC_KWH("hvac_kwh", "空调系统能耗", false, "kWh",
            List.of("hvac", "空调", "空调能耗", "冷热", "制冷", "制冷耗能", "冷量",
                    "空调冷量", "chiller")),
    WATER_M3("water_m3", "水耗", false, "m³",
            List.of("water", "水", "水耗", "用水", "用水量", "水量", "总水量")),
    GAS_KWH("gas_kwh", "燃气能耗", false, "kWh",
            List.of("gas", "燃气", "天然气", "煤气")),
    AIR_TEMPERATURE_C("air_temperature_c", "环境温度", false, "℃",
            List.of("airtemp", "outdoortemp", "temperature", "temp", "气温", "温度",
                    "室外温度", "室外气温", "环境温度", "干球温度")),
    HUMIDITY_PCT("humidity_pct", "相对湿度", false, "%RH",
            List.of("humidity", "rh", "湿度", "相对湿度")),
    CHW_SUPPLY_TEMP_C("chw_supply_temp_c", "空调出水温度", false, "℃",
            List.of("supplytemp", "chwsupply", "供水温度", "出水温度", "冷冻水供水")),
    CHW_RETURN_TEMP_C("chw_return_temp_c", "空调回水温度", false, "℃",
            List.of("returntemp", "chwreturn", "回水温度", "冷冻水回水")),
    OCCUPANCY_DENSITY("occupancy_density", "人员密度", false, "人/100㎡",
            List.of("occupancy", "occup", "人员", "人数", "人员数量", "人员在室", "在室人数")),
    AREA_M2("area_m2", "建筑面积", false, "m²",
            List.of("area", "sqm", "面积", "建筑面积"));

    private final String column;
    private final String label;
    private final boolean required;
    private final String unit;
    private final List<String> aliases;

    ImportTargetField(String column, String label, boolean required, String unit, List<String> aliases) {
        this.column = column;
        this.label = label;
        this.required = required;
        this.unit = unit;
        this.aliases = aliases;
    }

    public static Optional<ImportTargetField> byColumn(String column) {
        return Arrays.stream(values()).filter(f -> f.column.equals(column)).findFirst();
    }

    /** 全部可映射字段（供前端下拉） */
    public static List<ImportTargetField> all() {
        return Arrays.asList(values());
    }
}

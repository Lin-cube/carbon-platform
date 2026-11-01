package com.carbon.platform.energy.imports.service;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据源自动分类：只看表头语义，不依赖文件名。
 *
 * <p>批量导入时，用户可能一次拖入多个来源的文件（能耗时序、建筑台账、园区清单…）。
 * 先按表头判断每个文件是什么，再决定用哪套字段映射与清洗规则，避免把台账当能耗数据导入。
 *
 * <p>判定规则刻意保守：识别不出就返回 {@code UNKNOWN}，由前端提示用户手工确认，
 * 不做"猜一个"这种会在无人值守时写错数据的动作。
 */
public final class DataSourceClassifier {

    public static final String ENERGY_HOURLY = "ENERGY_HOURLY";
    public static final String BUILDING_META = "BUILDING_META";
    public static final String UNKNOWN = "UNKNOWN";

    private static final String[] TIME_KEYS = {"时间", "日期", "timestamp", "time", "date", "采集时间", "监测时间"};
    private static final String[] ENERGY_KEYS = {"电量", "能耗", "电力", "用电", "kwh", "电耗", "功率"};
    private static final String[] BUILDING_KEYS = {"建筑", "building", "楼", "楼宇"};
    private static final String[] AREA_KEYS = {"面积", "area"};

    private DataSourceClassifier() {
    }

    public static String classify(List<String> headers) {
        if (headers == null || headers.isEmpty()) {
            return UNKNOWN;
        }
        boolean hasTime = containsAny(headers, TIME_KEYS);
        boolean hasEnergy = containsAny(headers, ENERGY_KEYS);
        boolean hasBuilding = containsAny(headers, BUILDING_KEYS);
        boolean hasArea = containsAny(headers, AREA_KEYS);

        // 有时间 + 有能耗 → 时序能耗数据
        if (hasTime && hasEnergy) {
            return ENERGY_HOURLY;
        }
        // 有建筑标识但没有时间维度 → 建筑台账/元数据
        if (hasBuilding && !hasTime) {
            return BUILDING_META;
        }
        // 有建筑 + 面积且无能耗 → 台账
        if (hasBuilding && hasArea && !hasEnergy) {
            return BUILDING_META;
        }
        return UNKNOWN;
    }

    public static String label(String type) {
        return switch (type) {
            case ENERGY_HOURLY -> "能耗时序数据";
            case BUILDING_META -> "建筑台账数据";
            default -> "未识别（需人工确认）";
        };
    }

    private static boolean containsAny(List<String> headers, String[] keys) {
        for (String h : headers) {
            if (!StringUtils.hasText(h)) {
                continue;
            }
            String low = h.toLowerCase(Locale.ROOT);
            for (String k : keys) {
                if (low.contains(k)) {
                    return true;
                }
            }
        }
        return false;
    }
}

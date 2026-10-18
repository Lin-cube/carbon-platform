package com.carbon.platform.analytics.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.excel.write.metadata.style.WriteCellStyle;
import com.alibaba.excel.write.metadata.style.WriteFont;
import com.alibaba.excel.write.style.HorizontalCellStyleStrategy;
import com.carbon.platform.analytics.dto.AnomalyAnalysisVO;
import com.carbon.platform.analytics.dto.PeriodSummaryVO;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 报表导出服务：生成 Excel 统计报表（申报表"可自动生成并导出统计报表"）。
 *
 * <p>用 EasyExcel 流式写出，多 Sheet 结构：
 * 概览 / 时段汇总 / 分时占比 / 异常分析 / 异常建筑排名。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportExportService {

    private final AnalyticsService analyticsService;

    /** 导出能耗统计报表 */
    public void exportEnergyReport(HttpServletResponse response,
                                   LocalDate startDate, LocalDate endDate,
                                   String siteId, String buildingType,
                                   String periodType) throws IOException {
        String pt = StringUtils.hasText(periodType) ? periodType : "month";

        PeriodSummaryVO period = analyticsService.periodSummary(pt, startDate, endDate,
                null, siteId, buildingType);
        AnomalyAnalysisVO anomaly = analyticsService.anomalyAnalysis(startDate, endDate, siteId, null);

        // ---------------- Sheet 1 概览 ----------------
        List<List<String>> headOverview = List.of(List.of("指标"), List.of("数值"));
        List<List<Object>> rowsOverview = new ArrayList<>();
        rowsOverview.add(List.of("统计区间", startDate + " ~ " + endDate));
        rowsOverview.add(List.of("统计口径", ptLabel(pt)));
        rowsOverview.add(List.of("园区范围", StringUtils.hasText(siteId) ? siteId : "全部"));
        rowsOverview.add(List.of("建筑类型", StringUtils.hasText(buildingType) ? buildingType : "全部"));
        rowsOverview.add(List.of("总用电量 (kWh)", nz(period.getTotal())));
        rowsOverview.add(List.of("峰值时段", str(period.getPeakLabel()) + " / " + nz(period.getPeak()) + " kWh"));
        rowsOverview.add(List.of("谷值时段", str(period.getValleyLabel()) + " / " + nz(period.getValley()) + " kWh"));
        rowsOverview.add(List.of("峰谷差率 (%)", nz(period.getPeakValleyRatio())));
        rowsOverview.add(List.of("评估小时数（仅A级独立计量）", nz(anomaly.getEvaluatedHours())));
        rowsOverview.add(List.of("能耗异常小时数", nz(anomaly.getAnomalyHours())));
        rowsOverview.add(List.of("能耗异常率 (%)", nz(anomaly.getAnomalyRate())));
        rowsOverview.add(List.of("数据质量缺陷小时数", nz(anomaly.getQualityHours())));
        rowsOverview.add(List.of("异常事件数", nz(anomaly.getEventCount())));
        rowsOverview.add(List.of("平均事件持续 (小时)", nz(anomaly.getAvgDurationHours())));
        rowsOverview.add(List.of("报表生成时间", LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
        rowsOverview.add(List.of("数据来源", "BDG2 开源数据集（CC-BY 4.0）+ 分层基线-偏差算法标注"));
        rowsOverview.add(List.of("说明", "空调供回水温度/COP/人员密度为仿真值，详见数据字典"));

        // ---------------- Sheet 2 时段汇总 ----------------
        List<List<String>> headPeriod = List.of(
                List.of("时段"), List.of("用电量(kWh)"), List.of("空调能耗(kWh)"),
                List.of("水耗(m³)"), List.of("碳排放(kgCO₂)"), List.of("平均COP"),
                List.of("平均室外温度(℃)"), List.of("异常小时数"), List.of("环比变化(%)"));
        List<List<Object>> rowsPeriod = new ArrayList<>();
        for (PeriodSummaryVO.Bucket b : period.getBuckets()) {
            rowsPeriod.add(List.of(
                    str(b.getLabel()), nz(b.getElectricityKwh()), nz(b.getHvacKwh()),
                    nz(b.getWaterM3()), nz(b.getCarbonKg()), nz(b.getCopAvg()),
                    nz(b.getAirTempAvg()), nz(b.getAnomalyHours()), nz(b.getMomChange())));
        }

        // ---------------- Sheet 3 分时占比 ----------------
        List<List<String>> headTariff = List.of(
                List.of("时段类型"), List.of("时段划分"), List.of("用电量(kWh)"),
                List.of("占比(%)"), List.of("平均每小时(kWh)"));
        List<List<Object>> rowsTariff = new ArrayList<>();
        if (period.getTariffBuckets() != null) {
            for (PeriodSummaryVO.TariffBucket t : period.getTariffBuckets()) {
                rowsTariff.add(List.of(str(t.getName()), str(t.getHours()),
                        nz(t.getElectricityKwh()), nz(t.getPercentage()), nz(t.getAvgHourlyKwh())));
            }
        }

        // ---------------- Sheet 4 异常分析 ----------------
        List<List<String>> headAnomalyType = List.of(
                List.of("异常类型"), List.of("事件数"), List.of("占比(%)"), List.of("说明"));
        List<List<Object>> rowsAnomalyType = new ArrayList<>();
        if (anomaly.getByType() != null) {
            for (AnomalyAnalysisVO.ByType t : anomaly.getByType()) {
                rowsAnomalyType.add(List.of(str(t.getType()), nz(t.getCount()),
                        nz(t.getPercentage()), str(t.getDescription())));
            }
        }

        // ---------------- Sheet 5 异常建筑排名 ----------------
        List<List<String>> headBuilding = List.of(
                List.of("建筑编号"), List.of("园区"), List.of("建筑类型"),
                List.of("异常小时数"), List.of("异常率"), List.of("峰值偏离(%)"), List.of("主要异常类型"));
        List<List<Object>> rowsBuilding = new ArrayList<>();
        if (anomaly.getTopBuildings() != null) {
            for (AnomalyAnalysisVO.ByBuilding b : anomaly.getTopBuildings()) {
                rowsBuilding.add(List.of(str(b.getBuildingId()), str(b.getSiteId()),
                        str(b.getBuildingTypeCn()), nz(b.getAnomalyHours()),
                        nz(b.getAnomalyRate()), nz(b.getPeakDeviationPct()), str(b.getDominantType())));
            }
        }

        // ---------------- 写出多 Sheet ----------------
        String rawName = String.format("碳能智控_能耗统计报表_%s_%s.xlsx", startDate, endDate);
        String encoded = URLEncoder.encode(rawName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment;filename*=utf-8''" + encoded);

        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream())
                .registerWriteHandler(styleStrategy())
                .build()) {
            writer.write(rowsOverview, sheet(0, "概览", headOverview));
            writer.write(rowsPeriod, sheet(1, "时段汇总", headPeriod));
            writer.write(rowsTariff, sheet(2, "分时占比", headTariff));
            writer.write(rowsAnomalyType, sheet(3, "异常分析", headAnomalyType));
            writer.write(rowsBuilding, sheet(4, "异常建筑排名", headBuilding));
            writer.finish();
        }
        log.info("报表导出完成: {} ~ {} (5 sheet)", startDate, endDate);
    }

    private WriteSheet sheet(int index, String name, List<List<String>> head) {
        return EasyExcel.writerSheet(index, name).head(head).build();
    }

    private HorizontalCellStyleStrategy styleStrategy() {
        WriteCellStyle head = new WriteCellStyle();
        head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        WriteFont font = new WriteFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        head.setWriteFont(font);
        head.setHorizontalAlignment(HorizontalAlignment.CENTER);

        WriteCellStyle body = new WriteCellStyle();
        body.setVerticalAlignment(VerticalAlignment.CENTER);
        body.setBorderBottom(BorderStyle.THIN);
        body.setBorderLeft(BorderStyle.THIN);
        body.setBorderRight(BorderStyle.THIN);
        body.setBorderTop(BorderStyle.THIN);
        return new HorizontalCellStyleStrategy(head, body);
    }

    private String ptLabel(String pt) {
        return switch (pt) {
            case "year" -> "按年";
            case "quarter" -> "按季度";
            case "week" -> "按周";
            case "day" -> "按日";
            default -> "按月";
        };
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static Object nz(Object o) {
        return o == null ? "" : o;
    }
}

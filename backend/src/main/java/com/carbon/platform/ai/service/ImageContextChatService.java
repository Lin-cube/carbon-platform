package com.carbon.platform.ai.service;

import com.carbon.platform.ai.llm.LlmClient;
import com.carbon.platform.common.exception.BizException;
import com.carbon.platform.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「截图提问」：用户上传一张看板/图表截图，希望有人解释这段时间发生了什么。
 *
 * <h3>能力边界（必须如实告知用户）</h3>
 * 当前接入的 DeepSeek {@code deepseek-chat} <b>不支持图片输入</b>，
 * 因此系统<b>不解析截图像素</b>。真正做的是：
 * <ol>
 *   <li>校验并保存截图（归档，供人工核对）；</li>
 *   <li>取该时间范围（可选建筑）的<b>真实数据快照</b>——
 *       用电/空调/碳排、异常事件数与时长、异常最多的建筑、近期逐日趋势；</li>
 *   <li>把快照交给大模型，让它解释「这段时间发生了什么、可能原因、建议」。</li>
 * </ol>
 *
 * <p>这样回答的依据是可核对的真实数据，而不是对图片的猜测；
 * 返回体中 {@code imageAnalyzed=false} 与 {@code notice} 会明确说明这一点，
 * 不把「按数据处理」包装成「模型看图」。
 *
 * <p>若后续换成支持视觉的模型（如 Qwen-VL），只需在此处把图片以
 * image_url 形式加入消息体即可，其余链路不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageContextChatService {

    private final LlmClient llmClient;
    private final JdbcTemplate jdbcTemplate;

    @Value("${carbon.ai.upload-dir:.ai-uploads}")
    private String uploadDir;

    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final List<String> ALLOWED = List.of("jpg", "jpeg", "png", "webp");

    /**
     * 处理「截图 + 问题」。
     *
     * @param buildingId 可空；为空表示全平台
     * @param page       截图来源页面（dashboard/analytics/anomaly…），仅作提示
     */
    public Map<String, Object> analyze(MultipartFile image, String question, String sessionId,
                                       String buildingId, LocalDate startDate, LocalDate endDate,
                                       String page) {
        if (image == null || image.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST, "请选择要上传的截图");
        }
        if (image.getSize() > MAX_BYTES) {
            throw new BizException(ResultCode.BAD_REQUEST, "截图不能超过 5MB");
        }
        String ext = extOf(image.getOriginalFilename());
        if (!ALLOWED.contains(ext)) {
            throw new BizException(ResultCode.BAD_REQUEST, "仅支持 jpg / png / webp 截图");
        }
        // 真实解码校验：挡住改了后缀的非图片文件
        int width = 0;
        int height = 0;
        try (InputStream in = image.getInputStream()) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                throw new BizException(ResultCode.BAD_REQUEST, "文件不是有效图片");
            }
            width = img.getWidth();
            height = img.getHeight();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ResultCode.BAD_REQUEST, "图片读取失败：" + e.getMessage());
        }

        String imageId = save(image, ext);
        Map<String, Object> snapshot = buildSnapshot(buildingId, startDate, endDate);
        String answer = explain(question, snapshot, page, width, height);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imageId", imageId);
        out.put("imageAnalyzed", false);
        out.put("notice", "当前模型（deepseek-chat）不支持图片识别，截图已归档；"
                + "以下分析基于该时段的真实能耗数据，不是对图片内容的猜测。");
        out.put("snapshot", snapshot);
        out.put("answer", answer);
        return out;
    }

    // ================================================================ 数据快照

    private Map<String, Object> buildSnapshot(String buildingId, LocalDate startDate, LocalDate endDate) {
        boolean byBuilding = StringUtils.hasText(buildingId);
        String where = byBuilding ? " AND building_id = ?" : "";
        Object[] args = byBuilding
                ? new Object[]{startDate, endDate, buildingId}
                : new Object[]{startDate, endDate};

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("buildingId", byBuilding ? buildingId : "全部园区");
        s.put("dateRange", startDate + " ~ " + endDate);

        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT COUNT(*) AS hours, "
                            + "COALESCE(SUM(electricity_kwh),0) AS kwh, "
                            + "COALESCE(SUM(hvac_kwh),0) AS hvac, "
                            + "COALESCE(SUM(carbon_kg),0) AS carbon "
                            + "FROM fact_energy_hourly WHERE stat_date BETWEEN ? AND ?" + where, args);
            s.put("totalElectricityKwh", row.get("kwh"));
            s.put("hvacKwh", row.get("hvac"));
            s.put("carbonKg", row.get("carbon"));
            s.put("hours", row.get("hours"));
        } catch (Exception e) {
            s.put("energyError", e.getMessage());
        }

        try {
            Object[] a = byBuilding
                    ? new Object[]{startDate, endDate, buildingId}
                    : new Object[]{startDate, endDate};
            Map<String, Object> an = jdbcTemplate.queryForMap(
                    "SELECT COUNT(*) AS events, COALESCE(SUM(duration_hours),0) AS hours "
                            + "FROM fact_anomaly_event WHERE start_ts >= ? "
                            + "AND start_ts < DATE_ADD(?, INTERVAL 1 DAY)"
                            + (byBuilding ? " AND building_id = ?" : ""), a);
            s.put("anomalyEvents", an.get("events"));
            s.put("anomalyHours", an.get("hours"));
        } catch (Exception e) {
            s.put("anomalyError", e.getMessage());
        }

        try {
            List<Map<String, Object>> top = jdbcTemplate.queryForList(
                    "SELECT building_id AS buildingId, COUNT(*) AS events, "
                            + "SUM(duration_hours) AS hours "
                            + "FROM fact_anomaly_event WHERE start_ts >= ? "
                            + "AND start_ts < DATE_ADD(?, INTERVAL 1 DAY) "
                            + (byBuilding ? "AND building_id = ? " : "")
                            + "GROUP BY building_id ORDER BY hours DESC LIMIT 3",
                    byBuilding ? new Object[]{startDate, endDate, buildingId}
                            : new Object[]{startDate, endDate});
            s.put("topAnomalyBuildings", top);
        } catch (Exception e) {
            s.put("topError", e.getMessage());
        }

        try {
            List<Map<String, Object>> trend = jdbcTemplate.queryForList(
                    "SELECT stat_date AS d, ROUND(SUM(electricity_kwh),1) AS kwh "
                            + "FROM fact_energy_hourly WHERE stat_date BETWEEN ? AND ?" + where
                            + " GROUP BY stat_date ORDER BY stat_date DESC LIMIT 7",
                    args);
            List<Map<String, Object>> ordered = new ArrayList<>(trend);
            java.util.Collections.reverse(ordered);
            s.put("recentDailyKwh", ordered);
        } catch (Exception e) {
            s.put("trendError", e.getMessage());
        }
        return s;
    }

    // ================================================================ 解释

    private String explain(String question, Map<String, Object> snapshot, String page,
                           int width, int height) {
        String q = StringUtils.hasText(question) ? question : "这段时间发生了什么？可能是什么原因？有什么建议？";
        StringBuilder user = new StringBuilder();
        user.append("用户上传了一张截图（来源页面：").append(StringUtils.hasText(page) ? page : "未知")
                .append("，尺寸 ").append(width).append("×").append(height)
                .append("）。当前模型无法识别图片内容，请只依据下面这份真实数据回答。\n\n");
        user.append("【用户问题】").append(q).append("\n\n");
        user.append("【该时段真实数据】\n");
        user.append(toText(snapshot));
        user.append("\n【要求】\n");
        user.append("1) 先用一句话概括这段时间的总体情况（用电、异常）；\n");
        user.append("2) 指出最值得关注的 2~3 个现象，并说明依据的数据；\n");
        user.append("3) 给出可能原因，标注哪些是推测、需要现场核实；\n");
        user.append("4) 给出可执行的运维建议，按优先级排列；\n");
        user.append("5) 不要编造数据，不要声称看到图片内容，不要给出未经数据支持的节能比例。\n");

        String system = """
                你是建筑能源运维分析师。用户截了一张看板图但无法描述细节，
                你只能依据提供的真实数据作答。
                必须区分「数据事实」与「推测原因」，推测要标注需现场核实。
                回答控制在 350 字以内，分点，中文。""";

        if (llmClient != null && llmClient.available()) {
            try {
                String raw = llmClient.chat(system, user.toString(), 0.3, 900);
                if (StringUtils.hasText(raw)) {
                    return raw.trim();
                }
            } catch (Exception e) {
                log.warn("截图分析调用大模型失败：{}", e.getMessage());
            }
        }
        return template(snapshot);
    }

    private String template(Map<String, Object> s) {
        return "① 总体：" + s.get("dateRange") + "，用电 " + s.get("totalElectricityKwh")
                + " kWh，异常 " + s.get("anomalyEvents") + " 起 / " + s.get("anomalyHours") + " 小时。\n"
                + "② 关注：异常最集中的是 " + s.get("topAnomalyBuildings") + "。\n"
                + "③ 原因：需结合现场工况核实（大模型暂不可用，未做进一步归因）。\n"
                + "④ 建议：先核查异常时段设备启停，再评估运行策略调整。";
    }

    @SuppressWarnings("unchecked")
    private String toText(Map<String, Object> s) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : s.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ")
                    .append(String.valueOf(e.getValue())).append("\n");
        }
        return sb.toString();
    }

    private String save(MultipartFile image, String ext) {
        try {
            Path base = Paths.get(uploadDir);
            if (!base.isAbsolute()) {
                base = Paths.get(System.getProperty("user.dir")).resolve(base);
            }
            Files.createDirectories(base);
            String id = UUID.randomUUID().toString().replace("-", "");
            Path target = base.resolve(id + "." + ext);
            image.transferTo(target.toFile());
            return id;
        } catch (Exception e) {
            throw new BizException(ResultCode.IMPORT_FAILED, "截图保存失败：" + e.getMessage());
        }
    }

    private String extOf(String name) {
        if (name == null || !name.contains(".")) {
            return "";
        }
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase();
    }
}

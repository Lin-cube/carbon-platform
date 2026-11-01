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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「截图提问」：用户上传一张看板/图表截图，让 AI 解释这段时间发生了什么。
 *
 * <h3>实现方式</h3>
 * 实测 api.deepseek.com 的 {@code deepseek-chat} 支持图片输入，因此：
 * <ul>
 *   <li>截图直接以 {@code image_url} 交给多模态模型；</li>
 *   <li>同时附上该时段的真实数据快照（含环比、日序列、异常 TOP 事件）；</li>
 *   <li>要求模型<b>先复述截图里看到的数字</b>，再用数据交叉核对，最后解释与建议。</li>
 * </ul>
 *
 * <h3>为什么快照要做厚</h3>
 * 早期版本只给了 11 个总量指标，模型只能泛泛而谈（"用电较高、异常偏多"）。
 * 现在补上：环比、逐日序列、峰谷日、最严重的 3 起异常明细，
 * 模型才能说出"12/29 冲到 155.5 万 kWh"这种具体结论。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageContextChatService {

    private final LlmClient llmClient;
    private final JdbcTemplate jdbcTemplate;
    /** 与文字问答共用会话历史，保证「先传图问、再用文字追问」能接上 */
    private final DeepSeekToolCallingService toolCallingService;

    @Value("${carbon.ai.upload-dir:.ai-uploads}")
    private String uploadDir;

    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final List<String> ALLOWED = List.of("jpg", "jpeg", "png", "webp");
    private static final int MAX_HISTORY_TURNS = 4;
    /** 一次最多几张截图 */
    private static final int MAX_IMAGES = 5;

    /** 会话历史（sessionId → 最近几轮问答），让截图提问也能追问 */
    private final Map<String, List<String>> history = new ConcurrentHashMap<>();

    public Map<String, Object> analyze(MultipartFile[] files, String question, String sessionId,
                                       String buildingId, LocalDate startDate, LocalDate endDate,
                                       String page) {
        if (files == null || files.length == 0) {
            throw new BizException(ResultCode.BAD_REQUEST, "请选择要上传的截图");
        }
        if (files.length > MAX_IMAGES) {
            throw new BizException(ResultCode.BAD_REQUEST, "一次最多上传 " + MAX_IMAGES + " 张截图");
        }

        List<String> imageIds = new ArrayList<>();
        List<String> dataUrls = new ArrayList<>();
        List<String> sizes = new ArrayList<>();

        for (MultipartFile image : files) {
            if (image == null || image.isEmpty()) {
                continue;
            }
            if (image.getSize() > MAX_BYTES) {
                throw new BizException(ResultCode.BAD_REQUEST,
                        "每张截图不能超过 5MB：" + image.getOriginalFilename());
            }
            String ext = extOf(image.getOriginalFilename());
            if (!ALLOWED.contains(ext)) {
                throw new BizException(ResultCode.BAD_REQUEST,
                        "仅支持 jpg / png / webp 截图：" + image.getOriginalFilename());
            }
            // ★ 先把字节读进内存再复用。
            //   MultipartFile.transferTo() 会把临时文件移走，之后再读会失败，
            //   导致多模态退化成纯文本、模型答"看不到截图"。
            byte[] bytes;
            int width;
            int height;
            try {
                bytes = image.getBytes();
                BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                if (img == null) {
                    throw new BizException(ResultCode.BAD_REQUEST,
                            "文件不是有效图片：" + image.getOriginalFilename());
                }
                width = img.getWidth();
                height = img.getHeight();
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException(ResultCode.BAD_REQUEST, "图片读取失败：" + e.getMessage());
            }
            imageIds.add(save(bytes, ext));
            dataUrls.add(toDataUrl(bytes));
            sizes.add(width + "×" + height);
        }

        if (dataUrls.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST, "没有接收到有效图片");
        }

        Map<String, Object> snapshot = buildSnapshot(buildingId, startDate, endDate);
        boolean[] usedVision = {false};
        String answer = explain(question, snapshot, page, sizes, dataUrls, sessionId, usedVision);

        remember(sessionId, question, answer);
        try {
            toolCallingService.rememberExchange(sessionId,
                    StringUtils.hasText(question) ? question : "（上传截图）", answer);
        } catch (Exception e) {
            log.warn("同步截图问答到会话历史失败：{}", e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imageId", imageIds.get(0));
        out.put("imageIds", imageIds);
        out.put("imageCount", imageIds.size());
        out.put("imageAnalyzed", usedVision[0]);
        out.put("notice", usedVision[0]
                ? "已结合 " + imageIds.size() + " 张截图与该时段的真实能耗数据作答；"
                  + "若截图与数据不一致，以数据为准。"
                : "截图已归档，但本次未能调用多模态能力，回答仅基于该时段的真实能耗数据。");
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
        s.put("范围", byBuilding ? buildingId : "全部园区");
        s.put("统计区间", startDate + " ~ " + endDate);

        // ── 总量 ──
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT COUNT(*) AS hours, COUNT(DISTINCT building_id) AS blds, "
                            + "COALESCE(SUM(electricity_kwh),0) AS kwh, "
                            + "COALESCE(SUM(hvac_kwh),0) AS hvac, "
                            + "COALESCE(SUM(carbon_kg),0) AS carbon "
                            + "FROM fact_energy_hourly WHERE stat_date BETWEEN ? AND ?" + where, args);
            s.put("总用电kWh", row.get("kwh"));
            s.put("空调分项kWh", row.get("hvac"));
            s.put("碳排放kg", row.get("carbon"));
            s.put("参评建筑数", row.get("blds"));
        } catch (Exception e) {
            s.put("电量查询失败", e.getMessage());
        }

        // ── 环比 ──
        try {
            long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
            LocalDate prevStart = startDate.minusDays(days);
            LocalDate prevEnd = startDate.minusDays(1);
            Object[] a2 = byBuilding
                    ? new Object[]{prevStart, prevEnd, buildingId}
                    : new Object[]{prevStart, prevEnd};
            Map<String, Object> prev = jdbcTemplate.queryForMap(
                    "SELECT COALESCE(SUM(electricity_kwh),0) AS kwh "
                            + "FROM fact_energy_hourly WHERE stat_date BETWEEN ? AND ?" + where, a2);
            s.put("上期用电kWh", prev.get("kwh"));
            s.put("环比区间说明", "上一区间 " + prevStart + " ~ " + prevEnd);
        } catch (Exception e) {
            s.put("环比查询失败", e.getMessage());
        }

        // ── 逐日序列 + 峰谷 ──
        try {
            List<Map<String, Object>> daily = jdbcTemplate.queryForList(
                    "SELECT stat_date AS d, ROUND(SUM(electricity_kwh),1) AS kwh "
                            + "FROM fact_energy_hourly WHERE stat_date BETWEEN ? AND ?" + where
                            + " GROUP BY stat_date ORDER BY stat_date", args);
            if (daily.size() > 31) {
                daily = daily.subList(daily.size() - 31, daily.size());
            }
            s.put("逐日用电（最多31天）", daily);

            Map<String, Object> peak = null;
            Map<String, Object> trough = null;
            double sum = 0;
            for (Map<String, Object> d : daily) {
                double v = ((Number) d.get("kwh")).doubleValue();
                sum += v;
                if (peak == null || v > ((Number) peak.get("kwh")).doubleValue()) {
                    peak = d;
                }
                if (trough == null || v < ((Number) trough.get("kwh")).doubleValue()) {
                    trough = d;
                }
            }
            s.put("峰值日", peak);
            s.put("谷值日", trough);
            if (!daily.isEmpty()) {
                s.put("日均kWh", Math.round(sum / daily.size() * 10) / 10.0);
            }
        } catch (Exception e) {
            s.put("日序列查询失败", e.getMessage());
        }

        // ── 异常汇总 + TOP3 事件 ──
        try {
            Object[] a3 = byBuilding
                    ? new Object[]{startDate, endDate, buildingId}
                    : new Object[]{startDate, endDate};
            Map<String, Object> an = jdbcTemplate.queryForMap(
                    "SELECT COUNT(*) AS events, COALESCE(SUM(duration_hours),0) AS hours "
                            + "FROM fact_anomaly_event WHERE start_ts >= ? "
                            + "AND start_ts < DATE_ADD(?, INTERVAL 1 DAY)"
                            + (byBuilding ? " AND building_id = ?" : ""), a3);
            s.put("异常事件数", an.get("events"));
            s.put("异常小时数", an.get("hours"));

            s.put("最严重的3起异常", jdbcTemplate.queryForList(
                    "SELECT building_id AS 建筑, "
                            + "DATE_FORMAT(start_ts,'%Y-%m-%d %H:%i') AS 开始时间, "
                            + "duration_hours AS 持续小时, anomaly_type AS 类型, "
                            + "severity AS 严重度, deviation_pct AS 偏离百分比 "
                            + "FROM fact_anomaly_event WHERE start_ts >= ? "
                            + "AND start_ts < DATE_ADD(?, INTERVAL 1 DAY)"
                            + (byBuilding ? " AND building_id = ?" : "")
                            + " ORDER BY duration_hours DESC LIMIT 3", a3));

            s.put("异常时长TOP3建筑", jdbcTemplate.queryForList(
                    "SELECT building_id AS buildingId, COUNT(*) AS events, "
                            + "SUM(duration_hours) AS hours "
                            + "FROM fact_anomaly_event WHERE start_ts >= ? "
                            + "AND start_ts < DATE_ADD(?, INTERVAL 1 DAY) "
                            + (byBuilding ? "AND building_id = ? " : "")
                            + "GROUP BY building_id ORDER BY hours DESC LIMIT 3", a3));
        } catch (Exception e) {
            s.put("异常查询失败", e.getMessage());
        }
        return s;
    }

    // ================================================================ 解释

    private String explain(String question, Map<String, Object> snapshot, String page,
                           List<String> sizes, List<String> dataUrls,
                           String sessionId, boolean[] usedVision) {
        String q = StringUtils.hasText(question) ? question : "这张图反映的情况怎么样？可能是什么原因？该怎么处理？";
        StringBuilder user = new StringBuilder();

        // ConcurrentHashMap 不接受 null 键：未传 sessionId 时直接跳过历史
        List<String> hist = StringUtils.hasText(sessionId)
                ? history.getOrDefault(sessionId, List.of())
                : List.of();
        if (!hist.isEmpty()) {
            user.append("【之前的对话】\n");
            synchronized (hist) {
                for (String h : hist) {
                    user.append(h).append("\n");
                }
            }
            user.append("\n");
        }

        user.append("用户上传了 ").append(dataUrls.size()).append(" 张截图（来自「")
                .append(StringUtils.hasText(page) ? page : "未知").append("」页面，尺寸分别为 ")
                .append(String.join("、", sizes)).append("）。\n")
                .append("请按上传顺序编号为 图1、图2…，逐张说明你看到的内容；")
                .append("如果多张图是同一页面的不同时间或不同指标，请对比它们之间的差异。\n\n");
        user.append("【用户问题】").append(q).append("\n\n");
        user.append("【该时段真实数据】\n").append(toText(snapshot)).append("\n");
        user.append("""
                【回答要求】
                1) 先读图：把截图里能看到的内容逐条写出来。
                   - 是图表：说明图表类型、坐标轴含义、走势、峰值谷值与关键数值；
                   - 是文字/表格/卡片：逐条抄录可见的标题、字段名与数字；
                   - 只有真的什么都看不清时，才说「无法辨认」，不要因为不是图表就说看不清。
                2) 再对账：把截图里的数字与上面的真实数据逐项比对，一致就确认；
                   不一致就明确指出「图上 X，数据 Y」，并说明以哪个为准。
                3) 再解释：基于比对结果说明这段时间发生了什么、可能的原因；
                   推测必须标注「需现场核实」。
                4) 最后建议：给出 3~4 条可执行的运维动作，按优先级排列。
                5) 如果截图与建筑能源无关，直接说明「这张图与能耗数据无关」，
                   只针对图中内容回答，不要硬套能耗结论。
                """);

        String system = """
                你是建筑能源运维分析师，擅长看图说话 + 数据核对。
                先客观描述图中可见信息，再用真实数据交叉验证，最后给结论与建议。
                禁止编造图中没有的数字；禁止把推测说成事实；中文分点作答，350 字以内。""";

        if (llmClient != null && llmClient.available()) {
            try {
                String raw = llmClient.chatWithImages(system, user.toString(), dataUrls, 0.25, 1400);
                if (StringUtils.hasText(raw)) {
                    usedVision[0] = true;
                    return raw.trim();
                }
            } catch (Exception e) {
                log.warn("多模态调用失败，降级纯数据：{}", e.getMessage());
            }
            try {
                String raw = llmClient.chat(system, user.toString(), 0.25, 900);
                if (StringUtils.hasText(raw)) {
                    return raw.trim();
                }
            } catch (Exception e) {
                log.warn("纯文本降级也失败：{}", e.getMessage());
            }
        }
        return template(snapshot);
    }

    private String template(Map<String, Object> s) {
        return "① 图上信息：未能调用多模态模型，未读取截图内容。\n"
                + "② 数据核对：" + s.get("统计区间") + " 用电 " + s.get("总用电kWh")
                + " kWh，异常 " + s.get("异常事件数") + " 起 / " + s.get("异常小时数") + " 小时。\n"
                + "③ 原因：需现场核实（模型暂不可用，未做归因）。\n"
                + "④ 建议：先核查异常时段设备启停与传感器状态。";
    }

    private String toText(Map<String, Object> s) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : s.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ")
                    .append(String.valueOf(e.getValue())).append("\n");
        }
        return sb.toString();
    }

    // ================================================================ 会话历史

    private void remember(String sessionId, String question, String answer) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        List<String> list = history.computeIfAbsent(sessionId, k -> new ArrayList<>());
        synchronized (list) {
            list.add("用户问：" + (question == null ? "" : question));
            list.add("助手答：" + (answer == null ? "" : truncate(answer, 400)));
            while (list.size() > MAX_HISTORY_TURNS * 2) {
                list.remove(0);
            }
        }
        if (history.size() > 500) {
            history.clear();
        }
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ================================================================ 图片处理

    private String toDataUrl(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (img == null) {
                return null;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            int maxW = 1600;
            if (w > maxW) {
                int nw = maxW;
                int nh = Math.max(1, (int) Math.round(h * (maxW / (double) w)));
                BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, 0, 0, nw, nh, null);
                g.dispose();
                img = scaled;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            return "data:image/png;base64,"
                    + java.util.Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            log.warn("图片转 base64 失败：{}", e.getMessage());
            return null;
        }
    }

    private String save(byte[] bytes, String ext) {
        try {
            Path base = Paths.get(uploadDir);
            if (!base.isAbsolute()) {
                base = Paths.get(System.getProperty("user.dir")).resolve(base);
            }
            Files.createDirectories(base);
            String id = UUID.randomUUID().toString().replace("-", "");
            Files.write(base.resolve(id + "." + ext), bytes);
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

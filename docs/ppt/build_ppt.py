#!/usr/bin/env python3
"""生成「碳能智控运维平台」项目简介 PPT。

设计说明：
  - 16:9 版式（13.333 × 7.5 英寸），深色科技风，与系统界面配色一致
  - 全部使用 python-pptx 原生文本框/表格/图表，文字可编辑
  - 配色：深蓝底 0F1B2F / 主色 2F7DFF / 强调青 22D3EE / 成功绿 22C55E
  - 数据均来自真实系统实测值，不使用占位文本

用法：
    <python> docs/ppt/build_ppt.py
"""

from __future__ import annotations

import os
from pathlib import Path

from pptx import Presentation
from pptx.chart.data import CategoryChartData
from pptx.dml.color import RGBColor
from pptx.enum.chart import XL_CHART_TYPE, XL_LEGEND_POSITION
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.util import Inches, Pt

# ---------------------------------------------------------------- 设计常量
BG = RGBColor(0x0F, 0x1B, 0x2F)
BG_CARD = RGBColor(0x16, 0x24, 0x3D)
PRIMARY = RGBColor(0x2F, 0x7D, 0xFF)
CYAN = RGBColor(0x22, 0xD3, 0xEE)
GREEN = RGBColor(0x22, 0xC5, 0x5E)
AMBER = RGBColor(0xF5, 0x9E, 0x0B)
RED = RGBColor(0xEF, 0x44, 0x44)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
TEXT = RGBColor(0xE6, 0xED, 0xF7)
DIM = RGBColor(0x8B, 0xA0, 0xC0)
LINE = RGBColor(0x1E, 0x3A, 0x5F)

FONT = "微软雅黑"
SW, SH = Inches(13.333), Inches(7.5)


# ---------------------------------------------------------------- 基础构件
def solid(shape, color: RGBColor) -> None:
    shape.fill.solid()
    shape.fill.fore_color.rgb = color


def no_line(shape) -> None:
    shape.line.fill.background()


def rect(slide, x, y, w, h, color, line_color=None, line_w=1.0):
    from pptx.enum.shapes import MSO_SHAPE

    s = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, x, y, w, h)
    solid(s, color)
    if line_color is None:
        no_line(s)
    else:
        s.line.color.rgb = line_color
        s.line.width = Pt(line_w)
    s.shadow.inherit = False
    # 圆角尽量小，接近矩形
    try:
        s.adjustments[0] = 0.06
    except Exception:  # noqa: BLE001
        pass
    return s


def textbox(slide, x, y, w, h, text, size=14, color=TEXT, bold=False,
            align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP, line_spacing=1.25):
    tb = slide.shapes.add_textbox(x, y, w, h)
    tf = tb.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = tf.margin_right = Inches(0.04)
    tf.margin_top = tf.margin_bottom = 0

    lines = text.split("\n") if isinstance(text, str) else list(text)
    for i, line in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        p.line_spacing = line_spacing
        run = p.add_run()
        run.text = line
        run.font.size = Pt(size)
        run.font.color.rgb = color
        run.font.bold = bold
        run.font.name = FONT
    return tb


def bullets(slide, x, y, w, h, items, size=13, color=TEXT, gap=8, bullet="·"):
    """项目符号列表（手工加符号，避免依赖母版样式）"""
    tb = slide.shapes.add_textbox(x, y, w, h)
    tf = tb.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_right = 0
    for i, it in enumerate(items):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.line_spacing = 1.2
        p.space_after = Pt(gap)
        run = p.add_run()
        run.text = f"{bullet} {it}" if bullet else it
        run.font.size = Pt(size)
        run.font.color.rgb = color
        run.font.name = FONT
    return tb


def slide_base(prs, title=None, subtitle=None, page=None):
    """新建深色内容页，返回 slide"""
    s = prs.slides.add_slide(prs.slide_layouts[6])   # 空白版式
    bg = rect(s, 0, 0, SW, SH, BG)
    bg.shadow.inherit = False

    if title:
        # 主色竖条 + 标题
        rect(s, Inches(0.55), Inches(0.42), Inches(0.075), Inches(0.42), CYAN)
        textbox(s, Inches(0.78), Inches(0.36), Inches(11.4), Inches(0.55),
                title, size=25, color=WHITE, bold=True)
    if subtitle:
        textbox(s, Inches(0.78), Inches(0.95), Inches(11.6), Inches(0.34),
                subtitle, size=12.5, color=DIM)
    if page is not None:
        textbox(s, Inches(12.1), Inches(6.95), Inches(0.8), Inches(0.3),
                str(page), size=10.5, color=DIM, align=PP_ALIGN.RIGHT)
    return s


def kpi_card(slide, x, y, w, h, label, value, unit="", sub="", accent=PRIMARY):
    rect(slide, x, y, w, h, BG_CARD, LINE, 0.75)
    rect(slide, x, y, Inches(0.05), h, accent)
    textbox(slide, x + Inches(0.18), y + Inches(0.13), w - Inches(0.3), Inches(0.26),
            label, size=10.5, color=DIM)
    tb = slide.shapes.add_textbox(x + Inches(0.18), y + Inches(0.38),
                                  w - Inches(0.3), Inches(0.45))
    tf = tb.text_frame
    tf.word_wrap = True
    p = tf.paragraphs[0]
    r = p.add_run()
    r.text = str(value)
    r.font.size = Pt(21)
    r.font.bold = True
    r.font.color.rgb = WHITE
    r.font.name = FONT
    if unit:
        r2 = p.add_run()
        r2.text = " " + unit
        r2.font.size = Pt(10.5)
        r2.font.color.rgb = DIM
        r2.font.name = FONT
    if sub:
        textbox(slide, x + Inches(0.18), y + h - Inches(0.32), w - Inches(0.3),
                Inches(0.26), sub, size=9.5, color=DIM)


def style_chart_text(chart, size=10.5, color=DIM):
    chart.font.size = Pt(size)
    chart.font.color.rgb = color
    chart.font.name = FONT


# ================================================================ 幻灯片
def slide_cover(prs):
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, SW, SH, BG)
    # 顶部装饰渐变条
    rect(s, 0, 0, SW, Inches(0.09), CYAN)
    rect(s, Inches(4.9), Inches(0.09), Inches(3.5), Inches(0.09), PRIMARY)

    textbox(s, Inches(1.0), Inches(1.45), Inches(11.3), Inches(1.0),
            "碳能智控运维平台", size=52, color=WHITE, bold=True)
    textbox(s, Inches(1.0), Inches(2.5), Inches(11.3), Inches(0.4),
            "Carbon Energy Intelligent Control & O&M Platform", size=14, color=CYAN)

    rect(s, Inches(1.0), Inches(3.1), Inches(4.2), Inches(0.03), LINE)

    textbox(s, Inches(1.0), Inches(3.35), Inches(11.3), Inches(1.2),
            "建筑能源智能管理与运营优化 · AI + 大数据 + 数字孪生\n"
            "「能耗数据构建 — 查询统计 — 大模型智慧运维」一体化解决方案",
            size=16, color=TEXT, line_spacing=1.5)

    # 四个关键指标
    items = [
        ("数据规模", "599 万", "条逐小时记录"),
        ("接入建筑", "456", "栋 / 18 园区"),
        ("后端接口", "30", "个全部 ≤2 秒"),
        ("异常识别召回率", "98.7%", "误报率仅 3.3%"),
    ]
    x = Inches(1.0)
    for label, val, unit in items:
        kpi_card(s, x, Inches(4.85), Inches(2.75), Inches(1.25), label, val, unit)
        x += Inches(2.95)

    textbox(s, Inches(1.0), Inches(6.45), Inches(11.3), Inches(0.7),
            "郑州西亚斯学院 · 工学部科大讯飞人工智能学院　|　"
            "项目导师：席宏昌　|　AI+X Vibe Coding 实验班（二期）真实项目\n"
            "数据源：Building Data Genome Project 2（CC-BY 4.0）",
            size=11, color=DIM, line_spacing=1.4)
    return s


def slide_background(prs, page):
    s = slide_base(prs, "一、背景与真实痛点", "建筑运行能耗占全国碳排放 20% 以上，是「双碳」目标的关键领域", page)

    # 左侧：政策与行业背景
    rect(s, Inches(0.55), Inches(1.5), Inches(5.9), Inches(2.5), BG_CARD, LINE, 0.75)
    textbox(s, Inches(0.8), Inches(1.68), Inches(5.4), Inches(0.3),
            "行业背景", size=13, color=CYAN, bold=True)
    bullets(s, Inches(0.8), Inches(2.05), Inches(5.4), Inches(1.9), [
        "我国提出 2030 年前碳达峰、2060 年前碳中和",
        "建筑运行阶段（采暖/制冷/照明/设备）碳排放占全国 20% 以上",
        "AI 大模型在自然语言交互、数据分析、智能决策上能力快速迭代",
        "赛题合作方：中建八局第二建设有限公司（世界 500 强中国建筑子公司）",
    ], size=11.5)

    # 左侧下：合作方平台
    rect(s, Inches(0.55), Inches(4.15), Inches(5.9), Inches(2.55), BG_CARD, LINE, 0.75)
    textbox(s, Inches(0.8), Inches(4.33), Inches(5.4), Inches(0.3),
            "合作方已有能力", size=13, color=CYAN, bold=True)
    bullets(s, Inches(0.8), Inches(4.7), Inches(5.4), Inches(1.9), [
        "自研「擎翼数字中枢」智慧运维平台",
        "融合 AIoT、大数据、数字孪生技术",
        "已在临沂大学、临朐县人民医院等项目落地",
        "本项目可作为标准化模块嵌入该平台",
    ], size=11.5)

    # 右侧：四大痛点表
    textbox(s, Inches(6.85), Inches(1.5), Inches(5.9), Inches(0.3),
            "四大真实痛点（来自需求方业务场景）", size=13, color=AMBER, bold=True)

    pain = [
        ("① 运维决策缺乏智能赋能", "故障诊断慢、准确率低",
         "专业人员单次响应 5 分钟\n非专业人员需 30 分钟至数天"),
        ("② 知识资产难以传承", "隐性经验无标准化沉淀机制",
         "「传帮带」困境突出"),
        ("③ 智能化分析缺失", "数据停留在静态展示与简单统计",
         "缺乏 AI 深度挖掘能力"),
        ("④ 数据查询与报表低效", "多源查询繁琐、报表生成冗长",
         "数据割裂率超 60%\n导致能效损失 20%-30%"),
    ]
    y = Inches(1.9)
    for title, mid, bottom in pain:
        rect(s, Inches(6.85), y, Inches(5.9), Inches(1.12), BG_CARD, LINE, 0.75)
        rect(s, Inches(6.85), y, Inches(0.05), Inches(1.12), RED)
        textbox(s, Inches(7.05), y + Inches(0.1), Inches(5.5), Inches(0.3),
                title, size=12.5, color=WHITE, bold=True)
        textbox(s, Inches(7.05), y + Inches(0.4), Inches(2.9), Inches(0.6),
                mid, size=10.5, color=DIM, line_spacing=1.2)
        textbox(s, Inches(10.05), y + Inches(0.4), Inches(2.55), Inches(0.6),
                bottom, size=10.5, color=AMBER, line_spacing=1.2)
        y += Inches(1.22)
    return s


def slide_scope(prs, page):
    s = slide_base(prs, "二、项目范围与交付成果",
                   "围绕 3 项核心任务，交付 8 个功能页面 + 30 个后端接口 + 完整数据集", page)

    tasks = [
        ("任务①", "能耗数据导入与治理", CYAN, [
            "CSV / Excel 多源接入，自动识别 UTF-8 / GBK 编码",
            "五步向导：数据源选择 → 字段映射 → 清洗规则 → 预览导入 → 审计留痕",
            "AI 批量规划导入：自动推断列类型并给出字段映射建议",
            "内置清洗：去空格、剔负值、缺失策略、单位换算、去重",
            "审计留痕：记录映射与规则 JSON、成功/失败行数、操作人",
        ]),
        ("任务②", "实时监控与可视化看板", PRIMARY, [
            "3D 园区模型：楼栋按能耗评级/强度/异常率着色，高度映射面积或电量",
            "3D 交互：拖拽旋转、缩放、点击楼栋查看详情、用能粒子流动画",
            "6 类分析图表：趋势、园区对比、类型结构、分时占比、异常趋势、评级分布",
            "多级状态监控：园区 → 建筑 → 表计，含能耗评级 A–E 与异常告警",
        ]),
        ("任务③", "AI 智慧问答与诊断", GREEN, [
            "MCP 协议 7 类数据查询工具（维度/概览/分解/查询/异常/诊断/建议）",
            "「小E」助手：回答附数据证据、工具链、知识库引用、置信度、耗时",
            "异常诊断：输出「异常时段 vs 正常时段」对比证据链与归因置信度",
            "运维建议：5 类规则，每条附量化依据（如供水温度每升 1℃ COP 升 2~4%）",
        ]),
    ]
    x = Inches(0.55)
    for tag, name, accent, items in tasks:
        w = Inches(4.02)
        rect(s, x, Inches(1.5), w, Inches(5.2), BG_CARD, LINE, 0.75)
        rect(s, x, Inches(1.5), w, Inches(0.06), accent)
        textbox(s, x + Inches(0.22), Inches(1.68), w - Inches(0.44), Inches(0.28),
                tag, size=10.5, color=accent, bold=True)
        textbox(s, x + Inches(0.22), Inches(1.96), w - Inches(0.44), Inches(0.4),
                name, size=15, color=WHITE, bold=True)
        rect(s, x + Inches(0.22), Inches(2.48), w - Inches(0.44), Inches(0.02), LINE)
        bullets(s, x + Inches(0.22), Inches(2.62), w - Inches(0.44), Inches(3.9),
                items, size=10.5, gap=7)
        x += Inches(4.19)

    textbox(s, Inches(0.55), Inches(6.8), Inches(12.2), Inches(0.45),
            "交付物：可运行 Web 系统　|　完整数据集（599 万条 + 数据字典）　|　"
            "需求分析文档　|　技术方案文档　|　用户使用手册　|　测试记录与评测报告",
            size=11, color=CYAN)
    return s


def slide_data(prs, page):
    s = slide_base(prs, "三、数据底座：从开源数据到可用数据集",
                   "BDG2 开源数据集不是「成品」，需经 7 步管道转换才能满足业务字段要求", page)

    # 左：管道流程
    rect(s, Inches(0.55), Inches(1.5), Inches(6.5), Inches(5.2), BG_CARD, LINE, 0.75)
    textbox(s, Inches(0.8), Inches(1.68), Inches(6.0), Inches(0.3),
            "数据处理管道（Python，7 个脚本）", size=13, color=CYAN, bold=True)

    steps = [
        "① 逐表计覆盖率测绘 → 选表配置",
        "② 宽表 → 长表展开（直接求和，不做差分）",
        "③ 计量口径可信度校验（覆盖率/读数有效性/强度上限）",
        "④ 建筑级聚合 + 站点剩余量按面积分摊",
        "⑤ 单位统一（kBtu → kWh ×0.29307107）",
        "⑥ 推算补全（露点→湿度、供回水温度、人员密度、COP）",
        "⑦ 分层基线-偏差异常标注",
    ]
    y = Inches(2.08)
    for i, st in enumerate(steps):
        rect(s, Inches(0.8), y, Inches(0.26), Inches(0.26), PRIMARY if i % 2 == 0 else CYAN)
        textbox(s, Inches(1.18), y - Inches(0.015), Inches(5.7), Inches(0.3),
                st, size=11, color=TEXT)
        y += Inches(0.42)

    rect(s, Inches(0.8), Inches(5.18), Inches(6.0), Inches(1.35), BG, LINE, 0.75)
    textbox(s, Inches(1.0), Inches(5.3), Inches(5.6), Inches(1.15),
            "关键判断：BDG2 表计数值是【每小时区间消耗量】，不是累计读数。\n"
            "证据：某表计序列 44.05 → 44.93 → 43.83，单调递增比例仅 0.484。\n"
            "结论：统计直接 SUM，禁止 diff —— 判断错误会导致全部统计失真。",
            size=10, color=AMBER, line_spacing=1.3)

    # 右：实测结果
    textbox(s, Inches(7.3), Inches(1.5), Inches(5.45), Inches(0.3),
            "数据集实测结果", size=13, color=GREEN, bold=True)

    kpi_card(s, Inches(7.3), Inches(1.9), Inches(2.62), Inches(1.15),
             "总记录数", "5,991,840", "条", "赛题要求 ≥1,000 条，为 5992 倍", GREEN)
    kpi_card(s, Inches(10.13), Inches(1.9), Inches(2.62), Inches(1.15),
             "字段数", "45", "个", "含来源三态标注", PRIMARY)
    kpi_card(s, Inches(7.3), Inches(3.15), Inches(2.62), Inches(1.15),
             "建筑 / 园区", "456 / 18", "", "A 级独立计量 431 栋", CYAN)
    kpi_card(s, Inches(10.13), Inches(3.15), Inches(2.62), Inches(1.15),
             "时间范围", "13,140", "小时", "2016-01 ~ 2017-07", AMBER)

    rect(s, Inches(7.3), Inches(4.45), Inches(5.45), Inches(2.25), BG_CARD, LINE, 0.75)
    textbox(s, Inches(7.52), Inches(4.58), Inches(5.0), Inches(0.3),
            "数据来源三态标注（合规关键）", size=12, color=CYAN, bold=True)
    triad = [
        ("实测", GREEN, "电力/空调/水耗、室外温度、建筑面积"),
        ("推算", AMBER, "湿度（露点→Magnus 公式）、碳排放、空调电耗"),
        ("仿真", PRIMARY, "供回水温度、COP、人员密度（非实测）"),
        ("标注", RGBColor(0xEC, 0x48, 0x99), "异常标志、设备状态、严重度"),
    ]
    y = Inches(4.95)
    for name, color, desc in triad:
        rect(s, Inches(7.52), y, Inches(0.62), Inches(0.3), color)
        textbox(s, Inches(7.52), y + Inches(0.03), Inches(0.62), Inches(0.24),
                name, size=9.5, color=BG, bold=True, align=PP_ALIGN.CENTER)
        textbox(s, Inches(8.28), y + Inches(0.03), Inches(4.3), Inches(0.3),
                desc, size=10, color=TEXT)
        y += Inches(0.4)
    return s


def slide_algorithm(prs, page):
    s = slide_base(prs, "四、核心算法：分层基线 - 偏差诊断",
                   "针对建筑能耗「强周期性 + 右尾重」特点设计，避免固定阈值的高误报", page)

    # 左：算法原理
    rect(s, Inches(0.55), Inches(1.5), Inches(6.4), Inches(3.0), BG_CARD, LINE, 0.75)
    textbox(s, Inches(0.78), Inches(1.66), Inches(5.9), Inches(0.3),
            "算法四步", size=13, color=CYAN, bold=True)
    algo = [
        ("① 分层基线", "按「星期几 × 小时」分 168 个槽位，取前 52 周同槽位历史中位数"),
        ("② 分位数阈值", "取同槽位历史残差的 0.99 分位数作判定上限（误报率由分位数直接控制）"),
        ("③ 显著性下限", "要求被标记读数同时超过该建筑自身 P75（抑制近零负荷建筑误报）"),
        ("④ 双重判定", "模型偏差 + 物理规则约束；能耗异常与数据质量缺陷分开统计"),
    ]
    y = Inches(2.04)
    for name, desc in algo:
        textbox(s, Inches(0.78), y, Inches(1.5), Inches(0.3), name, size=11, color=WHITE, bold=True)
        textbox(s, Inches(2.3), y, Inches(4.4), Inches(0.55), desc, size=10, color=DIM,
                line_spacing=1.2)
        y += Inches(0.6)

    # 左下：为什么不能用固定阈值
    rect(s, Inches(0.55), Inches(4.65), Inches(6.4), Inches(2.05), BG_CARD, LINE, 0.75)
    textbox(s, Inches(0.78), Inches(4.8), Inches(5.9), Inches(0.3),
            "为什么不能用固定 3σ 阈值", size=12, color=AMBER, bold=True)
    bullets(s, Inches(0.78), Inches(5.15), Inches(5.9), Inches(1.45), [
        "建筑电耗近似对数正态，右尾很重，MAD 估计的 σ 远小于真实尾部宽度",
        "实测：固定 3σ 在【完全正常】的数据上就有 12.2% 的点超阈值",
        "实际超限率随历史窗口变化：2 周 34.8% → 52 周 6.7%（理论 1%）",
        "→ 建筑能耗具强季节性（非平稳），历史窗口必须足够长",
    ], size=10, gap=4)

    # 右：评测结果
    textbox(s, Inches(7.2), Inches(1.5), Inches(5.55), Inches(0.3),
            "评测结果（注入式评测，固定随机种子可复现）", size=13, color=GREEN, bold=True)

    kpi_card(s, Inches(7.2), Inches(1.9), Inches(2.68), Inches(1.3),
             "能耗异常召回率", "98.68", "%", "目标 ≥90%　✅ 达标", GREEN)
    kpi_card(s, Inches(10.07), Inches(1.9), Inches(2.68), Inches(1.3),
             "能耗异常误报率", "3.29", "%", "目标 ≤10%　✅ 达标", GREEN)
    kpi_card(s, Inches(7.2), Inches(3.35), Inches(2.68), Inches(1.3),
             "数据质量缺陷召回", "97.67", "%", "表计卡死/数据缺失", CYAN)
    kpi_card(s, Inches(10.07), Inches(3.35), Inches(2.68), Inches(1.3),
             "数据质量缺陷误报", "1.37", "%", "与能耗异常分开统计", CYAN)

    rect(s, Inches(7.2), Inches(4.8), Inches(5.55), Inches(1.9), BG_CARD, LINE, 0.75)
    textbox(s, Inches(7.42), Inches(4.93), Inches(5.1), Inches(0.3),
            "关键设计：两类判定严格分离", size=12, color=AMBER, bold=True)
    bullets(s, Inches(7.42), Inches(5.28), Inches(5.1), Inches(1.3), [
        "能耗异常 = 真实用能偏离基线（用于召回/误报评估）",
        "数据质量缺陷 = 采集侧问题（表计卡死、数据缺失）",
        "若混为一谈：误报率从 3.29% 虚高到 16.9%，指标失真",
    ], size=10, gap=4)
    return s


def slide_architecture(prs, page):
    s = slide_base(prs, "五、系统架构", "前后端分离 + 预聚合表支撑毫秒级聚合查询", page)

    # 分层架构
    layers = [
        ("展现层", "Vue 3.5 + TypeScript + Vite 6 + Element Plus + ECharts + Three.js",
         "8 个页面　|　路由懒加载 + 三方库分包（three / echarts / element 独立 chunk）", CYAN),
        ("应用层", "Spring Boot 3.5 + JDK 21（内嵌 Tomcat，30 个接口）",
         "common 公共 / system 认证 / energy 查询 / analytics 统计 / import 导入 / ai 助手", PRIMARY),
        ("数据层", "MySQL 8.4（17 张表，含 4 张预聚合表）",
         "事实表按月分区　|　索引：建筑+时间 / 异常覆盖索引 / 站点+时间", GREEN),
        ("管道层", "Python 3.14 + pandas + pyarrow（7 个脚本，独立于运行时）",
         "宽表→长表 → 计量校验 → 推算补全 → 异常标注 → 多格式导出 → 入库", AMBER),
    ]
    y = Inches(1.5)
    for name, tech, detail, accent in layers:
        rect(s, Inches(0.55), y, Inches(12.23), Inches(1.12), BG_CARD, LINE, 0.75)
        rect(s, Inches(0.55), y, Inches(0.06), Inches(1.12), accent)
        textbox(s, Inches(0.78), y + Inches(0.12), Inches(1.3), Inches(0.3),
                name, size=13, color=accent, bold=True)
        textbox(s, Inches(2.1), y + Inches(0.1), Inches(10.4), Inches(0.3),
                tech, size=12, color=WHITE)
        textbox(s, Inches(2.1), y + Inches(0.48), Inches(10.4), Inches(0.5),
                detail, size=10.5, color=DIM, line_spacing=1.2)
        y += Inches(1.22)

    # 底部：性能
    rect(s, Inches(0.55), Inches(6.42), Inches(12.23), Inches(0.72), BG_CARD, LINE, 0.75)
    textbox(s, Inches(0.78), Inches(6.5), Inches(11.8), Inches(0.6),
            "性能实测：30 / 30 个接口 P95 ≤ 2 秒，最慢 689 ms，全部接口 P95 中位数 34 ms　|　"
            "核心手段：4 张预聚合表把跨百万行聚合查询从秒级降到毫秒级（最慢接口 4.6s → 0.15s）",
            size=11.5, color=GREEN)
    return s


def slide_charts(prs, page):
    """架构性能对比图 + 关键指标"""
    s = slide_base(prs, "六、性能优化效果", "压测发现 4 处超标，通过预聚合表全部修复", page)

    # 左：优化前后对比柱状图
    data = CategoryChartData()
    data.categories = ["时段汇总\n按日", "AI 问答\n统计意图", "异常分析", "按类型分组"]
    data.add_series("优化前 (秒)", (4.612, 3.842, 2.263, 3.458))
    data.add_series("优化后 (秒)", (0.149, 0.100, 0.224, 0.041))

    gf = s.shapes.add_chart(XL_CHART_TYPE.COLUMN_CLUSTERED,
                            Inches(0.55), Inches(1.5), Inches(7.3), Inches(5.1), data)
    ch = gf.chart
    ch.has_title = True
    ch.chart_title.text_frame.text = "接口优化前后响应时间对比（秒）"
    ch.chart_title.text_frame.paragraphs[0].runs[0].font.size = Pt(13)
    ch.chart_title.text_frame.paragraphs[0].runs[0].font.color.rgb = WHITE
    ch.chart_title.text_frame.paragraphs[0].runs[0].font.name = FONT
    style_chart_text(ch)
    ch.has_legend = True
    ch.legend.position = XL_LEGEND_POSITION.BOTTOM
    ch.legend.include_in_layout = False
    ch.plots[0].has_data_labels = True
    ch.plots[0].data_labels.number_format = '0.000'
    ch.plots[0].data_labels.number_format_is_linked = False
    ch.plots[0].data_labels.font.size = Pt(9)
    ch.series[0].format.fill.solid()
    ch.series[0].format.fill.fore_color.rgb = RED
    ch.series[1].format.fill.solid()
    ch.series[1].format.fill.fore_color.rgb = GREEN
    # 2 秒验收线
    ch.value_axis.has_major_gridlines = True

    # 右：优化措施表
    textbox(s, Inches(8.1), Inches(1.5), Inches(4.68), Inches(0.3),
            "优化措施", size=13, color=CYAN, bold=True)

    rows = [
        ("预聚合表", "粒度", "行数", "服务的查询"),
        ("agg_building_daily", "建筑×日", "249,888", "日趋势、时段桶"),
        ("agg_building_monthly", "建筑×月", "8,664", "月度趋势、类型分解"),
        ("agg_anomaly_daily", "建筑×日", "249,888", "异常分析汇总"),
        ("agg_time_hourly", "日期×小时", "13,140", "峰谷/分时/小时"),
    ]
    y = Inches(1.9)
    for i, r in enumerate(rows):
        head = i == 0
        rect(s, Inches(8.1), y, Inches(4.68), Inches(0.44),
             PRIMARY if head else BG_CARD, LINE if not head else None, 0.75)
        widths = [Inches(1.76), Inches(0.72), Inches(0.78), Inches(1.42)]
        x = Inches(8.1)
        for cell, w in zip(r, widths):
            textbox(s, x + Inches(0.06), y + Inches(0.09), w - Inches(0.1), Inches(0.3),
                    cell, size=9 if not head else 9,
                    color=WHITE if head else TEXT, bold=head)
            x += w
        y += Inches(0.48)

    rect(s, Inches(8.1), Inches(4.42), Inches(4.68), Inches(2.18), BG_CARD, LINE, 0.75)
    textbox(s, Inches(8.3), Inches(4.55), Inches(4.3), Inches(0.3),
            "核心经验", size=12, color=AMBER, bold=True)
    bullets(s, Inches(8.3), Inches(4.9), Inches(4.3), Inches(1.6), [
        "跨大量行的聚合查询无法靠索引解决（索引加速定位，无法避免逐行聚合）",
        "必须走预聚合表；规范已写入建表脚本注释",
        "预聚合表不含建筑/园区维度时，需自动回退事实表",
        "注意：分区裁剪要求 WHERE 直接写分区列，写表达式不生效",
    ], size=9.5, gap=4)
    return s


def slide_metrics(prs, page):
    s = slide_base(prs, "七、验收指标达成情况", "四项申报表硬指标，三项已达标，一项待 AI 模块接入", page)

    metrics = [
        ("核心页面平均响应时间", "≤ 2 秒", "689 ms", "30/30 接口 P95 全部通过", "达标", GREEN),
        ("异常识别召回率", "≥ 90%", "98.68%", "注入式评测，1440 个尖峰样本", "达标", GREEN),
        ("异常识别误报率", "≤ 10%", "3.29%", "同一评测，77.9 万正常样本", "达标", GREEN),
        ("AI 问答可用准确率", "≥ 85%", "100%", "数据/统计类 16/16；知识类待 RAGFlow", "部分达标", AMBER),
    ]
    y = Inches(1.45)
    for name, target, actual, note, status, color in metrics:
        rect(s, Inches(0.55), y, Inches(12.23), Inches(1.18), BG_CARD, LINE, 0.75)
        rect(s, Inches(0.55), y, Inches(0.06), Inches(1.18), color)
        textbox(s, Inches(0.82), y + Inches(0.2), Inches(3.5), Inches(0.35),
                name, size=14, color=WHITE, bold=True)
        textbox(s, Inches(0.82), y + Inches(0.62), Inches(3.5), Inches(0.35),
                note, size=10, color=DIM)
        textbox(s, Inches(4.6), y + Inches(0.36), Inches(1.3), Inches(0.35),
                "目标 " + target, size=11, color=DIM)
        textbox(s, Inches(6.2), y + Inches(0.24), Inches(2.6), Inches(0.5),
                actual, size=22, color=color, bold=True)
        rect(s, Inches(9.6), y + Inches(0.36), Inches(1.55), Inches(0.44), color)
        textbox(s, Inches(9.6), y + Inches(0.44), Inches(1.55), Inches(0.3),
                status, size=11, color=BG, bold=True, align=PP_ALIGN.CENTER)
        y += Inches(1.28)

    rect(s, Inches(0.55), Inches(6.58), Inches(12.23), Inches(0.62), BG_CARD, LINE, 0.75)
    textbox(s, Inches(0.8), Inches(6.68), Inches(11.8), Inches(0.45),
            "说明：AI 问答准确率当前验证的是「Mock 模式下问答链路与数据准确性」"
            "（后端真实统计驱动，非假数据）；接入 RagFlow 知识库后，用同一 24 题测试集复测即得最终准确率。",
            size=10.5, color=DIM)
    return s


def slide_innovation(prs, page):
    s = slide_base(prs, "八、创新点与工程价值", "五个可迁移到其他项目的方法论沉淀", page)

    items = [
        ("01", "数据来源三态标注体系", CYAN,
         "严格区分实测 / 推算 / 仿真字段，并贯穿数据字典、接口返回、前端徽章三处。"
         "避免把仿真的供回水温度与 COP 当作实测值使用，满足企业对「风险边界说明」的要求。"),
        ("02", "分层基线 + 分位数阈值异常检测", GREEN,
         "用「星期几×小时」分层消除周期成分，用残差分位数替代固定 σ 倍数控制误报，"
         "并引入显著性下限抑制近零负荷建筑的系统性误报（该类异常率 12.98% → 5.88%）。"),
        ("03", "能耗异常与数据质量缺陷分离统计", PRIMARY,
         "把「表计卡死/数据缺失」从「能耗异常」中剥离。若混在一起，"
         "误报率会从 3.29% 虚高到 16.9%，导致验收指标失真。"),
        ("04", "四张预聚合表支撑 2 秒响应", AMBER,
         "按四个粒度预聚合，把跨百万行聚合查询从秒级降到毫秒级，"
         "并明确「看板查预聚合表、明细查事实表」的查询规范。"),
        ("05", "Mock 优先解耦 AI 模块", RGBColor(0xEC, 0x48, 0x99),
         "后端抽象 AI 服务接口，Mock 实现由真实统计能力驱动（非假数据），"
         "使前端与问答链路在 AI 队友服务就绪前即可联调，切换仅需改一行配置。"),
    ]
    y = Inches(1.42)
    for tag, name, color, desc in items:
        rect(s, Inches(0.55), y, Inches(12.23), Inches(1.04), BG_CARD, LINE, 0.75)
        rect(s, Inches(0.55), y, Inches(0.72), Inches(1.04), color)
        textbox(s, Inches(0.55), y + Inches(0.33), Inches(0.72), Inches(0.4),
                tag, size=17, color=BG, bold=True, align=PP_ALIGN.CENTER)
        textbox(s, Inches(1.48), y + Inches(0.11), Inches(11.1), Inches(0.3),
                name, size=13, color=WHITE, bold=True)
        textbox(s, Inches(1.48), y + Inches(0.46), Inches(11.1), Inches(0.5),
                desc, size=10, color=DIM, line_spacing=1.2)
        y += Inches(1.14)
    return s


def slide_risk(prs, page):
    s = slide_base(prs, "九、风险边界与人工复核", "明确 AI 能力边界，所有输出均可追溯、需人工确认", page)

    # 左：风险与复核
    rect(s, Inches(0.55), Inches(1.5), Inches(6.5), Inches(2.6), BG_CARD, LINE, 0.75)
    textbox(s, Inches(0.78), Inches(1.66), Inches(6.0), Inches(0.3),
            "专业风险与人工复核措施", size=13, color=AMBER, bold=True)
    risks = [
        ("AI 诊断与建议可能有偏差", "所有诊断/建议响应带「需人工复核」标记与免责声明"),
        ("AI 字段映射可能不准确", "五步向导强制「预览导入」，用户确认后才落库"),
        ("异常算法可能误报", "模型偏差 + 规则约束双重判定；异常事件表可登记处理结论"),
        ("AI 报表初稿需确认", "导出为原始统计数据，AI 解读文字单独标注"),
    ]
    y = Inches(2.06)
    for risk, action in risks:
        textbox(s, Inches(0.78), y, Inches(2.75), Inches(0.4), risk, size=10.5, color=RED)
        textbox(s, Inches(3.6), y, Inches(3.2), Inches(0.5), action, size=10, color=DIM,
                line_spacing=1.2)
        y += Inches(0.5)

    # 左下：合规边界
    rect(s, Inches(0.55), Inches(4.25), Inches(6.5), Inches(2.45), BG_CARD, LINE, 0.75)
    textbox(s, Inches(0.78), Inches(4.4), Inches(6.0), Inches(0.3),
            "数据合规边界", size=13, color=CYAN, bold=True)
    bullets(s, Inches(0.78), Inches(4.78), Inches(6.0), Inches(1.8), [
        "数据源：BDG2 开源学术数据集，CC-BY 4.0 许可，可自由使用并署名",
        "数据集本身已脱敏（建筑名称均为化名），不含真实地址",
        "不得采集真实建筑能耗原始数据；不接入企业生产环境数据库",
        "不得输出未脱敏的真实能耗数据；不展示企业内部业务流程细节",
    ], size=10, gap=6)

    # 右：已知限制
    rect(s, Inches(7.2), Inches(1.5), Inches(5.58), Inches(5.2), BG_CARD, LINE, 0.75)
    textbox(s, Inches(7.42), Inches(1.66), Inches(5.1), Inches(0.3),
            "已知数据限制（如实说明）", size=13, color=GREEN, bold=True)

    limits = [
        ("cop 缺失率 95.41%", "18 个园区中仅 5 个有可用冷冻水表计，其余经校验判定量级失真已剔除"),
        ("hvac_kwh 缺失 43.19%", "取决于园区是否有可用的冷冻水/蒸汽/热水表计"),
        ("water_m3 缺失 80.27%", "BDG2 中水表覆盖本身较少"),
        ("B 级建筑 25 栋为估算值", "电耗按面积分摊站点剩余量，anomaly_flag = -1（未评估）"),
        ("供回水温度/COP/人员密度为仿真值", "BDG2 无相应实测数据，按物理模型生成"),
        ("异常评测为注入式仿真", "与真实用能异常形态存在差异，已在评测报告中说明"),
    ]
    y = Inches(2.06)
    for name, desc in limits:
        textbox(s, Inches(7.42), y, Inches(5.1), Inches(0.28), "· " + name,
                size=11, color=AMBER, bold=True)
        textbox(s, Inches(7.6), y + Inches(0.3), Inches(4.95), Inches(0.5),
                desc, size=9.5, color=DIM, line_spacing=1.2)
        y += Inches(0.76)
    return s


def slide_demo(prs, page):
    s = slide_base(prs, "十、系统演示与快速验证", "一键启动，三分钟走完核心链路", page)

    # 左：启动命令
    rect(s, Inches(0.55), Inches(1.5), Inches(6.5), Inches(2.35), BG_CARD, LINE, 0.75)
    textbox(s, Inches(0.78), Inches(1.66), Inches(6.0), Inches(0.3),
            "一键启动", size=13, color=CYAN, bold=True)
    cmds = [
        "./scripts/start-all.sh          # 启动 MySQL + 后端 + 前端",
        "./scripts/start-all.sh status   # 查看服务状态",
        "./scripts/start-all.sh stop     # 全部停止",
        "",
        "# 复现评测结果",
        ".venv/bin/python scripts/load_test.py          # 性能压测（30 接口）",
        ".venv/bin/python scripts/build_qa_testset.py   # AI 问答测试集（24 题）",
    ]
    textbox(s, Inches(0.78), Inches(2.02), Inches(6.0), Inches(1.7),
            "\n".join(cmds), size=9.5, color=GREEN, line_spacing=1.5)

    # 左下：访问信息
    rect(s, Inches(0.55), Inches(4.0), Inches(6.5), Inches(2.7), BG_CARD, LINE, 0.75)
    textbox(s, Inches(0.78), Inches(4.16), Inches(6.0), Inches(0.3),
            "访问地址与演示账号", size=13, color=CYAN, bold=True)
    info = [
        ("系统界面", "http://127.0.0.1:5173"),
        ("接口文档", "http://127.0.0.1:8080/doc.html"),
        ("管理员", "admin / admin123（全部权限）"),
        ("运维人员", "operator / operator123"),
        ("只读用户", "viewer / viewer123"),
    ]
    y = Inches(4.54)
    for k, v in info:
        textbox(s, Inches(0.78), y, Inches(1.4), Inches(0.3), k, size=10.5, color=DIM)
        textbox(s, Inches(2.25), y, Inches(4.6), Inches(0.3), v, size=10.5, color=TEXT)
        y += Inches(0.4)

    # 右：演示脚本
    rect(s, Inches(7.2), Inches(1.5), Inches(5.58), Inches(5.2), BG_CARD, LINE, 0.75)
    textbox(s, Inches(7.42), Inches(1.66), Inches(5.1), Inches(0.3),
            "5 分钟演示脚本", size=13, color=GREEN, bold=True)
    script = [
        ("1", "登录", "用 admin 登录，注意演示账号一键填充"),
        ("2", "监控看板", "6 张 KPI 卡 + 6 类图表；看能耗趋势与室外温度的相关性"),
        ("3", "3D 园区", "切换着色模式（评级/强度/异常率）、高度模式；点击楼栋看详情"),
        ("4", "能耗查询", "选园区+时间，日粒度多建筑对比；试小时粒度触发单建筑约束提示"),
        ("5", "统计分析", "三个 Tab：时段汇总看峰谷；COP 看温度衰减；异常分析看两类分离"),
        ("6", "异常诊断", "按类型筛选事件 → 点诊断看归因证据链 → 点优化建议看量化依据"),
        ("7", "小E 助手", "问「Hog_office_Sydney 为什么能耗偏高」，看工具链与证据"),
        ("8", "数据导入", "上传 CSV → 看自动映射建议 → 预览 → 导入 → 查审计留痕"),
    ]
    y = Inches(2.06)
    for no, name, desc in script:
        rect(s, Inches(7.42), y, Inches(0.34), Inches(0.34), PRIMARY)
        textbox(s, Inches(7.42), y + Inches(0.05), Inches(0.34), Inches(0.26),
                no, size=10, color=WHITE, bold=True, align=PP_ALIGN.CENTER)
        textbox(s, Inches(7.88), y + Inches(0.02), Inches(1.5), Inches(0.3),
                name, size=11, color=WHITE, bold=True)
        textbox(s, Inches(9.35), y + Inches(0.03), Inches(3.3), Inches(0.5),
                desc, size=9.5, color=DIM, line_spacing=1.15)
        y += Inches(0.57)
    return s


def slide_team(prs, page):
    s = slide_base(prs, "十一、团队分工与协作机制", "全栈开发与 AI/算法双线并行，以书面接口契约解耦", page)

    # 分工
    rect(s, Inches(0.55), Inches(1.5), Inches(6.0), Inches(3.3), BG_CARD, LINE, 0.75)
    rect(s, Inches(0.55), Inches(1.5), Inches(6.0), Inches(0.06), PRIMARY)
    textbox(s, Inches(0.8), Inches(1.68), Inches(5.5), Inches(0.35),
            "角色 A · 全栈开发", size=15, color=WHITE, bold=True)
    bullets(s, Inches(0.8), Inches(2.12), Inches(5.5), Inches(2.5), [
        "技术栈：Vue3 + TypeScript + Spring Boot 3 + MySQL",
        "统一能耗数据集构建与治理",
        "查询统计模块（3 类核心统计 + 报表导出）",
        "监控看板与 3D 园区模型",
        "数据导入五步向导",
        "系统集成、RBAC 权限与审计留痕",
    ], size=10.5, gap=7)

    rect(s, Inches(6.78), Inches(1.5), Inches(6.0), Inches(3.3), BG_CARD, LINE, 0.75)
    rect(s, Inches(6.78), Inches(1.5), Inches(6.0), Inches(0.06), GREEN)
    textbox(s, Inches(7.03), Inches(1.68), Inches(5.5), Inches(0.35),
            "角色 B · AI + 算法 / RAG", size=15, color=WHITE, bold=True)
    bullets(s, Inches(7.03), Inches(2.12), Inches(5.5), Inches(2.5), [
        "技术栈：Python + FastAPI + RagFlow + 轻量 LLM",
        "MCP 7 类工具的协议适配层",
        "建筑能源知识库构建（运维手册、规范）",
        "「小E」AI 助手问答编排",
        "诊断算法可选用机器学习模型替换",
    ], size=10.5, gap=7)

    # 解耦机制
    rect(s, Inches(0.55), Inches(5.0), Inches(12.23), Inches(1.7), BG_CARD, LINE, 0.75)
    textbox(s, Inches(0.8), Inches(5.14), Inches(11.7), Inches(0.3),
            "解耦机制：三项措施保证双线并行不阻塞", size=13, color=CYAN, bold=True)

    mech = [
        ("书面接口契约", "明确 7 类 MCP 工具入参出参、AI 服务 5 个 HTTP 端点"),
        ("数据查询已就绪", "后端已实现 7 类工具的数据查询，角色 B 只需做协议适配层"),
        ("Mock 优先", "carbon.ai.mode=mock|http 双实现，切换改一行配置，前端与测试集零改动"),
    ]
    y = Inches(5.52)
    for name, desc in mech:
        rect(s, Inches(0.8), y, Inches(0.24), Inches(0.24), CYAN)
        textbox(s, Inches(1.18), y - Inches(0.02), Inches(2.0), Inches(0.28),
                name, size=11, color=WHITE, bold=True)
        textbox(s, Inches(3.3), y - Inches(0.02), Inches(9.2), Inches(0.3),
                desc, size=10, color=DIM)
        y += Inches(0.36)
    return s


def slide_thanks(prs):
    s = prs.slides.add_slide(prs.slide_layouts[6])
    rect(s, 0, 0, SW, SH, BG)
    rect(s, 0, 0, SW, Inches(0.09), CYAN)

    textbox(s, Inches(1.0), Inches(2.1), Inches(11.3), Inches(1.0),
            "感谢聆听", size=46, color=WHITE, bold=True, align=PP_ALIGN.CENTER)
    textbox(s, Inches(1.0), Inches(3.2), Inches(11.3), Inches(0.5),
            "碳能智控运维平台 · 建筑能源智能管理与运营优化",
            size=17, color=CYAN, align=PP_ALIGN.CENTER)

    rect(s, Inches(4.6), Inches(3.95), Inches(4.1), Inches(0.03), LINE)

    textbox(s, Inches(1.0), Inches(4.2), Inches(11.3), Inches(1.4),
            "数据规模 599 万条　|　30 个接口全部 ≤2 秒　|　异常识别召回 98.68% / 误报 3.29%\n"
            "郑州西亚斯学院 工学部科大讯飞人工智能学院　|　项目导师：席宏昌\n"
            "数据源：Building Data Genome Project 2（CC-BY 4.0）",
            size=12.5, color=DIM, align=PP_ALIGN.CENTER, line_spacing=1.7)
    return s


def main() -> int:
    prs = Presentation()
    prs.slide_width = SW
    prs.slide_height = SH

    slide_cover(prs)
    slide_background(prs, 2)
    slide_scope(prs, 3)
    slide_data(prs, 4)
    slide_algorithm(prs, 5)
    slide_architecture(prs, 6)
    slide_charts(prs, 7)
    slide_metrics(prs, 8)
    slide_innovation(prs, 9)
    slide_risk(prs, 10)
    slide_demo(prs, 11)
    slide_team(prs, 12)
    slide_thanks(prs)

    out = Path(__file__).resolve().parent / "碳能智控运维平台-项目简介.pptx"
    prs.save(str(out))
    print(f"[写出] {out}  ({len(prs.slides.__iter__.__self__._sldIdLst)} 张幻灯片, "
          f"{out.stat().st_size/1024:.0f} KB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
gen_ppt.py — 靠 Prompt 不如让 AI 直接读懂你的代码库
布局：2×2 卡片网格（四条并列局限）+ 底部结论条
"""
import sys, os

SKILL_DIR = r"D:\work\projects\java\latest\agent-core-java\examples\deepagent-example\dynamic_code\skills\ppt_generator"
sys.path.insert(0, SKILL_DIR)

from ppt_kit import (
    new_presentation, add_header, add_divider, add_rect, add_text,
    save,
    COLOR_PRIMARY, COLOR_ACCENT, COLOR_WARN, COLOR_TEXT, COLOR_LIGHT,
    COLOR_BG, COLOR_WHITE, COLOR_DIVIDER, FONT_CN,
)
from pptx.util import Inches, Pt, Emu
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from pptx.dml.color import RGBColor

# ---- 自定义第四种强调色（紫） ----
COLOR_PURPLE = RGBColor(0x7C, 0x3A, 0xED)


def add_detail_card(slide, left, top, width, height,
                    title, claim, limit, details, accent_c):
    """带标题条的卡片 + 结构化内容（核心论点 / 局限 / 细节）。"""
    # 卡片背景（白底 + 浅边框）
    add_rect(slide, left, top, width, height, COLOR_WHITE, COLOR_DIVIDER)

    # 标题条（强调色底 + 白字居中）
    title_h = Inches(0.40)
    add_rect(slide, left, top, width, title_h, accent_c)
    add_text(slide, left, top, width, title_h, title,
             size=13, bold=True, color=COLOR_WHITE,
             align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)

    # 内容区
    body_left = left + Inches(0.15)
    body_top  = top + title_h + Inches(0.10)
    body_w    = width - Inches(0.30)

    # 核心论点（加粗深色）
    add_text(slide, body_left, body_top, body_w, Inches(0.35),
             claim, size=11, bold=True, color=COLOR_TEXT,
             align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP)

    # 局限（强调色 + 箭头前缀）
    add_text(slide, body_left, body_top + Inches(0.40), body_w, Inches(0.30),
             "→  " + limit, size=10, bold=False, color=accent_c,
             align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP)

    # 细节（浅灰小字）
    add_text(slide, body_left, body_top + Inches(0.72), body_w, Inches(0.30),
             details, size=9, bold=False, color=COLOR_LIGHT,
             align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP)


# ===================== 构建 PPT =====================
prs, slide = new_presentation()

add_header(
    slide,
    "靠 Prompt 不如让 AI 直接读懂你的代码库",
    "Prompt 的四大局限：表达力 · 上下文 · 记忆 · 时效性",
    badge="DeepAgent · 动态生成 PPT",
)
add_divider(slide)

# ---- 2×2 卡片网格参数 ----
card_w = Inches(5.97)
card_h = Inches(2.20)
gap_x  = Inches(0.20)
gap_y  = Inches(0.15)
mleft  = Inches(0.60)
mtop   = Inches(1.60)

# 第一行
add_detail_card(slide, mleft, mtop, card_w, card_h,
    "① 表达力局限",
    "Prompt 写得再好，你能表达的，都是显性的",
    "穷尽不了代码库的所有隐式规则",
    "命名规范、逻辑约定、边界处理……",
    COLOR_WARN)

add_detail_card(slide, mleft + card_w + gap_x, mtop, card_w, card_h,
    "② 上下文局限",
    "上下文窗口再大，你能塞进去的，都是有限的",
    "装不下整个仓库的架构决策",
    "模块边界、依赖关系、演进历史……",
    COLOR_PRIMARY)

# 第二行
row2_top = mtop + card_h + gap_y
add_detail_card(slide, mleft, row2_top, card_w, card_h,
    "③ 记忆局限",
    "模型能力再强再智能，也没有项目记忆",
    "不知道你的项目有什么约定",
    "代码风格、设计模式、团队习惯……",
    COLOR_ACCENT)

add_detail_card(slide, mleft + card_w + gap_x, row2_top, card_w, card_h,
    "④ 时效性局限",
    "\u201c教得更好\u201d有天花板，你教得再多，追不上变化",
    "规则随代码演进变化，永远追不上",
    "重构、新需求、技术债、团队更替……",
    COLOR_PURPLE)

# ---- 底部结论条 ----
conc_top = Inches(6.50)
add_rect(slide, Inches(0.60), conc_top, Inches(12.13), Inches(0.55), COLOR_PRIMARY)
add_text(slide, Inches(0.60), conc_top, Inches(12.13), Inches(0.55),
    "结论：靠 Prompt 不如让 AI 直接读懂你的代码库和规则",
    size=14, bold=True, color=COLOR_WHITE,
    align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)

# ---- 保存 ----
out_path = r"D:\work\projects\java\latest\agent-core-java\examples\deepagent-example\dynamic_code\output\prompt_limitations.pptx"
save(prs, out_path)
print("[OK] PPT generated: " + out_path)

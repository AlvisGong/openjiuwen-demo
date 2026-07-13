#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
ppt_generator/reference_gen_ppt.py
==================================

**参考实现**：演示「根据 Markdown 内容生成一个贴合内容的 PPT 脚本」的标准写法。

- 读入 reference_content.md（ETCSV 模型说明，含一个 markdown 表格 + 结论）
- 解析其中的 标题 / 引用副标题 / 表格 / 结论
- 调用 ppt_kit 的原语渲染一页 **表格型** PPT
- 输出 .pptx

它不是"通用渲染器"，而是给 Agent 看的**风格范例**：
当输入内容以表格为主时，生成的脚本应当长成这样。
输入内容以"分栏要点"为主时，Agent 应改用 add_card 多栏布局；
以"时间线/流程"为主时，应自绘箭头串联——布局随内容而变。

可独立运行验证：

    python reference_gen_ppt.py \\
        --md  <skill_dir>/reference_content.md \\
        -o    <output>/etcsv.pptx
"""
import argparse
import os
import re
import sys

# 让本脚本无论从哪里运行都能 import 同目录下的 ppt_kit
SKILL_DIR = os.path.dirname(os.path.abspath(__file__))
if SKILL_DIR not in sys.path:
    sys.path.insert(0, SKILL_DIR)

from ppt_kit import (  # noqa: E402
    new_presentation, add_header, add_divider, add_table, add_bottom_bar,
    add_quote, save, FONT_CN,
)
from pptx.util import Inches  # noqa: E402


def parse_markdown(md_text):
    """极简 markdown 解析：提取标题 / 副标题(引用) / 第一个表格 / 结论。

    返回 dict：{title, subtitle, table:{headers, rows}, conclusion}
    """
    lines = md_text.splitlines()
    title = ""
    subtitle = ""
    conclusion = ""
    table = None

    # 表格定位：连续的 |...| 行，且第二行是分隔行 |---|
    table_start = None
    for i, line in enumerate(lines):
        if line.startswith("# "):
            title = line[2:].strip()
        elif line.startswith("> "):
            if not subtitle:
                subtitle = line[2:].strip()
        elif line.startswith("## "):
            # 第一个 ## 当作结论段标题之后的内容
            if not conclusion:
                # 取该标题后第一个非空、非标题、非表格行作为结论
                for nxt in lines[i + 1:]:
                    s = nxt.strip()
                    if s and not s.startswith("#") and "|" not in s:
                        conclusion = s
                        break
        elif line.lstrip().startswith("|") and table_start is None:
            # 表格首行后跟分隔行才确认是表格
            if i + 1 < len(lines) and re.match(r"^\s*\|?[\s:|-]+\|?\s*$", lines[i + 1]):
                table_start = i

    if table_start is not None:
        headers = _split_row(lines[table_start])
        rows = []
        j = table_start + 2  # 跳过分隔行
        while j < len(lines) and lines[j].lstrip().startswith("|"):
            rows.append(_split_row(lines[j]))
            j += 1
        table = {"headers": headers, "rows": rows}

    return {"title": title, "subtitle": subtitle, "table": table,
            "conclusion": conclusion}


def _split_row(line):
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    return cells


def build(md_path, out_path):
    with open(md_path, "r", encoding="utf-8") as f:
        spec = parse_markdown(f.read())

    prs, slide = new_presentation()

    add_header(
        slide,
        spec["title"] or "未命名",
        spec["subtitle"],
        badge="DeepAgent · 动态生成 PPT",
    )
    add_divider(slide)

    tbl = spec.get("table")
    if tbl and tbl.get("headers"):
        # 表格型布局：占满中部主区域
        add_table(
            slide,
            Inches(0.6), Inches(1.7), Inches(12.13), Inches(4.4),
            tbl["headers"], tbl["rows"],
            accent="primary",
            header_size=12, body_size=11,
            col_widths=[Inches(2.6), Inches(5.2), Inches(4.33)],
        )

    if spec.get("conclusion"):
        add_bottom_bar(slide, "结论：" + spec["conclusion"])
        add_quote(slide, 'ETCSV = 把「可控性」拆成可独立观测、可独立加固的工程接口。')

    save(prs, out_path)
    return out_path


def main():
    default_md = os.path.join(SKILL_DIR, "reference_content.md")
    parser = argparse.ArgumentParser(description="参考实现：从 Markdown 生成一页 PPT")
    parser.add_argument("--md", default=default_md, help="输入 markdown 文件路径")
    parser.add_argument("-o", "--output", default=None, help="输出 .pptx 路径")
    args = parser.parse_args()

    out = args.output or os.path.join(os.getcwd(), "reference.pptx")
    build(args.md, out)
    print("[OK] PPT generated: " + out)


if __name__ == "__main__":
    main()

---
name: ppt_generator
description: |
  PPT 生成技能。当用户给出一段内容描述，要求"生成一页 PPT / 做一张幻灯片 / 生成精美 PPT"
  时触发。流程：先把内容整理成 Markdown，再**根据该 Markdown 的实际结构每次生成一个
  贴合内容的 Python 脚本**（import ppt_kit 复用绘图原语），执行该脚本产出 .pptx。
  不套用固定模板，布局随内容而变。
---

# PPT 生成技能 (ppt_generator)

给定一段**内容描述**，生成一页**精美 PPT**。

核心思想：**输入内容千变万化，固定模板无法适配所有场景**。因此本技能不提供"固定
spec + 固定布局"，而是要求 Agent：

1. 先把用户内容**转成 Markdown**（`*.md`）；
2. **根据该 Markdown 的实际结构，每次生成一个全新的、贴合内容的 Python 脚本**；
3. 执行该脚本，产出 `.pptx` 并返回路径。

布局（表格 / 多栏要点 / 流程时间线 / 图文混排……）由内容的实际结构决定，**不要**强行
套用某一种固定排版。

## 触发场景

- 用户说："根据下面描述的内容，帮我生成一页精美的 PPT"
- 用户说："把这段话做成一张幻灯片 / 做一页 PPT"
- 用户提供一段文字 / 表格 / 列表，要求可视化呈现为单页 PPT

## 关键文件

本技能目录下提供：

| 文件 | 用途 |
|------|------|
| `ppt_kit.py` | **可复用绘图工具箱**：主题配色 + 原语（`new_presentation` / `add_header` / `add_divider` / `add_card` / `add_bullets` / `add_table` / `add_bottom_bar` / `add_quote` / `add_text` / `add_rect` / `save`）。生成的脚本只需 `import ppt_kit` 复用，不必重写绘图代码。 |
| `reference_gen_ppt.py` | **参考实现**：演示"读 Markdown → 生成贴合内容的脚本 → 产 PPT"的标准写法（表格型示例）。Agent 生成自己的脚本时应参考其风格，但布局要随自己内容而变。 |
| `reference_content.md` | 参考输入 Markdown（ETCSV 模型说明，含表格 + 结论）。 |

> 调用 `skill_tool(skill_name="ppt_generator")` 可获取本技能目录的绝对路径
> （返回字段 `skill_directory`）。`ppt_kit.py` 即在该目录下。

## ppt_kit 提供的原语（速查）

```python
from ppt_kit import (
    new_presentation,        # -> (prs, slide) 16:9 空白页
    set_bg, add_rect,        # 背景 / 矩形
    add_text, add_bullets,  # 多行文本 / 项目符号列表
    add_header,              # 顶部标题区（主标题+副标题+右上角标识）
    add_divider,            # 分隔线
    add_card,               # 带标题条的卡片 + 项目符号（多栏要点用）
    add_table,              # 带主题样式的表格（表头强调色+隔行底色）
    add_bottom_bar,         # 底部补充条
    add_quote,              # 底部居中金句/结论
    save,                   # 保存 .pptx
    # 配色：
    COLOR_PRIMARY, COLOR_ACCENT, COLOR_WARN, COLOR_TEXT, COLOR_LIGHT,
    COLOR_BG, COLOR_WHITE, FONT_CN,
)
```

`accent` 取值：`warn`(橙) / `primary`(蓝) / `accent`(绿)；不同元素用不同色区分。

## 执行流程

### 1. 内容 → Markdown

把用户的内容描述整理成结构清晰的 Markdown，写入输出目录，例如
`<output>/content.md`（用 `fs.writeFile`）。Markdown 元素约定：

- `# 标题` → 幻灯片主标题
- `> 引用` → 副标题 / 一句话定位
- `- 要点` 或 `1. 要点` → 列表（多栏卡片 / 项目符号）
- `| 表头 | … |` → 表格
- `## 结论` 后的段落 → 底部金句

> 整理 Markdown 时应**忠实保留**用户的层级与并列结构，不要为了凑版式而拆散或合并。
> 一页装不下时主动精炼，而不是堆砌。

### 2. 按 Markdown 结构生成 Python 脚本

阅读上一步的 Markdown，**根据其主结构选择布局**，生成一个全新的脚本
（例如 `<output>/gen_ppt.py`，用 `fs.writeFile` 写入）。脚本骨架：

```python
import sys, os
SKILL_DIR = r"<skill 目录绝对路径>"     # skill_tool 返回的 skill_directory
sys.path.insert(0, SKILL_DIR)
from ppt_kit import new_presentation, add_header, add_divider, save, ...

prs, slide = new_presentation()
add_header(slide, "<主标题>", "<副标题>", badge="DeepAgent · 动态生成 PPT")
add_divider(slide)

# ↓↓↓ 根据 Markdown 主结构选择下列之一（或组合） ↓↓↓

# (A) 内容以"并列分论点"为主 → 多栏卡片
from pptx.util import Inches
add_card(slide, Inches(0.6),  Inches(1.65), Inches(3.9), Inches(4.5),
         "栏标题1", ["要点1","要点2","要点3"], accent="warn")
add_card(slide, Inches(4.7),  Inches(1.65), Inches(3.9), Inches(4.5),
         "栏标题2", ["..."], accent="primary")
add_card(slide, Inches(8.8),  Inches(1.65), Inches(3.9), Inches(4.5),
         "栏标题3", ["..."], accent="accent")

# (B) 内容以"表格"为主 → add_table（见 reference_gen_ppt.py）
# add_table(slide, Inches(0.6), Inches(1.7), Inches(12.13), Inches(4.4),
#           headers=[...], rows=[[...],[...]], col_widths=[Inches(2.6), Inches(5.2), Inches(4.33)])

# (C) 内容以"时间线/流程"为主 → 用 add_rect + add_text 自绘节点与箭头
# (D) 图文混排 → add_text + add_rect 组合

add_quote(slide, "<结论金句>")
save(prs, r"<output 绝对路径>/<name>.pptx")
print("[OK] PPT generated: " + r"<output>/<name>.pptx")
```

> `reference_gen_ppt.py` 是布局 (B) 的完整可运行范例——**读它的写法，但不要照抄布局**：
> 你的内容若以分栏为主，就写 (A)；以流程为主，就写 (C)。布局必须贴合你的 Markdown。

### 3. 执行脚本生成 PPT

用 `code.executeCode(language="python")` 执行上一步生成的 `gen_ppt.py`。
> `ppt_kit` 首次 import 时会自动 `pip install python-pptx`；若自动安装失败
> （无网络/无 pip），改用 `shell.executeCmd` 执行
> `python -m pip install python-pptx` 后重试。

### 4. 确认产物并返回

- 用 `fs.listFiles` 确认 `.pptx` 已生成且非空。
- 向用户返回 **.pptx 绝对路径**，并说明采用了哪种布局、内容如何组织。

## 注意事项

- **每次都要根据内容生成贴合的脚本，不要套用固定模板/spec。** 这是本技能与"固定
  spec"方式的根本区别，也是它能覆盖各种场景的关键。
- 一页 16:9（13.333 × 7.5 英寸）；文字不宜过多，超长内容主动精炼。
- 所有文件写到输出目录（系统提示词给出），**不要**写到技能目录。
- 中文默认 `Microsoft YaHei`（ppt_kit 已设）。
- 必须产出 `.pptx` 文件并返回路径，不要只输出文字描述。

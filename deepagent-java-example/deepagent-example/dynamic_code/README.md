# dynamic_code — 基于 DeepAgent 动态生成 PPT

> 给定一段**内容描述**，让 DeepAgent 生成一页精美 PPT；过程中**通过执行 Python 代码**
> （`python-pptx`）完成实际排版与文件生成，而不是把 PPT 当成纯文字回复。

## 为什么不用固定模板

输入内容千变万化（表格 / 并列分论点 / 流程时间线 / 图文混排 ……），**固定 spec 模板无法
适配所有场景**。因此本样例采用：

> **内容 → Markdown → 每次按 Markdown 结构生成一个贴合内容的 Python 脚本 → 执行产出 .pptx**

布局随内容的实际结构而变，Agent 每次生成全新脚本，而非套用固定模板。

## 目录结构

```
dynamic_code/
├── DynamicPptExample.java                 # Java 入口：DeepAgent + SkillUseRail + SysOperationRail
├── skills/
│   └── ppt_generator/
│       ├── SKILL.md                        # 技能说明：内容→Markdown→生成脚本→执行 流程
│       ├── ppt_kit.py                      # 可复用绘图工具箱（主题配色 + 原语）
│       ├── reference_gen_ppt.py            # 参考实现：读 Markdown 生成贴合内容的脚本（表格型示例）
│       └── reference_content.md            # 参考输入 Markdown（ETCSV 模型说明）
├── output/                                 # 生成的 .pptx 落点（运行时产生）
└── README.md                               # 本文档
```

## 工作原理

```
用户内容描述
    │
    ▼  (Agent 自主)
1. skill_tool → 阅读 ppt_generator/SKILL.md + ppt_kit.py + reference_gen_ppt.py
2. 把内容整理成 Markdown → fs.writeFile 写入 output/content.md
3. 按 Markdown 实际结构生成贴合内容的脚本 → fs.writeFile 写入 output/gen_ppt.py
       (import ppt_kit 复用原语；表格用 add_table / 分栏用 add_card / 流程自绘)
4. code.executeCode(language=python) 执行 gen_ppt.py → 产出 .pptx 到 output/
5. fs.listFiles 确认产物 → 返回 .pptx 绝对路径
```

涉及的能力组件：

| 组件 | 作用 |
|------|------|
| `SkillUseRail` | 自动加载 `ppt_generator` 技能，注册 `list_skill` / `skill_tool`，把技能说明注入系统提示词 |
| `SysOperationRail` | 提供 `code.executeCode` / `fs.*` / `shell.executeCmd` 工具 |
| `code.executeCode` | **核心**：在 Python 沙箱中执行 Agent 生成的脚本，产出 .pptx |
| `ppt_kit.py` | 可复用工具箱：主题配色 + `new_presentation/add_header/add_divider/add_card/add_table/add_quote/save` 等原语；首次 import 自动 `pip install python-pptx` |
| `reference_gen_ppt.py` | 参考实现：演示"读 Markdown → 生成贴合内容的脚本"的标准写法（表格型） |

## ppt_kit 提供的原语

```python
from ppt_kit import (
    new_presentation, set_bg, add_rect, add_text, add_bullets,
    add_header, add_divider, add_card, add_table, add_bottom_bar, add_quote, save,
    COLOR_PRIMARY, COLOR_ACCENT, COLOR_WARN, COLOR_TEXT, COLOR_LIGHT,
    COLOR_BG, COLOR_WHITE, FONT_CN,
)
```

`accent`：`warn`(橙) / `primary`(蓝) / `accent`(绿)。布局选择：

| 内容主结构 | 推荐布局 |
|------------|----------|
| 表格 | `add_table` |
| 并列分论点 | 多栏 `add_card` |
| 时间线/流程 | `add_rect` + `add_text` 自绘节点与箭头 |
| 图文混排 | `add_text` + `add_rect` 组合 |

## 运行

### 前置条件

- 本机可执行 `python`（或 `python3`）。
- 首次运行 `ppt_kit` 会自动 `pip install python-pptx`；无网络时请提前 `pip install python-pptx`。
- 已配置 LLM（`examples/utils/SharedExampleApiConfigLoader`，见根目录 `apiconfig.json`）。

### 编译与运行

```bash
# 默认：用内置的内容描述生成一页 PPT
java myexample.dynamic_code.DynamicPptExample

# 自定义内容描述
java myexample.dynamic_code.DynamicPptExample --query "根据下面内容生成一页PPT：……"
```
# -*- coding: utf-8 -*-
"""生成 DeepAgent + Skill/Tool/Rail 开发培训 PPT（2 页）。

用法: python gen_deepagent_training_ppt.py
输出: DeepAgent开发培训.pptx（与本脚本同目录）
"""

from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR

FONT = "微软雅黑"
BLUE = RGBColor(0x1F, 0x4E, 0x79)
LIGHT_BLUE = RGBColor(0xDE, 0xEB, 0xF7)
GREEN = RGBColor(0x2E, 0x7D, 0x32)
ORANGE = RGBColor(0xE6, 0x6C, 0x00)
GRAY = RGBColor(0x59, 0x59, 0x59)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
CODE_BG = RGBColor(0xF2, 0xF2, 0xF2)


def add_textbox(slide, left, top, width, height, text, size=14, bold=False,
                color=None, align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP, wrap=True):
    box = slide.shapes.add_textbox(left, top, width, height)
    tf = box.text_frame
    tf.word_wrap = wrap
    tf.vertical_anchor = anchor
    lines = text.split("\n")
    for i, line in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        run = p.add_run()
        run.text = line
        run.font.name = FONT
        run.font.size = Pt(size)
        run.font.bold = bold
        run.font.color.rgb = color if color else RGBColor(0x33, 0x33, 0x33)
    return box


def add_title_bar(slide, title, subtitle=""):
    bar = slide.shapes.add_shape(1, Inches(0), Inches(0), Inches(13.333), Inches(0.75))
    bar.fill.solid()
    bar.fill.fore_color.rgb = BLUE
    bar.line.fill.background()
    tf = bar.text_frame
    tf.margin_left = Inches(0.35)
    p = tf.paragraphs[0]
    p.alignment = PP_ALIGN.LEFT
    run = p.add_run()
    run.text = title
    run.font.name = FONT
    run.font.size = Pt(26)
    run.font.bold = True
    run.font.color.rgb = WHITE
    if subtitle:
        add_textbox(slide, Inches(0.4), Inches(0.78), Inches(12.5), Inches(0.35),
                    subtitle, size=13, color=GRAY)


def add_box(slide, left, top, width, height, title, body, fill=LIGHT_BLUE,
            title_color=BLUE, body_size=12, title_size=14):
    shape = slide.shapes.add_shape(5, left, top, width, height)  # rounded rect
    shape.fill.solid()
    shape.fill.fore_color.rgb = fill
    shape.line.color.rgb = BLUE
    shape.line.width = Pt(1)
    shape.shadow.inherit = False
    tf = shape.text_frame
    tf.word_wrap = True
    tf.margin_left = Inches(0.12)
    tf.margin_right = Inches(0.12)
    tf.margin_top = Inches(0.06)
    tf.margin_bottom = Inches(0.06)
    p = tf.paragraphs[0]
    run = p.add_run()
    run.text = title
    run.font.name = FONT
    run.font.size = Pt(title_size)
    run.font.bold = True
    run.font.color.rgb = title_color
    for line in body.split("\n"):
        p2 = tf.add_paragraph()
        run2 = p2.add_run()
        run2.text = line
        run2.font.name = FONT
        run2.font.size = Pt(body_size)
        run2.font.color.rgb = RGBColor(0x33, 0x33, 0x33)
    return shape


def build_slide1(prs):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    add_title_bar(slide, "DeepAgent 开发实战：Skill + Tool + Rail",
                  "以银行贷后「经营流水下滑复核」为例 —— agent-core-java / examples/risk_review_cashflow")

    # 左上：需求背景
    add_box(slide, Inches(0.35), Inches(1.15), Inches(6.2), Inches(2.5),
            "① 需求场景与背景",
            "• 银行贷后风险复核：一期 LLM 初评（COT）提示企业 P002\n"
            "  经营流水下滑、收入持续下降、存在负现金流风险\n"
            "• 复核要求：取真实经营流水数据佐证，只陈述事实，\n"
            "  不做「是否下滑」布尔判断（交主复核决策）\n"
            "• 工程约束：全 Java 实现，不依赖 Python 脚本执行；\n"
            "  直连 GaussDB（openGauss JDBC，host=localhost:5432）\n"
            "• 可观测：工具调用参数与返回结果需要全程可见")

    # 右上：整体方案架构
    add_box(slide, Inches(6.85), Inches(1.15), Inches(6.1), Inches(2.5),
            "② 整体方案：三层协作",
            "DeepAgent（ReAct 循环编排）\n"
            "  ├─ Skill  = 领域知识：SKILL.md 定义触发条件与调用规约\n"
            "  ├─ Tool   = 确定性动作：Java 工具直连高斯数据库取数\n"
            "  └─ Rail   = 切面观测：打印每次工具调用的参数/结果\n"
            "运行链路：Agent 读 Skill 指引 → 调 cashflow_review 工具\n"
            "→ GaussDB(emc.emc_llm_flow_analysis) → Rail 实时打印\n"
            "→ Agent 基于事实数据输出复核意见")

    # 下方：架构流程图（文本框模拟）
    flow = ("用户 Query(P002 流水下滑)  →  DeepAgent(ReAct 1..8 轮)  →  skill_tool 加载 SKILL.md\n"
            "→  cashflow_review 工具  →  GaussDbClient(JDBC)  →  GaussDB emc 流水表(最新 PT_DT 批次)\n"
            "→  Rail[beforeToolCall/afterToolCall] 打印参数与结果  →  LLM 汇总输出事实复核意见")
    add_box(slide, Inches(0.35), Inches(3.85), Inches(12.6), Inches(1.35),
            "③ 端到端执行流程（实测链路）", flow,
            fill=RGBColor(0xE8, 0xF5, 0xE9), title_color=GREEN, body_size=12)

    # 底部：三个角色职责对比
    add_box(slide, Inches(0.35), Inches(5.35), Inches(4.05), Inches(1.55),
            "Skill（教 Agent「何时做什么」）",
            "• 目录: skills/fund_cashflow_review_skill_java/\n"
            "• SKILL.md: front-matter + 触发条件 + 参数契约\n"
            "• 由框架 skill_tool 自动注入给 LLM",
            body_size=11, title_size=13)
    add_box(slide, Inches(4.62), Inches(5.35), Inches(4.05), Inches(1.55),
            "Tool（替 Agent「动手取数」）",
            "• CashflowReviewTool extends Tool\n"
            "• ToolCard 定义 JSON Schema 入参\n"
            "• invoke() 查 GaussDB 返回结构化结果",
            fill=RGBColor(0xFD, 0xEF, 0xD9), title_color=ORANGE, body_size=11, title_size=13)
    add_box(slide, Inches(8.9), Inches(5.35), Inches(4.05), Inches(1.55),
            "Rail（旁观「全过程观测」）",
            "• SkillResultPrintRail extends AgentRail\n"
            "• beforeToolCall/afterToolCall 钩子\n"
            "• 不侵入业务逻辑，可插拔注册",
            fill=RGBColor(0xF3, 0xE5, 0xF5), title_color=RGBColor(0x6A, 0x1B, 0x9A),
            body_size=11, title_size=13)
    return slide


def build_slide2(prs):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    add_title_bar(slide, "具体实现：四步组装一个 DeepAgent",
                  "Skill 指引 → Tool 取数 → Rail 观测 → DeepAgentConfig 组装（均可复用于其他业务场景）")

    # 步骤1：开发 Java 工具
    add_box(slide, Inches(0.35), Inches(1.15), Inches(6.2), Inches(2.6),
            "Step 1  开发 Java 工具（examples/risk_review_cashflow/tools/）",
            "class CashflowReviewTool extends Tool {\n"
            "  // ① ToolCard 声明 ID/描述/入参 JSON Schema\n"
            "  super(ToolCard.builder().id(\"cashflow_review\")\n"
            "    .inputParams(schema).build());\n"
            "  // ② invoke() 直连 GaussDB 取数（最新 PT_DT 批次）\n"
            "  public Object invoke(inputs, kwargs) {\n"
            "    GaussDbClient.select(MAIN_CIS=party_id)\n"
            "    → INCM_AMT_OP/INCM_TXS_OP/EXPNDTR_AMT_OP/EXPNDTR_TXS_OP\n"
            "    → {success, operating_cashflow, assessment_summary}\n"
            "  }}",
            fill=CODE_BG, title_color=BLUE, body_size=10.5, title_size=13)

    # 步骤2：编写 SKILL.md
    add_box(slide, Inches(6.85), Inches(1.15), Inches(6.1), Inches(2.6),
            "Step 2  编写重构版 Skill（skills/…_java/SKILL.md）",
            "---\n"
            "name: fund_cashflow_review_skill_java\n"
            "description: 经营流水下滑场景评估（Java tool 版）\n"
            "---\n"
            "# 触发条件\n"
            "一期 COT/结论含：经营流水下滑、负现金流、\n"
            "收入持续下降、长期无收入。\n"
            "# 工具调用\n"
            "直接调用 Java 工具 cashflow_review，\n"
            "参数 {\"party_id\": \"...\", \"company_id\": \"...\"}\n"
            "→ 不再通过 call_mcp 调 Python 脚本",
            fill=CODE_BG, title_color=GREEN, body_size=10.5, title_size=13)

    # 步骤3：Rail
    add_box(slide, Inches(0.35), Inches(3.95), Inches(6.2), Inches(2.05),
            "Step 3  实现 Rail（examples/risk_review_cashflow/rails/）",
            "public class SkillResultPrintRail extends AgentRail {\n"
            "  @Override public void beforeToolCall(ctx) {\n"
            "    // 打印工具名 + 调用参数\n"
            "  }\n"
            "  @Override public void afterToolCall(ctx) {\n"
            "    // 打印工具返回结果（>2000 字符自动截断）\n"
            "  }}\n"
            "框架回调链自动触发，无需侵入工具代码",
            fill=CODE_BG, title_color=RGBColor(0x6A, 0x1B, 0x9A), body_size=10.5, title_size=13)

    # 步骤4：组装
    add_box(slide, Inches(6.85), Inches(3.95), Inches(6.1), Inches(2.05),
            "Step 4  组装并启动 DeepAgent（DeepAgentCashflowSkillExample）",
            "DeepAgentConfig.builder()\n"
            "  .model(...).backend(...)          // glm-5.2 / DashScope\n"
            "  .systemPrompt(复核助手提示词)\n"
            "  .skillDirectories(skills父目录)   // 扫描 SKILL.md\n"
            "  .skills(List.of(\"fund_cashflow_review_skill_java\"))\n"
            "  .rails(List.of(new SkillResultPrintRail()))\n"
            "  .build();\n"
            "agent.registerHarnessTool(new CashflowReviewTool(db));\n"
            "agent.invoke(inputs);  // ReAct 循环执行",
            fill=CODE_BG, title_color=ORANGE, body_size=10.5, title_size=13)

    # 底部：实测结果
    result = ("Rail 实测输出（run-rail2.log）：\n"
              "[rail][skill] 调用工具 skill_tool，参数: {\"skill_name\": \"fund_cashflow_review_skill_java\"}\n"
              "[rail][skill] 调用工具 cashflow_review，参数: {\"party_id\": \"P002\", \"company_id\": \"C002\"}\n"
              "[rail][skill] 工具 cashflow_review 返回结果: {success=true, operating_cashflow={PT_DT=20260630, INCM_AMT_OP=280000.00, INCM_TXS_OP=90, EXPNDTR_AMT_OP=540000.00, EXPNDTR_TXS_OP=140}, ...}\n"
              "→ Agent 最终输出：净流出 -260,000 元，收支比 1.93:1；仅陈述事实，布尔判断交主复核 ✓")
    add_box(slide, Inches(0.35), Inches(6.15), Inches(12.6), Inches(1.15),
            "验证结果（真实 GaussDB 数据全链路跑通）", result,
            fill=RGBColor(0xE8, 0xF5, 0xE9), title_color=GREEN, body_size=10.5, title_size=13)
    return slide


def build_slide3(prs):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    add_title_bar(slide, "附录：完整代码 —— DeepAgentConfig 组装 & Rail 实现",
                  "摘自 examples/risk_review_cashflow/DeepAgentCashflowSkillExample.java 与 examples/risk_review_cashflow/rails/SkillResultPrintRail.java")

    # 左：DeepAgentConfig 组装（buildAgent 方法，节选核心）
    config_code = (
        "private static DeepAgent buildAgent(Path skillDir, Path workspacePath) {\n"
        "  Map<String, Object> modelConfig = new LinkedHashMap<>();\n"
        "  modelConfig.put(\"model\", MODEL_NAME);          // glm-5.2\n"
        "  modelConfig.put(\"temperature\", 0.2);\n"
        "  modelConfig.put(\"max_tokens\", 4096);\n"
        "  modelConfig.put(\"enable_thinking\", false);\n"
        "\n"
        "  Map<String, Object> backendConfig = new LinkedHashMap<>();\n"
        "  backendConfig.put(\"client_provider\", MODEL_PROVIDER);\n"
        "  backendConfig.put(\"api_key\", API_KEY);\n"
        "  backendConfig.put(\"api_base\", API_BASE);       // DashScope\n"
        "  backendConfig.put(\"verify_ssl\", true);\n"
        "\n"
        "  DeepAgentConfig config = DeepAgentConfig.builder()\n"
        "      .enableTaskLoop(true)\n"
        "      .enableTaskPlanning(false)\n"
        "      .enableTenantIsolation(false)\n"
        "      .workspacePath(workspacePath.toString())\n"
        "      .systemPrompt(SYSTEM_PROMPT)\n"
        "      .maxIterations(8)\n"
        "      .completionTimeout(180.0)\n"
        "      .language(\"cn\")\n"
        "      .model(modelConfig)\n"
        "      .backend(backendConfig)\n"
        "      // skillDirectories 指向包含各 skill 子目录的父目录（skills/）\n"
        "      .skillDirectories(List.of(skillDir.getParent().toString()))\n"
        "      .skillMode(\"all\")\n"
        "      .skills(List.of(\"fund_cashflow_review_skill_java\"))\n"
        "      // 注册 Rail：打印 skill 相关工具的调用参数与返回结果\n"
        "      .rails(List.of(new SkillResultPrintRail()))\n"
        "      .build();\n"
        "\n"
        "  AgentCard card = AgentCard.builder()\n"
        "      .id(AGENT_ID).name(AGENT_ID)\n"
        "      .description(\"DeepAgent 经营流水复核（Java tool + GaussDB）\")\n"
        "      .build();\n"
        "  Workspace ws = Workspace.builder()\n"
        "      .rootPath(workspacePath.toString()).language(\"cn\").build();\n"
        "  return HarnessFactory.createDeepAgent(card, config, ws);\n"
        "}"
    )
    add_box(slide, Inches(0.35), Inches(1.15), Inches(6.2), Inches(5.3),
            "A. DeepAgentConfig 组装（buildAgent 方法节选）", config_code,
            fill=CODE_BG, title_color=BLUE, body_size=8.5, title_size=13)

    # 右：Rail 完整实现
    rail_code = (
        "public final class SkillResultPrintRail extends AgentRail {\n"
        "\n"
        "  private static final int MAX_RESULT_CHARS = 2000;\n"
        "  private static final String TAG = \"[rail][skill] \";\n"
        "\n"
        "  @Override\n"
        "  public void beforeToolCall(AgentCallbackContext ctx) {\n"
        "    if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {\n"
        "      return;\n"
        "    }\n"
        "    System.out.println(TAG + \"调用工具 \" + inputs.getToolName()\n"
        "        + \"，参数: \" + inputs.getToolArgs());\n"
        "  }\n"
        "\n"
        "  @Override\n"
        "  public void afterToolCall(AgentCallbackContext ctx) {\n"
        "    if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {\n"
        "      return;\n"
        "    }\n"
        "    System.out.println(TAG + \"工具 \" + inputs.getToolName()\n"
        "        + \" 返回结果:\\n\" + TAG + truncate(inputs.getToolResult()));\n"
        "  }\n"
        "\n"
        "  private static String truncate(Object result) {\n"
        "    String text = result == null ? \"(null)\" : String.valueOf(result);\n"
        "    if (text.length() <= MAX_RESULT_CHARS) {\n"
        "      return text;\n"
        "    }\n"
        "    return text.substring(0, MAX_RESULT_CHARS)\n"
        "        + \"...(截断，共 \" + text.length() + \" 字符)\";\n"
        "  }\n"
        "}"
    )
    add_box(slide, Inches(6.85), Inches(1.15), Inches(6.1), Inches(5.3),
            "B. Rail 完整实现（rails/SkillResultPrintRail.java）", rail_code,
            fill=CODE_BG, title_color=RGBColor(0x6A, 0x1B, 0x9A), body_size=8.5, title_size=13)

    # 底部：注册与触发说明
    note = ("关键点：① registerHarnessTool(new CashflowReviewTool(dbClient)) 在 DeepAgent 创建后注册工具；"
            "② Rails 在 DeepAgentConfig 中声明，框架 ensureInitialized 时挂到回调链；"
            "③ instanceof ToolCallInputs 模式匹配过滤非工具类回调（与框架 ToolTrackingRail 同款写法）；"
            "④ Rail 不侵入业务代码，加/删一行 .rails(...) 即插拔")
    add_box(slide, Inches(0.35), Inches(6.55), Inches(12.6), Inches(0.75),
            "注册与触发说明", note,
            fill=RGBColor(0xFD, 0xEF, 0xD9), title_color=ORANGE, body_size=10.5, title_size=12)
    return slide


def build_slide4(prs):
    """Skill / Rail 调用时序图页。"""
    from pptx.enum.shapes import MSO_CONNECTOR
    from pptx.enum.dml import MSO_LINE_DASH_STYLE as MSO_LINE
    from pptx.oxml.ns import qn

    PURPLE = RGBColor(0x6A, 0x1B, 0x9A)
    DARK = RGBColor(0x33, 0x33, 0x33)

    slide = prs.slides.add_slide(prs.slide_layouts[6])
    add_title_bar(slide, "Skill 与 Rail 调用时序图",
                  "依据 run-rail2.log 实测链路绘制 —— Rail 在每次工具调用前后各触发一次")

    # 参与方（生命线 x 坐标）
    actors = [
        ("用户", 1.15, GRAY),
        ("DeepAgent\n(ReAct+LLM)", 3.32, BLUE),
        ("Rail\n(切面观测)", 5.49, PURPLE),
        ("skill_tool", 7.66, GREEN),
        ("cashflow_review\n(Java Tool)", 9.83, ORANGE),
        ("GaussDB", 12.0, BLUE),
    ]
    top_y = 1.2
    line_end = 6.7

    def add_lifeline(x, color):
        conn = slide.shapes.add_connector(
            MSO_CONNECTOR.STRAIGHT, Inches(x), Inches(top_y + 0.45),
            Inches(x), Inches(line_end))
        conn.line.color.rgb = RGBColor(0xAA, 0xAA, 0xAA)
        conn.line.width = Pt(1)
        conn.line.dash_style = MSO_LINE.DASH

    for name, x, color in actors:
        box = slide.shapes.add_shape(5, Inches(x - 0.95), Inches(top_y),
                                     Inches(1.9), Inches(0.45))
        box.fill.solid()
        box.fill.fore_color.rgb = color
        box.line.fill.background()
        box.shadow.inherit = False
        tf = box.text_frame
        tf.word_wrap = False
        tf.margin_left = Inches(0.02)
        tf.margin_right = Inches(0.02)
        for i, part in enumerate(name.split("\n")):
            p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
            p.alignment = PP_ALIGN.CENTER
            run = p.add_run()
            run.text = part
            run.font.name = FONT
            run.font.size = Pt(10 if len(name) > 8 else 11)
            run.font.bold = True
            run.font.color.rgb = WHITE
        add_lifeline(x, color)

    def arrow(x1, x2, y, color, dashed=False, label="", label_color=None):
        conn = slide.shapes.add_connector(
            MSO_CONNECTOR.STRAIGHT, Inches(x1), Inches(y), Inches(x2), Inches(y))
        conn.line.color.rgb = color
        conn.line.width = Pt(1.75)
        if dashed:
            conn.line.dash_style = MSO_LINE.DASH
        ln = conn.line._get_or_add_ln()
        tail = ln.makeelement(qn('a:tailEnd'),
                              {'type': 'triangle', 'w': 'med', 'len': 'med'})
        ln.append(tail)
        if label:
            mid = (x1 + x2) / 2
            w = min(6.0, max(2.2, 0.16 * len(label)))
            add_textbox(slide, Inches(mid - w / 2), Inches(y - 0.30), Inches(w),
                        Inches(0.28), label, size=9.5, bold=True,
                        color=label_color if label_color else DARK, wrap=False)

    def self_note(x, y, text):
        add_textbox(slide, Inches(x + 0.1), Inches(y - 0.28), Inches(3.4),
                    Inches(0.55), text, size=9, color=GRAY, wrap=False)

    ux, ax, rx, sx, tx, gx = (a[1] for a in actors)

    # ---- 消息序列（与实测日志一致）----
    y = 2.02
    arrow(ux, ax, y, DARK, label="query（P002 流水下滑提示）")

    y = 2.42
    self_note(ax, y, "LLM 决策（iter 1/8）：先加载 skill")
    arrow(ax, rx, y, PURPLE, dashed=True, label="① beforeToolCall(skill_tool)")

    y = 2.82
    arrow(ax, sx, y, BLUE, label="执行 skill_tool(skill_name=...)")

    y = 3.22
    arrow(sx, ax, y, GRAY, dashed=True, label="SKILL.md 内容")

    y = 3.62
    arrow(ax, rx, y, PURPLE, dashed=True, label="② afterToolCall(SKILL.md)")

    y = 4.02
    self_note(ax, y, "LLM 按 SKILL.md 规约发起取数")
    arrow(ax, rx, y, PURPLE, dashed=True, label="③ beforeToolCall(cashflow_review)")

    y = 4.42
    arrow(ax, tx, y, BLUE, label="{party_id=P002, company_id=C002}")

    y = 4.82
    arrow(tx, gx, y, BLUE, label="JDBC 查询 emc 流水表（最新 PT_DT）")

    y = 5.22
    arrow(gx, tx, y, GRAY, dashed=True, label="流水数据（收入/支出金额与笔数）")

    y = 5.62
    arrow(tx, ax, y, GRAY, dashed=True,
          label="{success, operating_cashflow, assessment_summary}")

    y = 6.02
    arrow(ax, rx, y, PURPLE, dashed=True, label="④ afterToolCall(工具结果)")

    y = 6.34
    self_note(ax, y, "LLM 汇总（iter 2..3/8）")

    y = 6.62
    arrow(ax, ux, y, GREEN, label="final answer（事实复核意见）", label_color=GREEN)

    # 底部讲解要点
    note = ("讲解要点：① Rail 共 4 个触发点 = skill_tool / cashflow_review 各一对 "
            "beforeToolCall + afterToolCall（成对出现，紫虚线）；"
            "② skill_tool 仅首次加载，SKILL.md 进入对话历史，后续轮次复用不再重调；"
            "③ 所有请求=实线、返回=灰虚线；Rail 只旁观不改动数据（纯观测切面，即插拔）")
    add_box(slide, Inches(0.35), Inches(6.95), Inches(12.6), Inches(0.5),
            "", note, fill=RGBColor(0xFD, 0xEF, 0xD9),
            title_color=ORANGE, body_size=10)
    return slide


def build_slide5(prs):
    """数据来源页：emc_llm_flow_analysis 表结构与数据样例。"""
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    add_title_bar(slide, "附录：工具数据来源 —— emc.emc_llm_flow_analysis",
                  "cashflow_review 工具唯一读取的表（经营流水分析，按客户+批次日取最新一行）")

    # 左：表结构概览
    ddl_text = (
        "CREATE TABLE emc.emc_llm_flow_analysis (\n"
        "  main_cis            varchar(64),   -- 客户号（工具入参 party_id）★\n"
        "  -- ① 全口径收支\n"
        "  incm_amt_overall    numeric(18,2),  incm_txs_overall   integer,\n"
        "  expndtr_amt_overall numeric(18,2),  expndtr_txs_overall integer,\n"
        "  -- ② 非经营性（同业）\n"
        "  incm_amt_nonop_same numeric(18,2),  incm_txs_nonop_same integer,\n"
        "  expndtr_amt_nonop_same numeric(18,2), expndtr_txs_nonop_same integer,\n"
        "  -- ③ 非经营性（机构）\n"
        "  incm_amt_nonop_org  numeric(18,2),  incm_txs_nonop_org  integer,\n"
        "  expndtr_amt_nonop_org numeric(18,2), expndtr_txs_nonop_org integer,\n"
        "  -- ④ 经营性（工具实际读取）★\n"
        "  incm_amt_op         numeric(18,2),  incm_txs_op   integer,\n"
        "  expndtr_amt_op      numeric(18,2),  expndtr_txs_op integer,\n"
        "  pt_dt               varchar(8)      -- 批量日 ★\n"
        ")\n"
        "CREATE INDEX idx_emc_flow_main_cis_pt_dt\n"
        "  ON emc_llm_flow_analysis (main_cis, pt_dt DESC);\n"
        "★ = 工具读取的 6 列：MAIN_CIS / PT_DT / INCM_AMT_OP /\n"
        "    INCM_TXS_OP / EXPNDTR_AMT_OP / EXPNDTR_TXS_OP"
    )
    add_box(slide, Inches(0.35), Inches(1.15), Inches(6.2), Inches(5.0),
            "表结构（DDL 节选，★ 为工具读取列）", ddl_text,
            fill=CODE_BG, title_color=BLUE, body_size=9, title_size=13)

    # 右：数据样例表格（仅工具相关 6 列）
    rows_data = [
        ["main_cis", "pt_dt", "INCM_AMT_OP", "INCM_TXS_OP", "EXPNDTR_AMT_OP", "EXPNDTR_TXS_OP"],
        ["P002", "20260531", "250,000.00", "80", "520,000.00", "130"],
        ["P002", "20260630", "280,000.00", "90", "540,000.00", "140"],
        ["P004", "20260531", "220,000.00", "70", "290,000.00", "110"],
        ["P004", "20260630", "200,000.00", "65", "320,000.00", "120"],
    ]
    tbl_shape = slide.shapes.add_table(5, 6, Inches(6.85), Inches(1.55),
                                       Inches(6.1), Inches(2.2))
    table = tbl_shape.table
    col_widths = [0.85, 1.0, 1.15, 1.05, 1.15, 0.9]
    for i, w in enumerate(col_widths):
        table.columns[i].width = Inches(w)
    for r, row_vals in enumerate(rows_data):
        table.rows[r].height = Inches(0.42)
        for c, val in enumerate(row_vals):
            cell = table.cell(r, c)
            cell.vertical_anchor = MSO_ANCHOR.MIDDLE
            tf = cell.text_frame
            tf.word_wrap = False
            p = tf.paragraphs[0]
            p.alignment = PP_ALIGN.CENTER
            run = p.add_run()
            run.text = val
            run.font.name = FONT
            run.font.size = Pt(10)
            run.font.bold = r == 0 or r == 2
            if r == 0:
                cell.fill.solid()
                cell.fill.fore_color.rgb = BLUE
                run.font.color.rgb = WHITE
            elif r == 2:
                cell.fill.solid()
                cell.fill.fore_color.rgb = RGBColor(0xE8, 0xF5, 0xE9)
                run.font.color.rgb = GREEN
            else:
                cell.fill.solid()
                cell.fill.fore_color.rgb = WHITE
                run.font.color.rgb = RGBColor(0x33, 0x33, 0x33)

    add_textbox(slide, Inches(6.85), Inches(1.15), Inches(6.1), Inches(0.35),
                "数据样例（全表 4 行；绿色行 = P002 最新批次，工具命中行）",
                size=12, bold=True, color=BLUE)

    # 右下：工具 SQL 与命中说明
    sql_text = (
        "工具执行的 SQL（GaussDbClient.getEmcFlowAnalysis）：\n"
        "SELECT MAIN_CIS, PT_DT, INCM_AMT_OP, INCM_TXS_OP,\n"
        "       EXPNDTR_AMT_OP, EXPNDTR_TXS_OP\n"
        "  FROM emc.emc_llm_flow_analysis\n"
        " WHERE MAIN_CIS = ?            -- 入参 party_id\n"
        " ORDER BY PT_DT DESC LIMIT 1   -- 最新批量日\n"
        "\n"
        "→ party_id=P002 命中 20260630：收入 280,000.00 元/90 笔，\n"
        "  支出 540,000.00 元/140 笔 → 净流出 -260,000 元，收支比 1.93:1"
    )
    add_box(slide, Inches(6.85), Inches(4.0), Inches(6.1), Inches(2.15),
            "工具取数 SQL 与命中结果", sql_text,
            fill=CODE_BG, title_color=ORANGE, body_size=9.5, title_size=13)

    # 底部：导出文件说明
    note = ("导出文件（export/ 目录）：emc_llm_flow_analysis_ddl.sql（建表+索引 DDL）、"
            "emc_llm_flow_analysis_data.sql（4 行数据 INSERT，带列名）、dump.sh（gs_dump 导出脚本，"
            "注意 -t 需 schema 限定名 + --pipeline 传密码）。"
            "表全 TEXT/数值存储于 GaussDB，样例实跑取数与 rail 打印一致，已交叉验证")
    add_box(slide, Inches(0.35), Inches(6.35), Inches(12.6), Inches(0.85),
            "培训材料配套导出物", note,
            fill=RGBColor(0xE8, 0xF5, 0xE9), title_color=GREEN, body_size=10, title_size=12)
    return slide


def build_slide6(prs):
    """DeepAgent 构建方法页：构建流程 + 必要参数详解。"""
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    add_title_bar(slide, "DeepAgent 是怎么构建的 —— 从 Builder 到可运行 Agent",
                  "摘自 examples/risk_review_cashflow/DeepAgentCashflowSkillExample.java 的 buildAgent() 方法")

    # 左：构建流程
    flow_text = (
        "Step 1  准备 LLM 配置（Map）\n"
        "  modelConfig   : 模型名/温度/max_tokens\n"
        "  backendConfig : OpenAI 兼容协议接入\n"
        "    (client_provider/api_key/api_base)\n"
        "\n"
        "Step 2  组装运行时配置\n"
        "  DeepAgentConfig.builder()…build()\n"
        "  → 声明行为开关、迭代上限、Skill/Rail\n"
        "\n"
        "Step 3  声明 Agent 身份\n"
        "  AgentCard.builder().id(AGENT_ID)\n"
        "    .name(AGENT_ID).description(…)\n"
        "\n"
        "Step 4  声明工作区（文件沙箱）\n"
        "  Workspace.builder().rootPath(…)\n"
        "    .language(\"cn\")\n"
        "\n"
        "Step 5  工厂组装 → 可运行 Agent\n"
        "  HarnessFactory.createDeepAgent(\n"
        "    card, config, ws)\n"
        "\n"
        "运行前收尾（main 中）：\n"
        "  agent.registerHarnessTool(new\n"
        "    CashflowReviewTool(dbClient));\n"
        "  agent.ensureInitialized();\n"
        "  agent.invoke(inputs);  // ReAct 执行"
    )
    add_box(slide, Inches(0.35), Inches(1.3), Inches(3.55), Inches(5.0),
            "构建流程（5 步）", flow_text,
            fill=CODE_BG, title_color=BLUE, body_size=9.5, title_size=13)

    # 右：参数详解表
    param_rows = [
        ["参数", "本样例取值", "含义说明"],
        ["enableTaskLoop", "true", "自动注册 TaskCompletionRail，评估目标是否达成，防提前结束/空转"],
        ["enableTaskPlanning", "false", "不注册 TaskPlanningRail（计划/TODO 拆解），简单链路无需预规划"],
        ["enableTenantIsolation", "false", "不按租户隔离工作区，单租户演示直接共享 workspacePath"],
        ["workspacePath", "临时目录", "Agent 文件工具的根目录（read_file/write 等），会话中间文件落此"],
        ["systemPrompt", "复核助手提示词", "角色与行为约束：只陈述事实、不做布尔判断、失败如实说明"],
        ["maxIterations", "8（默认 15）", "ReAct 循环最大迭代轮数，日志显示为 iteration 1/8"],
        ["completionTimeout", "180.0 秒", "单次 invoke 的整体完成超时，超时强制收尾；不设则不限时"],
        ["language", "\"cn\"", "内置提示词与工作区语言"],
        ["model", "glm-5.2 / t=0.2", "模型名 + 推理参数（temperature / max_tokens / 关闭深度思考）"],
        ["backend", "DashScope 兼容", "OpenAI 兼容接入：client_provider / api_key / api_base / verify_ssl"],
        ["skillDirectories", "skills/ 父目录", "扫描根：每个子目录 = 一个 skill（含 SKILL.md）→ 注册池"],
        ["skillMode", "\"all\"", "all=全文注入系统提示词；auto_list=注册 list_skill 工具按需搜索"],
        ["skills", "白名单 1 项", "enabledSkills 白名单（空=全启用），过滤注册池决定实际启用"],
        ["rails", "SkillResultPrintRail", "注册 Rail 切面（调用记录落盘），框架再自动补 SecurityRail 等默认 Rail"],
    ]
    tbl_shape = slide.shapes.add_table(15, 3, Inches(4.1), Inches(1.35),
                                       Inches(8.85), Inches(5.0))
    table = tbl_shape.table
    for i, w in enumerate([2.05, 1.9, 4.9]):
        table.columns[i].width = Inches(w)
    for r, row_vals in enumerate(param_rows):
        table.rows[r].height = Inches(0.33)
        for c, val in enumerate(row_vals):
            cell = table.cell(r, c)
            cell.vertical_anchor = MSO_ANCHOR.MIDDLE
            tf = cell.text_frame
            tf.word_wrap = True
            tf.margin_left = Inches(0.06)
            tf.margin_right = Inches(0.06)
            p = tf.paragraphs[0]
            run = p.add_run()
            run.text = val
            run.font.name = FONT
            run.font.size = Pt(9.5 if r > 0 else 10.5)
            run.font.bold = r == 0 or c == 0
            if r == 0:
                cell.fill.solid()
                cell.fill.fore_color.rgb = BLUE
                run.font.color.rgb = WHITE
            elif r % 2 == 0:
                cell.fill.solid()
                cell.fill.fore_color.rgb = RGBColor(0xF2, 0xF7, 0xFC)
                run.font.color.rgb = RGBColor(0x33, 0x33, 0x33)
            else:
                cell.fill.solid()
                cell.fill.fore_color.rgb = WHITE
                run.font.color.rgb = RGBColor(0x33, 0x33, 0x33)

    # 底部：三参数协作关系
    note = ("Skill 三参数协作：skillDirectories 决定「扫描到什么」（注册池）→ "
            "skillMode 决定「怎么呈现给 LLM」（全文注入 or 按需搜索）→ "
            "skills 决定「实际启用哪些」（白名单过滤，disabledSkills 优先排除）。"
            "AgentCard=身份元数据，Workspace=文件沙箱，HarnessFactory.createDeepAgent "
            "完成最终组装并注入默认 Rail（SecurityRail 等）")
    add_box(slide, Inches(0.35), Inches(6.5), Inches(12.6), Inches(0.8),
            "关键理解：Skill 三参数协作关系", note,
            fill=RGBColor(0xFD, 0xEF, 0xD9), title_color=ORANGE, body_size=10, title_size=12)
    return slide


def build_slide7(prs):
    """Tool 开发方法页：以 CashflowReviewTool 为例，讲怎么开发一个 Tool。"""
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    add_title_bar(slide, "基于 DeepAgent 开发 Tool —— 以 CashflowReviewTool 为例",
                  "摘自 examples/risk_review_cashflow/tools/CashflowReviewTool.java（继承 Tool 基类，直连 GaussDB 取数）")

    # 左：开发四步法
    flow_text = (
        "Step 1  继承 Tool 基类\n"
        "  public final class CashflowReviewTool\n"
        "    extends Tool { … }\n"
        "  （com.openjiuwen.core.foundation.tool.Tool）\n"
        "\n"
        "Step 2  构造函数传入 ToolCard\n"
        "  public CashflowReviewTool(\n"
        "      GaussDbClient client) {\n"
        "    super(buildCard());   // ★ 说明书\n"
        "    this.client = client; // 依赖注入\n"
        "  }\n"
        "\n"
        "Step 3  覆写 invoke() 实现业务\n"
        "  invoke(inputs, kwargs)\n"
        "  取参 → 校验 → 取数 → 返回 Map\n"
        "\n"
        "Step 4  注册到 DeepAgent\n"
        "  agent.registerHarnessTool(\n"
        "    new CashflowReviewTool(dbClient));\n"
        "  → LLM 即可在 ReAct 循环中调用"
    )
    add_box(slide, Inches(0.35), Inches(1.3), Inches(3.55), Inches(5.1),
            "Tool 开发四步法", flow_text,
            fill=CODE_BG, title_color=BLUE, body_size=9.5, title_size=13)

    # 右上：ToolCard / JSON Schema 代码
    card_code = (
        "// ToolCard = 给 LLM 看的「工具说明书」\n"
        "private static ToolCard buildCard() {\n"
        "  // 入参 JSON Schema：LLM 据此生成调用参数\n"
        "  Map<String, Object> partyId = new LinkedHashMap<>();\n"
        "  partyId.put(\"type\", \"string\");\n"
        "  partyId.put(\"description\",\n"
        "      \"必填，客户号（关联 EMC 流水表主键 MAIN_CIS）\");\n"
        "  properties.put(\"party_id\", partyId);\n"
        "  properties.put(\"company_id\", companyId);\n"
        "  schema.put(\"type\", \"object\");\n"
        "  schema.put(\"properties\", properties);\n"
        "  schema.put(\"required\", List.of(\"party_id\"));\n"
        "\n"
        "  return ToolCard.builder()\n"
        "      .id(TOOL_ID)        // \"cashflow_review\"\n"
        "      .name(TOOL_ID)\n"
        "      .description(\"经营流水下滑场景评估：按 party_id \"\n"
        "          + \"查询 GaussDB EMC 经营性流水（最新 PT_DT）…\")\n"
        "      .inputParams(schema)   // ★ 参数说明书\n"
        "      .build();\n"
        "}"
    )
    add_box(slide, Inches(4.1), Inches(1.3), Inches(8.85), Inches(2.5),
            "Step 2 代码：ToolCard + 入参 JSON Schema（决定 LLM「何时调、怎么传」）",
            card_code, fill=CODE_BG, title_color=GREEN, body_size=9, title_size=12)

    # 右下：invoke 代码
    invoke_code = (
        "@Override\n"
        "public Object invoke(Map<String, Object> inputs, Map<String, Object> kwargs) {\n"
        "  String partyId = asText(inputs, \"party_id\");   // ① 取参 + trim\n"
        "  if (partyId.isBlank()) {                        // ② 必填二次校验\n"
        "    return errorPayload(\"party_id required\");\n"
        "  }\n"
        "  Map<String, String> flow;\n"
        "  try {                                           // ③ 业务取数\n"
        "    flow = client.getEmcFlowAnalysis(partyId);\n"
        "  } catch (SQLException e) {                      // ④ 失败不抛异常\n"
        "    return errorPayload(\"GAUSSDB_QUERY_FAILED: \" + e.getMessage());\n"
        "  }                                               //   返回 LLM 可读错误\n"
        "  Map<String, Object> payload = new LinkedHashMap<>();\n"
        "  payload.put(\"success\", true);                  // ⑤ 结构化返回\n"
        "  payload.put(\"operating_cashflow\", flow);       //   （与 Python 版对齐）\n"
        "  payload.put(\"assessment_summary\", buildSummary(flow));\n"
        "  return payload;  // → 进入对话历史，LLM 据此撰写复核意见\n"
        "}"
    )
    add_box(slide, Inches(4.1), Inches(3.95), Inches(8.85), Inches(2.45),
            "Step 3 代码：invoke() —— 校验 → 取数 → 结构化返回",
            invoke_code, fill=CODE_BG, title_color=ORANGE, body_size=9, title_size=12)

    # 底部：设计要点
    note = ("设计要点：① ToolCard 的 description 与参数 description 是给 LLM 看的「说明书」，"
            "决定模型何时调用、怎么传参；② required 声明必填 + invoke 内二次校验（双保险）；"
            "③ 失败不抛异常，返回 {success:false, mcp_error:…} 让 LLM 读懂失败原因并如实转述；"
            "④ 输出结构与原 Python 版逐字段对齐（success/total/operating_cashflow/…），保证语义行为一致；"
            "⑤ 依赖通过构造函数注入（GaussDbClient），工具类保持无状态；"
            "⑥ stream() 默认复用 invoke 结果即可")
    add_box(slide, Inches(0.35), Inches(6.55), Inches(12.6), Inches(0.8),
            "Tool 设计要点（LLM 视角）", note,
            fill=RGBColor(0xE8, 0xF5, 0xE9), title_color=GREEN, body_size=10, title_size=12)
    return slide


def build_slide8(prs):
    """Rail 开发方法页：以 SkillResultPrintRail 为例，讲怎么开发一个 Rail。"""
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    add_title_bar(slide, "基于 DeepAgent 开发 Rail —— 以 SkillResultPrintRail 为例",
                  "摘自 examples/risk_review_cashflow/rails/SkillResultPrintRail.java（工具调用切面：参数/结果落盘审计）")

    # 左：开发三步法
    flow_text = (
        "Step 1  继承 AgentRail 基类\n"
        "  public final class SkillResultPrintRail\n"
        "    extends AgentRail { … }\n"
        "  （com.openjiuwen.core.singleagent.rail.AgentRail）\n"
        "\n"
        "Step 2  按需覆写生命周期钩子\n"
        "  beforeToolCall(ctx)  // 调用前：记参数\n"
        "  afterToolCall(ctx)   // 调用后：记结果\n"
        "  （还有会话/子代理等其他钩子）\n"
        "\n"
        "Step 3  过滤工具类回调\n"
        "  if (!(ctx.getInputs() instanceof\n"
        "      ToolCallInputs inputs)) {\n"
        "    return;   // 非工具回调直接跳过\n"
        "  }\n"
        "  inputs.getToolName() / getToolArgs()\n"
        "  inputs.getToolResult()\n"
        "\n"
        "Step 4  注册到 DeepAgentConfig\n"
        "  .rails(List.of(new SkillResultPrintRail()))\n"
        "  → ensureInitialized 时挂上回调链"
    )
    add_box(slide, Inches(0.35), Inches(1.3), Inches(3.55), Inches(5.1),
            "Rail 开发四步法", flow_text,
            fill=CODE_BG, title_color=RGBColor(0x6A, 0x1B, 0x9A), body_size=9.5, title_size=13)

    # 右上：钩子实现代码
    hook_code = (
        "// 钩子 ①：工具调用前 —— 记录工具名 + 参数\n"
        "@Override\n"
        "public void beforeToolCall(AgentCallbackContext ctx) {\n"
        "  if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {\n"
        "    return;                       // 非工具回调跳过\n"
        "  }\n"
        "  appendLine(TAG + \"调用工具 \" + inputs.getToolName()\n"
        "      + \"，参数: \" + inputs.getToolArgs());\n"
        "}\n"
        "\n"
        "// 钩子 ②：工具调用后 —— 记录返回结果（超长截断）\n"
        "@Override\n"
        "public void afterToolCall(AgentCallbackContext ctx) {\n"
        "  if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {\n"
        "    return;\n"
        "  }\n"
        "  appendLine(TAG + \"工具 \" + inputs.getToolName()\n"
        "      + \" 返回结果:\\n\" + TAG + truncate(inputs.getToolResult()));\n"
        "}"
    )
    add_box(slide, Inches(4.1), Inches(1.3), Inches(8.85), Inches(2.55),
            "Step 2/3 代码：覆写钩子 + instanceof 过滤（框架 ToolTrackingRail 同款写法）",
            hook_code, fill=CODE_BG, title_color=RGBColor(0x6A, 0x1B, 0x9A),
            body_size=9, title_size=12)

    # 右下：落盘代码
    file_code = (
        "// 落盘：毫秒时间戳 + UTF-8 追加写本地日志文件\n"
        "private synchronized void appendLine(String message) {\n"
        "  String line = LocalDateTime.now().format(TIMESTAMP_FORMAT)\n"
        "      + \" \" + message + System.lineSeparator();\n"
        "  try {\n"
        "    Path parent = logFile.getParent();\n"
        "    if (parent != null) {\n"
        "      Files.createDirectories(parent);   // 父目录自动创建\n"
        "    }\n"
        "    Files.writeString(logFile, line, StandardCharsets.UTF_8,\n"
        "        StandardOpenOption.CREATE, StandardOpenOption.APPEND);\n"
        "  } catch (IOException e) {\n"
        "    // 降级：写失败仅报 stderr，绝不影响 Agent 主流程\n"
        "    System.err.println(TAG + \"写入调用记录失败: \" + logFile);\n"
        "  }\n"
        "}"
    )
    add_box(slide, Inches(4.1), Inches(4.0), Inches(8.85), Inches(2.4),
            "Step 3 代码：调用记录落盘（时间戳 + UTF-8 追加写）",
            file_code, fill=CODE_BG, title_color=BLUE, body_size=9, title_size=12)

    # 底部：设计要点
    note = ("设计要点：① Rail 是 AOP 切面，不侵入业务代码，加/删一行 .rails(…) 即插拔；"
            "② 钩子内不抛异常、不阻塞太久，失败一律降级（本例降级到 stderr）；"
            "③ 工具可能并行调用（maxParallelToolCalls），共享状态需加锁（本例 synchronized）；"
            "④ 可注册多个 Rail，按声明顺序形成回调链，框架还会自动注入 SecurityRail 等默认 Rail；"
            "⑤ ctx.getInputs() 是 Object，务必先 instanceof 过滤再取值")
    add_box(slide, Inches(0.35), Inches(6.55), Inches(12.6), Inches(0.8),
            "Rail 设计要点（切面视角）", note,
            fill=RGBColor(0xF3, 0xE5, 0xF5), title_color=RGBColor(0x6A, 0x1B, 0x9A),
            body_size=10, title_size=12)
    return slide


def build_slide9(prs):
    """Skill 开发方法页：以 fund_cashflow_review_skill_java 为例。"""
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    add_title_bar(slide, "基于 DeepAgent 开发 Skill —— 以 fund_cashflow_review_skill_java 为例",
                  "Skill = 一个目录 + SKILL.md（纯 Markdown、零代码），摘自 examples/risk_review_cashflow/skills/…/SKILL.md")

    # 左：开发步骤
    flow_text = (
        "Step 1  建目录\n"
        "  skills/fund_cashflow_review_skill_java/\n"
        "  └─ SKILL.md   （目录名 = skill 名）\n"
        "\n"
        "Step 2  写 front-matter 元数据\n"
        "  ---\n"
        "  name: <skill 名>\n"
        "  description: <一句话职责>\n"
        "  ---\n"
        "\n"
        "Step 3  写正文四要素\n"
        "  触发条件 / 工具调用契约 /\n"
        "  执行逻辑 / 返回字段\n"
        "\n"
        "Step 4  注册生效（三参数）\n"
        "  .skillDirectories(skills/ 父目录)\n"
        "  .skillMode(\"all\")   // 全文注入\n"
        "  .skills(List.of(\"…_java\"))\n"
        "\n"
        "Step 5  运行时生效链路\n"
        "  LLM 调 skill_tool → 注入 SKILL.md\n"
        "  → 按指引调用 cashflow_review"
    )
    add_box(slide, Inches(0.35), Inches(1.3), Inches(3.55), Inches(5.1),
            "Skill 开发五步法（零代码）", flow_text,
            fill=CODE_BG, title_color=GREEN, body_size=9.5, title_size=13)

    # 右上：SKILL.md 上半部分
    md_top = (
        "---\n"
        "name: fund_cashflow_review_skill_java\n"
        "description: 经营流水下滑场景评估（Java tool 版）\n"
        "---\n"
        "# fund_cashflow_review_skill_java\n"
        "\n"
        "## 触发条件        ← 什么时候用我\n"
        "一期 COT/结论含：经营流水下滑、负现金流、\n"
        "收入持续下降、长期无收入。\n"
        "\n"
        "## 工具调用        ← 调什么工具、传什么参数\n"
        "直接调用 Java 工具 `cashflow_review`：\n"
        "{\"party_id\": \"...\", \"company_id\": \"...\"}\n"
        "- party_id: 必填，客户号（MAIN_CIS）\n"
        "- company_id: 企业ID（可选，原样透传）"
    )
    add_box(slide, Inches(4.1), Inches(1.3), Inches(8.85), Inches(2.55),
            "SKILL.md（上）：front-matter + 触发条件 + 工具调用契约",
            md_top, fill=CODE_BG, title_color=GREEN, body_size=9.5, title_size=12)

    # 右下：SKILL.md 下半部分
    md_bottom = (
        "## 逻辑            ← 怎么干（写给 LLM 的执行步骤）\n"
        "1. 用 party_id 关联 emc.emc_llm_flow_analysis.MAIN_CIS\n"
        "2. 按 PT_DT 降序取最新一行\n"
        "3. 返回经营性四字段（字符串原样）+ MAIN_CIS、PT_DT\n"
        "4. 无数据时 operating_cashflow 为 null，摘要提示未取到\n"
        "\n"
        "## 返回字段        ← 拿到结果后怎么用\n"
        "- operating_cashflow: 6 个事实字段\n"
        "- assessment_summary: 仅陈述事实，不做「是否下滑」\n"
        "  布尔判断（交主复核）"
    )
    add_box(slide, Inches(4.1), Inches(4.0), Inches(8.85), Inches(2.4),
            "SKILL.md（下）：执行逻辑 + 返回字段与行为约束",
            md_bottom, fill=CODE_BG, title_color=ORANGE, body_size=9.5, title_size=12)

    # 底部：设计要点
    note = ("设计要点：① description 是路由关键——skillMode=auto_list 时 LLM 靠它判断是否检索/启用本 skill；"
            "② 指令要可执行：写清「何时触发、调哪个工具、参数从哪来、结果怎么用」，不要写成抽象描述；"
            "③ 输出契约与工具真实返回结构逐字段对齐，避免 LLM 误解数据；"
            "④ 行为约束写进正文（仅陈述事实、不做布尔判断）即可约束下游输出；"
            "⑤ skillMode=all 全文注入（确定性高、耗 token），auto_list 按需搜索（省 token），按 skill 数量权衡")
    add_box(slide, Inches(0.35), Inches(6.55), Inches(12.6), Inches(0.8),
            "Skill 设计要点（提示词工程视角）", note,
            fill=RGBColor(0xE8, 0xF5, 0xE9), title_color=GREEN, body_size=10, title_size=12)
    return slide


def main():
    prs = Presentation()
    prs.slide_width = Inches(13.333)
    prs.slide_height = Inches(7.5)
    build_slide1(prs)
    build_slide2(prs)
    build_slide3(prs)
    build_slide4(prs)
    build_slide5(prs)
    build_slide6(prs)
    build_slide7(prs)
    build_slide8(prs)
    build_slide9(prs)
    out = r"D:\work\code-proj\openjiuwen-fin\deep-agent-demo\730\agent-core-java\examples\risk_review_cashflow\training\DeepAgent开发培训.pptx"
    prs.save(out)
    print("saved:", out)


if __name__ == "__main__":
    main()

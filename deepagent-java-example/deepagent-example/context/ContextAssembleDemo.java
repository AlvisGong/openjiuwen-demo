/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package myexample.ctx;

import com.openjiuwen.core.foundation.tool.function.AnnotatedToolFactory;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.prompts.PromptSection;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.controller.schema.ControllerOutputPayload;
import com.openjiuwen.core.controller.schema.DataFrame;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.rails.ContextAssembleRail;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import examples.utils.SharedExampleApiConfigLoader;
import myexample.tool.MathTools;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * ContextAssembleDemo — 基于 DeepAgent 使用 ContextAssembleRail 的全自动验证样例。
 *
 * <p>本示例自动验证 {@link ContextAssembleRail} 的三大核心功能，无需用户输入：
 * <ul>
 *   <li><b>场景1: Context Section</b> — 询问产品价格，验证 context 文件内容被注入并回答</li>
 *   <li><b>场景2: Tools Section</b> — 数学计算，验证工具列表被注入并被 LLM 调用</li>
 *   <li><b>场景3: Workspace Section</b> — 询问工作区文件结构，验证目录树被注入</li>
 * </ul>
 *
 * <h3>ContextAssembleRail 工作机制</h3>
 * <pre>
 *   用户提问 → beforeModelCall:
 *     1. buildWorkspaceSection(language) → 扫描 workspace 目录树 → 注入 "workspace" prompt section
 *     2. buildToolsSection(language, ctx) → 收集可用工具 → 注入 "tools" prompt section
 *     3. buildContextSection(language) → 读取 context/ 目录文件 → 注入 "context" prompt section
 *     4. injectSystemMessages → 将所有 section 内容作为 SystemMessage 注入
 *   → LLM 接收到完整的 workspace + tools + context 信息
 *   → Agent 能够基于上下文文件内容回答问题
 * </pre>
 *
 * <h3>运行方式</h3>
 * <pre>{@code
 *   mvn compile exec:java \
 *     -Dexec.mainClass=myexample.ctx.ContextAssembleDemo \
 *     -Dopenjiuwen.example.config=examples/apiconfig.json
 * }</pre>
 */
public final class ContextAssembleDemo {

    private static final Path WORKSPACE_ROOT = Path.of("examples/myexample/ctx/workspace").toAbsolutePath().normalize();
    private static DeepAgent agent;
    private static ContextAssembleRail contextAssembleRail;

    /** 三个自动验证场景 */
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario(
                    "Context Section 验证",
                    "请告诉我产品的价格信息",
                    "验证: LLM 能从 context/product_spec.md 中读取价格并准确回答"
            ),
            new Scenario(
                    "Tools Section 验证",
                    "请用计算工具计算 123 加 456 的结果",
                    "验证: LLM 能从 tools section 中找到 add 工具并正确调用"
            ),
            new Scenario(
                    "Workspace Section 验证",
                    "我的工作区里有哪些文件和目录？请列出你看到的工作区结构",
                    "验证: LLM 能从 workspace section 中读取目录树并准确描述"
            )
    );

    static {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    private ContextAssembleDemo() {
    }

    // =========================================================================
    // 入口 — 全自动验证，无需用户输入
    // =========================================================================

    public static void main(String[] args) {
        // 1. 确保 context 目录和文件存在
        ensureContextFiles();

        // 2. 初始化 Runner
        Runner.start();

        // 3. 构建 DeepAgent
        agent = buildAgent();

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  ContextAssembleDemo — 全自动验证 ContextAssembleRail  ║");
        System.out.println("║  场景1: context section → 产品价格信息                  ║");
        System.out.println("║  场景2: tools section   → 数学工具调用                  ║");
        System.out.println("║  场景3: workspace section → 目录结构感知                ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        // 4. 依次自动执行三个场景
        try {
            for (int i = 0; i < SCENARIOS.size(); i++) {
                Scenario scenario = SCENARIOS.get(i);
                System.out.println("╔══════════════════════════════════════════════════════════╗");
                System.out.println("║  场景" + (i + 1) + ": " + scenario.name);
                System.out.println("║  " + scenario.description);
                System.out.println("╚══════════════════════════════════════════════════════════╝");
                System.out.println();

                // 注入前状态
                System.out.println("[Rail 注入前]");
                printRailStatus();
                System.out.println();

                System.out.println("[Query] " + scenario.query);
                System.out.println();

                // 执行查询
                String answer = runQuery(scenario.query);
                System.out.println();

                // 注入后状态
                System.out.println("[Rail 注入后]");
                printRailStatus();
                System.out.println();

                System.out.println("[Answer] " + answer);
                System.out.println();
                System.out.println("──────────────────────────────────────────────────────────");
                System.out.println();
            }

            System.out.println("╔══════════════════════════════════════════════════════════╗");
            System.out.println("║  全部 3 个场景验证完成                                  ║");
            System.out.println("╚══════════════════════════════════════════════════════════╝");
        } finally {
            Runner.stop();
        }
    }

    // =========================================================================
    // 执行单次查询
    // =========================================================================

    private static String runQuery(String query) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("query", query);

        StringBuilder answerBuilder = new StringBuilder();
        Iterator<Object> stream = agent.stream(inputs, List.of(StreamMode.OUTPUT));

        while (stream.hasNext()) {
            Object item = stream.next();
            if (!(item instanceof OutputSchema)) continue;
            OutputSchema chunk = (OutputSchema) item;
            Object payload = chunk.getPayload();
            String type = chunk.getType();

            if (payload instanceof ControllerOutputPayload) {
                ControllerOutputPayload ctrl = (ControllerOutputPayload) payload;
                String text = extractTextFromDataFrames(ctrl.getData());
                if (!text.isBlank()) {
                    answerBuilder.append(text);
                }
                continue;
            }

            switch (type) {
                case "answer": {
                    String t = extractDelta(payload);
                    if (!t.isEmpty()) answerBuilder.append(t);
                    break;
                }
                case "tool_result": {
                    answerBuilder.append("[tool:" + extractToolName(payload) + "] ");
                    answerBuilder.append(extractToolResult(payload));
                    answerBuilder.append("\n");
                    break;
                }
                case "error":
                    answerBuilder.append("[error] " + extractDisplayText(payload));
                    break;
                default:
                    break;
            }
        }

        return answerBuilder.toString().trim();
    }

    // =========================================================================
    // 确保 context 文件存在
    // =========================================================================

    private static void ensureContextFiles() {
        try {
            Path contextDir = WORKSPACE_ROOT.resolve("context");
            Files.createDirectories(contextDir);

            Path productSpec = contextDir.resolve("product_spec.md");
            if (!Files.exists(productSpec)) {
                Files.writeString(productSpec, """
                        # 产品规格说明书

                        ## 产品名称
                        智能客服助手 v2.0

                        ## 核心功能
                        1. 多轮对话管理 — 支持上下文关联的连续对话
                        2. 知识库检索 — 从预设知识库中检索相关信息
                        3. 工具调用 — 通过数学计算等工具处理精确计算需求
                        4. 上下文组装 — 自动将工作区、工具、文档信息注入LLM

                        ## 技术参数
                        - 最大对话轮次: 20
                        - 支持语言: 中文/英文
                        - 上下文文件上限: 8个
                        - 工作区扫描深度: 2层

                        ## 价格信息
                        - 基础版: ¥500/月
                        - 专业版: ¥1200/月
                        - 企业版: ¥3000/月
                        """);
            }

            Path faq = contextDir.resolve("faq.md");
            if (!Files.exists(faq)) {
                Files.writeString(faq, """
                        # 常见问题 FAQ

                        ## Q: 如何配置上下文文件？
                        A: 将需要注入LLM的文档放在 workspace/context/ 目录下，
                           ContextAssembleRail 会自动读取并注入。

                        ## Q: 上下文文件的大小限制？
                        A: 每个文件最多读取4000字符，超过部分会被截断。
                           最多读取8个上下文文件。

                        ## Q: 如何切换语言？
                        A: 在 DeepAgentConfig 中设置 language 参数，
                           "cn" 为中文，"en" 为英文。Section标题会自动切换。

                        ## Q: 工具列表如何生成？
                        A: ContextAssembleRail 从 Agent 的 AbilityManager 中
                           自动获取所有已注册工具的名称和描述。

                        ## Q: 工作区扫描规则？
                        A: 扫描 workspace root 下2层目录结构，
                           最多列出80个条目，区分文件和目录。
                        """);
            }

            Path teamInfo = contextDir.resolve("team_info.txt");
            if (!Files.exists(teamInfo)) {
                Files.writeString(teamInfo, """
                        团队信息
                        =========

                        项目组: DeepAgent Java SDK 开发组
                        负责人: 张三
                        成员数: 8人

                        当前迭代目标:
                        - 完成 ContextAssembleRail 的 Java parity 实现
                        - 验证 workspace/tools/context 三个 section 的注入功能
                        - 编写完整的 demo 样例和单元测试

                        Sprint 周期: 2周
                        代码审查: 每次提交需通过 codecheck
                        """);
            }

            Path srcDir = WORKSPACE_ROOT.resolve("src");
            Files.createDirectories(srcDir);
            Path mainJava = srcDir.resolve("Main.java");
            if (!Files.exists(mainJava)) {
                Files.writeString(mainJava, "// Main entry point placeholder\npublic class Main {}\n");
            }

            Path configDir = WORKSPACE_ROOT.resolve("config");
            Files.createDirectories(configDir);
            Path appConfig = configDir.resolve("app.json");
            if (!Files.exists(appConfig)) {
                Files.writeString(appConfig, "{\"name\":\"demo\",\"version\":\"1.0\"}");
            }

            System.out.println("[INFO] Context 文件已创建/确认: " + contextDir);
        } catch (Exception e) {
            System.err.println("[WARN] 创建 context 文件失败: " + e.getMessage());
        }
    }

    // =========================================================================
    // Agent 构建 — 配置 ContextAssembleRail
    // =========================================================================

    private static DeepAgent buildAgent() {
        List<Object> tools = new ArrayList<>(AnnotatedToolFactory.scan(new MathTools()));
        contextAssembleRail = new ContextAssembleRail();

        List<Object> rails = new ArrayList<>();
        rails.add(contextAssembleRail);

        AgentCard card = AgentCard.builder()
                .id("ctx_assemble_demo")
                .name("ctx_assemble_demo")
                .description("ContextAssembleDemo — 上下文组装 Rail 演示")
                .build();

        Map<String, String> llmConfig = SharedExampleApiConfigLoader.load();

        DeepAgentConfig config = DeepAgentConfig.builder()
                .enableTaskLoop(true)
                .enableTaskPlanning(true)
                .systemPrompt("""
                        你是一个智能助手，拥有以下能力：
                        1. 数学计算 — 可以通过 add/subtract/multiply/divide 工具进行精确计算
                        2. 上下文理解 — 你能看到工作区目录结构、可用工具列表和上下文文件内容
                        3. 信息整合 — 基于上下文文件中的信息回答用户问题

                        注意：你的上下文中已经包含了以下信息（由 ContextAssembleRail 自动注入）：
                        - workspace section: 工作区目录树
                        - tools section: 可用工具列表
                        - context section: context/ 目录下的文件内容

                        当用户问关于产品、团队、FAQ 等问题时，请参考 context section 中的内容回答。
                        当用户问数学问题时，使用工具进行计算。
                        """)
                .maxIterations(20)
                .language("cn")
                .model(Map.of("model", llmConfig.getOrDefault("MODEL_NAME", "glm-5")))
                .backend(buildBackendConfig(llmConfig))
                .restrictToWorkDir(false)
                .tools(tools)
                .rails(rails)
                .build();

        Workspace workspace = Workspace.builder()
                .rootPath(WORKSPACE_ROOT.toString())
                .language("cn")
                .build();

        DeepAgent agent = HarnessFactory.createDeepAgent(card, config, workspace);

        System.out.println("[INFO] Agent 构建完成");
        System.out.println("[INFO] ContextAssembleRail 已注册 (priority=85)");
        System.out.println("[INFO] Workspace root: " + WORKSPACE_ROOT);
        System.out.println();

        return agent;
    }

    private static Map<String, Object> buildBackendConfig(Map<String, String> llmConfig) {
        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("client_provider", llmConfig.getOrDefault("MODEL_PROVIDER", ""));
        backend.put("api_key", llmConfig.getOrDefault("API_KEY", ""));
        backend.put("api_base", llmConfig.getOrDefault("API_BASE", ""));
        backend.put("verify_ssl", Boolean.parseBoolean(llmConfig.getOrDefault("LLM_SSL_VERIFY", "false")));
        return backend;
    }

    // =========================================================================
    // Rail 状态查看
    // =========================================================================

    private static void printRailStatus() {
        boolean hasSections = contextAssembleRail.hasContextSections();
        System.out.println("  ContextAssembleRail sections: " + (hasSections ? "已注入" : "未注入"));

        if (agent != null && agent.getAgent() != null) {
            var promptBuilder = agent.getAgent().getPromptBuilder();
            String[] sectionNames = {"workspace", "tools", "context"};
            for (String name : sectionNames) {
                boolean exists = promptBuilder.hasSection(name);
                System.out.println("    " + name + ": " + (exists ? "存在" : "不存在"));
                if (exists) {
                    PromptSection section = promptBuilder.getSection(name);
                    System.out.println("      priority: " + section.getPriority());
                    System.out.println("      preview:  " + getSectionContentPreview(section));
                }
            }
        }
    }

    private static String getSectionContentPreview(PromptSection section) {
        Map<String, String> contentMap = section.getContent();
        if (contentMap == null || contentMap.isEmpty()) return "(empty)";
        for (Map.Entry<String, String> entry : contentMap.entrySet()) {
            String value = entry.getValue();
            if (value == null) continue;
            return value.length() > 120 ? value.substring(0, 120) + "..." : value;
        }
        return "(empty)";
    }

    // =========================================================================
    // 流式输出文本提取工具
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static String extractTextFromDataFrames(List<DataFrame> dataList) {
        if (dataList == null || dataList.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (DataFrame df : dataList) {
            if (df instanceof DataFrame.TextDataFrame) {
                DataFrame.TextDataFrame t = (DataFrame.TextDataFrame) df;
                if (t.text() != null) sb.append(t.text());
            } else if (df instanceof DataFrame.JsonDataFrame) {
                DataFrame.JsonDataFrame j = (DataFrame.JsonDataFrame) df;
                if (j.data() != null) sb.append(extractModelContent(j.data()));
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String extractModelContent(Map<String, Object> map) {
        for (String key : List.of("content", "delta", "reasoning_content")) {
            Object v = map.get(key);
            if (v instanceof String && !((String) v).isBlank()) return (String) v;
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static String extractDelta(Object payload) {
        if (payload instanceof String) return (String) payload;
        if (payload instanceof Map) return extractModelContent((Map<String, Object>) payload);
        return "";
    }

    @SuppressWarnings("unchecked")
    private static String extractToolName(Object payload) {
        if (payload instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) payload;
            Object name = m.get("tool_name");
            if (name != null) return String.valueOf(name);
        }
        return "unknown";
    }

    @SuppressWarnings("unchecked")
    private static String extractToolResult(Object payload) {
        if (payload instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) payload;
            for (String key : List.of("result", "output")) {
                Object v = m.get(key);
                if (v instanceof String) return (String) v;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static String extractDisplayText(Object payload) {
        if (payload == null) return "";
        if (payload instanceof String) return (String) payload;
        if (payload instanceof Map) return extractModelContent((Map<String, Object>) payload);
        return "";
    }

    // =========================================================================
    // 场景定义
    // =========================================================================

    private static final class Scenario {
        final String name;
        final String query;
        final String description;

        Scenario(String name, String query, String description) {
            this.name = name;
            this.query = query;
            this.description = description;
        }
    }
}

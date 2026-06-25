/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package myexample.skill;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.rails.SysOperationRail;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import examples.utils.SharedExampleApiConfigLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SkillExample — 基于 DeepAgent 使用 code-review Skill 进行代码评审的样例。
 *
 * <p>本示例演示如何在 DeepAgent 中注册和使用 Skill 进行代码评审：
 * <ul>
 *   <li>通过 DeepAgentConfig.skillDirectories 指定 Skill 目录</li>
 *   <li>框架自动扫描目录下的 SKILL.md 文件并注册 Skill</li>
 *   <li>Agent 运行时会获得 list_skill 和 skill_tool 两个工具</li>
 *   <li>添加 SysOperationRail 使 Agent 可以通过 readFile 读取源代码文件</li>
 *   <li>Agent 自主调用 skill_tool 阅读 code-review SKILL.md，按评审流程执行</li>
 * </ul>
 *
 * <h3>运行方式</h3>
 * <pre>{@code
 *   # 评审示例代码（默认）
 *   java myexample.skill.SkillExample
 *
 *   # 评审指定文件
 *   java myexample.skill.SkillExample --file path/to/Source.java
 *
 *   # 自定义查询
 *   java myexample.skill.SkillExample --query "请评审以下代码的安全性..."
 * }</pre>
 */
public final class SkillExample {

    private static final Path WORKSPACE_ROOT = Path.of(".").toAbsolutePath().normalize();

    private SkillExample() {
    }

    public static void main(String[] args) {
        try {
            // 1. 初始化 Runner
            Runner.start();

            // 2. 构建 DeepAgent（带 Skill + SysOperation 配置）
            DeepAgent agent = buildAgent();

            // 3. 添加 sysOperation 工具，使 Agent 能通过 readFile 读取源代码
            addSysOpTools(agent);

            // 4. 解析命令行参数，构建查询
            String query = resolveQuery(args);

            System.out.println("========== Code Review Skill Example ==========");
            System.out.println("Query: " + query);
            System.out.println();

            // 5. 执行查询
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("query", query);

            Object result = agent.run(inputs);

            System.out.println("========== Result ==========");
            System.out.println(result);
        } finally {
            Runner.stop();
        }
    }

    // =========================================================================
    // 查询构建
    // =========================================================================

    /**
     * 从命令行参数解析查询内容。
     *
     * <p>支持两种模式：
     * <ul>
     *   <li>{@code --file <path>} — 评审指定源代码文件</li>
     *   <li>{@code --query <text>} — 自定义评审查询</li>
     * </ul>
     *
     * <p>默认评审示例代码片段。
     */
    private static String resolveQuery(String[] args) {
        String filePath = null;
        String customQuery = null;

        for (int i = 0; i < args.length; i++) {
            if ("--file".equals(args[i]) && i + 1 < args.length) {
                filePath = args[++i];
            } else if ("--query".equals(args[i]) && i + 1 < args.length) {
                customQuery = args[++i];
            }
        }

        if (customQuery != null) {
            return customQuery;
        }

        if (filePath != null) {
            Path absPath = Path.of(filePath).toAbsolutePath().normalize();
            return "请评审以下源代码文件，按照 code-review 技能的评审维度检查代码质量、潜在Bug、性能、安全和最佳实践："
                    + absPath;
        }

        // 默认：评审一段包含典型问题的 Java 代码片段
        return "请评审以下 Java 代码，检查代码质量、潜在Bug、性能、安全和最佳实践：\n"
                + "```java\n"
                + "public class UserService {\n"
                + "    private static Connection conn;\n"
                + "\n"
                + "    public User getUser(String userId) {\n"
                + "        String sql = \"SELECT * FROM users WHERE id = \" + userId;\n"
                + "        Statement stmt = conn.createStatement();\n"
                + "        ResultSet rs = stmt.executeQuery(sql);\n"
                + "        if (rs.next()) {\n"
                + "            return new User(rs.getString(\"name\"), rs.getString(\"email\"));\n"
                + "        }\n"
                + "        return null;\n"
                + "    }\n"
                + "\n"
                + "    public List<User> getAllUsers() {\n"
                + "        List<User> users = new ArrayList<>();\n"
                + "        for (int i = 0; i < 10000; i++) {\n"
                + "            User user = getUser(String.valueOf(i));\n"
                + "            if (user != null) {\n"
                + "                users.add(user);\n"
                + "            }\n"
                + "        }\n"
                + "        return users;\n"
                + "    }\n"
                + "}\n"
                + "```\n";
    }

    // =========================================================================
    // Agent 构建
    // =========================================================================

    private static DeepAgent buildAgent() {
        // LLM 配置
        Map<String, String> llmConfig = SharedExampleApiConfigLoader.load();

        // Skill 目录路径（指向 code-review Skill）
        Path skillsDir = Path.of("examples", "myexample", "skill", "code-review")
                .toAbsolutePath().normalize();

        String systemPrompt = """
                你是一个代码评审助手，配备了 code-review 技能。
                当用户请求代码评审时，请按以下步骤操作：
                1. 调用 list_skill 查看可用技能
                2. 调用 skill_tool 阅读 code-review 的 SKILL.md，了解评审维度和流程
                3. 按照技能说明中的评审维度（代码质量、潜在Bug、性能、安全、最佳实践）逐项检查
                4. 输出评审报告，按严重程度分类（Critical/Warning/Suggestion）
                """;

        AgentCard card = AgentCard.builder()
                .id("code_review_agent")
                .name("code_review_agent")
                .description("代码评审 Agent — 使用 code-review Skill 审查源代码")
                .build();

        // 构建 rails 列表，包含 SysOperationRail 使 Agent 可以读取文件
        List<Object> rails = new ArrayList<>();
        rails.add(new SysOperationRail());

        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(systemPrompt)
                .maxIterations(20)
                .language("cn")
                .enableTaskLoop(true)
                .enableTaskPlanning(true)
                // 关键配置：指定 Skill 目录，框架会自动添加 SkillUseRail
                .skillDirectories(List.of(skillsDir.toString()))
                .skillMode("all")
                // 添加 SysOperationRail，使 Agent 可以使用 readFile 等文件操作工具
                .rails(rails)
                .model(buildModelConfig(llmConfig))
                .backend(buildBackendConfig(llmConfig))
                .restrictToWorkDir(false)
                .build();

        Workspace workspace = Workspace.builder()
                .rootPath(WORKSPACE_ROOT.toString())
                .language("cn")
                .build();

        System.out.println("[INFO] Skill directory: " + skillsDir);
        return HarnessFactory.createDeepAgent(card, config, workspace);
    }

    // =========================================================================
    // SysOperation 工具注册
    // =========================================================================

    /**
     * 为 Agent 注册 sysOperation 文件系统工具（readFile），
     * 使 Agent 可以读取源代码文件进行代码评审。
     */
    private static void addSysOpTools(DeepAgent agent) {
        String sysOpId = agent.getCard().getName() + "_" + agent.getCard().getId();

        addSysOpTool(agent, sysOpId, "fs", "readFile");
        addSysOpTool(agent, sysOpId, "fs", "writeFile");
        addSysOpTool(agent, sysOpId, "fs", "listDir");
    }

    private static void addSysOpTool(DeepAgent agent, String sysOperationId,
                                      String operationName, String toolName) {
        Object toolCard = Runner.resourceMgr().getSysOpToolCards(
                sysOperationId, operationName, toolName);
        if (toolCard != null) {
            agent.getAgent().getAbilityManager().add(toolCard);
        }
    }

    // =========================================================================
    // LLM 配置构建
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildModelConfig(Map<String, String> llmConfig) {
        Map<String, Object> model = (Map<String, Object>) (Map<?, ?>) new LinkedHashMap<>();
        model.put("model", llmConfig.getOrDefault("MODEL_NAME", "glm-5"));
        return model;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildBackendConfig(Map<String, String> llmConfig) {
        Map<String, Object> backend = (Map<String, Object>) (Map<?, ?>) new LinkedHashMap<>();
        backend.put("client_provider", llmConfig.getOrDefault("MODEL_PROVIDER", ""));
        backend.put("api_key", llmConfig.getOrDefault("API_KEY", ""));
        backend.put("api_base", llmConfig.getOrDefault("API_BASE", ""));
        backend.put("verify_ssl", Boolean.parseBoolean(llmConfig.getOrDefault("LLM_SSL_VERIFY", "false")));
        return backend;
    }
}

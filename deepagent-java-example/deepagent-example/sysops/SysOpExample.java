/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package myexample.sysop;

import com.openjiuwen.core.runner.Runner;
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
 * SysOpExample — 基于 DeepAgent + SysOperationRail 的文件查询样例。
 *
 * <p>本示例演示如何配置 SysOperationRail 使 Agent 可以读取和浏览文件：
 * <ul>
 *   <li>添加 SysOperationRail 使 Agent 获得 sysOperation 工具</li>
 *   <li>手动注册 fs.readFile、fs.listDir 等工具卡片</li>
 *   <li>Agent 自主调用 readFile/listDir 查询文件内容</li>
 * </ul>
 *
 * <h3>运行方式</h3>
 * <pre>{@code
 *   # 默认查询 pom.xml 文件内容
 *   java myexample.sysop.SysOpExample
 *
 *   # 查询指定文件
 *   java myexample.sysop.SysOpExample --file src/main/java/com/openjiuwen/core/sysop/SysOperation.java
 *
 *   # 列出目录
 *   java myexample.sysop.SysOpExample --query "请列出当前目录下的文件结构"
 * }</pre>
 *
 * <h3>文件访问范围说明</h3>
 * <p>SysOperationRail 的文件操作受 LocalWorkConfig 控制：
 * <ul>
 *   <li>{@code restrictToWorkDir=false} — 限制在工作目录(workspace根)内</li>
 *   <li>{@code restrictToWorkDir=true}  — 限制在 sandboxRoots(workspace + projectRoot 或自定义)内</li>
 * </ul>
 * <p>两种配置下均无法访问 /root 等外部目录。若需扩展范围，请配置 LocalWorkConfig.sandboxRoot。
 */
public final class SysOpExample {

    private static final Path WORKSPACE_ROOT = Path.of(".").toAbsolutePath().normalize();

    private SysOpExample() {
    }

    public static void main(String[] args) {
        try {
            // 1. 初始化 Runner
            Runner.start();

            // 2. 构建 DeepAgent（带 SysOperationRail 配置）
            DeepAgent agent = buildAgent();

            // 3. 注册 fs 工具卡片
            addFsTools(agent);

            // 4. 解析命令行参数
            String query = resolveQuery(args);

            System.out.println("========== SysOp File Query Example ==========");
            System.out.println("Workspace: " + WORKSPACE_ROOT);
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
            return "请读取以下文件的内容并总结其功能：" + absPath;
        }

        // 默认：读取 pom.xml
//        return "请读取 pom.xml 文件，简要说明这个项目的依赖和构建配置。";
//        return "读取文件C:\\Users\\HW\\.claude.json";
        return "使用 searchFiles工具 查询文件C:\\\\Users\\\\HW\\\\.claude.json";
    }


    // =========================================================================
    // Agent 构建
    // =========================================================================

    private static DeepAgent buildAgent() {
        Map<String, String> llmConfig = SharedExampleApiConfigLoader.load();

        String systemPrompt = """
                你是一个文件查询助手，可以读取文件和列出目录。
                当用户请求查询文件时，请按以下步骤操作：
                1. 如果需要浏览目录，调用 listDir 列出文件
                2. 如果需要查看文件内容，调用 readFile 读取
                3. 如果需要查找文件，调用 searchFiles 工具
                4. 根据文件内容回答用户的问题
                """;

        AgentCard card = AgentCard.builder()
                .id("sysop_agent")
                .name("sysop_agent")
                .description("文件查询 Agent — 使用 SysOperationRail 读取文件")
                .build();

        List<Object> rails = new ArrayList<>();
        rails.add(new SysOperationRail());

        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(systemPrompt)
                .maxIterations(10)
                .language("cn")
                .enableTaskLoop(true)
                // SysOperationRail：使 Agent 可以使用 fs 工具
                .rails(rails)
                .model(buildModelConfig(llmConfig))
                .backend(buildBackendConfig(llmConfig))
                // restrictToWorkDir=false: 文件操作限制在 workspace 根目录内
                // restrictToWorkDir=true:  文件操作限制在 sandboxRoots 内
                .restrictToWorkDir(false)
                .build();

        Workspace workspace = Workspace.builder()
                .rootPath(WORKSPACE_ROOT.toString())
                .language("cn")
                .build();

        return HarnessFactory.createDeepAgent(card, config, workspace);
    }

    // =========================================================================
    // SysOperation fs 工具注册
    // =========================================================================

    /**
     * 为 Agent 注册 sysOperation 文件系统工具卡片。
     * SysOperationRail 只负责创建 SysOperation 实例，
     * 具体的工具卡片（readFile/listDir 等）需要手动注册到 Agent 的 AbilityManager。
     */
    private static void addFsTools(DeepAgent agent) {
        String sysOpId = agent.getCard().getName() + "_" + agent.getCard().getId();

        addSysOpTool(agent, sysOpId, "fs", "readFile");
        addSysOpTool(agent, sysOpId, "fs", "listDir");
        addSysOpTool(agent, sysOpId, "fs", "searchFiles");
    }

    private static void addSysOpTool(DeepAgent agent, String sysOperationId,
                                      String operationName, String toolName) {
        Object toolCard = Runner.resourceMgr().getSysOpToolCards(
                sysOperationId, operationName, toolName);
        if (toolCard != null) {
            agent.getAgent().getAbilityManager().add(toolCard);
            System.out.println("[INFO] Registered tool: " + operationName + "." + toolName);
        } else {
            System.out.println("[WARN] Tool not found: " + operationName + "." + toolName);
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

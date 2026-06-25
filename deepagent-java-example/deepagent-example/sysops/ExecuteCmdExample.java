/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package myexample.sysop;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.sysop.OperationMode;
import com.openjiuwen.core.sysop.SysOperationCard;
import com.openjiuwen.core.sysop.config.LocalWorkConfig;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.rails.SysOperationRail;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import examples.utils.SharedExampleApiConfigLoader;

import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ExecuteCmdExample — 使用 DeepAgent + SysOperationRail 调用 shell 脚本的样例。
 *
 * <p>本示例演示如何配置 SysOperationRail 使 Agent 可以通过 executeCmd 工具调用 shell 脚本：
 * <ul>
 *   <li>添加 SysOperationRail 使 Agent 获得 sysOperation 工具</li>
 *   <li>注册 shell.executeCmd 工具卡片</li>
 *   <li>注册 fs.readFile 工具卡片（用于读取脚本和结果文件）</li>
 *   <li>Agent 自主调用 executeCmd 执行 demo_shell_script.sh</li>
 * </ul>
 *
 * <h3>前置条件</h3>
 * <ul>
 *   <li>确保 bash 可用（Windows 推荐安装 Git Bash 或 WSL）</li>
 *   <li>确保 demo_shell_script.sh 位于 examples/myexample/sysop/ 目录下</li>
 * </ul>
 *
 * <h3>运行方式</h3>
 * <pre>{@code
 *   # 默认：执行 demo_shell_script.sh 并输出结果
 *   java myexample.sysop.ExecuteCmdExample
 *
 *   # 自定义命令
 *   java myexample.sysop.ExecuteCmdExample --cmd "echo hello world"
 *
 *   # 指定脚本路径
 *   java myexample.sysop.ExecuteCmdExample --script path/to/your_script.sh
 * }</pre>
 */
public final class ExecuteCmdExample {

    private static final Path WORKSPACE_ROOT = Path.of(".").toAbsolutePath().normalize();
    private static final Path SCRIPT_PATH = Path.of("examples/myexample/sysop/demo_shell_script.sh")
            .toAbsolutePath().normalize();

    private ExecuteCmdExample() {
    }

    public static void main(String[] args) {
        fixConsoleEncoding();
        try {
            // 1. 初始化 Runner
            Runner.start();

            // 2. 预注册 SysOperationCard（设置 shellAllowlist 包含 bash/sh，
            //    默认白名单不含 bash，会导致脚本执行被拒绝）
            registerSysOperationCard();

            // 3. 构建 DeepAgent（带 SysOperationRail 配置）
            DeepAgent agent = buildAgent();

            // 4. 注册 sysOperation 工具卡片
            addTools(agent);

            // 5. 解析命令行参数，构建查询
            String query = resolveQuery(args);

            System.out.println("========== ExecuteCmd Shell Script Example ==========");
            System.out.println("Workspace: " + WORKSPACE_ROOT);
            System.out.println("Script: " + SCRIPT_PATH);
            System.out.println("Query: " + query);
            System.out.println();

            // 6. 执行查询
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
    // 控制台编码修复
    // =========================================================================

    /**
     * 修复 Windows 中文控制台乱码问题。
     * Java 18+ 在 Windows 上 stdout.encoding 默认为 GBK（匹配 native.encoding），
     * 而 Git Bash、IDE 终端等期望 UTF-8 输出，导致 System.out.println 中文乱码。
     * 此方法将 System.out 重配置为 UTF-8 编码，使输出与 bash/IDE 终端一致。
     * <p>
     * 若在 Windows cmd（code page 936）中运行且出现乱码，请先执行 chcp 65001。
     */
    private static void fixConsoleEncoding() {
        String stdoutEncoding = System.getProperty("stdout.encoding", "");
        // 当 stdout.encoding 为 GBK 时，重配置为 UTF-8 以匹配 bash/IDE 终端
        if ("GBK".equalsIgnoreCase(stdoutEncoding)) {
            try {
                System.setOut(new PrintStream(System.out, true, "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                // UTF-8 不可用时忽略
            }
        }
    }

    // =========================================================================
    // SysOperation 预注册（设置白名单）
    // =========================================================================

    /**
     * 预注册 SysOperationCard，设置 shellAllowlist 允许 bash 和 sh。
     * <p>
     * 默认白名单不包含 bash，若不预注册，executeCmd 执行 shell 脚本会被拒绝。
     * HarnessFactory 在创建 DeepAgent 时会检查 ResourceMgr 中是否已有 SysOperation，
     * 若已存在则直接使用，不再创建默认白名单的实例。
     */
    private static void registerSysOperationCard() {
        String sysOpId = "exec_cmd_agent_exec_cmd_agent";

        // 在默认白名单基础上增加 bash/sh 命令，允许执行 shell 脚本
        List<String> shellAllowlist = Arrays.asList(
                "echo", "ls", "dir", "cd", "pwd", "python", "python3", "pip", "pip3",
                "npm", "node", "git", "cat", "type", "mkdir", "md", "rm", "rd",
                "cp", "copy", "mv", "move", "grep", "find", "curl", "wget", "ps", "df", "ping",
                "bash", "sh"
        );

        LocalWorkConfig workConfig = LocalWorkConfig.builder()
                .workDir(WORKSPACE_ROOT.toString())
                .shellAllowlist(shellAllowlist)
                .restrictToSandbox(false)
                .build();

        SysOperationCard sysOpCard = SysOperationCard.builder()
                .id(sysOpId)
                .name(sysOpId)
                .mode(OperationMode.LOCAL)
                .workConfig(workConfig)
                .build();

        Runner.resourceMgr().addSysOperation(sysOpCard, null);
        System.out.println("[INFO] Pre-registered SysOperationCard with shellAllowlist including bash/sh");
    }

    // =========================================================================
    // 查询构建
    // =========================================================================

    private static String resolveQuery(String[] args) {
        String customCmd = null;
        String scriptPath = null;

        for (int i = 0; i < args.length; i++) {
            if ("--cmd".equals(args[i]) && i + 1 < args.length) {
                customCmd = args[++i];
            } else if ("--script".equals(args[i]) && i + 1 < args.length) {
                scriptPath = args[++i];
            }
        }

        if (customCmd != null) {
            return "请使用 executeCmd 工具执行以下命令，并告诉我输出结果：" + customCmd;
        }

        Path effectiveScript = scriptPath != null
                ? Path.of(scriptPath).toAbsolutePath().normalize()
                : SCRIPT_PATH;

        return "请使用 executeCmd 工具执行以下 shell 脚本，并告诉我输出结果："
                + "bash " + effectiveScript
                + " 工作目录为 " + WORKSPACE_ROOT;
    }

    // =========================================================================
    // Agent 构建
    // =========================================================================

    private static DeepAgent buildAgent() {
        Map<String, String> llmConfig = SharedExampleApiConfigLoader.load();

        String systemPrompt = """
                你是一个命令执行助手，可以使用 executeCmd 工具来运行 shell 命令和脚本。
                当用户请求执行命令或脚本时，请按以下步骤操作：
                1. 使用 executeCmd 工具执行用户指定的命令
                2. 将执行结果（stdout、stderr、exitCode）返回给用户
                3. 如果执行失败，分析错误原因并告知用户

                executeCmd 工具参数说明：
                - command: 要执行的 shell 命令（必填）
                - cwd: 工作目录（可选，默认为当前目录）
                - timeout: 超时时间，单位秒（可选，默认300）
                """;

        AgentCard card = AgentCard.builder()
                .id("exec_cmd_agent")
                .name("exec_cmd_agent")
                .description("命令执行 Agent — 使用 executeCmd 调用 shell 脚本")
                .build();

        List<Object> rails = new ArrayList<>();
        rails.add(new SysOperationRail());

        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(systemPrompt)
                .maxIterations(10)
                .language("cn")
                .enableTaskLoop(true)
                .rails(rails)
                .model(buildModelConfig(llmConfig))
                .backend(buildBackendConfig(llmConfig))
                .restrictToWorkDir(false)
                .build();

        Workspace workspace = Workspace.builder()
                .rootPath(WORKSPACE_ROOT.toString())
                .language("cn")
                .build();

        return HarnessFactory.createDeepAgent(card, config, workspace);
    }

    // =========================================================================
    // SysOperation 工具注册
    // =========================================================================

    /**
     * 为 Agent 注册 sysOperation 工具卡片。
     * SysOperationRail 只负责创建 SysOperation 实例，
     * 具体的工具卡片需要手动注册到 Agent 的 AbilityManager。
     */
    private static void addTools(DeepAgent agent) {
        String sysOpId = agent.getCard().getName() + "_" + agent.getCard().getId();

        // 核心：注册 shell.executeCmd 工具
        addSysOpTool(agent, sysOpId, "shell", "executeCmd");

        // 辅助：注册 fs.readFile（让 Agent 能读取脚本文件和结果文件）
        addSysOpTool(agent, sysOpId, "fs", "readFile");
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

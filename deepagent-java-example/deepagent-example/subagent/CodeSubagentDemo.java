/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package myexample.subagent;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.deepagents.subagents.DeepAgentSubagents;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.subagents.SubAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;

import examples.utils.SharedExampleApiConfigLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CodeSubagentDemo — 基于 DeepAgent + 预置 {@code code_agent} 子 Agent 的调用样例。
 *
 * <h3>核心机制</h3>
 * <p>本示例不手动拼装子 Agent 的工具与 Rail，而是直接复用预置的 {@code code_agent}：
 * 通过 {@link DeepAgentSubagents#buildCodeAgentConfig(String)} 拿到官方为“编程助手”预置的
 * {@link SubAgentConfig}（已挂载 {@code SysOperationRail}，提供 read/write/edit/grep/list/bash 等
 * 文件与命令行工具，以及 {@code AgentModeRail}/{@code AskUserRail} 等）。只需把它放入
 * {@link DeepAgentConfig#subagents(List)} 并保持 {@code enableAsyncSubagent=false}（默认），
 * {@link HarnessFactory} 即自动注入 {@code SubagentRail}，在父 Agent 上注册一个 {@code task_tool}。
 * 父 Agent 的 LLM 即可调用 {@code task_tool(subagent_type, task_description)}，把编码子任务
 * 委派给 {@code code_agent} 执行。
 *
 * <h3>关键细节：子 Agent 必须开启 taskLoop</h3>
 * <p>预置 {@code buildCodeAgentConfig} 默认 {@code isTaskLoopEnabled=false}。但
 * {@code SubagentRail} 注册的 {@code task_tool} 经 {@code TaskTool.delegate} 走的是
 * {@link DeepAgent#invoke} 路径——而 {@code invoke()} 在未开启 taskLoop 时只返回元数据、
 * 不会真正驱动 LLM。因此这里调用 {@link SubAgentConfig#setEnableTaskLoop(Boolean)}
 * 显式开启子 Agent 的 taskLoop，让被委派的 {@code code_agent} 真正跑起来。
 *
 * <h3>协作链路（同步）</h3>
 * <pre>
 *   父 Agent(orchestrator).run(inputs)            -- 同步入口，Runner.runAgent 驱动 ReAct 循环
 *      └─ LLM 决定委派 ─> 调用 task_tool(subagent_type="code_agent", task_description=...)
 *           └─ SubagentRail 注册的 handler ─> TaskTool.delegate(...)
 *                └─ code_agent.invoke(...)        -- isTaskLoopEnabled=true，真正执行一轮任务循环
 *                     └─ code_agent 用 sysop 工具 write/bash 实现+验证脚本，结果回传给父 Agent
 *   父 Agent 汇总 code_agent 的产出，输出最终答案
 * </pre>
 *
 * <h3>演示场景</h3>
 * <p>用户给出一个编码任务：“在工作目录下用 Python 实现斐波那契脚本 fib.py 并运行验证 fib(10)=55”。
 * 父 Agent 不亲自写代码，而是通过 {@code task_tool} 把整个任务交给预置的 {@code code_agent}：
 * 后者用自带的文件/命令工具完成“写脚本 → 运行 → 验证”的闭环，再把结果交回父 Agent 汇总。
 * 工作目录使用临时目录隔离，避免污染仓库。
 *
 * <h3>运行方式</h3>
 * <pre>{@code
 *   # 默认编码任务（委派给 code_agent）
 *   java myexample.subagent.CodeSubagentDemo
 *
 *   # 自定义任务
 *   java myexample.subagent.CodeSubagentDemo --query "在工作目录写一个 hello.py，运行输出 Hello, code_agent"
 *
 *   # 指定 apiconfig.json 路径
 *   java -Dopenjiuwen.example.config=examples/apiconfig.json myexample.subagent.CodeSubagentDemo
 * }</pre>
 */
public final class CodeSubagentDemo {

    private static final String AGENT_ID = "orchestrator_agent";
    private static final String CONVERSATION_ID = "code-subagent-demo";

    /** 默认编码任务：交给预置 code_agent 实现并自验。 */
    private static final String DEFAULT_QUERY = "请在工作目录下用 Python 实现一个计算斐波那契数列第 n 项的脚本 fib.py，"
            + "要求支持命令行传入 n，并运行它验证 fib(10)=55，最后把实现摘要和验证结果汇总给我。";

    private CodeSubagentDemo() {
    }

    public static void main(String[] args) {
        Path workspaceRoot = null;
        try {
            // 0. 隔离的工作目录（code_agent 会在这里 write/bash）
            workspaceRoot = Files.createTempDirectory("code-subagent-demo-ws");

            // 1. 启动全局 Runner（同步运行同样需要）
            Runner.start();

            // 2. 构建带预置 code_agent 子 Agent 的父 DeepAgent
            DeepAgent agent = buildAgent(workspaceRoot);

            // 3. 解析查询
            String query = resolveQuery(args);

            System.out.println("========== CodeSubagentDemo ==========");
            System.out.println("[INFO] 父 Agent  : " + AGENT_ID);
            System.out.println("[INFO] 子 Agent  : code_agent（预置，SysOperationRail 提供文件/命令工具）");
            System.out.println("[INFO] SubagentRail 由 HarnessFactory 自动注入，提供 task_tool");
            System.out.println("[INFO] workspace : " + workspaceRoot.toAbsolutePath());
            System.out.println();
            System.out.println("Query: " + query);
            System.out.println("--------------------------------------------");

            // 4. 同步执行：agent.run 内部调用 ensureInitialized() 与 Runner.runAgent
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("query", query);
            inputs.put("conversation_id", CONVERSATION_ID);

            Object result = agent.run(inputs);

            System.out.println("--------------------------------------------");
            System.out.println("========== Final Result ==========");
            System.out.println(result);
        } catch (Exception ex) {
            System.err.println("[ERROR] " + ex.getMessage());
            ex.printStackTrace();
        } finally {
            // 5. 释放会话资源并停止 Runner
            try {
                Runner.release(CONVERSATION_ID);
            } catch (Exception ignored) {
                // 会话可能已释放，忽略
            }
            Runner.stop();
        }
    }

    // =========================================================================
    // Agent 构建
    // =========================================================================

    private static DeepAgent buildAgent(Path workspaceRoot) {
        Map<String, String> llmConfig = SharedExampleApiConfigLoader.load();

        // --- 预置 code_agent 子 Agent：直接复用官方配置（含 SysOperationRail 等编程能力） ---
        SubAgentConfig codeAgent = DeepAgentSubagents.buildCodeAgentConfig("cn");
        // 关键：TaskTool.delegate 走 invoke 路径，必须开启 taskLoop，子 Agent 才会真正执行
        codeAgent.setEnableTaskLoop(true);

        // --- 父 Agent 系统提示：引导其用 task_tool 把编码任务委派给 code_agent ---
        String systemPrompt = """
                你是任务编排 Agent（orchestrator）。你本身不亲自写代码或执行命令，
                而是通过 task_tool 把编码类子任务委派给子 Agent：
                  - code_agent：资深编程助手，能读/写/编辑文件，执行 bash 命令，并自测验证。

                工作流程：
                1. 理解用户的编码需求，必要时拆解成清晰的、自包含的子任务；
                2. 对每个子任务调用 task_tool，subagent_type 填 "code_agent"，
                   task_description 要把目标、约束、验收标准写清楚；
                3. 收集 code_agent 返回的结果（文件路径、运行输出、验证结论等）；
                4. 汇总后用中文给出最终答复，简要说明 code_agent 的产出与验证情况。

                注意：不要自己猜测文件内容或命令输出，一切交给 code_agent 实际执行。
                """;

        AgentCard card = AgentCard.builder()
                .id(AGENT_ID)
                .name(AGENT_ID)
                .description("编排 Agent — 通过 SubagentRail 把编码任务委派给预置 code_agent")
                .build();

        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(systemPrompt)
                .maxIterations(15)
                .language("cn")
                .enableTaskLoop(false)          // 父 Agent 走 run() 的同步 ReAct 路径即可
                .enableTaskPlanning(false)
                .enableAsyncSubagent(false)     // 默认 false → HarnessFactory 自动注入 SubagentRail
                .subagents(List.of(codeAgent))  // 仅挂载预置 code_agent
                .model(buildModelConfig(llmConfig))
                .backend(buildBackendConfig(llmConfig))
                .restrictToWorkDir(false)
                .build();

        Workspace workspace = Workspace.builder()
                .rootPath(workspaceRoot.toString())
                .language("cn")
                .build();

        System.out.println("[INFO] Agent 构建完成 | model=" + llmConfig.getOrDefault("MODEL_NAME", "unknown"));
        return HarnessFactory.createDeepAgent(card, config, workspace);
    }

    // =========================================================================
    // 参数解析
    // =========================================================================

    private static String resolveQuery(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--query".equals(args[i]) && i + 1 < args.length) {
                return args[++i];
            }
        }
        return DEFAULT_QUERY;
    }

    // =========================================================================
    // LLM 配置构建（父 Agent 配置后，子 Agent 会自动继承作为 fallback）
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

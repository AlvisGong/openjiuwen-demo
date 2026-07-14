/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package myexample.subagent;

import com.openjiuwen.core.foundation.tool.function.AnnotatedToolFactory;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.subagents.SubAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.example.tool.MathTools;

import examples.utils.SharedExampleApiConfigLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SubagentSyncDemo — 基于 DeepAgent + SubagentRail 的同步多子 Agent 协作样例。
 *
 * <h3>核心机制</h3>
 * <p>本示例不手动 new SubagentRail()。只需在 {@link DeepAgentConfig#subagents(List)} 中放入
 * {@link SubAgentConfig}，并保持 {@code enableAsyncSubagent=false}（默认），{@link HarnessFactory}
 * 会自动注入 {@code SubagentRail}。该 Rail 在初始化时为父 Agent 注册一个 {@code task_tool}，
 * 父 Agent 的 LLM 即可调用它，把子任务委派给指定类型的子 Agent 执行。
 *
 * <h3>协作链路（同步）</h3>
 * <pre>
 *   父 Agent(orchestrator).run(inputs)          -- 同步入口，Runner.runAgent 驱动 ReAct 循环
 *      └─ LLM 决定委派 ─> 调用 task_tool(subagent_type, task_description)
 *           └─ SubagentRail 注册的 handler ─> TaskTool.delegate(...)
 *                └─ 子 Agent.invoke(...)        -- 子 Agent 需开启 taskLoop 才会真正执行
 *                     └─ 子 Agent 完成任务，结果回传给父 Agent
 *   父 Agent 汇总子 Agent 结果，输出最终答案
 * </pre>
 *
 * <h3>本示例的两个子 Agent</h3>
 * <ul>
 *   <li><b>math_analyst</b> — 配备加减乘除工具（{@link MathTools}），负责算术运算</li>
 *   <li><b>translator</b>   — 无工具，纯 LLM，负责中英翻译</li>
 * </ul>
 * 默认查询会同时触发两个子 Agent，演示父 Agent 的路由与汇总能力。
 *
 * <h3>运行方式</h3>
 * <pre>{@code
 *   # 默认查询（计算 + 翻译，触发两个子 Agent）
 *   java myexample.subagent.SubagentSyncDemo
 *
 *   # 自定义查询
 *   java myexample.subagent.SubagentSyncDemo --query "计算 100 / 4 的结果"
 *
 *   # 指定 apiconfig.json 路径（默认从 examples/apiconfig.json 或环境变量读取）
 *   java -Dopenjiuwen.example.config=examples/apiconfig.json myexample.subagent.SubagentSyncDemo
 * }</pre>
 *
 * <p><b>注意：</b>子 Agent 通过 {@code TaskTool.delegate} 走 {@code invoke} 路径执行，
 * 因此每个 {@link SubAgentConfig} 都必须设置 {@code isTaskLoopEnabled(true)}，否则子 Agent
 * 只返回元数据、不会真正运行。
 */
public final class SubagentSyncDemo {

    private static final Path WORKSPACE_ROOT = Path.of(".").toAbsolutePath().normalize();
    private static final String CONVERSATION_ID = "subagent-sync-demo";
    private static final String AGENT_ID = "orchestrator_agent";

    private SubagentSyncDemo() {
    }

    public static void main(String[] args) {
        try {
            // 1. 启动全局 Runner（同步运行同样需要）
            Runner.start();

            // 2. 构建带子 Agent 的父 DeepAgent
            DeepAgent agent = buildAgent();

            // 3. 解析查询
            String query = resolveQuery(args);

            System.out.println("========== SubagentRail Sync Demo ==========");
            System.out.println("[INFO] 父 Agent: " + AGENT_ID);
            System.out.println("[INFO] 子 Agent: math_analyst(算术), translator(翻译)");
            System.out.println("[INFO] SubagentRail 由 HarnessFactory 自动注入，提供 task_tool");
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

    private static DeepAgent buildAgent() {
        Map<String, String> llmConfig = SharedExampleApiConfigLoader.load();

        // --- 子 Agent 1: math_analyst（带算术工具，开启 taskLoop） ---
        List<Object> mathTools = new ArrayList<>(AnnotatedToolFactory.scan(new MathTools()));
        SubAgentConfig mathAnalyst = SubAgentConfig.builder()
                .agentCard(AgentCard.builder()
                        .name("math_analyst")
                        .description("算术运算专家，能调用 add/subtract/multiply/divide 工具完成加减乘除计算")
                        .build())
                .systemPrompt("""
                        你是算术运算专家。请使用提供的 add / subtract / multiply / divide 工具完成计算任务。
                        工作流程：
                        1. 分析需要哪些运算步骤；
                        2. 依次调用对应工具完成计算；
                        3. 汇总每一步结果，给出最终答案。
                        """)
                .tools(mathTools)
                .maxIterations(10)
                .isTaskLoopEnabled(true)   // 关键：TaskTool.delegate 走 invoke 路径，必须开启 taskLoop
                .executionMode("ephemeral")
                .language("cn")
                .build();

        // --- 子 Agent 2: translator（无工具，纯 LLM，开启 taskLoop） ---
        SubAgentConfig translator = SubAgentConfig.builder()
                .agentCard(AgentCard.builder()
                        .name("translator")
                        .description("中英翻译专家，负责把中文翻译成英文、或把英文翻译成中文")
                        .build())
                .systemPrompt("你是中英翻译专家。请把用户给出的文本翻译成目标语言，只输出译文，不要解释。")
                .maxIterations(5)
                .isTaskLoopEnabled(true)
                .executionMode("ephemeral")
                .language("cn")
                .build();

        // --- 父 Agent 系统提示：引导其用 task_tool 委派子 Agent ---
        String systemPrompt = """
                你是任务编排 Agent（orchestrator）。你本身不直接做算术或翻译，
                而是通过 task_tool 把子任务委派给合适的子 Agent：
                  - math_analyst：算术运算
                  - translator：中英翻译

                工作流程：
                1. 拆解用户请求，识别哪些部分应交给哪个子 Agent；
                2. 对每个子任务调用 task_tool，传入正确的 subagent_type 与 task_description；
                3. 收集各子 Agent 返回的结果；
                4. 汇总后用中文给出最终答复，简要说明各子 Agent 的贡献。

                注意：task_description 要清晰、自包含，把子 Agent 需要的全部信息写进去。
                """;

        AgentCard card = AgentCard.builder()
                .id(AGENT_ID)
                .name(AGENT_ID)
                .description("编排 Agent — 通过 SubagentRail 协调多个子 Agent（同步）")
                .build();

        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(systemPrompt)
                .maxIterations(15)
                .language("cn")
                .enableTaskLoop(false)          // 父 Agent 走 run() 的同步 ReAct 路径即可
                .enableTaskPlanning(false)
                .enableAsyncSubagent(false)     // 默认 false → HarnessFactory 自动注入 SubagentRail
                .subagents(List.of(mathAnalyst, translator))
                .model(buildModelConfig(llmConfig))
                .backend(buildBackendConfig(llmConfig))
                .restrictToWorkDir(false)
                .build();

        Workspace workspace = Workspace.builder()
                .rootPath(WORKSPACE_ROOT.toString())
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
        // 默认查询：同时触发算术与翻译两个子 Agent
        return "请帮我做两件事：第一，计算 (12 + 8) * 3 的结果；第二，把中文“任务已完成”翻译成英文。";
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

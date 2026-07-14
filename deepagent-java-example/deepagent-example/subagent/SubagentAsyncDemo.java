/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package myexample.subagent;

import com.openjiuwen.core.controller.schema.ControllerOutputPayload;
import com.openjiuwen.core.controller.schema.DataFrame;
import com.openjiuwen.core.foundation.tool.function.AnnotatedToolFactory;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SubagentAsyncDemo — 基于 DeepAgent + SubagentRail 的异步（流式）多子 Agent 协作样例。
 *
 * <p>与 {@code SubagentSyncDemo}（同步入口 {@code agent.run}）对照，本示例改用
 * <b>流式入口 {@link DeepAgent#stream}</b> 实现异步：父 Agent 在后台守护线程中执行，
 * 调用方通过返回的 {@code Iterator<Object>} 渐进消费输出，端到端非阻塞。
 *
 * <h3>为什么这是“异步”</h3>
 * <p>{@link DeepAgent#stream} 在 {@code enableTaskLoop=true} 时会启动一个守护线程
 * （{@code "deep-agent-stream-<sessionId>"}）运行 {@code runTaskLoop}，并立即返回
 * {@code session.streamIterator()}。产出边写边产、消费边读边出，调用方线程不被阻塞。
 *
 * <h3>为什么仍使用 SubagentRail</h3>
 * <p>“异步子 Agent”（{@code enableAsyncSubagent}）在本代码库中是一个名不副实的开关：
 * 置 true 时 {@link HarnessFactory} 注入的是 {@code SessionRail}（提供 sessions_spawn /
 * sessions_list / sessions_cancel 等会话管理工具），但其底层 {@code TaskTool.delegate}
 * 仍然是同步调用 {@code subagent.invoke}，并非真正的非阻塞委派。
 * <p>本示例要求使用 <b>SubagentRail</b>，因此保持 {@code enableAsyncSubagent=false}（默认），
 * 由 {@link HarnessFactory} 自动注入 SubagentRail，父 Agent 获得 {@code task_tool}。
 * “异步”体现在父 Agent 的流式执行通道，而非子 Agent 委派方式。
 *
 * <h3>异步协作链路</h3>
 * <pre>
 *   调用方线程:  agent.stream(inputs, session, [OUTPUT]) ──> 立即拿到 Iterator
 *                     │
 *   守护线程:    runTaskLoop ──> 父 LLM ReAct 循环
 *                     └─ 调用 task_tool(subagent_type, task_description)
 *                          └─ SubagentRail handler ─> TaskTool.delegate
 *                               └─ 子 Agent.invoke（同步，但跑在守护线程里）
 *                                    └─ 子 Agent 完成，结果写回流 ──> 父 Agent 继续
 *   调用方线程:  while (iterator.hasNext()) { 渐进打印 chunk }
 * </pre>
 *
 * <h3>子 Agent</h3>
 * <ul>
 *   <li><b>math_analyst</b> — 配备加减乘除工具（{@link MathTools}），负责算术运算</li>
 *   <li><b>translator</b>   — 无工具，纯 LLM，负责中英翻译</li>
 * </ul>
 * 默认查询同时触发两个子 Agent，演示异步流中子 Agent 委派与汇总。
 *
 * <h3>运行方式</h3>
 * <pre>{@code
 *   # 默认查询（计算 + 翻译，触发两个子 Agent，流式输出）
 *   java myexample.subagent.SubagentAsyncDemo
 *
 *   # 自定义查询
 *   java myexample.subagent.SubagentAsyncDemo --query "计算 100 / 4 的结果"
 *
 *   # 指定 apiconfig.json 路径
 *   java -Dopenjiuwen.example.config=examples/apiconfig.json myexample.subagent.SubagentAsyncDemo
 * }</pre>
 *
 * <p><b>注意：</b>子 Agent 通过 {@code TaskTool.delegate} 走 {@code invoke} 路径执行，
 * 因此每个 {@link SubAgentConfig} 都必须设置 {@code isTaskLoopEnabled(true)}。
 */
public final class SubagentAsyncDemo {

    private static final Path WORKSPACE_ROOT = Path.of(".").toAbsolutePath().normalize();
    private static final String AGENT_ID = "orchestrator_agent_async";
    private static final String DEFAULT_SESSION_ID = "subagent-async-demo";

    private SubagentAsyncDemo() {
    }

    public static void main(String[] args) {
        try {
            // 1. 启动全局 Runner
            Runner.start();

            // 2. 构建带子 Agent 的父 DeepAgent
            DeepAgent agent = buildAgent();

            // 3. 解析查询
            String query = resolveQuery(args);

            System.out.println("========== SubagentRail Async(Stream) Demo ==========");
            System.out.println("[INFO] 父 Agent: " + AGENT_ID + " (enableTaskLoop=true, 流式后台执行)");
            System.out.println("[INFO] 子 Agent: math_analyst(算术), translator(翻译)");
            System.out.println("[INFO] SubagentRail 由 HarnessFactory 自动注入，提供 task_tool");
            System.out.println();
            System.out.println("Query: " + query);
            System.out.println("---------- Agent 流式输出 ----------");
            System.out.flush();

            // 4. 创建流式 Session（OUTPUT 通道）
            AgentSessionApi session = new AgentSessionApi(
                    DEFAULT_SESSION_ID, null, agent.getCard(), List.of(StreamMode.OUTPUT));

            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("query", query);
            inputs.put("conversation_id", DEFAULT_SESSION_ID);

            // 5. 异步入口：stream 立即返回 live iterator，父 Agent 在守护线程里跑
            Iterator<Object> stream = agent.stream(inputs, session, List.of(StreamMode.OUTPUT));

            // 6. 渐进消费输出（边产边读，非阻塞）
            consumeStream(stream);

            System.out.println();
            System.out.println("---------- 流结束 ----------");
        } finally {
            try {
                Runner.release(DEFAULT_SESSION_ID);
            } catch (Exception ignored) {
                // 会话可能已释放，忽略
            }
            Runner.stop();
        }
    }

    // =========================================================================
    // 流式输出消费
    // =========================================================================

    /**
     * 消费 stream 迭代器。task-loop 模式下产出两种通道：
     * <ul>
     *   <li>通道 A：{@link ControllerOutputPayload}（含 {@link DataFrame} 列表，如 ALL_TASKS_PROCESSED 与流式文本）</li>
     *   <li>通道 B：直接 {@link OutputSchema} 的 payload（type=answer/tool_result/error/__end__ 等）</li>
     * </ul>
     */
    private static void consumeStream(Iterator<Object> stream) {
        int chunkCount = 0;
        while (stream.hasNext()) {
            Object item = stream.next();
            chunkCount++;

            if (!(item instanceof OutputSchema chunk)) {
                continue;
            }

            String type = chunk.getType();
            Object payload = chunk.getPayload();

            // 通道 A：ControllerOutputPayload
            if (payload instanceof ControllerOutputPayload controllerOutput) {
                String controllerType = controllerOutput.getType();
                List<DataFrame> dataList = controllerOutput.getData();

                if (ControllerOutputPayload.ALL_TASKS_PROCESSED.equals(controllerType)) {
                    String text = extractTextFromDataFrames(dataList);
                    if (!text.isBlank()) {
                        System.out.println();
                        System.out.println("[完成] " + text);
                    }
                    continue;
                }

                String text = extractTextFromDataFrames(dataList);
                if (!text.isBlank()) {
                    System.out.print(text);
                    System.out.flush();
                }
                continue;
            }

            // 通道 B：直接 payload
            switch (type == null ? "" : type) {
                case "answer" -> {
                    String text = extractText(payload);
                    if (!text.isEmpty()) {
                        System.out.print(text);
                        System.out.flush();
                    }
                }
                case "tool_result" -> {
                    System.out.print("[tool:" + extractToolName(payload) + "] ");
                    System.out.println(extractToolResult(payload));
                    System.out.flush();
                }
                case "error" -> System.out.println("[error] " + extractText(payload));
                case "__end__" -> { /* 流结束 */ }
                default -> {
                    String text = extractText(payload);
                    if (!text.isBlank()) {
                        System.out.println("[" + type + "] " + text);
                    }
                }
            }
        }
        System.out.println();
        System.out.println("[INFO] 共消费 " + chunkCount + " 个 chunk");
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
                .description("编排 Agent — 通过 SubagentRail 协调多个子 Agent（异步流式）")
                .build();

        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(systemPrompt)
                .maxIterations(15)
                .language("cn")
                .enableTaskLoop(true)          // 关键：stream 由此进入后台守护线程的异步流式路径
                .enableTaskPlanning(true)
                .enableAsyncSubagent(false)    // 保持 SubagentRail（true 会换为 SessionRail）
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

    // =========================================================================
    // 流式输出文本提取工具
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static String extractTextFromDataFrames(List<DataFrame> dataList) {
        if (dataList == null || dataList.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (DataFrame df : dataList) {
            if (df instanceof DataFrame.TextDataFrame textDf) {
                if (textDf.text() != null) {
                    sb.append(textDf.text());
                }
            } else if (df instanceof DataFrame.JsonDataFrame jsonDf) {
                if (jsonDf.data() != null) {
                    sb.append(extractModelContent((Map<String, Object>) jsonDf.data()));
                }
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String extractModelContent(Map<String, Object> map) {
        Object content = map.get("content");
        if (content instanceof String s && !s.isBlank()) {
            return s;
        }
        Object delta = map.get("delta");
        if (delta instanceof String s && !s.isBlank()) {
            return s;
        }
        Object reasoning = map.get("reasoning_content");
        if (reasoning instanceof String s && !s.isBlank()) {
            return s;
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static String extractText(Object payload) {
        if (payload instanceof String s) {
            return s;
        }
        if (payload instanceof Map<?, ?> map) {
            return extractModelContent((Map<String, Object>) map);
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static String extractToolName(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            Object name = map.get("tool_name");
            if (name != null) {
                return String.valueOf(name);
            }
        }
        return "unknown";
    }

    @SuppressWarnings("unchecked")
    private static String extractToolResult(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            Object result = map.get("result");
            if (result instanceof String s) {
                return s;
            }
            Object output = map.get("output");
            if (output instanceof String s) {
                return s;
            }
        }
        return "";
    }
}

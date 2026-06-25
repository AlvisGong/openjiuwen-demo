/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package myexample.deepagent;

import com.openjiuwen.core.controller.schema.ControllerOutputPayload;
import com.openjiuwen.core.controller.schema.DataFrame;
import com.openjiuwen.core.foundation.tool.function.AnnotatedToolFactory;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import examples.utils.SharedExampleApiConfigLoader;
import myexample.tool.MathTools;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * SteerDemo — 基于 DeepAgent 的 steer（外层循环引导）示例，使用流式调用。
 *
 * <h3>核心概念</h3>
 * <ul>
 *   <li><b>steer</b> — 在 Agent taskLoop 运行过程中，从外部注入引导指令，
 *       影响当前或后续迭代的行为方向。Steering 消息会被注入为
 *       [STEERING] UserMessage，ReActAgent 在每次迭代开始时
 *       通过 {@code injectPendingSteering()} 将队列中的 steering 文本注入到 ModelContext</li>
 *   <li><b>外层循环 (taskLoop)</b> — DeepAgent 启用 taskLoop 后，
 *       在每轮迭代结束后检查是否有 follow-up 或 steering 消息，
 *       如果有则继续新一轮迭代</li>
 *   <li><b>steer vs follow-up</b> — steer 是"引导当前行为方向",
 *       follow-up 是"追加新子任务"</li>
 *   <li><b>流式调用</b> — 使用 {@code agent.stream()} 代替 {@code agent.invoke()},
 *       实时消费 Agent 输出，每个 chunk 到达时立即打印</li>
 * </ul>
 *
 * <h3>运行方式</h3>
 * <pre>{@code
 *   # 自动模式（默认）— 自动注入 steer/follow-up，展示完整流程
 *   mvn compile exec:java \
 *     -Dexec.mainClass=myexample.deepagent.SteerDemo \
 *     -Dopenjiuwen.example.config=examples/apiconfig.json
 *
 *   # 交互模式 — 手动输入 :steer / :followup 命令
 *   mvn compile exec:java \
 *     -Dexec.mainClass=myexample.deepagent.SteerDemo \
 *     -Dexec.args="-i" \
 *     -Dopenjiuwen.example.config=examples/apiconfig.json
 * }</pre>
 */
public final class SteerDemo {

    private static final Path WORKSPACE_ROOT = Path.of(".").toAbsolutePath().normalize();

    static {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    private SteerDemo() {
    }

    // =========================================================================
    // 入口
    // =========================================================================

    public static void main(String[] args) {
        boolean interactiveMode = args.length > 0 && "-i".equals(args[0]);

        DeepAgent agent = buildAgent();
        agent.ensureInitialized();

        try {
            if (interactiveMode) {
                runInteractiveMode(agent);
            } else {
                runAutoMode(agent);
            }
        } finally {
            cleanup();
        }
    }

    // =========================================================================
    // 自动模式 — 分步骤演示 steer / follow-up 流程（流式调用）
    // =========================================================================

    private static void runAutoMode(DeepAgent agent) {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  SteerDemo 自动模式 — 流式调用 steer/follow-up     ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();

        // ---- Phase 1: 预注入 steer + stream（展示 steer 在首轮生效） ----
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("  Phase 1: 预注入 steer → stream（steer 在首轮迭代生效）");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println();

        String sessionId1 = "steer_phase1_" + System.currentTimeMillis();
        AgentSessionApi session1 = AgentSessionApi.create(
                sessionId1, null, agent.getCard(), List.of(StreamMode.OUTPUT));

        // 预注入 steering：要求简短回答
        String steerMsg1 = "请用简短方式回答，只给出关键结果和一两句说明，不要展开详细论述";
        System.out.println("  [预注入 steer] \"" + steerMsg1 + "\"");
        agent.steer(steerMsg1, session1);
        System.out.println();

        String query1 = "计算 15+27，然后乘以3，再除以2";
        System.out.println("  [发起查询] \"" + query1 + "\"");
        System.out.println();

        Map<String, Object> inputs1 = new LinkedHashMap<>();
        inputs1.put("query", query1);
        inputs1.put("conversation_id", sessionId1);

        System.out.println("  ┌─ Agent 流式输出 (Phase 1, 有 steer) ─────────────┐");
        StreamResult result1 = runAndPrintStream(agent, inputs1, session1);
        System.out.println("  └──────────────────────────────────────────────────┘");
        System.out.println();

        printPhaseSummary("Phase 1", result1, "steer=\"请用简短方式回答\" → Agent 应给出简短结果");

        // ---- Phase 2: 无 steer 同样查询（对比效果） ----
        System.out.println();
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("  Phase 2: 无 steer 同样查询（对比无 steer 的效果）");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println();

        String sessionId2 = "steer_phase2_" + System.currentTimeMillis();
        AgentSessionApi session2 = AgentSessionApi.create(
                sessionId2, null, agent.getCard(), List.of(StreamMode.OUTPUT));

        System.out.println("  [发起查询] \"" + query1 + "\" （无 steer）");
        System.out.println();

        Map<String, Object> inputs2 = new LinkedHashMap<>();
        inputs2.put("query", query1);
        inputs2.put("conversation_id", sessionId2);

        System.out.println("  ┌─ Agent 流式输出 (Phase 2, 无 steer) ─────────────┐");
        StreamResult result2 = runAndPrintStream(agent, inputs2, session2);
        System.out.println("  └──────────────────────────────────────────────────┘");
        System.out.println();

        printPhaseSummary("Phase 2", result2, "无 steer → Agent 应给出较详细的回答");

        // ---- Phase 3: follow-up 子任务追加 ----
        System.out.println();
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("  Phase 3: follow-up 追加子任务");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println();

        String sessionId3 = "steer_phase3_" + System.currentTimeMillis();
        AgentSessionApi session3 = AgentSessionApi.create(
                sessionId3, null, agent.getCard(), List.of(StreamMode.OUTPUT));

        String steerMsg3 = "只输出数字结果，不要解释过程";
        System.out.println("  [预注入 steer] \"" + steerMsg3 + "\"");
        agent.steer(steerMsg3, session3);

        String followupMsg3 = "顺便计算 100-37";
        System.out.println("  [注入 follow-up] \"" + followupMsg3 + "\"");
        agent.isFollowUp(followupMsg3, session3);
        System.out.println();

        String query3 = "计算 8*9";
        System.out.println("  [发起查询] \"" + query3 + "\"");
        System.out.println();

        Map<String, Object> inputs3 = new LinkedHashMap<>();
        inputs3.put("query", query3);
        inputs3.put("conversation_id", sessionId3);

        System.out.println("  ┌─ Agent 流式输出 (Phase 3, steer + follow-up) ────┐");
        StreamResult result3 = runAndPrintStream(agent, inputs3, session3);
        System.out.println("  └──────────────────────────────────────────────────┘");
        System.out.println();

        printPhaseSummary("Phase 3", result3, "steer=\"只输出数字结果\" + follow-up=\"顺便计算100-37\" → 应包含两个计算结果");

        // ---- 总结 ----
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  演示结束                                            ║");
        System.out.println("║                                                      ║");
        System.out.println("║  steer:  引导 Agent 行为方向（如简短回答、只给结果）  ║");
        System.out.println("║  follow-up: 追加子任务（如顺便计算XX）              ║");
        System.out.println("║  stream:  流式实时输出，每个 chunk 到达时立即打印   ║");
        System.out.println("║                                                      ║");
        System.out.println("║  交互模式请加 -i 参数: 可手动输入 :steer/:followup   ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
    }

    // =========================================================================
    // 流式调用核心方法
    // =========================================================================

    /**
     * 运行流式 stream 并实时打印每个 chunk。
     * <p>
     * 使用 {@code agent.stream()} 替代 {@code agent.invoke()},
     * 返回 {@link StreamResult} 用于后续摘要打印。
     */
    private static StreamResult runAndPrintStream(
            DeepAgent agent, Map<String, Object> inputs, AgentSessionApi session) {
        Iterator<Object> stream = agent.stream(inputs, session, List.of(StreamMode.OUTPUT));

        int chunkCount = 0;
        StringBuilder answerBuilder = new StringBuilder();
        List<String> toolCalls = new ArrayList<>();
        boolean hasError = false;
        String errorMsg = null;

        while (stream.hasNext()) {
            Object item = stream.next();
            chunkCount++;

            if (!(item instanceof OutputSchema chunk)) {
                continue;
            }

            String type = chunk.getType();
            Object payload = chunk.getPayload();

            // --- 通道 A: ControllerOutputPayload（Task-loop 模式） ---
            if (payload instanceof ControllerOutputPayload controllerOutput) {
                String controllerType = controllerOutput.getType();
                List<DataFrame> dataList = controllerOutput.getData();

                if (ControllerOutputPayload.ALL_TASKS_PROCESSED.equals(controllerType)) {
                    String completionText = extractTextFromDataFrames(dataList);
                    System.out.println();
                    System.out.println("  [完成] " + completionText);
                    answerBuilder.append(completionText);
                    continue;
                }

                String text = extractTextFromDataFrames(dataList);
                if (!text.isBlank()) {
                    System.out.print(text);
                    System.out.flush();
                    answerBuilder.append(text);
                }
                continue;
            }

            // --- 通道 B: 直接 Payload ---
            switch (type) {
                case "answer" -> {
                    String text = extractDelta(payload);
                    if (!text.isEmpty()) {
                        System.out.print(text);
                        System.out.flush();
                        answerBuilder.append(text);
                    }
                }
                case "tool_result" -> {
                    String toolName = extractToolName(payload);
                    String toolResult = extractToolResult(payload);
                    toolCalls.add(toolName + "=" + toolResult);
                    System.out.println("  [tool:" + toolName + "] " + toolResult);
                    System.out.flush();
                }
                case "error" -> {
                    hasError = true;
                    errorMsg = extractDisplayText(payload);
                    System.out.println("  [error] " + errorMsg);
                    System.out.flush();
                }
                case "__end__" -> { /* 流结束 */ }
                default -> {
                    String text = extractDisplayText(payload);
                    if (!text.isBlank()) {
                        System.out.println("  [" + type + "] " + text);
                    }
                }
            }
        }

        System.out.println();

        return new StreamResult(chunkCount, answerBuilder.toString(), toolCalls, hasError, errorMsg);
    }

    // =========================================================================
    // Phase 摘要打印（适配流式结果）
    // =========================================================================

    private static void printPhaseSummary(String phaseName, StreamResult result, String description) {
        System.out.println("  ╔─ " + phaseName + " 摘要 ──────────────────────────────────╗");
        System.out.println("  │ " + description);
        System.out.println("  │ chunks: " + result.chunkCount
                + " | tools: " + (result.toolCalls.isEmpty() ? "无" : result.toolCalls));
        if (result.hasError) {
            System.out.println("  │ error: " + result.errorMsg);
        } else if (!result.answer.isBlank()) {
            // 截取前60字符作为摘要
            String preview = result.answer.length() > 60
                    ? result.answer.substring(0, 60) + "..."
                    : result.answer;
            System.out.println("  │ answer: " + preview);
        }
        System.out.println("  ╚──────────────────────────────────────────────────╝");
    }

    // =========================================================================
    // 交互模式 — 手动 :steer / :followup 命令（流式调用）
    // =========================================================================

    private static void runInteractiveMode(DeepAgent agent) {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  SteerDemo 交互模式（流式调用）                     ║");
        System.out.println("║  命令: :steer <指令> | :followup <问题> | :abort    ║");
        System.out.println("║        :help | quit/exit                             ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();

        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        ConversationState state = new ConversationState();

        try {
            while (true) {
                System.out.print(">> ");
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }

                // steer 命令 — 注入引导指令
                if (line.startsWith(":steer ")) {
                    String msg = line.substring(7).trim();
                    if (msg.isEmpty()) {
                        System.out.println("[WARN] steer 内容不能为空");
                    } else {
                        String sid = state.sessionId != null ? state.sessionId : "steer_pre_session";
                        AgentSessionApi steerSession = state.session != null ? state.session
                                : AgentSessionApi.create(sid, null, agent.getCard(), List.of(StreamMode.OUTPUT));
                        agent.steer(msg, steerSession);
                        System.out.println("[OK] steer 已注入: \"" + msg + "\"");
                        System.out.println("     steering 消息将在 taskLoop 下次迭代时通过 injectPendingSteering() 注入");
                    }
                    System.out.println();
                    continue;
                }

                // follow-up 命令
                if (line.startsWith(":followup ")) {
                    String msg = line.substring(10).trim();
                    if (msg.isEmpty()) {
                        System.out.println("[WARN] follow-up 内容不能为空");
                    } else {
                        String sid = state.sessionId != null ? state.sessionId : "steer_pre_session";
                        AgentSessionApi followupSession = state.session != null ? state.session
                                : AgentSessionApi.create(sid, null, agent.getCard(), List.of(StreamMode.OUTPUT));
                        agent.isFollowUp(msg, followupSession);
                        System.out.println("[OK] follow-up 已注入: \"" + msg + "\"");
                        System.out.println("     follow-up 将在 taskLoop 当前迭代结束后作为新迭代启动");
                    }
                    System.out.println();
                    continue;
                }

                // abort 命令
                if (line.equalsIgnoreCase(":abort")) {
                    agent.requestAbort();
                    System.out.println("[OK] 已请求终止 taskLoop");
                    System.out.println();
                    continue;
                }

                // help
                if (line.equalsIgnoreCase(":help")) {
                    printHelp();
                    System.out.println();
                    continue;
                }

                // 退出
                if ("quit".equalsIgnoreCase(line) || "exit".equalsIgnoreCase(line)) {
                    System.out.println("Bye!");
                    break;
                }

                // 执行一轮对话（流式调用，实时打印输出）
                try {
                    String sessionId = "steer-interactive-" + System.currentTimeMillis();
                    state.sessionId = sessionId;
                    // 每轮创建新 session 以获得新的 StreamQueue
                    AgentSessionApi newSession = new AgentSessionApi(
                            sessionId, null, agent.getCard(), List.of(StreamMode.OUTPUT));
                    // 如果之前有 session，迁移状态保持对话上下文
                    if (state.session != null) {
                        newSession.getInner().state().setState(state.session.getInner().state().getState());
                    }
                    state.session = newSession;
                    state.turnCount++;

                    Map<String, Object> inputs = new LinkedHashMap<>();
                    inputs.put("query", line);
                    inputs.put("conversation_id", sessionId);

                    System.out.println("  ┌─ Agent 流式输出 ─────────────────────────────────┐");
                    StreamResult result = runAndPrintStream(agent, inputs, state.session);
                    System.out.println("  └──────────────────────────────────────────────────┘");
                    System.out.println();
                    System.out.println("[INFO] sessionId=" + sessionId
                            + " | chunks=" + result.chunkCount
                            + " | 已进行 " + state.turnCount + " 轮对话");
                    System.out.println();
                } catch (Exception e) {
                    System.err.println("[ERROR] 执行失败: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } finally {
            scanner.close();
        }
    }

    // =========================================================================
    // 命令/辅助
    // =========================================================================

    private static void printHelp() {
        System.out.println("""
                命令列表:
                  :steer <指令>      — 注入 steering 消息（引导行为方向）
                  :followup <问题>   — 注入 follow-up 子任务（追加新任务）
                  :abort             — 终止当前 taskLoop
                  :help              — 显示此帮助
                  quit / exit        — 退出程序

                直接输入问题即可通过 stream() 启动流式 taskLoop。
                在启动前输入 :steer 可预注入 steering 消息。

                steer vs follow-up:
                  steer   — 引导 Agent 行为方向，如 "请用简短回答"
                  follow-up — 追加新子任务，如 "顺便计算 5*8"

                流式调用:
                  使用 agent.stream() 代替 agent.invoke(),
                  每个 OutputSchema chunk 到达时实时打印。

                配置: -Dopenjiuwen.example.config=examples/apiconfig.json""");
    }

    // =========================================================================
    // Agent 构建
    // =========================================================================

    private static DeepAgent buildAgent() {
        List<LocalFunction> scannedTools = AnnotatedToolFactory.scan(new MathTools());
        List<Object> tools = new ArrayList<>(scannedTools);

        String systemPrompt = """
                你是一个智能助手，可以进行数学计算并回答用户的各种问题。

                可用工具：
                - add      — 加法 a + b
                - subtract — 减法 a - b
                - multiply — 乘法 a * b
                - divide   — 除法 a / b

                工作方式：
                1. 理解用户的问题和需求
                2. 如果需要计算，调用对应的数学工具
                3. 用清晰的中文回答用户
                4. 当收到 [STEERING] 引导指令时，调整你的回答风格和方向
                5. 当收到 follow-up 时，继续执行追加的子任务

                注意：steering 消息可能会在运行过程中动态注入，
                你应该根据 steering 内容调整当前和后续回答的方向。
                """;

        AgentCard card = AgentCard.builder()
                .id("steer_demo_agent")
                .name("steer_demo_agent")
                .description("SteerDemo — steer 外层循环引导示例")
                .build();

        Map<String, String> llmConfig = SharedExampleApiConfigLoader.load();

        DeepAgentConfig config = DeepAgentConfig.builder()
                .enableTaskLoop(true)
                .enableTaskPlanning(true)
                .systemPrompt(systemPrompt)
                .maxIterations(10)
                .language("cn")
                .model(buildModelConfig(llmConfig))
                .backend(buildBackendConfig(llmConfig))
                .restrictToWorkDir(false)
                .tools(tools)
                .build();

        Workspace workspace = Workspace.builder()
                .rootPath(WORKSPACE_ROOT.toString())
                .language("cn")
                .build();

        System.out.println("[INFO] Agent 构建完成 | model=" + llmConfig.getOrDefault("MODEL_NAME", "unknown"));
        System.out.println("[INFO] taskLoop 已启用 | maxIterations=10 | 流式调用模式");
        System.out.println();

        return HarnessFactory.createDeepAgent(card, config, workspace);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildModelConfig(Map<String, String> llmConfig) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("model", llmConfig.getOrDefault("MODEL_NAME", "glm-5"));
        return model;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildBackendConfig(Map<String, String> llmConfig) {
        Map<String, Object> backend = new LinkedHashMap<>();
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
                    sb.append(extractModelContent(jsonDf.data()));
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
    private static String extractDelta(Object payload) {
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

    @SuppressWarnings("unchecked")
    private static String extractDisplayText(Object payload) {
        if (payload == null) {
            return "";
        }
        if (payload instanceof String s) {
            return s;
        }
        if (payload instanceof Map<?, ?> map) {
            return extractModelContent((Map<String, Object>) map);
        }
        return "";
    }

    // =========================================================================
    // 清理
    // =========================================================================

    private static void cleanup() {
        try {
            Runner.stop();
        } catch (Exception ignored) {
        }
    }

    // =========================================================================
    // 数据结构
    // =========================================================================

    /** 流式调用结果摘要，用于 Phase 汇总。 */
    private static final class StreamResult {
        final int chunkCount;
        final String answer;
        final List<String> toolCalls;
        final boolean hasError;
        final String errorMsg;

        StreamResult(int chunkCount, String answer, List<String> toolCalls, boolean hasError, String errorMsg) {
            this.chunkCount = chunkCount;
            this.answer = answer;
            this.toolCalls = toolCalls;
            this.hasError = hasError;
            this.errorMsg = errorMsg;
        }
    }

    // =========================================================================
    // 对话状态（交互模式）
    // =========================================================================

    private static final class ConversationState {
        String sessionId;
        AgentSessionApi session;
        int turnCount;
    }
}

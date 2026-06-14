/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package myexample.multiquery;

import com.openjiuwen.core.context.context.ContextUtils;
import com.openjiuwen.core.context.context.SessionModelContext;
import com.openjiuwen.core.context.schema.ContextEngineConfig;
import com.openjiuwen.core.controller.schema.ControllerOutputPayload;
import com.openjiuwen.core.controller.schema.DataFrame;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.tool.function.AnnotatedToolFactory;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
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
 * ShortMemoryDemo — 多轮对话 + 短期记忆（只保留最近 3 轮上下文）。
 *
 * <p>与 MultiTurnDemo 的区别：
 * <ul>
 *   <li>使用默认内存持久化（无需 Redis），程序退出后数据丢失</li>
 *   <li>每轮对话结束后主动截断 buffer，只保留最近 3 轮消息</li>
 *   <li>截断后的数据同步到 Session，确保 session 中也只保存最近 3 轮</li>
 *   <li>LLM 调用时只会看到最近 3 轮的对话历史</li>
 * </ul>
 *
 * <h3>运行方式</h3>
 * <pre>{@code
 *   mvn compile exec:java \
 *     -Dexec.mainClass=myexample.multiquery.ShortMemoryDemo \
 *     -Dopenjiuwen.example.config=examples/apiconfig.json
 * }</pre>
 *
 * <h3>交互命令</h3>
 * <pre>
 *   直接输入问题  — 在当前会话中对话
 *   :new          — 开始新会话
 *   :status       — 查看当前会话与上下文状态
 *   :help         — 显示帮助
 *   quit / exit   — 退出程序
 * </pre>
 */
public final class ShortMemoryDemo {

    /** 短期记忆保留的对话轮数 */
    private static final int KEEP_ROUND_NUM = 3;

    private static final Path WORKSPACE_ROOT = Path.of(".").toAbsolutePath().normalize();
    private static DeepAgent agent;

    static {
        // 确保 UTF-8 输出
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    private ShortMemoryDemo() {
    }

    public static void main(String[] args) {
        Runner.start();          // 使用默认内存模式（无需 Redis）
        agent = buildAgent();

        System.out.println("==============================================");
        System.out.println("  ShortMemoryDemo — 多轮对话 (只保留最近3轮)");
        System.out.println("  命令: :new :status :help quit/exit");
        System.out.println("==============================================");
        System.out.println();

        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        try {
            runLoop(scanner);
        } finally {
            scanner.close();
            Runner.stop();
        }
    }

    // ========================= 对话主循环 =========================

    private static void runLoop(Scanner scanner) {
        ConversationState state = new ConversationState();

        while (true) {
            System.out.print("[" + (state.sessionId != null
                    ? state.sessionId.substring(Math.max(0, state.sessionId.length() - 8)) + " #" + state.turnCount
                    : "new") + "] >> ");
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            if (line.startsWith(":")) { handleCommand(line, state); System.out.println(); continue; }
            if ("quit".equalsIgnoreCase(line) || "exit".equalsIgnoreCase(line)) { System.out.println("Bye!"); break; }

            try {
                executeTurn(line, state);
                System.out.println();
            } catch (Exception e) {
                System.err.println("[ERROR] " + e.getMessage());
            }
        }
    }

    // ========================= 单轮对话 =========================

    private static void executeTurn(String userInput, ConversationState state) {
        // 1. 创建/复用 Session
        AgentSessionApi session = ensureSession(state);

        // 2. 构建输入
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("query", userInput);
        inputs.put("conversation_id", state.sessionId);

        System.out.println("---------- Agent 回复 ----------");

        // 3. 流式执行并打印输出
        Iterator<Object> stream = agent.stream(inputs, session, List.of(StreamMode.OUTPUT));
        consumeStream(stream);

        System.out.println("---------- 本轮结束 ----------");

        // 4. 截断 buffer：只保留最近 KEEP_ROUND_NUM 轮消息
        truncateBuffer(state);

        // 5. 截断后同步到 Session（确保持久化也只保存最近 N 轮）
        agent.getAgent().getContextEngine().saveContexts(session, null);

        // 6. 更新轮次
        state.turnCount++;
        printStatus(state);
    }

    // ========================= Buffer 截断 =========================

    /**
     * 截断 ContextEngine 的消息 buffer，只保留最近 {@code KEEP_ROUND_NUM} 轮。
     *
     * <p>截断后：
     * <ul>
     *   <li>buffer 中物理删除旧消息，{@code saveState()} 只返回最近 N 轮</li>
     *   <li>后续 LLM 调用时 {@code getContextWindow()} 只看到最近 N 轮</li>
     *   <li>Session 中也只保存最近 N 轮（通过 saveContexts 同步）</li>
     * </ul>
     */
    private static void truncateBuffer(ConversationState state) {
        SessionModelContext modelCtx = (SessionModelContext) agent.getAgent().getContextEngine()
                .getContext(null, state.sessionId);
        if (modelCtx == null) return;

        List<BaseMessage> allMsgs = modelCtx.getMessages(null, true);
        int roundIndex = ContextUtils.findLastNDialogueRound(allMsgs, KEEP_ROUND_NUM);

        if (roundIndex > 0) {
            // 从第 roundIndex 条开始截取，丢弃更早的消息
            List<BaseMessage> truncated = new ArrayList<>(allMsgs.subList(roundIndex, allMsgs.size()));
            modelCtx.setMessages(truncated, true);
            System.out.println("[INFO] buffer 截断: " + allMsgs.size() + " → " + truncated.size()
                    + " 条消息（保留最近 " + KEEP_ROUND_NUM + " 轮）");
        }
    }

    // ========================= Session 管理 =========================

    /**
     * 创建新 Session 或复用已有 Session。
     * 每轮创建新 session 对象（获取新 StreamQueue），通过 state 迁移上一轮的上下文。
     */
    private static AgentSessionApi ensureSession(ConversationState state) {
        if (state.sessionId == null) {
            state.sessionId = "short-mem-" + System.currentTimeMillis();
        }

        AgentSessionApi newSession = new AgentSessionApi(
                state.sessionId, null, agent.getCard(), List.of(StreamMode.OUTPUT));

        if (state.session != null) {
            // 迁移上一轮的会话状态（截断后的上下文已包含在内）
            newSession.getInner().state().setState(state.session.getInner().state().getState());
        } else {
            System.out.println("[INFO] 创建新会话: " + state.sessionId);
        }

        state.session = newSession;
        return newSession;
    }

    // ========================= 命令处理 =========================

    private static void handleCommand(String cmd, ConversationState state) {
        String action = cmd.split("\\s+", 2)[0].toLowerCase();
        switch (action) {
            case ":new" -> {
                state.reset();
                System.out.println("[OK] 已开始新会话");
            }
            case ":status" -> printStatus(state);
            case ":help" -> System.out.println("""
                    命令列表:
                      :new    — 开始新会话（清空上下文）
                      :status — 查看会话与上下文状态
                      :help   — 显示此帮助
                      quit    — 退出程序

                    直接输入问题即可对话，上下文只保留最近 """ + KEEP_ROUND_NUM + " 轮。");
            default -> System.out.println("未知命令: " + action);
        }
    }

    private static void printStatus(ConversationState state) {
        System.out.println("[INFO] sessionId=" + state.sessionId
                + " | 已进行 " + state.turnCount + " 轮 | 保留最近 " + KEEP_ROUND_NUM + " 轮");

        SessionModelContext modelCtx = (SessionModelContext) agent.getAgent().getContextEngine()
                .getContext(null, state.sessionId);
        if (modelCtx != null) {
            int msgs = modelCtx.saveState().get("messages") instanceof List<?> list ? list.size() : 0;
            System.out.println("[DEBUG] buffer消息数=" + msgs + " | 保留轮数=" + KEEP_ROUND_NUM);
        }
    }

    // ========================= Agent 构建 =========================

    private static DeepAgent buildAgent() {
        List<Object> tools = new ArrayList<>(AnnotatedToolFactory.scan(new MathTools()));

        AgentCard card = AgentCard.builder()
                .id("short_memory_demo")
                .name("short_memory_demo")
                .description("ShortMemoryDemo — 短期记忆多轮对话")
                .build();

        Map<String, String> llmConfig = SharedExampleApiConfigLoader.load();

        DeepAgentConfig config = DeepAgentConfig.builder()
                .enableTaskLoop(true)
                .enableTaskPlanning(true)
                .systemPrompt("""
                        你是一个智能助手，可以进行数学计算并回答各种问题。
                        可用工具: add(加), subtract(减), multiply(乘), divide(除)。
                        记住对话历史，保持上下文连贯性。""")
                .maxIterations(20)
                .language("cn")
                .model(Map.of("model", llmConfig.getOrDefault("MODEL_NAME", "glm-5")))
                .backend(buildBackendConfig(llmConfig))
                .restrictToWorkDir(false)
                .tools(tools)
                .build();

        Workspace workspace = Workspace.builder()
                .rootPath(WORKSPACE_ROOT.toString())
                .language("cn")
                .build();

        DeepAgent agent = HarnessFactory.createDeepAgent(card, config, workspace);

        // 配置短期记忆：保留最近 3 轮对话，最多 200 条消息
        ReActAgentConfig reactConfig = (ReActAgentConfig) agent.getAgent().getConfig();
        reactConfig.setContextEngineConfig(ContextEngineConfig.builder()
                .maxContextMessageNum(200)
                .defaultWindowRoundNum(KEEP_ROUND_NUM)
                .build());
        agent.getAgent().configure(reactConfig);

        System.out.println("[INFO] Agent 构建完成 | model=" + llmConfig.getOrDefault("MODEL_NAME", "unknown")
                + " | 短期记忆=" + KEEP_ROUND_NUM + "轮");

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

    // ========================= 流式输出消费 =========================

    private static void consumeStream(Iterator<Object> stream) {
        boolean hasOutput = false;
        while (stream.hasNext()) {
            Object item = stream.next();
            if (!(item instanceof OutputSchema chunk)) continue;

            Object payload = chunk.getPayload();
            String type = chunk.getType();

            if (payload instanceof ControllerOutputPayload ctrl) {
                String text = extractText(ctrl.getData());
                if (ControllerOutputPayload.ALL_TASKS_PROCESSED.equals(ctrl.getType())) {
                    System.out.println("\n[完成] " + text);
                } else if (!text.isBlank()) {
                    System.out.print(text);
                    System.out.flush();
                    hasOutput = true;
                }
                continue;
            }

            switch (type) {
                case "answer" -> {
                    String t = extractText(payload);
                    if (!t.isEmpty()) { System.out.print(t); System.out.flush(); hasOutput = true; }
                }
                case "tool_result" -> System.out.println("[tool] " + extractText(payload));
                case "error" -> System.out.println("[error] " + extractText(payload));
                case "__end__" -> {}
                default -> {
                    String t = extractText(payload);
                    if (!t.isBlank()) System.out.println("[" + type + "] " + t);
                }
            }
        }
        if (!hasOutput) System.out.println("(无输出)");
    }

    // --- 文本提取辅助方法 ---
    private static String extractText(Object payload) {
        if (payload instanceof String s) return s;
        if (payload instanceof Map<?, ?> m) {
            for (String key : List.of("content", "delta", "reasoning_content", "result", "output")) {
                Object v = m.get(key);
                if (v instanceof String s && !s.isBlank()) return s;
            }
        }
        if (payload instanceof ControllerOutputPayload ctrl) return extractText(ctrl.getData());
        return "";
    }

    private static String extractText(List<DataFrame> frames) {
        if (frames == null) return "";
        StringBuilder sb = new StringBuilder();
        for (DataFrame df : frames) {
            if (df instanceof DataFrame.TextDataFrame t && t.text() != null) sb.append(t.text());
            else if (df instanceof DataFrame.JsonDataFrame j && j.data() != null) sb.append(extractText(j.data()));
        }
        return sb.toString();
    }

    // ========================= 对话状态 =========================

    private static final class ConversationState {
        String sessionId;
        AgentSessionApi session;
        int turnCount;

        void reset() { sessionId = null; session = null; turnCount = 0; }
    }
}

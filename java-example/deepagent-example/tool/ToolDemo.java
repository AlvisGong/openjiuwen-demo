/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package myexample.stream;

import com.openjiuwen.core.controller.schema.ControllerOutputPayload;
import com.openjiuwen.core.controller.schema.DataFrame;
import com.openjiuwen.core.foundation.tool.function.AnnotatedToolFactory;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
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
 * StreamDemo — 基于 DeepAgent 的流式输出与多轮对话示例，使用框架内置 Redis Checkpointer 持久化会话。
 *
 * <h3>核心特性</h3>
 * <ul>
 *   <li><b>流式输出</b> — 实时消费 Agent 的流式输出</li>
 *   <li><b>多轮对话</b> — 同一 Session 内多轮交互，Agent 记住历史上下文</li>
 *   <li><b>Redis 持久化</b> — 通过 {@code RunnerConfig} 配置框架内置 RedisCheckpointer，
 *        会话状态自动持久化，无需手工序列化</li>
 *   <li><b>会话恢复</b> — 使用相同 sessionId 创建会话时自动从 Redis 恢复状态</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 *   # 交互式多轮对话
 *   java -Dredis.checkpointer.url=redis://localhost:6379/0 \
 *        myexample.stream.StreamDemo
 *
 *   # 单次问答
 *   java -Dredis.checkpointer.url=redis://localhost:6379/0 \
 *        myexample.stream.StreamDemo --query "计算 3+5"
 *
 *   # 恢复历史会话
 *   java -Dredis.checkpointer.url=redis://localhost:6379/0 \
 *        myexample.stream.StreamDemo --resume <sessionId>
 * }</pre>
 *
 * <h3>交互命令</h3>
 * <pre>
 *   直接输入问题       — 当前会话中发起一轮对话
 *   :new               — 开始新会话
 *   :status            — 查看当前会话状态
 *   :help              — 显示帮助
 *   quit / exit        — 退出程序
 * </pre>
 */
public final class ToolDemo {

    private static final Path WORKSPACE_ROOT = Path.of(".").toAbsolutePath().normalize();

    private static volatile boolean redisCheckpointerInitialized;
    private static DeepAgent agent;

    static {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    private ToolDemo() {
    }

    // =========================================================================
    // 入口
    // =========================================================================

    public static void main(String[] args) {
        configureRedisCheckpointer();
        agent = buildAgent();

        String query = null;
        String resumeSessionId = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--query" -> { if (i + 1 < args.length) query = args[++i]; }
                case "--resume" -> { if (i + 1 < args.length) resumeSessionId = args[++i]; }
            }
        }

        if (query != null || resumeSessionId != null) {
            runSingleQuery(query, resumeSessionId);
            cleanup();
            return;
        }

        System.out.println("============================================");
        System.out.println("  StreamDemo — DeepAgent 流式输出与多轮对话");
        System.out.println("  命令: :new :status :help quit/exit");
        System.out.println("============================================");
        System.out.println();

        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        try {
            runConversationLoop(scanner);
        } finally {
            scanner.close();
            cleanup();
        }
    }

    // =========================================================================
    // 对话主循环
    // =========================================================================

    private static void runConversationLoop(Scanner scanner) {
        ConversationState state = new ConversationState();

        while (true) {
            printPrompt(state);
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith(":")) {
                handleCommand(line, state);
                System.out.println();
                continue;
            }

            if ("quit".equalsIgnoreCase(line) || "exit".equalsIgnoreCase(line)) {
                System.out.println("Bye!");
                break;
            }

            try {
                executeConversationTurn(line, state);
                System.out.println();
            } catch (Exception e) {
                System.err.println("[ERROR] 对话执行失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // =========================================================================
    // 单轮对话执行
    // =========================================================================

    private static void executeConversationTurn(String userInput, ConversationState state) {
        // 1. 确保 Session 存在（新建或复用）
        AgentSessionApi session = ensureSession(state);

        // 2. 准备输入
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("query", userInput);
        inputs.put("conversation_id", state.sessionId);

        System.out.println("---------- Agent 回复 ----------");
        System.out.flush();

        // 3. 流式执行
        Iterator<Object> stream = agent.stream(inputs, session, List.of(StreamMode.OUTPUT));

        int chunkCount = 0;
        boolean hasOutput = false;

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
                    System.out.println();
                    System.out.println("[完成] " + extractTextFromDataFrames(dataList));
                    continue;
                }

                String text = extractTextFromDataFrames(dataList);
                if (!text.isBlank()) {
                    System.out.print(text);
                    System.out.flush();
                    hasOutput = true;
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
                        hasOutput = true;
                    }
                }
                case "tool_result" -> {
                    System.out.print("[tool:" + extractToolName(payload) + "] ");
                    System.out.println(extractToolResult(payload));
                    System.out.flush();
                }
                case "error" -> {
                    System.out.println("[error] " + extractDisplayText(payload));
                    System.out.flush();
                }
                case "__end__" -> { /* 流结束 */ }
                default -> {
                    String text = extractDisplayText(payload);
                    if (!text.isBlank()) {
                        System.out.println("[" + type + "] " + text);
                    }
                }
            }
        }

        if (!hasOutput) {
            System.out.println("(无输出)");
        }
        System.out.println();
        System.out.println("---------- 本轮结束 (chunks: " + chunkCount + ") ----------");

        // 4. 更新轮次计数
        state.turnCount++;
        System.out.println("[INFO] sessionId=" + state.sessionId + " | 已进行 " + state.turnCount + " 轮对话");
    }

    // =========================================================================
    // 单次查询（CLI 参数模式）
    // =========================================================================

    private static void runSingleQuery(String query, String resumeSessionId) {
        ConversationState state = new ConversationState();
        if (resumeSessionId != null && !resumeSessionId.isBlank()) {
            state.sessionId = resumeSessionId;
        }
        String q = query != null ? query : "你好";
        executeConversationTurn(q, state);
    }

    // =========================================================================
    // Session 管理（框架内置 Checkpointer 自动持久化）
    // =========================================================================

    /**
     * 确保 Session 可用。
     *
     * <p>每次都创建新的 session 对象以获得全新的 StreamQueue，
     * 但复用相同 sessionId 并通过 {@code copySessionState()} 迁移上一轮的全局状态，
     * 这样 ContextEngine 能找到之前的上下文，对话历史得以延续。
     */
    private static AgentSessionApi ensureSession(ConversationState state) {
        if (state.sessionId == null) {
            state.sessionId = "stream-demo-" + System.currentTimeMillis();
        }

        AgentSessionApi oldSession = state.session;
        AgentSessionApi newSession = new AgentSessionApi(
                state.sessionId, null, agent.getCard(), List.of(StreamMode.OUTPUT));

        if (oldSession != null) {
            // 迁移上一轮的会话状态（包含 context 消息历史）
            newSession.getInner().state().setState(oldSession.getInner().state().getState());
        } else {
            System.out.println("[INFO] 创建新会话: " + state.sessionId
                    + " (Redis 持久化: " + (hasRedisCheckpointer() ? "ON" : "OFF") + ")");
        }

        state.session = newSession;
        return newSession;
    }

    // =========================================================================
    // 命令处理
    // =========================================================================

    private static void handleCommand(String cmd, ConversationState state) {
        String action = cmd.split("\\s+", 2)[0].toLowerCase();

        switch (action) {
            case ":new" -> {
                state.reset();
                System.out.println("[OK] 已开始新会话");
            }
            case ":status" -> {
                if (state.sessionId == null) {
                    System.out.println("当前无活跃会话");
                } else {
                    System.out.println("当前会话ID: " + state.sessionId);
                    System.out.println("已进行轮次: " + state.turnCount);
                    System.out.println("Redis 持久化: " + (hasRedisCheckpointer() ? "已启用" : "未启用（内存模式）"));
                }
            }
            case ":help" -> {
                System.out.println("""
                        命令列表:
                          :new               — 开始新会话
                          :status            — 查看当前会话状态
                          :help              — 显示此帮助
                          quit / exit        — 退出程序

                        直接输入问题即可在当前会话中进行多轮对话。
                        Redis 配置: -Dredis.checkpointer.url=redis://localhost:6379/0""");
            }
            default -> System.out.println("未知命令: " + action + " (输入 :help 查看帮助)");
        }
    }

    private static void printPrompt(ConversationState state) {
        if (state.sessionId != null) {
            System.out.print("[" + state.sessionId.substring(Math.max(0, state.sessionId.length() - 16))
                    + " #" + state.turnCount + "] >> ");
        } else {
            System.out.print(">> ");
        }
    }

    // =========================================================================
    // Redis Checkpointer 初始化（框架内置）
    // =========================================================================

    /**
     * 通过 {@link RunnerConfig} 配置框架内置的 RedisCheckpointer。
     *
     * <p>JVM 参数:
     * <ul>
     *   <li>{@code -Dredis.checkpointer.url=redis://localhost:6379/0}</li>
     *   <li>{@code -Dredis.checkpointer.ttl=3600}（秒，默认 3600）</li>
     * </ul>
     *
     * <p>配置后，框架会在每次 Agent 执行前后自动持久化/恢复 Session 状态，
     * 业务代码无需做任何手工序列化。
     */
    private static synchronized void configureRedisCheckpointer() {
        if (redisCheckpointerInitialized) {
            return;
        }
        String redisUrl = System.getProperty("redis.checkpointer.url", "redis://localhost:6379/0").trim();

        int ttlSeconds = Integer.parseInt(System.getProperty("redis.checkpointer.ttl", "3600"));

        Map<String, Object> connConf = new LinkedHashMap<>();
        connConf.put("url", redisUrl);

        Map<String, Object> ttlConf = new LinkedHashMap<>();
        ttlConf.put("default_ttl", ttlSeconds);
        ttlConf.put("refresh_on_read", true);

        Map<String, Object> redisConf = new LinkedHashMap<>();
        redisConf.put("connection", connConf);
        redisConf.put("ttl", ttlConf);

        Map<String, Object> checkpointerConfig = new LinkedHashMap<>();
        checkpointerConfig.put("type", "redis");
        checkpointerConfig.put("conf", redisConf);

        try {
            RunnerConfig config = RunnerConfig.builder()
                    .distributedMode(false)
                    .checkpointerConfig(checkpointerConfig)
                    .build();
            Runner.setConfig(config);
            Runner.start();
            System.out.println("[INFO] Redis 已启用: " + redisUrl + " | TTL: " + ttlSeconds + "s");
        } catch (Exception e) {
            System.err.println("[WARN] Redis 初始化失败: " + e.getMessage());
            System.err.println("[WARN] 回退到内存模式");
        }
        redisCheckpointerInitialized = true;
    }

    private static boolean hasRedisCheckpointer() {
        return redisCheckpointerInitialized
                && System.getProperty("redis.checkpointer.url", "redis://localhost:6379/0").trim().length() > 0;
    }

    // =========================================================================
    // Agent 构建
    // =========================================================================

    private static DeepAgent buildAgent() {
        List<LocalFunction> scannedTools = AnnotatedToolFactory.scan(new MathTools());
        List<Object> tools = new ArrayList<>(scannedTools);

        String systemPrompt = """
                你是一个数学计算助手，可以调用加减乘除四个工具完成算术运算。

                可用工具：
                - add      — 加法 a + b
                - subtract — 减法 a - b
                - multiply — 乘法 a * b
                - divide   — 除法 a / b

                工作流程：
                1. 分析用户的计算需求，确定需要哪些运算
                2. 直接调用对应的工具函数（add/subtract/multiply/divide）
                3. 根据工具返回的结果回答用户
                4. 如果有多步计算，依次调用工具并用上一步的结果继续

                注意事项：
                - 除法时注意除数不能为零
                - 计算完成后直接输出结果
                """;

        AgentCard card = AgentCard.builder()
                .id("stream_demo")
                .name("stream_demo")
                .description("StreamDemo — 流式输出与多轮对话示例")
                .build();

        Map<String, String> llmConfig = SharedExampleApiConfigLoader.load();

        DeepAgentConfig config = DeepAgentConfig.builder()
                .enableTaskLoop(true)
                .enableTaskPlanning(true)
                .systemPrompt(systemPrompt)
                .maxIterations(20)
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
        return HarnessFactory.createDeepAgent(card, config, workspace);
    }

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
    // 对话状态
    // =========================================================================

    private static final class ConversationState {
        String sessionId;
        AgentSessionApi session;
        int turnCount;

        void reset() {
            sessionId = null;
            session = null;
            turnCount = 0;
        }
    }
}

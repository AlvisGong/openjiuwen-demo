/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package myexample.ctx;

import com.openjiuwen.core.context.ContextEngine;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.context.context.SessionModelContext;
import com.openjiuwen.core.context.processor.compressor.CurrentRoundCompressorConfig;
import com.openjiuwen.core.context.processor.compressor.DialogueCompressorConfig;
import com.openjiuwen.core.context.processor.compressor.RoundLevelCompressorConfig;
import com.openjiuwen.core.context.processor.offloader.MessageSummaryOffloaderConfig;
import com.openjiuwen.core.context.schema.ContextEngineConfig;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.ToolMessage;
import com.openjiuwen.core.foundation.tool.function.AnnotatedToolFactory;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.controller.schema.ControllerOutputPayload;
import com.openjiuwen.core.controller.schema.DataFrame;
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
 * ContextCompressionDemo — 展示 Agent 多轮循环执行中，对话历史不断增长，
 * 最终超出模型窗口时，上下文压缩(Context Compression)自动触发，确保 Agent 不因 token 限制中断。
 *
 * <h3>核心流程</h3>
 * <pre>
 *   Agent ReAct 循环：Reasoning → Acting → Observation → Repeat
 *       ↓ 每轮迭代产生多条消息 (UserMessage + AssistantMessage + ToolMessage)
 *       ↓ 对话历史持续累积，token 数不断增长
 *       ↓ 当总 token 数超过阈值时，ContextProcessor 链自动触发:
 *       ├─ MessageSummaryOffloader    → 卸载大块工具结果，生成摘要
 *       ├─ DialogueCompressor         → 压缩历史 ReAct 对话块为 memory block
 *       ├─ CurrentRoundCompressor     → 增量压缩当前轮次为 memory block
 *       ├─ RoundLevelCompressor       → 兜底级 token 预算压缩
 *       ↓ 压缩后的 memory block 替换原始消息，token 数大幅减少
 *       ↓ Agent 继续正常执行，不会因超窗口而中断
 * </pre>
 *
 * <h3>运行方式</h3>
 * <pre>{@code
 *   mvn compile exec:java \
 *     -Dexec.mainClass=myexample.ctx.ContextCompressionDemo \
 *     -Dopenjiuwen.example.config=examples/apiconfig.json
 * }</pre>
 *
 * <h3>交互命令</h3>
 * <pre>
 *   直接输入问题  — 在当前会话中对话
 *   :new          — 开始新会话（清空上下文）
 *   :status       — 查看完整的上下文压缩状态
 *   :auto         — 自动执行10轮对话（快速演示压缩触发全过程）
 *   :help         — 显示帮助
 *   quit / exit   — 退出程序
 * </pre>
 */
public final class ContextCompressionDemo {

    // Memory Block 标识前缀（与 CurrentRoundCompressor / DialogueCompressor 内部常量一致）
    private static final String CURRENT_ROUND_MEMORY_BLOCK = "[CURRENT_ROUND_MEMORY_BLOCK]";
    private static final String DIALOGUE_MEMORY_BLOCK = "[DIALOGUE_MEMORY_BLOCK]";

    /** 模拟多轮对话的自动问题列表（用于 :auto 命令） */
    private static final List<String> AUTO_QUESTIONS = List.of(
            "计算 123 + 456 的结果",
            "把上面的结果乘以 789",
            "再减去 10000",
            "计算 999 除以 3",
            "把第2步和第4步的结果相加",
            "计算 2024 乘以 2025",
            "把第5步的结果除以第6步的结果",
            "计算 1000 减去 500 再加上 200",
            "把第7步的结果乘以 100",
            "计算第1步的结果加上第8步的结果再减去第9步的结果"
    );

    private static final Path WORKSPACE_ROOT = Path.of(".").toAbsolutePath().normalize();
    private static DeepAgent agent;
    private static ContextEngine contextEngine;

    static {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    private ContextCompressionDemo() {
    }

    // =========================================================================
    // 入口
    // =========================================================================

    public static void main(String[] args) {
        Runner.start();
        agent = buildAgent();
        contextEngine = agent.getAgent().getContextEngine();

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  ContextCompressionDemo — 上下文压缩自动触发演示      ║");
        System.out.println("║  展示: 多轮对话 → token累积 → 自动压缩 → Agent不中断  ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.println("║  命令: :new :status :auto :help quit/exit              ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        try {
            runConversationLoop(scanner);
        } finally {
            scanner.close();
            Runner.stop();
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
            if (line.isEmpty()) continue;

            if (line.startsWith(":")) { handleCommand(line, state); System.out.println(); continue; }
            if ("quit".equalsIgnoreCase(line) || "exit".equalsIgnoreCase(line)) {
                System.out.println("Bye!");
                break;
            }

            try {
                executeConversationTurn(line, state);
                System.out.println();
            } catch (Exception e) {
                System.err.println("[ERROR] " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // =========================================================================
    // 单轮对话执行
    // =========================================================================

    private static void executeConversationTurn(String userInput, ConversationState state) {
        AgentSessionApi session = ensureSession(state);
        printBeforeContextState(state);

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("query", userInput);
        inputs.put("conversation_id", state.sessionId);

        System.out.println("---------- Agent 回复 ----------");
        System.out.flush();

        Iterator<Object> stream = agent.stream(inputs, session, List.of(StreamMode.OUTPUT));
        int chunkCount = 0;
        boolean hasOutput = false;

        while (stream.hasNext()) {
            Object item = stream.next();
            chunkCount++;

            if (!(item instanceof OutputSchema)) continue;
            OutputSchema chunk = (OutputSchema) item;
            Object payload = chunk.getPayload();
            String type = chunk.getType();

            if (payload instanceof ControllerOutputPayload) {
                ControllerOutputPayload ctrl = (ControllerOutputPayload) payload;
                String text = extractTextFromDataFrames(ctrl.getData());
                if (ControllerOutputPayload.ALL_TASKS_PROCESSED.equals(ctrl.getType())) {
                    System.out.println();
                    System.out.println("[完成] " + text);
                } else if (!text.isBlank()) {
                    System.out.print(text);
                    System.out.flush();
                    hasOutput = true;
                }
                continue;
            }

            switch (type) {
                case "answer": {
                    String t = extractDelta(payload);
                    if (!t.isEmpty()) { System.out.print(t); System.out.flush(); hasOutput = true; }
                    break;
                }
                case "tool_result": {
                    System.out.print("[tool:" + extractToolName(payload) + "] ");
                    System.out.println(extractToolResult(payload));
                    System.out.flush();
                    break;
                }
                case "error":
                    System.out.println("[error] " + extractDisplayText(payload));
                    break;
                case "__end__":
                    break;
                default: {
                    String t = extractDisplayText(payload);
                    if (!t.isBlank()) System.out.println("[" + type + "] " + t);
                    break;
                }
            }
        }

        if (!hasOutput) System.out.println("(无输出)");
        System.out.println("---------- 本轮结束 (chunks: " + chunkCount + ") ----------");

        printAfterContextState(state);
        state.turnCount++;
    }

    // =========================================================================
    // 上下文状态监控 — 核心：展示压缩触发过程
    // =========================================================================

    private static void printBeforeContextState(ConversationState state) {
        SessionModelContext modelCtx = getModelContext(state);
        if (modelCtx == null) {
            System.out.println("[BEFORE] 首轮对话，上下文为空");
            state.beforeMessages = 0;
            state.beforeTokens = 0;
            state.beforeMemoryBlocks = 0;
            return;
        }

        List<BaseMessage> messages = modelCtx.getMessages(null, true);
        int totalTokens = estimateTokens(messages);
        int memoryBlockCount = countMemoryBlocks(messages);
        int toolMsgCount = countToolMessages(messages);

        System.out.println("┌─ 执行前上下文状态 ─────────────────────────────┐");
        System.out.println("│ 消息总数: " + messages.size()
                + " | 估算tokens: " + totalTokens
                + " | 工具消息: " + toolMsgCount);
        System.out.println("│ Memory Block: " + memoryBlockCount + " 个 (压缩痕迹)");
        System.out.println("│ 轮次: #" + state.turnCount);
        System.out.println("└──────────────────────────────────────────────────┘");

        state.beforeMessages = messages.size();
        state.beforeTokens = totalTokens;
        state.beforeMemoryBlocks = memoryBlockCount;
    }

    private static void printAfterContextState(ConversationState state) {
        SessionModelContext modelCtx = getModelContext(state);
        if (modelCtx == null) return;

        List<BaseMessage> messages = modelCtx.getMessages(null, true);
        int totalTokens = estimateTokens(messages);
        int memoryBlockCount = countMemoryBlocks(messages);
        int toolMsgCount = countToolMessages(messages);

        int msgDelta = messages.size() - state.beforeMessages;
        int tokenDelta = totalTokens - state.beforeTokens;
        int blockDelta = memoryBlockCount - state.beforeMemoryBlocks;

        String compressionIndicator = "";
        if (blockDelta > 0) {
            compressionIndicator = " ★ 压缩已触发!";
        } else if (tokenDelta < 0) {
            compressionIndicator = " ★ 压缩生效(tokens减少)";
        }

        System.out.println("┌─ 执行后上下文状态 ─────────────────────────────┐");
        System.out.println("│ 消息总数: " + messages.size()
                + " (变化: " + msgDelta + ")"
                + " | 估算tokens: " + totalTokens
                + " (变化: " + tokenDelta + ")");
        System.out.println("│ 工具消息: " + toolMsgCount
                + " | Memory Block: " + memoryBlockCount
                + " (新增: " + blockDelta + ")");
        System.out.println("│ 轮次: #" + state.turnCount + compressionIndicator);

        if (memoryBlockCount > 0) {
            System.out.println("│ ─── Memory Block 摘要 ───");
            List<String> blockSummaries = extractMemoryBlockSummaries(messages);
            for (int i = 0; i < Math.min(3, blockSummaries.size()); i++) {
                String summary = blockSummaries.get(i);
                String truncated = summary.length() > 120
                        ? summary.substring(0, 120) + "..."
                        : summary;
                System.out.println("│  [Block#" + (i + 1) + "] " + truncated);
            }
            if (blockSummaries.size() > 3) {
                System.out.println("│  ... 共 " + blockSummaries.size() + " 个 Block");
            }
        }

        System.out.println("└──────────────────────────────────────────────────┘");
    }

    // =========================================================================
    // 上下文辅助方法
    // =========================================================================

    private static SessionModelContext getModelContext(ConversationState state) {
        if (state.sessionId == null) return null;
        ModelContext ctx = contextEngine.getContext(null, state.sessionId);
        if (ctx instanceof SessionModelContext) {
            return (SessionModelContext) ctx;
        }
        return null;
    }

    private static int estimateTokens(List<BaseMessage> messages) {
        int total = 0;
        for (BaseMessage msg : messages) {
            String content = msg.getContentAsString();
            if (content != null) {
                total += content.length() / 4;
            }
            if (msg instanceof AssistantMessage) {
                AssistantMessage assistant = (AssistantMessage) msg;
                if (assistant.getToolCalls() != null) {
                    for (ToolCall call : assistant.getToolCalls()) {
                        String args = call.getArguments() instanceof String ? (String) call.getArguments() : "";
                        total += args.length() / 4;
                        total += (call.getName() != null ? call.getName().length() / 4 : 0);
                    }
                }
            }
        }
        return total;
    }

    private static int countMemoryBlocks(List<BaseMessage> messages) {
        int count = 0;
        for (BaseMessage msg : messages) {
            String content = msg.getContentAsString();
            if (content != null
                    && (content.startsWith(CURRENT_ROUND_MEMORY_BLOCK)
                        || content.startsWith(DIALOGUE_MEMORY_BLOCK))) {
                count++;
            }
        }
        return count;
    }

    private static int countToolMessages(List<BaseMessage> messages) {
        int count = 0;
        for (BaseMessage msg : messages) {
            if (msg instanceof ToolMessage) count++;
        }
        return count;
    }

    private static List<String> extractMemoryBlockSummaries(List<BaseMessage> messages) {
        List<String> summaries = new ArrayList<>();
        for (BaseMessage msg : messages) {
            String content = msg.getContentAsString();
            if (content == null) continue;
            if (content.startsWith(CURRENT_ROUND_MEMORY_BLOCK)
                    || content.startsWith(DIALOGUE_MEMORY_BLOCK)) {
                int summaryIdx = content.indexOf("Summary:\n");
                if (summaryIdx >= 0) {
                    summaries.add(content.substring(summaryIdx + "Summary:\n".length()).trim());
                } else {
                    summaries.add(content.trim());
                }
            }
        }
        return summaries;
    }

    // =========================================================================
    // Session 管理
    // =========================================================================

    private static AgentSessionApi ensureSession(ConversationState state) {
        if (state.sessionId == null) {
            state.sessionId = "ctx-demo-" + System.currentTimeMillis();
        }

        AgentSessionApi newSession = AgentSessionApi.create(
                state.sessionId, null, agent.getCard(), List.of(StreamMode.OUTPUT));

        if (state.session != null) {
            newSession.getInner().state().setState(state.session.getInner().state().getState());
        } else {
            System.out.println("[INFO] 创建新会话: " + state.sessionId);
        }

        state.session = newSession;
        return newSession;
    }

    // =========================================================================
    // 命令处理
    // =========================================================================

    private static void handleCommand(String cmd, ConversationState state) {
        String[] parts = cmd.split("\\s+", 2);
        String action = parts[0].toLowerCase();

        switch (action) {
            case ":new":
                state.reset();
                System.out.println("[OK] 已开始新会话");
                break;
            case ":status":
                printFullStatus(state);
                break;
            case ":auto":
                runAutoQuestions(state);
                break;
            case ":help":
                System.out.println("""
                        命令列表:
                          :new    — 开始新会话（清空上下文）
                          :status — 查看完整的上下文压缩状态
                          :auto   — 自动执行10轮对话（快速演示压缩触发全过程）
                          :help   — 显示此帮助
                          quit    — 退出程序

                        本 Demo 展示的核心机制:
                          1. Agent 多轮循环 → 对话历史不断增长 → token 数上升
                          2. token 数超过阈值 → ContextProcessor 自动触发压缩
                          3. 压缩后 token 数大幅减少 → Agent 继续运行不中断
                          4. 压缩产生的 Memory Block 替换原始消息

                        处理器链(演示用低阈值配置):
                          MessageSummaryOffloader  → tokens阈值=2000
                          DialogueCompressor       → tokens阈值=3000
                          CurrentRoundCompressor   → tokens阈值=3000
                          RoundLevelCompressor     → tokens阈值=8000

                        System Prompt 要求详细回答 → 增加每轮token产出 → 加速触发压缩

                        建议: 使用 :auto 命令快速观察压缩触发全过程""");
                break;
            default:
                System.out.println("未知命令: " + action + " (输入 :help 查看帮助)");
        }
    }

    private static void printPrompt(ConversationState state) {
        if (state.sessionId != null) {
            System.out.print("[ctx-demo #" + state.turnCount + "] >> ");
        } else {
            System.out.print("[new] >> ");
        }
    }

    // =========================================================================
    // :status — 查看完整上下文状态
    // =========================================================================

    private static void printFullStatus(ConversationState state) {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║ 上下文压缩状态详情                                  ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");

        System.out.println("║ 会话ID: " + (state.sessionId != null ? state.sessionId : "(无)"));
        System.out.println("║ 已执行轮次: " + state.turnCount);

        SessionModelContext modelCtx = getModelContext(state);
        if (modelCtx == null) {
            System.out.println("║ (无活跃上下文)");
            System.out.println("╚══════════════════════════════════════════════════════╝");
            return;
        }

        List<BaseMessage> messages = modelCtx.getMessages(null, true);
        int totalTokens = estimateTokens(messages);
        int memoryBlockCount = countMemoryBlocks(messages);
        int toolMsgCount = countToolMessages(messages);
        int userMsgCount = 0;
        int assistantMsgCount = 0;
        int systemMsgCount = 0;

        for (BaseMessage msg : messages) {
            if (msg instanceof ToolMessage) { /* counted separately */ }
            else if (msg instanceof AssistantMessage) assistantMsgCount++;
            else if (msg instanceof com.openjiuwen.core.foundation.llm.schema.UserMessage) userMsgCount++;
            else if (msg instanceof com.openjiuwen.core.foundation.llm.schema.SystemMessage) systemMsgCount++;
        }

        System.out.println("║ ─── 消息统计 ───");
        System.out.println("║ 消息总数: " + messages.size());
        System.out.println("║   SystemMessage: " + systemMsgCount);
        System.out.println("║   UserMessage:   " + userMsgCount);
        System.out.println("║   AssistantMessage: " + assistantMsgCount);
        System.out.println("║   ToolMessage:   " + toolMsgCount);
        System.out.println("║ ─── Token 统计 ───");
        System.out.println("║ 估算总 tokens: " + totalTokens);
        System.out.println("║ ─── 压缩统计 ───");
        System.out.println("║ Memory Block 数: " + memoryBlockCount);

        if (memoryBlockCount > 0) {
            List<String> blockSummaries = extractMemoryBlockSummaries(messages);
            System.out.println("║ Memory Block 详情:");
            for (int i = 0; i < Math.min(5, blockSummaries.size()); i++) {
                String s = blockSummaries.get(i);
                String truncated = s.length() > 80 ? s.substring(0, 80) + "..." : s;
                System.out.println("║   [#" + (i + 1) + "] " + truncated);
            }
            if (blockSummaries.size() > 5) {
                System.out.println("║   ... 共 " + blockSummaries.size() + " 个 Block");
            }
        }

        System.out.println("║ ─── 处理器链（演示低阈值配置） ───");
        System.out.println("║ 1. MessageSummaryOffloader  (tokens阈值=2000, 默认60000)");
        System.out.println("║ 2. DialogueCompressor       (tokens阈值=3000, 默认100000)");
        System.out.println("║ 3. CurrentRoundCompressor   (tokens阈值=3000, 默认100000)");
        System.out.println("║ 4. RoundLevelCompressor     (tokens阈值=8000, 默认230000)");
        System.out.println("║");
        System.out.println("║ 压缩触发机制:");
        System.out.println("║   addMessages() → 遍历 processors:");
        System.out.println("║     if triggerAddMessages() == true:");
        System.out.println("║       onAddMessages() → 执行压缩 → 生成 Memory Block");
        System.out.println("║");
        System.out.println("║ 注: 阈值已降低用于演示，生产环境应使用默认值");

        System.out.println("╚══════════════════════════════════════════════════════╝");
    }

    // =========================================================================
    // :auto — 自动执行多轮对话
    // =========================================================================

    private static void runAutoQuestions(ConversationState state) {
        System.out.println("[AUTO] 开始自动执行 " + AUTO_QUESTIONS.size() + " 轮对话...");
        System.out.println("[AUTO] 目标: 观察 token 增长 → 压缩触发 → Agent 不中断");
        System.out.println();

        for (int i = 0; i < AUTO_QUESTIONS.size(); i++) {
            String question = AUTO_QUESTIONS.get(i);
            System.out.println("[AUTO] 第 " + (i + 1) + " 轮: " + question);
            try {
                executeConversationTurn(question, state);
            } catch (Exception e) {
                System.err.println("[AUTO ERROR] 第 " + (i + 1) + " 轮失败: " + e.getMessage());
            }
            System.out.println();

            SessionModelContext modelCtx = getModelContext(state);
            if (modelCtx != null) {
                List<BaseMessage> messages = modelCtx.getMessages(null, true);
                int blocks = countMemoryBlocks(messages);
                int tokens = estimateTokens(messages);
                System.out.println("[AUTO] 当前状态: 消息=" + messages.size()
                        + " tokens≈" + tokens
                        + " MemoryBlocks=" + blocks);
                if (blocks > 0) {
                    System.out.println("[AUTO] ★★ 压缩已触发! Agent 不会因超窗口中断 ★★");
                }
            }
            System.out.println();
        }

        System.out.println("[AUTO] 自动执行完成! 共 " + AUTO_QUESTIONS.size() + " 轮");
        System.out.println("[AUTO] 输入 :status 查看最终上下文状态");
    }

    // =========================================================================
    // Agent 构建 — 配置处理器链
    // =========================================================================

    /**
     * 构建 DeepAgent，配置上下文压缩处理器链。
     *
     * <p>处理器链说明（与 ContextProcessorRail 的 isPreset=true 配置一致）：
     * <ol>
     *   <li><b>MessageSummaryOffloader</b> — tokens阈值 60000
     *       卸载大块工具结果（ToolMessage），生成摘要替代，释放大量 token</li>
     *   <li><b>DialogueCompressor</b> — tokens阈值 100000
     *       压缩历史完成的 ReAct 对话块为 [DIALOGUE_MEMORY_BLOCK]</li>
     *   <li><b>CurrentRoundCompressor</b> — tokens阈值 100000
     *       增量压缩当前轮次为 [CURRENT_ROUND_MEMORY_BLOCK]</li>
     *   <li><b>RoundLevelCompressor</b> — tokens阈值 230000
     *       兜底级 token 预算压缩，确保总 token 不超过模型窗口上限</li>
     * </ol>
     *
     * <p>触发机制（在 SessionModelContext.addMessages 中执行）：
     * <pre>
     *   for (ContextProcessor processor : processors) {
     *       if (processor.triggerAddMessages(context, messagesToAdd)) {
     *           processor.onAddMessages(context, messagesToAdd);  // 执行压缩
     *       }
     *   }
     * </pre>
     */
    private static DeepAgent buildAgent() {
        List<Object> tools = new ArrayList<>(AnnotatedToolFactory.scan(new MathTools()));

        AgentCard card = AgentCard.builder()
                .id("ctx_compression_demo")
                .name("ctx_compression_demo")
                .description("ContextCompressionDemo — 上下文压缩自动触发演示")
                .build();

        Map<String, String> llmConfig = SharedExampleApiConfigLoader.load();

        // 不在 rails 中配置 ContextProcessorRail — 我们手动配置低阈值处理器
        DeepAgentConfig config = DeepAgentConfig.builder()
                .enableTaskLoop(true)
                .enableTaskPlanning(true)
                .systemPrompt("""
                        你是一个详细的数学计算助手，可以进行加减乘除运算。
                        可用工具: add(加), subtract(减), multiply(乘), divide(除)。

                        回答规则（重要，必须遵守）:
                        1. 每次回答时，先回顾之前所有的计算步骤和结果，逐条列出
                        2. 详细说明当前步骤的计算逻辑和过程
                        3. 给出最终答案后，再次总结所有步骤
                        4. 回答要尽量详尽，包含推理过程、中间结果、最终结论

                        这种详细的回答方式是为了展示上下文压缩机制：
                        随着对话轮次增加，详细回答会占用更多 token，
                        当 token 数超过阈值时，压缩处理器会自动触发。""")
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

        // 从已构建的 agent 中获取模型配置
        ReActAgentConfig reactConfig = (ReActAgentConfig) agent.getAgent().getConfig();
        ModelRequestConfig modelConfig = reactConfig.getModelConfigObj();
        ModelClientConfig modelClientConfig = reactConfig.getModelClientConfig();

        // ==================================================================
        // 核心：手动配置低阈值的上下文处理器
        //
        // 原因：预设的 ContextProcessorRail 阈值太高（60000/100000/230000），
        //       简单数学计算每轮只产生几百 tokens，无法触发压缩。
        //       因此我们需要大幅降低阈值，使压缩在 5-10 轮内触发。
        //
        // 处理器链（按触发顺序）：
        //   1. MessageSummaryOffloader    → 卸载大块工具结果
        //   2. DialogueCompressor         → 压缩历史对话块
        //   3. CurrentRoundCompressor     → 增量压缩当前轮次
        //   4. RoundLevelCompressor       → 兜底级 token 预算压缩
        //
        // 触发机制：SessionModelContext.addMessages() 遍历 processors:
        //   if processor.triggerAddMessages() == true  → 执行 onAddMessages()
        // ==================================================================

        List<ContextEngine.ProcessorSpec> processorSpecs = new ArrayList<>();

        // 1. MessageSummaryOffloader — 卸载大块工具结果（Token阈值 2000）
        processorSpecs.add(new ContextEngine.ProcessorSpec("MessageSummaryOffloader",
                MessageSummaryOffloaderConfig.builder()
                        .tokensThreshold(2000)             // 演示用低阈值（默认60000）
                        .largeMessageThreshold(2000)       // 大消息阈值也降低
                        .offloadMessageType(List.of("tool"))
                        .protectedToolNames(List.of())
                        .messagesToKeep(4)                 // 保留最近4条消息
                        .keepLastRound(false)
                        .model(modelConfig)
                        .modelClient(modelClientConfig)
                        .build()));

        // 2. DialogueCompressor — 压缩历史对话块（Token阈值 3000）
        processorSpecs.add(new ContextEngine.ProcessorSpec("DialogueCompressor",
                DialogueCompressorConfig.builder()
                        .tokensThreshold(3000)             // 演示用低阈值（默认100000）
                        .messagesToKeep(4)                 // 保留最近4条消息
                        .compressionTargetTokens(500)      // 压缩目标（默认1800）
                        .model(modelConfig)
                        .modelClient(modelClientConfig)
                        .build()));

        // 3. CurrentRoundCompressor — 增量压缩当前轮次（Token阈值 3000）
        processorSpecs.add(new ContextEngine.ProcessorSpec("CurrentRoundCompressor",
                CurrentRoundCompressorConfig.builder()
                        .tokensThreshold(3000)             // 演示用低阈值（默认100000）
                        .messagesToKeep(3)                 // 保留最近3条消息
                        .minSelectedTokensForCompression(300) // 最小压缩量（默认20000）
                        .compressionTargetTokens(300)      // 压缩目标（默认4000）
                        .summaryMergeTargetTokens(300)
                        .accumulatedSummaryTokenLimit(1500)
                        .summaryMergeMinBlocks(2)          // 最少合并块数（默认3）
                        .priorContextWindowSize(3)         // 上下文窗口（默认10）
                        .model(modelConfig)
                        .modelClient(modelClientConfig)
                        .build()));

        // 4. RoundLevelCompressor — 兜底级token预算压缩（Token阈值 8000）
        processorSpecs.add(new ContextEngine.ProcessorSpec("RoundLevelCompressor",
                RoundLevelCompressorConfig.builder()
                        .triggerTotalTokens(8000)          // 演示用低阈值（默认230000）
                        .targetTotalTokens(4000)           // 压缩目标（默认160000）
                        .keepRecentMessages(4)              // 保留最近4条消息
                        .model(modelConfig)
                        .modelClient(modelClientConfig)
                        .build()));

        // 应用处理器配置
        reactConfig.configureContextProcessors(new ArrayList<>(processorSpecs));
        reactConfig.setContextEngineConfig(ContextEngineConfig.builder()
                .maxContextMessageNum(500)     // 允许较多消息，让压缩有机会触发
                .build());
        agent.getAgent().configure(reactConfig);

        System.out.println("[INFO] Agent 构建完成");
        System.out.println("[INFO] 已手动配置低阈值处理器（用于演示压缩触发）:");
        System.out.println("[INFO]   1. MessageSummaryOffloader    tokens阈值=2000 (默认60000)");
        System.out.println("[INFO]   2. DialogueCompressor         tokens阈值=3000 (默认100000)");
        System.out.println("[INFO]   3. CurrentRoundCompressor     tokens阈值=3000 (默认100000)");
        System.out.println("[INFO]   4. RoundLevelCompressor       tokens阈值=8000 (默认230000)");
        System.out.println("[INFO] System Prompt 已设置为要求详细回答（增加每轮token产出）");
        System.out.println("[INFO] 预期: 3-5轮对话后开始触发压缩");

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
    // 对话状态
    // =========================================================================

    private static final class ConversationState {
        String sessionId;
        AgentSessionApi session;
        int turnCount;

        int beforeMessages;
        int beforeTokens;
        int beforeMemoryBlocks;

        void reset() {
            sessionId = null;
            session = null;
            turnCount = 0;
            beforeMessages = 0;
            beforeTokens = 0;
            beforeMemoryBlocks = 0;
        }
    }
}

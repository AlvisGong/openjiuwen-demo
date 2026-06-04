/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 */

package com.example.deepagent;

import com.example.tool.ApiConfigLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.rails.TaskPlanningRail;
import com.openjiuwen.harness.rails.ToolTrackingRail;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 {@link DeepAgent} 的可运行示例。
 *
 * <p>默认进入 <strong>规划模式</strong>（{@code enableTaskPlanning=true}，挂载 {@link TaskPlanningRail}）：
 * 模型通过 {@code todo_create/list/modify} 管理多步任务，并通过流式 {@code llm_reasoning} 输出思考过程。
 *
 * <p>加参数 {@code --simple} 可回到最小 ReAct 示例（无 todo、非流式）。
 */
public final class DeepAgentMinimalDemo {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AGENT_ID = "deep_agent_minimal_demo";
    private static final String DEFAULT_CONVERSATION_ID = "deep_agent_planning_session";
    private static final String SIMPLE_QUERY = "用一两句话介绍 DeepAgent 是什么。";

    /**
     * 默认用户问题（与 jiuwenswarm 一致：真实 query，不在 query 里写 todo 指令）。
     * 多步实现类任务会由 {@link TaskPlanningRail} 注入的 todo 规则触发 todo_create。
     */
    private static final String DEFAULT_PLANNING_QUERY = "创建2个文件A.txt和B.txt，在A中随机生成一段10个英文字符的字符串，再读取A中的字符串转成全小写，再写入B.txt";

    /**
     * 规划模式下的身份提示（对齐 jiuwenswarm {@code build_agent_identity_prompt}：仅身份，不写 todo 规则）。
     * todo_create / todo_modify 等约束由 {@link TaskPlanningRail#beforeModelCall} 注入的 todo section 提供。
     */
    private static final String GENERAL_AGENT_IDENTITY = "你是一个有帮助的助手，对于复杂问题规划时，每个resoning信息步骤输出title+content的方式";

    private static final String SIMPLE_SYSTEM_PROMPT = """
            你是一个简洁的助手。
            用户提问时直接回答；若需要当前时间，可调用 get_current_time 工具后再回答。
            """;

    private DeepAgentMinimalDemo() {
    }

    public static void main(String[] args) throws Exception {
        boolean simpleMode = args.length > 0 && "--simple".equals(args[0]);
        String query = resolveQuery(args, simpleMode);
        Path workspaceRoot = Files.createTempDirectory("deep-agent-demo-workspace");
        Tool timeTool = null;

        System.out.println("=== DeepAgent Demo ===");
        System.out.println("mode: " + (simpleMode ? "simple (无 TaskPlanningRail)" : "planning (TaskPlanningRail + 流式思考)"));
        System.out.println("workspace: " + workspaceRoot.toAbsolutePath());
        System.out.println("query: " + query.trim());
        System.out.println("LLM config: " + ApiConfigLoader.describeConfig());
        System.out.println();

        try {
            DeepAgent agent = simpleMode
                    ? createSimpleDeepAgent(workspaceRoot)
                    : createPlanningDeepAgent(workspaceRoot);
            configureInnerReActAgent(agent, simpleMode);
            if (simpleMode) {
                timeTool = createTimeTool();
                agent.registerHarnessTool(timeTool);
            }
            agent.ensureInitialized();

            if (simpleMode) {
                runSimpleDemo(agent, query);
            } else {
                runPlanningDemo(agent, workspaceRoot, query);
            }
        } finally {
            if (timeTool != null) {
                Runner.resourceMgr().removeTool(
                        timeTool.getCard().getId(), AGENT_ID, TagMatchStrategy.ALL, true);
            }
            Runner.release(DEFAULT_CONVERSATION_ID);
            Runner.stop();
        }
    }

    private static String resolveQuery(String[] args, boolean simpleMode) {
        if (args.length == 0) {
            return simpleMode ? SIMPLE_QUERY : DEFAULT_PLANNING_QUERY;
        }
        if ("--simple".equals(args[0])) {
            return args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : SIMPLE_QUERY;
        }
        return String.join(" ", args);
    }

    private static void runSimpleDemo(DeepAgent agent, String query) throws Exception {
        System.out.println("--- invoke() 预览（不调用 LLM，仅元数据）---");
        Map<String, Object> invokePreview = agent.invoke(Map.of(
                "query", query,
                "conversation_id", DEFAULT_CONVERSATION_ID
        ));
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(invokePreview));
        System.out.println();

        System.out.println("--- run() 真实 ReAct 调用 ---");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) agent.run(Map.of(
                "query", query,
                "conversation_id", DEFAULT_CONVERSATION_ID
        ));
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    private static void runPlanningDemo(DeepAgent agent, Path workspaceRoot, String query) throws Exception {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("query", query);
        inputs.put("conversation_id", DEFAULT_CONVERSATION_ID);

        AgentSessionApi session = new AgentSessionApi(
                DEFAULT_CONVERSATION_ID,
                null,
                agent.getCard(),
                List.of(StreamMode.OUTPUT)
        );

        Iterator<Object> stream = Runner.runAgentStreaming(
                agent.getAgent(),
                inputs,
                session,
                null,
                List.of(StreamMode.OUTPUT)
        );
        new ConsoleStreamRenderer(query).consume(stream);
        System.out.println();
        printTodoArtifact(workspaceRoot, DEFAULT_CONVERSATION_ID);
    }

    private static void printTodoArtifact(Path workspaceRoot, String sessionId) throws Exception {
        Path todoFile = workspaceRoot.resolve(".todo").resolve(sessionId).resolve("todo.json");
        System.out.println("--- 待办落盘 ---");
        System.out.println("path: " + todoFile.toAbsolutePath());
        if (Files.exists(todoFile)) {
            System.out.println(Files.readString(todoFile));
        } else {
            System.out.println("(未生成 todo.json，可能模型未调用 todo_create 或会话 id 不一致)");
        }
    }

    private static void configureInnerReActAgent(DeepAgent deepAgent, boolean simpleMode) {
        ReActAgent inner = deepAgent.getAgent();
        String systemPrompt = simpleMode ? SIMPLE_SYSTEM_PROMPT : GENERAL_AGENT_IDENTITY;
        int maxIterations = simpleMode ? 5 : 18;
        ReActAgentConfig reactConfig = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of("role", "system", "content", systemPrompt)))
                .maxIterations(maxIterations)
                .build()
                .configureModelClient(
                        ApiConfigLoader.getModelProvider(),
                        ApiConfigLoader.getApiKey(),
                        ApiConfigLoader.getApiBase(),
                        ApiConfigLoader.getModelName(),
                        ApiConfigLoader.getSslVerify()
                );
        inner.configure(reactConfig);
        inner.setLlm(null);
    }

    private static DeepAgent createPlanningDeepAgent(Path workspaceRoot) {
        AgentCard card = AgentCard.builder()
                .id(AGENT_ID)
                .name(AGENT_ID)
                .description("DeepAgent 规划与待办示例")
                .build();

        TaskPlanningRail planningRail = new TaskPlanningRail(true, 2);
        ToolTrackingRail toolTrackingRail = new ToolTrackingRail();

        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(GENERAL_AGENT_IDENTITY)
                .maxIterations(18)
                .enableTaskLoop(false)
                .enableTaskPlanning(true)
                .language("cn")
                .rails(List.of(toolTrackingRail))
//                .rails(List.of(planningRail, toolTrackingRail))
                .build();

        Workspace workspace = Workspace.builder()
                .rootPath(workspaceRoot.toString())
                .language("cn")
                .build();

        return HarnessFactory.createDeepAgent(card, config, workspace);
    }

    private static DeepAgent createSimpleDeepAgent(Path workspaceRoot) {
        AgentCard card = AgentCard.builder()
                .id(AGENT_ID)
                .name(AGENT_ID)
                .description("DeepAgent 最小示例")
                .build();

        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(SIMPLE_SYSTEM_PROMPT)
                .maxIterations(5)
                .enableTaskLoop(false)
                .enableTaskPlanning(false)
                .language("cn")
                .build();

        Workspace workspace = Workspace.builder()
                .rootPath(workspaceRoot.toString())
                .language("cn")
                .build();

        return HarnessFactory.createDeepAgent(card, config, workspace);
    }

    private static Tool createTimeTool() {
        ToolCard card = ToolCard.builder()
                .id("get_current_time")
                .name("get_current_time")
                .description("返回当前 UTC 时间（ISO-8601）")
                .inputParams(Map.of(
                        "type", "object",
                        "properties", Map.of()
                ))
                .build();

        return new LocalFunction(card, inputs -> Map.of("utc_time", Instant.now().toString()));
    }
}

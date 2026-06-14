/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package myexample.rails.external_memory_rail;

import com.openjiuwen.core.memory.external.MemoryProvider;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.rails.ExternalMemoryRail;
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
 * ExternalMemoryRailExample — 基于 DeepAgent 使用 ExternalMemoryRail 的样例。
 *
 * <p>本示例演示如何为 DeepAgent 配置外部长期记忆（External Memory）：
 * <ul>
 *   <li>创建自定义 MemoryProvider（{@link LocalFileMemoryProvider}）实现记忆的存取</li>
 *   <li>通过 {@link ExternalMemoryRail} 将 MemoryProvider 注册到 Agent</li>
 *   <li>Agent 自动获得 ltm_search 和 ltm_save 两个工具</li>
 *   <li>每次模型调用前，Rail 自动 prefetch 相关记忆注入上下文</li>
 *   <li>每次对话结束后，Rail 自动 syncTurn 将对话内容同步到记忆</li>
 * </ul>
 *
 * <h3>ExternalMemoryRail 工作机制</h3>
 * <pre>
 *   用户提问 → beforeModelCall: prefetch(查询) → 注入 &lt;memory-context&gt; → LLM 接收到记忆上下文
 *   LLM 回复 → afterInvoke: syncTurn(用户消息, LLM回复) → 保存对话到记忆
 * </pre>
 *
 * <h3>运行方式</h3>
 * <pre>{@code
 *   # 多轮对话模式（推荐）
 *   java myexample.rails.external_memory_rail.ExternalMemoryRailExample
 *
 *   # 单次查询模式
 *   java myexample.rails.external_memory_rail.ExternalMemoryRailExample --query "我叫张三，请记住我的名字"
 * }</pre>
 */
public final class ExternalMemoryRailExample {

    private static final Path WORKSPACE_ROOT = Path.of(".").toAbsolutePath().normalize();

    private ExternalMemoryRailExample() {
    }

    public static void main(String[] args) {
        try {
            // 1. 初始化 Runner
            Runner.start();

            // 2. 创建 MemoryProvider
            LocalFileMemoryProvider provider = new LocalFileMemoryProvider();

            // 预置一些记忆事实，模拟已有记忆（事实内容包含常见同义词以提高召回率）
            provider.saveFact("用户名字叫张三");
            provider.saveFact("用户爱好是喝咖啡，喜欢咖啡");
            provider.saveFact("用户在做Java开发工作");

            // 3. 构建 DeepAgent（带 ExternalMemoryRail 配置）
            DeepAgent agent = buildAgent(provider);

            // 4. 解析参数并执行
            String query = resolveQuery(args);

            System.out.println("========== External Memory Rail Example ==========");
            System.out.println("[INFO] MemoryProvider: " + provider.getName());
            System.out.println("[INFO] Prefetched memories will be injected into LLM context");
            System.out.println("[INFO] Conversations will be synced to memory after each turn");
            System.out.println();
            System.out.println("Query: " + query);
            System.out.println();

            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("query", query);

            Object result = agent.run(inputs);

            System.out.println("========== Result ==========");
            System.out.println(result);
        } finally {
            Runner.stop();
        }
    }

    private static DeepAgent buildAgent(MemoryProvider provider) {
        // LLM 配置
        Map<String, String> llmConfig = SharedExampleApiConfigLoader.load();

        String systemPrompt = """
                你是一个智能助手，拥有长期记忆能力。
                你可以通过 ltm_search 搜索记忆中的相关信息，通过 ltm_save 保存重要事实。
                在回答用户问题前，先检查记忆中是否有相关信息可以利用。
                如果用户告诉你一些个人信息或偏好，主动保存到记忆中。
                """;

        AgentCard card = AgentCard.builder()
                .id("external_memory_agent")
                .name("external_memory_agent")
                .description("外部记忆 Agent — 使用 ExternalMemoryRail 实现长期记忆")
                .build();

        // 构建 rails 列表
        List<Object> rails = new ArrayList<>();
        rails.add(new SysOperationRail());

        // 关键配置：创建 ExternalMemoryRail 并添加到 rails
        // provider: 记忆提供者；userId/scopeId/sessionId: 记忆的隔离维度
        ExternalMemoryRail memoryRail = new ExternalMemoryRail(
                provider,
                "user-1",       // userId — 用户维度隔离
                "scope-1",      // scopeId — 项目/空间维度隔离
                "session-1"     // sessionId — 会话维度隔离
        );
        rails.add(memoryRail);

        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(systemPrompt)
                .maxIterations(20)
                .language("cn")
                .enableTaskLoop(true)
                .enableTaskPlanning(true)
                .rails(rails)
                .model(buildModelConfig(llmConfig))
                .backend(buildBackendConfig(llmConfig))
                .restrictToWorkDir(false)
                .build();

        Workspace workspace = Workspace.builder()
                .rootPath(WORKSPACE_ROOT.toString())
                .language("cn")
                .build();

        System.out.println("[INFO] ExternalMemoryRail configured with provider: " + provider.getName());
        return HarnessFactory.createDeepAgent(card, config, workspace);
    }

    private static String resolveQuery(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--query".equals(args[i]) && i + 1 < args.length) {
                return args[++i];
            }
        }

        // 默认查询：测试记忆召回能力
        return "你还记得我的名字和爱好吗？";
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

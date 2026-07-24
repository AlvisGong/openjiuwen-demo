/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package otel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.tool.function.AnnotatedToolFactory;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.session.tracer.TracerHandlerRegistry;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.extensions.tracerotel.OtelAgentHandler;
import com.openjiuwen.extensions.tracerotel.OtelRail;
import com.openjiuwen.extensions.tracerotel.OtelTracerConfig;
import com.openjiuwen.extensions.tracerotel.OtelTracerSetup;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;

import io.opentelemetry.api.trace.Tracer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 DeepAgent 的加减乘除样例，接入 OpenTelemetry Tracer 并通过 OTLP/HTTP 上报 Jaeger。
 *
 * <p>样例流程：
 * <ol>
 *   <li>初始化 OTel Tracer（控制台导出 + 可选 OTLP/HTTP 导出到 Jaeger localhost:4318）</li>
 *   <li>注册 {@link OtelAgentHandler} 到 {@link TracerHandlerRegistry}，承接 Agent 维度事件</li>
 *   <li>构建 DeepAgent：注入 {@link MathTools}（加减乘除四个 @ToolDefinition 工具）、
 *       挂载 {@link OtelRail}（接管 Agent 根 Span 与 LLM 子 Span 生命周期）、配置 LLM</li>
 *   <li>以 {@code TRACE + OUTPUT} 流式模式运行 Agent：session 自带 Tracer，
 *       OtelRail 据此产生 span 并分发到 OtelAgentHandler → OTel → Jaeger</li>
 *   <li>等待 BatchSpanProcessor 刷新后清理资源</li>
 * </ol>
 *
 * <p>运行方式（在仓库根目录执行）：
 * <pre>{@code
 * mvn test-compile -q -DskipTests
 * mvn dependency:build-classpath -q -Dmdep.outputFile=target/cp.txt
 * $cp = Get-Content target/cp.txt -Raw
 * java -cp "target/classes;target/test-classes;examples;$cp" otel.OtelDeepAgentMathDemo
 * }</pre>
 * 需要 {@code examples/apiconfig.json} 提供 LLM 凭据；需要本地 Jaeger（4318 OTLP / 16686 UI）
 * 才能在 UI 查看链路，否则仅控制台输出 span。
 */
public final class OtelDeepAgentMathDemo {
    private static final Logger LOG = LoggerFactory.getLogger(OtelDeepAgentMathDemo.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String AGENT_ID = "otel_deep_agent_math";
    private static final String SERVICE_NAME = "otel-deep-agent-math";
    private static final String DEFAULT_QUERY = "请计算 (12 + 8) * 3 - 4，然后除以 5，给出最终结果。";

    private static final String SYSTEM_PROMPT = "你是一个数学计算助手，拥有加减乘除四个工具：add、subtract、multiply、divide。"
            + "遇到算术运算时，必须调用对应工具完成计算，不要自己心算。"
            + "对于复合表达式，按运算顺序分步调用工具，并基于上一步的工具结果进行下一步运算。"
            + "最终用一句话给出结果。";

    private OtelDeepAgentMathDemo() {
    }

    /**
     * Entry point.
     *
     * @param args command-line arguments (unused)
     * @throws InterruptedException if the flush sleep is interrupted
     * @throws IOException if the API config file cannot be read
     */
    public static void main(String[] args) throws InterruptedException, IOException {
        LOG.info("=== OTel + DeepAgent 数学工具样例启动 ===");

        // 1. 配置并初始化 OTel Tracer（控制台导出，便于本地调试）
        OtelTracerConfig consoleConfig = OtelTracerConfig.builder()
                .exporterType("console")
                .serviceName(SERVICE_NAME)
                .isRedactionEnabled(false)
                .scheduleDelayMillis(500)
                .build();
        Tracer consoleTracer = OtelTracerSetup.initOtelTracer(consoleConfig);
        LOG.info("[setup] OTel Tracer 已初始化（console 导出）");

        // 同时探测本地 Jaeger 的 OTLP 接收端口（4318），可用则追加 OTLP/HTTP 导出
        boolean jaegerAvailable = isOtlpCollectorAvailable();
        OtelTracerConfig otlpConfig = null;
        Tracer otlpTracer = null;
        if (jaegerAvailable) {
            otlpConfig = OtelTracerConfig.builder()
                    .exporterType("otlp")
                    .exporterEndpoint("http://localhost:4318")
                    .protocol("http")
                    .serviceName(SERVICE_NAME)
                    .isRedactionEnabled(false)
                    .scheduleDelayMillis(500)
                    .build();
            otlpTracer = OtelTracerSetup.initOtelTracer(otlpConfig);
            LOG.info("[setup] OTLP Tracer 已初始化（OTLP/HTTP -> http://localhost:4318）");
        }

        // 2. 注册 Agent 维度 OTel handler（自定义名称，避免与内置名称冲突）
        TracerHandlerRegistry.registerHandler("otel_agent_console", new OtelAgentHandler(consoleTracer, consoleConfig));
        if (jaegerAvailable) {
            TracerHandlerRegistry.registerHandler("otel_agent_otlp", new OtelAgentHandler(otlpTracer, otlpConfig));
        }
        LOG.info("[setup] OTel agent handler 已注册 (console{} )", jaegerAvailable ? "+otlp" : "");

        DeepAgent deepAgent = null;
        try {
            // 3. 构建 DeepAgent
            deepAgent = buildDeepAgent();
            deepAgent.ensureInitialized(); // 注册 tools 与 OtelRail 到内部 ReActAgent
            Object agent = deepAgent.getAgent();

            String query = args.length == 0 ? DEFAULT_QUERY : String.join(" ", args);
            String sessionId = AGENT_ID + "_session_" + System.currentTimeMillis();
            Map<String, Object> inputs = Map.of("query", query, "conversation_id", sessionId);

            // 4. 以 TRACE + OUTPUT 流式模式运行：session 自带 Tracer，
            //    OtelRail 在 beforeInvoke/afterInvoke/beforeModelCall/afterModelCall 产生 span
            AgentSessionApi session = new AgentSessionApi(sessionId, null, deepAgent.getCard(),
                    List.of(StreamMode.TRACE, StreamMode.OUTPUT));
            Iterator<Object> chunkIterator = Runner.runAgentStreaming(agent, inputs, session, null,
                    List.of(StreamMode.TRACE, StreamMode.OUTPUT));

            List<Object> outputChunks = new ArrayList<>();
            int traceChunkCount = 0;
            while (chunkIterator.hasNext()) {
                Object chunk = chunkIterator.next();
                if (chunk instanceof OutputSchema outputSchema) {
                    outputChunks.add(outputSchema.getPayload());
                } else {
                    traceChunkCount++;
                }
            }

            LOG.info("[result] DeepAgent 执行完成");
            LOG.info("  session_id    = {}", sessionId);
            LOG.info("  query         = {}", query);
            LOG.info("  output chunks = {}", outputChunks.size());
            LOG.info("  trace chunks  = {}", traceChunkCount);
            LOG.info("  outputs       = {}", outputChunks);

            // 5. 等待 BatchSpanProcessor 刷新
            LOG.info("[flush] 等待 span 刷新...");
            Thread.sleep(2000L);
        } finally {
            // 清理已注册的 tool 与 handler，避免影响后续测试
            if (deepAgent != null) {
                for (LocalFunction tool : AnnotatedToolFactory.scan(new MathTools())) {
                    Runner.resourceMgr().removeTool(tool.getCard().getId(), AGENT_ID, TagMatchStrategy.ALL, true);
                }
            }
            TracerHandlerRegistry.unregisterHandler("otel_agent_console");
            if (jaegerAvailable) {
                TracerHandlerRegistry.unregisterHandler("otel_agent_otlp");
            }
        }

        LOG.info("=== 样例结束 ===");
        if (jaegerAvailable) {
            LOG.info("在 Jaeger UI 查看链路: http://localhost:16686 (service: {})", SERVICE_NAME);
        } else {
            LOG.info("Jaeger 未运行（localhost:4318 不可达），span 仅在控制台输出。"
                    + "可用 docker 启动：docker run -d -p 4318:4318 -p 16686:16686 jaegertracing/all-in-one:1.57");
        }

        // BatchSpanProcessor 后台线程非 daemon，显式退出以保证样例正常终止
        System.exit(0);
    }

    /**
     * 构建 DeepAgent：注入 MathTools（加减乘除）、挂载 OtelRail、配置 LLM。
     *
     * @return 已配置的 DeepAgent 实例
     * @throws IOException if the API config file cannot be read
     */
    private static DeepAgent buildDeepAgent() throws IOException {
        Map<String, String> apiConfig = loadApiConfig();
        String modelProvider = apiConfig.get("MODEL_PROVIDER");
        String apiKey = apiConfig.get("API_KEY");
        String apiBase = apiConfig.get("API_BASE");
        String modelName = apiConfig.get("MODEL_NAME");
        String sslVerify = apiConfig.getOrDefault("LLM_SSL_VERIFY", "true");

        AgentCard card = AgentCard.builder()
                .id(AGENT_ID)
                .name(AGENT_ID)
                .description("基于 DeepAgent 的加减乘除数学助手")
                .build();

        // tools: AnnotatedToolFactory.scan 返回 List<LocalFunction>，转成 List<Object> 供配置消费
        List<Object> tools = new ArrayList<>(AnnotatedToolFactory.scan(new MathTools()));

        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(SYSTEM_PROMPT)
                .maxIterations(8)
                // model 与 backend 以 Map 形式传入，DeepAgent 内部 applyModelConfig/applyBackendConfig 解析
                .model(Map.of("model", modelName, "temperature", 0.2, "max_tokens", 512))
                .backend(buildBackendMap(modelProvider, apiKey, apiBase, sslVerify))
                .tools(tools)
                .rails(List.<Object>of(new OtelRail()))
                .build();

        return new DeepAgent(card, config, null);
    }

    /**
     * 构建后端配置 Map（DeepAgentConfig.backend 的 Map 形式）。
     *
     * @param provider 模型提供方
     * @param apiKey API Key
     * @param apiBase API Base
     * @param sslVerify 是否校验 SSL
     * @return 后端配置 Map
     */
    private static Map<String, Object> buildBackendMap(String provider, String apiKey, String apiBase,
            String sslVerify) {
        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("client_provider", provider);
        backend.put("api_key", apiKey);
        backend.put("api_base", apiBase);
        backend.put("verify_ssl", Boolean.parseBoolean(sslVerify));
        backend.put("timeout", 60.0);
        backend.put("max_retries", 3);
        return backend;
    }

    /**
     * 加载 {@code examples/apiconfig.json} 中的 LLM 凭据。
     *
     * @return 配置键值对
     * @throws IOException if the config file cannot be read
     */
    private static Map<String, String> loadApiConfig() throws IOException {
        Path resolved = resolveApiConfigPath();
        try (InputStream in = Files.newInputStream(resolved)) {
            return MAPPER.readValue(in, new TypeReference<Map<String, String>>() {
            });
        }
    }

    /**
     * 按优先级解析 apiconfig.json 路径（系统属性 → 环境变量 → 常见相对路径）。
     *
     * @return 已解析的配置文件路径
     * @throws IOException if no candidate file is found
     */
    private static Path resolveApiConfigPath() throws IOException {
        List<Path> candidates = new ArrayList<>();
        String prop = System.getProperty("openjiuwen.example.config");
        if (prop != null && !prop.isBlank()) {
            candidates.add(Path.of(prop));
        }
        String env = System.getenv("OPENJIUWEN_API_CONFIG");
        if (env != null && !env.isBlank()) {
            candidates.add(Path.of(env));
        }
        candidates.add(Path.of("examples", "apiconfig.json"));
        candidates.add(Path.of("apiconfig.json"));
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) {
                return normalized;
            }
        }
        throw new IOException("未找到 apiconfig.json，尝试过的路径: " + candidates);
    }

    /**
     * 探测本地 OTLP/HTTP collector（4318）是否可达。
     * <p>探测 4318 而非 Jaeger UI 16686：UI 可能先于 OTLP 接收端口就绪，
     * 此时上报会刷出 "Failed to export spans" 噪声日志。
     *
     * @return 4318 端口有任意 HTTP 响应（含 404/405）返回 true，连接失败返回 false
     */
    private static boolean isOtlpCollectorAvailable() {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new URL("http://localhost:4318/").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return code > 0;
        } catch (IOException e) {
            LOG.debug("OTLP collector (localhost:4318) 不可达: {}", e.getMessage());
            return false;
        }
    }
}

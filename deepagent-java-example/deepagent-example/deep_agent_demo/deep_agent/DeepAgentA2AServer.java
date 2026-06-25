/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package examples.deep_agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.core.singleagent.skills.SkillManager;
import com.openjiuwen.harness.rails.SkillUseRail;
import com.openjiuwen.harness.tools.SessionToolkit;
import com.openjiuwen.harness.workspace.Workspace;
import examples.utils.SharedExampleApiConfigLoader;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * DeepAgent A2A 协议服务器 — 将 DeepAgent 以 A2A (JSON-RPC 2.0) RESTful 接口暴露。
 * <p>
 * <b>支持的技能（3 个）：</b>
 * <ul>
 * <li>{@code transfer_service} — 转账服务（依赖 WorkflowNewServer :8080）</li>
 * <li>{@code financial_workflow_a2a} — 理财/余额查询（依赖 WorkflowA2AServer :8081）</li>
 * <li>{@code image_resizer} — 图片缩放（依赖本地 Python OpenCV）</li>
 * </ul>
 * <p>
 * <b>A2A 端点：</b>
 * <ul>
 * <li>{@code POST /tasks/send} — 非流式任务执行</li>
 * <li>{@code POST /tasks/sendSubscribe} — 流式任务执行（SSE）</li>
 * <li>{@code POST /tasks/get} — 查询已提交任务的状态</li>
 * <li>{@code GET /.well-known/agent-card} — 获取 Agent 能力声明</li>
 * </ul>
 * <p>
 * 使用方式：
 * 
 * <pre>{@code
 *   # 启动服务
 *   bash examples/deep_agent/a2a_server.sh
 *
 *   # 调用转账
 *   bash examples/deep_agent/a2a_cli.sh --method tasks/send --query "我要转账"
 * }</pre>
 */
public final class DeepAgentA2AServer {

    private static final int DEFAULT_PORT = 8082;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 任务状态管理 (使用 harness 标准组件) */
    private static final SessionToolkit TASK_TOOLKIT = new SessionToolkit();

    /** 任务会话元数据: taskId → conversationId */
    private static final Map<String, String> CONVERSATION_IDS = new ConcurrentHashMap<>();

    /** 任务完整结果: taskId → lastResult (Map) */
    private static final Map<String, Map<String, Object>> TASK_RESULTS = new ConcurrentHashMap<>();

    static {
        // Replace stdout/stderr with raw UTF-8 streams BEFORE any logging
        // framework initializes. Uses FileDescriptor.out to bypass the JVM's
        // default PrintStream (which encodes via the system code page, e.g. GBK).
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    private DeepAgentA2AServer() {
    }

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0] + ", using default " + DEFAULT_PORT);
            }
        }

        // ----- 创建 DeepAgent -----
        DeepAgent deepAgent = createDeepAgent();

        // ----- HTTP 服务器 -----
        ExecutorService executor = Executors.newFixedThreadPool(4);
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port),
                0);
        server.setExecutor(executor);

        // A2A: tasks/send (非流式)
        server.createContext("/tasks/send", exchange -> {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendJsonRpcError(exchange, null, -32000, "Method not allowed");
                    return;
                }
                handleTasksSend(deepAgent, exchange);
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    sendJsonRpcError(exchange, null, -32603, "Internal error: " + e.getMessage());
                } catch (IOException ignored) {
                }
            }
        });

        // A2A: tasks/sendSubscribe (流式 SSE)
        server.createContext("/tasks/sendSubscribe", exchange -> {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendJsonRpcError(exchange, null, -32000, "Method not allowed");
                    return;
                }
                handleTasksSendSubscribe(deepAgent, exchange);
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    sendJsonRpcError(exchange, null, -32603, "Internal error: " + e.getMessage());
                } catch (IOException ignored) {
                }
            }
        });

        // A2A: tasks/get
        server.createContext("/tasks/get", exchange -> {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendJsonRpcError(exchange, null, -32000, "Method not allowed");
                    return;
                }
                handleTasksGet(exchange);
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    sendJsonRpcError(exchange, null, -32603, "Internal error: " + e.getMessage());
                } catch (IOException ignored) {
                }
            }
        });

        // Agent card (A2A 能力发现)
        server.createContext("/.well-known/agent-card", exchange -> {
            try {
                handleAgentCard(exchange);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        server.start();
        System.out.println("============================================");
        System.out.println("  DeepAgent A2A Server started on port " + port);
        System.out.println("  A2A JSON-RPC endpoints:");
        System.out.println("    POST /tasks/send");
        System.out.println("    POST /tasks/sendSubscribe");
        System.out.println("    POST /tasks/get");
        System.out.println("    GET  /.well-known/agent-card");
        System.out.println("============================================");
        // 动态技能列表
        List<SkillInfo> skills = loadSkillInfos();
        System.out.println("  技能列表 (" + skills.size() + " 个):");
        for (int i = 0; i < skills.size(); i++) {
            SkillInfo si = skills.get(i);
            System.out.println("    " + (i + 1) + ". " + si.id() + " (" + si.name() + ") — " + si.description());
        }
        System.out.println("============================================");
        System.out.println("Example:");
        System.out.println("  curl -X POST http://localhost:" + port + "/tasks/send \\");
        System.out.println("    -H \"Content-Type: application/json\" \\");
        System.out.println("    -d '{\"jsonrpc\":\"2.0\",\"method\":\"tasks/send\","
                + "\"params\":{\"id\":\"t1\",\"message\":{\"role\":\"user\",\"parts\":"
                + "[{\"type\":\"text\",\"text\":\"我要转账\"}]}},\"id\":1}'");
        System.out.println();
    }

    // ==================================================================
    // JSON-RPC 2.0 helpers
    // ==================================================================

    private static Map<String, Object> jsonRpcResult(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("result", result);
        response.put("id", id);
        return response;
    }

    private static Map<String, Object> jsonRpcError(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        response.put("error", error);
        response.put("id", id);
        return response;
    }

    // ==================================================================
    // Request handlers
    // ==================================================================

    /**
     * tasks/send — 非流式执行。
     * <p>
     * 解析 A2A JSON-RPC 请求，调用 DeepAgent.run()，将结果映射回 A2A 格式。
     */
    @SuppressWarnings("unchecked")
    private static void handleTasksSend(DeepAgent deepAgent,
            com.sun.net.httpserver.HttpExchange exchange) throws Exception {
        // 1. 解析请求体
        byte[] bodyBytes = readAllBytes(exchange.getRequestBody());
        System.out.println("[tasks/send] 请求体: " + new String(bodyBytes, StandardCharsets.UTF_8));
        Map<String, Object> rpcRequest = MAPPER.readValue(bodyBytes, Map.class);

        Object jsonRpcVersion = rpcRequest.get("jsonrpc");
        Object rpcId = rpcRequest.get("id");
        Object method = rpcRequest.get("method");

        if (!"2.0".equals(jsonRpcVersion)) {
            sendJsonRpcError(exchange, rpcId, -32600, "Invalid JSON-RPC version");
            return;
        }
        if (!"tasks/send".equals(method)) {
            sendJsonRpcError(exchange, rpcId, -32601, "Method not found: " + method);
            return;
        }

        Map<String, Object> params = (Map<String, Object>) rpcRequest.get("params");
        if (params == null) {
            sendJsonRpcError(exchange, rpcId, -32602, "Missing params");
            return;
        }

        // 2. 提取任务 ID
        String taskId = params.containsKey("id") ? String.valueOf(params.get("id"))
                : "task-" + System.currentTimeMillis();

        // 3. 提取 message.text
        Map<String, Object> message = (Map<String, Object>) params.get("message");
        String query = extractTextFromMessage(message);

        if (query == null || query.isBlank()) {
            sendJsonRpcError(exchange, rpcId, -32602, "Missing message.parts[0].text");
            return;
        }

        // 4. 提取 metadata (conversation_id, node_id)
        Map<String, Object> metadata = (Map<String, Object>) params.get("metadata");
        String conversationId = null;
        if (metadata != null) {
            Object convObj = metadata.get("conversation_id");
            if (convObj != null && !"null".equals(convObj) && !((String) convObj).isBlank()) {
                conversationId = String.valueOf(convObj);
            }
        }

        // 5. 如果已有会话记录，恢复 conversation_id
        String existingConvId = CONVERSATION_IDS.get(taskId);
        if (existingConvId != null && conversationId == null) {
            conversationId = existingConvId;
        }
        if (conversationId == null) {
            conversationId = "deep_a2a_" + taskId;
        }

        // 6. 更新会话 (使用 SessionToolkit)
        CONVERSATION_IDS.put(taskId, conversationId);
        TASK_TOOLKIT.upsertRunning(taskId, conversationId, query);

        // 7. 调用 DeepAgent — 原样传递用户查询
        // 框架会自动发现技能 (list_skill) 并读取 SKILL.md (skill_tool)，
        // 每个 SKILL.md 中已有正确的 API 地址和调用方式
        Map<String, Object> agentInputs = new LinkedHashMap<>();
        agentInputs.put("query", query);
        agentInputs.put("conversation_id", conversationId);

        @SuppressWarnings("unchecked")
        Map<String, Object> agentResult = (Map<String, Object>) deepAgent.run(agentInputs);

        // 8. 提取输出和状态
        String output = String.valueOf(agentResult.getOrDefault("output", ""));
        String resultType = String.valueOf(agentResult.getOrDefault("result_type", "answer"));

        // 判断是否需要用户输入
        boolean inputRequired = "interrupt".equals(resultType);

        // 保存会话状态 (使用 SessionToolkit)
        TASK_RESULTS.put(taskId, agentResult);
        CONVERSATION_IDS.put(taskId, conversationId);

        String status = inputRequired ? "input-required" : "completed";
        if (inputRequired) {
            TASK_TOOLKIT.markCompleted(taskId, "interrupt:" + output);
        } else {
            TASK_TOOLKIT.markCompleted(taskId, output);
        }

        // 9. 构建 A2A 响应
        Map<String, Object> taskResult = new LinkedHashMap<>();
        taskResult.put("id", taskId);
        taskResult.put("status", status);

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> agentMessage = new LinkedHashMap<>();
        agentMessage.put("role", "agent");
        agentMessage.put("parts", List.of(Map.of("type", "text", "text", output)));
        messages.add(agentMessage);
        taskResult.put("messages", messages);

        Map<String, Object> respMetadata = new LinkedHashMap<>();
        respMetadata.put("conversation_id", conversationId);
        if (inputRequired) {
            respMetadata.put("node_id", "deep_agent");
        }
        taskResult.put("metadata", respMetadata);

        // 10. 发送响应
        Map<String, Object> response = jsonRpcResult(rpcId, taskResult);
        byte[] responseBytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(response);
        System.out.println("[tasks/send] 响应体: " + new String(responseBytes, StandardCharsets.UTF_8));
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
        exchange.getResponseBody().close();
    }

    /**
     * tasks/sendSubscribe — 流式执行 (SSE)。
     * 当前简化为非流式后通过 SSE 发送一次完成事件。
     */
    @SuppressWarnings("unchecked")
    private static void handleTasksSendSubscribe(DeepAgent deepAgent,
            com.sun.net.httpserver.HttpExchange exchange) throws Exception {
        // 与 tasks/send 同样的逻辑，但通过 SSE 发送
        byte[] bodyBytes = readAllBytes(exchange.getRequestBody());
        System.out.println("[tasks/sendSubscribe] 请求体: " + new String(bodyBytes, StandardCharsets.UTF_8));
        Map<String, Object> rpcRequest = MAPPER.readValue(bodyBytes, Map.class);

        Object rpcId = rpcRequest.get("id");
        Map<String, Object> params = (Map<String, Object>) rpcRequest.get("params");
        if (params == null) {
            sendJsonRpcError(exchange, rpcId, -32602, "Missing params");
            return;
        }

        String taskId = params.containsKey("id") ? String.valueOf(params.get("id"))
                : "task-" + System.currentTimeMillis();
        Map<String, Object> message = (Map<String, Object>) params.get("message");
        String query = extractTextFromMessage(message);
        if (query == null || query.isBlank()) {
            sendJsonRpcError(exchange, rpcId, -32602, "Missing message.parts[0].text");
            return;
        }

        // 提取 conversation_id
        Map<String, Object> metadata = (Map<String, Object>) params.get("metadata");
        String conversationId = null;
        if (metadata != null) {
            Object convObj = metadata.get("conversation_id");
            if (convObj != null && !"null".equals(convObj) && !((String) convObj).isBlank()) {
                conversationId = String.valueOf(convObj);
            }
        }
        String existingConvId = CONVERSATION_IDS.get(taskId);
        if (existingConvId != null && conversationId == null) {
            conversationId = existingConvId;
        }
        if (conversationId == null) {
            conversationId = "deep_a2a_" + taskId;
        }
        CONVERSATION_IDS.put(taskId, conversationId);
        TASK_TOOLKIT.upsertRunning(taskId, conversationId, query);

        // SSE headers
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        // 捕获 lambda 所需的 effectively-final 变量
        final String finalConvId = conversationId;
        final String finalTaskId = taskId;
        final DeepAgent finalDeepAgent = deepAgent;
        final com.sun.net.httpserver.HttpExchange finalExchange = exchange;
        final String finalQuery = query;

        // 异步执行
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> agentInputs = new LinkedHashMap<>();
                agentInputs.put("query", finalQuery);
                agentInputs.put("conversation_id", finalConvId);

                @SuppressWarnings("unchecked")
                Map<String, Object> agentResult = (Map<String, Object>) finalDeepAgent.run(agentInputs);

                String output = String.valueOf(agentResult.getOrDefault("output", ""));
                String resultType = String.valueOf(agentResult.getOrDefault("result_type", "answer"));
                boolean inputRequired = "interrupt".equals(resultType);
                String status = inputRequired ? "input-required" : "completed";

                // 保存会话状态
                TASK_RESULTS.put(finalTaskId, agentResult);
                CONVERSATION_IDS.put(finalTaskId, finalConvId);
                if (inputRequired) {
                    TASK_TOOLKIT.markCompleted(finalTaskId, "interrupt:" + output);
                } else {
                    TASK_TOOLKIT.markCompleted(finalTaskId, output);
                }

                // 构建 SSE 事件
                Map<String, Object> sseData = new LinkedHashMap<>();
                sseData.put("jsonrpc", "2.0");
                sseData.put("method", "tasks/sendSubscribe");
                Map<String, Object> sseParams = new LinkedHashMap<>();
                sseParams.put("id", finalTaskId);
                sseParams.put("status", status);

                List<Map<String, Object>> messages = new ArrayList<>();
                Map<String, Object> agentMsg = new LinkedHashMap<>();
                agentMsg.put("role", "agent");
                agentMsg.put("parts", List.of(Map.of("type", "text", "text", output)));
                messages.add(agentMsg);
                sseParams.put("messages", messages);

                if (inputRequired) {
                    Map<String, Object> sseMeta = new LinkedHashMap<>();
                    sseMeta.put("conversation_id", finalConvId);
                    sseMeta.put("node_id", "deep_agent");
                    sseParams.put("metadata", sseMeta);
                }

                sseData.put("params", sseParams);

                String eventData = MAPPER.writeValueAsString(sseData);
                String sseEvent = "event: " + status + "\ndata: " + eventData + "\n\n";
                System.out.println("[tasks/sendSubscribe] SSE 事件: " + sseEvent.trim());
                var os = finalExchange.getResponseBody();
                os.write(sseEvent.getBytes(StandardCharsets.UTF_8));
                os.flush();

                // 如果是 completed，发送一个 done 事件
                if (!inputRequired) {
                    String doneEvent = "event: completed\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"tasks/sendSubscribe\""
                            + ",\"params\":{\"id\":\"" + finalTaskId + "\",\"status\":\"completed\"}}\n\n";
                    os.write(doneEvent.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (Exception e) {
                try {
                    String errorEvent = "event: error\ndata: {\"error\":\"" + e.getMessage() + "\"}\n\n";
                    var os = finalExchange.getResponseBody();
                    os.write(errorEvent.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException ignored) {
                }
            } finally {
                try {
                    finalExchange.getResponseBody().close();
                } catch (IOException ignored) {
                }
            }
        });
    }

    /**
     * tasks/get — 查询任务状态。
     */
    @SuppressWarnings("unchecked")
    private static void handleTasksGet(com.sun.net.httpserver.HttpExchange exchange) throws Exception {
        byte[] bodyBytes = readAllBytes(exchange.getRequestBody());
        System.out.println("[tasks/get] 请求体: " + new String(bodyBytes, StandardCharsets.UTF_8));
        Map<String, Object> rpcRequest = MAPPER.readValue(bodyBytes, Map.class);

        Object rpcId = rpcRequest.get("id");
        Map<String, Object> params = (Map<String, Object>) rpcRequest.get("params");
        if (params == null) {
            sendJsonRpcError(exchange, rpcId, -32602, "Missing params");
            return;
        }

        String taskId = String.valueOf(params.get("id"));
        var taskRow = TASK_TOOLKIT.get(taskId);
        String convId = CONVERSATION_IDS.get(taskId);
        Map<String, Object> lastResult = TASK_RESULTS.get(taskId);

        Map<String, Object> taskResult = new LinkedHashMap<>();
        taskResult.put("id", taskId);
        if (taskRow != null) {
            String status = taskRow.getStatus();
            String output = taskRow.getResult();

            // 处理 interrupt 状态的特殊标记
            if (output != null && output.startsWith("interrupt:")) {
                status = "input-required";
                output = output.substring("interrupt:".length());
            }

            taskResult.put("status", status);
            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> agentMessage = new LinkedHashMap<>();
            agentMessage.put("role", "agent");
            agentMessage.put("parts", List.of(Map.of("type", "text", "text", output != null ? output : "")));
            messages.add(agentMessage);
            taskResult.put("messages", messages);
            Map<String, Object> respMeta = new LinkedHashMap<>();
            respMeta.put("conversation_id", convId != null ? convId : taskRow.getSubSessionId());
            taskResult.put("metadata", respMeta);
        } else {
            taskResult.put("status", "unknown");
            taskResult.put("messages", List.of());
        }

        Map<String, Object> response = jsonRpcResult(rpcId, taskResult);
        byte[] responseBytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(response);
        System.out.println("[tasks/get] 响应体: " + new String(responseBytes, StandardCharsets.UTF_8));
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
        exchange.getResponseBody().close();
    }

    /**
     * /.well-known/agent-card — Agent 能力声明。
     */
    private static void handleAgentCard(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", "DeepAgent A2A Server");
        card.put("description", "多技能 AI 智能体");

        List<Map<String, Object>> skills = new ArrayList<>();
        List<SkillInfo> skillInfos = loadSkillInfos();
        for (SkillInfo si : skillInfos) {
            Map<String, Object> skill = new LinkedHashMap<>();
            skill.put("id", si.id());
            skill.put("name", si.name());
            skill.put("description", si.description());
            skills.add(skill);
        }
        card.put("skills", skills);

        List<Map<String, Object>> capabilities = new ArrayList<>();
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("id", "deep_agent_react");
        cap.put("name", "DeepAgent ReAct 自循环");
        cap.put("description", "LLM 自动发现技能 → 读取 SKILL.md → 调用工作流 API");
        capabilities.add(cap);
        card.put("capabilities", capabilities);

        List<Map<String, Object>> endpoints = new ArrayList<>();
        for (String path : List.of("/tasks/send", "/tasks/sendSubscribe", "/tasks/get")) {
            Map<String, Object> ep = new LinkedHashMap<>();
            ep.put("path", path);
            ep.put("method", "POST");
            ep.put("protocol", "A2A JSON-RPC 2.0");
            endpoints.add(ep);
        }
        card.put("endpoints", endpoints);

        byte[] cardBytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(card);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, cardBytes.length);
        exchange.getResponseBody().write(cardBytes);
        exchange.getResponseBody().close();
    }

    // ==================================================================
    // Internal helpers
    // ==================================================================

    /** 从 A2A message 中提取 text */
    @SuppressWarnings("unchecked")
    private static String extractTextFromMessage(Map<String, Object> message) {
        if (message == null) {
            return null;
        }
        List<Object> parts = (List<Object>) message.get("parts");
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        for (Object partObj : parts) {
            if (partObj instanceof Map<?, ?> part) {
                String type = String.valueOf(part.get("type"));
                if ("text".equals(type) || type.isEmpty() || "null".equals(type)) {
                    Object text = part.get("text");
                    if (text != null) {
                        return String.valueOf(text);
                    }
                }
            }
        }
        return null;
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int nRead;
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    private static void sendJsonRpcError(com.sun.net.httpserver.HttpExchange exchange,
            Object id, int code, String message) throws IOException {
        Map<String, Object> response = jsonRpcError(id, code, message);
        byte[] responseBytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(400, responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
        exchange.getResponseBody().close();
    }

    // ==================================================================
    // DeepAgent 创建
    // ==================================================================

    private static DeepAgent createDeepAgent() {
        String skillsDir = resolveStringConfig("SKILLS_DIR",
                Path.of("examples", "deep_agent", "skills").toAbsolutePath().normalize().toString());
        int maxIterations = Integer.parseInt(resolveStringConfig("MAX_ITERATIONS", "60"));

        AgentCard card = AgentCard.builder()
                .id("deep_agent_a2a_server")
                .name("deep_agent_a2a_server")
                .description("A2A 协议多技能智能体服务器")
                .build();

        Map<String, String> llmConfig = SharedExampleApiConfigLoader.load();

        // SkillUseRail 会自动注入 list_skill/skill_tool 工具和技能提示词
        String systemPrompt = """
                你是一个多技能 AI 助手，可以调用工具完成用户的金融请求。
                """;

        Map<String, Object> modelConfig = buildModelConfig(llmConfig);
        Map<String, Object> backendConfig = buildBackendConfig(llmConfig);

        DeepAgentConfig config = DeepAgentConfig.builder()
                .enableTaskLoop(true)
                .systemPrompt(systemPrompt)
                .maxIterations(maxIterations)
                .language("cn")
                .model(modelConfig)
                .backend(backendConfig)
                .restrictToWorkDir(false)
                .rails(List.of(new SkillUseRail(skillsDir)))
                .build();

        Workspace workspace = Workspace.builder()
                .rootPath(Path.of(".").toAbsolutePath().normalize().toString())
                .language("cn")
                .build();

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

    private static String resolveStringConfig(String key, String defaultValue) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return env;
        }
        String property = System.getProperty(key);
        if (property != null && !property.isBlank()) {
            return property;
        }
        return defaultValue;
    }

    // ==================================================================
    // 技能动态发现
    // ==================================================================

    /** 技能元信息 */
    private record SkillInfo(String id, String name, String description) {
    }

    /** 从技能目录动态加载所有技能信息 */
    private static List<SkillInfo> loadSkillInfos() {
        String skillsDirStr = resolveStringConfig("SKILLS_DIR",
                Path.of("examples", "deep_agent", "skills").toAbsolutePath().normalize().toString());
        Path skillsDir = Path.of(skillsDirStr);
        if (!Files.exists(skillsDir) || !Files.isDirectory(skillsDir)) {
            return List.of();
        }
        SkillManager skillManager = new SkillManager("deep_agent");
        skillManager.register(skillsDir);
        return skillManager.getAll().stream()
                .map(s -> new SkillInfo(
                        Path.of(s.getDirectory()).getFileName().toString(),
                        s.getName(),
                        s.getDescription()))
                .sorted(java.util.Comparator.comparing(SkillInfo::id))
                .toList();
    }
}

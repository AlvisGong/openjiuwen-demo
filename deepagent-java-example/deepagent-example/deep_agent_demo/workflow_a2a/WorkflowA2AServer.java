/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package examples.workflow_a2a;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.openjiuwen.core.application.workflow.WorkflowAgent;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.common.constants.Constant;
import com.openjiuwen.core.session.interaction.InteractionOutput;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * A2A (Agent-to-Agent) protocol server using JSON-RPC 2.0 over HTTP.
 * <p>
 * Implements the core A2A methods:
 * <ul>
 * <li>{@code tasks/send} — non-streaming task execution</li>
 * <li>{@code tasks/sendSubscribe} — streaming task execution via SSE</li>
 * <li>{@code tasks/get} — retrieve task status</li>
 * </ul>
 * <p>
 * All endpoints accept JSON-RPC 2.0 request bodies and return
 * JSON-RPC 2.0 responses.
 */
public final class WorkflowA2AServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_PORT = 8081;

    /** In-memory task store: taskId → A2ATask */
    private static final ConcurrentHashMap<String, WorkflowA2ASupport.A2ATask> TASK_STORE = new ConcurrentHashMap<>();

    static {
        // Replace stdout/stderr with raw UTF-8 streams BEFORE any logging
        // framework initializes. Uses FileDescriptor.out to bypass the JVM's
        // default PrintStream (which encodes via the system code page, e.g. GBK).
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    private WorkflowA2AServer() {
    }

    /**
     * Start the A2A server.
     *
     * @param args optional: first arg is port number
     */
    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0] + ", using default " + DEFAULT_PORT);
            }
        }

        WorkflowAgent agent = WorkflowA2ASupport.createAgent();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        // A2A: tasks/send (non-streaming)
        server.createContext("/tasks/send", exchange -> {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendJsonRpcError(exchange, null, -32000, "Method not allowed");
                    return;
                }
                handleTasksSend(agent, exchange);
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    sendJsonRpcError(exchange, null, -32603, "Internal error: " + e.getMessage());
                } catch (IOException ignored) {
                }
            }
        });

        // A2A: tasks/sendSubscribe (streaming via SSE)
        server.createContext("/tasks/sendSubscribe", exchange -> {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendJsonRpcError(exchange, null, -32000, "Method not allowed");
                    return;
                }
                handleTasksSendSubscribe(agent, exchange);
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
                handleTasksGet(agent, exchange);
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    sendJsonRpcError(exchange, null, -32603, "Internal error: " + e.getMessage());
                } catch (IOException ignored) {
                }
            }
        });

        // Agent card endpoint (A2A discovery)
        server.createContext("/.well-known/agent-card", exchange -> {
            try {
                handleAgentCard(exchange);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        server.start();
        System.out.println("============================================");
        System.out.println("  WorkflowA2A Server started on port " + port);
        System.out.println("  A2A JSON-RPC endpoints:");
        System.out.println("    POST /tasks/send");
        System.out.println("    POST /tasks/sendSubscribe");
        System.out.println("    POST /tasks/get");
        System.out.println("    GET  /.well-known/agent-card");
        System.out.println("============================================");
        System.out.println("Example:");
        System.out.println("  curl -X POST http://localhost:" + port + "/tasks/send \\");
        System.out.println("    -H \"Content-Type: application/json\" \\");
        System.out.println(
                "    -d '{\"jsonrpc\":\"2.0\",\"method\":\"tasks/send\",\"params\":{\"id\":\"t1\",\"message\":{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":\"我要转账\"}]}},\"id\":1}'");
        System.out.println();
    }

    // ======================== JSON-RPC 2.0 helper methods ========================

    /**
     * Create a JSON-RPC 2.0 success response.
     */
    private static Map<String, Object> jsonRpcResult(Object id, Object result) {
        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("result", result);
        response.put("id", id);
        return response;
    }

    /**
     * Create a JSON-RPC 2.0 error response.
     */
    private static Map<String, Object> jsonRpcError(Object id, int code, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        Map<String, Object> error = new HashMap<>();
        error.put("code", code);
        error.put("message", message);
        response.put("error", error);
        response.put("id", id);
        return response;
    }

    /**
     * Convert an A2ATask to a JSON-friendly map (proto-compatible shape).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> taskToMap(WorkflowA2ASupport.A2ATask task) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", task.id());
        map.put("status", task.status());

        // Convert messages
        List<Map<String, Object>> messageMaps = new ArrayList<>();
        for (WorkflowA2ASupport.A2AMessage msg : task.messages()) {
            Map<String, Object> msgMap = new HashMap<>();
            msgMap.put("role", msg.role());
            msgMap.put("parts", msg.parts());
            messageMaps.add(msgMap);
        }
        map.put("messages", messageMaps);

        // Artifacts
        map.put("artifacts", task.artifacts());

        // Extra: conversation_id for the reply chain
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("conversation_id", task.conversationId());
        map.put("metadata", metadata);

        return map;
    }

    // ======================== Request handlers ========================

    /**
     * Handle tasks/send (non-streaming).
     * <p>
     * Request:
     * 
     * <pre>
     * {
     *   "jsonrpc": "2.0",
     *   "method": "tasks/send",
     *   "params": {
     *     "id": "task-123",
     *     "message": {
     *       "role": "user",
     *       "parts": [{"type": "text", "text": "我要转账"}]
     *     },
     *     "metadata": {
     *       "conversation_id": "conv-abc",
     *       "node_id": null
     *     }
     *   },
     *   "id": 1
     * }
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private static void handleTasksSend(WorkflowAgent agent, HttpExchange exchange) throws Exception {
        // Parse JSON-RPC 2.0 request
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        System.out.println("[WorkflowA2A/tasks/send] 请求体: " + new String(bodyBytes, StandardCharsets.UTF_8));
        Map<String, Object> rpcRequest = MAPPER.readValue(bodyBytes, Map.class);

        Object jsonRpcVersion = rpcRequest.get("jsonrpc");
        Object method = rpcRequest.get("method");
        Object rpcId = rpcRequest.get("id");

        // Validate jsonrpc field
        if (!"2.0".equals(jsonRpcVersion)) {
            sendJsonRpcError(exchange, rpcId, -32600, "Invalid JSON-RPC version");
            return;
        }

        // Validate method
        if (!"tasks/send".equals(method)) {
            sendJsonRpcError(exchange, rpcId, -32601, "Method not found: " + method);
            return;
        }

        Map<String, Object> params = (Map<String, Object>) rpcRequest.get("params");
        if (params == null) {
            sendJsonRpcError(exchange, rpcId, -32602, "Missing params");
            return;
        }

        // Extract task ID
        String taskId = getString(params, "id");
        if (taskId == null) {
            taskId = "task-" + UUID.randomUUID().toString().substring(0, 8);
        }

        // Extract message
        Map<String, Object> message = (Map<String, Object>) params.get("message");
        if (message == null) {
            sendJsonRpcError(exchange, rpcId, -32602, "Missing message");
            return;
        }

        // Extract text from parts
        List<Map<String, Object>> parts = (List<Map<String, Object>>) message.get("parts");
        String query = "";
        if (parts != null && !parts.isEmpty()) {
            Object textObj = parts.get(0).get("text");
            query = textObj != null ? String.valueOf(textObj) : "";
        }

        if (query.isBlank()) {
            sendJsonRpcError(exchange, rpcId, -32602, "Empty message text");
            return;
        }

        // Extract metadata (conversation_id, node_id)
        Map<String, Object> metadata = (Map<String, Object>) params.get("metadata");
        String conversationId = null;
        String nodeId = null;
        if (metadata != null) {
            conversationId = getString(metadata, "conversation_id");
            nodeId = getString(metadata, "node_id");
        }
        if (conversationId == null) {
            conversationId = "conv-" + UUID.randomUUID().toString().substring(0, 8);
        }

        // Execute
        WorkflowA2ASupport.A2ATask task = WorkflowA2ASupport.executeTask(
                agent, taskId, query, nodeId, conversationId);

        // Store
        TASK_STORE.put(taskId, task);

        // Send JSON-RPC 2.0 response
        Map<String, Object> response = jsonRpcResult(rpcId, taskToMap(task));
        byte[] jsonBytes = MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(response);
        System.out.println("[WorkflowA2A/tasks/send] 响应体: " + new String(jsonBytes, StandardCharsets.UTF_8));
        exchange.getResponseHeaders().set("Content-Type",
                "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, jsonBytes.length);
        exchange.getResponseBody().write(jsonBytes);
        exchange.getResponseBody().close();
    }

    /**
     * Handle tasks/sendSubscribe (streaming via SSE).
     * <p>
     * Same request format as tasks/send.
     * Response is SSE with JSON-RPC 2.0 notification events:
     * 
     * <pre>
     * event: task
     * data: {"jsonrpc":"2.0","method":"tasks/sendSubscribe","params":{"id":"task-123","status":"working",...}}
     *
     * event: status_update
     * data: {"jsonrpc":"2.0","method":"tasks/sendSubscribe","params":{...}}
     *
     * event: completed
     * data: {"jsonrpc":"2.0","method":"tasks/sendSubscribe","params":{...}}
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private static void handleTasksSendSubscribe(
            WorkflowAgent agent, HttpExchange exchange) throws Exception {
        // Parse JSON-RPC 2.0 request
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        System.out.println("[WorkflowA2A/tasks/sendSubscribe] 请求体: " + new String(bodyBytes, StandardCharsets.UTF_8));
        Map<String, Object> rpcRequest = MAPPER.readValue(bodyBytes, Map.class);

        Object jsonRpcVersion = rpcRequest.get("jsonrpc");
        Object method = rpcRequest.get("method");
        Object rpcId = rpcRequest.get("id");

        if (!"2.0".equals(jsonRpcVersion)) {
            sendJsonRpcError(exchange, rpcId, -32600, "Invalid JSON-RPC version");
            return;
        }
        if (!"tasks/sendSubscribe".equals(method)) {
            sendJsonRpcError(exchange, rpcId, -32601,
                    "Method not found: " + method);
            return;
        }

        Map<String, Object> params = (Map<String, Object>) rpcRequest.get("params");
        if (params == null) {
            sendJsonRpcError(exchange, rpcId, -32602, "Missing params");
            return;
        }

        String taskId = getString(params, "id");
        if (taskId == null) {
            taskId = "task-" + UUID.randomUUID().toString().substring(0, 8);
        }

        Map<String, Object> message = (Map<String, Object>) params.get("message");
        if (message == null) {
            sendJsonRpcError(exchange, rpcId, -32602, "Missing message");
            return;
        }

        List<Map<String, Object>> parts = (List<Map<String, Object>>) message.get("parts");
        String query = "";
        if (parts != null && !parts.isEmpty()) {
            Object textObj = parts.get(0).get("text");
            query = textObj != null ? String.valueOf(textObj) : "";
        }

        if (query.isBlank()) {
            sendJsonRpcError(exchange, rpcId, -32602, "Empty message text");
            return;
        }

        Map<String, Object> metadata = (Map<String, Object>) params.get("metadata");
        String conversationId = null;
        String nodeId = null;
        if (metadata != null) {
            conversationId = getString(metadata, "conversation_id");
            nodeId = getString(metadata, "node_id");
        }
        if (conversationId == null) {
            conversationId = "conv-" + UUID.randomUUID().toString().substring(0, 8);
        }

        // Set SSE headers
        exchange.getResponseHeaders().set("Content-Type",
                "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        OutputStream os = exchange.getResponseBody();

        try {
            // Send initial task state (working)
            Map<String, Object> workingTask = new HashMap<>();
            workingTask.put("id", taskId);
            workingTask.put("status", "working");
            workingTask.put("messages", List.of());
            workingTask.put("artifacts", List.of());
            String workingTaskData = wrapJsonRpcNotification("tasks/sendSubscribe", workingTask);
            System.out.println("[WorkflowA2A/tasks/sendSubscribe] SSE 事件(task): " + workingTaskData);
            writeSseEvent(os, "task", workingTaskData);

            // Execute streaming
            Iterator<Object> stream = WorkflowA2ASupport.executeTaskStreaming(
                    agent, taskId, query, nodeId, conversationId);

            String lastText = null;
            String interactionText = null;
            String interactionNodeId = null;

            while (stream.hasNext()) {
                Object item = stream.next();
                if (!(item instanceof OutputSchema output)) {
                    continue;
                }

                String type = output.getType();
                Object payload = output.getPayload();

                if (Constant.INTERACTION.equals(type)
                        || "interaction".equals(type)) {
                    // Agent is asking for more info
                    InteractionOutput interaction = toInteraction(payload);
                    if (interaction != null) {
                        interactionText = WorkflowA2ASupport.stringify(interaction.getValue());
                        interactionNodeId = interaction.getId();
                    } else {
                        interactionText = WorkflowA2ASupport.stringify(payload);
                        interactionNodeId = "questioner";
                    }

                    // Send status update: input-required
                    Map<String, Object> statusUpdate = new HashMap<>();
                    statusUpdate.put("id", taskId);
                    statusUpdate.put("status", "input-required");
                    statusUpdate.put("message",
                            Map.of("role", "agent",
                                    "parts", List.of(
                                            Map.of("type", "text",
                                                    "text", interactionText))));
                    statusUpdate.put("node_id", interactionNodeId);
                    String statusUpdateData = wrapJsonRpcNotification(
                            "tasks/sendSubscribe", statusUpdate);
                    System.out.println("[WorkflowA2A/tasks/sendSubscribe] SSE 事件(status_update): " + statusUpdateData);
                    writeSseEvent(os, "status_update", statusUpdateData);
                    os.flush();

                } else {
                    String text = WorkflowA2ASupport.extractDisplayText(payload);
                    if (!text.isBlank()) {
                        lastText = text;

                        // Send artifact update
                        Map<String, Object> artifactUpdate = new HashMap<>();
                        artifactUpdate.put("id", taskId);
                        artifactUpdate.put("status", "working");
                        artifactUpdate.put("artifact",
                                Map.of("parts", List.of(
                                        Map.of("type", "text", "text", text))));
                        String artifactUpdateData = wrapJsonRpcNotification(
                                "tasks/sendSubscribe", artifactUpdate);
                        System.out.println(
                                "[WorkflowA2A/tasks/sendSubscribe] SSE 事件(artifact_update): " + artifactUpdateData);
                        writeSseEvent(os, "artifact_update", artifactUpdateData);
                        os.flush();
                    }
                }
            }

            // Final completed state
            Map<String, Object> finalTask = new HashMap<>();
            finalTask.put("id", taskId);
            finalTask.put("status", "completed");
            finalTask.put("messages", List.of(
                    Map.of("role", "agent",
                            "parts", List.of(
                                    Map.of("type", "text",
                                            "text", lastText != null
                                                    ? lastText
                                                    : "[完成]")))));
            finalTask.put("artifacts", List.of());
            String finalTaskData = wrapJsonRpcNotification("tasks/sendSubscribe", finalTask);
            System.out.println("[WorkflowA2A/tasks/sendSubscribe] SSE 事件(completed): " + finalTaskData);
            writeSseEvent(os, "completed", finalTaskData);
            os.flush();

            // Store final task state
            WorkflowA2ASupport.A2ATask finalA2ATask = new WorkflowA2ASupport.A2ATask(
                    taskId, "completed",
                    List.of(new WorkflowA2ASupport.A2AMessage(
                            "agent",
                            List.of(Map.of("type", "text",
                                    "text", lastText != null
                                            ? lastText
                                            : "[完成]")))),
                    List.of(), conversationId);
            TASK_STORE.put(taskId, finalA2ATask);

        } catch (Exception e) {
            Map<String, Object> errUpdate = new HashMap<>();
            errUpdate.put("id", taskId);
            errUpdate.put("status", "failed");
            errUpdate.put("error", Map.of("code", -1, "message", e.getMessage()));
            writeSseEvent(os, "error",
                    wrapJsonRpcNotification("tasks/sendSubscribe", errUpdate));
            os.flush();

            TASK_STORE.put(taskId, new WorkflowA2ASupport.A2ATask(
                    taskId, "failed", List.of(), List.of(), conversationId));
        } finally {
            os.close();
        }
    }

    /**
     * Handle tasks/get.
     * <p>
     * Request:
     * 
     * <pre>
     * {
     *   "jsonrpc": "2.0",
     *   "method": "tasks/get",
     *   "params": {"id": "task-123"},
     *   "id": 1
     * }
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private static void handleTasksGet(
            WorkflowAgent agent, HttpExchange exchange) throws Exception {
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        System.out.println("[WorkflowA2A/tasks/get] 请求体: " + new String(bodyBytes, StandardCharsets.UTF_8));
        Map<String, Object> rpcRequest = MAPPER.readValue(bodyBytes, Map.class);

        Object jsonRpcVersion = rpcRequest.get("jsonrpc");
        Object method = rpcRequest.get("method");
        Object rpcId = rpcRequest.get("id");

        if (!"2.0".equals(jsonRpcVersion)) {
            sendJsonRpcError(exchange, rpcId, -32600, "Invalid JSON-RPC version");
            return;
        }
        if (!"tasks/get".equals(method)) {
            sendJsonRpcError(exchange, rpcId, -32601, "Method not found: " + method);
            return;
        }

        Map<String, Object> params = (Map<String, Object>) rpcRequest.get("params");
        if (params == null) {
            sendJsonRpcError(exchange, rpcId, -32602, "Missing params");
            return;
        }

        String taskId = getString(params, "id");
        if (taskId == null) {
            sendJsonRpcError(exchange, rpcId, -32602, "Missing task id");
            return;
        }

        WorkflowA2ASupport.A2ATask task = TASK_STORE.get(taskId);
        if (task == null) {
            sendJsonRpcError(exchange, rpcId, -32001, "Task not found: " + taskId);
            return;
        }

        Map<String, Object> response = jsonRpcResult(rpcId, taskToMap(task));
        byte[] jsonBytes = MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(response);
        System.out.println("[WorkflowA2A/tasks/get] 响应体: " + new String(jsonBytes, StandardCharsets.UTF_8));
        exchange.getResponseHeaders().set("Content-Type",
                "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, jsonBytes.length);
        exchange.getResponseBody().write(jsonBytes);
        exchange.getResponseBody().close();
    }

    /**
     * Serve the Agent Card for A2A discovery.
     */
    private static void handleAgentCard(HttpExchange exchange) throws IOException {
        Map<String, Object> card = new HashMap<>();
        card.put("name", "金融助手 (Financial Assistant)");
        card.put("description", "支持转账、理财和余额查询的金融工作流 Agent");
        card.put("url", "http://localhost:" + exchange.getLocalAddress().getPort());
        card.put("version", "1.0.0");
        card.put("capabilities", Map.of(
                "streaming", true,
                "pushNotifications", false,
                "statefulTasks", true));
        card.put("skills", List.of(
                Map.of("id", "transfer_flow", "name", "转账服务",
                        "description", "处理用户转账、汇款请求"),
                Map.of("id", "invest_flow", "name", "理财服务",
                        "description", "处理理财、投资请求"),
                Map.of("id", "balance_flow", "name", "余额查询",
                        "description", "处理账户余额查询请求")));

        byte[] jsonBytes = MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(card);
        exchange.getResponseHeaders().set("Content-Type",
                "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, jsonBytes.length);
        exchange.getResponseBody().write(jsonBytes);
        exchange.getResponseBody().close();
    }

    // ======================== Utility methods ========================

    private static void sendJsonRpcError(
            HttpExchange exchange, Object id, int code, String message)
            throws IOException {
        Map<String, Object> response = jsonRpcError(id, code, message);
        byte[] bytes = MAPPER.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type",
                "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private static void writeSseEvent(
            OutputStream os, String event, String data) throws IOException {
        String line = "event: " + event + "\ndata: " + data + "\n\n";
        os.write(line.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static String wrapJsonRpcNotification(
            String method, Object params) throws IOException {
        Map<String, Object> notification = new HashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);
        return MAPPER.writeValueAsString(notification);
    }

    @SuppressWarnings("unchecked")
    private static String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof String s) {
            return s;
        }
        return null;
    }

    private static InteractionOutput toInteraction(Object payload) {
        if (payload instanceof InteractionOutput io) {
            return io;
        }
        if (payload instanceof Map<?, ?> map) {
            Object nodeId = map.get("id");
            Object value = map.get("value");
            return new InteractionOutput(
                    nodeId == null ? "questioner" : String.valueOf(nodeId),
                    value);
        }
        return null;
    }
}

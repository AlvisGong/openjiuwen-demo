/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 */

package com.example.deepagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.session.stream.OutputSchema;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将 agent 流式 chunk 渲染为接近 TUI/Web 的控制台输出：分区标题、增量打字、工具卡片、待办面板。
 */
public final class ConsoleStreamRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private enum OpenSection {
        NONE, THINKING, ASSISTANT
    }

    private final String userQuery;
    private int round = 1;
    private OpenSection openSection = OpenSection.NONE;
    private boolean roundHeaderPrinted;
    private boolean sawToolInRound;
    private final StringBuilder assistantBuffer = new StringBuilder();

    public ConsoleStreamRenderer(String userQuery) {
        this.userQuery = userQuery == null ? "" : userQuery.trim();
    }

    /**
     * 消费流并返回聚合的助手正文（用于落盘摘要）。
     */
    public String consume(Iterator<Object> chunks) {
        printSessionHeader();
        while (chunks.hasNext()) {
            Object chunk = chunks.next();
            if (chunk instanceof OutputSchema schema) {
                handleChunk(schema.getType(), schema.getPayload(), schema.getIndex());
            } else if (chunk instanceof Map<?, ?> map) {
                Object type = map.get("type");
                if (type != null) {
                    handleChunk(String.valueOf(type), map.get("payload"), -1);
                }
            }
        }
        closeOpenTextSections();
        println("");
        println(dim("── 流式输出结束 ──"));
        flush();
        return assistantBuffer.toString().trim();
    }

    private void printSessionHeader() {
        println(bold("════════════════════════════════════════════════════════════"));
        println(bold("  DeepAgent 对话（控制台流式预览）"));
        println(bold("════════════════════════════════════════════════════════════"));
        println("");
        println(cyan("用户"));
        println(horizontalRule());
        println(wrap(userQuery.isEmpty() ? "(空)" : userQuery));
        println("");
        flush();
    }

    private void ensureRoundHeader() {
        if (roundHeaderPrinted) {
            return;
        }
        println(bold("助手 · 第 " + round + " 轮"));
        println(horizontalRule());
        roundHeaderPrinted = true;
        flush();
    }

    private void beginNextRoundIfNeeded() {
        if (!sawToolInRound) {
            return;
        }
        closeOpenTextSections();
        println("");
        println(dim("· · ·"));
        round++;
        roundHeaderPrinted = false;
        sawToolInRound = false;
        ensureRoundHeader();
    }

    private void handleChunk(String type, Object payload, int index) {
        if (type == null || type.isBlank()) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = payload instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        switch (type) {
            case "llm_reasoning" -> appendReasoning(stringValue(body.get("content")));
            case "llm_output" -> handleLlmOutput(body);
            case "tool_call" -> printToolCall(body);
            case "tool_result" -> printToolResult(body);
            case "tool_update" -> printToolUpdate(body);
            case "llm_usage" -> { /* 静默或仅在调试时打印 */ }
            case "thinking" -> printStatus("处理中", body);
            case "todo.updated" -> printTodoPanel(body);
            case "answer" -> handleAnswer(body);
            case "error" -> printError(body);
            default -> {
                if (!body.isEmpty()) {
                    closeOpenTextSections();
                    println(dim("[" + type + (index >= 0 ? " #" + index : "") + "] " + compactJson(body)));
                    flush();
                }
            }
        }
    }

    private void handleLlmOutput(Map<String, Object> body) {
        Object toolCalls = body.get("tool_calls");
        if (toolCalls instanceof List<?> list && !list.isEmpty()) {
            closeOpenTextSections();
            ensureRoundHeader();
            printToolCallsFromModel(list);
            sawToolInRound = true;
        }
        String content = stringValue(body.get("content"));
        if (!content.isEmpty()) {
            appendAssistant(content);
        }
    }

    private void appendReasoning(String content) {
        if (content.isEmpty()) {
            return;
        }
        beginNextRoundIfNeeded();
        ensureRoundHeader();
        if (openSection != OpenSection.THINKING) {
            closeOpenTextSections();
            println("");
            println(magenta("▼ 思考过程"));
            openSection = OpenSection.THINKING;
        }
        System.out.print(dim("  "));
        System.out.print(content);
        flush();
    }

    private void appendAssistant(String content) {
        if (content.isEmpty()) {
            return;
        }
        beginNextRoundIfNeeded();
        ensureRoundHeader();
        if (openSection != OpenSection.ASSISTANT) {
            closeOpenTextSections();
            println("");
            println(green("▼ 助手回复"));
            openSection = OpenSection.ASSISTANT;
        }
        System.out.print(content);
        assistantBuffer.append(content);
        flush();
    }

    private void closeOpenTextSections() {
        if (openSection != OpenSection.NONE) {
            println("");
            openSection = OpenSection.NONE;
            flush();
        }
    }

    private void printToolCallsFromModel(List<?> toolCalls) {
        for (Object item : toolCalls) {
            if (item instanceof ToolCall call) {
                printToolCard(call.getName(), call.getArguments(), null, "模型发起");
            } else if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) map;
                printToolCard(
                        stringValue(m.getOrDefault("name", m.get("tool_name"))),
                        m.get("arguments"),
                        null,
                        "模型发起"
                );
            } else {
                printToolCard("unknown", item, null, "模型发起");
            }
        }
    }

    private void printToolCall(Map<String, Object> body) {
        closeOpenTextSections();
        ensureRoundHeader();
        Object nested = body.get("tool_call");
        if (nested instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> toolCall = (Map<String, Object>) map;
            printToolCard(
                    stringValue(toolCall.getOrDefault("tool_name", toolCall.get("name"))),
                    toolCall.get("tool_args"),
                    null,
                    "执行中"
            );
        } else {
            printToolCard(
                    stringValue(body.get("tool_name")),
                    body.get("tool_args"),
                    null,
                    "执行中"
            );
        }
        flush();
    }

    private void printToolResult(Map<String, Object> body) {
        closeOpenTextSections();
        ensureRoundHeader();
        String name = stringValue(body.get("tool_name"));
        Object result = body.containsKey("tool_result") ? body.get("tool_result") : body.get("result");
        printToolCard(name, body.get("tool_args"), result, "完成");
        if (name.startsWith("todo_")) {
            printTodoFromToolResult(name, result);
        }
        sawToolInRound = true;
        flush();
    }

    private void printToolUpdate(Map<String, Object> body) {
        closeOpenTextSections();
        println("");
        println(yellow("◆ 工具进度 ") + stringValue(body.get("tool_name")));
        println(dim(wrap(compactJson(body))));
        flush();
    }

    private void printTodoFromToolResult(String toolName, Object result) {
        println("");
        println(cyan("▼ 待办面板") + dim(" （来自 " + toolName + "）"));
        println(formatTodoResult(result));
        flush();
    }

    private void printTodoPanel(Map<String, Object> body) {
        closeOpenTextSections();
        println("");
        println(cyan("▼ 待办更新"));
        Object todos = body.get("todos");
        if (todos != null) {
            println(formatTodoResult(todos));
        } else {
            println(dim(compactJson(body)));
        }
        flush();
    }

    private void handleAnswer(Map<String, Object> body) {
        closeOpenTextSections();
        Object output = body.get("output");
        String text = extractAnswerText(output);
        if (!text.isEmpty() && !assistantBuffer.toString().contains(text)) {
            println("");
            println(green("▼ 最终答复"));
            println(wrap(text));
            assistantBuffer.append(text);
        }
        String resultType = stringValue(body.get("result_type"));
        if ("error".equalsIgnoreCase(resultType)) {
            printError(body);
            return;
        }
        println(dim("  ✓ 第 " + round + " 轮结束"));
        flush();
    }

    private void printError(Map<String, Object> body) {
        closeOpenTextSections();
        println("");
        println(red("✗ 错误"));
        Object msg = body.getOrDefault("output", body.get("error"));
        println(wrap(stringValue(msg)));
        flush();
    }

    private void printStatus(String label, Map<String, Object> body) {
        println(dim("… " + label + (body.isEmpty() ? "" : " " + compactJson(body))));
        flush();
    }

    private void printToolCard(String name, Object args, Object result, String phase) {
        println("");
        println(yellow("🔧 工具 · " + (name.isEmpty() ? "(未命名)" : name)) + dim("  [" + phase + "]"));
        if (args != null && !stringValue(args).isBlank()) {
            println(dim("  参数:"));
            println(indent(formatArgs(args), 4));
        }
        if (result != null) {
            println(dim("  结果:"));
            String formatted = name.startsWith("todo_") ? formatTodoResult(result) : formatResult(result);
            println(indent(formatted, 4));
        }
    }

    private static String extractAnswerText(Object output) {
        if (output == null) {
            return "";
        }
        if (output instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) map;
            if (m.containsKey("output")) {
                return extractAnswerText(m.get("output"));
            }
            return compactMap(m);
        }
        return stringValue(output);
    }

    private static String formatArgs(Object args) {
        if (args == null) {
            return "";
        }
        if (args instanceof String s) {
            String trimmed = s.trim();
            if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                    || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                try {
                    Object parsed = MAPPER.readValue(trimmed, Object.class);
                    return MAPPER.writeValueAsString(parsed);
                } catch (Exception ignored) {
                    return trimmed;
                }
            }
            return trimmed;
        }
        return compactJson(args);
    }

    private static String formatResult(Object result) {
        String raw = stringValue(result);
        if (raw.length() > 1200) {
            return raw.substring(0, 1200) + "\n  … (结果已截断)";
        }
        return wrap(raw);
    }

    private static String formatTodoResult(Object result) {
        String raw = formatArgs(result);
        if (raw.contains("in_progress") || raw.contains("completed") || raw.contains("pending")) {
            return prettifyTodoLines(raw);
        }
        return wrap(raw);
    }

    private static String prettifyTodoLines(String jsonOrText) {
        StringBuilder sb = new StringBuilder();
        String[] lines = jsonOrText.split("\n");
        for (String line : lines) {
            String status = "";
            String icon = "○";
            if (line.contains("in_progress")) {
                status = "进行中";
                icon = "●";
            } else if (line.contains("completed")) {
                status = "已完成";
                icon = "✓";
            } else if (line.contains("pending")) {
                status = "待办";
                icon = "○";
            } else if (line.contains("cancelled")) {
                status = "已取消";
                icon = "×";
            }
            if (!status.isEmpty()) {
                sb.append("  ").append(icon).append(' ');
                String content = extractJsonField(line, "content");
                if (content.isEmpty()) {
                    content = line.trim();
                }
                sb.append(content);
                sb.append(dim("  [" + status + "]")).append('\n');
            }
        }
        if (sb.isEmpty()) {
            return indent(wrap(jsonOrText), 2);
        }
        return sb.toString();
    }

    private static String extractJsonField(String line, String field) {
        String key = "\"" + field + "\"";
        int idx = line.indexOf(key);
        if (idx < 0) {
            return "";
        }
        int colon = line.indexOf(':', idx);
        if (colon < 0) {
            return "";
        }
        int start = line.indexOf('"', colon + 1);
        if (start < 0) {
            return "";
        }
        int end = line.indexOf('"', start + 1);
        if (end < 0) {
            return "";
        }
        return line.substring(start + 1, end);
    }

    private static String compactJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    @SuppressWarnings("unchecked")
    private static String compactMap(Map<String, Object> map) {
        return compactJson(map);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : Objects.toString(value, "").trim();
    }

    private static String wrap(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("\r\n", "\n");
    }

    private static String indent(String text, int spaces) {
        String pad = " ".repeat(Math.max(0, spaces));
        return text.replace("\n", "\n" + pad);
    }

    private static String horizontalRule() {
        return dim("──────────────────────────────────────────────────────────");
    }

    private static void println(String line) {
        System.out.println(line);
    }

    private static void flush() {
        System.out.flush();
    }

    // ANSI（Windows 10+ / 现代终端普遍支持）
    private static String bold(String s) {
        return "\u001b[1m" + s + "\u001b[0m";
    }

    private static String dim(String s) {
        return "\u001b[2m" + s + "\u001b[0m";
    }

    private static String cyan(String s) {
        return "\u001b[36m" + s + "\u001b[0m";
    }

    private static String green(String s) {
        return "\u001b[32m" + s + "\u001b[0m";
    }

    private static String yellow(String s) {
        return "\u001b[33m" + s + "\u001b[0m";
    }

    private static String magenta(String s) {
        return "\u001b[35m" + s + "\u001b[0m";
    }

    private static String red(String s) {
        return "\u001b[31m" + s + "\u001b[0m";
    }
}

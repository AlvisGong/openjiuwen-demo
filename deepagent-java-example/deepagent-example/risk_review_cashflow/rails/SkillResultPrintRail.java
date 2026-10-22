/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package examples.risk_review_cashflow.rails;

import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 记录 skill 相关工具（skill_tool / cashflow_review 等）调用参数与
 * 返回结果的 Rail，调用记录以 UTF-8 追加写入本地日志文件，
 * 便于回溯观察 skill 执行链路的中间产物。
 *
 * <p>
 * 默认日志文件为 {@code examples/risk_review_cashflow/logs/rail-tool-calls.log}
 * （相对运行时工作目录，即仓库根目录），也可通过
 * {@link #SkillResultPrintRail(Path)} 指定路径。
 * 每行记录带毫秒级时间戳，返回结果超过 2000 字符自动截断。
 *
 * <p>
 * 用法：在 DeepAgentConfig 中注册
 * {@code .rails(List.of(new SkillResultPrintRail()))}。
 *
 * @since 0.1.14
 */
public final class SkillResultPrintRail extends AgentRail {

    private static final int MAX_RESULT_CHARS = 2000;

    private static final String TAG = "[rail][skill] ";

    private static final String DEFAULT_LOG_FILE = "examples/risk_review_cashflow/logs/rail-tool-calls.log";

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 调用记录日志文件路径。 */
    private final Path logFile;

    /**
     * Construct the rail，日志文件使用默认路径 logs/rail-tool-calls.log。
     *
     * @since 0.1.14
     */
    public SkillResultPrintRail() {
        this(Paths.get(DEFAULT_LOG_FILE));
    }

    /**
     * Construct the rail，指定调用记录日志文件路径。
     *
     * @param logFile 日志文件路径（UTF-8 追加写，父目录不存在时自动创建）
     * @since 0.1.14
     */
    public SkillResultPrintRail(Path logFile) {
        this.logFile = logFile;
    }

    /**
     * beforeToolCall hook：记录工具名与调用参数到日志文件。
     *
     * @param ctx callback context
     * @since 0.1.14
     */
    @Override
    public void beforeToolCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        appendLine(TAG + "调用工具 " + inputs.getToolName()
                + "，参数: " + inputs.getToolArgs());
    }

    /**
     * afterToolCall hook：记录工具名与返回结果（超长截断）到日志文件。
     *
     * @param ctx callback context
     * @since 0.1.14
     */
    @Override
    public void afterToolCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        appendLine(TAG + "工具 " + inputs.getToolName()
                + " 返回结果:\n" + TAG + truncate(inputs.getToolResult()));
    }

    private static String truncate(Object result) {
        String text = result == null ? "(null)" : String.valueOf(result);
        if (text.length() <= MAX_RESULT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_RESULT_CHARS)
                + "...(截断，共 " + text.length() + " 字符)";
    }

    /**
     * 追加一条带时间戳的调用记录到日志文件（UTF-8）。
     * 写入失败仅降级输出到 stderr，不影响 Agent 执行。
     *
     * @param message 记录内容
     */
    private synchronized void appendLine(String message) {
        String line = LocalDateTime.now().format(TIMESTAMP_FORMAT) + " " + message
                + System.lineSeparator();
        try {
            Path parent = logFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(logFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println(TAG + "写入调用记录失败: " + logFile + "，原因: " + e.getMessage());
        }
    }
}

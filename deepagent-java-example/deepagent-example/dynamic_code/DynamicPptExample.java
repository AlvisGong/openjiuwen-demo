/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package myexample.dynamic_code;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.sysop.OperationMode;
import com.openjiuwen.core.sysop.SysOperationCard;
import com.openjiuwen.core.sysop.config.LocalWorkConfig;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.rails.SysOperationRail;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;

import examples.utils.SharedExampleApiConfigLoader;

import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DynamicPptExample — 基于 DeepAgent「动态生成 PPT」样例。
 *
 * <p>演示如何让 DeepAgent 根据「一段内容描述」生成一页精美 PPT，过程中
 * <b>通过执行 Python 代码</b>（python-pptx）完成实际排版与文件生成，
 * 而不是把 PPT 当成纯文字回复。
 *
 * <p>核心机制（内容 → Markdown → 生成脚本 → 执行）：
 * <ul>
 *   <li>{@code SkillUseRail}：通过 {@code skillDirectories} 自动加载 ppt_generator 技能，
 *       框架自动注册 {@code list_skill} / {@code skill_tool} 工具，并把技能说明注入系统提示词。</li>
 *   <li>{@code SysOperationRail}：注册 {@code code.executeCode}（Python 沙箱）、
 *       {@code fs.readFile/writeFile/listFiles}、{@code shell.executeCmd} 工具。</li>
 *   <li>Agent 自主：调用 {@code skill_tool} 阅读 SKILL.md → 把内容整理成 Markdown →
 *       根据该 Markdown 的实际结构每次生成一个贴合内容的 Python 脚本（{@code import ppt_kit}
 *       复用绘图原语）→ 调用 {@code executeCode} 执行该脚本 → 产出 .pptx。</li>
 * </ul>
 *
 * <p>与"固定 spec 模板"的区别：输入内容千变万化，固定模板无法适配。本样例要求 Agent
 * 每次按内容结构生成全新脚本（表格/多栏/流程/图文混排随内容而变），从而覆盖各种场景。
 *
 * <h3>运行方式</h3>
 * <pre>{@code
 *   # 默认：用内置的内容描述生成一页 PPT
 *   java myexample.dynamic_code.DynamicPptExample
 *
 *   # 自定义内容描述
 *   java myexample.dynamic_code.DynamicPptExample --query "根据下面内容生成一页PPT：..."
 * }</pre>
 *
 * <h3>前置条件</h3>
 * <ul>
 *   <li>本机可执行 {@code python}（或 {@code python3}）。</li>
 *   <li>首次运行时脚本会自动 {@code pip install python-pptx}；无网络时请提前
 *       {@code pip install python-pptx}。</li>
 * </ul>
 */
public final class DynamicPptExample {

    private static final Path WORKSPACE_ROOT = Path.of(
            "examples", "deepagent-example", "dynamic_code").toAbsolutePath().normalize();
    private static final Path SKILLS_DIR = WORKSPACE_ROOT.resolve("skills");
    private static final Path OUTPUT_DIR = WORKSPACE_ROOT.resolve("output");

    /** 内置的默认内容描述（即本示例任务的那一页）。 */
 private static final String DEFAULT_CONTENT_QUERY = """
        请根据下面描述的内容，帮我生成一页精美的 PPT（保存为 .pptx 文件并返回路径）：

                Prompt 写得再好 你能表达的，都是显性的	
                穷尽不了代码库的所有隐式规则	\s
                	命名规范、逻辑约定、边界处理…\s
                \s
                上下文窗口再大，你能塞进去的，都是有限的	\s
                装不下整个仓库的架构决策\s
                	模块边界、依赖关系、演进历史…\s
                \s
                模型能力再强 再智能，也没有项目记忆	
                不知道你的项目有什么约定
                	代码风格、设计模式、团队习惯…
                \s
                "教得更好"有天花板	你教得再多，追不上变化	
                规则随代码演进变化，永远追不上
                	重构、新需求、技术债、团队更替….
                	
                结论：靠 Prompt 不如让 Al 直接读懂你的代码库和规则\s
        """;
    // harness的ETCSLV模型：
    // 全称	职责	它在对抗什么问题
    // E Execution Loop	驱动 Agent "思考—行动—观察"的主循环	失控（死循环、无限重试、跑飞）
    // T Tool Registry	定义工具能做什么、不能做什么、做错了怎么反馈	能力边界模糊、参数非法、错误无法恢复
    // C Context Manager	维护上下文的质量与体积	上下文膨胀、注意力漂移、信息退化
    // S State Store	持久化运行状态，支持中断与恢复	长任务中断丢状态、不可回放
    // L Lifecycle Hooks 在关键时机插入强规则拦截	模型不可控、强约束无法落地
    // V Evaluation Interface	提供可观测、可分析的评估体系	质量不可量化、问题不可追溯
    //

    private DynamicPptExample() {
    }

    public static void main(String[] args) {
        fixConsoleEncoding();
        try {
            // 1. 初始化 Runner
            Runner.start();

            // 2. 预注册 SysOperationCard（设置 shell 白名单，包含 python/pip/bash/sh）
            registerSysOperationCard();

            // 3. 构建 DeepAgent（SkillUseRail + SysOperationRail）
            DeepAgent agent = buildAgent();

            // 4. 注册 sysOperation 工具卡片（code.executeCode / fs / shell）
            addTools(agent);

            // 5. 解析参数，构建 query
            String query = resolveQuery(args);

            System.out.println("========== Dynamic PPT Generation Example ==========");
            System.out.println("Workspace : " + WORKSPACE_ROOT);
            System.out.println("SkillsDir : " + SKILLS_DIR);
            System.out.println("OutputDir : " + OUTPUT_DIR);
            System.out.println("Query     : " + query);
            System.out.println();

            // 6. 执行
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("query", query);

            Object result = agent.run(inputs);

            System.out.println("========== Result ==========");
            System.out.println(result);
        } finally {
            Runner.stop();
        }
    }

    // =========================================================================
    // 控制台编码修复（Windows 中文乱码）
    // =========================================================================

    @SuppressWarnings("java:S108")
    private static void fixConsoleEncoding() {
        String stdoutEncoding = System.getProperty("stdout.encoding", "");
        if ("GBK".equalsIgnoreCase(stdoutEncoding)) {
            try {
                System.setOut(new PrintStream(System.out, true, "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                // UTF-8 不可用时忽略
            }
        }
    }

    // =========================================================================
    // SysOperation 预注册（白名单）
    // =========================================================================

    /**
     * 预注册 SysOperationCard，设置 shell 白名单包含 python/pip/bash/sh。
     * <p>executeCode 直接用 ProcessBuilder 启动 python，不经过 shell 白名单；
     * 此白名单主要服务于 executeCmd（例如让 Agent 用 shell 执行
     * {@code pip install python-pptx} 兜底）。
     */
    private static void registerSysOperationCard() {
        String sysOpId = "dynamic_ppt_agent_dynamic_ppt_agent";

        List<String> shellAllowlist = Arrays.asList(
                "echo", "ls", "dir", "cd", "pwd", "python", "python3", "pip", "pip3",
                "npm", "node", "git", "cat", "type", "mkdir", "md", "rm", "rd",
                "cp", "copy", "mv", "move", "grep", "find", "curl", "wget",
                "bash", "sh"
        );

        LocalWorkConfig workConfig = LocalWorkConfig.builder()
                .workDir(WORKSPACE_ROOT.toString())
                .shellAllowlist(shellAllowlist)
                .restrictToSandbox(false)
                .build();

        SysOperationCard sysOpCard = SysOperationCard.builder()
                .id(sysOpId)
                .name(sysOpId)
                .mode(OperationMode.LOCAL)
                .workConfig(workConfig)
                .build();

        Runner.resourceMgr().addSysOperation(sysOpCard, null);
        System.out.println("[INFO] Pre-registered SysOperationCard (shell allowlist includes python/pip/bash/sh)");
    }

    // =========================================================================
    // 查询构建
    // =========================================================================

    private static String resolveQuery(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--query".equals(args[i]) && i + 1 < args.length) {
                return args[++i];
            }
        }
        return DEFAULT_CONTENT_QUERY;
    }

    // =========================================================================
    // Agent 构建
    // =========================================================================

    private static DeepAgent buildAgent() {
        Map<String, String> llmConfig = SharedExampleApiConfigLoader.load();

        String systemPrompt = "你是一个 PPT 生成助手，配备了 ppt_generator 技能。\n"
                + "输入内容千变万化，固定模板无法适配，因此不要套用固定 spec/模板。请严格按以下流程操作：\n"
                + "1. 调用 list_skill 确认可用技能；调用 skill_tool 阅读 ppt_generator 的 SKILL.md，"
                + "并阅读参考实现 reference_gen_ppt.py 与可复用工具箱 ppt_kit.py（技能目录绝对路径见 skill_tool 返回的 skill_directory）。\n"
                + "2. 把用户内容整理成结构清晰的 Markdown，用 fs.writeFile 写入输出目录 "
                + OUTPUT_DIR + "（例如 content.md）。\n"
                + "3. 根据该 Markdown 的实际结构（表格 / 并列分论点 / 流程时间线 / 图文混排），"
                + "生成一个全新的、贴合内容的 Python 脚本（例如 gen_ppt.py，用 fs.writeFile 写入 "
                + OUTPUT_DIR + "）：脚本中 import 技能目录下的 ppt_kit 复用绘图原语，"
                + "按内容选择 add_table / 多栏 add_card / 自绘节点等布局，最后 save 到 .pptx。\n"
                + "4. 用 code.executeCode(language=python) 执行该脚本，生成 .pptx 到 " + OUTPUT_DIR + "。\n"
                + "5. 用 fs.listFiles 确认 .pptx 已生成，向用户返回该文件的绝对路径，并说明采用了哪种布局。\n"
                + "注意：python-pptx 首次运行会自动安装；每次都要根据内容生成贴合的脚本，不要套用固定模板；"
                + "务必产出 .pptx 文件并返回路径，不要只输出文字。\n";

        AgentCard card = AgentCard.builder()
                .id("dynamic_ppt_agent")
                .name("dynamic_ppt_agent")
                .description("动态 PPT 生成 Agent — 通过 Python 代码生成一页精美 PPT")
                .build();

        // rails：SysOperationRail 提供 code/fs/shell 工具；
        // SkillUseRail 由框架根据 skillDirectories 自动添加。
        List<Object> rails = new ArrayList<>();
        rails.add(new SysOperationRail());

        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(systemPrompt)
                .maxIterations(30)
                .language("cn")
                .enableTaskLoop(true)
                .enableTaskPlanning(true)
                // 整轮任务超时（秒）：默认仅 600s，推理模型多步（读技能→生成md→生成
                // 脚本→执行→确认）易超时，放宽到 1800s；可在 apiconfig.json 用
                // COMPLETION_TIMEOUT 覆盖。
                .completionTimeout(Double.parseDouble(
                        llmConfig.getOrDefault("COMPLETION_TIMEOUT", "1800")))
                // 关键：指定技能目录，框架自动加载 ppt_generator 并注册 list_skill / skill_tool
                .skillDirectories(List.of(SKILLS_DIR.toString()))
                .skillMode("all")
                .rails(rails)
                .model(buildModelConfig(llmConfig))
                .backend(buildBackendConfig(llmConfig))
                .restrictToWorkDir(false)
                .build();

        Workspace workspace = Workspace.builder()
                .rootPath(WORKSPACE_ROOT.toString())
                .language("cn")
                .build();

        System.out.println("[INFO] Skill directory: " + SKILLS_DIR);
        System.out.println("[INFO] Output directory: " + OUTPUT_DIR);
        return HarnessFactory.createDeepAgent(card, config, workspace);
    }

    // =========================================================================
    // SysOperation 工具注册
    // =========================================================================

    /**
     * 注册本示例所需的 sysOperation 工具卡片：
     * <ul>
     *   <li>{@code code.executeCode} — 执行 Python 代码生成 PPT（核心）</li>
     *   <li>{@code fs.writeFile / readFile / listFiles} — 写 Markdown、生成/读取脚本、确认产物</li>
     *   <li>{@code shell.executeCmd} — 兜底：手动 pip install / 运行脚本</li>
     * </ul>
     */
    private static void addTools(DeepAgent agent) {
        String sysOpId = agent.getCard().getName() + "_" + agent.getCard().getId();

        addSysOpTool(agent, sysOpId, "code", "executeCode");
        addSysOpTool(agent, sysOpId, "fs", "writeFile");
        addSysOpTool(agent, sysOpId, "fs", "readFile");
        addSysOpTool(agent, sysOpId, "fs", "listFiles");
        addSysOpTool(agent, sysOpId, "shell", "executeCmd");
    }

    private static void addSysOpTool(DeepAgent agent, String sysOperationId,
                                      String operationName, String toolName) {
        Object toolCard = Runner.resourceMgr().getSysOpToolCards(
                sysOperationId, operationName, toolName);
        if (toolCard != null) {
            agent.getAgent().getAbilityManager().add(toolCard);
            System.out.println("[INFO] Registered tool: " + operationName + "." + toolName);
        } else {
            System.out.println("[WARN] Tool not found: " + operationName + "." + toolName);
        }
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
        // 单次模型调用超时（秒）。glm-5.2 等推理模型 + 工具/技能提示词较大，
        // 默认 60s 易触发 java.net.http.HttpTimeoutException: request timed out，
        // 这里放宽到 300s；可在 apiconfig.json 用 MODEL_TIMEOUT 覆盖。
        backend.put("timeout",
                Double.parseDouble(llmConfig.getOrDefault("MODEL_TIMEOUT", "300")));
        // 失败重试次数设为 1：真实超时下避免再排队 3 次慢重试。
        backend.put("max_retries",
                Integer.parseInt(llmConfig.getOrDefault("MODEL_MAX_RETRIES", "1")));
        return backend;
    }
}

/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package examples.risk_review_cashflow;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.core.session.checkpointer.InMemoryCheckpointer;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import examples.risk_review_cashflow.rails.SkillResultPrintRail;
import examples.risk_review_cashflow.tools.CashflowReviewTool;
import examples.risk_review_cashflow.tools.GaussDbClient;
import examples.risk_review_cashflow.tools.GaussDbConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepAgent + 重构版 Skill（fund_cashflow_review_skill_java）+ Java 工具样例。
 *
 * <p>
 * 演示链路：DeepAgent 加载 SKILL.md（经营流水下滑场景评估指引），
 * 按 skill 指引调用 Java 工具 {@code cashflow_review}，
 * 工具通过 openGauss JDBC 直连 desktop 上的 GaussDB 读取
 * EMC 经营性流水分析数据，最终由 Agent 输出复核结论。
 *
 * <p>
 * 运行方式（在仓库根目录执行 {@code examples/risk_review_cashflow/run.ps1}）：
 * 
 * <pre>
 * mvn -DskipTests compile
 * mvn dependency:copy-dependencies "-DoutputDirectory=target/dependency" -q
 * javac -encoding UTF-8 --release 17 \
 *   -cp "target/classes;target/dependency/*;examples/risk_review_cashflow/lib/*" \
 *   -d examples/risk_review_cashflow/build \
 *   examples/risk_review_cashflow/tools/*.java examples/risk_review_cashflow/DeepAgentCashflowSkillExample.java
 * java "-Dfile.encoding=UTF-8" \
 *   -cp "examples/risk_review_cashflow/build;target/classes;target/dependency/*;examples/risk_review_cashflow/lib/*" \
 *   examples.risk_review_cashflow.DeepAgentCashflowSkillExample
 * </pre>
 *
 * @since 0.1.14
 */
public final class DeepAgentCashflowSkillExample {

    private static final String AGENT_ID = "deep_agent_cashflow_skill";

    private static final String MODEL_NAME = "glm-5.2";

    private static final String MODEL_PROVIDER = "OpenAI";

    private static final String API_KEY = "sk-xxx";

    private static final String API_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private static final String SYSTEM_PROMPT = "你是一位银行对公客户风险复核助手。\n"
            + "请先阅读并遵循已加载的 fund_cashflow_review_skill_java 技能指引：\n"
            + "当一期COT/结论包含经营流水下滑、负现金流、收入持续下降、长期无收入时，"
            + "必须调用 cashflow_review 工具（party_id 必填）获取 EMC 经营性流水数据，"
            + "然后仅基于工具返回的事实数据撰写复核意见，不做主观臆断，"
            + "不做是否下滑的布尔判断（交主复核）。若工具返回失败，如实说明失败原因。";

    private static final String DEFAULT_QUERY = "一期COT结论提示：企业 P002 近期经营流水下滑、"
            + "收入持续下降，存在负现金流风险。请使用 fund_cashflow_review_skill_java 技能"
            + "对客户 P002 执行经营流水复核评估（party_id=P002，company_id=C002）。";

    private DeepAgentCashflowSkillExample() {
    }

    /**
     * Entry point.
     *
     * @param args optional: skill directory path (defaults to sibling project)
     * @throws Exception if execution fails
     */
    public static void main(String[] args) throws Exception {
        System.out.println("=== DeepAgent + fund_cashflow_review_skill_java 样例 ===");
        System.out.println();

        Path skillDir = resolveSkillDir(args);
        System.out.println("[setup] skill 目录: " + skillDir);
        if (!Files.isRegularFile(skillDir.resolve("SKILL.md"))) {
            throw new IOException("SKILL.md not found under: " + skillDir);
        }

        GaussDbConfig dbConfig = GaussDbConfig.load();
        System.out.println("[setup] GaussDB: " + dbConfig.toSafeString());
        System.out.println("[setup] 模型: " + MODEL_NAME + " (" + MODEL_PROVIDER + ")");
        System.out.println();

        Path workspacePath = Files.createTempDirectory("deep-agent-cashflow-");
        System.out.println("[setup] workspace=" + workspacePath);

        Runner.setConfig(RunnerConfig.DEFAULT);
        Runner.start();
        CheckpointerFactory.setDefaultCheckpointer(new InMemoryCheckpointer());

        DeepAgent deepAgent = null;
        GaussDbClient dbClient = new GaussDbClient(dbConfig);
        try {
            deepAgent = buildAgent(skillDir, workspacePath);
            deepAgent.registerHarnessTool(new CashflowReviewTool(dbClient));
            deepAgent.ensureInitialized();
            System.out.println("[setup] DeepAgent initialized, skill + tool registered");
            System.out.println();

            String sessionId = "cashflow_" + System.currentTimeMillis();
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("query", DEFAULT_QUERY);
            inputs.put("conversation_id", sessionId);

            System.out.println("========== 开始经营流水复核 ==========");
            System.out.println("[query] " + DEFAULT_QUERY);
            System.out.println();

            Map<String, Object> result = deepAgent.invoke(inputs);

            System.out.println("========== 复核完成 ==========");
            System.out.println();
            printFinalOutput(result);
        } finally {
            closeQuietly(deepAgent);
            closeDbQuietly(dbClient);
            try {
                CheckpointerFactory.setDefaultCheckpointer(null);
            } catch (Exception ignored) {
                // best-effort
            }
            Runner.stop();
            System.out.println();
            System.out.println("=== 样例结束 ===");
        }
    }

    private static DeepAgent buildAgent(Path skillDir, Path workspacePath) {
        Map<String, Object> modelConfig = new LinkedHashMap<>();
        modelConfig.put("model", MODEL_NAME);
        modelConfig.put("temperature", 0.2);
        modelConfig.put("max_tokens", 4096);
        modelConfig.put("enable_thinking", false);

        Map<String, Object> backendConfig = new LinkedHashMap<>();
        backendConfig.put("client_provider", MODEL_PROVIDER);
        backendConfig.put("api_key", API_KEY);
        backendConfig.put("api_base", API_BASE);
        backendConfig.put("verify_ssl", true);

        DeepAgentConfig config = DeepAgentConfig.builder()
                // 启用任务循环完成判定：框架自动注册 TaskCompletionRail，
                // 由 rail 评估 ReAct 循环是否已达成目标，防止提前结束或空转
                .enableTaskLoop(true)
                // 关闭任务规划：不开 TaskPlanningRail（计划/TODO 拆解工具），
                // 本样例单skill单工具链路简单，无需 LLM 先做计划
                .enableTaskPlanning(false)
                // 关闭多租户工作区隔离：不按 tenantId 划分独立工作区/技能覆盖层，
                // 本样例单租户演示，直接用下方共享 workspacePath
                .enableTenantIsolation(false)
                // Agent 工作区根目录：read_file/write 等文件工具的根路径，
                // 这里用临时目录承载会话内产生的中间文件
                .workspacePath(workspacePath.toString())
                // 系统提示词：定义 Agent 角色与行为约束（只陈述事实、不做布尔判定等）
                .systemPrompt(SYSTEM_PROMPT)
                // ReAct 循环最大迭代轮数（框架默认 15）；日志中 "ReAct iteration 1/8"
                .maxIterations(8)
                // 单次 invoke 的整体完成超时（秒），超时强制收尾；不设置则不限时
                .completionTimeout(180.0)
                // 语言（cn=中文），影响内置提示词与工作区语言
                .language("cn")
                // LLM 推理参数：模型名/温度/单轮最大输出 token/关闭深度思考
                .model(modelConfig)
                // 模型后端接入：OpenAI 兼容协议（client_provider）、密钥、
                // 网关地址、是否校验服务端 TLS 证书
                .backend(backendConfig)
                // skillDirectories 需指向包含各 skill 子目录的父目录（skills/），
                // 框架按子目录扫描 SKILL.md 注册；决定"扫描到什么" → 注册池
                .skillDirectories(List.of(skillDir.getParent().toString()))
                // skill 目录呈现方式：all=完整目录全文注入系统提示词（skill 少时直给），
                // auto_list=不预展开、额外注册 list_skill 工具按需搜索（省 token）
                .skillMode("all")
                // 启用白名单：为空=全部启用；非空=只保留名单内 skill；
                // 决定"实际启用哪些" → 白名单过滤注册池
                .skills(List.of("fund_cashflow_review_skill_java"))
                // 注册 Rail：把 skill 相关工具的调用参数与返回结果追加写入
                // examples/risk_review_cashflow/logs/rail-tool-calls.log（UTF-8 带时间戳）
                .rails(List.of(new SkillResultPrintRail()))
                .build();

        AgentCard card = AgentCard.builder()
                .id(AGENT_ID).name(AGENT_ID)
                .description("DeepAgent 经营流水复核（Java tool + GaussDB）").build();
        Workspace ws = Workspace.builder()
                .rootPath(workspacePath.toString()).language("cn").build();
        return HarnessFactory.createDeepAgent(card, config, ws);
    }

    private static void printFinalOutput(Map<String, Object> result) {
        if (result == null) {
            System.out.println("[result] 无返回结果");
            return;
        }
        Object output = result.get("output");
        if (output == null) {
            output = result.get("answer");
        }
        if (output != null) {
            System.out.println("[final answer]");
            System.out.println(output);
            return;
        }
        System.out.println("[result] raw=" + result);
    }

    private static Path resolveSkillDir(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return Paths.get(args[0]).toAbsolutePath().normalize();
        }
        String property = System.getProperty("SKILL_DIR");
        if (property != null && !property.isBlank()) {
            return Paths.get(property).toAbsolutePath().normalize();
        }
        // 仓库根目录（agent-core-java）下的示例 skill 目录
        Path repoRoot = Paths.get("").toAbsolutePath().normalize();
        return repoRoot.resolve("examples/risk_review_cashflow/skills/fund_cashflow_review_skill_java")
                .toAbsolutePath().normalize();
    }

    private static void closeQuietly(DeepAgent deepAgent) {
        if (deepAgent == null) {
            return;
        }
        try {
            deepAgent.close();
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private static void closeDbQuietly(GaussDbClient dbClient) {
        try {
            dbClient.close();
        } catch (Exception ignored) {
            // best-effort
        }
    }
}

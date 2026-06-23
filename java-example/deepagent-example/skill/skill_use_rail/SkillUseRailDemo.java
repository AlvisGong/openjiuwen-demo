/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package myexample.skill;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.rails.SkillUseRail;
import com.openjiuwen.harness.rails.SysOperationRail;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import examples.utils.SharedExampleApiConfigLoader;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SkillUseRailDemo — 基于 DeepAgent 的 SkillUseRail 功能演示。
 *
 * <p>本示例演示 SkillUseRail 的核心功能：
 * <ul>
 *   <li>通过 DeepAgentConfig.skillDirectories 指定多个 Skill 目录</li>
 *   <li>框架自动扫描目录下的 SKILL.md 并注册 Skill</li>
 *   <li>Agent 运行时获得 list_skill 和 skill_tool 两个工具</li>
 *   <li>skillMode="all" 模式下，所有 skill 信息注入到 System Prompt</li>
 *   <li>Session 级 Skill 访问控制：禁止某个 session 访问指定 skill</li>
 * </ul>
 *
 * <h3>演示步骤</h3>
 * <ol>
 *   <li>Step 1: 检查全局 Skill 注册状态</li>
 *   <li>Step 2: Session "vip_user" 正常访问 — 可见所有 skill</li>
 *   <li>Step 3: 对 Session "restricted_user" 禁止 translator skill</li>
 *   <li>Step 4: Session "restricted_user" 尝试查询翻译 — translator 被屏蔽</li>
 *   <li>Step 5: 解除禁止，恢复 translator 可见</li>
 *   <li>Step 6: 再次查询 — translator 恢复可见</li>
 * </ol>
 *
 * <h3>运行方式</h3>
 * <pre>{@code
 *   # 自动模式（默认）— 自动演示 session 级访问控制
 *   mvn compile exec:java \
 *     -Dexec.mainClass=myexample.skill.SkillUseRailDemo \
 *     -Dopenjiuwen.example.config=examples/apiconfig.json
 * }</pre>
 */
public final class SkillUseRailDemo {

    private static final Path WORKSPACE_ROOT = Path.of(".").toAbsolutePath().normalize();

    static {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    private SkillUseRailDemo() {
    }

    // =========================================================================
    // 入口
    // =========================================================================

    public static void main(String[] args) {
        try {
            Runner.start();

            DeepAgent agent = buildAgent();
            agent.ensureInitialized();

            SkillUseRail skillRail = findSkillUseRail(agent);

            // ---- Step 1: 全局 Skill 注册状态 ----
            printRegistrationStatus(skillRail);

            // ---- Step 2: Session "vip_user" — 可见所有 skill ----
            String vipSessionId = "vip_user";
            System.out.println();
            printSeparator("Step 2: Session 'vip_user' — 可见所有 skill");
            printSessionSkillStatus(skillRail, vipSessionId);

            // ---- Step 3: 对 Session "restricted_user" 禁止 translator ----
            String restrictedSessionId = "restricted_user";
            System.out.println();
            printSeparator("Step 3: 禁止 Session 'restricted_user' 访问 translator skill");
            skillRail.disableSkillForSession(restrictedSessionId, "translator");
            System.out.println("[ACTION] skillRail.disableSkillForSession(\"" + restrictedSessionId + "\", \"translator\")");
            printSessionSkillStatus(skillRail, restrictedSessionId);

            // ---- Step 4: Session "restricted_user" 尝试查询翻译 ----
            System.out.println();
            printSeparator("Step 4: Session 'restricted_user' 查询翻译 — translator 被屏蔽");

            String translateQuery = "请将以下文字翻译为英文：人工智能正在改变世界。";
            Map<String, Object> inputs4 = new LinkedHashMap<>();
            inputs4.put("query", translateQuery);
            inputs4.put("conversation_id", restrictedSessionId);

            System.out.println("Query: " + translateQuery);
            System.out.println("Session: " + restrictedSessionId);
            System.out.println("可见 skill: " + skillRail.registeredSkillNamesForSession(restrictedSessionId));
            System.out.println();
            System.out.println("  ┌─ Agent 输出 (translator 被屏蔽) ─────────────────────┐");

            Object result4 = agent.run(inputs4);

            System.out.println("  └──────────────────────────────────────────────────────┘");
            System.out.println();
            System.out.println("[INFO] 结果: " + truncateResult(String.valueOf(result4)));
            System.out.println("[INFO] Agent 无法看到 translator skill，prompt 中不会列出它");
            System.out.println("[INFO] 即使 Agent 尝试调用 skill_tool(\"translator\")，也会返回 error");

            // ---- Step 5: 解除禁止，恢复 translator ----
            System.out.println();
            printSeparator("Step 5: 解除禁止 — enableSkillForSession");
            skillRail.enableSkillForSession(restrictedSessionId, "translator");
            System.out.println("[ACTION] skillRail.enableSkillForSession(\"" + restrictedSessionId + "\", \"translator\")");
            printSessionSkillStatus(skillRail, restrictedSessionId);

            // ---- Step 6: 再次查询翻译 — translator 恢复可见 ----
            System.out.println();
            printSeparator("Step 6: Session 'restricted_user' 再次查询翻译 — translator 恢复可见");

            String query6 = "请将以下文字翻译为英文：深度学习是人工智能的重要分支。";
            Map<String, Object> inputs6 = new LinkedHashMap<>();
            inputs6.put("query", query6);
            inputs6.put("conversation_id", restrictedSessionId);

            System.out.println("Query: " + query6);
            System.out.println("Session: " + restrictedSessionId);
            System.out.println("可见 skill: " + skillRail.registeredSkillNamesForSession(restrictedSessionId));
            System.out.println();
            System.out.println("  ┌─ Agent 输出 (translator 恢复可见) ──────────────────┐");

            Object result6 = agent.run(inputs6);

            System.out.println("  └──────────────────────────────────────────────────────┘");
            System.out.println();
            System.out.println("[INFO] 结果: " + truncateResult(String.valueOf(result6)));

            // ---- 总结 ----
            System.out.println();
            printSeparator("演示总结");
            System.out.println("  三层过滤体系:");
            System.out.println("    1. 全局白名单 enabledSkills  — 只暴露指定 skill (空=全部)");
            System.out.println("    2. 全局黑名单 disabledSkills — 永久屏蔽指定 skill");
            System.out.println("    3. Session 黑名单            — 按会话动态屏蔽 skill");
            System.out.println();
            System.out.println("  API:");
            System.out.println("    skillRail.disableSkillForSession(sessionId, skillName)");
            System.out.println("    skillRail.enableSkillForSession(sessionId, skillName)");
            System.out.println("    skillRail.isSkillDisabledForSession(sessionId, skillName)");
            System.out.println("    skillRail.getSessionDisabledSkills(sessionId)");
            System.out.println("    skillRail.clearSessionDisabledSkills(sessionId)");

        } finally {
            Runner.stop();
        }
    }

    // =========================================================================
    // 打印工具
    // =========================================================================

    private static void printSeparator(String title) {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("  " + title);
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private static String truncateResult(String text) {
        if (text == null || text.isBlank()) {
            return "(空)";
        }
        int maxLen = 200;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    // =========================================================================
    // 状态检查
    // =========================================================================

    private static SkillUseRail findSkillUseRail(DeepAgent agent) {
        for (Object rail : agent.getConfig().getRails()) {
            if (rail instanceof SkillUseRail skillRail) {
                return skillRail;
            }
        }
        return null;
    }

    private static void printRegistrationStatus(SkillUseRail skillRail) {
        printSeparator("Step 1: 全局 Skill 注册状态");
        if (skillRail == null) {
            System.out.println("[WARN] SkillUseRail not found — skills are not configured.");
            return;
        }
        System.out.println("skillMode:            " + skillRail.skillMode());
        System.out.println("configuredDirectories: " + skillRail.configuredSkillDirectories());
        System.out.println("registeredSkills:     " + skillRail.registeredSkillNames());
        System.out.println("registeredTools:      " + skillRail.registeredToolNames());
        System.out.println("enabledSkills:        " + skillRail.enabledSkills());
        System.out.println("disabledSkills:       " + skillRail.disabledSkills());
        System.out.println("[OK] 全局注册完成");
    }

    private static void printSessionSkillStatus(SkillUseRail skillRail, String sessionId) {
        if (skillRail == null) {
            System.out.println("[WARN] SkillUseRail not found");
            return;
        }
        List<String> globalSkills = skillRail.registeredSkillNames();
        List<String> sessionSkills = skillRail.registeredSkillNamesForSession(sessionId);
        Set<String> sessionDisabled = skillRail.getSessionDisabledSkills(sessionId);

        System.out.println("  sessionId:               " + sessionId);
        System.out.println("  全局可见 skill:           " + globalSkills);
        System.out.println("  该 session 可见 skill:    " + sessionSkills);
        System.out.println("  该 session 禁用 skill:    " + sessionDisabled);

        // 指出差异
        List<String> hidden = globalSkills.stream()
                .filter(name -> !sessionSkills.contains(name))
                .toList();
        if (hidden.isEmpty()) {
            System.out.println("  → 无差异，该 session 可见所有 skill");
        } else {
            System.out.println("  → 被隐藏的 skill: " + hidden);
        }
    }

    // =========================================================================
    // Agent 构建
    // =========================================================================

    private static DeepAgent buildAgent() {
        Map<String, String> llmConfig = SharedExampleApiConfigLoader.load();

        Path skillsParentDir = Path.of("examples", "myexample", "skill")
                .toAbsolutePath().normalize();

        String systemPrompt = """
                你是一个智能助手，配备了 text_analyzer 和 translator 两个技能。
                工作方式：
                1. 当用户请求文本分析时，调用 skill_tool 阅读 text_analyzer 的 SKILL.md，按技能流程执行分析
                2. 当用户请求翻译时，调用 skill_tool 阅读 translator 的 SKILL.md，按翻译流程执行
                3. 按照技能说明中的维度和格式输出结果
                注意：有些 session 可能无法看到某些 skill，请只使用当前可见的 skill。
                """;

        AgentCard card = AgentCard.builder()
                .id("skill_rail_demo")
                .name("skill_rail_demo")
                .description("SkillUseRail 功能演示 Agent")
                .build();

        List<Object> rails = new ArrayList<>();
        rails.add(new SysOperationRail());

        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(systemPrompt)
                .maxIterations(15)
                .language("cn")
                .enableTaskLoop(true)
                .enableTaskPlanning(true)
                .skillDirectories(List.of(skillsParentDir.toString()))
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

        System.out.println("[INFO] Skill parent directory: " + skillsParentDir);
        return HarnessFactory.createDeepAgent(card, config, workspace);
    }

    // =========================================================================
    // LLM 配置构建
    // =========================================================================

    private static Map<String, Object> buildModelConfig(Map<String, String> llmConfig) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("model", llmConfig.getOrDefault("MODEL_NAME", "glm-5"));
        return model;
    }

    private static Map<String, Object> buildBackendConfig(Map<String, String> llmConfig) {
        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("client_provider", llmConfig.getOrDefault("MODEL_PROVIDER", ""));
        backend.put("api_key", llmConfig.getOrDefault("API_KEY", ""));
        backend.put("api_base", llmConfig.getOrDefault("API_BASE", ""));
        backend.put("verify_ssl", Boolean.parseBoolean(llmConfig.getOrDefault("LLM_SSL_VERIFY", "false")));
        return backend;
    }
}

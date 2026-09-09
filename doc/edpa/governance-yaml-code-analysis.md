# Governance 三层 YAML 配合 Deep Agent 使用：代码分析

> 分析对象：`edp-agent-java/engine/src/main/resources/governance/` 下的 `planrule.yaml`、`actrule.yaml`、`scriptconfig.yaml`
> 分析目标：三个 YAML 如何被加载、合并、消费，以及 wealth-demo 场景的使用示例

---

## 一、三层 YAML 职责划分

| YAML | 职责 | 代码入口 | 消费方 |
|------|------|----------|--------|
| `planrule.yaml` | Agent 是谁、能做什么、遵循什么协议 | `PlanrulePromptBuilder.buildSystemPromptFragment()` | 系统提示词 |
| `actrule.yaml` | 执行约束、工具注册、Todo 数据层 | `EdpaBusinessTools.build()` + `ExecutionLimitRail` + `EdpaTodoRail` | 工具注册 + 运行时拦截 |
| `scriptconfig.yaml` | 话术模板、思维链帧 | `SysScriptsConfig.load()` → `ScriptsRail` | 前端推送 + 合规把关 |

---

## 二、加载链路

### 2.1 入口：EdpaExtHandler.performInit()

**文件**：`engine/src/main/java/com/huawei/ascend/edp/handler/EdpaExtHandler.java#L394-L417`

```java
public static InitResult performInit(EdpaSpringBootConfig config, ...) {
    // 第三至五步：加载 Governance（框架级 + 场景级合并）
    loadGovernanceAndValidate(result, config, yamlDir);

    // 第六步：从 actrule 加载 Todo 数据层
    ActRuleConfig actrule = result.getGovernanceConfig().getActrule();
    EdpaTodolist edpaTodolist = loadTodoDataLayer(actrule);

    // 第七步：从 planrule 拼接系统提示词
    String systemPrompt = buildSystemPrompt(result);

    // 第八步：构造 DeepAgentConfig（actrule.maxSteps → maxIterations）
    DeepAgentConfig deepAgentConfig = buildDeepAgentConfig(config, ..., actrule, systemPrompt, ...);

    // 第十一步：加载 scriptconfig（框架级 + 场景级 + Skill级 三层合并）
    SysScriptsConfig sysScriptsConfig = loadSysScripts(result, yamlDir, skillsDir);
}
```

### 2.2 Governance 加载：loadGovernanceAndValidate()

**文件**：`EdpaExtHandler.java#L444-L500`

```java
// 框架级路径：src/main/resources/governance/
Path frameworkGovernancePath = yamlDir.resolve("governance");

// 场景级路径：${scenarioHome}/governance/
Path scenarioGovernancePath = result.getScenarioHomePath().resolve("governance");

// 双路径合并：场景级覆盖框架级
result.setGovernanceConfig(
    GovernanceConfigLoader.loadWithPriority(scenarioGovernancePath, frameworkGovernancePath));
```

### 2.3 GovernanceConfigLoader：loadWithPriority()

**文件**：`engine/src/main/java/com/huawei/ascend/edp/config/GovernanceConfigLoader.java#L43-L56`

```
loadWithPriority(scenarioDir, frameworkDir)
  ├── loadFromFilesystem(frameworkDir)  → 框架级 GovernanceConfig
  │     ├── planrule.yaml    → PlanRuleConfig
  │     ├── actrule.yaml     → ActRuleConfig
  │     └── scriptconfig.yaml → ScriptConfig
  ├── loadFromFilesystem(scenarioDir)  → 场景级 GovernanceConfig
  │     └── (同上三个文件)
  └── mergeScenarioConfig(framework, scenario)  → 合并结果
        ├── planrule: 替代式覆盖(scope) + 继承式覆盖(role) + 追加(additional_prompt)
        ├── actrule: 继承式覆盖(max_steps) + 叠加合并(allowed_tools) + 逐key取min(tool_limits)
        └── scriptconfig: 替代式覆盖(general_scripts)
```

**loadFromFilesystem()** 逐个加载三个 YAML：

```java
// 加载 planrule.yaml
Path planrulePath = governanceDir.resolve("planrule.yaml");
JsonNode root = YAML_MAPPER.readTree(Files.readString(planrulePath));
PlanRuleConfig planrule = YAML_MAPPER.treeToValue(root.get("planrule"), PlanRuleConfig.class);
config.setPlanrule(planrule);

// 加载 actrule.yaml
Path actrulePath = governanceDir.resolve("actrule.yaml");
ActRuleConfig actrule = YAML_MAPPER.treeToValue(root.get("actrule"), ActRuleConfig.class);
config.setActrule(actrule);

// 加载 scriptconfig.yaml
Path scriptconfigPath = governanceDir.resolve("scriptconfig.yaml");
ScriptConfig scriptconfig = YAML_MAPPER.treeToValue(root.get("scriptconfig"), ScriptConfig.class);
config.setScriptconfig(scriptconfig);
```

**loadFromClasspath()** 用于集成态（JAR 内加载）：

```java
config.setPlanrule(loadYamlFromClasspath(cl, basePathPrefixes,
        "governance/planrule.yaml", "planrule", PlanRuleConfig.class).orElse(null));
config.setActrule(loadYamlFromClasspath(cl, basePathPrefixes,
        "governance/actrule.yaml", "actrule", ActRuleConfig.class).orElse(null));
config.setScriptconfig(loadYamlFromClasspath(cl, basePathPrefixes,
        "governance/scriptconfig.yaml", "scriptconfig", ScriptConfig.class).orElse(null));
```

---

## 三、三个 YAML 的具体消费方式

### 3.1 planrule.yaml → 系统提示词

**消费方**：`PlanrulePromptBuilder.buildSystemPromptFragment()`

**文件**：`engine/src/main/java/com/huawei/ascend/edp/stream/PlanrulePromptBuilder.java#L62-L113`

```
拼接顺序：
  1. role           → "# 通用动态规划智能体\n\n"
  2. description    → "你的核心职责是..."
  3. scenarioName   → "**当前场景**：理财购买\n"
  4. scenarioDesc   → "理财产品推荐、筛选、购买全流程\n\n"
  5. scope          → "**当前支持的业务**：理财产品推荐...\n**禁止的业务**：基金...\n"
  6. skillRouting   → "**Skill 路由**：\n- xxx -> yyy\n"
  7. baseProtocol   → "你采用「规划—执行—观察—反思」ReAct 循环..."  ← 框架保护，不可覆盖
  8. additionalPrompt → "## 理财场景强制规则..."          ← 场景级追加
```

**代码细节**：

```java
public static String buildSystemPromptFragment(PlanRuleConfig planrule) {
    StringBuilder sb = new StringBuilder();

    // 1. 角色定义（role字段）
    if (isNotEmpty(planrule.getRole())) {
        sb.append("# ").append(planrule.getRole()).append("\n\n");
    }
    // 2. 角色描述（description字段）
    if (isNotEmpty(planrule.getDescription())) {
        sb.append(planrule.getDescription()).append("\n\n");
    }
    // 3. 场景上下文（scenarioName + scenarioDescription）
    if (isNotEmpty(planrule.getScenarioName())) {
        sb.append("**当前场景**：").append(planrule.getScenarioName()).append("\n");
    }
    // 4. 业务范围（scope字段）
    appendScopeSection(sb, planrule);
    // 5. Skill路由（skillRouting字段）
    appendSkillRoutingSection(sb, planrule);
    // 6. 补充提示词（baseProtocol + additionalPrompt）
    appendSupplementaryPromptSection(sb, planrule);

    return sb.toString().trim();
}
```

**调用方**：`EdpaExtHandler.buildSystemPrompt()`

```java
private static String buildSystemPrompt(InitResult result) {
    if (result.getGovernanceConfig() != null && result.getGovernanceConfig().getPlanrule() != null) {
        systemPrompt = PlanrulePromptBuilder.buildSystemPromptFragment(
            result.getGovernanceConfig().getPlanrule());
    } else if (result.getAgentConfig().getPrompt() != null) {
        systemPrompt = result.getAgentConfig().getPrompt().getSystem();
    }
    return systemPrompt;
}
```

**写入**：`buildDeepAgentConfig()` → `DeepAgentConfig.builder().systemPrompt(systemPrompt)`

### 3.2 actrule.yaml → 工具注册 + 执行约束 + Todo 数据层

#### 消费方 1：工具注册 — EdpaBusinessTools.build()

**文件**：`engine/src/main/java/com/huawei/ascend/edp/tools/EdpaBusinessTools.java#L93-L117`

```java
public static List<Tool> build(EdpConfig edpConfig, ActRuleConfig actrule) {
    List<Tool> tools = new ArrayList<>();
    if (actrule == null || actrule.getAllowedTools() == null || actrule.getAllowedTools().isEmpty()) {
        return tools;  // 没配 allowed_tools → 不注册任何业务工具
    }
    for (String toolName : actrule.getAllowedTools()) {
        // 跳过框架内部自动注册的工具
        if ("bash".equals(toolName) || "skill_tool".equals(toolName)
                || "todo_create".equals(toolName) || "todo_modify".equals(toolName)
                || "todo_list".equals(toolName) || "todo_get".equals(toolName)) {
            continue;
        }
        // 按名称查找对应构建器并实例化
        Tool tool = EdpaToolRegistry.build(toolName, edpConfig).orElse(null);
        if (tool != null) {
            tools.add(tool);
        }
    }
    return tools;
}
```

→ 通过 `EdpaAgentEnhancer.registerBusinessTools()` 注册到 `agent.registerHarnessTool(tool)`

#### 消费方 2：执行步数限制 — buildDeepAgentConfig()

```java
.maxIterations(actrule.getMaxSteps() != null ? actrule.getMaxSteps() : 15)
.enableTaskLoop(actrule.getEnableTaskLoop() != null ? actrule.getEnableTaskLoop() : false)
.skillMode(actrule.getSkillMode() != null ? actrule.getSkillMode() : "all")
```

#### 消费方 3：工具调用次数限制 — ExecutionLimitRail

**文件**：`engine/src/main/java/com/huawei/ascend/edp/rail/ExecutionLimitRail.java#L153-L162`

```java
public void beforeToolCall(AgentCallbackContext ctx) {
    if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
        return;
    }
    String toolName = inputs.getToolName();
    int limit = getToolLimit(toolName);  // 从 actrule.tool_limits 读取
    int count = toolCallCounts.get(sessionId).get(toolName);
    if (count >= limit) {
        ctx.requestForceFinish();  // 超限拦截
    }
}

// 未配置的工具默认上限为100次
private int getToolLimit(String toolName) {
    Integer limit = actrule.getToolLimits().get(toolName);
    return limit != null ? limit : 100;
}
```

→ 在 `EdpaAgentEnhancer.buildRails()` 注册：`rails.add(new ExecutionLimitRail(ctx.getActrule()))`

#### 消费方 4：Todo 数据层 — loadTodoDataLayer()

```java
private static EdpaTodolist loadTodoDataLayer(ActRuleConfig actrule) {
    if (actrule != null && actrule.getTodolistEntries() != null && !actrule.getTodolistEntries().isEmpty()) {
        edpaTodolist = new EdpaTodolist(actrule.getTodolistEntries(), actrule.getTodolistDynamicPaths());
    }
    return edpaTodolist;
}
```

→ 注入 `EdpaTodoRail`：`rails.add(new EdpaTodoRail(ctx.getDeepAgent(), ctx.getEdpaTodolist(), ctx.getActrule()))`

### 3.3 scriptconfig.yaml → 话术推送 + 合规把关

#### 加载方：loadSysScripts() — 三层合并

**文件**：`EdpaExtHandler.java#L547-L590`

```java
private static SysScriptsConfig loadSysScripts(InitResult result, Path yamlDir, Path skillsDir) {
    SysScriptsConfig sysScriptsConfig = new SysScriptsConfig();

    // 第一层：框架级 scriptconfig.yaml
    Path frameworkScriptsPath = yamlDir.resolve("governance").resolve("scriptconfig.yaml");
    if (Files.exists(frameworkScriptsPath)) {
        sysScriptsConfig.load(frameworkScriptsPath.toString());  // 文件系统优先（开发态）
    } else {
        sysScriptsConfig.loadFromClasspath();  // classpath 回退（集成态）
    }

    // 第二层：场景级 scriptconfig.yaml（覆盖框架级话术）
    if (result.getScenarioHomePath() != null) {
        Path scenarioScriptsPath = result.getScenarioHomePath().resolve("governance/scriptconfig.yaml");
        if (Files.exists(scenarioScriptsPath)) {
            sysScriptsConfig.load(scenarioScriptsPath.toString());
        }
    }

    // 第三层：Skill 级话术（从 skills/ 目录的 SKILL.yaml 收集）
    if (skillsDir != null && Files.exists(skillsDir)) {
        Map<String, String> skillScripts = SkillScriptsCollector.collectSkillScripts(skillsDir);
        sysScriptsConfig.mergeSkillScripts(skillScripts);
    }
    return sysScriptsConfig;
}
```

#### 消费方：ScriptsRail

**文件**：`engine/src/main/java/com/huawei/ascend/edp/rail/ScriptsRail.java`

```java
// 工具调用时推送话术
public void beforeToolCall(AgentCallbackContext ctx) {
    String tool = inputs.getToolName();
    // 从 scriptconfig.general_scripts 获取话术
    String text = scripts.getScriptOrDefault("SCRIPT_TOOL_START", "");
    // → "正在调用：{tool_name}"
}

// 合规把关：LLM 使用了配置外的话术 key → 替换为 out_of_scope
private void complianceGate(AgentCallbackContext ctx) {
    if (scripts != null && resolvedKey != null && !scripts.has(resolvedKey)) {
        ctx.getExtra().put(ScriptConstants.KEY_RESPONSE_TEMPLATE,
                scripts.getScriptOrDefault("SCRIPT_OUT_OF_SCOPE", ""));
        // → "当前请求暂不在可处理范围内。"
    }
}

// 注入取消规则和业务规则到 prompt
public void beforeAgentStart(Agent agent) {
    reActAgent.addPromptBuilderSection(CANCEL_RULES_SECTION, ScriptResolver.cancelRulesPrompt(scripts), 30);
    reActAgent.addPromptBuilderSection(BUSINESS_RULES_SECTION, ScriptResolver.businessRulesPrompt(scripts), 40);
}
```

→ 在 `EdpaAgentEnhancer.buildRails()` 注册：`rails.add(new ScriptsRail(ctx.getScripts(), ctx.getEdpConfig()))`

---

## 四、使用 Example：wealth-demo 场景

以理财场景为例，三个 YAML 的框架级 + 场景级配置如下：

### 4.1 planrule 合并效果

| 字段 | 框架级 | 场景级(wealth-demo) | 合并结果 |
|------|--------|---------------------|---------|
| role | "通用动态规划智能体" | 未配置 | 继承框架级 |
| description | "你的核心职责是..." | 未配置 | 继承框架级 |
| scenarioName | (空) | "理财购买" | 场景级新增 |
| scenarioDescription | (空) | "理财产品推荐、筛选、购买全流程" | 场景级新增 |
| scope.allowed | (空) | "理财产品推荐、筛选、购买、银行账户余额查询..." | 替代式覆盖 |
| scope.denied | (空) | "基金相关业务、股票相关业务、保险相关业务..." | 替代式覆盖 |
| baseProtocol | "你采用ReAct循环..." | 未配置 | 框架保护，不可覆盖 |
| additionalPrompt | (空) | "## 理财场景强制规则（不可违反）..." | 追加到 baseProtocol 之后 |
| skillRouting | (空) | 场景级路由规则 | 场景级新增 |

**最终系统提示词**：

```
# 通用动态规划智能体

你的核心职责是负责任务规划、执行和结果总结。无论当前场景是什么，
你的身份始终是通用动态规划智能体，场景只是你需要处理的具体业务上下文。
自我介绍时，必须说「你好，我是通用动态规划智能体」...

**当前场景**：理财购买
理财产品推荐、筛选、购买全流程

**当前支持的业务**：理财产品推荐、筛选、购买、银行账户余额查询、
银行账户间转账、资金筹划（理财卡与储蓄卡之间的资金调配）
**禁止的业务**：基金相关业务、股票相关业务、保险相关业务、贷款相关业务...

你采用「规划—执行—观察—反思」ReAct 循环处理用户请求。

## 任务规划协议（核心）
### 工作模式
- 当 scope 已配置业务范围时：业务范围内的请求**必须**走
  「todo_create → call_versatile → todo_modify → final_answer」流程
### 用 catalog_id 创建任务列表
...（框架级 baseProtocol，不可覆盖）

## 理财场景强制规则（不可违反）
### 强制规则
- 当用户提出业务范围内的请求时，你**必须**立即调用 `todo_create` 工具
  创建任务列表，**禁止直接用文本回答**
### ★ Skill 路由强制规则（最高优先级，不可违反）
- 当用户输入同时包含购买意图 + 产品提及 + 金额时，
  必须 `skill_tool` 加载并执行 `product_select_skill`
### ★★ todo_create 规划规则
- 必须一次性创建完整的4步任务列表：
  product_recommend → interact_finance_rec → product_select → fund_planning
...（场景级 additionalPrompt，追加到 baseProtocol 之后）
```

### 4.2 actrule 合并效果

| 字段 | 框架级 | 场景级(wealth-demo) | 合并结果 |
|------|--------|---------------------|---------|
| max_steps | 100 | 未配置 | 继承 100 |
| max_subtasks | 50 | 未配置 | 继承 50 |
| enable_task_loop | true | 未配置 | 继承 true |
| skill_mode | "all" | 未配置 | 继承 "all" |
| allowed_tools | [bash, skill_tool, call_versatile, call_mcp, ask_user, todo_create, todo_modify, todo_list, todo_get, cancel_task] | [同框架，无新增] | 并集（同框架） |
| tool_limits | {call_versatile:50, call_mcp:50, ask_user:50, execute_cmd:50} | 未配置 | 继承框架级 |
| todolist_entries | (空) | 4个 catalog_id | 场景级新增 |
| todolist_dynamic_paths | (空) | 跳转规则 | 场景级新增 |

**工具注册结果**：`EdpaBusinessTools.build()` 遍历 `allowed_tools`，跳过 bash/skill_tool/todo_*（框架原生），注册 4 个业务工具：

| 工具名 | 注册器 | 用途 |
|--------|--------|------|
| call_versatile | CallVersatileTool | 业务工作流调用（workflow_id + params） |
| call_mcp | CallMcpTool | 通用脚本调用（script_command + script_params） |
| ask_user | EnhancedAskUserTool | 关键信息缺失/敏感操作确认时中断 |
| cancel_task | CancelTaskTool | 取消整个会话 |

**Todo 数据层**：`EdpaTodolist` 加载 4 个 catalog_id：

```
product_recommend (无依赖) → interact_finance_rec (依赖 product_recommend)
  → product_select (依赖 interact_finance_rec) → fund_planning (依赖 product_select)
```

`EdpaTodoRail` 在运行时按 catalog_id 自动填充任务 content/description，LLM 只需提供 `catalog_id`。

**tool_limits 运行时拦截**：`ExecutionLimitRail` 在 `beforeToolCall` 中按 sessionId + toolName 计数，当 `call_versatile` 调用达 50 次时 `ctx.requestForceFinish()`。

### 4.3 scriptconfig 合并效果

| 字段 | 框架级 | 场景级(wealth-demo) | 合并结果 |
|------|--------|---------------------|---------|
| tool_start | "正在调用：{tool_name}" | "正在调用：{tool_name}" | 相同 |
| tool_end | "{tool_name} 执行完成" | "{tool_name} 执行完成" | 相同 |
| out_of_scope | "正在学习中，暂不支持该业务。" | "当前请求暂不在可处理范围内。" | 场景级覆盖 |
| todolist_start | "规划任务清单" | "已生成任务规划" | 场景级覆盖 |
| todolist_end | "todolist规划完成" | "任务规划完成" | 场景级覆盖 |
| think_chunk_mode | "fixed_script" | "fixed_script" | 相同 |
| query_intent_tool_text | (空) | 理财专用话术映射 | 场景级新增 |
| query_patterns | (空) | 理财关键词匹配 | 场景级新增 |
| interrupt_source | (未配置) | "script" | 场景级新增 |

**运行时效果**：

1. 当 LLM 调用 `call_versatile(query_intent="理财推荐")` 时：
   - `ScriptsRail.beforeToolCall()` 从 `query_intent_tool_text` 映射出 `tool_start = "正在获取理财产品列表..."`
   - 推送到前端

2. 当 LLM 调用 `ask_user(response_template_keys=["xxx"])` 时：
   - `ScriptsRail.complianceGate()` 检查 `xxx` 是否在 `scripts.has()` 中
   - 不在 → 替换为 `out_of_scope` 话术

3. 思维链展示（think_chunk）：
   - `think_chunk_mode=fixed_script` → 用预定义固定话术帧替代 LLM shard
   - planning 阶段：匹配 `query_patterns` 关键词，输出对应话术帧
   - executing 阶段：输出 "正在分析执行结果..."

---

## 五、完整数据流图

```
┌─────────────────────────────────────────────────────────────────────┐
│                    GovernanceConfigLoader                           │
│  框架级 governance/*.yaml  +  场景级 scenario/governance/*.yaml     │
│  → mergeScenarioConfig() → GovernanceConfig                        │
└──────────┬──────────────────┬──────────────────┬──────────────────┘
           │                  │                  │
     planrule             actrule            scriptconfig
           │                  │                  │
           ▼                  │                  │
  PlanrulePromptBuilder       │                  │
  → 系统提示词                  │                  │
           │                  ▼                  │
           │     ┌────────────┴────────────┐     │
           │     │                         │     │
           │     ▼                         ▼     │
           │  EdpaBusinessTools      ExecutionLimitRail
           │  → 工具注册列表           → tool_limits 拦截
           │     │                         │
           │     ▼                         ▼
           │  EdpaTodoRail              (Rail 注册)
           │  → todolist_entries
           │
           ▼
  DeepAgentConfig.builder()
    .systemPrompt(planrule 拼接结果)
    .maxIterations(actrule.max_steps)
    .enableTaskLoop(actrule.enable_task_loop)
    .skillMode(actrule.skill_mode)
    .build()
           │
           ▼
  EdpaAgentEnhancer.enhance()
    ├── registerBusinessTools(agent, edpConfig, actrule)
    │     └── EdpaBusinessTools.build() → actrule.allowed_tools 驱动
    └── buildRails(ctx)
          ├── EdpaTodoRail(deepAgent, edpaTodolist, actrule)     ← actrule.todolist_entries
          ├── ExecutionLimitRail(actrule)                        ← actrule.tool_limits
          ├── ScriptsRail(sysScriptsConfig, edpConfig)            ← scriptconfig 消费
          ├── EdpaEventRail(...)                                  ← scriptconfig 话术发射
          └── ...其他 Rails
```

---

## 六、与迁移到 Starter 的对照

源项目 edp-agent-java 的三个 YAML 各自的消费方式，迁移到 Starter 后：

| YAML | 源项目消费方式 | Starter 迁移后 |
|------|--------------|---------------|
| planrule | `PlanrulePromptBuilder` → 系统提示词 | `GovernancePromptBuilder.buildSystemPrompt()` → `effectivePrompt`（相同） |
| actrule.allowed_tools | `EdpaBusinessTools.build()` → 工具注册 | `ToolLimitRail` 白名单拦截（语义重新定位为运行时治理） |
| actrule.max_steps | `DeepAgentConfig.maxIterations` | `Math.min(properties.maxIterations, actrule.maxSteps)`（取 min 更安全） |
| actrule.tool_limits | `ExecutionLimitRail` 拦截 | `ToolLimitRail` 超限拦截（合并到同一个 Rail） |
| actrule.todolist_entries | `EdpaTodoRail` + `EdpaTodolist` | 未迁移（已有 `StarterTodoLifecycleRail` + `CatalogTodoRail` 覆盖） |
| scriptconfig | `SysScriptsConfig` → `ScriptsRail` | 已独立迁移（`ScriptAutoConfiguration` + `SysScriptsConfig`） |

---

## 七、关键代码文件索引

| 文件 | 职责 |
|------|------|
| [GovernanceConfigLoader.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/config/GovernanceConfigLoader.java) | 三层 YAML 加载 + 框架级/场景级合并 |
| [GovernanceConfig.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/config/GovernanceConfig.java) | 配置聚合模型（planrule + actrule + scriptconfig） |
| [PlanRuleConfig.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/config/PlanRuleConfig.java) | planrule.yaml 配置模型 |
| [ActRuleConfig.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/config/ActRuleConfig.java) | actrule.yaml 配置模型 |
| [EdpaExtHandler.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/handler/EdpaExtHandler.java) | 初始化入口（performInit） |
| [PlanrulePromptBuilder.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/stream/PlanrulePromptBuilder.java) | planrule → 系统提示词拼接 |
| [EdpaBusinessTools.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/tools/EdpaBusinessTools.java) | actrule.allowed_tools → 工具注册 |
| [EdpaToolRegistry.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/tools/EdpaToolRegistry.java) | 工具名称 → 构建器映射 |
| [ExecutionLimitRail.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/rail/ExecutionLimitRail.java) | actrule.tool_limits → 超限拦截 |
| [ScriptsRail.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/rail/ScriptsRail.java) | scriptconfig → 话术推送 + 合规把关 |
| [EdpaAgentEnhancer.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/enhancer/EdpaAgentEnhancer.java) | 工具注册 + Rail 注册 |
| [planrule.yaml](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/resources/governance/planrule.yaml) | 框架级 planrule |
| [actrule.yaml](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/resources/governance/actrule.yaml) | 框架级 actrule |
| [scriptconfig.yaml](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/resources/governance/scriptconfig.yaml) | 框架级 scriptconfig |

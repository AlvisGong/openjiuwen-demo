# AgentModeRail 详细解读

## 一、核心定位

AgentModeRail 是 DeepAgent Harness 层的**Agent 模式管理 Rail**，为 Agent 提供**双模式切换能力**（Normal 模式 / Plan 模式），通过工具可见性过滤、写操作限制和模式专用提示注入，实现"先规划后执行"的受控工作流。

| 属性 | 值 |
|------|---|
| 继承 | DeepAgentRail → AgentRail |
| 优先级 | 85 |
| 配置名 | `agent_mode` |
| 核心能力 | 模式切换 + 工具可见性过滤 + 写操作限制 + Plan 模式提示注入 + 子智能体委派 |

---

## 二、双模式设计

### 2.1 AgentMode 枚举

```java
public enum AgentMode {
    NORMAL,   // 正常执行模式 — 可使用所有工具，无写限制
    PLAN      // 规划模式 — 只能查看信息和编写计划文件，不能修改仓库代码
}
```

### 2.2 模式对比

| 维度 | Normal 模式 | Plan 模式 |
|------|------------|----------|
| **定位** | 执行模式，直接操作 | 规划模式，只看不改 |
| **可用工具** | 全部工具（隐藏 enter_plan_mode/exit_plan_mode） | 仅白名单工具（隐藏 todo/sessions 类工具） |
| **写操作** | 无限制 | 只能写计划文件（.plans/{sessionId}.md） |
| **提示注入** | 无额外提示 | 注入 Plan mode 指令 |
| **子智能体** | 不可用 task_tool | 可用 task_tool 委派子智能体执行 |
| **切换工具** | enter_plan_mode | exit_plan_mode |

---

## 三、类结构

```
AgentModeRail extends DeepAgentRail
  │
  ├── 静态常量
  │     PLAN_MODE_SECTION = "agent_mode_plan"     ← Prompt Section 名
  │     PLAN_MODE_SECTION_PRIORITY = 35           ← Section 优先级
  │     HIDDEN_IN_NORMAL = {enter_plan_mode, exit_plan_mode}
  │     HIDDEN_IN_PLAN = {todo_create, todo_list, todo_modify,
  │                       sessions_list, sessions_cancel, sessions_spawn}
  │     PLAN_WRITE_TOOLS = {write_file, edit_file}
  │     DEFAULT_PLAN_ALLOWED_TOOLS = {switch_mode, enter_plan_mode,
  │                       exit_plan_mode, ask_user, task_tool, read_file,
  │                       grep, list_files, glob, bash, write_file, edit_file}
  │
  ├── 实例字段
  │     owner: DeepAgent                          ← 所属 DeepAgent
  │     tools: List<Tool>                         ← 注册的 3 个工具
  │     allowedTools: Set<String>                 ← Plan 模式允许的工具白名单
  │     ownedTaskTool: Tool                       ← Plan 模式动态注册的 task_tool
  │
  └── 生命周期钩子
        init()              → 注册 3 个模式切换工具
        uninit()            → 注销工具 + 清理 task_tool
        beforeModelCall()   → 注入 Plan 模式提示 + 过滤工具可见性
        afterModelCall()    → 移除 Plan 模式提示
        beforeToolCall()    → 校验工具调用合法性（模式+写限制）
        afterToolCall()     → 模式切换后注册/注销 task_tool
```

---

## 四、三大注册工具

### 4.1 switch_mode

通用模式切换工具，支持 Normal ↔ Plan 任意方向切换。

```json
{
  "name": "switch_mode",
  "parameters": {
    "mode": "plan"    // "plan" 或 "normal"
  }
}
```

**返回值**：

```json
{
  "success": true,
  "data": {
    "previous_mode": "normal",
    "current_mode": "plan"
  }
}
```

### 4.2 enter_plan_mode

进入规划模式，同时创建计划文件。

```json
{
  "name": "enter_plan_mode",
  "parameters": {
    "conversation_id": "sess_123"    // 可选，用于确定计划文件路径
  }
}
```

**执行流程**：

```
1. DeepAgent.ensurePlanFile(conversationId)
   → 创建 .plans/{sessionId}.md 文件（如不存在）
   → 设置 planFilePath
2. SwitchModeTool.switchMode("plan")
   → DeepAgent.setMode(PLAN)
3. AgentModeRail.registerOwnedTaskTool()
   → 如果配置了子智能体，动态注册 task_tool
4. 返回 {previous_mode, current_mode, plan_file_path}
```

### 4.3 exit_plan_mode

退出规划模式，返回计划文件内容。

```json
{
  "name": "exit_plan_mode",
  "parameters": {}
}
```

**执行流程**：

```
1. SwitchModeTool.switchMode("normal")
   → DeepAgent.setMode(NORMAL)
2. AgentModeRail.unregisterOwnedTaskTool()
   → 注销 task_tool
3. 读取计划文件内容
4. 返回 {previous_mode, current_mode, plan_file_path, plan_content}
```

---

## 五、核心机制详解

### 5.1 工具可见性过滤

beforeModelCall() 中根据当前模式过滤发送给 LLM 的工具列表：

```java
private void filterVisibleTools(ModelCallInputs inputs, AgentMode mode) {
    Set<String> hidden = mode == AgentMode.PLAN ? HIDDEN_IN_PLAN : HIDDEN_IN_NORMAL;
    List<ToolInfo> visible = inputs.getTools().stream()
        .filter(tool -> !hidden.contains(tool.getName()))
        .toList();
    inputs.setTools(visible);
}
```

**Normal 模式隐藏**：`enter_plan_mode`、`exit_plan_mode`（避免 LLM 直接调用，应通过 switch_mode）

**Plan 模式隐藏**：`todo_create`、`todo_list`、`todo_modify`、`sessions_list`、`sessions_cancel`、`sessions_spawn`（规划阶段不应操作任务列表和会话）

### 5.2 工具调用校验

beforeToolCall() 中对每次工具调用进行三重校验：

```java
public ToolOutput validateToolCall(AgentMode mode, String toolName,
                                    Map<String, Object> toolArgs, Path planPath) {
    // 校验 1：enter_plan_mode 只能在 Normal 模式调用
    if ("enter_plan_mode".equals(toolName) && mode == AgentMode.PLAN)
        → error: "already in plan mode"

    // 校验 2：exit_plan_mode 只能在 Plan 模式调用
    if ("exit_plan_mode".equals(toolName) && mode != AgentMode.PLAN)
        → error: "not in plan mode"

    // 校验 3：Plan 模式下工具必须在白名单中
    if (mode == AgentMode.PLAN && !allowsToolInPlanMode(toolName))
        → error: "tool is not available in plan mode"

    // 校验 4：Plan 模式下写操作只能写计划文件
    if (mode == AgentMode.PLAN && !allowsWriteTarget(toolName, filePath, planPath))
        → error: "write/edit can only target the plan file"
}
```

**allowsWriteTarget()** 的写限制逻辑：

```java
public boolean allowsWriteTarget(String toolName, String filePath, Path planPath) {
    // 非写工具 → 放行
    if (!PLAN_WRITE_TOOLS.contains(toolName)) return true;
    // 写工具 → 目标路径必须等于计划文件路径
    return Path.of(filePath).toAbsolutePath().normalize()
        .equals(planPath.toAbsolutePath().normalize());
}
```

**校验失败处理**：设置 `_skip_tool=true`，将错误信息作为 ToolMessage 返回给 LLM，LLM 看到错误后可自行调整行为。

### 5.3 Plan 模式提示注入

Plan 模式激活时，beforeModelCall() 注入专用提示：

```java
public String planModeInstructions(Path planPath) {
    return "Plan mode is active.\n"
         + "Only inspect information needed to produce or refine the plan.\n"
         + "Do not modify repository files. Write or edit only the active plan file.\n"
         + "Active plan file: " + planPath.toAbsolutePath();
}
```

**注入方式**：双重注入——
1. `PromptBuilder.addSection("agent_mode_plan", content, 35)` — 添加到 System Prompt
2. `injectPlanModeMessage()` — 在 messages 头部插入 SystemMessage（防重复）

afterModelCall() 中移除 Prompt Section（每次 model call 前重新注入，保持最新）。

### 5.4 task_tool 动态注册

Plan 模式下，如果 DeepAgent 配置了子智能体（subagents），AgentModeRail 会动态注册 `task_tool`：

```java
private void registerOwnedTaskTool(DeepAgent agent) {
    // 仅当配置了子智能体且尚未注册时
    if (agent.getConfig().getSubagents() == null
        || agent.getConfig().getSubagents().isEmpty()) return;

    TaskTool taskTool = new TaskTool(agent);
    ownedTaskTool = new LocalFunction(card, inputs -> taskTool.delegate(
        subagentType, taskDescription, parentSessionId
    ));
    agent.registerHarnessTool(ownedTaskTool);
}
```

**task_tool 的作用**：在 Plan 模式下，Agent 可以将具体执行任务委派给子智能体，实现"规划者委派执行者"的分工模式。

**TaskTool.delegate()** 的执行流程：

```
1. 构建子会话 ID：{parentSessionId}_sub_{subagentType}_{uuid}
2. 创建子智能体：DeepAgent.createSubagent(subagentType, sessionId)
3. 调用子智能体执行任务：subagent.invoke({query, conversation_id})
4. 返回 {agent_id, sub_session_id, result}
```

退出 Plan 模式时，task_tool 被自动注销。

---

## 六、计划文件管理

### 6.1 文件路径

```
{workspace}/.plans/{conversationId}.md
```

### 6.2 ensurePlanFile()

```java
public Path ensurePlanFile(String conversationId) {
    Path planDir = workspace.root().resolve(".plans");
    Path planFile = planDir.resolve(sessionId + ".md").normalize();
    Files.createDirectories(planDir);
    if (!Files.exists(planFile)) {
        Files.writeString(planFile, "# Plan\n");  // 初始模板
    }
    planFilePath = planFile;
    return planFile;
}
```

### 6.3 计划文件示例

```markdown
# Plan

## 目标
为华为科技生成营销方案，并出一份融资方案

## 步骤
1. [ ] 搜索华为科技公司信息
2. [ ] 基于信息生成营销方案
3. [ ] 搜索对公贷款政策
4. [ ] 根据政策生成融资方案

## 约束
- 营销方案需简洁
- 融资方案需符合最新政策
```

---

## 七、配置方式

### 7.1 YAML 配置

```yaml
rails:
  agent_mode: {}    # 使用默认配置
```

如需自定义 Plan 模式允许的工具：

```yaml
rails:
  agent_mode:
    allowed_tools:
      - switch_mode
      - enter_plan_mode
      - exit_plan_mode
      - ask_user
      - task_tool
      - read_file
      - grep
      - list_files
      - glob
      - bash
      - write_file
      - edit_file
      - my_custom_tool    # 扩展自定义工具
```

### 7.2 代码配置

```java
// 默认配置
AgentModeRail rail = new AgentModeRail();
deepAgent.registerRail(rail);

// 自定义 Plan 模式允许的工具
Set<String> allowedTools = Set.of(
    "switch_mode", "enter_plan_mode", "exit_plan_mode",
    "ask_user", "task_tool", "read_file", "grep",
    "list_files", "glob", "bash", "write_file", "edit_file",
    "my_custom_tool"  // 允许自定义工具
);
AgentModeRail rail = new AgentModeRail(allowedTools);
deepAgent.registerRail(rail);
```

### 7.3 HarnessConfigBuilder 自动创建

```java
BUILTIN_RAILS.put("agent_mode", (root, spec) -> new AgentModeRail());
```

> 注意：当前 HarnessConfigBuilder 使用默认构造器，不支持从 YAML 配置 allowed_tools。如需自定义，需代码配置。

---

## 八、完整执行流程示例

### 场景：用户请求"帮我重构用户模块"

```
┌─ Normal 模式 ────────────────────────────────────────────────┐
│  beforeModelCall():                                            │
│    → 过滤隐藏工具：隐藏 enter_plan_mode, exit_plan_mode        │
│    → LLM 看到所有业务工具（todo_create, write_file 等）         │
│                                                                │
│  LLM 判断任务复杂，决定先规划：                                   │
│    → 调用 switch_mode(mode="plan")                             │
│    → DeepAgent.setMode(PLAN)                                   │
└──────────────────────────────────────────────────────────────┘
                          ↓
┌─ Plan 模式 [第1次 model call] ───────────────────────────────┐
│  beforeModelCall():                                            │
│    → 注入 Plan 模式提示：                                       │
│      "Plan mode is active.                                     │
│       Only inspect information needed to produce the plan.     │
│       Do not modify repository files.                          │
│       Write or edit only the active plan file.                 │
│       Active plan file: /workspace/.plans/sess_123.md"         │
│    → 过滤隐藏工具：隐藏 todo_create, todo_list, todo_modify,   │
│      sessions_list, sessions_cancel, sessions_spawn            │
│    → 注册 task_tool（如配置了子智能体）                           │
│                                                                │
│  LLM 只做规划相关操作：                                          │
│    → 调用 read_file("src/user/module.java")  ← 只读，允许      │
│    → 调用 grep("class User")                  ← 只读，允许      │
│    → 调用 write_file(                          ← 写计划文件，允许│
│        file_path="/workspace/.plans/sess_123.md",              │
│        content="# Plan\n## 步骤\n1. 重构 User 类...")          │
│                                                                │
│  如果 LLM 尝试写非计划文件：                                     │
│    → 调用 write_file(file_path="src/User.java", ...)           │
│    → beforeToolCall() 校验失败                                  │
│    → 返回 ToolMessage: "write/edit can only target the plan    │
│       file"                                                    │
│    → LLM 看到错误，自行调整行为                                  │
└──────────────────────────────────────────────────────────────┘
                          ↓
┌─ Plan 模式 [委派子智能体] ───────────────────────────────────┐
│  LLM 决定将具体执行委派给子智能体：                               │
│    → 调用 task_tool(                                           │
│        subagent_type="coder",                                  │
│        task_description="重构 User 类，添加 Builder 模式",      │
│        parent_session_id="sess_123"                            │
│      )                                                         │
│    → TaskTool.delegate() 创建子智能体并执行                      │
│    → 返回执行结果                                               │
└──────────────────────────────────────────────────────────────┘
                          ↓
┌─ 退出 Plan 模式 ────────────────────────────────────────────┐
│  LLM 规划完成：                                                 │
│    → 调用 exit_plan_mode()                                     │
│    → DeepAgent.setMode(NORMAL)                                 │
│    → 注销 task_tool                                            │
│    → 返回 {plan_content: "# Plan\n## 步骤\n1. ..."}            │
│                                                                │
│  回到 Normal 模式，LLM 可以正常执行所有操作                       │
└──────────────────────────────────────────────────────────────┘
```

---

## 九、使用场景

### 场景 1：复杂任务先规划后执行

```
用户："帮我重构整个用户模块，包括数据库、后端、前端"

Agent 行为：
  1. switch_mode("plan") → 进入规划模式
  2. read_file / grep / list_files → 了解现有代码结构
  3. write_file(plan_file) → 编写重构计划
  4. exit_plan_mode() → 退出规划，拿到计划内容
  5. 按计划逐步执行重构
```

### 场景 2：规划阶段防止误操作

```
Plan 模式下 Agent 尝试直接修改代码：
  → write_file("src/User.java", "新代码")
  → beforeToolCall() 校验失败
  → 返回 "write/edit can only target the plan file"
  → Agent 被迫只写计划文件，不会误改代码
```

### 场景 3：规划者委派执行者

```
配置了子智能体 coder、reviewer：

Plan 模式下：
  1. Agent 分析需求 → 编写计划
  2. task_tool(subagent_type="coder", task="实现 User Builder")
  3. task_tool(subagent_type="reviewer", task="审查代码质量")
  4. 汇总结果 → 更新计划

退出 Plan 模式后：
  → task_tool 被注销，回到正常执行
```

### 场景 4：多轮规划迭代

```
Agent 在 Plan 模式下：
  1. 读取代码 → 发现依赖关系复杂
  2. 更新计划文件 → 添加更多步骤
  3. 读取更多文件 → 发现新的约束
  4. 再次更新计划 → 调整优先级
  5. 满意后 exit_plan_mode → 开始执行
```

### 场景 5：安全审查场景

```
金融系统中，要求所有变更必须先提交计划审批：

  1. Agent 进入 Plan 模式
  2. 只能查看和编写计划，不能修改任何代码
  3. 计划文件 .plans/sess_123.md 可被人工审批
  4. 审批通过后 exit_plan_mode → Agent 开始执行
```

---

## 十、设计要点总结

| 设计要点 | 实现方式 |
|---------|---------|
| **双模式隔离** | AgentMode 枚举（NORMAL/PLAN），通过 DeepAgent.currentMode 全局状态管理 |
| **工具可见性** | beforeModelCall 中根据模式过滤 tools 列表，LLM 只能看到当前模式允许的工具 |
| **写操作限制** | Plan 模式下 write_file/edit_file 只能写计划文件，通过路径绝对值比对校验 |
| **校验拦截** | beforeToolCall 三重校验（模式+白名单+写限制），失败返回 ToolMessage 而非抛异常 |
| **提示引导** | Plan 模式注入 "Plan mode is active" 指令，引导 LLM 只做规划 |
| **双重注入** | PromptBuilder.addSection() + messages 头部 SystemMessage，防提示丢失 |
| **动态工具注册** | Plan 模式下注册 task_tool，退出时注销，实现"规划者委派执行者" |
| **计划文件管理** | ensurePlanFile() 自动创建 .plans/{sessionId}.md，exit 时返回内容 |
| **优雅降级** | 校验失败不抛异常，返回 ToolMessage 让 LLM 自行调整，保持执行流不中断 |
| **可扩展白名单** | allowedTools 构造参数支持自定义 Plan 模式允许的工具集 |

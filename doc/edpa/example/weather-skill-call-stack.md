# 天气查询 Skill 调用栈分析

> 基于 wealth-demo 场景中的 `weather_query_skill`，梳理从 HTTP 请求到 Python 脚本执行的完整调用链路。
> 数据来源：代码静态分析 + 运行时日志（2026-09-08 11:31:06 ~ 11:31:23）。

---

## 1. 整体架构概览

```
HTTP Client
  │
  ▼
A2A Protocol (JSON-RPC over HTTP)
  │
  ▼
A2AEnabledServeOrchestrator
  │
  ▼
EdpAgentFactory.create()  ──────────► Per-Task DeepAgent 实例
  │                                     │
  │                                     ├─ registerSkills()          ← Skill 注册
  │                                     └─ EdpaAgentEnhancer.enhance() ← 工具+Rail 注册
  │
  ▼
DeepAgent ReAct 循环 (最多 100 轮)
  │
  ├─ 迭代1: skill_tool      → 加载 Skill 描述到上下文
  ├─ 迭代2: todo_create      → 创建任务列表（查询天气）
  ├─ 迭代3: todo_create(重试) → 补全任务字段
  ├─ 迭代4: call_mcp         → McpInterruptRail 拦截 → 执行 Python 脚本
  ├─ 迭代5: todo_modify      → 标记任务完成
  └─ 迭代6: final_answer     → 格式化输出天气结果
```

---

## 2. 文件结构与配置

### 2.1 Skill 目录结构

```
scenarios/wealth-demo/skills/
├── weather_query.py                        # 入口脚本（skillsDir 直接查找）
├── weather_query.bat                        # Windows 批处理转发（可选）
└── weather_query_skill/
    ├── SKILL.md                             # Skill 元数据（必须含 YAML front matter）
    ├── SKILL.yaml                           # 话术模板定义（被 SkillScriptsCollector 收集）
    └── scripts/
        └── run_weather_query.py             # Mock 天气数据生成脚本
```

### 2.2 SKILL.md 格式要求

框架底层 `SkillManager.loadDescription()` **要求** SKILL.md 以 YAML front matter 开头，包含 `name` 和 `description` 字段，否则 Skill 被静默丢弃（`hasSkill=false`）。

```markdown
---
name: weather_query_skill
description: >
  根据用户提供的城市名称查询天气信息并返回结果。
  触发词：查询天气、天气怎么样、今天天气、北京天气、上海天气、明天天气。
  不要用于：理财产品推荐、账户查询、转账业务。
---

# 天气查询 Skill
...正文内容...
```

### 2.3 PlanRule 中的 Skill 路由

`scenarios/wealth-demo/governance/planrule.yaml` 末尾配置：

```yaml
  skill_routing:
    - trigger: "用户请求查询天气、询问天气情况"
      skill: "weather_query_skill"
      priority: 1
```

该配置通过 `PlanrulePromptBuilder.appendSkillRoutingSection()` 注入到系统提示词中，LLM 可见。

---

## 3. 启动阶段：Skill 注册

### 3.1 调用链

`performInit()` 不是由 `SpringApplication.run()` 直接调用，而是通过 **Spring Boot Bean 装配机制** 间接触发。完整路径：

```
EdpApplication.main()
  → SpringApplication.run(EdpApplication.class, args)
    → Spring Boot 启动：扫描包 + 自动装配
      │
      ├─ @SpringBootApplication(scanBasePackages={..., "com.openjiuwen.edp"})
      │   → 扫描到 @Configuration EdpEngineConfiguration（在 com.openjiuwen.edp 包下）
      │
      ├─ EdpEngineConfiguration.edpaExtHandler()    ← @Bean 方法
      │   │  Spring 容器创建 AgentHandler Bean 时执行此方法体
      │   │
      │   ├─ resolveDecoratedSandboxClient()         → 治理装饰沙箱（当前为 null）
      │   │
      │   └─ EdpaExtHandler.performInit()            ← 这里才进入 EdpaExtHandler
      │       → loadGovernanceAndValidate()           # 步骤3-5: 加载 planrule/actrule/scriptconfig
      │       → buildSystemPrompt()                   # 步骤7: PlanrulePromptBuilder 拼接系统提示词
      │       → buildDeepAgentConfig()                # 步骤8: 构造 DeepAgent 配置
      │       → HarnessFactory.createDeepAgent()      # 步骤9: 创建 DeepAgent 实例
      │       → registerSkills()                      # 步骤10: 注册 Skill 目录
      │       → loadSysScripts()                      # 步骤11: 收集话术模板
      │       → setupEnhanceContext()                 # 步骤12-13: 注册工具和 Rail
      │
      ├─ EdpEngineConfiguration.edpInitResult()  ← @Bean，依赖 edpaExtHandler
      │   → 返回 cachedInitResult（供 Per-Task 工厂使用）
      │
      └─ EdpAgentFactoryAutoConfiguration（自动装配）
          → @ConditionalOnBean(EdpaExtHandler.InitResult.class)
          → 创建 EdpAgentFactory Bean（Per-Task 模式）
```

**关键机制**：`performInit()` 是在 Spring 容器创建 `edpaExtHandler` Bean 时，作为 `@Bean` 方法体的同步代码执行的。不是事件回调，不是 `ApplicationReadyEvent`，而是 Bean 实例化阶段的直接调用。

**代码位置**：`EdpEngineConfiguration.java#L79`

```java
@Bean
AgentHandler edpaExtHandler(EdpaSpringBootConfig config, ...) {
    cachedInitResult = EdpaExtHandler.performInit(config, ...);  // ← Bean 创建时同步执行
    EdpaExtHandler handler = new EdpaExtHandler(cachedInitResult.getAgentInstance());
    handler.applyInitResult(cachedInitResult);
    return handler;
}
```

### 3.2 registerSkills 详解

**代码位置**: `EdpaExtHandler.java#L1241`

```java
private static void registerSkills(DeepAgent deepAgent, Path skillsDir, String agentName) {
    ensureSkillSysOperationId(deepAgent, agentName);
    deepAgent.getAgent().registerSkill(skillsDir.toString());  // 框架底层注册
    boolean hasSkill = deepAgent.getAgent().getSkillUtil() != null
            && deepAgent.getAgent().getSkillUtil().hasSkill();
    // 日志输出: hasSkill=true, skillCount=1, skillNames=[weather_query_skill]
}
```

**关键逻辑**:
- `registerSkill(skillsDir.toString())` — 框架 `SkillManager` 扫描 skillsDir 下所有子目录
- 每个子目录必须包含 `SKILL.md` 文件，且文件以 `---` YAML front matter 开头
- front matter 中必须包含 `description:` 字段，否则 `loadDescription()` 返回 null，Skill 被静默丢弃

### 3.3 话术收集

**代码位置**: `SkillScriptsCollector.java`

独立于 Skill 注册，扫描所有 SKILL.yaml 中的 `scripts` 字段，合并到 `SysScriptsConfig`：

```
loadSysScripts()
  → SkillScriptsCollector.collectSkillScripts(skillsDir)
    → 遍历 skillsDir 下每个子目录
    → 读取 SKILL.yaml（或 v1/SKILL.yaml）
    → 提取 scripts map 中的 key-value
  → sysScriptsConfig.mergeSkillScripts(skillScripts)
```

本例收集到 3 条话术：`weather_query_success`、`weather_query_empty`、`weather_query_error`。

### 3.4 启动日志（关键节点）

```
11:30:43.688  Skill load completed: hasSkill=true, skillCount=1, skillNames=[weather_query_skill]
11:30:43.793  Registered callback: EDPAgent_before_tool_call (各 Rail 注册回调)
11:30:43.857  add resource succeed, id=EDPAgent.skill_tool, type=tool
11:30:43.857  add resource succeed, id=EDPAgent.todo_create, type=tool
```

---

## 4. 请求阶段：从 HTTP 接入到 ReAct 循环启动

### 4.1 端到端调用链（完整版）

```
用户 HTTP 请求
  │  POST http://localhost:8190/a2a
  │  Content-Type: application/json
  │  Body: {"jsonrpc":"2.0","id":"weather-001","method":"SendMessage",
  │         "params":{"message":{"role":"ROLE_USER","messageId":"msg-001",
  │         "parts":[{"kind":"text","text":"帮我查询一下北京的天气"}]}}}
  │
  ▼
Tomcat (NIO, port 8190)
  │  → DispatcherServlet (Spring MVC)
  │    → A2A Controller (agent-runtime 提供的 @PostMapping("/a2a"))
  │
  ▼
A2AProtocolAdapter.toServeRequest()                         [agent-runtime-java]
  │  → 解析 JSON-RPC 请求体
  │  → 提取 method=SendMessage, message.parts[0].text="帮我查询一下北京的天气"
  │  → 构造 A2AMessageContext（taskId, contextId, conversationId）
  │  → 日志: "A2A toServeRequest taskId=d60eab52... textLen=11"
  │
  ▼
A2AAgentExecutor.execute()                                  [agent-runtime-java]
  │  → 并发准入控制：检查当前活跃任务数 vs maxConcurrentTasks(30)
  │  │  → 日志: "[CONCURRENCY] task_admitted currentActive=1 maxConcurrent=30"
  │  │  → 超出限制返回 HTTP 503
  │  │
  │  → 获取 AgentHandler Bean（即 EdpaExtHandler，Spring 容器中单例）
  │  → 判断 DFX-002 模式：AgentFactory Bean 是否存在
  │
  ▼
A2AEnabledServeOrchestrator.query()                         [agent-runtime-java]
  │  → 日志: "Orchestrator query START conversationId=5e375f2e..."
  │  │
  │  ├─ DFX-002 模式（Per-Task Agent）────────────────────────┐
  │  │  → EdpAgentFactory.create()                           │
  │  │    → creationLock.lock()                              │
  │  │    → HarnessFactory.createDeepAgent(card, config, null)│
  │  │    → agent.ensureInitialized()                        │
  │  │    → EdpaExtHandler.initPerTaskAgent(agent, initResult)│
  │  │      → registerSkills(agent, skillsDir, agentName)   │
  │  │        → deepAgent.getAgent().registerSkill(skillsDir)│
  │  │        → 日志: "Skill load completed: hasSkill=true, │
  │  │          skillCount=1, skillNames=[weather_query_skill]"│
  │  │      → EdpaAgentEnhancer.enhance(agent, ctx)         │
  │  │        → registerBusinessTools()                      │
  │  │          → 注册: call_versatile, call_subagent,      │
  │  │            call_mcp, ask_user, cancel_task            │
  │  │        → registerBusinessRails()                      │
  │  │          → 注册: CancelRail(100), ExecutionLimitRail(70),│
  │  │            McpInterruptRail(85), VersatileDelegateRail(85),│
  │  │            SubagentDelegateRail(85), AskUserTemplateRail(85),│
  │  │            LogRail(10), EdpaEventRail(80), ScriptsRail(50)│
  │  │    → creationLock.unlock()                            │
  │  │    → 返回 DeepAgent 实例                              │
  │  │  └────────────────────────────────────────────────────┘
  │  │
  │  → 回复用 EdpaExtHandler.query() 或 streamQuery()
  │    → validateInputSecurity(request)                      [EdpaExtHandler]
  │    │  → 检查请求体大小 ≤ 2MB, parts ≤ 100, text ≤ 100000
  │    → setOriginalBodyFromRequest(request)                 [EdpaExtHandler]
  │    │  → 提取 metadata["body"] 存入 ThreadLocal（供 buildSkillInput 使用）
  │    → super.query() / super.streamQuery()                 [JiuwenCoreAgentExtHandler]
  │      → 创建/恢复 session（checkpointer，in_memory 模式）
  │      │  → 日志: "Create new agent checkpointer store, sessionId=5e375f2e..."
  │      │  → 日志: "Begin to restore agent session"
  │      │  → 日志: "Succeed to restore agent session"
  │      │
  │      → 提交 deep_agent_task 到 TaskScheduler
  │        → 日志: "Task deep_agent_task_5e375f2e..._1 started"
  │        → 日志: "Executing task deep_agent_task_5e375f2e..._1"
  │      │
  │      ▼
  │    ┌─────────────────────────────────────────────────┐
  │    │  DeepAgent ReAct 循环（详见第5节）               │
  │    │  → EdpaEventRail.beforeInvoke()                 │
  │    │    → 读取 Redis Todo 存储（当前为空）              │
  │    │    → emit conversation_start                     │
  │    │    → emit interrupt_start("您的请求已收到")       │
  │    │  → LogRail.beforeModelCall()                    │
  │    │    → 构造 LLM 请求（system_prompt + user_message）│
  │    │  → LLM 推理（glm-5.2 via DashScope API）         │
  │    │  → EdpaEventRail.afterModelCall()               │
  │    │    → 解析 finishReason, toolCalls               │
  │    │  → 执行工具调用（经 Rail 拦截链）                 │
  │    │  → 循环直到 finishReason=stop                   │
  │    └─────────────────────────────────────────────────┘
  │
  ▼
A2AAgentExecutor
  │  → save checkpoint（in_memory）
  │  → EdpAgentFactory.destroy(agent)  → 销毁 Per-Task DeepAgent
  │  → 日志: "EdpAgentFactory destroying DeepAgent, agentId=EDPAgent"
  │
  ▼
HTTP 200 返回
  → 构造 JSON-RPC 响应体（task.id, status.state=TASK_STATE_COMPLETED, artifacts）
```

### 4.2 关键组件说明

| 组件 | 来源 | 职责 |
|------|------|------|
| DispatcherServlet | Spring MVC | HTTP 请求路由到 `/a2a` 端点 |
| A2A Controller | agent-runtime-java | 接收 JSON-RPC 请求，委托给 A2AProtocolAdapter |
| A2AProtocolAdapter | agent-runtime-java | 解析 JSON-RPC 协议，提取消息内容 |
| A2AAgentExecutor | agent-runtime-java | 并发准入控制（max=30），创建/恢复会话 |
| A2AEnabledServeOrchestrator | agent-runtime-java | 编排 Agent 执行流程，创建 Per-Task Agent |
| EdpAgentFactory | edp-agent-java | DFX-002 模式：每次请求创建新 DeepAgent 实例 |
| EdpaExtHandler | edp-agent-java | AgentHandler SPI 实现，请求入口方法 `query()` / `streamQuery()` |
| EdpaAgentEnhancer | edp-agent-java | 给 DeepAgent 注册业务工具（5个）和 Rail（8个） |

### 4.3 EdpAgentFactory.create() 详解

**代码位置**: [EdpAgentFactory.java](file:///d:/work/agent/EDPA/agent-solution-main/common/agents/edp-agent-java/engine/src/main/java/com/openjiuwen/edp/handler/EdpAgentFactory.java#L70)

```java
protected DeepAgent createAgent() {
    DeepAgent agent = HarnessFactory.createDeepAgent(agentCard, deepAgentConfig, null);
    agent.ensureInitialized();
    EdpaExtHandler.initPerTaskAgent(agent, initResult);  // 复用启动时的 InitResult
    return agent;
}
```

**线程安全**: V1 通过 `ReentrantLock` 串行化 Agent 创建，因为 `HarnessFactory.createDeepAgent()` 写入全局 `ResourceMgr` 的 `HashMap`（非线程安全）。Agent 执行（LLM 调用、工具执行）在锁外并行。

### 4.4 EdpaExtHandler 请求入口方法

**代码位置**: [EdpaExtHandler.java](file:///d:/work/agent/EDPA/agent-solution-main/common/agents/edp-agent-java/engine/src/main/java/com/openjiuwen/edp/handler/EdpaExtHandler.java#L803)

```java
@Override
public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
    validateInputSecurity(request);        // 请求体安全校验
    setOriginalBodyFromRequest(request);  // 提取原始 body 到 ThreadLocal
    super.streamQuery(request, observer); // 委托父类执行 ReAct 循环
}
```

### 4.5 请求阶段日志（端到端）

```
# ── HTTP 接入 + 任务创建 ──────────────────────────────────────
11:31:06.067  A2A NEW task taskId=d60eab52... contextId=5e375f2e...
11:31:06.069  A2A toServeRequest taskId=d60eab52... textLen=11
11:31:06.071  [CONCURRENCY] task_admitted currentActive=1 maxConcurrent=30
11:31:06.072  A2A execute START stream=false

# ── Per-Task Agent 创建 ───────────────────────────────────────
11:31:06.074  Orchestrator query START conversationId=5e375f2e...
11:31:06.076  EdpAgentFactory creating per-Task DeepAgent, agentId=EDPAgent
11:31:06.085  Skill load completed: hasSkill=true, skillCount=1, skillNames=[weather_query_skill]
11:31:06.085  EdpaAgentEnhancer.enhance() start, sandbox=disabled

# ── 业务工具注册 ─────────────────────────────────────────────
11:31:06.087  Registered business tool: call_versatile
11:31:06.087  Registered business tool: call_subagent
11:31:06.087  Registered business tool: call_mcp
11:31:06.088  Registered business tool: ask_user
11:31:06.088  Registered business tool: cancel_task

# ── 业务 Rail 注册 ───────────────────────────────────────────
11:31:06.088  Registered business rail: CancelRail (priority=100)
11:31:06.088  Registered business rail: ExecutionLimitRail (priority=70)
11:31:06.088  Registered business rail: McpInterruptRail (priority=85)
11:31:06.090  Registered business rail: VersatileDelegateRail (priority=85)
11:31:06.090  Registered business rail: SubagentDelegateRail (priority=85)
11:31:06.090  Registered business rail: AskUserTemplateRail (priority=85)
11:31:06.090  Registered business rail: LogRail (priority=10)
11:31:06.091  Registered business rail: EdpaEventRail (priority=80)
11:31:06.091  Registered business rail: ScriptsRail (priority=50)
11:31:06.091  EdpaAgentEnhancer.enhance() completed

# ── 会话恢复 ─────────────────────────────────────────────────
11:31:06.097  otel trajectory disabled for request: missing conversationId
11:31:06.132  Create new agent checkpointer store, sessionId=5e375f2e...
11:31:06.132  Begin to restore agent session, sessionId=5e375f2e...
11:31:06.133  Succeed to restore agent session, sessionId=5e375f2e...

# ── ReAct 循环启动 ───────────────────────────────────────────
11:31:06.200  Task deep_agent_task_5e375f2e..._1 started
11:31:06.200  Executing task deep_agent_task_5e375f2e..._1 (type: deep_agent_task)
11:31:07.362  [EDPA-DIAG] LOAD_CURRENT_TODOS source=STORAGE items=0
11:31:07.362  [EDPA-DIAG] beforeInvoke -> emit conversation_start
11:31:07.394  ReAct iteration 1/100   ← 进入第5节
```

---

## 5. 执行阶段：ReAct 循环（6 轮迭代）

### 5.1 迭代时序总览

| 迭代 | 时间 | LLM 决策 | 工具 | 说明 |
|------|------|----------|------|------|
| 1 | 11:31:07.394 | tool_calls | `skill_tool` | 加载 weather_query_skill 的 SKILL.md 内容到上下文 |
| 2 | 11:31:11.093 | tool_calls | `todo_create` | 创建"查询北京天气信息"任务 |
| 3 | 11:31:13.330 | tool_calls | `todo_create`(重试) | 补全任务字段 |
| 4 | 11:31:15.197 | tool_calls | `call_mcp` | 调用 Python 脚本查询天气 |
| 5 | 11:31:21.049 | tool_calls | `todo_modify` | 标记任务 COMPLETED |
| 6 | 11:31:21.207 | stop | `final_answer` | 格式化输出天气结果 |

### 5.2 迭代 1：skill_tool（Skill 加载）

```
11:31:07.394  ReAct iteration 1/100
11:31:10.937  MODEL_RESPONSE finishReason=tool_calls, toolCalls=1, toolNames=[skill_tool]
11:31:11.093  tool_call: skill_tool
11:31:11.093  Executing tool: skill_tool
11:31:11.118  (skill_tool 执行完成，返回 SKILL.md 内容给 LLM)
```

LLM 第一轮收到用户消息"帮我查询一下北京的天气"，根据系统提示词中的 Skill 路由配置，决定先调用 `skill_tool` 加载 `weather_query_skill` 的详细内容（SKILL.md 正文）。

### 5.3 迭代 2-3：todo_create（任务创建）

```
11:31:11.093  ReAct iteration 2/100
11:31:13.187  MODEL_RESPONSE finishReason=tool_calls, toolCalls=1, toolNames=[todo_create]
11:31:13.298  tool_call: todo_create
11:31:13.298  Executing tool: todo_create
...
11:31:13.330  ReAct iteration 3/100
11:31:15.065  tool_call: todo_create  (重试，补全字段)
```

LLM 读取 SKILL.md 后，按 planrule 中「规划—执行—观察—反思」协议，调用 `todo_create` 创建任务列表。第 2 轮创建未完全成功，第 3 轮重试补全任务字段。

### 5.4 迭代 4：call_mcp（脚本执行）

这是**核心环节**，`McpInterruptRail` 拦截 `call_mcp` 工具调用并执行 Python 脚本。

```
11:31:15.197  ReAct iteration 4/100
11:31:16.649  MODEL_RESPONSE finishReason=tool_calls, toolCalls=1, toolNames=[call_mcp]
              todos=count=1, [查询北京天气信息=IN_PROGRESS]
11:31:16.757  tool_call: call_mcp
11:31:16.757  Executing tool: call_mcp
```

#### 5.4.1 McpInterruptRail.beforeToolCall() 拦截

**代码位置**: `McpInterruptRail.java#L196`

```
beforeToolCall(ctx)
  → 判断 toolName == "call_mcp" ✓
  → ctx.getExtra().put(KEY_SKIP_TOOL, true)        # 跳过框架默认工具执行
  → executeMcpScript(inputs, ctx)                  # MCP 脚本执行
    → 从 inputs 提取 script_command 和 script_params
    → buildCommand(scriptCommand)                   # 分词 + 路径解析
    → resolveWorkDir(command)                       # 确定 workDir
    → executeViaProcessBuilder(command, workDir, ...)  # ProcessBuilder 执行
  → inputs.setToolResult(result)                    # 设置工具返回值
```

#### 5.4.2 脚本路径解析

**代码位置**: `McpInterruptRail.java#L568` `resolveScriptPath()`

LLM 发送 `script_command="python weather_query_skill/scripts/run_weather_query.py"`，`buildCommand()` 分词后：

1. `tokens[0]` = `"python"` → `isPythonCommand()` 返回 true
2. `tokens[1]` = `"weather_query_skill/scripts/run_weather_query.py"` → 调用 `resolveScriptPath()`
3. `resolveScriptPath()` 在 `skillsDir` 下查找：
   - `skillsDir.resolve("weather_query_skill/scripts/run_weather_query.py")` → **存在** ✓
   - 返回绝对路径：`.../skills/weather_query_skill/scripts/run_weather_query.py`
4. `resolveWorkDir()` 取脚本父目录：`.../skills/weather_query_skill/scripts`

#### 5.4.3 ProcessBuilder 执行

**代码位置**: `McpInterruptRail.java#L375` `executeViaProcessBuilder()`

```java
ProcessBuilder builder = configureProcessBuilder(command, workDir, argumentsJson, scriptParams);
// command = ["python", ".../run_weather_query.py"]
// workDir = ".../skills/weather_query_skill/scripts"
// 环境变量: SKILL_INPUT=<JSON>, PYTHONIOENCODING=utf-8, PYTHONUTF8=1
return executeProcess(builder);
```

`executeProcess()` 执行：
1. `builder.start()` 启动进程
2. 异步读取 stdout/stderr
3. `process.waitFor(60s)` 等待完成
4. `parseScriptOutput(stdout)` 解析最后一行 JSON

#### 5.4.4 脚本执行日志

```
11:31:16.985  [MCPInterruptRail] local script executed: exitCode=0, stdoutLen=134, stderrLen=
```

Python 脚本 stdout 输出（Mock 数据）：
```json
{"success": true, "city": "北京", "weather": "小雨", "temperature": "34°C", "wind": "西北风 3-4级", "humidity": "48%", "tips": "出门请带伞；注意防暑降温"}
```

#### 5.4.5 McpInterruptRail.afterToolCall() 后置处理

**代码位置**: `McpInterruptRail.java#L206`

```
afterToolCall(ctx)
  → 判断 toolName == "call_mcp" ✓
  → normalizeResult(inputs)                        # 提取工具返回值
  → injectEmptyResultTip(ctx, inputs, result)     # 空结果时注入话术提示
  → persistMcpResult(ctx, result)                  # 写入 ToolDataChannel
    → toolDataChannel.store(key, "mcp_products_data", data)
  → updateToolMessage(inputs, result)              # 更新 ToolMessage 内容
```

日志：
```
11:31:16.985  McpInterruptRail: call_mcp completed, result validated
11:31:16.016  stored call_mcp result to ToolDataChannel key=..., resultKey=mcp_products_data, fields=[success, city, weather, temperature, wind, humidity, tips]
```

### 5.5 迭代 5：todo_modify（任务完成）

```
11:31:17.024  ReAct iteration 5/100
11:31:21.048  MODEL_RESPONSE finishReason=tool_calls, toolCalls=1, toolNames=[todo_modify]
              todos=count=1, [查询北京天气信息=IN_PROGRESS]
11:31:21.049  tool_call: todo_modify
11:31:21.049  Executing tool: todo_modify
11:31:21.207  (todo_modify 完成，任务标记为 COMPLETED)
```

LLM 观察到 `call_mcp` 返回的天气数据后，调用 `todo_modify` 将"查询北京天气信息"任务标记为 COMPLETED。

### 5.6 迭代 6：final_answer（最终输出）

```
11:31:21.207  ReAct iteration 6/100
11:31:23.755  final_answer_chunk: 您好，以下是北京当前的天气查询结果：
11:31:23.827  conversation_end
11:31:23.828  Task completed, state=TASK_STATE_COMPLETED
```

LLM 综合天气数据，格式化输出最终回答。

---

## 6. 完整响应数据

```json
{
  "jsonrpc": "2.0",
  "id": "weather-query-001",
  "result": {
    "task": {
      "id": "d60eab52-4f45-40e6-92db-791a318a907b",
      "contextId": "5e375f2e-772b-44a7-bdf3-53e74e02b17e",
      "status": {
        "state": "TASK_STATE_COMPLETED",
        "timestamp": "2026-09-08T03:31:23.8278699Z"
      },
      "artifacts": [
        {
          "artifactId": "de39b02d-3bf7-452f-b7e9-b0f1076d3700",
          "parts": [
            {
              "text": "您好，以下是北京当前的天气查询结果：\n\n🌤 **北京天气**\n\n| 项目 | 详情 |\n|------|------|\n| 天气 | 小雨 🌧 |\n| 温度 | 34°C |\n| 风力 | 西北风 3-4级 |\n| 湿度 | 48% |\n\n💡 **温馨提示**：出门请带伞；注意防暑降温。"
            }
          ],
          "metadata": { "_agentcore_terminal": true }
        }
      ],
      "history": []
    }
  }
}
```

---

## 7. 关键组件索引

| 组件 | 文件 | 职责 |
|------|------|------|
| EdpApplication | [EdpApplication.java](file:///d:/work/agent/EDPA/agent-solution-main/common/agents/edp-agent-java/engine/src/main/java/com/openjiuwen/edp/EdpApplication.java) | Spring Boot 启动入口 |
| EdpaExtHandler | [EdpaExtHandler.java](file:///d:/work/agent/EDPA/agent-solution-main/common/agents/edp-agent-java/engine/src/main/java/com/openjiuwen/edp/handler/EdpaExtHandler.java) | 核心初始化（Governance 加载、Skill 注册、工具/Rail 注册） |
| EdpAgentFactory | [EdpAgentFactory.java](file:///d:/work/agent/EDPA/agent-solution-main/common/agents/edp-agent-java/engine/src/main/java/com/openjiuwen/edp/handler/EdpAgentFactory.java) | Per-Task Agent 工厂（每次请求创建新 DeepAgent） |
| EdpaAgentEnhancer | [EdpaAgentEnhancer.java](file:///d:/work/agent/EDPA/agent-solution-main/common/agents/edp-agent-java/engine/src/main/java/com/openjiuwen/edp/enhancer/EdpaAgentEnhancer.java) | 业务工具和 Rail 注册到 DeepAgent |
| McpInterruptRail | [McpInterruptRail.java](file:///d:/work/agent/EDPA/agent-solution-main/common/agents/edp-agent-java/engine/src/main/java/com/openjiuwen/edp/rail/McpInterruptRail.java) | 拦截 call_mcp，执行 Python 脚本，写入 ToolDataChannel |
| PlanrulePromptBuilder | [PlanrulePromptBuilder.java](file:///d:/work/agent/EDPA/agent-solution-main/common/agents/edp-agent-java/engine/src/main/java/com/openjiuwen/edp/stream/PlanrulePromptBuilder.java) | 拼接系统提示词（含 Skill 路由信息） |
| SkillScriptsCollector | [SkillScriptsCollector.java](file:///d:/work/agent/EDPA/agent-solution-main/common/agents/edp-agent-java/engine/src/main/java/com/openjiuwen/edp/stream/SkillScriptsCollector.java) | 收集 SKILL.yaml 中的话术模板 |
| EdpConfigValidator | [EdpConfigValidator.java](file:///d:/work/agent/EDPA/agent-solution-main/common/agents/edp-agent-java/engine/src/main/java/com/openjiuwen/edp/config/EdpConfigValidator.java) | 启动时校验 scenarioHome/skills 目录 |

---

## 8. 常见问题与排查

### 8.1 hasSkill=false（Skill 未加载）

**原因**: SKILL.md 缺少 YAML front matter（`---` 包裹的 `name` 和 `description` 字段）

**框架约束**: `SkillManager.loadDescription()` 要求 SKILL.md 必须以 `---` 开头并包含 `description:` 字段，否则返回 null，Skill 被静默丢弃。

**修复**: 确保 SKILL.md 格式如下：
```markdown
---
name: skill_name
description: 技能描述...
---
# 正文内容...
```

### 8.2 call_mcp 脚本执行失败（CreateProcess error=2）

**原因**: LLM 发送的 `script_command` 是简单名称（如 `weather_query`），`resolveScriptPath()` 在 skillsDir 下找不到对应文件。

**排查**: 查看日志中 `script_command=` 的值和 `script path falling back to default` 警告。

**修复**: 在 SKILL.md 的 description 和工具调用示例中明确指定完整的 python 命令路径，如 `python weather_query_skill/scripts/run_weather_query.py`。

### 8.3 脚本执行超时

**默认超时**: 60 秒（`SCRIPT_TIMEOUT = Duration.ofSeconds(60)`）

**排查**: 日志中出现 `MCP script timeout after 60s`。

**修复**: 优化脚本执行效率，或检查脚本是否卡在网络请求上。

---

## 9. 请求示例

```bash
curl -X POST http://localhost:8190/a2a \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "weather-query-001",
    "method": "SendMessage",
    "params": {
      "message": {
        "role": "ROLE_USER",
        "messageId": "msg-weather-001",
        "parts": [{"kind": "text", "text": "帮我查询一下北京的天气"}]
      }
    }
  }'
```

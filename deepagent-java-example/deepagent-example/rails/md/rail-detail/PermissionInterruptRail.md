# PermissionInterruptRail 详细解读

## 一、核心定位

PermissionInterruptRail 是 DeepAgent Harness 层的**权限中断 Rail**，在工具执行前根据权限策略决定**放行（Allow）、拒绝（Deny）或中断等待人工审批（Ask）**。它是实现 Agent **人机协同审批**的核心机制，让敏感工具调用在执行前暂停，等待用户确认后再恢复执行。

| 属性 | 值 |
|------|---|
| 继承 | BaseInterruptRail → AgentRail |
| 优先级 | 90（BaseInterruptRail 构造中设定） |
| 配置名 | `permissions` |
| 核心能力 | 三级权限判定（Allow/Ask/Deny） + 工具中断暂停 + 恢复执行 |

---

## 二、整体架构

```
┌─────────────────────────────────────────────────────────┐
│                   PermissionInterruptRail                │
│                   extends BaseInterruptRail              │
│                                                          │
│  ┌─────────────────┐    ┌──────────────────┐            │
│  │ PermissionEngine │    │ ToolPermissionHost│            │
│  │  (权限判定引擎)  │    │  (权限宿主环境)   │            │
│  └────────┬────────┘    └──────────────────┘            │
│           │                                              │
│           │ checkPermission(toolName, toolArgs)           │
│           ↓                                              │
│  ┌─────────────────────┐                                 │
│  │ PermissionCheckResult│                                 │
│  │  permission: ALLOW   │ → approve() → 正常执行           │
│  │  permission: DENY    │ → reject()  → 拒绝并返回错误     │
│  │  permission: ASK     │ → interrupt() → 抛出异常暂停     │
│  └─────────────────────┘                                 │
└─────────────────────────────────────────────────────────┘
```

---

## 三、核心类详解

### 3.1 PermissionInterruptRail

```java
public class PermissionInterruptRail extends BaseInterruptRail {
    private final PermissionEngine engine;      // 权限判定引擎
    private final ToolPermissionHost host;      // 权限宿主环境

    public PermissionInterruptRail(PermissionEngine engine, ToolPermissionHost host) {
        super(null);
        this.engine = engine;
        this.host = host;
        // 从配置中提取所有工具名，注册为需要拦截的工具
        Map<String, Object> tools = (Map<String, Object>) engine.getConfig()
                .getOrDefault("tools", Map.of());
        addTools(tools.keySet());
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx,
                                                  ToolCall toolCall,
                                                  Object userInput) {
        String toolName = toolCall != null ? toolCall.getName() : "";
        // 提取工具参数
        Map<String, Object> toolArgs = extractToolArgs(ctx);
        
        // 调用权限引擎判定
        PermissionCheckResult result = engine.checkPermission(toolName, toolArgs);

        if (result.getPermission() == PermissionLevel.ALLOW) {
            return approve();                    // 放行
        }
        if (result.getPermission() == PermissionLevel.DENY) {
            return reject("Permission denied for tool: " + toolName);  // 拒绝
        }
        // ASK → 中断等待用户审批
        return interrupt(InterruptRequest.builder()
                .message("Permission approval required for tool: " + toolName)
                .context(Map.of(
                        "tool_name", toolName,
                        "matched_rule", result.getMatchedRule()
                ))
                .build());
    }
}
```

### 3.2 BaseInterruptRail — 中断基类

BaseInterruptRail 提供了中断 Rail 的通用框架：

```java
public abstract class BaseInterruptRail extends AgentRail {
    private final Set<String> toolNames = new LinkedHashSet<>();

    protected BaseInterruptRail(Iterable<String> toolNames) {
        if (toolNames != null) {
            for (String toolName : toolNames) {
                this.toolNames.add(toolName);
            }
        }
        setPriority(90);  // 高优先级，确保在其他 Rail 之前拦截
    }

    // 三种决策方法
    public ApproveResult approve() { return new ApproveResult(null); }      // 放行
    public ApproveResult approve(String newArgs) { return new ApproveResult(newArgs); }  // 放行并改写参数
    public RejectResult reject(Object toolResult) { return new RejectResult(toolResult, null); }  // 拒绝
    public InterruptResult interrupt(InterruptRequest request) { return new InterruptResult(request); }  // 中断

    @Override
    public void beforeToolCall(AgentCallbackContext ctx) {
        ToolCallInputs inputs = (ToolCallInputs) ctx.getInputs();
        String toolName = inputs.getToolName();
        
        // 只拦截注册过的工具
        if (!toolNames.contains(toolName)) return;

        ToolCall toolCall = inputs.getToolCall();
        String toolCallId = toolCall != null ? toolCall.getId() : "";
        
        // 获取用户恢复输入（中断后用户提供的审批结果）
        Object userInput = getUserInput(ctx, toolCallId);
        
        // 子类实现判定逻辑
        InterruptDecision decision = resolveInterrupt(ctx, toolCall, userInput);
        
        // 应用决策
        applyDecision(ctx, toolCall, decision);
    }
}
```

### 3.3 applyDecision — 决策应用

```java
private void applyDecision(AgentCallbackContext ctx, ToolCall toolCall, InterruptDecision decision) {
    ToolCallInputs inputs = (ToolCallInputs) ctx.getInputs();
    
    if (decision instanceof ApproveResult) {
        // 放行：可选改写参数
        ApproveResult approveResult = (ApproveResult) decision;
        if (approveResult.getNewArgs() != null) {
            inputs.setToolArgs(approveResult.getNewArgs());
        }
        return;  // 正常执行
    }
    
    if (decision instanceof RejectResult) {
        // 拒绝：跳过执行 + 返回错误消息
        RejectResult rejectResult = (RejectResult) decision;
        ctx.getExtra().put("_skip_tool", Boolean.TRUE);
        inputs.setToolResult(rejectResult.getToolResult());
        inputs.setToolMsg(ToolMessage.builder()
                .content(String.valueOf(rejectResult.getToolResult()))
                .toolCallId(toolCall.getId())
                .build());
        return;
    }
    
    if (decision instanceof InterruptResult) {
        // 中断：抛出 ToolInterruptException 暂停执行
        InterruptResult interruptResult = (InterruptResult) decision;
        throw new ToolInterruptException(interruptResult.getRequest(), toolCall);
    }
}
```

### 3.4 PermissionEngine — 权限判定引擎

```java
public class PermissionEngine {
    private final Map<String, Object> config;
    private final Path workspaceRoot;

    public PermissionCheckResult checkPermission(String toolName, Map<String, Object> toolArgs) {
        // 1. 全局策略判定
        Map.Entry<PermissionLevel, String> direct = evaluateGlobalPolicyDirectly(toolName, toolArgs);
        PermissionLevel level = direct.getKey();
        
        return PermissionCheckResult.builder()
                .permission(level)
                .matchedRule(direct.getValue())
                .needsApproval(level == PermissionLevel.ASK)
                .build();
    }

    public Map.Entry<PermissionLevel, String> evaluateGlobalPolicyDirectly(
            String toolName, Map<String, Object> toolArgs) {
        // 未启用权限控制 → 默认放行
        if (!Boolean.TRUE.equals(config.getOrDefault("enabled", Boolean.FALSE))) {
            return Map.entry(PermissionLevel.ALLOW, "disabled");
        }
        
        // 优先匹配 tools 中的工具级配置
        Map<String, Object> tools = (Map<String, Object>) config.getOrDefault("tools", Map.of());
        if (tools.containsKey(toolName)) {
            return Map.entry(
                PermissionLevel.fromValue(tools.get(toolName)),
                "tools." + toolName
            );
        }
        
        // 回退到 defaults 通配符配置
        Map<String, Object> defaults = (Map<String, Object>) config.getOrDefault("defaults", Map.of("*", "allow"));
        return Map.entry(
            PermissionLevel.fromValue(defaults.getOrDefault("*", "allow")),
            "defaults.*"
        );
    }
}
```

**判定优先级**：

```
1. config.enabled == false → ALLOW（权限控制未启用）
2. config.tools[toolName] → 按工具级配置判定
3. config.defaults["*"] → 按通配符默认配置判定
4. 都没有 → ALLOW
```

### 3.5 PermissionLevel — 三级权限

```java
public enum PermissionLevel {
    ALLOW,   // 放行
    ASK,     // 中断等待审批
    DENY;    // 拒绝

    public static PermissionLevel fromValue(Object value) {
        return switch (String.valueOf(value).trim().toLowerCase(Locale.ROOT)) {
            case "allow" -> ALLOW;
            case "ask"   -> ASK;
            case "deny"  -> DENY;
            default      -> ALLOW;
        };
    }
}
```

### 3.6 PermissionCheckResult — 判定结果

```java
public class PermissionCheckResult {
    private PermissionLevel permission;    // 权限级别
    private String matchedRule;            // 匹配的规则名（如 "tools.write_file"）
    private boolean isApprovalNeeded;      // 是否需要审批（ASK 时为 true）
}
```

### 3.7 ToolPermissionHost — 权限宿主

```java
public class ToolPermissionHost {
    private Supplier<Path> resolveWorkspaceDir;           // 工作空间目录解析
    private Path permissionYamlPath;                       // 权限配置文件路径
    private Supplier<Map<String, Object>> getPermissionsSnapshot;  // 权限快照获取

    // 请求权限确认（默认实现返回 false，需自定义）
    public boolean requestPermissionConfirmation(String toolName, Map<String, Object> toolArgs) {
        return false;
    }

    // 持久化 allow 规则（用户选择"始终允许"时调用）
    public Map<String, Object> persistAllowRule(String toolName, Map<String, Object> toolArgs) {
        Map<String, Object> snapshot = new LinkedHashMap<>(getPermissionsSnapshot());
        Map<String, Object> tools = (Map<String, Object>) snapshot.getOrDefault("tools", Map.of());
        tools.put(toolName, "allow");  // 将工具权限改为 allow
        return snapshot;
    }
}
```

---

## 四、中断与恢复机制

### 4.1 中断流程

```
LLM 调用 write_file(file_path="/src/Main.java", content="...")
     ↓
BaseInterruptRail.beforeToolCall()
     ↓
PermissionInterruptRail.resolveInterrupt()
     ↓
PermissionEngine.checkPermission("write_file", {...})
     ↓
PermissionLevel = ASK
     ↓
返回 InterruptResult(InterruptRequest{
     message: "Permission approval required for tool: write_file",
     context: {tool_name: "write_file", matched_rule: "tools.write_file"}
})
     ↓
BaseInterruptRail.applyDecision()
     ↓
throw new ToolInterruptException(request, toolCall)
     ↓
ReActAgent 捕获异常 → 暂停执行 → 保存中断状态到 Session
     ↓
向调用方返回中断信息，等待用户审批
```

### 4.2 恢复流程

```
用户提供审批结果（approve / deny / modify_args）
     ↓
调用 agent.invoke({query: "...", resume_input: {toolCallId: "approve"}})
     ↓
ReActAgent 检测到 Session 中有中断状态
     ↓
从 ToolInterruptionState 恢复：
  - 加载 interruptedTools 列表
  - 将 resume_input 放入 ctx.extra["_resume_user_input"]
     ↓
重新执行被中断的工具调用
     ↓
BaseInterruptRail.beforeToolCall()
     ↓
getUserInput(ctx, toolCallId) → 获取用户审批结果
     ↓
PermissionInterruptRail.resolveInterrupt(ctx, toolCall, userInput)
     ↓
根据 userInput 判定：
  - "approve" → approve() → 正常执行
  - "deny" → reject() → 返回拒绝消息
  - {new_args: ...} → approve(newArgs) → 改写参数后执行
```

### 4.3 中断状态持久化

```java
// ToolInterruptionState — 中断状态
public class ToolInterruptionState implements Serializable {
    public static final String INTERRUPTION_KEY = "__react_agent_interruption__";
    public static final String RESUME_USER_INPUT_KEY = "_resume_user_input";

    private int iteration;                              // 中断时的迭代轮次
    private List<ToolInterruptEntry> interruptedTools;  // 被中断的工具列表
    private String originalQuery;                       // 原始查询
}
```

中断状态保存在 Session 的 state 中，Key 为 `__react_agent_interruption__`，支持跨请求恢复。

---

## 五、配置方式

### 5.1 DeepAgentConfig 配置

```java
DeepAgentConfig config = DeepAgentConfig.builder()
    .permissions(Map.of(
        "enabled", true,                    // 启用权限控制
        "schema", "tiered_policy",          // 策略模式
        "permission_mode", "normal",        // 权限模式
        "tools", Map.of(
            "read_file", "ask",             // read_file 需要审批
            "write_file", "deny",           // write_file 直接拒绝
            "bash", "ask"                   // bash 需要审批
        ),
        "defaults", Map.of("*", "allow")    // 其他工具默认放行
    ))
    .permissionHost(ToolPermissionHost.builder()
        .resolveWorkspaceDir(() -> Path.of("/workspace"))
        .permissionYamlPath(Path.of("/workspace/.permissions.yaml"))
        .build())
    .build();
```

### 5.2 YAML 配置

```yaml
permissions:
  enabled: true
  schema: tiered_policy
  permission_mode: normal
  tools:
    read_file: ask        # 读取文件需审批
    write_file: deny      # 禁止写文件
    edit_file: deny       # 禁止编辑文件
    bash: ask             # 执行命令需审批
    todo_create: allow    # 创建 todo 放行
    todo_modify: allow     # 修改 todo 放行
  defaults:
    "*": allow             # 其他工具默认放行
```

### 5.3 代码直接构建

```java
// 方式 1：通过 PermissionFactory
PermissionInterruptRail rail = PermissionFactory.buildPermissionInterruptRail(
    Map.of(
        "enabled", true,
        "tools", Map.of("write_file", "ask", "bash", "ask"),
        "defaults", Map.of("*", "allow")
    ),
    ToolPermissionHost.builder().build(),
    Path.of("/workspace")
);
deepAgent.registerRail(rail);

// 方式 2：直接构造
PermissionEngine engine = new PermissionEngine(
    Map.of("enabled", true, "tools", Map.of("write_file", "ask")),
    Path.of("/workspace")
);
ToolPermissionHost host = ToolPermissionHost.builder().build();
PermissionInterruptRail rail = new PermissionInterruptRail(engine, host);
deepAgent.registerRail(rail);
```

### 5.4 DeepAgent 自动注册

在 [DeepAgent.java](file:///d:/work/projects/java/latest/agent-core-java/src/main/java/com/openjiuwen/harness/deep_agent/DeepAgent.java) 的 init() 中自动注册：

```java
// DeepAgent.java 第 371-380 行
if (config.getPermissions() != null 
    && Boolean.TRUE.equals(config.getPermissions().get("enabled"))) {
    var rail = PermissionFactory.buildPermissionInterruptRail(
            config.getPermissions(),
            config.getPermissionHost(),
            workspace.root()
    );
    agent.registerRail(rail);
    registeredRails.add(rail);
}
```

**条件**：`permissions` 配置存在且 `enabled=true` 时才注册。

---

## 六、完整执行流程示例

### 场景 1：write_file 被 DENY 拒绝

```
配置：
  permissions:
    enabled: true
    tools:
      write_file: deny
    defaults:
      "*": allow

执行流程：
  LLM → 调用 write_file(file_path="/src/Main.java", content="...")
  
  beforeToolCall():
    → toolNames.contains("write_file") = true → 拦截
    → resolveInterrupt():
       → engine.checkPermission("write_file", {...})
       → tools["write_file"] = "deny" → PermissionLevel.DENY
       → 返回 reject("Permission denied for tool: write_file")
    → applyDecision():
       → _skip_tool = true
       → toolResult = "Permission denied for tool: write_file"
       → toolMsg = ToolMessage(content="Permission denied for tool: write_file")
  
  LLM 收到错误消息 → 调整策略（如改用其他方式）
```

### 场景 2：bash 被 ASK 中断等待审批

```
配置：
  permissions:
    enabled: true
    tools:
      bash: ask
    defaults:
      "*": allow

执行流程：
  LLM → 调用 bash(command="rm -rf /tmp/old_files")
  
  beforeToolCall():
    → toolNames.contains("bash") = true → 拦截
    → resolveInterrupt():
       → engine.checkPermission("bash", {command: "rm -rf /tmp/old_files"})
       → tools["bash"] = "ask" → PermissionLevel.ASK
       → 返回 interrupt(InterruptRequest{
           message: "Permission approval required for tool: bash",
           context: {tool_name: "bash", matched_rule: "tools.bash"}
         })
    → applyDecision():
       → throw new ToolInterruptException(request, toolCall)
  
  ReActAgent 捕获异常：
    → 保存 ToolInterruptionState 到 Session
    → 返回中断信息给调用方
    → Agent 暂停，等待用户审批

用户审批 approve：
    → 调用 agent.invoke({query: "...", resume_input: {"call_xxx": "approve"}})
    → ReActAgent 检测到中断状态
    → ctx.extra["_resume_user_input"] = {call_xxx: "approve"}
    → 重新执行 bash 工具调用
    → beforeToolCall():
       → getUserInput(ctx, "call_xxx") → "approve"
       → resolveInterrupt(ctx, toolCall, "approve")
       → 用户已审批 → approve() → 正常执行
    → bash(command="rm -rf /tmp/old_files") 执行成功
```

### 场景 3：read_file 被 ASK 中断，用户改写参数后放行

```
配置：
  permissions:
    enabled: true
    tools:
      read_file: ask

执行流程：
  LLM → 调用 read_file(file_path="/etc/passwd")
  
  beforeToolCall():
    → resolveInterrupt():
       → PermissionLevel.ASK
       → interrupt(InterruptRequest{...})
    → throw ToolInterruptException
  
  Agent 暂停，用户看到请求：
    "Permission approval required for tool: read_file"
    "tool_name: read_file, matched_rule: tools.read_file"
  
  用户决定改写参数（拒绝读取敏感文件，改为读取其他文件）：
    → resume_input: {"call_xxx": {new_args: {file_path: "/workspace/README.md"}}}
  
  恢复执行：
    → getUserInput(ctx, "call_xxx") → {new_args: {file_path: "/workspace/README.md"}}
    → resolveInterrupt(ctx, toolCall, userInput)
    → 检测到 userInput 中有 new_args → approve(newArgs)
    → applyDecision(): inputs.setToolArgs(newArgs) → 改写参数
    → read_file(file_path="/workspace/README.md") 执行
```

### 场景 4：默认放行（未配置的工具）

```
配置：
  permissions:
    enabled: true
    tools:
      write_file: deny
    defaults:
      "*": allow

LLM → 调用 todo_create(tasks=[...])

  beforeToolCall():
    → toolNames = {"write_file"}（只注册了配置中声明的工具）
    → toolNames.contains("todo_create") = false → 不拦截
    → 正常执行

或：
  → engine.checkPermission("todo_create", {...})
  → tools 中没有 todo_create
  → defaults["*"] = "allow" → PermissionLevel.ALLOW
  → approve() → 正常执行
```

---

## 七、使用场景

### 场景 1：金融系统敏感操作审批

```
配置：
  permissions:
    enabled: true
    tools:
      write_file: ask          # 修改文件需审批
      edit_file: ask           # 编辑文件需审批
      bash: ask                # 执行命令需审批
      git_push: deny           # 禁止推送代码
      delete_file: deny        # 禁止删除文件
    defaults:
      "*": allow

效果：
  → Agent 可以自由读取文件、搜索代码
  → 修改文件前必须人工审批
  → 禁止推送和删除操作
```

### 场景 2：生产环境只读 + 审批写入

```
配置：
  permissions:
    enabled: true
    tools:
      read_file: allow          # 读取放行
      grep: allow               # 搜索放行
      list_files: allow         # 列表放行
      write_file: ask           # 写入需审批
      edit_file: ask            # 编辑需审批
      bash: ask                 # 命令需审批
    defaults:
      "*": deny                 # 其他全部禁止

效果：
  → Agent 默认只能读取和搜索
  → 任何写操作都需要人工审批
  → 未明确允许的工具全部拒绝
```

### 场景 3：分级权限管控

```
配置：
  permissions:
    enabled: true
    tools:
      read_file: allow          # 读取：放行
      grep: allow               # 搜索：放行
      list_files: allow         # 列表：放行
      todo_create: allow         # todo：放行
      todo_modify: allow         # todo：放行
      write_file: ask           # 写文件：需审批
      edit_file: ask            # 编辑文件：需审批
      bash: ask                 # 命令：需审批
      git_commit: deny          # git 提交：禁止
      git_push: deny            # git 推送：禁止
    defaults:
      "*": ask                  # 其他工具默认需审批

效果：
  → 只读工具自由使用
  → 写操作需审批
  → Git 操作禁止
  → 新工具默认需审批（安全默认）
```

### 场景 4：用户选择"始终允许"后持久化

```
首次调用 bash：
  → PermissionLevel.ASK → 中断
  → 用户选择 "始终允许"
  → ToolPermissionHost.persistAllowRule("bash", {...})
  → 权限配置更新：tools["bash"] = "allow"
  → 后续 bash 调用直接放行，不再中断
```

---

## 八、中断恢复详解

### 8.1 中断信息返回

中断后，Agent 向调用方返回中断信息：

```java
// ReActAgent.collectToolInterrupts() 收集中断信息
ToolInterruptionState state = ToolInterruptionState.builder()
    .iteration(iteration)
    .interruptedTools(interruptedTools)
    .originalQuery(originalQuery)
    .build();

// commitInterrupt() 提交中断
session.updateState(Map.of(INTERRUPTION_KEY, state));
```

### 8.2 恢复输入格式

用户提供恢复输入，支持三种格式：

```java
// 格式 1：简单审批（字符串）
resume_input: "approve"           // 放行
resume_input: "deny"              // 拒绝

// 格式 2：按 toolCallId 审批（Map）
resume_input: {
    "call_abc123": "approve",     // 批准 call_abc123
    "call_def456": "deny"         // 拒绝 call_def456
}

// 格式 3：改写参数（Map with new_args）
resume_input: {
    "call_abc123": {
        "new_args": {
            "file_path": "/safe/path/file.txt"
        }
    }
}
```

### 8.3 恢复执行流程

```java
// ReActAgent.invoke() 中的恢复逻辑
ToolInterruptionState interruptionState = loadInterruptionState(session);
if (interruptionState != null) {
    // 有中断状态 → 恢复执行
    Object resumeInput = invokeInputs.getResumeInput();
    ctx.getExtra().put(RESUME_USER_INPUT_KEY, resumeInput);
    
    // 重新执行被中断的工具调用
    List<ToolCall> interruptedToolCalls = toInterruptedToolCalls(interruptionState);
    // ... 重新执行
}
```

---

## 九、设计要点总结

| 设计要点 | 实现方式 |
|---------|---------|
| **三级权限** | PermissionLevel 枚举：ALLOW（放行）/ ASK（中断审批）/ DENY（拒绝） |
| **声明式配置** | YAML/Map 配置工具权限，无需编码 |
| **工具级配置** | `config.tools[toolName]` 按工具名配置权限 |
| **通配符默认** | `config.defaults["*"]` 配置未声明工具的默认权限 |
| **选择性拦截** | 只有 `config.tools` 中声明的工具才会被拦截 |
| **中断暂停** | `throw ToolInterruptException` 暂停 ReAct 循环 |
| **状态持久化** | `ToolInterruptionState` 保存到 Session，支持跨请求恢复 |
| **恢复输入** | 支持 approve/deny/new_args 三种恢复方式 |
| **参数改写** | `approve(newArgs)` 允许审批时改写工具参数 |
| **优先级 90** | 高于普通 Rail（50）和 SecurityRail（80），优先执行权限校验 |
| **条件注册** | `permissions.enabled=true` 时才注册，不影响无权限场景的性能 |
| **宿主扩展** | ToolPermissionHost 提供权限确认和规则持久化的扩展点 |
| **优雅降级** | DENY 不抛异常，返回错误 ToolMessage 让 LLM 自行调整 |

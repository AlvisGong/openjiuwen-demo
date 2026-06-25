# SkillUseRail 触发调用链路分析

## 一、创建 Agent 时调用 `SkillUseRail.init()` 的链路

`SkillUseRail` 同时继承 `AgentRail` 和 `DeepAgentRail`，在 `ensureInitialized()` 中两条 `instanceof` 分支**都会命中**：

```java
// DeepAgent.java:357-368
for (Object rail : config.getRails()) {
    if (rail instanceof AgentRail agentRail) {        // ✅ SkillUseRail 是 AgentRail
        agent.registerRail(agentRail);                 // 步骤 A
    }
    if (rail instanceof DeepAgentRail deepAgentRail) { // ✅ SkillUseRail 也是 DeepAgentRail
        deepAgentRail.init(this);                      // 步骤 B
    }
}
```

### 步骤 A：`agent.registerRail(skillUseRail)` → 注册回调机制

```java
// BaseAgent.java:176-178
public BaseAgent registerRail(AgentRail rail) {
    agentCallbackManager.registerRail(rail, this);  // this = ReActAgent
}

// AgentCallbackManager.java:81-97
public void registerRail(AgentRail rail, Object agent) {
    rail.init(agent);  // ← skillUseRail.init(reActAgent)
    // ⚠️ 但 SkillUseRail.init() 第一行就检查:
    //   if (!(agent instanceof DeepAgent deepAgent)) { return; }
    // ReActAgent 不是 DeepAgent → init() 直接返回，不做任何初始化

    // 不过，回调注册仍然执行 ↓
    for (Map.Entry<AgentCallbackEvent, Consumer<AgentCallbackContext>> entry : rail.getCallbacks().entrySet()) {
        registerCallback(entry.getKey(), entry.getValue(), rail.getPriority());
        // ← 将 beforeModelCall 注册为 BEFORE_MODEL_CALL 事件的回调
    }
}
```

`getCallbacks()` 通过反射检测子类是否覆写了基类方法：

```java
// AgentRail.java:181-192
public Map<AgentCallbackEvent, Consumer<AgentCallbackContext>> getCallbacks() {
    for (Map.Entry<AgentCallbackEvent, String> entry : EVENT_METHOD_MAP.entrySet()) {
        if (!isBaseMethod(entry.getValue())) {
            // SkillUseRail 覆写了 beforeModelCall → 不是基类方法
            callbacks.put(entry.getKey(), buildCallback(entry.getValue()));
        }
    }
    // → BEFORE_MODEL_CALL → SkillUseRail.beforeModelCall 被提取出来注册
}
```

### 步骤 B：`deepAgentRail.init(this)` → 实际初始化

```java
// SkillUseRail.java:125-152
public void init(Object agent) {
    if (!(agent instanceof DeepAgent deepAgent)) { return; }  // ✅ this = DeepAgent，通过检查
    owner = deepAgent;
    skillManager = new SkillManager(deepAgent.getCard().getId());
    // 创建 list_skill / skill_tool 工具，注册到 DeepAgent
    tools.add(new LocalFunction(card("list_skill", ...), inputs -> listSkill(inputs)));
    tools.add(new LocalFunction(card("skill_tool", ...), inputs -> readSkill(inputs)));
    for (Tool tool : tools) {
        deepAgent.registerHarnessTool(tool);   // 注册到 ReActAgent 的 AbilityManager
    }
    reloadSkills();                            // 首次加载所有 skill
}
```

**关键设计**：步骤 A 中 `init(ReActAgent)` 虽然空跑，但**回调注册已完成**（`beforeModelCall` 已挂到 `BEFORE_MODEL_CALL` 事件）；步骤 B 中 `init(DeepAgent)` 完成**实际初始化**（工具注册、skill 加载）。两步互补。

---

## 二、LLM 调用前触发 `beforeModelCall()` 的链路

完整调用链：

```
ReActAgent.step()
  → ReActAgent.think(messages, tools)
    → railedModelCall(ctx)
      → RailExecutor.execute(ctx, BEFORE_MODEL_CALL, AFTER_MODEL_CALL, ON_MODEL_EXCEPTION, () -> model.invoke(...))
        → ctx.fire(BEFORE_MODEL_CALL)
          → AgentCallbackContext.fire(event)
            → firer.fireCallbackEvent(event, this)      // firer = BaseAgent(ReActAgent)
              → AgentCallbackManager.execute(event, ctx)
                → 按优先级遍历 localCallbacks 中的 BEFORE_MODEL_CALL 回调
                  → SkillUseRail.beforeModelCall(ctx)    ← 最终到达
```

### 层1 — ReActAgent 调用 LLM 时用 RailExecutor 包装

```java
// ReActAgent.java:349-371
private Optional<AssistantMessage> railedModelCall(AgentCallbackContext ctx) {
    return RailExecutor.execute(
            ctx,
            AgentCallbackEvent.BEFORE_MODEL_CALL,  // ← before 事件类型
            AgentCallbackEvent.AFTER_MODEL_CALL,
            AgentCallbackEvent.ON_MODEL_EXCEPTION,
            () -> { model.invoke(...); }            // ← 实际 LLM 调用
    );
}
```

### 层2 — RailExecutor 在执行 body 前先 fire before 事件

```java
// RailExecutor.java:56-62
if (before != null) {
    ctx.fire(before);  // ctx.fire(BEFORE_MODEL_CALL)
}
if (ctx.hasForceFinishRequest()) {
    return Optional.empty();  // rail 可通过 forceFinish 阻止 LLM 调用
}
return Optional.ofNullable(body.execute());  // 执行实际 LLM 调用
```

### 层3 — AgentCallbackContext.fire() → BaseAgent → AgentCallbackManager

```java
// AgentCallbackContext.java:58-64
public void fire(AgentCallbackEvent event) {
    this.event = event;
    if (agent instanceof AgentCallbackFirer firer) {
        firer.fireCallbackEvent(event, this);
    }
}

// BaseAgent.java:196-198
public void fireCallbackEvent(AgentCallbackEvent event, AgentCallbackContext ctx) {
    agentCallbackManager.execute(event, ctx);
}
```

### 层4 — AgentCallbackManager 按优先级执行所有注册的回调

```java
// AgentCallbackManager.java:200-211
public void execute(AgentCallbackEvent event, AgentCallbackContext ctx) {
    String agentEvent = getAgentEvent(event);  // "deep_agent_before_model_call"
    List<RegisteredCallback> snapshot = new ArrayList<>(localCallbacks.get(agentEvent));
    snapshot.sort(按priority降序);  // priority越大越先执行, SkillUseRail.priority=100
    for (RegisteredCallback rc : snapshot) {
        rc.callback().accept(ctx);  // ← 执行 SkillUseRail.beforeModelCall(ctx)
    }
}
```

### 层5 — SkillUseRail.beforeModelCall() 执行热加载检测 + prompt 注入

```java
// SkillUseRail.java:202-217
public void beforeModelCall(AgentCallbackContext ctx) {
    if (owner == null || skillManager.count() == 0 || "none".equals(skillMode)) {
        removePromptSection(); return;
    }
    // 热加载检测：比较当前文件 mtime 签名 vs 上次快照
    List<Map.Entry<String, Long>> currentSignature = buildCurrentSignature();
    if (signaturesEqual(currentSignature, skillsSnapshotSignature)) {
        injectSkillPrompt(ctx);  // 无变化，直接注入
        return;
    }
    prepareSkills();                       // 有变化 → 增量刷新 skill
    skillsSnapshotSignature = currentSignature;
    injectSkillPrompt(ctx);               // 注入更新后的 prompt
}
```

---

## 三、总结流程图

```
┌─ 创建阶段 ──────────────────────────────────────────────┐
│  DeepAgent.ensureInitialized()                           │
│    ├─ agent.registerRail(skillUseRail)                   │
│    │    → AgentCallbackManager.registerRail()             │
│    │      ├─ rail.init(ReActAgent) → 空跑(return)        │
│    │      └─ rail.getCallbacks() → 注册 beforeModelCall  │
│    │         为 BEFORE_MODEL_CALL 回调                    │
│    └─ skillUseRail.init(DeepAgent) → 实际初始化           │
│       ├─ 注册 list_skill / skill_tool 工具               │
│       └─ reloadSkills() 首次加载                         │
└──────────────────────────────────────────────────────────┘

┌─ 运行阶段（每次 LLM 调用）──────────────────────────────┐
│  ReActAgent.railedModelCall(ctx)                         │
│    → RailExecutor.execute(ctx, BEFORE_MODEL_CALL, ...)   │
│      → ctx.fire(BEFORE_MODEL_CALL)                       │
│        → AgentCallbackManager.execute()                  │
│          → SkillUseRail.beforeModelCall(ctx)             │
│            ├─ mtime签名比对 → 热加载检测                  │
│            ├─ 变化时 prepareSkills() 增量刷新             │
│            └─ injectSkillPrompt() 注入 skill prompt      │
│      → model.invoke(...)  ← 实际 LLM 调用               │
└──────────────────────────────────────────────────────────┘
```

---

## 四、涉及的关键文件

| 文件 | 作用 |
|------|------|
| `DeepAgent.java` | Harness 层 Agent 封装，`ensureInitialized()` 中注册 rail 并调用 `init()` |
| `BaseAgent.java` | 核心 Agent 基类，`registerRail()` → `AgentCallbackManager.registerRail()`，`fireCallbackEvent()` 触发回调 |
| `AgentCallbackManager.java` | Rail 回调注册与执行管理器，`registerRail()` 调用 `rail.init()` + 注册回调，`execute()` 按优先级遍历回调 |
| `AgentRail.java` | Rail 基类，`getCallbacks()` 通过反射提取子类覆写的钩子方法 |
| `RailExecutor.java` | 替代 Python `@rail` 装饰器，`execute()` 在执行 body 前先 fire before 事件 |
| `AgentCallbackContext.java` | 回调上下文，`fire()` 方法委托给 `AgentCallbackFirer`（即 `BaseAgent`） |
| `ReActAgent.java` | ReAct 推理 Agent，`railedModelCall()` 用 `RailExecutor` 包装 LLM 调用 |
| `SkillUseRail.java` | Skill 使用 Rail，`init()` 初始化工具和 skill，`beforeModelCall()` 热加载检测 + prompt 注入 |

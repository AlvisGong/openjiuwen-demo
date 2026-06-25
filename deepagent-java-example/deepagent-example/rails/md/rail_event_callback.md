# Rail 事件与回调机制详解

## 核心概念：事件是"信号"，回调是"谁来响应"

类比**广播系统**：

- **事件**（`AgentCallbackEvent`）= 广播频道号，比如"频道3 = BEFORE_MODEL_CALL"
- **回调**（`Consumer<AgentCallbackContext>`）= 收音机，某个收音机订阅了频道3，频道3有广播时它就会收到
- **fire** = 播报员按下广播按钮，向频道3发送信号
- 所有订阅了频道3的收音机（回调）**按优先级顺序依次响起**

---

## 具体实现分两步：注册 + 触发

### 第一步：注册 —— "收音机订阅频道"

发生在 `ensureInitialized()` → `agent.registerRail(skillUseRail)` 时：

```
AgentCallbackManager.registerRail(skillUseRail, reActAgent)
```

这里面做了两件事：

**1. 先调 `rail.init()`**（空跑，SkillUseRail.init(ReActAgent) 因类型检查直接 return）

**2. 把 rail 覆写的方法提取出来，注册到对应事件频道**

```java
// AgentCallbackManager.java:81-88
public void registerRail(AgentRail rail, Object agent) {
    rail.init(agent);
    for (Map.Entry<AgentCallbackEvent, Consumer<AgentCallbackContext>> entry : rail.getCallbacks().entrySet()) {
        // entry.getKey()   = 事件频道号 (BEFORE_MODEL_CALL)
        // entry.getValue() = 回调函数 (skillUseRail.beforeModelCall)
        registerCallback(entry.getKey(), entry.getValue(), rail.getPriority());
    }
}
```

### `getCallbacks()` 如何知道 SkillUseRail 有 `beforeModelCall`？——反射检测

```java
// AgentRail.java:181-192
// EVENT_METHOD_MAP 是一张固定映射表:
//   BEFORE_MODEL_CALL → "beforeModelCall"
//   AFTER_MODEL_CALL  → "afterModelCall"
//   ...

public Map<AgentCallbackEvent, Consumer<AgentCallbackContext>> getCallbacks() {
    for (Map.Entry<AgentCallbackEvent, String> entry : EVENT_METHOD_MAP.entrySet()) {
        if (!isBaseMethod(entry.getValue())) {  // 关键：检查子类是否覆写了这个方法
            // SkillUseRail 覆写了 beforeModelCall → 不是基类方法 → true
            // SkillUseRail 没覆写 afterInvoke      → 是基类方法   → false (不注册)
            callbacks.put(entry.getKey(), buildCallback(entry.getValue()));
        }
    }
}
```

`isBaseMethod` 用反射比较：

```java
// AgentRail.java:194-202
private boolean isBaseMethod(String methodName) {
    Method subclassMethod = this.getClass().getMethod(methodName, AgentCallbackContext.class);
    // this.getClass() = SkillUseRail.class
    Method baseMethod = AgentRail.class.getMethod(methodName, AgentCallbackContext.class);
    // AgentRail.class

    return subclassMethod.equals(baseMethod);
    // SkillUseRail.beforeModelCall ≠ AgentRail.beforeModelCall → return false
    // → 说明子类覆写了 → 需要注册这个回调
}
```

**注册结果**：在 `localCallbacks` 这个 Map 中，key 是事件名，value 是回调列表：

```
localCallbacks = {
    "deep_agent_before_model_call" → [
        RegisteredCallback(callback=skillUseRail.beforeModelCall, priority=100)
    ],
    // 其他事件频道也可能有其他回调...
}
```

---

### 第二步：触发 —— "播报员按广播按钮"

发生在 ReActAgent 每次调用 LLM 时：

```java
// ReActAgent.java:349-371
private Optional<AssistantMessage> railedModelCall(AgentCallbackContext ctx) {
    return RailExecutor.execute(
        ctx,
        AgentCallbackEvent.BEFORE_MODEL_CALL,  // ← 传入"频道号"
        AgentCallbackEvent.AFTER_MODEL_CALL,
        AgentCallbackEvent.ON_MODEL_EXCEPTION,
        () -> { model.invoke(...); }            // ← 实际业务
    );
}
```

### RailExecutor：在业务代码前先 fire before 事件

```java
// RailExecutor.java:43-62
public static <T> Optional<T> execute(ctx, before, after, onException, body) {
    while (true) {
        try {
            if (before != null) {
                ctx.fire(before);               // ← fire(BEFORE_MODEL_CALL)
            }
            if (ctx.hasForceFinishRequest()) {   // rail 可以通过这个阻止 LLM 调用
                return Optional.empty();
            }
            return Optional.ofNullable(body.execute()); // ← 执行实际 LLM 调用
        } catch (Exception e) {
            ctx.setException(e);
            if (onException != null) {
                ctx.fire(onException);          // fire(ON_MODEL_EXCEPTION)
            }
            // retry 逻辑...
        } finally {
            if (after != null) {
                ctx.fire(after);                 // fire(AFTER_MODEL_CALL)
            }
        }
    }
}
```

### ctx.fire() → 委托给 BaseAgent → AgentCallbackManager

```java
// AgentCallbackContext.java:58-64
public void fire(AgentCallbackEvent event) {
    this.event = event;   // 记录当前是哪个事件
    // ctx.agent 是创建时绑定的 BaseAgent(ReActAgent)
    if (agent instanceof AgentCallbackFirer firer) {
        firer.fireCallbackEvent(event, this);   // ← 委托给 BaseAgent
    }
}

// BaseAgent.java:196-198
public void fireCallbackEvent(AgentCallbackEvent event, AgentCallbackContext ctx) {
    agentCallbackManager.execute(event, ctx);   // ← 委托给 AgentCallbackManager
}
```

### AgentCallbackManager.execute() —— 最终分发点

找到对应频道的所有收音机，按优先级依次播放：

```java
// AgentCallbackManager.java:200-211
public void execute(AgentCallbackEvent event, AgentCallbackContext ctx) {
    // 把枚举转成带 agentId 前缀的字符串，避免多个 agent 冲突
    String agentEvent = getAgentEvent(event);  // "deep_agent" + "_" + "before_model_call"

    // 找到订阅了该频道的所有回调
    List<RegisteredCallback> callbacksForEvent = localCallbacks.get(agentEvent);

    // 按优先级排序（priority 大的先执行）
    snapshot.sort(优先级降序);

    // 逐个调用
    for (RegisteredCallback rc : snapshot) {
        rc.callback().accept(ctx);  // ← skillUseRail.beforeModelCall(ctx)
    }
}
```

---

## 完整数据流图

```
┌─────────── 注册阶段（启动时，一次性）───────────────┐
│                                                     │
│  SkillUseRail 覆写了 beforeModelCall                │
│       │                                             │
│       ▼                                             │
│  AgentRail.getCallbacks()                           │
│    反射检测: SkillUseRail.beforeModelCall            │
│    ≠ AgentRail.beforeModelCall → 需要注册            │
│       │                                             │
│       ▼                                             │
│  提取出: {                                          │
│    BEFORE_MODEL_CALL → skillUseRail.beforeModelCall │
│  }                                                  │
│       │                                             │
│       ▼                                             │
│  AgentCallbackManager.registerCallback()            │
│    存入 localCallbacks:                             │
│    key  = "deep_agent_before_model_call"            │
│    value = [RegisteredCallback(cb, priority=100)]   │
│                                                     │
└─────────────────────────────────────────────────────┘

┌─────────── 触发阶段（每次 LLM 调用，反复执行）──────┐
│                                                     │
│  ReActAgent 要调用 LLM                              │
│       │                                             │
│       ▼                                             │
│  RailExecutor.execute(ctx, BEFORE_MODEL_CALL, ...)  │
│       │                                             │
│       ▼  先 fire before 事件                        │
│  ctx.fire(BEFORE_MODEL_CALL)                        │
│       │                                             │
│       ▼                                             │
│  ctx.agent.fireCallbackEvent(event, ctx)            │
│    → BaseAgent.fireCallbackEvent()                  │
│       │                                             │
│       ▼                                             │
│  agentCallbackManager.execute(event, ctx)           │
│    1. getAgentEvent → "deep_agent_before_model_call"│
│    2. localCallbacks.get(key) → 找到回调列表        │
│    3. 按优先级排序                                   │
│    4. 逐个执行: rc.callback().accept(ctx)           │
│       │                                             │
│       ▼                                             │
│  SkillUseRail.beforeModelCall(ctx) ← 最终到达       │
│    - mtime签名比对(热加载检测)                       │
│    - injectSkillPrompt(注入skill prompt)            │
│       │                                             │
│       ▼                                             │
│  回到 RailExecutor, 执行 model.invoke() (LLM调用)  │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

## 涉及的关键文件

| 文件 | 角色 |
|------|------|
| `AgentCallbackEvent.java` | 事件枚举，定义 8 种生命周期事件（频道号） |
| `AgentCallbackContext.java` | 上下文对象，`fire()` 方法触发事件分发 |
| `AgentCallbackManager.java` | 核心管理器，`registerCallback()` 注册回调，`execute()` 按优先级分发 |
| `AgentRail.java` | Rail 基类，`getCallbacks()` 反射提取子类覆写的钩子方法 |
| `RailExecutor.java` | 执行器，在业务代码前后 fire before/after 事件 |
| `BaseAgent.java` | Agent 基类，`fireCallbackEvent()` 桥接到 AgentCallbackManager |
| `ReActAgent.java` | ReAct Agent，`railedModelCall()` 用 RailExecutor 包装 LLM 调用 |
| `SkillUseRail.java` | Skill 使用 Rail，`beforeModelCall()` 是订阅了 BEFORE_MODEL_CALL 的回调 |

---

## 本质

**事件是枚举值当 key，回调是函数当 value，注册时存到 Map 里，触发时从 Map 里查出来执行。** 本质上就是一个观察者模式（Observer Pattern）的应用。

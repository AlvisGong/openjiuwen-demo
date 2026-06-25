# DeepAgent 10 个 Rail 扩展点实现机制分析

## 一、整体架构：三层调度

```
┌─────────────────────────────────────────────────────────────────────────┐
│  第 1 层：AgentCallbackEvent（事件定义）                                  │
│  8 个枚举值，定义 8 个生命周期事件                                        │
│  + TaskIterationRail 接口定义 2 个外循环事件                              │
├─────────────────────────────────────────────────────────────────────────┤
│  第 2 层：AgentCallbackManager（注册与调度）                              │
│  注册 Rail → 提取覆写的钩子方法 → 按优先级排序 → 逐个执行                  │
├─────────────────────────────────────────────────────────────────────────┤
│  第 3 层：RailExecutor（执行包装器）                                      │
│  fire(before) → body() → fire(after)  [异常时 fire(onException)]         │
│  支持 retry 和 forceFinish                                              │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 二、10 个扩展点的定义

### 8 个基础事件（AgentCallbackEvent 枚举）

AgentCallbackEvent.java 定义了 8 个事件：

```java
public enum AgentCallbackEvent {
    BEFORE_INVOKE("before_invoke"),         // Agent.invoke() 执行前
    AFTER_INVOKE("after_invoke"),           // Agent.invoke() 执行后
    BEFORE_MODEL_CALL("before_model_call"), // LLM 调用前
    AFTER_MODEL_CALL("after_model_call"),   // LLM 调用后
    ON_MODEL_EXCEPTION("on_model_exception"),// LLM 调用异常
    BEFORE_TOOL_CALL("before_tool_call"),   // 工具调用前
    AFTER_TOOL_CALL("after_tool_call"),     // 工具调用后
    ON_TOOL_EXCEPTION("on_tool_exception"); // 工具调用异常
}
```

### 2 个外循环事件（TaskIterationRail 接口）

TaskIterationRail.java 定义了外循环专属事件：

```java
public interface TaskIterationRail {
    default void afterTaskIteration(TaskIterationContext ctx) {}
    // beforeTaskIteration 在当前版本未实现（无匹配代码）
}
```

> **注意**：`before_task_iteration` 在当前代码中**未实现触发点**，只有 `after_task_iteration` 在 CoreTaskLoopEventExecutor 中被调用。

---

## 三、事件与钩子方法的映射

AgentRail.java 通过 `EVENT_METHOD_MAP` 建立事件→方法的映射：

```java
EVENT_METHOD_MAP.put(BEFORE_INVOKE,     "beforeInvoke");
EVENT_METHOD_MAP.put(AFTER_INVOKE,      "afterInvoke");
EVENT_METHOD_MAP.put(BEFORE_MODEL_CALL, "beforeModelCall");
EVENT_METHOD_MAP.put(AFTER_MODEL_CALL,  "afterModelCall");
EVENT_METHOD_MAP.put(ON_MODEL_EXCEPTION,"onModelException");
EVENT_METHOD_MAP.put(BEFORE_TOOL_CALL,  "beforeToolCall");
EVENT_METHOD_MAP.put(AFTER_TOOL_CALL,   "afterToolCall");
EVENT_METHOD_MAP.put(ON_TOOL_EXCEPTION, "onToolException");
```

每个 Rail 子类只需覆写感兴趣的钩子方法，**未覆写的方法不会注册**（通过 `isBaseMethod()` 反射检测）。

---

## 四、注册机制：AgentCallbackManager

AgentCallbackManager.java 是核心调度器：

```java
// 注册 Rail 的完整流程
public void registerRail(AgentRail rail, Object agent) {
    // 1. 调用 rail.init(agent) 生命周期钩子
    rail.init(agent);

    // 2. 提取 Rail 覆写的钩子方法（反射检测哪些方法被覆写）
    Map<AgentCallbackEvent, Consumer<AgentCallbackContext>> callbacks = rail.getCallbacks();

    // 3. 将每个钩子注册到对应事件，带上 Rail 的优先级
    for (var entry : callbacks.entrySet()) {
        registerCallback(entry.getKey(), entry.getValue(), rail.getPriority());
    }

    // 4. 将 Rail 携带的工具注册到 Agent
    if (rail.getTools() != null) {
        for (var toolCard : rail.getTools()) {
            baseAgent.getAbilityManager().add(toolCard);
        }
    }
}
```

### getCallbacks() 的反射检测

```java
public Map<AgentCallbackEvent, Consumer<AgentCallbackContext>> getCallbacks() {
    for (var entry : EVENT_METHOD_MAP.entrySet()) {
        // 检查子类是否覆写了该方法
        if (!isBaseMethod(entry.getValue())) {
            // 通过反射构建 Consumer<AgentCallbackContext>
            callbacks.put(entry.getKey(), buildCallback(entry.getValue()));
        }
    }
    return callbacks;
}

private boolean isBaseMethod(String methodName) {
    Method subclassMethod = this.getClass().getMethod(methodName, AgentCallbackContext.class);
    Method baseMethod = AgentRail.class.getMethod(methodName, AgentCallbackContext.class);
    return subclassMethod.equals(baseMethod);  // 如果等于基类方法，说明未覆写
}
```

### 执行时的优先级排序

```java
public void execute(AgentCallbackEvent event, AgentCallbackContext ctx) {
    List<RegisteredCallback> snapshot = new ArrayList<>(callbacksForEvent);
    // 按优先级降序排列：优先级高的先执行
    snapshot.sort((left, right) -> Integer.compare(right.priority(), left.priority()));
    for (var registeredCallback : snapshot) {
        registeredCallback.callback().accept(ctx);
    }
}
```

---

## 五、触发机制：RailExecutor

RailExecutor.java 是执行包装器，实现了 `before → body → after` 的标准模式：

```java
public static <T> Optional<T> execute(
    AgentCallbackContext ctx,
    AgentCallbackEvent before,      // 前置事件
    AgentCallbackEvent after,       // 后置事件
    AgentCallbackEvent onException, // 异常事件
    RailBody<T> body                // 实际业务逻辑
) {
    while (true) {
        try {
            ctx.fire(before);              // ① 触发前置事件
            if (ctx.hasForceFinishRequest()) {
                return Optional.empty();   // ② Rail 可通过 requestForceFinish 提前终止
            }
            return Optional.of(body.execute());  // ③ 执行业务逻辑
        } catch (Exception e) {
            ctx.setException(e);
            ctx.fire(onException);         // ④ 触发异常事件
            RetryRequest retry = ctx.consumeRetryRequest();
            if (retry == null) throw e;     // ⑤ 无 retry 请求则抛出
            // ⑥ 有 retry 请求，等待后重试
            Thread.sleep((long)(retry.getDelaySeconds() * 1000));
        } finally {
            ctx.fire(after);               // ⑦ 触发后置事件（always）
        }
    }
}
```

---

## 六、10 个扩展点的触发位置

### 1. before_invoke / after_invoke — 在 ReActAgent.invoke() 中

```java
// before_invoke：invoke() 入口处
fireCallbackEvent(AgentCallbackEvent.BEFORE_INVOKE, ctx);
try {
    // ... ReAct 循环执行 ...
} finally {
    // after_invoke：invoke() 出口处（always）
    fireCallbackEvent(AgentCallbackEvent.AFTER_INVOKE, ctx);
}
```

### 2. before_model_call / after_model_call / on_model_exception — 在 railedModelCall() 中

```java
private Optional<AssistantMessage> railedModelCall(AgentCallbackContext ctx) {
    return RailExecutor.execute(
        ctx,
        AgentCallbackEvent.BEFORE_MODEL_CALL,   // LLM 调用前
        AgentCallbackEvent.AFTER_MODEL_CALL,     // LLM 调用后
        AgentCallbackEvent.ON_MODEL_EXCEPTION,   // LLM 调用异常
        () -> {
            AssistantMessage aiMessage = model.invoke(messages, tools, ...);
            return aiMessage;
        }
    );
}
```

### 3. before_tool_call / after_tool_call / on_tool_exception — 在 railedExecuteSingleToolCall() 中

```java
private ToolExecutionEntry railedExecuteSingleToolCall(AgentCallbackContext ctx, ToolCall toolCall, ...) {
    return RailExecutor.execute(
        ctx,
        AgentCallbackEvent.BEFORE_TOOL_CALL,    // 工具调用前
        AgentCallbackEvent.AFTER_TOOL_CALL,      // 工具调用后
        AgentCallbackEvent.ON_TOOL_EXCEPTION,    // 工具调用异常
        () -> {
            // beforeToolCall 的 Rail 可修改 toolName/toolArgs
            // （通过 ctx.inputs 修改 ToolCallInputs）
            ToolExecutionEntry result = executeSingleToolCall(toolCall, session, tag);
            return result;
        }
    );
}
```

### 4. after_task_iteration — 在 CoreTaskLoopEventExecutor 中

```java
private List<ControllerOutputChunk> executeOnce(String taskId, AgentSessionApi session) {
    try {
        Map<String, Object> result = invokeTask(effective, session);
        fireAfterTaskIteration(task, session, effective, result, null);  // 成功后触发
    } catch (RuntimeException ex) {
        fireAfterTaskIteration(task, session, effective, Map.of("error", ...), ex);  // 异常也触发
    }
}
```

**DeepAgent.fireAfterTaskIteration()** 遍历所有实现了 `TaskIterationRail` 接口的 Rail：

```java
public void fireAfterTaskIteration(TaskIterationContext ctx) {
    for (Object rail : registeredRails) {
        if (rail instanceof TaskIterationRail taskIterationRail) {
            taskIterationRail.afterTaskIteration(ctx);
        }
    }
}
```

### 5. before_task_iteration — 当前版本未实现

代码中无 `BEFORE_TASK_ITERATION` 枚举值或触发点。这是一个预留扩展点。

---

## 七、上下文传递：AgentCallbackContext

AgentCallbackContext.java 是所有钩子共享的上下文对象：

```java
AgentCallbackContext {
    agent,              // Agent 实例引用
    event,              // 当前事件类型
    inputs,             // 事件输入数据（不同事件不同类型）
    config,             // 运行时配置
    session,            // 当前 Session
    context,            // 当前 ModelContext
    extra,              // 跨 Rail 通信字典
    exception,          // 异常对象（异常事件时设置）
    retryAttempt,       // 当前重试次数
    retryRequest,       // 重试请求（Rail 可设置）
    forceFinishRequest, // 强制结束请求（Rail 可设置）
    steeringQueue       // Steering 队列（Rail 可注入纠偏指令）
}
```

### inputs 按事件类型不同

| 事件 | inputs 类型 | 关键字段 |
|------|-----------|---------|
| before/after_invoke | InvokeInputs | query, conversationId, result |
| before/after_model_call | ModelCallInputs | messages, tools, response |
| on_model_exception | ModelCallInputs | messages, tools, exception |
| before/after_tool_call | ToolCallInputs | toolName, toolArgs, toolResult, toolMsg |
| on_tool_exception | ToolCallInputs | toolName, toolArgs, exception |
| after_task_iteration | TaskIterationContext | task, round, isFollowUp, result, usageMetadata |

### Rail 可通过 ctx 实现三种控制

1. **requestRetry(delaySeconds)** — 请求重试（RailExecutor 检测到后自动重试）
2. **requestForceFinish(result)** — 请求强制结束（RailExecutor 检测到后立即返回）
3. **pushSteering(message)** — 注入纠偏指令（通过 SteeringQueue 影响下一次 LLM 调用）

---

## 八、完整执行流程图

```
DeepAgent.invoke()
  │
  ├─ fire(BEFORE_INVOKE)  ←─── 所有 Rail 的 beforeInvoke()
  │
  ├─ while (shouldContinue) {  ←─── 外循环
  │    │
  │    ├─ ReActAgent 内循环
  │    │    │
  │    │    ├─ RailExecutor.execute(BEFORE_MODEL_CALL, AFTER_MODEL_CALL, ON_MODEL_EXCEPTION)
  │    │    │    ├─ fire(BEFORE_MODEL_CALL)  ←─── 所有 Rail 的 beforeModelCall()
  │    │    │    │    [Rail 可注入 System Prompt / 模型路由 / 记忆 prefetch]
  │    │    │    ├─ model.invoke(messages, tools)
  │    │    │    └─ fire(AFTER_MODEL_CALL)   ←─── 所有 Rail 的 afterModelCall()
  │    │    │
  │    │    ├─ 解析 LLM 输出中的 tool_calls
  │    │    │
  │    │    └─ for each tool_call:
  │    │         │
  │    │         └─ RailExecutor.execute(BEFORE_TOOL_CALL, AFTER_TOOL_CALL, ON_TOOL_EXCEPTION)
  │    │              ├─ fire(BEFORE_TOOL_CALL)  ←─── 所有 Rail 的 beforeToolCall()
  │    │              │    [Rail 可拦截/修改工具参数/中断等待用户确认]
  │    │              ├─ executeSingleToolCall(toolCall)
  │    │              └─ fire(AFTER_TOOL_CALL)   ←─── 所有 Rail 的 afterToolCall()
  │    │                   [Rail 可追踪/触发进化/更新进度]
  │    │
  │    └─ drainFollowUp()  ←─── 检查追加队列
  │  }
  │
  ├─ fire(AFTER_TASK_ITERATION)  ←─── 所有 TaskIterationRail 的 afterTaskIteration()
  │    [Rail 可持久化快照/同步记忆]
  │
  └─ fire(AFTER_INVOKE)  ←─── 所有 Rail 的 afterInvoke()
       [Rail 可同步记忆/清理状态]
```

---

## 九、设计要点总结

| 设计要点 | 实现方式 |
|---------|---------|
| **可插拔** | Rail 继承 AgentRail，只需覆写感兴趣的钩子，未覆写的不注册 |
| **优先级** | 每个 Rail 声明 priority()，同事件按优先级降序执行 |
| **反射检测** | `isBaseMethod()` 判断钩子是否被覆写，避免空方法被注册 |
| **统一包装** | RailExecutor 封装 before→body→after+异常+重试，所有触发点一致 |
| **上下文共享** | AgentCallbackContext 跨 Rail 传递，extra 字典支持 Rail 间通信 |
| **控制能力** | Rail 可通过 requestRetry/requestForceFinish/pushSteering 控制执行流 |
| **工具注册** | Rail.init() 时自动将携带的 ToolCard 注册到 Agent |
| **外循环扩展** | TaskIterationRail 接口独立于 8 个基础事件，由 DeepAgent 直接遍历触发 |

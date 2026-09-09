# Agent B CalcInterruptRail ask_user 中断冒泡到用户的代码分析

> 以 "What is 1+1?" 为例，追踪 CalcInterruptRail 产生的 `ask_user` 中断如何从 Agent B 内部逐层冒泡，最终到达用户。
> 严格按代码执行轨迹梳理。

---

## 中断冒泡链路全景

```
Agent B (18091) 内部
│
① CalcInterruptRail.resolveInterrupt()
   → interrupt(InterruptRequest{context:{_interrupt_kind:"ask_user"}})
│     ↓
② JiuwenCoreAgentHandler.toQueryResponse()
   → QueryResponse{result:{_interrupt:{type,message,context,toolCallId}}}
│     ↓
③ A2AEnabledServeOrchestrator.handleQueryInterrupt()
   → isCoordinatorInterrupt() → false (非 a2a_delegate)
   → return Optional.empty() → 中断透传
│     ↓
④ A2AAgentExecutor.executeQuery()
   → emitter.requiresInput(statusMessage(interruptData))
   → Task 状态 = INPUT_REQUIRED
   → 中断数据存入 Task.status.message.metadata["_interrupt"]
│     ↓
⑤ closeEventQueue() → 等待事件排空 → 关闭队列
│     ↓
   A2A SDK 通过 JSON-RPC 返回 INPUT_REQUIRED Task 事件给 Agent A
│     ↓
⑥ A2ARemoteAgentClient.handleOutcomeStatus()
   → state.isInterrupted() → true
   → RemoteCallOutcome{state:INPUT_REQUIRED, inputPrompt:message}
│     ↓
⑦ BatchCoordinator.applyOutcome()
   → member.state = INPUT_REQUIRED
   → saveShadow("WAITING_INPUT")
   → BatchResolution{isReadyToResume:false, interrupt:publicInterrupt}
│     ↓
⑧ A2AEnabledServeOrchestrator.queryBatchResolution()
   → 构造中断 QueryResponse → 返回给 Controller
│     ↓
⑨ QueryMvcController.writeJson()
   → HTTP 200 + _interrupt JSON → 用户
```

---

## ① CalcInterruptRail 产生 ask_user 中断

文件：`service/agent-service-demo/example/a2a/src/main/java/.../CalcInterruptRail.java`

```java
// 第56行: 中断入口
@Override
protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object resumeInput) {
    String expression = extractExpression(toolCall);
    // → 从 toolCall.arguments 提取 expression = "1+1"

    if (resumeInput == null) {
        return requestConfirmation(expression);  // ← 首次调用, 请求确认
    }
    // resumeInput != null 的恢复路径见后续分析
    ...
}
```

```java
// 第66行: 构造确认中断
private InterruptDecision requestConfirmation(String expression) {
    var request = InterruptRequest.builder()
        .message(
            "Agent B is ready to calculate " + displayExpression(expression)
            + ". Continue? Reply yes or no."
        )
        // ↓↓↓ 关键: _interrupt_kind 是 "ask_user", 不是 "a2a_delegate" ↓↓↓
        .context(Map.of("_interrupt_kind", "ask_user"))
        .build();
    return interrupt(request);
    // → ReActAgent 循环暂停, 中断信息冒泡到 OutputSchema
}
```

**关键对比**：

| Rail | `_interrupt_kind` | 含义 |
|---|---|---|
| `A2aDelegateRail` | `"a2a_delegate"` | 触发远端 A2A 委派 |
| `CalcInterruptRail` | `"ask_user"` | 直接询问最终用户，不触发远端委派 |
| `FoodRecommendInterruptRail` | `"ask_user"` | 同上 |

这个 `_interrupt_kind` 值是后续 Orchestrator 路由中断的分叉依据。

---

## ② JiuwenCoreAgentHandler 归一化为 QueryResponse

文件：`service/agent-service-adapters/.../agentfw/JiuwenCoreAgentHandler.java`

中断从 agent-core-java 的 `OutputSchema`（type=`__interaction__`）冒泡到 Handler：

```java
// 第635行: 将 OutputSchema 归一化为 Map
private static Map<String, Object> toInterruptData(OutputSchema output) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("type", INTERACTION_TYPE);   // "__interaction__"
    data.put("index", output.getIndex());
    data.put("payload", output.getPayload());

    if (payload instanceof InteractionOutput io) {
        extractInteractionData(io, data);
    }
    return data;
}
```

```java
// 第648行: 从 InteractionOutput 提取中断详情
private static void extractInteractionData(InteractionOutput io, Map<String, Object> data) {
    Object value = io.getValue();
    if (value instanceof InterruptRequest req) {   // ← CalcInterruptRail 构造的 InterruptRequest
        if (req.getMessage() != null)
            data.put("message", req.getMessage());
            // → "Agent B is ready to calculate 1+1. Continue? Reply yes or no."

        if (req.getContext() != null)
            data.put("context", req.getContext());
            // → {_interrupt_kind: "ask_user"}  ← 关键: 保留 context 供 Orchestrator 判断

        if (value instanceof ToolCallInterruptRequest tcr) {
            data.put("toolCallId", tcr.getToolCallId());  // "call_yyy"
            data.put("toolName", tcr.getToolName());      // "calc"
        }
    }
}
```

```java
// 第393行: 构造含 _interrupt 的 QueryResponse
private static QueryResponse buildInterruptQueryResponse(Map<String, Object> payload, String conversationId) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("role", "assistant");
    result.put("_interrupt", new LinkedHashMap<>(payload));
    // → _interrupt = {
    //     type: "__interaction__",
    //     index: N,
    //     payload: ...,
    //     message: "Agent B is ready to calculate 1+1...",
    //     context: {_interrupt_kind: "ask_user"},
    //     toolCallId: "call_yyy",
    //     toolName: "calc"
    //   }
    result.put("content", interrupt.getOrDefault("message", ""));
    // → content = "Agent B is ready to calculate 1+1..."
    return new QueryResponse(result, conversationId);
}
```

此时 QueryResponse 含完整的 `_interrupt` 字段，返回给 Agent B 的 Orchestrator。

---

## ③ Agent B Orchestrator 判断中断类型——透传

文件：`service/agent-service-app/.../orchestrator/A2AEnabledServeOrchestrator.java`

```java
// 第136行: query 方法中断-恢复循环
public QueryResponse query(ServeRequest request) {
    while (true) {
        QueryResponse response = agentHandler.query(current);
        // → 拿到含 _interrupt 的 QueryResponse

        // 第154行: 提取中断数据
        Map<String, Object> interruptData = extractInterruptFromResponse(response);
        // → interruptData = {
        //     type: "__interaction__",
        //     message: "Agent B is ready to calculate 1+1...",
        //     context: {_interrupt_kind: "ask_user"},
        //     toolCallId: "call_yyy",
        //     toolName: "calc"
        //   }

        if (interruptData.isEmpty()) {
            return response;  // 无中断
        }

        // 第157行: 处理中断
        Optional<ServeRequest> interruptResult =
            handleQueryInterrupt(interruptData, current, response, NOOP_OBSERVER);
```

```java
// 第445行: 中断处理入口
private Optional<ServeRequest> handleQueryInterrupt(
        Map<String, Object> interruptData, ServeRequest current,
        QueryResponse response, QueryStreamObserver outputObserver) {

    // 第447行: 判断是否是 A2A 委派中断
    if (isCoordinatorInterrupt(interruptData)) {
        // a2a_delegate 路径 → batchCoordinator.execute() 远端调用
        ...
    }

    // 第454行: 检查是否有混合中断
    if (hasRemoteDelegateItem(interruptData)) {
        throw new IllegalArgumentException(MIXED_INTERRUPT_ERROR + ": mixed A2A and non-A2A interrupts");
    }

    // 第458行: 检查是否是单独的远端委派中断
    if (isRemoteDelegate(interruptData)) {
        throw new IllegalArgumentException("CORE_INTERRUPT_CORRELATION_MISSING");
    }

    // 第463行: 既不是 a2a_delegate, 也不是混合中断 → 返回 empty
    return Optional.empty();
    // ← ask_user 中断走到这里! Orchestrator 不处理, 透传给上层
}
```

```java
// 第528行: 判断是否是 A2A 委派中断
private static boolean isCoordinatorInterrupt(Map<String, Object> interrupt) {
    // 检查 items 列表 (批量中断)
    if (interrupt.get("items") instanceof List<?> items) {
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> itemMap)
                    || !(itemMap.get("context") instanceof Map<?, ?> context)
                    || !A2A_DELEGATE_KIND.equals(context.get("_interrupt_kind"))) {
                // A2A_DELEGATE_KIND = "a2a_delegate"
                // CalcInterruptRail 的 _interrupt_kind = "ask_user" ≠ "a2a_delegate"
                return false;  // ← 不匹配!
            }
        }
        return true;
    }
    // 单个中断路径
    return "__interaction__".equals(interrupt.get("type"))
        && isRemoteDelegate(interrupt)  // ← 检查 _interrupt_kind == "a2a_delegate"
        && ...;
    // isRemoteDelegate → context._interrupt_kind = "ask_user" ≠ "a2a_delegate" → false
}
// → 返回 false: 这不是 A2A 委派中断
```

```java
// 第582行: 检查 _interrupt_kind
private static boolean isRemoteDelegate(Map<String, Object> interrupt) {
    return interrupt.get("context") instanceof Map<?, ?> context
        && A2A_DELEGATE_KIND.equals(context.get("_interrupt_kind"));
        // A2A_DELEGATE_KIND = "a2a_delegate"
        // context._interrupt_kind = "ask_user" ≠ "a2a_delegate"
        // → 返回 false
}
```

**关键点**：`handleQueryInterrupt` 返回 `Optional.empty()`，意味着 Orchestrator 不做任何额外处理，QueryResponse（含 `_interrupt`）原样返回给调用者 `A2AAgentExecutor`。

---

## ④ A2AAgentExecutor 将中断映射为 A2A Task 状态

文件：`service/agent-service-app/.../controller/a2a/A2AAgentExecutor.java`

这是中断从 "内部 QueryResponse" 到 "A2A 协议 Task 状态" 的关键转换点。

```java
// 第377行: 非流式查询执行
private void executeQuery(A2AMessageContext msgCtx, RequestContext ctx, ServeRequest req, AgentEmitter emitter) {
    // 调用 Orchestrator (内部会走完 agentHandler → ReActAgent → CalcInterruptRail)
    QueryResponse response = orchestrator.query(req);

    // 检查响应是否含中断
    if (response.getResult() instanceof Map<?, ?> result
            && result.get(INTERRUPT) instanceof Map<?, ?> interruptData) {
        // INTERRUPT = "_interrupt"
        // → 有中断! interruptData = {type, message, context, toolCallId, toolName}

        log.info("A2A query interrupt detected taskId={} contextId={}",
            msgCtx.getTaskId(), msgCtx.getContextId());

        // 第383行: 将中断映射为 A2A Task 的 INPUT_REQUIRED 状态
        emitter.requiresInput(statusMessage(interruptData));

        // 第384行: 关闭事件队列 (等待 INPUT_REQUIRED 事件分发完毕)
        closeEventQueue(emitter, msgCtx.getTaskId());
    } else {
        // 无中断, 正常完成
        ...
    }
}
```

```java
// 第392行: 构造 A2A 协议的 status message
private static Message statusMessage(Map<?, ?> interruptData) {
    String message = interruptData.get("message") instanceof String text && !text.isBlank()
        ? text
        : "Input required";
    // → "Agent B is ready to calculate 1+1. Continue? Reply yes or no."

    return Message.builder()
        .role(Message.Role.ROLE_AGENT)
        .parts(List.of(new TextPart(message)))
        // ↓↓↓ 关键: 中断数据完整存入 message.metadata ↓↓↓
        .metadata(Map.of(INTERRUPT, interruptData))
        // INTERRUPT = "_interrupt"
        // → metadata = {"_interrupt": {type, message, context, toolCallId, toolName}}
        // 这些数据将在恢复时被 findStoredInterrupt() 取回
        .build();
}
```

`emitter.requiresInput(message)` 做了什么：
- A2A SDK 将 Task 状态设置为 `TASK_STATE_INPUT_REQUIRED`
- Task 的 `status.message` 设置为上述 Message（含中断 metadata）
- Task 被持久化到 TaskStore（InMemory 或 Redis）
- 通过事件总线分发 `INPUT_REQUIRED` 状态更新事件

---

## ⑤ closeEventQueue——确保 INPUT_REQUIRED 事件已分发

```java
// 第384行: 关闭事件队列
private static void closeEventQueue(AgentEmitter emitter, String taskId) {
    try {
        Optional<EventQueue> queue = emitterEventQueue(emitter);
        if (queue.isPresent()) {
            closeWhenDrained(queue.get(), taskId, CLOSE_DRAIN_TIMEOUT_MS);
            // CLOSE_DRAIN_TIMEOUT_MS = 60000 (60秒)
        }
    } catch (ReflectiveOperationException | SecurityException e) {
        log.warn("A2A closeEventQueue failed, falling back to complete() taskId={}", taskId, e);
        emitter.complete();
    }
}
```

```java
// 第423行: 等待 in-flight 事件排空后关闭
static void closeWhenDrained(EventQueue queue, String taskId, long timeoutMs) {
    if (awaitInFlightDrained(queue, taskId, timeoutMs)) {
        queue.close(false, false);
        // → close(false, false): 不改变 Task 状态, 只关闭事件队列
        // → Task 仍然是 INPUT_REQUIRED (可恢复)
        log.info("A2A eventQueue closed (INPUT_REQUIRED preserved) taskId={}", taskId);
        return;
    }
    log.warn("A2A eventQueue still has in-flight events after {}ms; "
        + "leaving it open to preserve INPUT_REQUIRED delivery taskId={}", timeoutMs, taskId);
}
```

**关键点**：
- `close(false, false)` 不改变 Task 状态，保持 `INPUT_REQUIRED`
- 必须等待事件排空后才关闭，否则 Redis 延迟可能导致 `INPUT_REQUIRED` 事件丢失
- 这是"持久化后再分发"的设计——确保客户端一定能收到中断事件

---

## ⑥ A2ARemoteAgentClient 处理远端 INPUT_REQUIRED（Agent A 侧）

Agent B 通过 A2A SDK 将 `INPUT_REQUIRED` Task 事件通过 JSON-RPC 返回给 Agent A。

文件：`service/agent-service-app/.../controller/a2a/client/A2ARemoteAgentClient.java`

```java
// 第355行: 处理 A2A SDK 事件
private void handleClientEvent(ClientEvent event, CompletableFuture<RemoteCallOutcome> result,
        RemoteAgentCaller.EventObserver eventObserver, boolean isCallbackMode, boolean isStreaming) {

    if (event instanceof TaskUpdateEvent tue) {
        if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
            // → Task 状态更新事件
            handleOutcomeStatus(sue, tue.getTask(), result, eventObserver, isCallbackMode);
        }
    }
    ...
}
```

```java
// 第407行: 处理状态更新
private void handleOutcomeStatus(TaskStatusUpdateEvent event, Task task,
        CompletableFuture<RemoteCallOutcome> result, ..., boolean isCallbackMode) {

    if (result.isDone()) return;

    TaskState state = event.status().state();
    // → TaskState.TASK_STATE_INPUT_REQUIRED (Agent B 返回的中断状态)

    eventObserver.onStatus(event);  // 转发状态给 observer

    String statusText = event.status().message() != null
        ? extractText(event.status().message().parts())
        : "";
    // → "Agent B is ready to calculate 1+1. Continue? Reply yes or no."

    completeTaskOutcome(
        new TaskOutcome(event.taskId(), state, statusText, task, remoteFailure(...)),
        result, isCallbackMode
    );
}
```

```java
// 第440行: 根据 Task 状态构造 RemoteCallOutcome
private static void completeTaskOutcome(TaskOutcome outcome,
        CompletableFuture<RemoteCallOutcome> result, boolean isCallbackMode) {

    if (result.isDone()) return;

    // 检查是否是中断状态
    if (outcome.state().isInterrupted()) {
        // TASK_STATE_INPUT_REQUIRED.isInterrupted() → true
        String inputPrompt = outcome.statusText().isBlank()
            ? "Remote agent requires input"
            : outcome.statusText();
        // → "Agent B is ready to calculate 1+1. Continue? Reply yes or no."

        result.complete(new RemoteCallOutcome(
            outcome.taskId(),                                      // "agent-b-task-xxx"
            outcome.state(),                                       // INPUT_REQUIRED
            resultCategory(outcome.state()),                       // "INPUT_REQUIRED"
            null,                                                  // result = null (无结果)
            inputPrompt                                            // 中断提示消息
        ));
        return;
    }
    // 非 interrupted 的处理...
}
```

**RemoteCallOutcome 结构**：
```
RemoteCallOutcome {
    taskId: "agent-b-task-xxx",
    remoteState: TASK_STATE_INPUT_REQUIRED,
    resultCategory: "INPUT_REQUIRED",
    result: null,
    inputPrompt: "Agent B is ready to calculate 1+1. Continue? Reply yes or no."
}
```

---

## ⑦ BatchCoordinator 处理 INPUT_REQUIRED 结果

文件：`service/agent-service-app/.../orchestrator/RemoteInvocationBatchCoordinator.java`

```java
// finishInvocation → applyOutcome
// RemoteInvocationBatchMapper.java#L99
void applyOutcome(Member member, RemoteCallOutcome outcome, Throwable error) {
    member.completedAt = Instant.now();

    // 检查远端状态
    if (outcome.remoteState() == TaskState.TASK_STATE_INPUT_REQUIRED
            || outcome.remoteState() == TaskState.TASK_STATE_AUTH_REQUIRED) {

        member.state = MemberState.INPUT_REQUIRED;  // ← Member 进入等待状态
        member.inputPrompt = outcome.inputPrompt() == null
            ? "Remote agent requires input"
            : outcome.inputPrompt();
        // → "Agent B is ready to calculate 1+1. Continue? Reply yes or no."

        member.remoteTaskId = outcome.remoteTaskId();
        // → "agent-b-task-xxx" ← 记住 Agent B 的 Task ID, 供恢复时使用
    }
    ...
}
```

```java
// RemoteInvocationBatchCoordinator.java#L418
private void finishBatchIfSettled(RemoteInvocationBatch batch) {
    if (!state.settle(batch)) return;

    BatchResolution resolution = resolveSettledBatch(batch);
    // ↓
}
```

```java
// RemoteInvocationBatchCoordinator.java#L433
private BatchResolution resolveSettledBatch(RemoteInvocationBatch batch) {
    boolean hasWaitingMember = batch.members.stream()
        .anyMatch(member -> member.state == MemberState.INPUT_REQUIRED);
    // → true (member 状态为 INPUT_REQUIRED)

    if (hasWaitingMember) {
        saveShadow(batch, "WAITING_INPUT");
        // → 持久化 shadow Task:
        //   Task{id:"shadow:a2a-test-1", status:INPUT_REQUIRED,
        //     metadata._remote_batch.members[0].state="INPUT_REQUIRED",
        //     metadata._remote_batch.members[0].remoteTaskId="agent-b-task-xxx"}

        return batchMapper.resolution(batch);
    }
    ...
}
```

```java
// RemoteInvocationBatchMapper.java#L188
BatchResolution resolution(RemoteInvocationBatch batch) {
    boolean hasWaitingMember = batch.members.stream()
        .anyMatch(member -> member.state == MemberState.INPUT_REQUIRED);

    if (hasWaitingMember) {
        // 构造给客户端的中断
        return new BatchResolution(
            batch.batchId,
            false,                 // isReadyToResume = false (远端等输入)
            Map.of(),              // results = 空
            publicInterrupt(batch),// interrupt = 构造的中断数据
            batch.shouldResume     // shouldResume = true (tool-call 路径)
        );
    }
    ...
}
```

```java
// RemoteInvocationBatchMapper.java#L248: 构造给用户的公开中断
private static Map<String, Object> publicInterrupt(RemoteInvocationBatch batch) {
    List<Map<String, Object>> items = new ArrayList<>();
    for (Member member : batch.members) {
        if (member.state != MemberState.INPUT_REQUIRED) continue;

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("toolCallId", member.toolCallId);
        item.put("toolName", member.toolName);     // "delegate_to_agentb"
        item.put("message", member.inputPrompt);
        // → "Agent B is ready to calculate 1+1. Continue? Reply yes or no."
        items.add(item);
    }

    Map<String, Object> interrupt = new LinkedHashMap<>();
    interrupt.put("type", "__interaction__");
    interrupt.put("state", "input_required");
    interrupt.put("message",
        items.size() == 1 ? items.get(0).get("message") : "Multiple remote agents require input");
    interrupt.put("items", items);
    return interrupt;
    // ↑ 注意: context (含 _interrupt_kind, agentName) 被剥离!
    //   用户看到的是干净的 message + items, 不含内部路由信息
}
```

---

## ⑧ Orchestrator 构造中断 QueryResponse 返回给用户

文件：`A2AEnabledServeOrchestrator.java`

```java
// 第470行: queryBatchResolution (非流式)
private QueryResumeResult queryBatchResolution(ServeRequest current,
        BatchResolution resolution, QueryResponse response) {

    if (resolution.isReadyToResume()) {
        // 远端完成 → 构造恢复请求
        ...
    }

    // isReadyToResume = false (远端 INPUT_REQUIRED)
    // 构造中断响应返回给用户
    Map<String, Object> result = new LinkedHashMap<>();
    if (response != null && response.getResult() instanceof Map<?, ?> existing) {
        existing.forEach((key, value) -> result.put(String.valueOf(key), value));
    } else {
        result.put("role", "assistant");
    }
    result.put("content", resolution.interrupt().getOrDefault("message", "Remote agent requires input"));
    // → content = "Agent B is ready to calculate 1+1. Continue? Reply yes or no."

    result.put("_interrupt", resolution.interrupt());
    // → _interrupt = {
    //     type: "__interaction__",
    //     state: "input_required",
    //     message: "Agent B is ready to calculate 1+1...",
    //     items: [{toolCallId, toolName:"delegate_to_agentb", message:"..."}]
    //   }

    return QueryResumeResult.respond(
        new QueryResponse(result, current.getConversationId())
    );
    // → 返回中断 QueryResponse, Orchestrator 循环结束
}
```

---

## ⑨ QueryMvcController 返回 HTTP 响应

文件：`service/agent-service-app/.../controller/query/QueryMvcController.java`

```java
// handleQuery 方法 (第103行)
QueryResponse queryResponse = orchestrator.query(validation.serveRequest());
// → 拿到含 _interrupt 的 QueryResponse

writeJson(response, HttpStatus.OK.value(), queryResponse);
// → HTTP 200, JSON 响应
```

**用户收到的响应**：

```json
{
  "result": {
    "role": "assistant",
    "_interrupt": {
      "type": "__interaction__",
      "state": "input_required",
      "message": "Agent B is ready to calculate 1+1. Continue? Reply yes or no.",
      "items": [
        {
          "toolCallId": "call_xxx",
          "toolName": "delegate_to_agentb",
          "message": "Agent B is ready to calculate 1+1. Continue? Reply yes or no."
        }
      ]
    },
    "content": "Agent B is ready to calculate 1+1. Continue? Reply yes or no."
  },
  "conversation_id": "a2a-test-1"
}
```

---

## 中断数据在各层中的格式变化

```
CalcInterruptRail
  InterruptRequest {
    message: "Agent B is ready to calculate 1+1...",
    context: {_interrupt_kind: "ask_user"}       ← 有 context
  }
         ↓
JiuwenCoreAgentHandler
  QueryResponse {
    result: {
      _interrupt: {
        type: "__interaction__",
        message: "...",
        context: {_interrupt_kind: "ask_user"},  ← 保留 context
        toolCallId: "call_yyy",
        toolName: "calc"
      }
    }
  }
         ↓
A2AEnabledServeOrchestrator (Agent B)
  → isCoordinatorInterrupt → false (非 a2a_delegate)
  → 透传, 不改变格式
         ↓
A2AAgentExecutor
  → emitter.requiresInput(Message{
      role: ROLE_AGENT,
      parts: [TextPart("...")],
      metadata: {"_interrupt": {完整中断数据}}   ← 存入 Task metadata
    })
  → Task.status.state = INPUT_REQUIRED
         ↓
A2A SDK JSON-RPC 传输
  Task 事件 {
    status: {
      state: INPUT_REQUIRED,
      message: {role, parts, metadata}
    }
  }
         ↓
A2ARemoteAgentClient (Agent A)
  RemoteCallOutcome {
    state: INPUT_REQUIRED,
    inputPrompt: "..."                           ← 只保留 message 文本
  }
         ↓
BatchCoordinator
  Member {
    state: INPUT_REQUIRED,
    inputPrompt: "...",
    remoteTaskId: "agent-b-task-xxx"             ← 记住远端 taskId
  }
  → saveShadow (持久化完整 batch 快照)
         ↓
BatchResolution
  interrupt: publicInterrupt {
    type: "__interaction__",
    state: "input_required",
    message: "...",
    items: [{toolCallId, toolName, message}]    ← context 已剥离
  }
         ↓
用户收到的 HTTP 响应
  _interrupt {
    type, state, message, items                  ← 干净的, 无内部 context
  }
```

---

## 关键源码文件索引

| 文件 | 在冒泡过程中的角色 |
|---|---|
| [CalcInterruptRail.java](src/main/java/com/openjiuwen/service/demo/example/a2a/CalcInterruptRail.java) | 产生 ask_user 中断, message 含确认提示 |
| [JiuwenCoreAgentHandler.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/agentfw/JiuwenCoreAgentHandler.java) | 归一化: InterruptRequest → QueryResponse._interrupt |
| [A2AEnabledServeOrchestrator.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/A2AEnabledServeOrchestrator.java) | 中断路由: ask_user ≠ a2a_delegate → 透传 |
| [A2AAgentExecutor.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/controller/a2a/A2AAgentExecutor.java) | 协议转换: QueryResponse._interrupt → Task INPUT_REQUIRED |
| [A2ARemoteAgentClient.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/controller/a2a/client/A2ARemoteAgentClient.java) | 远端事件处理: INPUT_REQUIRED → RemoteCallOutcome |
| [RemoteInvocationBatchMapper.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/RemoteInvocationBatchMapper.java) | 结果映射: RemoteCallOutcome → MemberState, publicInterrupt |
| [RemoteInvocationBatchCoordinator.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/RemoteInvocationBatchCoordinator.java) | 状态管理: saveShadow, resolveSettledBatch |

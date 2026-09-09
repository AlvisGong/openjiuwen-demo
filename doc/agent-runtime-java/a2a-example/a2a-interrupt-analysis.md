# A2A 跨 Agent 中断过程深度分析

> 以 "What is 1+1?" 为例，分析中断从 Agent B 的 CalcInterruptRail 产生，
> 经 Agent B Orchestrator → A2A SDK → Agent A Orchestrator → 最终到达用户的完整传递过程，
> 以及用户回复 "yes" 后的恢复路径。严格按代码执行轨迹梳理。

---

## 中断链路全景

```
Agent B (18091)                                    Agent A (18090)                用户
│                                                  │                             │
│  CalcInterruptRail.resolveInterrupt()            │                             │
│  interrupt(_interrupt_kind="ask_user")           │                             │
│       ↓                                          │                             │
│  JiuwenCoreAgentHandler                          │                             │
│  → QueryResponse{_interrupt}                     │                             │
│       ↓                                          │                             │
│  Agent B Orchestrator                            │                             │
│  handleQueryInterrupt()                         │                             │
│  → 非 a2a_delegate → 透传                       │                             │
│       ↓                                          │                             │
│  A2AAgentExecutor.executeQuery()                 │                             │
│  → emitter.requiresInput(statusMessage)          │                             │
│  → Task 状态 = INPUT_REQUIRED                    │                             │
│  → 中断数据存入 Task.status.message.metadata     │                             │
│       ↓                                          │                             │
│  A2A SDK 返回 Task 事件                          │                             │
│  (TaskState.INPUT_REQUIRED)                     │                             │
│       ─────────────────────────────────────→    │                             │
│                                                  │ A2ARemoteAgentClient        │
│                                                  │ handleOutcomeStatus()      │
│                                                  │ → state.isInterrupted()    │
│                                                  │ → RemoteCallOutcome(       │
│                                                  │     INPUT_REQUIRED,        │
│                                                  │     inputPrompt)           │
│                                                       ↓                      │
│                                                  │ BatchCoordinator           │
│                                                  │ applyOutcome()            │
│                                                  │ → member.state=           │
│                                                  │   INPUT_REQUIRED          │
│                                                  │ → saveShadow(WAITING_INPUT)│
│                                                  │ → BatchResolution(        │
│                                                  │     isReadyToResume=false)│
│                                                       ↓                      │
│                                                  │ Orchestrator              │
│                                                  │ queryBatchResolution()    │
│                                                  │ → 构造中断 QueryResponse    │
│                                                       ↓                      │
│                                                  │ QueryMvcController        │
│                                                  │ → HTTP 200 + _interrupt   │
│                                                       ──────────────────→     │
│                                                                              │
│  用户收到 "Continue? Reply yes or no."                                        │
│                                                                              │
│  用户回复 "yes" ─────────────────────────────────────────────────────→       │
│                                                  │                             │
│                                                  │ Orchestrator.query()       │
│                                                  │ syncResumePending()        │
│                                                  │ → batchCoordinator.resume()│
│                                                       ──────────────────────→│
│  A2A SDK 恢复 Task (message="yes", taskId=...)  │                             │
│  A2AAgentExecutor.execute()                      │                             │
│  → isInputRequiredResume=true                    │                             │
│  → findStoredInterrupt(task) → 恢复中断上下文     │                             │
│  → Orchestrator.query()                         │                             │
│  → agentHandler.query()                         │                             │
│  → Runner.runAgent()                            │                             │
│  → CalcInterruptRail.resolveInterrupt(           │                             │
│      resumeInput="yes")                         │                             │
│  → AFFIRMATIVE → calculate("1+1") → "2"          │                             │
│  → reject(result) → LLM 生成回答                 │                             │
│  → TaskState.COMPLETED                          │                             │
│       ─────────────────────────────────────→    │                             │
│                                                  │ RemoteCallOutcome(         │
│                                                  │   COMPLETED, "结果2")       │
│                                                       ↓                      │
│                                                  │ buildBatchResumeRequest() │
│                                                  │ metadata.remoteToolResults│
│                                                       ↓                      │
│                                                  │ agentHandler.query()      │
│                                                  │ → A2aDelegateRail         │
│                                                  │   reject(result)          │
│                                                  │ → LLM 原样输出             │
│                                                       ──────────────────→     │
│  用户收到 "The result of 1+1 is **2**."                                      │
```

---

## 第一阶段：中断的产生（Agent B 侧）

### 1.1 CalcInterruptRail 触发中断

Agent B 的 ReActAgent 收到 "What is 1+1?" 后，LLM 调用 `calc` 工具。CalcInterruptRail 拦截：

```
CalcInterruptRail.resolveInterrupt(ctx, toolCall, resumeInput=null)
文件: CalcInterruptRail.java#L56
│
├── resumeInput == null → true (首次调用)
│
├── extractExpression(toolCall)
│   → 从 toolCall.arguments 解析出 expression = "1+1"
│
└── requestConfirmation("1+1")                      ← CalcInterruptRail.java#L66
    │
    ├── InterruptRequest.builder()
    │   .message("Agent B is ready to calculate 1+1. Continue? Reply yes or no.")
    │   .context(Map.of("_interrupt_kind", "ask_user"))  ← 注意: 不是 "a2a_delegate"
    │   .build()
    │
    └── return interrupt(request)
        → ReActAgent 循环暂停
```

**关键点**：`_interrupt_kind = "ask_user"` 而非 `"a2a_delegate"`。这决定了中断不会被远端委派处理，而是直接冒泡给最终用户。

### 1.2 JiuwenCoreAgentHandler 归一化为 QueryResponse

中断从 agent-core-java 的 OutputSchema 冒泡到 JiuwenCoreAgentHandler：

```
JiuwenCoreAgentHandler.toQueryResponse(rawResult, conversationId)
文件: JiuwenCoreAgentHandler.java#L330
│
├── getQueryResponse(conversationId, map)            ← JiuwenCoreAgentHandler.java#L367
│   ├── map.get("result_type") == "interrupt"
│   ├── map.get("state") instanceof List → true
│   ├── normalizeChunk(state) → 归一化
│   ├── isCoreInteraction → true (type=="__interaction__")
│   ├── extractInteractionData(io, data)             ← JiuwenCoreAgentHandler.java#L640
│   │   ├── 提取 message: "Agent B is ready to calculate 1+1..."
│   │   ├── 提取 context: {_interrupt_kind: "ask_user"}
│   │   └── 提取 toolCallId, toolName
│   │
│   └── buildInterruptQueryResponse(interrupts, convId)  ← JiuwenCoreAgentHandler.java#L393
│       → result = {role:"assistant", _interrupt:{...}, content: message}
│
└── 返回 QueryResponse 给 Agent B 的 Orchestrator
```

### 1.3 Agent B Orchestrator 判断中断类型——透传

```
A2AEnabledServeOrchestrator.query(request) (Agent B 内部)
文件: A2AEnabledServeOrchestrator.java#L136
│
├── agentHandler.query(current) → 返回含 _interrupt 的 QueryResponse
│
├── extractInterruptFromResponse(response) → interruptData 非空
│
└── handleQueryInterrupt(interruptData, current, response, NOOP_OBSERVER)
    文件: A2AEnabledServeOrchestrator.java#L445
    │
    ├── isCoordinatorInterrupt(interruptData)         ← A2AEnabledServeOrchestrator.java#L528
    │   │
    │   ├── interruptData.get("items") → 检查是否是批量中断
    │   │   ├── 如果是 List → 遍历 items 检查 context._interrupt_kind
    │   │   │   └── item.context._interrupt_kind == "ask_user" != "a2a_delegate"
    │   │   │       → 返回 false (不是 A2A 委派中断)
    │   │   │
    │   │   └── 如果不是 List (单个中断) → 检查 isRemoteDelegate
    │   │       └── interruptData.context._interrupt_kind == "ask_user"
    │   │           != "a2a_delegate" → 返回 false
    │   │
    │   └── 返回 false  ← 关键: 这是 ask_user 中断, 不触发远端委派
    │
    ├── hasRemoteDelegateItem(interruptData) → false (无 a2a_delegate item)
    ├── isRemoteDelegate(interruptData) → false
    │
    └── return Optional.empty()
        → 中断不是 A2A 委派, Orchestrator 不处理
        → QueryResponse 原样返回给 A2AAgentExecutor
```

**关键点**：Orchestrator 的 `isCoordinatorInterrupt()` 通过检查 `context._interrupt_kind` 区分中断类型。`"a2a_delegate"` 触发远端委派逻辑，`"ask_user"` 不触发——这是中断路由的核心分叉点。

### 1.4 A2AAgentExecutor 将中断映射为 A2A Task 状态

```
A2AAgentExecutor.executeQuery(msgCtx, ctx, req, emitter)
文件: A2AAgentExecutor.java#L377
│
├── orchestrator.query(req) → 返回 QueryResponse
│   → response.getResult() instanceof Map → true
│   → result.get("_interrupt") instanceof Map → true (有中断!)
│
├── log.info("A2A query interrupt detected...")
│
├── emitter.requiresInput(statusMessage(interruptData))  ← A2AAgentExecutor.java#L383
│   │
│   │   statusMessage(interruptData)                     ← A2AAgentExecutor.java#L392
│   │   │
│   │   ├── message = interruptData.get("message")
│   │   │   = "Agent B is ready to calculate 1+1. Continue? Reply yes or no."
│   │   │
│   │   └── Message.builder()
│   │       .role(ROLE_AGENT)
│   │       .parts([TextPart("Agent B is ready to calculate 1+1...")])
│   │       .metadata(Map.of("_interrupt", interruptData))  ← 中断数据存入 metadata
│   │       .build()
│   │
│   │   emitter.requiresInput(message):
│   │   → A2A SDK 将 Task 状态设置为 TASK_STATE_INPUT_REQUIRED
│   │   → Task.status.message = 上述 Message (含中断 metadata)
│   │   → Task 被持久化 (InMemoryTaskStore 或 RedisTaskStore)
│   │
│   └── 这一步是中断从 "内部 QueryResponse" 到 "A2A Task 状态" 的关键转换
│
└── closeEventQueue(emitter, msgCtx.getTaskId())  ← A2AAgentExecutor.java#L384
    │
    ├── 等待 in-flight 事件排空 (确保 INPUT_REQUIRED 事件已持久化和分发)
    │
    └── queue.close(false, false)
        → 关闭事件队列, 不改变 Task 状态 (保持 INPUT_REQUIRED)
        → SSE 流终止, 但 Task 仍然是 INPUT_REQUIRED (可恢复)
```

文件：`service/agent-service-app/.../controller/a2a/A2AAgentExecutor.java`

**关键点**：
1. `emitter.requiresInput()` 是中断从内部格式到 A2A 协议的转换点——将 Task 状态设为 `INPUT_REQUIRED`
2. 中断的完整数据（message、context、toolCallId 等）存储在 `Task.status.message.metadata["_interrupt"]` 中，供恢复时取回
3. `closeEventQueue` 确保 INPUT_REQUIRED 事件已分发后才关闭流，避免 Redis 延迟导致事件丢失

### 1.5 A2A SDK 返回 INPUT_REQUIRED 事件给 Agent A

Agent B 的 A2A SDK 通过 JSON-RPC 将 Task 状态事件返回给 Agent A 的 `A2ARemoteAgentClient`：

```
Agent B A2A SDK → HTTP Response (SSE or JSON)
  Task 事件: {
    taskId: "agent-b-task-xxx",
    status: {
      state: TASK_STATE_INPUT_REQUIRED,
      message: {
        role: ROLE_AGENT,
        parts: [{text: "Agent B is ready to calculate 1+1. Continue? Reply yes or no."}],
        metadata: {"_interrupt": {type, state, message, items, context}}
      }
    }
  }
```

---

## 第二阶段：中断的传递（Agent A 侧接收）

### 2.1 A2ARemoteAgentClient 处理远端 INPUT_REQUIRED

```
A2ARemoteAgentClient.handleClientEvent(event, result, eventObserver, ...)
文件: A2ARemoteAgentClient.java#L355
│
├── event instanceof TaskUpdateEvent
│   └── tue.getUpdateEvent() instanceof TaskStatusUpdateEvent
│       └── handleOutcomeStatus(sue, task, result, ...)
│           文件: A2ARemoteAgentClient.java#L407
│           │
│           ├── state = event.status().state()
│           │   = TaskState.TASK_STATE_INPUT_REQUIRED
│           │
│           ├── statusText = extractText(event.status().message().parts())
│           │   = "Agent B is ready to calculate 1+1. Continue? Reply yes or no."
│           │
│           └── completeTaskOutcome(outcome, result, isCallbackMode)
│               文件: A2ARemoteAgentClient.java#L440
│               │
│               ├── outcome.state() = TASK_STATE_INPUT_REQUIRED
│               │
│               ├── outcome.state().isInterrupted() → true
│               │   (INPUT_REQUIRED 属于 interrupted 状态)
│               │
│               ├── inputPrompt = outcome.statusText()
│               │   = "Agent B is ready to calculate 1+1..."
│               │
│               └── result.complete(new RemoteCallOutcome(
│                     taskId: "agent-b-task-xxx",
│                     state: TaskState.TASK_STATE_INPUT_REQUIRED,
│                     resultCategory: "INPUT_REQUIRED",
│                     result: null,          ← 无结果文本
│                     inputPrompt: "Agent B is ready to calculate 1+1..."
│                   ))
```

文件：`service/agent-service-app/.../controller/a2a/client/A2ARemoteAgentClient.java`

### 2.2 BatchCoordinator 处理 INPUT_REQUIRED 结果

```
finishInvocation(invocation, outcome, error=null)
文件: RemoteInvocationBatchCoordinator.java#L421
│
├── state.finishInvocation(invocation, () -> batchMapper.applyOutcome(member, outcome, null))
│   │
│   └── batchMapper.applyOutcome(member, outcome, null)
│       文件: RemoteInvocationBatchMapper.java#L99
│       │
│       ├── member.completedAt = Instant.now()
│       ├── outcome.remoteState() = TASK_STATE_INPUT_REQUIRED
│       │
│       ├── state.isInterrupted() → true
│       │
│       ├── member.state = MemberState.INPUT_REQUIRED  ← Member 进入等待状态
│       │
│       └── member.inputPrompt = outcome.inputPrompt()
│           = "Agent B is ready to calculate 1+1..."
│
└── finishBatchIfSettled(batch)                        ← RemoteInvocationBatchCoordinator.java#L418
    │
    ├── state.settle(batch) → true (所有 member 已结束)
    │
    └── resolveSettledBatch(batch)                    ← RemoteInvocationBatchCoordinator.java#L433
        │
        ├── hasWaitingMember → true (member.state == INPUT_REQUIRED)
        │
        ├── saveShadow(batch, "WAITING_INPUT")       ← RemoteInvocationBatchCoordinator.java#L443
        │   │
        │   │   构造 shadow Task 快照:
        │   │   Task {
        │   │     id: "shadow:a2a-test-1",
        │   │     contextId: "a2a-test-1",
        │   │     status: TASK_STATE_INPUT_REQUIRED,
        │   │     metadata: {
        │   │       "_remote_batch": {
        │   │         batchId: "xxx",
        │   │         parentTaskId: "a2a-test-1",
        │   │         resume: true,
        │   │         state: "WAITING_INPUT",
        │   │         request: {conversationId, messages, metadata, ...},
        │   │         members: [{
        │   │           index: 0,
        │   │           toolCallId: "call_xxx",
        │   │           toolName: "delegate_to_agentb",
        │   │           agentName: "agentb",
        │   │           state: "INPUT_REQUIRED",
        │   │           inputPrompt: "Agent B is ready to calculate 1+1...",
        │   │           remoteTaskId: "agent-b-task-xxx"  ← 关键: 记住 Agent B 的 Task ID
        │   │         }]
        │   │       }
        │   │     }
        │   │   }
        │   │
        │   └── taskStore.save(shadowTask, true)
        │       → Shadow Task 持久化 (用于后续恢复)
        │
        └── batchMapper.resolution(batch)             ← RemoteInvocationBatchMapper.java#L188
            │
            ├── hasWaitingMember → true
            │
            ├── publicInterrupt(batch)                ← RemoteInvocationBatchMapper.java#L248
            │   │   构造给客户端的中断:
            │   │   {
            │   │     type: "__interaction__",
            │   │     state: "input_required",
            │   │     message: "Agent B is ready to calculate 1+1...",
            │   │     items: [{
            │   │       toolCallId: "call_xxx",
            │   │       toolName: "delegate_to_agentb",
            │   │       message: "Agent B is ready to calculate 1+1..."
            │   │     }]
            │   │   }
            │   │   (注意: context 中的 _interrupt_kind 和 agentName 被剥离, 不暴露给用户)
            │   │
            │   └── return BatchResolution(
            │         batchId: "xxx",
            │         isReadyToResume: false,        ← 远端还在等输入
            │         results: {},                    ← 无结果
            │         interrupt: publicInterrupt,     ← 中断数据
            │         shouldResume: true             ← tool-call 路径, 需要回喂 Agent A
            │       )
```

**关键点**：
1. `saveShadow()` 将 batch 的完整状态（含 Agent B 的 remoteTaskId）持久化为 shadow Task，这是恢复的基础
2. `publicInterrupt()` 剥离了内部 context（agentName、_interrupt_kind），只暴露用户需要的信息

### 2.3 Orchestrator 将中断返回给用户

```
A2AEnabledServeOrchestrator.query() (续)
│
├── queryBatchResolution(current, resolution, response)
│   文件: A2AEnabledServeOrchestrator.java#L470
│   │
│   ├── resolution.isReadyToResume() → false (远端 INPUT_REQUIRED)
│   │
│   └── 构造中断 QueryResponse:
│       result = {
│         role: "assistant",
│         _interrupt: resolution.interrupt(),  ← 远端中断透传
│         content: resolution.interrupt().getOrDefault("message", "...")
│       }
│       → return QueryResumeResult.respond(interruptResponse)
│
├── return response
│
└── QueryMvcController:
    writeJson(response, 200, queryResponse)
    → HTTP 200 + _interrupt JSON
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
      "items": [{"toolCallId":"call_xxx", "toolName":"delegate_to_agentb", "message":"..."}]
    },
    "content": "Agent B is ready to calculate 1+1. Continue? Reply yes or no."
  },
  "conversation_id": "a2a-test-1"
}
```

---

## 中断状态映射表

中断在传递过程中经过多次格式转换：

```
Agent B CalcInterruptRail     →  InterruptRequest
                                  {message, context:{_interrupt_kind:"ask_user"}}

         ↓ JiuwenCoreAgentHandler.toQueryResponse()

Agent B QueryResponse         →  {role:"assistant", _interrupt:{type,state,message,items,context}}

         ↓ A2AEnabledServeOrchestrator (非 a2a_delegate → 透传)

Agent B A2AAgentExecutor      →  emitter.requiresInput(statusMessage)
                                  Task.status.state = INPUT_REQUIRED
                                  Task.status.message.metadata["_interrupt"] = 中断数据

         ↓ A2A SDK JSON-RPC 传输

Agent A A2ARemoteAgentClient  →  RemoteCallOutcome
                                  {state:INPUT_REQUIRED, inputPrompt:message}

         ↓ BatchCoordinator.applyOutcome()

Agent A Member state          →  MemberState.INPUT_REQUIRED
                                  member.inputPrompt = message
                                  member.remoteTaskId = Agent B 的 taskId

         ↓ saveShadow()

Agent A Shadow Task          →   Task{status:INPUT_REQUIRED,
                                  metadata._remote_batch.members[0].state="INPUT_REQUIRED"}

         ↓ BatchResolution

Agent A Orchestrator          →  QueryResponse{_interrupt: publicInterrupt}
                                  (context 剥离, 仅暴露 message/items)

         ↓ QueryMvcController

用户收到                     →   HTTP 200 JSON {_interrupt}
```

---

## 第三阶段：用户回复 "yes" 的恢复过程

### 3.1 Agent A Orchestrator 检测到恢复请求

```
POST http://localhost:18090/v1/query
Body: {"conversation_id":"a2a-test-1","message":"yes","stream":false}

A2AEnabledServeOrchestrator.query(request)
│
├── syncResumePending(current, NOOP_OBSERVER)       ← A2AEnabledServeOrchestrator.java#L340
│   │
│   ├── isClientToolResume(current) → false
│   │   (metadata 中无 _interrupt key)
│   │
│   ├── batchCoordinator.resume(current, observer)   ← RemoteInvocationBatchCoordinator.java#L200
│   │   │
│   │   ├── request.metadata.get("runtime.remoteToolResults") → null
│   │   │   (用户直接回复, 不是远端结果回喂)
│   │   │
│   │   ├── request.metadata.get("runtime.remoteBatchId") → 可能为空
│   │   │
│   │   ├── parentTaskId = parentTaskId(request) → "a2a-test-1"
│   │   │
│   │   ├── shadow = taskStore.get("shadow:a2a-test-1")  ← 查找 shadow Task
│   │   │   → 找到! (之前 saveShadow 时创建的)
│   │   │
│   │   ├── shadowState = rawBatch.get("state") → "WAITING_INPUT"
│   │   │   → 不是 "READY_TO_RESUME", 走 resumeWaitingBatch
│   │   │
│   │   └── resumeWaitingBatch(batch, targetedInputs, parentTaskId, lastUserQuery="yes")
│   │       ← RemoteInvocationBatchCoordinator.java#L294
│   │       │
│   │       ├── pending = members.filter(state == INPUT_REQUIRED)
│   │       │   → [{toolCallId:"call_xxx", agentName:"agentb", remoteTaskId:"agent-b-task-xxx"}]
│   │       │
│   │       ├── targetedInputs 为空 → 只有1个 pending member
│   │       │   → effectiveInputs = {"call_xxx": "yes"}  ← 用户回复绑定到 toolCallId
│   │       │
│   │       ├── 更新 member:
│   │       │   member.message = "yes"          ← 要发送给 Agent B 的消息
│   │       │   member.state = MemberState.QUEUED
│   │       │   member.queuedAt = Instant.now()
│   │       │
│   │       ├── state.registerBatch(batch) → true
│   │       │
│   │       └── selected.forEach(member → submit(new PendingInvocation(batch, member)))
│   │           → 提交远端恢复调用
│   │
│   │   → 返回 Optional.of(batch.completion)  ← 有待恢复的远端任务
│   │
│   └── 等待 batch.completion 完成...
│       (远端调用进行中)
```

### 3.2 Agent A 发起远端 Task 恢复

```
submit(invocation)
← RemoteInvocationBatchCoordinator.java#L370
│
├── state.submit(invocation) → Submission.START (获取并发槽位)
│
└── start(invocation)                                ← RemoteInvocationBatchCoordinator.java#L380
    │
    ├── 构造 RemoteCall:
    │   call = new RemoteCall(
    │     agentName: "agentb",
    │     message: "yes",                     ← 用户的确认消息
    │     contextId: remoteContextId,
    │     taskId: member.remoteTaskId,        ← "agent-b-task-xxx" 恢复已有 Task!
    │     metadata: ...,
    │     isStream: true
    │   )
    │
    └── client.callOutcome(call, eventObserver)
        ← A2ARemoteAgentClient.java#L260
        │
        ├── prepareCall(call)                         ← A2ARemoteAgentClient.java#L155
        │   └── buildSendParams(call, contextId)
        │       ← A2ARemoteAgentClient.java#L167
        │       │
        │       ├── Message.builder()
        │       │   .role(ROLE_USER)
        │       │   .contextId(contextId)
        │       │   .parts([TextPart("yes")])     ← 用户的 "yes" 作为消息内容
        │       │   .taskId("agent-b-task-xxx")   ← 指定恢复已有的 Task!
        │       │
        │       └── MessageSendConfiguration.builder()
        │           .returnImmediately(false)    ← 同步等待
        │
        └── client.sendMessage(params, ...)
            → POST http://localhost:18091/a2a (JSON-RPC)
              method = "message/send"
              params.message.taskId = "agent-b-task-xxx"  ← 恢复已有 Task
              params.message.parts[0].text = "yes"
```

### 3.3 Agent B 恢复执行——CalcInterruptRail 确认并计算

```
Agent B: DefaultRequestHandler → A2AAgentExecutor.execute(ctx, emitter)
文件: A2AAgentExecutor.java#L131
│
├── A2AMessageContext.from(ctx)
│   → 提取消息: parts[0].text = "yes"
│   → 提取 taskId: "agent-b-task-xxx"
│
├── adapter.toServeRequest(msgCtx)
│   → ServeRequest {conversation_id, messages:[{role:"user", content:"yes"}]}
│
├── task = ctx.getTask()  → Task 对象 (状态 = INPUT_REQUIRED)
│
├── isInputRequiredResume = true                      ← A2AAgentExecutor.java#L137
│   (task.status().state() == TASK_STATE_INPUT_REQUIRED)
│
├── findStoredInterrupt(task)                         ← A2AAgentExecutor.java#L399
│   │
│   │   从 Task 的 status.message.metadata 中提取 _interrupt:
│   │   task.status().message().metadata().get("_interrupt")
│   │   → 返回之前存储的完整中断数据
│   │     {type, state, message, items, context:{_interrupt_kind:"ask_user"}}
│   │
│   └── metadata.put("_interrupt", storedInterrupt)  ← 恢复中断上下文!
│       → ServeRequest 的 metadata 中注入了中断数据
│
└── executeRequest(ctx, msgCtx, req, emitter, isNewTask=false)
    ├── emitter.startWork()  (不创建新 Task, 恢复已有)
    │
    └── executeQuery(msgCtx, ctx, req, emitter)
        │
        └── orchestrator.query(req)
            │
            ├── agentHandler.query(req)
            │   │
            │   ├── JiuwenCoreAgentHandler.query(req)
            │   │   ├── buildInputs(req)
            │   │   │   → request.metadata.get("_interrupt") → 存在!
            │   │   │   → request.metadata.get("runtime.remoteToolResults") → 可能有
            │   │   │   → inputs.put("query", "yes") 或 InteractiveInput
            │   │   │
            │   │   └── Runner.runAgent(agent, inputs, session, null)
            │   │       → ReActAgent 恢复执行
            │   │       │
            │   │       │  CalcInterruptRail.resolveInterrupt(ctx, toolCall, resumeInput="yes")
            │   │       │  文件: CalcInterruptRail.java#L56
            │   │       │  │
            │   │       │  ├── resumeInput != null → true  ← 有恢复输入!
            │   │       │  │
            │   │       │  ├── normalizeConfirmation("yes") → "yes"
            │   │       │  │   = String.valueOf("yes").trim().toLowerCase().replaceFirst("[.!]$","")
            │   │       │  │
            │   │       │  ├── AFFIRMATIVE_RESPONSES.contains("yes") → true
            │   │       │  │   AFFIRMATIVE = {"ok","yes","y","confirm","confirmed",
            │   │       │  │                 "approve","approved","continue","proceed"}
            │   │       │  │
            │   │       │  └── return reject(calculate("1+1"))
            │   │       │      ← CalcInterruptRail.java#L62
            │   │       │      │
            │   │       │      ├── calculate("1+1")         ← CalcInterruptRail.java#L73
            │   │       │      │   ├── BINARY_EXPRESSION.matcher("1+1").matches() → true
            │   │       │      │   ├── group(1)="1", group(2)="+", group(3)="1"
            │   │       │      │   ├── result = BigDecimal(1).add(BigDecimal(1)) = 2
            │   │       │      │   └── return "Calculation completed: 1+1 = 2"
            │   │       │      │
            │   │       │      └── reject → 工具返回 "Calculation completed: 1+1 = 2"
            │   │       │          → ReAct 循环恢复, LLM 获得工具结果
            │   │       │
            │   │       │  [Reason] LLM 基于工具结果生成最终回答:
            │   │       │  → "The result of 1+1 is **2**."
            │   │       │
            │   │       └── toQueryResponse → QueryResponse{content:"The result of 1+1 is **2**."}
            │   │
            │   └── extractInterruptFromResponse → empty (无中断, 正常完成)
            │       → 返回最终 QueryResponse
            │
            └── A2AAgentExecutor.executeQuery (续):
                │
                ├── response.getResult() instanceof Map → true
                ├── result.get("_interrupt") → null (无中断!)
                ├── result.get("content") → "The result of 1+1 is **2**."
                │
                ├── emitter.addArtifact([TextPart("The result of 1+1 is **2**.")], null, null,
                │     Map.of(TERMINAL_RESULT_METADATA, true))
                │   → 向 A2A Task 添加最终结果 artifact
                │
                └── completeAndDrain(emitter, taskId)
                    → emitter.complete()
                    → Task 状态 → TASK_STATE_COMPLETED
                    → 等待事件排空后关闭
```

### 3.4 Agent A 接收远端结果并回喂 LLM

```
A2ARemoteAgentClient.handleClientEvent(event, ...)
│
├── event = TaskStatusUpdateEvent (state = COMPLETED)
│
└── handleOutcomeStatus → completeTaskOutcome
    → result.complete(RemoteCallOutcome(
        taskId: "agent-b-task-xxx",
        state: TASK_STATE_COMPLETED,
        resultCategory: "COMPLETED",
        result: "The result of 1+1 is **2**.",
        inputPrompt: null
      ))

BatchCoordinator.finishInvocation:
│
├── applyOutcome(member, outcome, null)
│   ├── outcome.remoteState() = TASK_STATE_COMPLETED
│   ├── member.state = MemberState.COMPLETED
│   └── member.result = "The result of 1+1 is **2**."
│
├── finishBatchIfSettled(batch)
│   ├── resolveSettledBatch:
│   │   ├── hasWaitingMember → false (全部 COMPLETED)
│   │   └── batch.shouldResume → true → saveShadow(batch, "READY_TO_RESUME")
│   │       (状态变为 READY_TO_RESUME, 可恢复到 Agent A)
│   │
│   └── batch.completion.complete(BatchResolution(
│         isReadyToResume: true,
│         results: {"call_xxx": "The result of 1+1 is **2**."},
│         shouldResume: true
│       ))

Orchestrator.syncResumePending → 等待 batch.completion.get():
  → BatchResolution(isReadyToResume=true, shouldResume=true)

→ queryBatchResolution(current, resolution, null)
  ← A2AEnabledServeOrchestrator.java#L470
  │
  ├── resolution.isReadyToResume() → true
  ├── resolution.shouldResume() → true (tool-call 路径)
  │
  └── buildBatchResumeRequest(current, resolution)   ← A2AEnabledServeOrchestrator.java#L512
      │
      ├── resume = new ServeRequest()
      ├── resume.setConversationId("a2a-test-1")
      ├── resume.setMessages(original.getMessages())
      ├── metadata.put("runtime.remoteToolResults",
      │     {"call_xxx": "The result of 1+1 is **2**."})  ← 远端结果!
      ├── metadata.put("runtime.remoteBatchId", resolution.batchId())
      └── 返回带远端结果的 ServeRequest
      → return QueryResumeResult.continueWith(resume)

→ current = resume (带 remoteToolResults)

→ agentHandler.query(current)  ← 第2次调用 Agent A 的 AgentHandler

  JiuwenCoreAgentHandler.query(current)
  ├── buildInputs(current)
  │   ├── request.metadata.get("runtime.remoteToolResults") → 是 Map!
  │   ├── InteractiveInput interactiveInput = new InteractiveInput()
  │   ├── interactiveInput.setUserInputs({"call_xxx": "The result..."})
  │   └── inputs.put("query", interactiveInput)  ← 中断恢复路径
  │
  └── Runner.runAgent(agent, inputs, session, null)
      → ReActAgent 恢复执行
      │
      │  A2aDelegateRail.resolveInterrupt(ctx, toolCall, resumeInput="The result...")
      │  文件: A2aDelegateRail.java#L49
      │  │
      │  ├── resumeInput != null → true
      │  └── return reject(resumeInput)
      │      → 远端结果直接作为工具返回值
      │      → ReAct 循环恢复
      │
      │  [Reason] LLM 获得工具结果:
      │    delegate_to_agentb 返回: "The result of 1+1 is **2**."
      │
      │  system-prompt 指示: "return Agent B's tool result verbatim;
      │    do not summarize, reformat, or translate it."
      │
      │  → LLM 输出: "The result of 1+1 is **2**."
      │
      └── toQueryResponse → QueryResponse{content:"The result of 1+1 is **2**."}

→ extractInterruptFromResponse → empty
→ return response → HTTP 200
```

---

## 中断恢复的状态流转图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Member 状态机                                     │
│                                                                         │
│  QUEUED ──────→ DISPATCHED ──────→ INPUT_REQUIRED ──────→ QUEUED        │
│    │              │                    │                   │           │
│    │              ↓                    │                   │           │
│    │          COMPLETED               │                   │           │
│    │              │                    │ (用户回复 yes)     │           │
│    │              ↓                    │                   │           │
│    │         FINISHED                 ↓                   │           │
│    │                                QUEUED ──→ DISPATCHED ──→ COMPLETED│
│    │                                                         │          │
│    └────────────────────────────────────────────────────── FINISHED   │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                        Shadow Task 状态                                 │
│                                                                         │
│  (无 Shadow) → WAITING_INPUT → (用户回复) → READY_TO_RESUME → (删除)    │
│                    │                         │                         │
│                    │ Task 状态:               │ Task 状态:              │
│                    │ INPUT_REQUIRED           │ INPUT_REQUIRED          │
│                    │                          │                         │
│                    │ Member 处于              │ Member 处于             │
│                    │ INPUT_REQUIRED           │ COMPLETED               │
│                    └─────────────────────────┘                         │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                        A2A Task (Agent B 侧) 状态                       │
│                                                                         │
│  (新创建) → SUBMITTED → WORKING → INPUT_REQUIRED ──→ WORKING → COMPLETED│
│                                    │                  │                │
│                                    │ (用户回复"yes"   │ (计算完成)      │
│                                    │  恢复 Task)       │               │
│                                    └──────────────────┘                │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 关键设计要点

### 1. 中断类型的路由分叉

| `_interrupt_kind` | 产生者 | 处理方式 |
|---|---|---|
| `"a2a_delegate"` | A2aDelegateRail / BToCDelegateRail / BToDDelegateRail | Orchestrator 拦截 → batchCoordinator 远端调用 |
| `"ask_user"` | CalcInterruptRail / FoodRecommendInterruptRail | Orchestrator 透传 → 直接返回给用户 |

代码位置：`A2AEnabledServeOrchestrator.isCoordinatorInterrupt()` 检查 `_interrupt_kind == "a2a_delegate"`

### 2. Shadow Task——跨请求持久化中断上下文

Shadow Task 是恢复的关键基础设施：
- 在 `saveShadow()` 时创建，存储完整的 batch 快照（含远端 taskId、member 状态、原始请求）
- 用户恢复时 `batchCoordinator.resume()` 读取 shadow Task 恢复 batch
- 恢复完成后 `completeResume()` 删除 shadow Task

### 3. 中断数据在 Task metadata 中的存储与恢复

Agent B 侧：`emitter.requiresInput(statusMessage)` 将中断数据存入 `Task.status.message.metadata["_interrupt"]`
Agent B 恢复：`findStoredInterrupt(task)` 从 `Task.status.message.metadata` 取回中断数据

### 4. 双重中断——Agent A 和 Agent B 各有一层

```
Agent A 层:  A2aDelegateRail 中断 (a2a_delegate) → 触发远端调用
Agent B 层:  CalcInterruptRail 中断 (ask_user) → 冒泡到用户

用户回复 "yes" 的传递:
  用户 → Agent A Orchestrator
    → batchCoordinator.resume() (查 shadow Task)
    → A2ARemoteAgentClient.callOutcome (恢复 Agent B 的 Task)
    → Agent B A2AAgentExecutor (isInputRequiredResume)
    → findStoredInterrupt (恢复中断上下文)
    → Agent B Orchestrator → agentHandler.query
    → CalcInterruptRail.resolveInterrupt(resumeInput="yes")
```

---

## 关键源码文件索引

| 文件 | 在中断过程中的角色 |
|---|---|
| [CalcInterruptRail.java](src/main/java/com/openjiuwen/service/demo/example/a2a/CalcInterruptRail.java) | 产生 ask_user 中断, 首次请求确认, "yes" 后计算 |
| [A2aDelegateRail.java](src/main/java/com/openjiuwen/service/demo/example/a2a/A2aDelegateRail.java) | 产生 a2a_delegate 中断, 恢复时 reject(resumeInput) |
| [JiuwenCoreAgentHandler.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/agentfw/JiuwenCoreAgentHandler.java) | 中断归一化: OutputSchema → QueryResponse._interrupt |
| [A2AEnabledServeOrchestrator.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/A2AEnabledServeOrchestrator.java) | 中断路由: isCoordinatorInterrupt 分叉 a2a_delegate vs ask_user |
| [RemoteInvocationBatchCoordinator.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/RemoteInvocationBatchCoordinator.java) | 远端调用协调: execute/resume/finishInvocation, shadow Task 管理 |
| [RemoteInvocationBatchMapper.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/RemoteInvocationBatchMapper.java) | 中断解析: interrupt → Member, outcome → MemberState, shadow 快照 |
| [A2AAgentExecutor.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/controller/a2a/A2AAgentExecutor.java) | A2A 协议转换: requiresInput/executeQuery, findStoredInterrupt |
| [A2ARemoteAgentClient.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/controller/a2a/client/A2ARemoteAgentClient.java) | 远端调用: callOutcome, handleOutcomeStatus (INPUT_REQUIRED → RemoteCallOutcome) |
| [A2AProtocolAdapter.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/controller/a2a/A2AProtocolAdapter.java) | A2A Message → ServeRequest (消息文本提取) |

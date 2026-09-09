# Agent A 委派 Agent B 执行 "1+1" 完整过程详解

> 以 "What is 1+1?" 为例，严格按代码执行轨迹梳理 Agent A 如何通过 A2A 协议将任务委派给 Agent B 执行。
> 所有代码引用均标注实际文件路径和行号。

---

## 场景概述

```
用户                    Agent A (18090)                    Agent B (18091)
 │                      │                                 │
 │── "What is 1+1?" ──→ │                                 │
 │                      │── A2A JSON-RPC 委派 ──────────→ │
 │                      │                                 │── CalcInterruptRail 中断
 │←── "Continue?" ───── │←──────── 中断冒泡 ────────────── │
 │── "yes" ──────────→ │── A2A 恢复 (yes) ──────────────→ │
 │                      │                                 │── 计算完成 1+1=2
 │                      │←── 结果返回 ───────────────────── │
 │←── "结果是2" ─────── │                                 │
```

---

## 第一阶段：Agent A 内部——LLM 决定委派

### 1.1 HTTP 请求到达 Agent A

```
POST http://localhost:18090/v1/query
Body: {"conversation_id":"a2a-test-1","message":"What is 1+1?","stream":false}
```

**代码轨迹**：

```
QueryMvcController.queryV1()                          ← QueryMvcController.java#L86
└── handleQuery(rawBody, headers, servletRequest, response)  ← QueryMvcController.java#L103
    ├── objectMapper.readValue(rawBody, QueryRequest.class)
    │   → 解析出 conversation_id="a2a-test-1", message="What is 1+1?", stream=false
    ├── QueryIngressSupport.validateAndBuild(request, headers)
    │   → 构造 ServeRequest
    ├── isAgentReady() → true
    ├── orchestratorProvider.getIfAvailable()
    │   → 返回 A2AEnabledServeOrchestrator
    └── orchestrator.query(serveRequest)             ← 进入编排器
```

文件：`service/agent-service-app/.../controller/query/QueryMvcController.java`

### 1.2 Orchestrator 调用 AgentHandler

```
A2AEnabledServeOrchestrator.query(request)           ← A2AEnabledServeOrchestrator.java#L136
├── agentHandler.prepareTask(request)                 → Optional.empty() (无 task-scoped 资源)
├── while (true) {  ← 中断-恢复循环 第1次迭代
│   ├── syncResumePending(current, NOOP_OBSERVER)    ← A2AEnabledServeOrchestrator.java#L340
│   │   ├── isClientToolResume(current) → false
│   │   ├── batchCoordinator.resume(current, observer) → Optional.empty() (无待恢复远端任务)
│   │   └── return QueryResumeResult.continueWith(current)  → 继续
│   │
│   └── agentHandler.query(current)                  ← 调用 AgentHandler
```

文件：`service/agent-service-app/.../orchestrator/A2AEnabledServeOrchestrator.java`

### 1.3 JiuwenCoreAgentHandler 构建 Runner 输入并执行

```
JiuwenCoreAgentHandler.query(request)                 ← JiuwenCoreAgentHandler.java#L228
├── FutureTask(() -> {
│   ├── supportsInvoke(agent) → true (ReActAgent 有 invoke() 方法)  ← JiuwenCoreAgentHandler.java#L388
│   │
│   ├── executeAgent(buildInputs(request), runnerSession(request))
│   │   │
│   │   ├── buildInputs(request)                      ← JiuwenCoreAgentHandler.java#L416
│   │   │   ├── inputs.put("conversation_id", "a2a-test-1")
│   │   │   ├── inputs.put("messages", [...])
│   │   │   ├── request.getMetadata().get("runtime.remoteToolResults") → null (首次请求)
│   │   │   └── inputs.put("query", "What is 1+1?")   ← 用户文本作为 query
│   │   │
│   │   ├── runnerSession(request)                    ← JiuwenCoreAgentHandler.java#L488
│   │   │   └── 返回 "a2a-test-1" (用 conversation_id 作 session id)
│   │   │
│   │   └── Runner.runAgent(agent, inputs, session, null)
│   │       → 进入 agent-core-java ReActAgent 推理循环
```

文件：`service/agent-service-adapters/.../agentfw/JiuwenCoreAgentHandler.java`

### 1.4 ReActAgent 推理——LLM 决定调用工具

在 agent-core-java 的 Runner 内部，ReActAgent 执行 reason → act → observe 循环：

```
Runner.runAgent(agent, inputs, session, null)
│
└── ReActAgent 推理循环
    │
    │  [Reason] LLM 推理
    │  ├── 输入给 LLM 的内容:
    │  │   system prompt: "You are Agent A in an A2A demo.
    │  │     For every user request, immediately call delegate_to_agentb
    │  │     before producing any answer; do not answer directly..."
    │  │   (来自 application-a2a-agent-a.yml 中的 llm.system-prompt 配置)
    │  │   temperature: 0.0 (完全确定性)
    │  │
    │  │   user message: "What is 1+1?"
    │  │
    │  │   可用工具列表: [delegate_to_agentb]
    │  │   (工具由 A2aDelegateRail 构造时注册)
    │  │
    │  └── LLM 输出: tool_call(delegate_to_agentb, {message: "What is 1+1?"})
    │      → LLM 遵循 system prompt 指示，不直接回答，而是调用委派工具
    │
    │  [Act] 工具调用拦截
    │  → ReActAgent 发现 LLM 要调用 delegate_to_agentb
    │  → 该工具名匹配 A2aDelegateRail 注册的监听列表
    │  → 进入 A2aDelegateRail.resolveInterrupt()
```

### 1.5 A2aDelegateRail 拦截工具调用——触发中断

```
A2aDelegateRail.resolveInterrupt(ctx, toolCall, resumeInput=null)  ← A2aDelegateRail.java#L47
│
├── resumeInput == null → true (首次调用, 无恢复输入)
│
├── 从 toolCall.getArguments() 提取用户消息:
│   ├── GSON.fromJson(toolCall.getArguments(), Map.class)
│   │   → {message: "What is 1+1?"}
│   └── userQuery = "What is 1+1?"
│
├── 构造 InterruptRequest:
│   ├── message = "What is 1+1?"            ← 用户的原始请求
│   └── context = {
│         "agentName": "agentb",           ← 指定委派给 Agent B
│         "_interrupt_kind": "a2a_delegate" ← 标识为 A2A 委派中断
│       }
│
└── return interrupt(request)
    → ReActAgent 循环暂停, 中断信息冒泡到 OutputSchema
```

文件：`service/agent-service-demo/example/a2a/src/main/java/.../A2aDelegateRail.java`

**关键点**：`context` 中的 `agentName="agentb"` 是委派的核心——后续 Orchestrator 据此找到 Agent B 的 URL。`_interrupt_kind="a2a_delegate"` 区分了 A2A 委派中断和其他类型的中断（如 `ask_user`）。

### 1.6 中断信息归一化为 QueryResponse

```
JiuwenCoreAgentHandler (续)
│
├── Runner.runAgent 返回 rawResult:
│   → Map {result_type: "interrupt", state: [{type: "__interaction__", ...}]}
│
├── toQueryResponse(rawResult, conversationId)       ← JiuwenCoreAgentHandler.java#L330
│   ├── rawResult instanceof Map → true
│   ├── getQueryResponse(conversationId, map)         ← JiuwenCoreAgentHandler.java#L367
│   │   ├── map.get("result_type") == "interrupt"
│   │   ├── map.get("state") instanceof List → true
│   │   ├── 遍历 state 列表:
│   │   │   ├── normalizeChunk(state) → 归一化
│   │   │   ├── isCoreInteraction(normalized) → true (type=="__interaction__")
│   │   │   ├── extractInteractionData(io, data)      ← JiuwenCoreAgentHandler.java#L640
│   │   │   │   ├── 提取 message: "What is 1+1?"
│   │   │   │   ├── 提取 context: {agentName:"agentb", _interrupt_kind:"a2a_delegate"}
│   │   │   │   └── 提取 toolCallId, toolName
│   │   │   └── copyStringMap(interrupt) → 添加到 interrupts 列表
│   │   │
│   │   └── buildInterruptQueryResponse(normalizeInterrupts(interrupts), conversationId)
│   │       ← JiuwenCoreAgentHandler.java#L393
│   │       └── result = {
│   │             role: "assistant",
│   │             _interrupt: {
│   │               type: "__interaction__",
│   │               state: "input_required",
│   │               message: "What is 1+1?",
│   │               toolCallId: "call_xxx",
│   │               toolName: "delegate_to_agentb",
│   │               context: {agentName: "agentb", _interrupt_kind: "a2a_delegate"},
│   │               items: [...]
│   │             },
│   │             content: "What is 1+1?"
│   │           }
│   │
│   └── 返回 QueryResponse (含 _interrupt)
│
└── 返回给 Orchestrator
```

---

## 第二阶段：Orchestrator 拦截中断——发起远端调用

### 2.1 Orchestrator 识别 A2A 委派中断

```
A2AEnabledServeOrchestrator.query() (续)             ← A2AEnabledServeOrchestrator.java#L136
│
├── batchCoordinator.completeResume(current)
├── extractInterruptFromResponse(response)            ← A2AEnabledServeOrchestrator.java#L503
│   → response.getResult()._interrupt 存在 → 返回 interruptData Map
│
├── interruptData.isEmpty() → false (有中断)
│
└── handleQueryInterrupt(interruptData, current, response, NOOP_OBSERVER)
    ← A2AEnabledServeOrchestrator.java#L445
    │
    ├── isCoordinatorInterrupt(interruptData)         ← A2AEnabledServeOrchestrator.java#L528
    │   ├── interruptData.get("items") → 是 List, 非空
    │   ├── 遍历 items:
    │   │   item.context._interrupt_kind == "a2a_delegate" → true
    │   └── 返回 true  ← 确认是 A2A 委派中断
    │
    └── batchCoordinator.execute(interruptData, current, NOOP_OBSERVER)
        ← 进入远端调用协调器
```

### 2.2 RemoteInvocationBatchCoordinator 解析中断并发起远端调用

```
RemoteInvocationBatchCoordinator.execute(interrupt, request, observer)
← RemoteInvocationBatchCoordinator.java#L127
│
├── batchMapper.parse(interrupt, request, parentTaskId, observer)
│   ← RemoteInvocationBatchMapper.java#L44
│   │
│   ├── interruptItems(interrupt)                    ← RemoteInvocationBatchMapper.java#L262
│   │   → 从 interrupt.items 提取 List<Map>
│   │   → [{toolCallId:"call_xxx", toolName:"delegate_to_agentb", message:"What is 1+1?",
│   │       context:{agentName:"agentb", _interrupt_kind:"a2a_delegate"}}]
│   │
│   ├── 遍历 items, 为每个 item 构造 Member:
│   │   ├── toolCallId = "call_xxx"
│   │   ├── toolName = "delegate_to_agentb"
│   │   ├── agentName = "agentb"              ← 从 context.agentName 提取
│   │   ├── message = "What is 1+1?"         ← 用户原始请求
│   │   └── _interrupt_kind = "a2a_delegate"  ← 校验, 非此值会抛异常
│   │
│   └── 返回 RemoteInvocationBatch (含 Member 列表)
│
├── registerBatch(batch) → 注册到 state, 无冲突
│
├── for each member: submit(new PendingInvocation(batch, member))
│   → RemoteInvocationCoordinatorState 调度
│   → 获取并发槽位后执行远端调用:
│
│   └── client.callOutcome(remoteCall, eventObserver)
│       ← A2ARemoteAgentClient.java#L260
```

文件：`service/agent-service-app/.../orchestrator/RemoteInvocationBatchCoordinator.java`
文件：`service/agent-service-app/.../orchestrator/RemoteInvocationBatchMapper.java`

### 2.3 A2ARemoteAgentClient 发起 JSON-RPC 调用

```
A2ARemoteAgentClient.callOutcome(call, eventObserver)  ← A2ARemoteAgentClient.java#L260
│
├── registry.get(call.agentName())                   ← A2ARemoteAgentClient.java#L265
│   → 从 A2ARemoteAgentCardRegistry 查 "agentb"
│   → 返回 RemoteAgentEntry {name:"agentb", card:AgentCard, timeoutSeconds:300, streaming:true}
│   (此 entry 在启动时由 A2AAgentCardDiscovery.discoverAll() 注册)
│
├── isStreaming = entry.isStreaming() && call.isCallerStreaming() → true
│
├── prepareCall(call)                                 ← A2ARemoteAgentClient.java#L155
│   ├── registry.get("agentb") → entry
│   ├── contextId = call.contextId() (或 UUID)
│   └── buildSendParams(call, contextId)              ← A2ARemoteAgentClient.java#L167
│       │
│       ├── Message.builder()
│       │   .role(ROLE_USER)
│       │   .contextId(contextId)
│       │   .parts([TextPart("What is 1+1?")])    ← 用户原始消息作为 A2A 消息
│       │   .metadata(call.messageMetadata())
│       │
│       ├── MessageSendConfiguration.builder()
│       │   .returnImmediately(false)              ← 同步等待远端完成
│       │
│       └── MessageSendParams.builder()
│           .message(message)
│           .configuration(configuration)
│           .metadata(paramsMetadata)
│           → 返回 MessageSendParams
│
├── createClient(entry, isStreaming)                  ← A2ARemoteAgentClient.java#L198
│   │   → Client.builder(card)
│   │       .clientConfig(ClientConfig{streaming:true})
│   │       .withTransport(JSONRPCTransport, config)
│   │       .build()
│   │   → 创建 A2A SDK Client (JSON-RPC 传输)
│   │
│   → Client 内部的 endpoint URL 来自 AgentCard:
│     GET http://localhost:18091/.well-known/agent-card.json 返回的 card.supportedInterfaces[0].url
│     = "http://localhost:18091/a2a" (Agent B 的 JSON-RPC 端点)
│
└── submitInvocation(call, setup, client, eventConsumer, result)
    ← A2ARemoteAgentClient.java#L317
    │
    ├── ioExecutor.submit(() -> {
    │   └── client.sendMessage(params, [eventConsumer], errorCallback, null)
    │       → 发送 A2A JSON-RPC 请求:
    │         POST http://localhost:18091/a2a
    │         Body: JSON-RPC message, method="message/send",
    │               params.message.parts[0].text = "What is 1+1?"
    │   })
    │
    └── eventConsumer 回调处理远端返回的事件:
        handleClientEvent(event, result, eventObserver, ...)
        ← A2ARemoteAgentClient.java#L355
```

文件：`service/agent-service-app/.../controller/a2a/client/A2ARemoteAgentClient.java`

**实际发出的 HTTP 请求**：

```
POST http://localhost:18091/a2a
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "method": "message/send",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "parts": [{"kind": "text", "text": "What is 1+1?"}],
      "contextId": "a2a-test-1"
    },
    "configuration": {"returnImmediately": false}
  },
  "id": "..."
}
```

---

## 第三阶段：Agent B 接收并处理请求

### 3.1 Agent B 的 A2A SDK 接收 JSON-RPC 请求

Agent B 运行在端口 18091，其 A2A SDK 的 `DefaultRequestHandler` 接收到 JSON-RPC 请求后：

```
Agent B: DefaultRequestHandler → A2AAgentExecutor.execute(ctx, emitter)
← A2AAgentExecutor.java#L131
│
├── A2AMessageContext.from(ctx)
│   → 提取 A2A 消息: parts[0].text = "What is 1+1?"
│   → 提取 contextId (作为 conversation_id)
│
├── adapter.toServeRequest(msgCtx)                   ← A2AProtocolAdapter.java#L43
│   │
│   ├── req.setConversationId(ctx.getContextId())
│   ├── 从 headers 提取 userId/spaceId/tenantId
│   ├── extractText(msg.parts()) → "What is 1+1?"
│   ├── 构造 user message: {role:"user", content:"What is 1+1?"}
│   └── req.setMessages([userMsg])
│
├── isInputRequiredResume = false (新任务, 非 INPUT_REQUIRED 恢复)
│
└── executeRequest(ctx, msgCtx, req, emitter, isNewTask=true)
    ← A2AAgentExecutor.java#L163
    │
    ├── (admission gate 检查, 默认无限制)
    ├── executeAdmitted(ctx, msgCtx, req, emitter, isNewTask=true)
    │   ← A2AAgentExecutor.java#L213
    │   │
    │   ├── emitter.submit() (创建新 Task)
    │   ├── emitter.startWork()
    │   │
    │   └── FutureTask(() -> {
    │       │   req.isStream() → false
    │       │
    │       └── executeQuery(msgCtx, ctx, req, emitter)   ← 非流式路径
    │           → orchestrator.query(req)  ← Agent B 的 Orchestrator
    │       })
```

文件：`service/agent-service-app/.../controller/a2a/A2AAgentExecutor.java`
文件：`service/agent-service-app/.../controller/a2a/A2AProtocolAdapter.java`

### 3.2 Agent B 的 Orchestrator → AgentHandler → ReActAgent

Agent B 的处理链与 Agent A 相同：

```
A2AEnabledServeOrchestrator.query(req)
├── agentHandler.prepareTask(req)
├── syncResumePending → 无待恢复任务
├── agentHandler.query(req)
│   │
│   ├── JiuwenCoreAgentHandler.query(req)
│   │   ├── buildInputs(req) → inputs={query:"What is 1+1?", ...}
│   │   └── Runner.runAgent(agent, inputs, session, null)
│   │       → Agent B 的 ReActAgent 推理循环
│   │       │
│   │       │  [Reason] LLM 推理
│   │       │  ├── system prompt: "You are Agent B in an A2A demo.
│   │       │  │     For math or arithmetic, call calc with the original expression..."
│   │       │  │     (来自 application-a2a-agent-b.yml)
│   │       │  │
│   │       │  │     可用工具: [calc, delegate_to_agentc_streaming, delegate_to_agentc_nonstreaming,
│   │       │  │                review_expense_streaming, review_expense_nonstreaming]
│   │       │  │
│   │       │  └── LLM 输出: tool_call(calc, {expression: "1+1"})
│   │       │      → 识别为数学计算, 调用 calc 工具
│   │       │
│   │       │  [Act] CalcInterruptRail 拦截
│   │       │  → CalcInterruptRail.resolveInterrupt(ctx, toolCall, resumeInput=null)
│   │       │    ← CalcInterruptRail.java#L56
│   │       │    │
│   │       │    ├── resumeInput == null → true (首次调用)
│   │       │    ├── extractExpression(toolCall) → "1+1"
│   │       │    │   (从 toolCall.arguments 的 expression 字段提取)
│   │       │    │
│   │       │    └── requestConfirmation("1+1")
│   │       │        ← CalcInterruptRail.java#L66
│   │       │        │
│   │       │        ├── InterruptRequest.builder()
│   │       │        │   .message("Agent B is ready to calculate 1+1. Continue? Reply yes or no.")
│   │       │        │   .context({"_interrupt_kind": "ask_user"})  ← 注意: 不是 a2a_delegate
│   │       │        │
│   │       │        └── return interrupt(request)
│   │       │            → Agent B 的 ReActAgent 循环暂停
│   │       │
│   │       └── toQueryResponse → 返回含 _interrupt 的 QueryResponse
```

文件：`service/agent-service-demo/example/a2a/src/main/java/.../CalcInterruptRail.java`

**关键点**：Agent B 的 `CalcInterruptRail` 的中断 `context._interrupt_kind = "ask_user"`，不是 `"a2a_delegate"`。这意味着这个中断不会触发远端委派，而是直接冒泡给客户端（用户）。

### 3.3 Agent B 中断冒泡回 Agent A

```
Agent B 的 Orchestrator:
│
├── extractInterruptFromResponse(response) → 有中断
├── handleQueryInterrupt(interruptData, ...)
│   ├── isCoordinatorInterrupt(interruptData)
│   │   ├── interruptData.context._interrupt_kind == "ask_user"
│   │   │   != "a2a_delegate"
│   │   └── 返回 false  ← 不是 A2A 委派中断
│   │
│   ├── hasRemoteDelegateItem(interruptData) → false
│   ├── isRemoteDelegate(interruptData) → false
│   │
│   └── 返回 Optional.empty() → 中断透传给客户端
│       → 构造 INPUT_REQUIRED 响应返回给 Agent A
│
└── queryBatchResolution 返回:
    BatchResolution {
      isReadyToResume: false,  ← 远端也返回了 INPUT_REQUIRED
      interrupt: {type:"__interaction__", state:"input_required",
                  message:"Agent B is ready to calculate 1+1. Continue? Reply yes or no.",
                  items:[{toolCallId:"...", toolName:"delegate_to_agentb", ...}]},
      shouldResume: true       ← tool-call 路径, 需要回喂 Agent A
    }
```

### 3.4 Agent A 将中断返回给客户端

```
A2AEnabledServeOrchestrator.query() (续)
│
├── queryBatchResolution(current, resolution, response)
│   ← A2AEnabledServeOrchestrator.java#L470
│   │
│   ├── resolution.isReadyToResume() → false (远端 INPUT_REQUIRED)
│   │
│   └── 构造中断 QueryResponse:
│       result = {
│         role: "assistant",
│         _interrupt: {
│           type: "__interaction__",
│           state: "input_required",
│           message: "Agent B is ready to calculate 1+1. Continue? Reply yes or no.",
│           items: [{toolCallId, toolName:"delegate_to_agentb", ...}]
│         },
│         content: "Agent B is ready to calculate 1+1. Continue? Reply yes or no."
│       }
│       → 返回 QueryResumeResult.respond(interruptResponse)
│
├── return response  ← 返回中断给 Controller
│
└── QueryMvcController:
    writeJson(response, 200, queryResponse)
    → HTTP 200 响应返回给客户端
```

**客户端收到的第一次响应**：

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

## 第四阶段：用户确认 "yes"——中断恢复

### 4.1 客户端发送恢复请求

```
POST http://localhost:18090/v1/query
Body: {"conversation_id":"a2a-test-1","message":"yes","stream":false}
```

### 4.2 Agent A Orchestrator 恢复远端任务

```
A2AEnabledServeOrchestrator.query(request)
├── agentHandler.prepareTask(request)
├── while (true) {
│   │
│   ├── syncResumePending(current, NOOP_OBSERVER)    ← A2AEnabledServeOrchestrator.java#L340
│   │   │
│   │   ├── isClientToolResume(current) → false (无 _interrupt metadata)
│   │   │
│   │   ├── batchCoordinator.resume(current, observer)
│   │   │   ← RemoteInvocationBatchCoordinator.java#L200
│   │   │   │
│   │   │   ├── request.metadata.get("runtime.remoteToolResults") → null (用户恢复, 非远端恢复)
│   │   │   ├── stringValue(request.metadata.get("runtime.remoteBatchId")) → 可能有值
│   │   │   │
│   │   │   ├── parentTaskId = parentTaskId(request) → "a2a-test-1"
│   │   │   │
│   │   │   ├── 检查 shadow task 是否存在 (taskStore.get(shadowTaskId))
│   │   │   │   → 存在! (之前远端调用时创建的 shadow task)
│   │   │   │
│   │   │   ├── restore(rawBatch, request, parentTaskId, observer)
│   │   │   │   ← RemoteInvocationBatchMapper.java#L66
│   │   │   │   → 从 shadow task 快照恢复 batch
│   │   │   │
│   │   │   └── 对每个 INPUT_REQUIRED 的 member 提交恢复调用:
│   │   │       submit(new PendingInvocation(batch, member))  ← 恢复远端调用
│   │   │
│   │   │   → client.callOutcome(remoteCall, eventObserver)
│   │   │     ← A2ARemoteAgentClient.java#L260
│   │   │     │
│   │   │     ├── 远端调用恢复:
│   │   │     │   POST http://localhost:18091/a2a
│   │   │     │   method = "message/send"
│   │   │     │   params.message.taskId = 之前 Agent B 创建的 taskId  ← 恢复已有 Task
│   │   │     │   params.message.parts[0].text = "yes"  ← 用户确认
│   │   │     │
│   │   │     └── Agent B 接收到 "yes", 恢复执行
```

### 4.3 Agent B CalcInterruptRail 处理用户确认

```
Agent B: A2AAgentExecutor.execute(ctx, emitter)
├── isInputRequiredResume = true (Task 状态为 INPUT_REQUIRED)
├── findStoredInterrupt(task) → 找到之前存储的中断数据
├── metadata.put("_interrupt", storedInterrupt)  ← 恢复中断上下文
│
└── executeRequest → orchestrator.query(req)
    │
    ├── agentHandler.query(req)
    │   │
    │   ├── buildInputs(request)
    │   │   ├── request.metadata.get("runtime.remoteToolResults") → 可能有值
    │   │   └── inputs.put("query", "yes") 或 InteractiveInput
    │   │
    │   └── Runner.runAgent(agent, inputs, session, null)
    │       → ReActAgent 恢复执行
    │       │
    │       │  CalcInterruptRail.resolveInterrupt(ctx, toolCall, resumeInput="yes")
    │       │  ← CalcInterruptRail.java#L56
    │       │  │
    │       │  ├── resumeInput != null → true (有恢复输入 "yes")
    │       │  │
    │       │  ├── normalizeConfirmation("yes") → "yes"
    │       │  │   ← String.valueOf(resumeInput).trim().toLowerCase().replaceFirst("[.!]$","")
    │       │  │
    │       │  ├── AFFIRMATIVE_RESPONSES.contains("yes") → true
    │       │  │   ← AFFIRMATIVE_RESPONSES = {"ok","yes","y","confirm","confirmed",
    │       │  │      "approve","approved","continue","proceed"}
    │       │  │
    │       │  └── return reject(calculate("1+1"))
    │       │      ← CalcInterruptRail.java#L62
    │       │      │
    │       │      ├── calculate("1+1")               ← CalcInterruptRail.java#L73
    │       │      │   │
    │       │      │   ├── BINARY_EXPRESSION.matcher("1+1").matches() → true
    │       │      │   ├── group(1) = "1" (left)
    │       │      │   ├── group(2) = "+" (operator)
    │       │      │   ├── group(3) = "1" (right)
    │       │      │   │
    │       │      │   ├── switch("+"):
    │       │      │   │   result = left.add(right) = BigDecimal(1).add(BigDecimal(1))
    │       │      │   │         = BigDecimal(2)
    │       │      │   │
    │       │      │   ├── normalizedExpression = format(1) + "+" + format(1) = "1+1"
    │       │      │   │
    │       │      │   └── return "Calculation completed: 1+1 = 2"
    │       │      │
    │       │      └── reject("Calculation completed: 1+1 = 2")
    │       │          → reject = 将结果作为工具返回值, 不真正执行工具
    │       │          → ReActAgent 循环恢复, LLM 获得工具结果
    │       │
    │       │  [Observe] LLM 获得工具结果:
    │       │    calc 工具返回: "Calculation completed: 1+1 = 2"
    │       │
    │       │  [Reason] LLM 基于工具结果生成最终回答:
    │       │    → "The result of 1+1 is **2**."
    │       │
    │       └── toQueryResponse → QueryResponse {content: "The result of 1+1 is **2**."}
    │
    └── 返回给 A2AAgentExecutor → 通过 AgentEmitter 发送 Task 终态事件
```

### 4.4 Agent B 结果返回给 Agent A

```
Agent B 通过 A2A SDK 返回 Task 终态事件:

A2ARemoteAgentClient (Agent A 侧):
handleClientEvent(event, result, eventObserver, ...)
← A2ARemoteAgentClient.java#L355
│
├── event instanceof TaskUpdateEvent
│   └── tue.getUpdateEvent() instanceof TaskStatusUpdateEvent
│       └── handleOutcomeStatus(sue, task, result, ...)
│           ← A2ARemoteAgentClient.java#L407
│           │
│           ├── state = event.status().state()
│           │   → TaskState.TASK_STATE_COMPLETED (Agent B 完成计算)
│           │
│           ├── statusText = extractText(event.status().message().parts())
│           │   → "The result of 1+1 is **2**."
│           │
│           └── completeTaskOutcome(outcome, result, isCallbackMode)
│               ← A2ARemoteAgentClient.java#L440
│               │
│               ├── outcome.state() = TASK_STATE_COMPLETED
│               ├── taskText = A2aPartContent.extractTaskResult(task)
│               │   → "The result of 1+1 is **2**."
│               │
│               └── result.complete(new RemoteCallOutcome(
│                     taskId,
│                     TaskState.TASK_STATE_COMPLETED,
│                     "COMPLETED",
│                     "The result of 1+1 is **2**.",
│                     null
│                   ))
```

### 4.5 Agent A Orchestrator 处理远端结果

```
RemoteInvocationBatchCoordinator:
│
├── applyOutcome(member, outcome, null)              ← RemoteInvocationBatchMapper.java#L99
│   │
│   ├── outcome.remoteState() = TASK_STATE_COMPLETED
│   ├── member.state = MemberState.COMPLETED
│   └── member.result = "The result of 1+1 is **2**."
│
├── resolution(batch)                                ← RemoteInvocationBatchMapper.java#L188
│   │
│   ├── hasWaitingMember → false (无 INPUT_REQUIRED)
│   ├── results = {toolCallId:"call_xxx" → "The result of 1+1 is **2**."}
│   └── return BatchResolution(batchId, isReadyToResume=true, results, {}, shouldResume=true)
│
└── 返回给 Orchestrator

A2AEnabledServeOrchestrator:
├── queryBatchResolution(current, resolution, response)
│   ← A2AEnabledServeOrchestrator.java#L470
│   │
│   ├── resolution.isReadyToResume() → true
│   ├── resolution.shouldResume() → true (tool-call 路径)
│   │
│   └── buildBatchResumeRequest(current, resolution)  ← A2AEnabledServeOrchestrator.java#L512
│       │
│       ├── 复制 conversation_id, messages, userId 等
│       ├── metadata.put("runtime.remoteToolResults",
│       │     {"call_xxx": "The result of 1+1 is **2**."})
│       ├── metadata.put("runtime.remoteBatchId", resolution.batchId())
│       └── 返回新的 ServeRequest (带远端结果)
│
│   → 返回 QueryResumeResult.continueWith(resume)
│
├── current = resume (带 remoteToolResults 的请求)
│
├── agentHandler.query(current)  ← 第2次调用 Agent A 的 AgentHandler
│   │
│   ├── buildInputs(request)                         ← JiuwenCoreAgentHandler.java#L416
│   │   ├── request.metadata.get("runtime.remoteToolResults") → 是 Map!
│   │   ├── InteractiveInput interactiveInput = new InteractiveInput()
│   │   ├── interactiveInput.setUserInputs({"call_xxx": "The result of 1+1 is **2**."})
│   │   └── inputs.put("query", interactiveInput)    ← 中断恢复路径!
│   │
│   └── Runner.runAgent(agent, inputs, session, null)
│       → ReActAgent 恢复执行
│       │
│       │  A2aDelegateRail.resolveInterrupt(ctx, toolCall, resumeInput="The result of 1+1 is **2**.")
│       │  ← A2aDelegateRail.java#L49
│       │  │
│       │  ├── resumeInput != null → true (有远端结果)
│       │  └── return reject(resumeInput)
│       │      → 将远端结果直接作为工具返回值
│       │      → ReAct 循环恢复
│       │
│       │  [Reason] LLM 获得工具结果:
│       │    delegate_to_agentb 工具返回: "The result of 1+1 is **2**."
│       │
│       │  LLM 遵循 system prompt:
│       │    "After the tool returns, return Agent B's tool result verbatim;
│       │     do not summarize, reformat, or translate it."
│       │
│       │  → LLM 输出: "The result of 1+1 is **2**."
│       │
│       └── toQueryResponse → QueryResponse {content: "The result of 1+1 is **2**."}
│
├── extractInterruptFromResponse(response) → empty (无中断, 正常完成)
│
└── return response → HTTP 200
```

**客户端收到的最终响应**：

```json
{
  "result": {
    "role": "assistant",
    "content": "The result of 1+1 is **2**."
  },
  "conversation_id": "a2a-test-1"
}
```

---

## 完整数据流图

```
┌─────────┐     ┌─────────────────────────────┐     ┌──────────────────────────────────────┐
│  用户    │     │      Agent A (18090)         │     │      Agent B (18091)                  │
│         │     │                              │     │                                       │
│ "1+1?"──┼────→│ QueryMvcController           │     │                                       │
│         │     │  └→ Orchestrator.query()      │     │                                       │
│         │     │     └→ agentHandler.query()   │     │                                       │
│         │     │        └→ Runner.runAgent()   │     │                                       │
│         │     │           └→ ReActAgent      │     │                                       │
│         │     │              system-prompt:  │     │                                       │
│         │     │              "必须调用        │     │                                       │
│         │     │               delegate_to_    │     │                                       │
│         │     │               agentb"         │     │                                       │
│         │     │              ↓                 │     │                                       │
│         │     │           LLM→tool_call(      │     │                                       │
│         │     │             delegate_to_      │     │                                       │
│         │     │             agentb,           │     │                                       │
│         │     │             {message:         │     │                                       │
│         │     │              "What is 1+1?"}) │     │                                       │
│         │     │              ↓                 │     │                                       │
│         │     │           A2aDelegateRail      │     │                                       │
│         │     │           .resolveInterrupt() │     │                                       │
│         │     │           interrupt(context:{ │     │                                       │
│         │     │             agentName:"agentb",│     │                                       │
│         │     │             _interrupt_kind:  │     │                                       │
│         │     │             "a2a_delegate"})   │     │                                       │
│         │     │              ↓                 │     │                                       │
│         │     │           Orchestrator 识别    │     │                                       │
│         │     │           a2a_delegate 中断    │     │                                       │
│         │     │              ↓                 │     │                                       │
│         │     │           batchCoordinator     │     │                                       │
│         │     │           .execute()          │     │                                       │
│         │     │              ↓                 │     │                                       │
│         │     │           A2ARemoteAgentClient │     │                                       │
│         │     │           .callOutcome()       │     │                                       │
│         │     │              │                 │     │                                       │
│ "Continue?"←────┤←── 中断冒泡(远端INPUT_REQUIRED)│    │                                       │
│         │     │              │                 │     │                                       │
│  "yes"──┼────→│ Orchestrator.syncResumePending│     │                                       │
│         │     │  └→ batchCoordinator.resume()│     │                                       │
│         │     │     └→ A2ARemoteAgentClient   │     │                                       │
│         │     │        .callOutcome()         │     │                                       │
│         │     │           POST /a2a ──────────┼────→│ A2AAgentExecutor.execute()            │
│         │     │           (message:"yes",     │     │  └→ A2AProtocolAdapter.toServeRequest│
│         │     │            taskId:Agent B's)  │     │  └→ Orchestrator.query()              │
│         │     │                              │     │     └→ agentHandler.query()             │
│         │     │                              │     │        └→ Runner.runAgent()              │
│         │     │                              │     │           └→ ReActAgent                 │
│         │     │                              │     │              system-prompt:             │
│         │     │                              │     │              "数学题调用calc工具"          │
│         │     │                              │     │              ↓                          │
│         │     │                              │     │           LLM→tool_call(               │
│         │     │                              │     │             calc, {expression:"1+1"})  │
│         │     │                              │     │              ↓                          │
│         │     │                              │     │           CalcInterruptRail              │
│         │     │                              │     │           .resolveInterrupt(             │
│         │     │                              │     │             resumeInput="yes")          │
│         │     │                              │     │              ↓                          │
│         │     │                              │     │           AFFIRMATIVE_RESPONSES         │
│         │     │                              │     │           .contains("yes") → true        │
│         │     │                              │     │              ↓                          │
│         │     │                              │     │           calculate("1+1") → "2"         │
│         │     │                              │     │           reject("Calculation             │
│         │     │                              │     │             completed: 1+1 = 2")          │
│         │     │                              │     │              ↓                          │
│         │     │                              │     │           LLM生成最终回答                  │
│         │     │                              │     │           "The result of 1+1 is **2**."  │
│         │     │                              │     │              ↓                          │
│         │     │     ←── Task 终态事件 ────────┼─────┘   (TASK_STATE_COMPLETED)                │
│         │     │              ↓                 │                                           │
│         │     │           applyOutcome:        │                                           │
│         │     │           member.result =     │                                           │
│         │     │           "The result of       │                                           │
│         │     │            1+1 is **2**."      │                                           │
│         │     │              ↓                 │                                           │
│         │     │           buildBatchResumeRequest│                                          │
│         │     │           metadata.remoteToolResults│                                        │
│         │     │           = {result}           │                                           │
│         │     │              ↓                 │                                           │
│         │     │           agentHandler.query() │                                           │
│         │     │           (第2次)              │                                           │
│         │     │              ↓                 │                                           │
│         │     │           buildInputs:         │                                           │
│         │     │           InteractiveInput(    │                                           │
│         │     │             remoteToolResults) │                                           │
│         │     │              ↓                 │                                           │
│         │     │           Runner.runAgent()   │                                           │
│         │     │           A2aDelegateRail      │                                           │
│         │     │           .resolveInterrupt(   │                                           │
│         │     │             resumeInput=result)│                                          │
│         │     │           reject(result)       │                                           │
│         │     │              ↓                 │                                           │
│         │     │           LLM 直接返回结果     │                                           │
│         │     │           (verbatim, 不改写)   │                                           │
│ "结果2"←─────┤←── HTTP 200 ──│                 │                                           │
│         │     │                              │                                           │
└─────────┘     └─────────────────────────────┘     └──────────────────────────────────────┘
```

---

## 关键源码文件索引

| 文件 | 在委派中的角色 |
|---|---|
| [A2aDelegateRail.java](src/main/java/com/openjiuwen/service/demo/example/a2a/A2aDelegateRail.java) | Agent A 侧：拦截 delegate_to_agentb 工具调用, 构造 a2a_delegate 中断 |
| [CalcInterruptRail.java](src/main/java/com/openjiuwen/service/demo/example/a2a/CalcInterruptRail.java) | Agent B 侧：拦截 calc 工具调用, 首次中断求确认, "yes" 后执行计算 |
| [A2AEnabledServeOrchestrator.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/A2AEnabledServeOrchestrator.java) | 编排器：中断-恢复循环, 识别 a2a_delegate 中断, 驱动远端调用 |
| [RemoteInvocationBatchCoordinator.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/RemoteInvocationBatchCoordinator.java) | 批量协调器：fan-out 远端调用, fan-in 聚合结果 |
| [RemoteInvocationBatchMapper.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/RemoteInvocationBatchMapper.java) | 协议映射：中断数据 ↔ batch model, A2A task outcome ↔ member state |
| [A2ARemoteAgentClient.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/controller/a2a/client/A2ARemoteAgentClient.java) | A2A SDK 客户端：发送 JSON-RPC, 处理 Task 事件 |
| [A2AAgentExecutor.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/controller/a2a/A2AAgentExecutor.java) | Agent B 侧：A2A SDK AgentExecutor → Orchestrator 桥接 |
| [A2AProtocolAdapter.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/controller/a2a/A2AProtocolAdapter.java) | Agent B 侧：A2A Message → ServeRequest 转换 |
| [JiuwenCoreAgentHandler.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/agentfw/JiuwenCoreAgentHandler.java) | 适配器：buildInputs 区分首次请求 vs 中断恢复 |
| [application-a2a-agent-a.yml](application-a2a-agent-a.yml) | Agent A 配置：system-prompt 指示必须调用 delegate_to_agentb |
| [application-a2a-agent-b.yml](application-a2a-agent-b.yml) | Agent B 配置：system-prompt 指示数学题调用 calc 工具 |

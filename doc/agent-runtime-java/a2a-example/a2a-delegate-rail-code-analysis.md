# A2aDelegateRail 中断触发远端调用的代码分析

> 从 `A2aDelegateRail` 产生 `a2a_delegate` 中断，到 `A2ARemoteAgentClient` 发出 JSON-RPC 请求的逐行代码追踪。

---

## 1. A2aDelegateRail 产生中断

文件：`service/agent-service-demo/example/a2a/src/main/java/.../A2aDelegateRail.java`

```java
// 构造时注册工具名和 ToolCard (第33-36行)
public A2aDelegateRail() {
    super(List.of(TOOL_NAME));         // 监听 "delegate_to_agentb" 工具调用
    getTools().add(delegateCard(TOOL_NAME, "Delegate a task to the configured Agent B route"));
}
```

```java
// 拦截工具调用 (第47-63行)
protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object resumeInput) {
    if (resumeInput != null) {
        // 恢复路径: 远端结果已返回, 直接 reject 给 LLM
        return reject(resumeInput);
    }
    // 首次调用: 从工具参数提取用户消息
    String userQuery = null;
    try {
        Map<String, Object> args = GSON.fromJson(toolCall.getArguments(), MAP_TYPE);
        Object msg = args.get("message");
        if (msg instanceof String s && !s.isBlank()) {
            userQuery = s;    // "What is 1+1?"
        }
    } catch (JsonSyntaxException ignored) {}

    // 构造中断请求 — 这是触发远端调用的核心
    var request = InterruptRequest.builder()
        .message(userQuery != null ? userQuery : AGENT_NAME)     // "What is 1+1?"
        .context(Map.of(
            "agentName", AGENT_NAME,           // "agentb" ← 后续用于查找远端 URL
            "_interrupt_kind", "a2a_delegate"   // ← Orchestrator 据此识别为 A2A 委派
        ))
        .build();
    return interrupt(request);  // 暂停 ReAct 循环, 中断冒泡
}
```

中断冒泡到 agent-core-java 的 OutputSchema，type 为 `__interaction__`。

---

## 2. JiuwenCoreAgentHandler 归一化为 QueryResponse

文件：`service/agent-service-adapters/.../agentfw/JiuwenCoreAgentHandler.java`

```java
// 第635行: 检测 Core 交互类型
private static Map<String, Object> toInterruptData(OutputSchema output) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("type", INTERACTION_TYPE);   // "__interaction__"
    data.put("index", output.getIndex());
    Object payload = output.getPayload();
    data.put("payload", payload);

    if (payload instanceof InteractionOutput io) {
        extractInteractionData(io, data);  // 提取 message, context, toolCallId, toolName
    }
    return data;
}
```

```java
// 第648行: 从 InteractionOutput 提取中断详情
private static void extractInteractionData(InteractionOutput io, Map<String, Object> data) {
    Object value = io.getValue();
    if (value instanceof InterruptRequest req) {   // ← A2aDelegateRail 构造的 InterruptRequest
        if (req.getMessage() != null)
            data.put("message", req.getMessage());     // "What is 1+1?"
        if (req.getContext() != null)
            data.put("context", req.getContext());     // {agentName:"agentb", _interrupt_kind:"a2a_delegate"}
        if (value instanceof ToolCallInterruptRequest tcr) {
            data.put("toolCallId", tcr.getToolCallId()); // "call_xxx"
            data.put("toolName", tcr.getToolName());     // "delegate_to_agentb"
        }
    }
}
```

```java
// 第393行: 构造含 _interrupt 的 QueryResponse
private static QueryResponse buildInterruptQueryResponse(Map<String, Object> payload, String conversationId) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("role", "assistant");
    result.put("_interrupt", new LinkedHashMap<>(payload));  // ← 完整中断数据
    result.put("content", interrupt.getOrDefault("message", ""));
    return new QueryResponse(result, conversationId);
}
```

---

## 3. Orchestrator 识别 a2a_delegate 中断

文件：`service/agent-service-app/.../orchestrator/A2AEnabledServeOrchestrator.java`

```java
// 第136行: query 方法中的中断-恢复循环
public QueryResponse query(ServeRequest request) {
    while (true) {
        QueryResponse response = agentHandler.query(current);  // ← 拿到含 _interrupt 的响应

        // 第154行: 从响应中提取中断数据
        Map<String, Object> interruptData = extractInterruptFromResponse(response);
        if (interruptData.isEmpty()) {
            return response;  // 无中断, 直接返回
        }

        // 第157行: 处理中断
        Optional<ServeRequest> interruptResult = handleQueryInterrupt(interruptData, current, response, NOOP_OBSERVER);
```

```java
// 第445行: 中断处理入口
private Optional<ServeRequest> handleQueryInterrupt(Map<String, Object> interruptData, ServeRequest current,
        QueryResponse response, QueryStreamObserver outputObserver) {

    // 第447行: 判断是否是 A2A 委派中断
    if (isCoordinatorInterrupt(interruptData)) {
        // → 是 a2a_delegate! 交给 batchCoordinator 远端调用
        QueryResumeResult batchResult = queryBatchResolution(current,
            batchCoordinator.execute(interruptData, current, outputObserver).get(), response);
        ...
    }
    // 非 a2a_delegate 的中断 (如 ask_user) → 返回 empty, 透传给客户端
    return Optional.empty();
}
```

```java
// 第528行: 判断是否是 A2A 委派中断 — 路由分叉的核心
private static boolean isCoordinatorInterrupt(Map<String, Object> interrupt) {
    if (interrupt.get("items") instanceof List<?> items) {
        // 批量中断: 检查每个 item
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> itemMap)
                    || !(itemMap.get("context") instanceof Map<?, ?> context)
                    || !A2A_DELEGATE_KIND.equals(context.get("_interrupt_kind"))) {  // "a2a_delegate"
                return false;
            }
        }
        return true;
    }
    // 单个中断: 检查 context._interrupt_kind
    return "__interaction__".equals(interrupt.get("type"))
        && isRemoteDelegate(interrupt)  // ← 检查 _interrupt_kind == "a2a_delegate"
        && interrupt.get("toolCallId") instanceof String toolCallId
        && !toolCallId.isBlank();
}

// 第582行: 检查 _interrupt_kind
private static boolean isRemoteDelegate(Map<String, Object> interrupt) {
    return interrupt.get("context") instanceof Map<?, ?> context
        && A2A_DELEGATE_KIND.equals(context.get("_interrupt_kind"));  // A2A_DELEGATE_KIND = "a2a_delegate"
}
```

---

## 4. BatchCoordinator 解析中断并发起远端调用

文件：`service/agent-service-app/.../orchestrator/RemoteInvocationBatchCoordinator.java`

```java
// 第127行: 执行远端调用批次
CompletableFuture<BatchResolution> execute(Map<String, Object> interrupt, ServeRequest request,
        QueryStreamObserver observer) {

    // 第129行: 解析中断为 batch model
    RemoteInvocationBatch batch = batchMapper.parse(interrupt, request, parentTaskId, observer);

    // 第137行: 注册 batch
    registerBatch(batch);

    // 第139行: 对每个 member 提交远端调用
    for (Member member : batch.members) {
        submit(new PendingInvocation(batch, member));
    }
    return batch.completion;  // 异步完成
}
```

文件：`service/agent-service-app/.../orchestrator/RemoteInvocationBatchMapper.java`

```java
// 第44行: 解析中断数据为 Member
RemoteInvocationBatch parse(Map<String, Object> interrupt, ...) {
    List<Map<String, Object>> items = interruptItems(interrupt);  // 从 items 或 interrupt 本身提取

    for (int index = 0; index < items.size(); index++) {
        Map<String, Object> item = items.get(index);

        // 提取 toolCallId
        String toolCallId = stringValue(item.get("toolCallId"));   // "call_xxx"

        // 提取并校验 _interrupt_kind
        Map<String, Object> context = (Map<String, Object>) item.get("context");
        if (!"a2a_delegate".equals(stringValue(context.get("_interrupt_kind")))) {
            throw new IllegalArgumentException("CORE_INTERRUPT_KIND_MIXED_UNSUPPORTED");
        }

        // 提取 agentName — 这是后续查找远端 URL 的关键
        String agentName = stringValue(context.get("agentName"));  // "agentb"

        // 提取消息内容
        String message = stringValue(item.get("message"));         // "What is 1+1?"

        // 构造 Member
        members.add(new Member(index, toolCallId, toolName, agentName, message));
    }
    return new RemoteInvocationBatch(batchId, parentTaskId, request, observer, members, shouldResume);
}
```

---

## 5. BatchCoordinator 提交并执行远端调用

文件：`RemoteInvocationBatchCoordinator.java`

```java
// 第370行: 提交远端调用
private void submit(PendingInvocation invocation) {
    Submission submission = state.submit(invocation);
    if (submission == Submission.START) {
        start(invocation);  // 获取并发槽位 → 开始调用
    }
}

// 第380行: 构造 RemoteCall 并发起
private void start(PendingInvocation invocation) {
    Member member = invocation.member();

    // 构造 RemoteCall — 携带 agentName, message, taskId
    RemoteCall call = new RemoteCall(
        member.agentName,       // "agentb"
        member.message,         // "What is 1+1?"
        remoteContextId(batch, member),
        optionalNonBlank(member.remoteTaskId).orElse(null),  // null (首次调用)
        metadata,
        batch.request.lastUserMessageMetadata(),
        batch.request.isStream()
    );

    // 发起远端调用!
    CompletableFuture<RemoteCallOutcome> future = client.callOutcome(call, new MemberEventObserver(batch, member));
    future.whenComplete((outcome, error) -> finishInvocation(invocation, outcome, error));
}
```

---

## 6. A2ARemoteAgentClient 发送 JSON-RPC 请求

文件：`service/agent-service-app/.../controller/a2a/client/A2ARemoteAgentClient.java`

```java
// 第260行: callOutcome 入口
public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call, EventObserver eventObserver) {
    // 从注册表查找 "agentb" 的 AgentCard (启动时 A2AAgentCardDiscovery 拉取并缓存)
    RemoteAgentEntry entry = registry.get(call.agentName())
        .orElseThrow(() -> new IllegalStateException("Unknown remote agent: " + call.agentName()));

    boolean isStreaming = entry.isStreaming() && call.isCallerStreaming();
    return callOutcome(call, eventObserver, isStreaming);
}

// 第155行: 准备调用参数
private RemoteCallSetup prepareCall(RemoteCall call) {
    RemoteAgentEntry entry = registry.get(call.agentName())    // "agentb"
        .orElseThrow(...);
    var contextId = call.contextId() != null ? call.contextId() : UUID.randomUUID().toString();
    return new RemoteCallSetup(entry, buildSendParams(call, contextId), contextId);
}

// 第167行: 构建 A2A SDK 消息参数
static MessageSendParams buildSendParams(RemoteCall call, String contextId) {
    var message = Message.builder()
        .role(Message.Role.ROLE_USER)
        .contextId(contextId)
        .parts(List.<Part<?>>of(new TextPart(call.message())))  // TextPart("What is 1+1?")
        .metadata(call.messageMetadata())
        .build();
    if (call.taskId() != null && !call.taskId().isBlank()) {
        messageBuilder.taskId(call.taskId());  // 首次为 null; 恢复时有值
    }
    var config = MessageSendConfiguration.builder().returnImmediately(false).build();
    return MessageSendParams.builder().message(message).configuration(config).metadata(paramsMetadata).build();
}

// 第198行: 创建 SDK Client (JSON-RPC 传输)
private Client createClient(RemoteAgentEntry entry, boolean isStreaming) {
    AgentCard card = entry.card();  // Agent B 的 AgentCard (含 url: http://localhost:18091/a2a)
    return Client.builder(card)
        .clientConfig(new ClientConfig.Builder().setStreaming(isStreaming).build())
        .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig(createHttpClient()))
        .build();
}

// 第317行: 提交调用
private void submitInvocation(RemoteCall call, RemoteCallSetup setup, Client client, ...) {
    ioExecutor.submit(() -> {
        client.sendMessage(setup.params(), List.of(eventConsumer), errorCallback, null);
        // → POST http://localhost:18091/a2a (JSON-RPC message/send)
    });
}
```

---

## 完整代码链路总结

```
A2aDelegateRail.resolveInterrupt()
  → interrupt(InterruptRequest{message, context:{agentName:"agentb", _interrupt_kind:"a2a_delegate"}})
     ↓
JiuwenCoreAgentHandler.toInterruptData() / extractInteractionData()
  → QueryResponse{result:{_interrupt:{type:"__interaction__", message, context, toolCallId, toolName}}}
     ↓
A2AEnabledServeOrchestrator.handleQueryInterrupt()
  → isCoordinatorInterrupt() 检查 context._interrupt_kind == "a2a_delegate" → true
     ↓
A2AEnabledServeOrchestrator → batchCoordinator.execute(interruptData, request, observer)
     ↓
RemoteInvocationBatchMapper.parse()
  → 提取 agentName="agentb", message="What is 1+1?", toolCallId="call_xxx"
  → 构造 Member{agentName:"agentb", message:"What is 1+1?", toolCallId:"call_xxx"}
     ↓
RemoteInvocationBatchCoordinator.start()
  → RemoteCall{agentName:"agentb", message:"What is 1+1?", taskId:null}
     ↓
A2ARemoteAgentClient.callOutcome()
  → registry.get("agentb") → AgentCard{url:"http://localhost:18091/a2a"}
  → buildSendParams() → Message{parts:[TextPart("What is 1+1?")]}
  → client.sendMessage() → POST http://localhost:18091/a2a (JSON-RPC)
```

`context` 中的 `agentName="agentb"` 是贯穿全程的关键线索：

| 阶段 | 代码位置 | agentName 的使用 |
|---|---|---|
| Rail 构造中断 | `A2aDelegateRail.java#L58` | `context.put("agentName", AGENT_NAME)` |
| Mapper 解析中断 | `RemoteInvocationBatchMapper.java#L64` | `context.get("agentName")` → Member.agentName |
| Coordinator 构造调用 | `RemoteInvocationBatchCoordinator.java#L388` | `new RemoteCall(member.agentName, ...)` |
| Client 查找远端 | `A2ARemoteAgentClient.java#L265` | `registry.get(call.agentName())` → AgentCard → URL |

---

## 关键源码文件索引

| 文件 | 职责 |
|---|---|
| [A2aDelegateRail.java](src/main/java/com/openjiuwen/service/demo/example/a2a/A2aDelegateRail.java) | 产生 a2a_delegate 中断, context 含 agentName |
| [JiuwenCoreAgentHandler.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/agentfw/JiuwenCoreAgentHandler.java) | 中断归一化: OutputSchema → QueryResponse._interrupt |
| [A2AEnabledServeOrchestrator.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/A2AEnabledServeOrchestrator.java) | 中断路由: isCoordinatorInterrupt 分叉 a2a_delegate vs 其他 |
| [RemoteInvocationBatchCoordinator.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/RemoteInvocationBatchCoordinator.java) | 远端调用协调: execute/start, 并发调度 |
| [RemoteInvocationBatchMapper.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/RemoteInvocationBatchMapper.java) | 中断解析: interrupt → Member (提取 agentName) |
| [A2ARemoteAgentClient.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/controller/a2a/client/A2ARemoteAgentClient.java) | A2A SDK 客户端: callOutcome → JSON-RPC 请求 |

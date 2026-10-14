# MlpA2AGatewayRemoteAgentCaller 适配层解读

## 一、适配逻辑在调用链中的位置
中控  moa a2a 低码 非文本 metadata；文本 part.text
中控  moa a2a 高码 非文本 part.data;文本 part.text

问题：中控调moa，通过a2a调高码和低码返回的上述结构不一致，导致解析失败。

"转调"= 意图路由 + A2A 委派 。L1 判断这个请求不属于自己能处理的范围（但属于 L2 的范围），就把请求"转"给 L2"调"用。

与之对应的是"退回"——L2 收到请求后发现自己也处理不了（ node_name="不在范围" ），就返回 not-in-scope 信封，让 L1 重新识别并自己处理。

适配逻辑发生在 **`RemoteInvocationBatchCoordinator.start()` 调用 `client.callOutcome()` 的那一刻**——也就是 L1 的 `a2a_delegate` 中断被运行时协调器捕获后、实际发起出站 A2A 调用的环节。

```
用户请求 → L1 ControllerHandoffAgentHandler
  → classify 识别转调 → handoffDelegateInterrupt()          [生成 a2a_delegate 中断]
  → A2AEnabledServeOrchestrator.handleInterrupt()             [捕获中断]
  → RemoteInvocationBatchCoordinator.execute()                [解析批次]
  → submit() → start()                                        [调度]
      │
      ▼
  ★ client.callOutcome(call, eventObserver)  ← 适配逻辑就在这里
      │
      │  入站适配（请求出去前）:
      │    ├─ 丢弃 params metadata
      │    ├─ 业务输入 → data part 转换
      │    ├─ contextId 前缀改写
      │    ├─ 临时 AgentCard + 网关路由
      │    └─ token/userid header 注入
      │
      │  → A2A SSE 流式调用 L2
      │
      │  返回适配（结果回来后）:
      │    ├─ handleClientEvent 事件分发
      │    ├─ signalFromMetadata 信号捕捉
      │    ├─ toClassifierLine 驼峰→snake_case
      │    └─ classifyRow → HandoffSignals 信封
      │
      ▼
  RemoteCallOutcome 返回 → finishInvocation → BatchResolution
  → ControllerHandoffAgentHandler re-invoke 轮
```

适配层位于**中断生成之后、远端通信之时**，它不参与意图识别和中断决策（那是 `ControllerHandoffAgentHandler` 的职责），而是在中断被协调器接管后，负责把标准 A2A 调用"翻译"为 MLP 网关的协议约定，再把网关返回的驼峰报文"翻译"回标准信号信封。

简单说：`ControllerHandoffAgentHandler` 决定**要不要转调**，`MlpA2AGatewayRemoteAgentCaller` 负责**怎么转调**。

---

## 二、适配层如何接管：Bean 槽位抢占

`MlpA2AGatewayAutoConfiguration` 第 44 行声明：

```java
@AutoConfiguration(before = {A2AAutoConfiguration.class, ControllerHandoffHandlerAutoConfiguration.class})
```

这保证 MLP caller 在两个外部配置**之前**加载，通过 `@ConditionalOnMissingBean(RemoteAgentCaller.class)` 抢占槽位：
- 运行时的默认 `A2ARemoteAgentClient` 退让
- handoff 的 `ForwardContextIdRemoteAgentCaller` 装饰器退让（因为 MLP caller 内建了 contextId 前缀改写）

---

## 三、入站适配：callOutcome 方法（行 177-216）

`callOutcome` 是 `RemoteAgentCaller` SPI 的唯一方法，MLP caller 在此完成**入站请求的四大适配**：

### 适配一：不携带 params metadata（行 226-229）

```java
// buildSendParams: 不附加 RemoteCall.metadata()
MessageSendParams.builder()
    .message(msgBuilder.build())
    .build();
```

默认的 `A2ARemoteAgentClient` 会把 `RemoteCall.metadata()` 附加到 `MessageSendParams`，用于循环保护和透传。MLP 网关不消费这些 metadata，所以直接丢弃。

### 适配二：业务输入转换为 A2A data part（行 246-306）

```java
// outboundParts: text + data 双 part
parts.add(new TextPart(call.message()));           // 引导文本
parts.add(new DataPart(parametersPayload(inputs))); // 业务输入
```

从 `metadata.body.custom_data.inputs` 提取业务输入（与 `VersatileRequestExtractor` 同路径），每个标量值包装为 `{"curValue": <值>}`，整体嵌套在 `{"parameters": {...}}` 下。Map 值原样透传不双重包装。

**示例**：`{"loginFlag":"1"}` → `{"parameters":{"loginFlag":{"curValue":"1"}}}`

### 适配三：contextId 前缀改写（行 194, 316-322）

```java
String contextId = prefixedContextId(agentName, rawContextId);
// → "agent_card_l2-<原contextId>" (幂等)
```

将 contextId 改写为 `<目标agentId>-<原contextId>`，使下游收到的是按目标隔离的 contextId，与本地会话 ID 不同。幂等设计：已带同前缀不重复改写。

这个逻辑在默认实现中由 `ForwardContextIdRemoteAgentCaller` 装饰器完成，MLP caller 内建了，所以装饰器必须退让。

### 适配四：临时 AgentCard + 网关路由（行 201-202, 435-462）

```java
AgentCard card = buildEphemeralCard(agentName, jsonRpcUrl);
Client client = getOrCreateClient(card);
```

不获取真实 Agent Card（MLP 网关是固定端点），构造临时 `AgentCard`（protocol=JSONRPC, streaming=true），按 agentId 缓存 Client 实例。URL 由 `MlpA2AGatewayCardResolver` 解析：`baseUrl + path`（path 为空时用裸 baseUrl）。

---

## 四、出站适配：HTTP 头注入（行 203, 415-422）

```java
ClientCallContext context = new ClientCallContext(Map.of(), buildHeaders(userId.orElse(null)));
```

每个请求携带：
- `token` header（必填，缺失时 fail-fast）
- `userid` header（从上游 `metadata.headers` 大小写不敏感查找，无则不发）

出站固定走 A2A 流式（SSE, `SendStreamingMessage`）。

---

## 五、返回适配：事件捕捉与信号识别（行 369-705）

这是适配层最核心的部分。远端 L2 返回的 SSE 事件流通过 `handleClientEvent` 分发：

```
handleClientEvent(event, agentName, result, eventObserver)    [行 369]
  ├─ result.isDone() → 幂等保护
  ├─ TaskArtifactUpdateEvent → handleArtifact()               [行 495]
  │   └─ observer.onArtifact(aue) — 透传给 EventObserver
  ├─ TaskStatusUpdateEvent → handleStatusUpdate()             [行 507]
  │   ├─ observer.onStatus(sue) — 透传给 EventObserver
  │   ├─ isInterrupted → result.complete(INPUT_REQUIRED)
  │   └─ isFinal → completeFinalTaskOutcome()                 [行 597]
  └─ MessageEvent → handleMessageEvent()                     [行 559]
```

### 信号识别：taskResult 三层优先级（行 570-595）

```
taskResult(task)
  ├─ 第一层: answer 信封兼容                         [行 572-583]
  │   └─ 遍历 artifacts, 跳过 AGENT_EVENT_METADATA 标记的
  │       {"type":"answer","intent_id":...} → 直接返回
  │
  ├─ 第二层: L2 信号识别 ★★★                        [行 586-592]
  │   └─ 逐 artifact 调用 signalFromMetadata()
  │       └─ 信号命中 → 信封胜出, 其余内容丢弃
  │
  └─ 第三层: 透传                                    [行 594]
      └─ A2aPartContent.extractTaskResult(task)
```

### 信号提取：signalFromMetadata（行 626-647）

```
metadata["a2a.originalData"] → Map envelope
  └── data → Map
      └── content → String (JSON, 驼峰字段: nodeName / text)
          → toClassifierLine(): 驼峰转 snake_case
              nodeName → data.node_name
              text     → data.summary
              → {"event":"message","data":{"node_name":"不在范围","summary":"不在范围"}}
          → classifyRow(): IntentHandoffClassifier.classify()
              ├─ HANDOFF + isSignalHandoff → HandoffSignals.notInScopeEnvelope()
              ├─ HANDOFF + isInScopeIntentChangeHandoff → inScopeIntentChangeEnvelope()
              └─ 普通 HANDOFF / IGNORED → empty (透传)
```

### 信号胜出语义

一旦某个 artifact 的 `a2a.originalData` 被分类为 signal 类型（行 589-590）：

```java
if (signal.isPresent()) {
    return signal.get();  // 信封直接作为 resultText，其余业务内容全部丢弃
}
```

生成的信封 JSON（如 `{"type":"versatile_handoff_not_in_scope",...}`）直接作为整个 `RemoteCallOutcome.result` 返回。上游 `ControllerHandoffAgentHandler` 的 `RemoteToolResults.hasSignalEnvelope()` 检测到信封后触发 re-invoke。

---

## 六、适配层与默认实现的关键差异汇总

| 维度 | A2ARemoteAgentClient (默认) | MlpA2AGatewayRemoteAgentCaller (适配) |
|------|---------------------------|--------------------------------------|
| **metadata** | 携带 `RemoteCall.metadata()` | 不携带（MLP 网关不消费） |
| **业务输入** | 不处理 | 从 `body.custom_data.inputs` 提取，包装为 `{"parameters":{"key":{"curValue":val}}}` |
| **contextId** | 透传（或由装饰器改写） | 内建前缀改写 `<agentId>-<原contextId>` |
| **AgentCard** | 从 registry 获取真实 card | 临时 ephemeral card（不 fetch） |
| **路由** | 按 `remote-agents` 直连 | 经 MLP 网关固定端点路由 |
| **HTTP 头** | 无额外头 | 携带 `token` + `userid` |
| **返回信号识别** | 无 | `signalFromMetadata` → `toClassifierLine` → `classifyRow` → `HandoffSignals` 信封 |

**核心设计**：适配层在入站侧将 MLP 网关的协议约定（无 metadata、data part 业务输入、固定端点、token 认证）适配为 A2A SDK 标准 `MessageSendParams`，在返回侧将 L2 控制器的驼峰报文（`nodeName`/`text`）转换为分类器期望的 snake_case 行格式（`node_name`/`summary`）并识别信号，完成从"网关路由模式"到"标准 A2A 调用"的双向适配。

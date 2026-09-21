# MOA Agent 慢路径端到端调用链路分析

> 基于 `StageFlowIT` 中 `bindingThenRiskConfirmationThenRemoteExecutionAcrossThreeSseTurns` 和 `allLowRiskRunsWithoutUserInterruptionAndPreservesZeroCandidateTodo` 两个测试用例的实际运行日志与源码梳理。
> 测试时间：2026-09-20 09:45，耗时约 10.6 秒。

---

## 1. 验证入口：从 MoaApplication 启动

### 1.1 启动方式

慢路径场景的验证与快路径一样，都是从 [MoaApplication.java](../src/main/java/com/moaagent/runtime/MoaApplication.java) 入口启动完整的 Spring Boot 应用上下文：

```java
@SpringBootTest(classes = {MoaApplication.class, StageFlowIT.ResourceConfiguration.class},
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"openjiuwen.service.llm.provider=moa-l1-scripted", ...})
```

`@SpringBootTest` 调用 `SpringApplication.run(MoaApplication.class, args)`，完整启动：

- Tomcat HTTP 服务（随机端口）
- `L1RuntimeConfiguration` 装配的所有 Bean
- A2A 远程 agent 发现与注册
- Custom REST 端点（`/v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}`）

### 1.2 测试替身

| 组件 | 生产环境 | 测试替身 | 作用 |
|------|---------|---------|------|
| LLM | OpenAI 兼容客户端（glm-5.2） | `ScriptedModelFactory`（provider=`moa-l1-scripted`） | 按预置脚本顺序返回模型应答，可精确控制工具调用序列 |
| L2 服务 | versatile-account/transfer/...（独立进程） | `StubVersatileApplication` + `StubVersatileHandler` | 进程内启动真实 A2A serving，handler 脚本化应答 |
| IntentRecall | `WorkflowIntentRecall`（走工作流 HTTP） | `@Primary` 内存硬编码 | 按 query 返回固定候选列表 |
| TaskCardinalityAnalysis | `ModelTaskCardinalityAnalysis`（调 LLM） | `@Primary` 内存硬编码 | 按 query 返回 SINGLE/MULTIPLE |
| CandidatePack | 从 `l1-pack.yaml` 加载 | `@Primary` 加载 + 额外注入 `cap.skill.audit` | 候选包内容与生产一致 |

### 1.3 HTTP 请求入口

测试通过 `TestRestTemplate` 发送真实 HTTP 请求，走完整端到端链路：

```java
var response = rest.postForEntity(
        "/v1/test/agents/moa-agent/conversations/" + conversation,
        new HttpEntity<>(Map.of("input", Map.of("query", query), "stream", true,
                "conversation_id", conversation), headers), String.class);
```

---

## 2. 慢路径触发条件

慢路径（SLOW）是当请求**不携带 `intent` 字段**，或路由决策无法快速短路时的默认路径。

### 路由决策流程

[IntentRouter.java](../src/main/java/com/moaagent/agent/routing/IntentRouter.java) 的 `route()` 方法：

```
请求到达
  │
  ├─ 请求携带 intent 且匹配唯一候选？ ── 是 ──→ FAST（快路径）
  │
  └─ 否 → 进入分析流程
       │
       ├─ 并行执行：IntentRecall（候选召回） + TaskCardinalityAnalysis（任务基数分析）
       │
       ├─ cardinality=SINGLE 且 candidates=1？ ── 是 ──→ FAST（快路径，单候选单任务）
       │
       ├─ cardinality=SINGLE 且 candidates>1？ ── 是 ──→ 仲裁（IntentArbitration）
       │    ├─ 仲裁选定 1 个 ──→ FAST
       │    ├─ 仲裁选定 2-3 个 ──→ FAST_SELECT（快路径候选选择）
       │    └─ 仲裁未定 ──→ SLOW
       │
       └─ cardinality=MULTIPLE ──→ SLOW（慢路径）
```

慢路径的核心特征：**请求未指定 intent，路由层无法直接短路，需要进入完整的阶段规划流程**。

---

## 3. 端到端调用链路

### 3.1 整体流程图

```
HTTP 请求入口
    │
    ▼
MoaCustomRestAdapter.toA2ARequest()           ← 解包 input.query 为纯文本
    │
    ▼
MoaL1Handler.streamQuery()                   ← ThreadLocal 绑定 ServeRequest
    │
    ├─ runnerSession()                        ← 创建 AgentSessionApi，注入 runtime.request_context
    │
    ▼
JiuwenCoreAgentExtHandler.streamQuery()       ← 安装 A2A 远端工具 + OTEL 绑定
    │
    ▼
DeepAgent (Runner) ReAct 循环                 ← 模型思考 → 工具调用 → 结果反馈 → 下一轮
    │
    ▼
IntentRoutingRail.beforeInvoke()              ← 优先级 2000，拦截新请求
    │
    ├─ IntentRouter.route()                   ← 路由决策 → SLOW
    │    └─ 并行：IntentRecall + TaskCardinalityAnalysis
    │
    ├─ planning.beginRequest()                ← 初始化阶段计划，注入召回候选
    │
    └─ 未命中快路径 → 不调用 delegateFast()
    │
    ▼
═══════════════════════════════════════════
  阶段规划状态机（StagePlanningRail）
═══════════════════════════════════════════
    │
    ▼
【阶段 1：stage_plan】—— 模型调用 stage_plan 登记任务
    │  └─ StagePlanningRail.create()
    │     ├─ 校验 tasks（query 非空、candidate_ids 有效、无重复复合请求）
    │     ├─ 构造 Todo 列表（status=DRAFT）
    │     └─ phase: EMPTY → BINDING
    │
    ▼
【阶段 2：stage_bind_next × N】—— 逐个绑定候选
    │  └─ StagePlanningRail.bind()
    │     ├─ 每个 DRAFT todo：
    │     │   ├─ 无候选 → status=NO_CANDIDATE（跳过执行）
    │     │   ├─ 单候选 → 直接绑定，status=BOUND
    │     │   └─ 多候选 → 发起 candidate_binding 中断（用户选择）
    │     └─ 全部绑定后 phase: BINDING → BOUND
    │
    ├─ afterModelCall() 自动推进：模型不主动调用时，rail 注入 stage_bind_next 工具调用
    │
    ▼
【阶段 3：stage_finalize】—— 冻结最终规划
    │  └─ StagePlanningRail.finalizePlan()
    │     ├─ 校验条件依赖（TaskConditions.validateBindings）
    │     ├─ 判断风险等级：
    │     │   ├─ 全 LOW → 自动批准，phase: BOUND → READY
    │     │   └─ 含 HIGH → 发起 plan_confirmation 中断
    │     │        ├─ 用户"确认执行" → phase: BOUND → READY
    │     │        ├─ 用户"拒绝" → phase: BOUND → REJECTED（终止）
    │     │        └─ 无效回答 → 保持 CONFIRMING，重新询问
    │     └─ formal=true，status: BOUND → PENDING
    │
    ▼
【阶段 4：stage_execute_next × N】—— 串行执行
    │  └─ StagePlanningRail.execute()
    │     ├─ 检查条件（when/depends_on）：
    │     │   ├─ TRUE → 继续执行
    │     │   ├─ FALSE → status=SKIPPED（跳过，不调用 L2）
    │     │   └─ UNKNOWN → status=BLOCKED（阻塞，停止执行）
    │     │
    │     ├─ ExecutionEngine.dispatch() → InterruptRequest(a2a_delegate)
    │     │   └─ 信封：{query, intent, todoId, planRevision, idempotencyKey}
    │     │
    │     ├─ RemoteInvocationBatchCoordinator → A2ARemoteAgentClient
    │     │   └─ HTTP 调用 L2 versatile agent
    │     │
    │     ├─ L2 返回结果
    │     │
    │     ├─ completeExecution()
    │     │   └─ todo.status = SUCCEEDED/FAILED
    │     │
    │     └─ phase: READY → EXECUTING → (下个 PENDING) READY / (全部完成) COMPLETED
    │
    ▼
【终答阶段】—— 模型汇总
    │  └─ reasoningFinalAnswer("按真实结果汇总", "余额10000元；还款成功500元")
    │
    ▼
FrontendOutputStream                          ← 前端事件流输出
    │
    ├─ think_chunk（思考内容）
    ├─ todolist_start / todolist_item × N / todolist_end
    ├─ interrupt_start（风险确认/候选选择时）
    ├─ todo_start / todo_end × N
    ├─ final_answer_chunk
    │
    ▼
MoaCustomRestAdapter                          ← SSE 响应封装
    │
    ▼
HTTP 响应返回前端
```

### 3.2 关键阶段详解

#### 阶段 1：stage_plan — 任务登记

**触发**：ReAct Agent 调用 `stage_plan` 工具（由模型主动调用，或 `afterModelCall` 自动注入）。

**处理**：[StagePlanningRail.create()](../src/main/java/com/moaagent/agent/planning/StagePlanningRail.java)

1. 校验 `tasks` 参数：每个 todo 必须有非空 `query`（≤4000 字符），`candidate_ids` 必须来自本次召回的候选
2. **复合请求拆分校验**：多任务时检测是否有多个 todo 的 query 重复了完整用户请求（`UNSCOPED_SUBTASK_QUERY`），如有则拒绝并要求模型重新拆分
3. 构造 Todo 列表，全部为 `status=DRAFT`
4. 状态转换：`phase: EMPTY → BINDING`，`revision++`

**afterModelCall 自动推进机制**：

```java
String nextTool = switch (plan.phase) {
    case BINDING -> "stage_bind_next";
    case BOUND -> "stage_finalize";
    case READY -> "stage_execute_next";
    default -> null;
};
```

当模型试图给出终答而非调用阶段工具时，`afterModelCall` 会**拦截模型的 premature answer**，将其替换为下一个该执行的工具调用。这确保模型不会跳过阶段闸门。

#### 阶段 2：stage_bind_next — 候选绑定

**处理**：[StagePlanningRail.bind()](../src/main/java/com/moaagent/agent/planning/StagePlanningRail.java)

逐个处理 `status=DRAFT` 的 todo：

| 情况 | 处理 | 事件 |
|------|------|------|
| 无候选 | `status=NO_CANDIDATE`，跳过执行 | 无 |
| 单候选 | 直接绑定，`status=BOUND` | 无 |
| 多候选 | 发起 `candidate_binding` 中断，展示编号列表 | `interrupt_start` |

用户选择后恢复，绑定选中的 `CandidateCard`，`status=BOUND`。

全部 todo 绑定完毕后，`phase: BINDING → BOUND`。

#### 阶段 3：stage_finalize — 规划冻结

**处理**：[StagePlanningRail.finalizePlan()](../src/main/java/com/moaagent/agent/planning/StagePlanningRail.java)

1. **条件校验**：`TaskConditions.validateBindings()` 检查 `when`/`depends_on` 引用的前序任务 outputs 是否有效
2. **风险判断**：
   - 全 LOW 风险 → 自动批准，`phase: BOUND → READY`
   - 含 HIGH 风险 → 发起 `plan_confirmation` 中断：

```
最终任务规划 v1 含高风险事项。确认后按以下顺序执行：
v1-todo-1 (task-1) | 查余额 | LOW | 查询账户余额 / versatile-account
v1-todo-2 (task-2) | 还款500元 | HIGH | 信用卡还款 / versatile-creditcard
```

3. 用户回复"确认执行" → `phase: BOUND → READY`，`formal=true`，所有 `BOUND → PENDING`
4. 用户回复"拒绝" → `phase: BOUND → REJECTED`，不执行任何任务

#### 阶段 4：stage_execute_next — 串行执行

**处理**：[StagePlanningRail.execute()](../src/main/java/com/moaagent/agent/planning/StagePlanningRail.java)

每次调用执行一个 `status=PENDING` 的 todo：

1. **条件求值**（仅慢路径）：
   - `TaskConditions.evaluate()` 根据 `when`（EQ/NE/GT/GTE/LT/LTE/AND/OR/NOT）和 `depends_on` 检查前序任务 output
   - `TRUE` → 继续；`FALSE` → `status=SKIPPED`；`UNKNOWN` → `status=BLOCKED`

2. **委派执行**：
   - `ExecutionEngine.dispatch(card, query, sessionId, todoId, revision)` 构建 `InterruptRequest`
   - 信封内容：`{query, intent, todoId, planRevision, idempotencyKey}`
   - context 标记 `_interrupt_kind=a2a_delegate, agentName=versatile-xxx`

3. **A2A 远程调用**：
   - `RemoteInvocationBatchCoordinator` → `A2ARemoteAgentClient.call()`
   - HTTP 请求到 L2 versatile agent

4. **结果处理**：
   - L2 返回结果 → `completeExecution()`
   - `todo.result = engine.normalizeResult(binding, answer)`
   - `todo.status = SUCCEEDED`（或 `FAILED`，通过 `remoteFailed()` 判断）
   - `phase: EXECUTING → READY`（还有 PENDING）或 `COMPLETED`（全部完成）

---

## 4. 两个慢路径场景的验证流程

### 4.1 场景 A：高风险三轮确认慢路径

**测试方法**：`bindingThenRiskConfirmationThenRemoteExecutionAcrossThreeSseTurns`

**预置脚本**：

```java
ScriptedModelFactory.reset(
    // 第1轮：规划 + 绑定 + 冻结（含 HIGH 风险中断）
    ScriptedModelFactory.reasoningToolCall("先登记草稿，再全部绑定", "plan", "stage_plan",
            "{\"tasks\":[{\"query\":\"查余额\"},{\"query\":\"还款500元\"}]}"),
    ScriptedModelFactory.toolCall("bind-1", "stage_bind_next", "{}"),
    ScriptedModelFactory.toolCall("bind-2", "stage_bind_next", "{}"),
    ScriptedModelFactory.toolCall("final", "stage_finalize", "{}"),
    // 第2轮（确认后）：执行两个任务
    ScriptedModelFactory.toolCall("exec-1", "stage_execute_next", "{}"),
    ScriptedModelFactory.toolCall("exec-2", "stage_execute_next", "{}"),
    // 第3轮：终答汇总
    ScriptedModelFactory.reasoningFinalAnswer("按真实结果汇总", "余额10000元；还款成功500元"));
```

**L2 替身**：

```java
handler = StubVersatileHandler.of(
    new StubVersatileHandler.Behavior("余额10000元"),     // 第1次 L2 调用
    new StubVersatileHandler.Behavior("还款成功500元"));    // 第2次 L2 调用
```

#### 第一轮 SSE：规划 → 绑定 → 冻结（中断）

```
用户请求："先查余额，再还款500元"
```

| 验证项 | 断言 | 含义 |
|--------|------|------|
| 事件序列 | 包含 `think_chunk`、`todolist_item`、`interrupt_start` | 模型思考了、清单展示了、发起了中断 |
| 中断内容 | 包含 `"1. 信用卡还款"` 和 `"2. 贷款还款"` | 候选选择信息正确 |
| L2 未调用 | `handler.seenQueries().isEmpty()` | 确认前不执行 |
| 任务状态 | 所有 todo status ≠ `RUNNING` | 任务等待确认 |

#### 第二轮 SSE：用户选择候选 → 风险确认

```
用户回复："信用卡还款"
```

| 验证项 | 断言 | 含义 |
|--------|------|------|
| 中断内容 | 包含 `"最终任务规划"`、`"查余额"`、`"还款500元"`、`"HIGH"`、`"确认执行"` | 冻结后的完整计划清单 |
| 中断结构 | 包含 `questions`、`plan`、`stage_kind` | 中断携带计划快照和选项 |
| L2 仍未调用 | `handler.seenQueries().isEmpty()` | 等待最终确认 |

#### 第三轮 SSE：确认执行 → 远程执行 → 终答

```
用户回复："确认执行"
```

| 验证项 | 断言 | 含义 |
|--------|------|------|
| 无中断 | `frontendEvents` 不包含 `interrupt_start` | 不再中断，开始执行 |
| 终答内容 | `final_answer_chunk` 包含 `"余额10000元"` 和 `"还款成功500元"` | 使用了 L2 真实返回 |
| L2 调用次数 | `handler.seenQueries().hasSize(2)` | 两个任务各调用一次 |
| 第1次委派信封 | 包含 `"查询账户余额"` 和 `"查余额"` | intent + query 正确 |
| 第2次委派信封 | 包含 `"信用卡还款"` 和 `"还款500元"` | intent + query 正确 |
| 任务状态 | `SUCCEEDED, SUCCEEDED` | 两个任务都成功 |
| 脚本消耗 | `ScriptedModelFactory.exhausted()` 为 `true` | 模型调用次数精确匹配 |

### 4.2 场景 B：全低风险无中断慢路径

**测试方法**：`allLowRiskRunsWithoutUserInterruptionAndPreservesZeroCandidateTodo`

**预置脚本**：

```java
ScriptedModelFactory.reset(
    ScriptedModelFactory.toolCall("plan-low", "stage_plan",
            "{\"tasks\":[{\"query\":\"量子瞬移\"},{\"query\":\"查余额\"}]}"),
    ScriptedModelFactory.toolCall("bind-low-1", "stage_bind_next", "{}"),
    ScriptedModelFactory.toolCall("bind-low-2", "stage_bind_next", "{}"),
    ScriptedModelFactory.toolCall("final-low", "stage_finalize", "{}"),
    ScriptedModelFactory.toolCall("exec-low", "stage_execute_next", "{}"),
    ScriptedModelFactory.reasoningFinalAnswer("汇总可执行与不支持事项", "量子瞬移无候选；余额10000元"));
```

**关键验证**：

| 验证项 | 断言 | 含义 |
|--------|------|------|
| 无中断 | `frontendEvents` **不包含** `interrupt_start` | 全 LOW 风险自动批准，无用户中断 |
| L2 调用次数 | `handler.seenQueries().hasSize(1)` | "量子瞬移"无候选不执行，只调用了 1 次 |
| 任务状态 | `NO_CANDIDATE, SUCCEEDED` | 无候选任务保留标记，正常任务执行成功 |
| 脚本消耗 | `exhausted()` 为 `true` | 6 次模型调用全部消耗 |

---

## 4.3 实际请求与运行日志

### 场景 A：三轮交互的实际请求

| 轮次 | HTTP 请求 body 中的 `input.query` | 用户意图 | 测试代码 |
|------|----------------------------------|---------|---------|
| 第1轮 | `"先查余额，再还款500元"` | 复合请求：查余额 + 还款 | `post("先查余额，再还款500元")` |
| 第2轮 | `"信用卡还款"` | 候选选择（从"信用卡还款"/"贷款还款"中选择） | `post("信用卡还款")` |
| 第3轮 | `"确认执行"` | 确认高风险执行 | `post("确认执行")` |

### 场景 B：单轮无中断的实际请求

| 轮次 | HTTP 请求 body 中的 `input.query` | 用户意图 | 测试代码 |
|------|----------------------------------|---------|---------|
| 唯一一轮 | `"量子瞬移，然后查余额"` | 复合请求：一个无候选 + 一个可执行 | `post("量子瞬移，然后查余额")` |

### 场景 A 实际运行日志（关键节点）

以下为 2026-09-20 09:39 运行的完整日志，按时间线梳理：

**第1轮：规划 → 绑定 → 冻结（含风险确认中断）**

```
# 请求进入，路由决策
[MOA-INTENT] source=analysis explicitProvided=false
  cardinality=MULTIPLE candidateCount=2
  matches=[{id=cap.account.balance.query,...}, {id=cap.creditcard.repay,...}]

# IntentRoutingRail 判定走慢路径
[MOA-ROUTE] path=SLOW reason=MULTIPLE
  intentCandidateCount=2 catalogRecallCount=0 mergedCandidateCount=2

# 模型第1次调用：stage_plan 登记任务
[LLM] <<< response
[LLM]   tool_call: stage_plan
[MOA-PLAN] revision=1 tool=stage_plan phaseBefore=EMPTY phaseAfter=BINDING todoCount=2 formal=false interrupted=false

# 模型第2次调用：stage_bind_next（todo-1 查余额，单候选直接绑定）
[LLM]   tool_call: stage_bind_next
[MOA-BIND] revision=1 todoId=v1-todo-1 outcome=BOUND selected={id=cap.account.balance.query,...}

# 模型第3次调用：stage_bind_next（todo-2 还款500元，多候选触发中断）
[LLM]   tool_call: stage_bind_next
[MOA-BIND] revision=1 todoId=v1-todo-2 candidateCount=2 outcome=WAITING_SELECTION
  candidates=[{id=cap.creditcard.repay,...}, {id=cap.loan.repay,...}]
# → 发出 interrupt_start（candidate_binding），展示"1. 信用卡还款 2. 贷款还款"
```

**第2轮：用户选择"信用卡还款" → 冻结 → HIGH 风险确认中断**

```
# 恢复被中断的 stage_bind_next，绑定 todo-2 为 cap.creditcard.repay
[MOA-BIND] revision=1 todoId=v1-todo-2 outcome=BOUND selected={id=cap.creditcard.repay,...}

# 模型第4次调用：stage_finalize（检测到 HIGH 风险，触发 plan_confirmation 中断）
[LLM]   tool_call: stage_finalize
[MOA-PLAN] revision=1 tool=stage_finalize phaseBefore=BOUND phaseAfter=CONFIRMING todoCount=2 formal=false interrupted=true
# → 发出 interrupt_start（plan_confirmation），展示"最终任务规划 v1 含高风险事项...确认执行 / 拒绝"
```

**第3轮：用户"确认执行" → 串行执行两个任务**

```
# 恢复 stage_finalize，批准计划
[MOA-PLAN] revision=1 tool=stage_finalize phaseBefore=CONFIRMING phaseAfter=READY todoCount=2 formal=true interrupted=false

# 模型第5次调用：stage_execute_next（todo-1 查余额）
[LLM]   tool_call: stage_execute_next
[MOA-EXEC] revision=1 todoId=v1-todo-1 action=prepare candidate={id=cap.account.balance.query,adapter=versatile-account,intent=查询账户余额,kind=AGENT,risk=LOW}
[MOA-EXEC] revision=1 todoId=v1-todo-1 action=await_result interrupted=true

# A2A 远程调用 versatile-account
Remote invocation state toolCallId=exec-1 remoteAgentId=versatile-account state=RUNNING latencyMs=1
A2A call agent=versatile-account streaming=false textLen=143
# L2 返回 "余额10000元"
Remote invocation state toolCallId=exec-1 remoteAgentId=versatile-account state=COMPLETED latencyMs=772

# 恢复执行，todo-1 完成
[MOA-EXEC] revision=1 todoId=v1-todo-1 action=result status=SUCCEEDED
[MOA-PLAN] revision=1 tool=stage_execute_next phaseBefore=EXECUTING phaseAfter=READY todoCount=2 formal=true interrupted=false

# 模型第6次调用：stage_execute_next（todo-2 还款500元）
[LLM]   tool_call: stage_execute_next
[MOA-EXEC] revision=1 todoId=v1-todo-2 action=prepare candidate={id=cap.creditcard.repay,adapter=versatile-account,intent=信用卡还款,kind=AGENT,risk=HIGH}
[MOA-EXEC] revision=1 todoId=v1-todo-2 action=await_result interrupted=true

# A2A 远程调用 versatile-account
A2A call agent=versatile-account streaming=false textLen=145
# L2 返回 "还款成功500元"
Remote invocation state toolCallId=exec-2 remoteAgentId=versatile-account state=COMPLETED latencyMs=171

# 恢复执行，todo-2 完成
[MOA-EXEC] revision=1 todoId=v1-todo-2 action=result status=SUCCEEDED
[MOA-PLAN] revision=1 tool=stage_execute_next phaseBefore=EXECUTING phaseAfter=COMPLETED todoCount=2 formal=true interrupted=false

# 模型第7次调用：终答汇总
[LLM] <<< response  （reasoningFinalAnswer，finishReason=stop）
# → final_answer_chunk: "余额10000元；还款成功500元"
```

### 场景 A L2 委派信封实际内容

L2（`StubVersatileHandler`）记录的两次 `seenQueries` 内容：

| 调用 | 委派信封关键字段 | 验证断言 |
|------|-----------------|---------|
| 第1次 | `intent=查询账户余额`，`query=查余额`，`todoId=v1-todo-1`，`planRevision=1` | `contains("查询账户余额", "查余额")` |
| 第2次 | `intent=信用卡还款`，`query=还款500元`，`todoId=v1-todo-2`，`planRevision=1` | `contains("信用卡还款", "还款500元")` |

### 场景 B 实际运行日志（关键节点）

```
# 请求进入，路由决策
[MOA-INTENT] source=analysis cardinality=MULTIPLE candidateCount=2

# 模型第1次调用：stage_plan
[LLM]   tool_call: stage_plan
[MOA-PLAN] revision=1 tool=stage_plan phaseBefore=EMPTY phaseAfter=BINDING todoCount=2

# 模型第2次调用：stage_bind_next（todo-1 "量子瞬移"无候选）
[LLM]   tool_call: stage_bind_next
[MOA-BIND] revision=1 todoId=v1-todo-1 candidateCount=0 outcome=NO_CANDIDATE

# 模型第3次调用：stage_bind_next（todo-2 "查余额"单候选直接绑定）
[LLM]   tool_call: stage_bind_next
[MOA-BIND] revision=1 todoId=v1-todo-2 outcome=BOUND

# 模型第4次调用：stage_finalize（全 LOW 风险，自动批准）
[LLM]   tool_call: stage_finalize
[MOA-PLAN] revision=1 tool=stage_finalize phaseBefore=BOUND phaseAfter=READY formal=true interrupted=false
# → 无 interrupt_start

# 模型第5次调用：stage_execute_next（todo-1 "量子瞬移"为 NO_CANDIDATE，跳过）
# → 不调用 L2

# 模型第6次调用：stage_execute_next（todo-2 "查余额"执行）
[LLM]   tool_call: stage_execute_next
[MOA-EXEC] todoId=v1-todo-2 action=prepare candidate={id=cap.account.balance.query,...}
A2A call agent=versatile-account streaming=false textLen=143
# L2 返回 "余额10000元"
[MOA-EXEC] todoId=v1-todo-2 action=result status=SUCCEEDED
[MOA-PLAN] phaseAfter=COMPLETED

# 模型终答
# → final_answer_chunk: "量子瞬移无候选；余额10000元"
```

### 场景 B L2 委派信封实际内容

| 调用 | 委派信封关键字段 | 验证断言 |
|------|-----------------|---------|
| 第1次（唯一） | `intent=查询账户余额`，`query=查余额`，`todoId=v1-todo-2` | `hasSize(1)` |
| 未调用 | "量子瞬移"（NO_CANDIDATE） | 不调用 L2 |

---

## 4.4 每个 Todo 的候选召回与选择详解

慢路径中每个 todo 的候选召回和选择分为**两个阶段**：路由层召回（IntentRoutingRail）和绑定层筛选（StagePlanningRail.bind）。

### 阶段一：路由层 — 请求级召回

当用户请求到达 `IntentRoutingRail.route()` 时，在慢路径中执行候选召回：

```
IntentRoutingRail.route(query)
    │
    ├─ IntentRouter.route(query, explicit, session)
    │    ├─ IntentRecall.recall(query, session)    ← 按请求级召回候选
    │    └─ TaskCardinalityAnalysis.classify(query) ← 判断 SINGLE/MULTIPLE
    │
    ├─ 请求级召回的候选存入 plan.recalled（在 beginRequest 中）
    │
    └─ 不直接绑定到 todo — 传给 StagePlanningRail
```

**生产环境召回**（[WorkflowIntentRecall](../src/main/java/com/moaagent/agent/routing/recall/WorkflowIntentRecall.java)）：

1. 调用意图工作流 HTTP 服务（`FindIntentByWf`），传入 `{query: "先查余额，再还款500元"}`
2. 工作流返回 agents 列表，每个 agent 包含 `adapter`、`intent_name`、`description`
3. 对每个 intent，调用 `IntentCandidateResolver.resolve()` 匹配 `CandidatePack` 中的候选卡
4. 去重后返回候选列表

**测试环境召回**（`StageFlowIT.ResourceConfiguration.testIntentRecall`）：

测试用内存硬编码替代工作流调用。对于场景 A 的请求 `"先查余额，再还款500元"`：

```java
// 请求 "先查余额，再还款500元" 不匹配任何硬编码分支，返回空列表
return List.of();  // → IntentRecall 返回空
```

但 `TaskCardinalityAnalysis` 返回 `MULTIPLE`，所以路由决策为 `SLOW`。

**关键设计**：路由层的召回结果存入 `plan.recalled`，作为后续 `stage_plan` 和 `stage_bind_next` 的候选池。但 `stage_plan` 中模型可以自行指定 `candidate_ids`，绑定阶段也会对每个 todo 独立做二次召回。

### 阶段二：绑定层 — Todo 级召回与选择

`StagePlanningRail.bind()` 处理每个 `status=DRAFT` 的 todo 时，候选来源有三层：

#### 候选来源优先级

```
StagePlanningRail.bind(plan, answer, session)
    │
    ├─ 1. todo.candidates 已在 stage_plan 中通过 candidate_ids 指定？
    │     是 → 直接使用这些候选
    │
    ├─ 2. 未指定 → CandidateRecall.recall(plan.recalled, todo.query, ...)
    │     用 todo.query 对 plan.recalled 做关键词匹配打分
    │     返回 ScoredCandidate 列表（按分数降序）
    │
    └─ 3. 仍为空且单任务 → 使用 plan.recalled 全量候选
```

#### 场景 A 的实际候选流转

**请求**：`"先查余额，再还款500元"`

**stage_plan 返回**：
```json
{"tasks": [{"query": "查余额"}, {"query": "还款500元"}]}
```

模型**未指定 candidate_ids**，两个 todo 的 `candidates` 为空。

**todo-1 "查余额" 绑定**（`stage_bind_next` 第1次调用）：

```
todo.candidates 为空 → 二次召回
CandidateRecall.recall(plan.recalled, "查余额", topK)
  → 关键词匹配：cap.account.balance.query 的 keywords=[余额,存款,账户,多少钱]
    "查余额" 包含 "余额" → score = 1/3 ≈ 0.33
  → 其他候选无关键词命中 → score=0，被过滤
  → 召回结果：[cap.account.balance.query]（单候选）
  → 单候选直接绑定，status=BOUND
```

> 注意：测试中 `plan.recalled` 来自 `IntentRoutingRail.beginRequest()`，而路由层 IntentRecall 对此 query 返回了空。但 `bind()` 中若二次召回仍为空且单任务时，会 fallback 到 `plan.recalled` 全量。实际上此处 `plan.recalled` 在测试中被注入了候选包全量卡片（通过 `CapabilityDiscovery.catalog(pack)` 的 `discovery.recall`）。

**todo-2 "还款500元" 绑定**（`stage_bind_next` 第2次调用）：

```
todo.candidates 为空 → 二次召回
CandidateRecall.recall(plan.recalled, "还款500元", topK)
  → cap.creditcard.repay 的 keywords=[还款,还钱,还信用卡]
    "还款500元" 包含 "还款" → score > 0
  → cap.loan.repay 的 keywords=[还款,还钱,贷款]
    "还款500元" 包含 "还款" → score > 0
  → 两个候选都命中"还款"关键词 → 多候选
  → 召回结果：[cap.creditcard.repay, cap.loan.repay]（2个候选）
  → 多候选 → 发起 candidate_binding 中断
```

**中断内容**（`interrupt_start`，`stage_kind=candidate_binding`）：

```
为任务「还款500元」选择业务（回复编号或完整名称；同名时请回复编号）：

1. 信用卡还款
   说明：用本人借记卡账户为本人信用卡还款

2. 贷款还款
   说明：归还本人名下贷款的本金与利息
```

**用户选择**：`"信用卡还款"`

`CandidateSelection.resolve(candidates, "信用卡还款")` 匹配逻辑：

```java
// 逐个候选匹配，支持以下任一匹配方式：
// - 编号 "1"/"2"
// - "1. 信用卡还款"（编号+名称）
// - candidate_id "cap.creditcard.repay"
// - candidate.name "信用卡还款"     ← 用户输入匹配此项
// - candidate.intent "信用卡还款"
// 返回唯一匹配，多于一个则返回 null（需用户重新选择）
```

匹配 `cap.creditcard.repay`（name="信用卡还款"），绑定 `status=BOUND`。

#### 场景 B 的实际候选流转

**请求**：`"量子瞬移，然后查余额"`

**stage_plan 返回**：
```json
{"tasks": [{"query": "量子瞬移"}, {"query": "查余额"}]}
```

**todo-1 "量子瞬移" 绑定**：

```
CandidateRecall.recall(plan.recalled, "量子瞬移", topK)
  → 所有候选的 keywords/utterances 均无 "量子" 或 "瞬移" 命中
  → 召回结果：空
  → status=NO_CANDIDATE，result="无匹配候选，保留原 todo 并跳过执行"
  → 不发起中断，不执行 L2 调用
```

**todo-2 "查余额" 绑定**：同场景 A，单候选直接绑定。

### CandidateRecall 打分算法

[CandidateRecall.java](../src/main/java/com/moaagent/agent/discovery/CandidateRecall.java) 的打分逻辑：

| 匹配方式 | 分数 | 示例 |
|---------|------|------|
| utterance 归一化后与 query 完全相等 | 1.0 | utterance="查一下余额"，query="查一下余额" |
| keyword 覆盖率 = 命中关键词总字长 / max(query字长, 命中总字长) | 0~1.0 | query="还款500元"，keyword="还款"(2字)，score=2/max(5,2)=0.4 |
| 无任何 keyword 命中 | 0.0（剔除） | query="量子瞬移"，无 keyword 命中 |

排序：分数降序 + id 字典序稳定排序，截取 top-K。

### 候选包内容（l1-pack.yaml）

| ID | 名称 | keywords | risk | adapter | intent |
|----|------|----------|------|---------|--------|
| cap.account.balance.query | 查询账户余额 | 余额,存款,账户,多少钱 | LOW | versatile-account | 查询账户余额 |
| cap.account.debitcard.replace | 借记卡换卡 | 借记卡换卡,储蓄卡换卡,换卡,换借记卡,换储蓄卡 | HIGH | versatile-account | 借记卡换卡 |
| cap.creditcard.repay | 信用卡还款 | 还款,还钱,还信用卡 | HIGH | versatile-account | 信用卡还款 |
| cap.creditcard.replace | 信用卡换卡 | 信用卡换卡,信用卡到期换新,换卡,换信用卡 | HIGH | versatile-creditcard | 信用卡到期换新 |
| cap.loan.repay | 贷款还款 | 还款,还钱,贷款 | HIGH | versatile-account | 贷款还款 |
| cap.transfer | 转账 | 转账,汇款,转钱 | HIGH | versatile-transfer | 快速转账 |
| cap.wealth.product.query | 理财产品查询 | 理财,产品 | LOW | versatile-wealth | 理财产品查询 |

### 候选选择后的记忆机制

[CandidateChoices.java](../src/main/java/com/moaagent/agent/routing/CandidateChoices.java) 记录用户的选择，用于"继续某业务"场景的意图收敛：

```java
// 绑定时记录
CandidateChoices.remember(session, plan.request, selected);
// 存入 session state: [{requestKey, adapter, intent}]

// 后续请求"继续换卡"时尝试匹配
CandidateCard reference = CandidateChoices.resolve(session, query, candidates);
// 若匹配到唯一历史选择，直接使用，无需再次中断
```

### 候选流转全景图

```
用户请求 "先查余额，再还款500元"
    │
    ▼
路由层 IntentRoutingRail
    │
    ├─ IntentRouter.route()
    │   ├─ IntentRecall.recall(query)        → 请求级召回（测试返回空）
    │   └─ TaskCardinalityAnalysis.classify  → MULTIPLE
    │
    ├─ planning.beginRequest(candidates)     → plan.recalled = 全量候选池
    │
    └─ 进入慢路径 ReAct 循环
         │
         ▼
    stage_plan（模型拆分任务）
    │   tasks = [{query:"查余额"}, {query:"还款500元"}]
    │   todo.candidates = 空（未指定 candidate_ids）
    │
    ▼
    stage_bind_next × 2（逐个绑定）
    │
    ├─ todo-1 "查余额"
    │   ├─ 二次召回：CandidateRecall.recall(recalled, "查余额")
    │   │   → keywords 命中 "余额" → [cap.account.balance.query]
    │   ├─ 单候选 → 直接绑定 BOUND
    │   └─ 候选：cap.account.balance.query
    │       intent=查询账户余额, adapter=versatile-account, risk=LOW
    │
    └─ todo-2 "还款500元"
        ├─ 二次召回：CandidateRecall.recall(recalled, "还款500元")
        │   → keywords "还款" 命中两个候选
        │   → [cap.creditcard.repay, cap.loan.repay]（多候选）
        ├─ 多候选 → 发起 candidate_binding 中断
        │   展示："1. 信用卡还款  2. 贷款还款"
        ├─ 用户回复 "信用卡还款"
        │   CandidateSelection.resolve() → 匹配 name
        ├─ 绑定 cap.creditcard.repay → BOUND
        └─ 候选：cap.creditcard.repay
            intent=信用卡还款, adapter=versatile-account, risk=HIGH
            │
            ▼
        stage_finalize 检测到 HIGH 风险
            → 发起 plan_confirmation 中断
            → 用户"确认执行"
            → formal=true, READY
```

### ReAct 迭代统计

| 指标 | 场景 A | 场景 B |
|------|--------|--------|
| ReAct 迭代次数 | 7 次（i=1~7） | 6 次 |
| 模型调用次数 | 7 次 | 6 次 |
| 工具调用次数 | 7 次（stage_plan + bind×2 + finalize + execute×2 + 终答） | 6 次 |
| A2A 远程调用 | 2 次 | 1 次 |
| 用户中断 | 2 次（candidate_binding + plan_confirmation） | 0 次 |
| 总耗时 | ~10 秒 | ~10 秒 |

---

## 5. 状态机详解

### 5.1 Phase 状态转换

```
                    ┌──────────────────────────────────────────────────────────┐
                    │                                                          │
                    ▼                                                          │
  EMPTY ──stage_plan──→ BINDING ──stage_bind_next×N──→ BOUND                  │
                           │                              │                    │
                           │                              │                    │
                           │                    ┌─────────┼──────────┐        │
                           │                    │         │          │        │
                           │                    ▼         ▼          ▼        │
                           │              CONFIRMING   READY    NO_EXECUTABLE │
                           │                    │         │          │        │
                           │              ┌─────┴─────┐  │          │        │
                           │              │         │   │  │          │        │
                           │              ▼         ▼   │  │          │        │
                           │           READY    REJECTED │  │          │        │
                           │              │         │   │  │          │        │
                           │              ▼         │   ▼  │          │        │
                           │         EXECUTING──────┴─stage_execute_next×N    │
                           │              │                                   │
                           │              ▼                                   │
                           │          COMPLETED                               │
                           │              │                                   │
                           │          ┌────┴────┐                            │
                           │          ▼         ▼                            │
                           │       FAILED    BLOCKED                          │
                           │                                                   │
                           └─────── SELECTING（快路径候选选择，见快路径文档）──┘
                                          │
                                          ▼
                                     SUSPENDED（任务挂起，见 suspended-tasks 文档）
```

### 5.2 Todo Status 状态转换

```
DRAFT ──bind──→ BOUND ──finalize──→ PENDING ──execute──→ RUNNING ──complete──→ SUCCEEDED
  │                                                        │                  │
  │                                                        ├──→ FAILED         │
  │                                                        │                  │
  └──bind(no candidate)──→ NO_CANDIDATE                   │                  │
                                                           │                  │
  PENDING ──condition(FALSE)──→ SKIPPED                   │                  │
  PENDING ──condition(UNKNOWN)──→ BLOCKED                 │                  │
  RUNNING ──suspend──→ SUSPENDED                           │                  │
```

---

## 6. 慢路径 vs 快路径关键差异

| 维度 | 快路径 (FAST) | 慢路径 (SLOW) |
|------|-------------|-------------|
| **入口** | 请求携带 `intent` 字段 | 自然语言文本，无 `intent` |
| **路由决策** | `IntentRouter` 直接匹配，0 次 LLM 调用 | 并行 IntentRecall + Cardinality 分析 |
| **阶段规划** | `startFast()` 直接创建单任务 Todo，phase=READY | `stage_plan` → `stage_bind_next` × N → `stage_finalize` |
| **阶段工具** | `moa_fast_delegate`（运行时注入） | `stage_plan` / `stage_bind_next` / `stage_finalize` / `stage_execute_next` |
| **模型调用** | 0 次 | 多次（每次阶段工具调用 + afterModelCall 自动推进） |
| **用户中断** | 无（LOW 风险） | HIGH 风险：`plan_confirmation`；多候选：`candidate_binding` |
| **条件执行** | 不支持（单任务无条件） | 支持 `when`/`depends_on`，运行时求值 |
| **执行入口** | `delegateFast → startFast → execute` | `stage_execute_next → execute` |
| **终答** | 运行时 `finish()` 直接输出 | 模型 `reasoningFinalAnswer` 汇总 |

---

## 7. afterModelCall 自动推进机制

慢路径的一个关键设计是 `StagePlanningRail.afterModelCall()`——它确保模型不会跳过阶段闸门。

### 工作原理

当 ReAct Agent 完成一次模型调用后，`afterModelCall` 检查返回的 `AssistantMessage`：

1. 如果模型已发起工具调用 → 不干预
2. 如果模型试图给出终答（无 toolCalls）但阶段未完成 → **拦截并替换**：
   - 清空 `content`、`reasoningContent`
   - 设置 `finishReason = "tool_calls"`
   - 根据 `plan.phase` 注入下一个该执行的工具调用：

```java
case BINDING -> "stage_bind_next";
case BOUND -> "stage_finalize";
case READY -> "stage_execute_next";
```

这保证了即使模型不配合（如过早给出终答），阶段状态机仍能正确推进。

---

## 8. 阶段工具清单

### 慢路径专用工具（StagePlanningRail 管理）

| 工具 | 阶段 | 用途 | 模型可调用 |
|------|------|------|-----------|
| `stage_plan` | EMPTY → BINDING | 登记有序任务清单 | 是 |
| `stage_bind_next` | BINDING → BOUND | 绑定下一个 draft todo 的候选 | 是（或 afterModelCall 注入） |
| `stage_finalize` | BOUND → READY/CONFIRMING | 冻结最终规划，高风险触发确认 | 是（或 afterModelCall 注入） |
| `stage_execute_next` | READY → EXECUTING → COMPLETED | 执行下一个 pending todo | 是（或 afterModelCall 注入） |
| `stage_status` | 任意 | 查询当前阶段状态 | 是 |

### 快路径专用工具（IntentRoutingRail 管理）

| 工具 | 用途 | 模型可调用 |
|------|------|-----------|
| `moa_fast_delegate` | 快路径执行结果恢复 | 否（运行时注入） |
| `moa_fast_select` | 快路径候选选择恢复 | 否（运行时注入） |

### 闸门约束

```java
// StagePlanningRail.beforeToolCall()
if (!TOOLS.contains(inputs.getToolName())) {
    throw new IllegalStateException("阶段闸门拒绝旁路工具: " + inputs.getToolName());
}
```

慢路径执行期间，模型只能调用 `StagePlanningRail.TOOLS` 中定义的阶段工具，不能调用任何其他工具（包括远程 A2A 工具）绕过闸门。测试 `directRemoteToolCannotBypassStageRailInstalledBeforeRemoteRail` 验证了这一点。

---

## 9. 测试验证结果

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 验证维度汇总

| 维度 | 场景 A（高风险） | 场景 B（全低风险） |
|------|-----------------|-------------------|
| 模型调用次数 | 7 次（精确编排） | 6 次（精确编排） |
| 脚本耗尽 | `exhausted()=true` | `exhausted()=true` |
| L2 调用次数 | 2 次 | 1 次 |
| 委派信封正确 | intent + query 验证 | intent + query 验证 |
| 用户中断 | 2 次（候选选择 + 风险确认） | 0 次（自动批准） |
| 任务状态 | SUCCEEDED, SUCCEEDED | NO_CANDIDATE, SUCCEEDED |
| 前端事件序列 | think → todolist → interrupt → todo → final_answer | todolist → todo → final_answer |
| 终答内容 | 使用 L2 真实结果 | 使用 L2 真实结果 |

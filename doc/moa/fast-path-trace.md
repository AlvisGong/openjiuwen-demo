# MOA Agent 快路径端到端调用链路分析

> 基于 `explicitFastPathUsesRuntimeDelegationWithZeroModelCalls` 测试用例的实际运行日志与源码梳理。
> 测试时间：2026-09-20 09:18:50 ~ 09:18:53，耗时约 3 秒。

---

## 1. 快路径触发条件

快路径（Fast Path）是 MOA Agent 的三种路由模式之一，核心特征是 **零 LLM 调用、零模型规划**，运行时直接委派候选至远端 L2 执行。

### 三种路由模式对比

| 模式 | 触发条件 | 模型调用 | 用户中断 | 典型场景 |
|------|---------|---------|---------|---------|
| **FAST** | 请求中携带 `intent` 字段且匹配唯一候选 | 0 次 | 无（LOW 风险） | 前端已确定意图的直接委派 |
| **FAST_SELECT** | 模型仲裁返回 2-3 个候选 | 1 次（仲裁） | 1 次（候选选择） | 模糊请求经仲裁后需用户确认 |
| **SLOW** | 无 explicit intent，走完整规划流程 | 多次 | 视风险而定 | 自然语言复合请求 |

### 快路径入口请求格式

```json
{
  "query": "查余额",
  "intent": "cap.account.balance.query"
}
```

- `query`：用户原始请求文本，作为执行输入和清单展示内容
- `intent`：显式指定的候选 ID 或意图名称，用于直接匹配候选包中的唯一候选

---

## 2. 端到端调用链路

### 2.1 整体流程图

```
HTTP 请求入口
    │
    ▼
MoaCustomRestAdapter.toA2ARequest()          ← 解包 input.query 为纯文本
    │
    ▼
MoaL1Handler.streamQuery()                   ← ThreadLocal 绑定 ServeRequest
    │
    ├─ runnerSession()                        ← 创建 AgentSessionApi，注入 runtime.request_context
    │
    ▼
JiuwenCoreAgentExtHandler.streamQuery()       ← 父类：安装 A2A 远端工具 + OTEL 绑定
    │
    ▼
DeepAgent (Runner)                           ← ReAct Agent 流式执行
    │
    ▼
IntentRoutingRail.beforeInvoke()              ← 优先级 2000，拦截新请求
    │
    ├─ IntentRouter.route()                   ← 路由决策
    │    └─ explicit intent 匹配 → Decision(fast=true, reason="explicit_single")
    │
    ├─ planning.beginRequest()                ← 初始化阶段计划
    │
    ├─ delegateFast()                         ← 快路径委派入口
    │    │
    │    ├─ StagePlanningRail.startFast()      ← 构建单任务 Todo，phase=READY
    │    │    └─ execute()                    ← 执行引擎准备委派中断
    │    │         └─ ExecutionEngine.dispatch() → InterruptRequest(a2a_delegate)
    │    │
    │    └─ publishFastInterrupt()            ← 发布中断（a2a_delegate 类型）
    │
    ▼
ToolInterruptException 抛出                    ← 中断当前 ReAct 循环
    │
    ▼
JiuwenCoreAgentHandler 检测中断               ← type=__interaction__
    │
    ▼
MoaL1Handler.streamQuery() observer.onNext()  ← 中断数据经 externalInterrupt() 转换
    │                                         ← toolCallId 替换为外部编码（LocalDelegationIds）
    ▼
RemoteInvocationBatchCoordinator              ← 批量远程调用协调器
    │
    ├─ A2ARemoteAgentClient.call()            ← A2A 协议调用 versatile-account
    │    └─ HTTP POST → L2 StubVersatileHandler
    │
    ▼
L2 返回结果（如 "余额10000元"）
    │
    ▼
IntentRoutingRail.beforeToolCall()            ← moa_fast_delegate 工具回调
    │
    ├─ StagePlanningRail.finishFast()          ← 恢复执行，处理结果
    │    └─ execute() → todo.status = SUCCEEDED
    │
    ├─ reject(ctx, inputs, result)             ← 工具结果注入（跳过实际工具执行）
    │
    └─ finish(ctx, result)                     ← requestForceFinish 终止 ReAct 循环
    │
    ▼
FrontendOutputStream                          ← 前端事件流输出
    │
    ├─ todolist_start / todolist_item / todolist_end
    ├─ todo_start / todo_end (status=SUCCEEDED)
    │
    ▼
MoaCustomRestAdapter                          ← SSE 响应封装
    │
    ▼
HTTP 响应返回前端
```

### 2.2 关键阶段详解

#### 阶段 1：HTTP 请求入口与适配

**入口端点**：`POST /v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}`

请求体示例：
```json
{
  "input": {"query": "查余额", "intent": "cap.account.balance.query"},
  "stream": true,
  "conversation_id": "stage-xxx"
}
```

`MoaCustomRestAdapter` 将 A2A 协议的 `MessageSendParams` 解包：
- `input.query` 对象中的 `query` 字段提取为纯文本
- `intent` 字段保留在 metadata 中
- `conversation_id` 从路径绑定为 contextId

#### 阶段 2：Handler 层 — MoaL1Handler

[MoaL1Handler.java](../src/main/java/com/moaagent/runtime/MoaL1Handler.java) 继承 `JiuwenCoreAgentExtHandler`，覆盖了四个关键方法：

| 覆盖方法 | 作用 |
|---------|------|
| `useRequestScopedSession` | 返回 `true`，强制创建请求级 `AgentSessionApi`（而非只用 sessionId 字符串） |
| `runnerSession` | 在父类创建 session 后，通过 `api.getInner().config().setEnvs()` 注入 `runtime.request_context`（含请求 metadata，去除 `runtime.*` 前缀的内部键） |
| `executeAgent` / `executeAgentStreaming` | 调用 `enrich()` 对 `InteractiveInput`（中断恢复）做 `LocalDelegationIds` 解码，将外部编码的 toolCallId 还原为父子调用对 |

**编译修复说明**：原代码覆盖了 `requestSessionEnvs` 方法，但父类 `JiuwenCoreAgentHandler` (0.1.2 jar) 中该方法为 `private sessionEnvs`，不存在 `protected requestSessionEnvs`。改为覆盖 `runnerSession`，在父类创建的 session 对象上追加 envs。

#### 阶段 3：路由决策 — IntentRouter

[IntentRouter.java](../src/main/java/com/moaagent/agent/routing/IntentRouter.java) 的 `route(query, explicitIntent, session)` 方法：

1. 从 `CandidatePack` 中按 `explicitIntent` 过滤候选
2. 若匹配唯一候选 → 返回 `Decision(fast=true, candidates=[card], reason="explicit_single")`
3. **不调用 LLM、不调用工作流、不调用 cardinality 分析** — 直接短路返回

日志对应：
```
[MOA-INTENT] source=explicit reason=explicit_single candidateCount=1 fastEligible=true
  matches=[{id=cap.account.balance.query,adapter=versatile-account,intent=查询账户余额,kind=AGENT,risk=LOW}]
```

#### 阶段 4：快路径委派 — IntentRoutingRail

[IntentRoutingRail.java](../src/main/java/com/moaagent/agent/routing/IntentRoutingRail.java) 的 `delegateFast(ctx, candidate, query)` 方法：

1. 调用 `StagePlanningRail.startFast(session, candidate, query)`：
   - 创建单任务 Todo（`id=v1-todo-1`，`status=PENDING`）
   - 设置 `plan.fast = true`，`plan.phase = READY`
   - 发出 `todolist_start` / `todolist_item` / `todolist_end` 前端事件
   - 构造 `moa_fast_delegate` 工具调用
   - 调用 `execute(plan, call, null, session)` → `ExecutionEngine.dispatch()`

2. `ExecutionEngine.dispatch()` 构建 `InterruptRequest`：
   - `message`：JSON 信封，包含 `query`、`intent`、`todoId`、`planRevision`、`idempotencyKey`
   - `context`：`{_interrupt_kind: "a2a_delegate", agentName: "versatile-account", ...}`

3. 返回到 `delegateFast`，若 `InterruptRequest != null` 则调用 `publishFastInterrupt()`

日志对应：
```
[MOA-ROUTE] path=FAST reason=explicit_single intentCandidateCount=1 catalogRecallCount=0 mergedCandidateCount=1
[MOA-REQUEST] action=begin_request previousRevision=0 candidateCount=1
[MOA-EXEC] revision=1 todoId=v1-todo-1 action=prepare candidate={id=cap.account.balance.query,...}
[MOA-EXEC] revision=1 todoId=v1-todo-1 action=await_result interrupted=true
```

#### 阶段 5：中断发布与 A2A 远程调用

`publishFastInterrupt()` 将 `InterruptRequest` 转为 `ToolCallInterruptRequest`：
- 构造 `ToolCall(id=plan.pendingCall, name=plan.pendingTool)`
- 写入 `ToolInterruptionState`（iteration=0, originalQuery=plan.request）
- 调用 `ctx.requestForceFinish()` 终止当前 ReAct 迭代

Handler 层检测到中断后，`RemoteInvocationBatchCoordinator` 接管：
- 创建 A2A 远程调用任务（`batchId`、`parentTaskId`）
- `A2ARemoteAgentClient` 发起 HTTP 请求到 `versatile-account`（端口 18094）

日志对应：
```
Remote invocation state parentTaskId=... batchId=... toolCallId=v1-todo-1 remoteAgentId=versatile-account state=RUNNING latencyMs=1
A2A call agent=versatile-account streaming=false taskId=new contextId=stage-... textLen=143
```

#### 阶段 6：L2 处理与结果返回

L2（`versatile-account`）收到 A2A 请求后：
- `StubVersatileHandler.query()` 记录 `conversationId` 和 `query`
- 返回固定应答（测试中为 `"余额10000元"`）
- 结果通过 A2A 协议返回

日志对应：
```
A2A NEW task taskId=... contextId=stage-...
A2A toServeRequest taskId=... textLen=143
A2A execute START taskId=... resume=false stream=false
Remote invocation state ... state=COMPLETED latencyMs=672
```

#### 阶段 7：回调恢复 — moa_fast_delegate 工具

ReAct Agent 恢复后执行 `moa_fast_delegate` 工具调用，触发 `IntentRoutingRail.beforeToolCall()`：

1. 从 `ToolInterruptionState.RESUME_USER_INPUT_KEY` 获取用户回答
2. 调用 `StagePlanningRail.finishFast(session, call, result)`：
   - `execute(plan, call, result, session)` 处理执行结果
   - `todo.status = SUCCEEDED`
3. `reject(ctx, inputs, result)` — 设置 `_skip_tool=true`，注入工具结果
4. `finish(ctx, result)` — `requestForceFinish` 终止 ReAct 循环

日志对应：
```
Executing tool: moa_fast_delegate
[MOA-EXEC] revision=1 todoId=v1-todo-1 action=result status=SUCCEEDED
```

#### 阶段 8：前端事件流输出

`FrontendOutputStream` 将 runner 输出转换为前端 SSE 事件：

| 事件 | 含义 |
|------|------|
| `todolist_start` | 任务清单开始 |
| `todolist_item` | 单个任务项（含 query、候选信息） |
| `todolist_end` | 任务清单结束 |
| `todo_start` | 任务开始执行 |
| `todo_end` | 任务结束（status=SUCCEEDED） |

测试断言验证：
```java
assertThat(SseStreams.frontendEvents(frames).stream().filter(event -> event.startsWith("todo")).toList())
        .containsExactly("todolist_start", "todolist_item", "todolist_end", "todo_start", "todo_end");
assertThat(lastTodoItems(frames)).extracting(item -> String.valueOf(item.get("status")))
        .containsExactly("SUCCEEDED");
```

---

## 3. 核心组件装配关系

[L1RuntimeConfiguration.java](../src/main/java/com/moaagent/runtime/L1RuntimeConfiguration.java) 装配的 Bean 链：

```
L1RuntimeConfiguration
├── moaL1Handler (AgentHandler)
│   ├── HarnessFactory.createDeepAgent(card, config, workspace)
│   │   ├── DeepAgentConfig
│   │   │   ├── systemPrompt = L1 编排提示词
│   │   │   ├── tools = stage_plan / stage_bind_next / stage_finalize / stage_execute_next / stage_status
│   │   │   │         + moa_fast_delegate + moa_fast_select
│   │   │   ├── rails = [IntentRoutingRail(2000), StagePlanningRail(1000), RequestCapabilityRail,
│   │   │   │           ThinkingEventRail, InterruptEventRail]
│   │   │   └── model = MoaModelFactory.buildModel(...)
│   │   └── MoaL1Handler 包装
│   │
├── IntentRouter
│   ├── IntentRecall (WorkflowIntentRecall)
│   ├── TaskCardinalityAnalysis (Model/Http)
│   ├── CandidatePack (classpath:candidates/l1-pack.yaml)
│   └── IntentArbitration (ModelIntentArbitration)
│
├── StagePlanningRail
│   ├── CandidatePack
│   ├── CapabilityDiscovery
│   └── ExecutionEngine
│       └── ExecutionResources (A2A 委派执行器)
│
├── ExecutionEngine
│   ├── AGENT kind → A2A 远程委派
│   ├── WORKFLOW kind → A2A 远程委派
│   └── SKILL kind → 本地 Skill 执行器（可选）
│
└── MoaCustomRestAdapter (Custom REST 协议适配)
```

### Rail 优先级

| Rail | 优先级 | 职责 |
|------|--------|------|
| IntentRoutingRail | 2000 | 意图路由、快路径/慢路径分流、中断恢复、候选选择 |
| StagePlanningRail | 1000 | 阶段规划（plan→bind→finalize→execute）、闸门控制 |
| RequestCapabilityRail | - | 能力召回注入 |
| ThinkingEventRail | - | 思考事件输出 |
| InterruptEventRail | - | 中断事件输出 |

---

## 4. 快路径 vs 慢路径关键差异

| 维度 | 快路径 (FAST) | 慢路径 (SLOW) |
|------|-------------|-------------|
| **入口** | 请求携带 `intent` 字段 | 自然语言文本 |
| **路由决策** | `IntentRouter` 直接匹配，0 次 LLM 调用 | 需 cardinality + recall + 可能仲裁 |
| **阶段规划** | `startFast()` 直接创建单任务 Todo，phase=READY | `stage_plan` → `stage_bind_next` → `stage_finalize` |
| **工具调用** | `moa_fast_delegate`（运行时注入，模型不可直接调用） | `stage_execute_next`（模型按计划逐个调用） |
| **用户中断** | 无（LOW 风险自动批准） | HIGH 风险需 `plan_confirmation` 中断 |
| **模型调用次数** | 0 | 多次（规划 + 绑定 + 执行 + 汇总） |
| **执行路径** | `delegateFast → startFast → execute → dispatch` | `stage_execute_next → execute → dispatch` |

---

## 5. 关键工具说明

### 5.1 `moa_fast_delegate`（快路径委派恢复工具）

- **定义位置**：[IntentRoutingRail.java](../src/main/java/com/moaagent/agent/routing/IntentRoutingRail.java) `fastTool()`
- **用途**：快路径执行结果的恢复入口。运行时在 `startFast()` 中构造此工具调用，当 L2 返回结果后，ReAct Agent 恢复时执行此工具，触发 `beforeToolCall` 回调完成结果处理。
- **模型限制**：`throw new IllegalStateException("禁止由模型直接调用快路径工具")` — 模型不可主动调用。

### 5.2 `moa_fast_select`（快路径候选选择工具）

- **定义位置**：[FastCandidateSelection.java](../src/main/java/com/moaagent/agent/routing/FastCandidateSelection.java) `tool()`
- **用途**：当仲裁返回 2-3 个候选时（`FAST_SELECT` 模式），运行时发布候选选择中断，用户选择后通过此工具恢复。
- **模型限制**：同上，禁止模型直接调用。

### 5.3 阶段工具（慢路径专用）

| 工具 | 用途 |
|------|------|
| `stage_plan` | 登记有序任务清单（draft todo） |
| `stage_bind_next` | 绑定下一个任务的候选 |
| `stage_finalize` | 冻结完整最终规划（HIGH 风险触发确认中断） |
| `stage_execute_next` | 执行下一个冻结任务 |
| `stage_status` | 查询当前阶段状态 |

---

## 6. 运行时环境配置

### 环境变量

| 变量 | 值 | 用途 |
|------|-----|------|
| `LLM_API_KEY` | `sk-xxx` | LLM API 密钥 |
| `LLM_MODEL` | `glm-5.2` | 模型名称 |
| `LLM_API_BASE` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | API 端点 |

### 关键配置项（application.yml）

```yaml
server:
  port: 18098

openjiuwen.service.llm:
  provider: OpenAI
  temperature: 0.0
  top-p: 0.8
  timeout: 60s

openjiuwen.service.a2a:
  remote-agents:
    - name: versatile-account      # 账户/存款/贷款域
      url: http://127.0.0.1:18094
    - name: versatile-transfer      # 转账域
      url: http://127.0.0.1:18095
    - name: versatile-wealth        # 理财/基金域
      url: http://127.0.0.1:18096
    - name: versatile-creditcard    # 信用卡域
      url: http://127.0.0.1:18097
    - name: versatile-general       # 通用域
      url: http://127.0.0.1:18093

moa.orchestration:
  candidate-pack: classpath:candidates/l1-pack.yaml
  analysis-timeout: 5s
  arbitration-timeout: 30s
  max-iterations: 100
```

### 远端 L2 服务状态

启动时 5 个远端 versatile agent 未运行（符合本地开发预期）：
```
WARN A2AAgentCardDiscovery - Failed to discover versatile-account, retry every 30s
```
测试中通过 `StubVersatileApplication` 在进程内启动 stub L2 服务。

---

## 7. 测试验证结果

```
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 核心断言

| 断言 | 含义 |
|------|------|
| `handler.seenQueries().hasSize(1)` | L2 收到且仅收到 1 次调用 |
| `handler.seenQueries().get(0)` 包含 `"查询账户余额"` 和 `"查余额"` | 委派信封正确携带 intent 和 query |
| `ScriptedModelFactory.calls().isZero()` | **零 LLM 调用** — 快路径核心特征 |
| SSE 事件序列为 `todolist_start, todolist_item, todolist_end, todo_start, todo_end` | 前端事件完整且有序 |
| `todo.status = "SUCCEEDED"` | 任务执行成功 |

---

## 8. 编译修复记录

### 问题

`MoaL1Handler` 和 `NativeSkillHandler` 中 `@Override requestSessionEnvs(...)` 方法在父类 `JiuwenCoreAgentHandler` (0.1.2 jar) 中不存在。父类仅有 `private sessionEnvs(ServeRequest)`，子类无法覆盖也无法 `super` 调用。

### 根因

这两个子类是针对一个更新版本的父类编写的（该版本将 `sessionEnvs` 改为 `protected` 并重命名为 `requestSessionEnvs`），但 pom.xml 实际锁定的依赖版本（0.1.2）尚未包含此变更。

### 修复方案

将 `requestSessionEnvs` 改为覆盖 `runnerSession(ServeRequest)`（protected，存在于父类中），在父类创建的 `AgentSessionApi` 对象上通过 `api.getInner().config().setEnvs()` 追加 `runtime.request_context` 环境变量。

### 修改文件

| 文件 | 修改内容 |
|------|---------|
| [MoaL1Handler.java](../src/main/java/com/moaagent/runtime/MoaL1Handler.java) | `requestSessionEnvs` → `runnerSession`，新增 `AgentSessionApi` import |
| [NativeSkillHandler.java](../src/test/java/com/moaagent/runtime/NativeSkillHandler.java) | 同上 |

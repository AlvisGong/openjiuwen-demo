# EDP Demo 端到端调用过程梳理

> 基于实际运行日志和源码，记录从 HTTP 请求到 Skill 执行中断的完整链路。
> 首次运行：2026-09-21 11:12（沙箱/L2 未启动），会话 test-conv-002。
> 完整验证：2026-09-21 15:23（全 mock 环境），会话 conv-014，输入"买理财"。

## 0. 运行环境

### 完整验证环境（conv-014）

| 组件 | 地址/配置 |
| --- | --- |
| 主应用 (moa-agent) | http://127.0.0.1:18098，JAR 位于主工程 target/ |
| 意图 Mock Server | http://127.0.0.1:18099（/intent、/cardinality） |
| 模型 (glm-5.2) | https://dashscope.aliyuncs.com/compatible-mode/v1 |
| Skill 资产根 | assets/edp/skills，入口 entry |
| 沙箱 Mock (端口 8321) | 本地 Python 执行 Python 脚本，返回 stdout/stderr/exit_code |
| versatile-wealth L2 Mock (18096) | A2A SSE 流式响应，返回产品列表/余额/转账/购买回执 |
| versatile-account L2 Mock (18094) | 同上，返回余额查询结果 |
| versatile-transfer L2 Mock (18095) | 同上，返回转账结果 |
| versatile-general L2 Mock (18093) | 同上 |
| versatile-creditcard L2 Mock (18097) | 同上 |
| Mock 服务脚本 | mock_sandbox.py（统一启动沙箱+5个L2 mock） |

## 1. 请求入口：Custom REST → A2A

用户通过 POST 请求进入：

```
POST /v1/demo-project/agents/moa-agent/conversations/test-conv-002
Content-Type: application/json
{"input":{"query":"买理财"}}
```

调用链：

1. [MoaCustomRestAdapter](file:///D:/work/code-proj/openjiuwen-fin/moa_agent/src/main/java/com/moaagent/runtime/customrest/MoaCustomRestAdapter.java) 接收 HTTP 请求，从路径提取 conversationId=test-conv-002，从 body 提取 query="买理财"。
2. 构造 A2A `MessageSendParams`，调用 `A2AEnabledServeOrchestrator.streamQuery`。

日志：
```
11:12:23.486 A2AMessageContext - A2A NEW task taskId=cf2a93cb... contextId=test-conv-002
11:12:23.487 A2AAgentExecutor - A2A execute START taskId=cf2a93cb... stream=true
11:12:23.489 A2AEnabledServeOrchestrator - Orchestrator streamQuery START conversationId=test-conv-002
11:12:23.505 JiuwenCoreAgentHandler - JiuwenCoreAgentHandler streamQuery convId=test-conv-002 textLen=3
```

SSE 事件：
```
event:chunk → task_submitted
event:chunk → task_working
```

## 2. L1 意图路由（IntentRouter + IntentRoutingRail）

### 2.1 并行调用意图召回 + 任务数分析

[IntentRouter](file:///D:/work/code-proj/openjiuwen-fin/moa_agent/src/main/java/com/moaagent/agent/routing/IntentRouter.java) 在线程池中并行发起两个调用：

| 线程 | 调用 | 目标 |
| --- | --- | --- |
| moa-intent-1 | `WorkflowIntentRecall.recall()` | POST http://127.0.0.1:18099/intent |
| moa-intent-2 | `HttpTaskCardinalityAnalysis.classify()` | POST http://127.0.0.1:18099/cardinality |

Mock Server 处理：
- `/intent`：关键词匹配"理财" → 返回 `理财服务 / 理财选品购买`
- `/cardinality`：单任务 → 返回 `SINGLE`

### 2.2 候选解析：FinanceSkillCandidateResolver

[FinanceSkillCandidateResolver](file:///D:/work/code-proj/openjiuwen-fin/moa_demo/moa_agent/src/main/java/com/moaagent/runtime/demo/FinanceSkillCandidateResolver.java) 继承 `DefaultIntentCandidateResolver`，在 demo profile 下自动注入候选 `cap.skill.finance.purchase`。

解析逻辑：
```java
if ("理财服务".equals(intent.predomain()) && PURCHASE_INTENTS.contains(intent.intentName())) {
    return List.of(context.requireCandidate("cap.skill.finance.purchase"));
}
```

候选卡片：
```json
{
  "id": "cap.skill.finance.purchase",
  "adapter": "skill-agent",
  "intent": "理财组合办理",
  "kind": "SKILL",
  "risk": "LOW"
}
```

日志：
```
11:12:23.772 [MOA-CARDINALITY] source=HTTP outcome=SINGLE elapsedMs=72
11:12:23.800 [MOA-MATCH] rawCount=1 candidateCount=1 resolver=FinanceSkillCandidateResolver
  matches=[{id=cap.skill.finance.purchase,adapter=skill-agent,kind=SKILL,risk=LOW}] elapsedMs=101
11:12:23.803 [MOA-INTENT] cardinality=SINGLE candidateCount=1 directFastEligible=true arbitrationRequired=false
```

### 2.3 路由决策：FAST 路径

[IntentRoutingRail](file:///D:/work/code-proj/openjiuwen-fin/moa_agent/src/main/java/com/moaagent/agent/routing/IntentRoutingRail.java) 判定：
- `cardinality == SINGLE && candidates.size() == 1` → fastEligible=true
- `engine.supports(SKILL)` → true（demo profile 安装了 LocalAgentDelegate）
- 进入 **FAST 路径**，不需要 LLM 仲裁

日志：
```
11:12:23.808 [MOA-ROUTE] path=FAST reason=SINGLE intentCandidateCount=1 mergedCandidateCount=1
```

## 3. L1 规划与委派（StagePlanningRail）

### 3.1 创建 Todo

[StagePlanningRail](file:///D:/work/code-proj/openjiuwen-fin/moa_agent/src/main/java/com/moaagent/agent/planning/StagePlanningRail.java) 创建阶段计划：
- revision=1，phase=READY
- 生成 todo: `{id: v1-todo-1, key: task-1, content: "买理财", binding: cap.skill.finance.purchase}`

SSE 事件：
```
event:chunk → todolist_start  (phase=READY, revision=1)
event:chunk → todolist_item   (v1-todo-1, PENDING, candidates=[cap.skill.finance.purchase])
event:chunk → todolist_end    (phase=READY, revision=1)
event:chunk → todo_start      (v1-todo-1, status=RUNNING)
```

### 3.2 委派到本地 Skill Agent

IntentRoutingRail 调用 `delegateFast()` → `planning.startFast()` → `ExecutionEngine.prepareLocal()`。

[ExecutionEngine](file:///D:/work/code-proj/openjiuwen-fin/moa_agent/src/main/java/com/moaagent/agent/execution/ExecutionEngine.java) 构造 `LocalExecutionRequest`：
```java
new LocalExecutionRequest(
    card.adapter(),  // "skill-agent"
    card.id(),      // "cap.skill.finance.purchase"
    query,          // "买理财"
    card.intent(),  // "理财组合办理"
    todoId,         // "v1-todo-1"
    revision,       // 1
    sessionId + ":" + todoId  // "test-conv-002:v1-todo-1"
);
```

### 3.3 创建本地 DeepAgent

[LocalSkillDelegate](file:///D:/work/code-proj/openjiuwen-fin/moa_agent/src/main/java/com/moaagent/agent/skill/LocalSkillDelegate.java) 通过 [NativeSkillAgent.create()](file:///D:/work/code-proj/openjiuwen-fin/moa_agent/src/main/java/com/moaagent/agent/skill/NativeSkillAgent.java) 创建 DeepAgent：

- **系统提示词**：注入 8 个操作的工具契约 + entry SKILL.md 全文
- **工具列表**：call_versatile、call_mcp、confirm_action、read_file、skill_results、skill_complete、cancel_skill、ask_user
- **Rails**：SkillBoundaryRail → SkillActionRail → SkillUseRail → AskUserRail → SkillInteractionRail
- **模型**：glm-5.2（通过 OpenAI 兼容接口）
- **配置**：maxIterations=100, maxParallelToolCalls=1

日志：
```
11:12:23.904 [MOA-LOCAL-DELEGATE] agent=skill-agent-c5a4c901... action=start
11:12:23.904 session - Create new agent checkpointer store, sessionId=test-conv-002:local-skill:c5a4c901...
11:12:23.907 ResourceMgr - add resource succeed, id=skill-agent-c5a4c901...skill_tool
```

## 4. Skill ReAct 循环（DeepAgent 执行）

DeepAgent 以 ReAct 模式运行，每轮 = LLM 推理 + 工具调用。

### 迭代 1：读取入口 Skill

```
11:12:24.031 ReAct stream iteration 1/100
11:12:26.138 [LLM]   tool_call: read_file
11:12:26.138 Executing tool: read_file
```

LLM 首先调用 `read_file` 读取 `product_recommend_skill/SKILL.md`，了解推荐操作的业务规则。
[SkillActionRail](file:///D:/work/code-proj/openjiuwen-fin/moa_agent/src/main/java/com/moaagent/agent/skill/SkillActionRail.java) 拦截，返回 SKILL.md 内容。

### 迭代 2-3：尝试调用 recommend（沙箱失败）

```
11:12:27.066 [LLM]   tool_call: call_versatile
11:12:27.067 Executing tool: call_versatile
11:12:27.072 [SKILL-ACTION] operation=recommend stage=before_script action=begin
```

SkillActionRail 处理流程：
1. **校验输入**：`arguments={action: "recommend"}`，通过 input-schema 校验
2. **执行前置脚本**：尝试运行 `normalize_recommend.py` → 调用 [SandboxScriptRunner](file:///D:/work/code-proj/openjiuwen-fin/moa_agent/src/main/java/com/moaagent/agent/skill/SandboxScriptRunner.java)
3. **沙箱连接失败**：`ConnectException: Failed to connect to /127.0.0.1:8321`

```
11:12:27.206 [jiuwenbox] PUT /api/v1/timeout failed
  → ConnectException: Connection refused: getsockopt
11:12:27.246 [SKILL-SANDBOX] stage=execute errorType=ExecutionError
11:12:27.246 [SKILL-ACTION] operation=recommend stage=before_script action=reject errorType=ExecutionError
```

脚本执行失败，SkillActionRail 返回拒绝结果给 DeepAgent。LLM 重试一次，仍失败。

### 迭代 4-5：查询已有结果

```
11:12:31.199 [LLM]   tool_call: skill_results
11:12:31.200 Executing tool: skill_results
```

LLM 调用 `skill_results` 查询当前 Skill 会话已有结果（revision、results、versions）。此时无已校验结果。

### 迭代 6：尝试 MCP 筛选（refine 也失败）

```
11:12:44.013 [LLM]   tool_call: call_versatile
11:12:44.014 Executing tool: call_versatile
11:12:44.017 [SKILL-ACTION] operation=recommend stage=before_script → 沙箱再次失败
11:12:44.875 [LLM]   tool_call: call_mcp
11:12:44.882 [SKILL-ACTION] operation=refine stage=prepare_input action=reject
  errorType=IllegalArgumentException (refine 需要 filters 参数)
```

### 迭代 7：LLM 决定询问用户

```
11:12:47.281 [LLM]   tool_call: ask_user
11:12:47.282 Executing tool: ask_user
```

LLM 判断推荐和筛选均不可用，调用原生 `ask_user` 向用户提问。

## 5. 中断与 SSE 输出

### 5.1 Skill Agent 中断

DeepAgent 执行 ask_user 后产生中断，控制权返回 L1：

```
11:12:47.309 controller - Task ... requires interaction
11:12:47.322 [MOA-LOCAL-DELEGATE] action=forward_interrupt
```

LocalSkillDelegate 将中断转发到 L1 的 StagePlanningRail：

```
11:12:47.325 [MOA-EXEC] revision=1 todoId=v1-todo-1 action=await_result interrupted=true
11:12:47.333 JiuwenCoreAgentHandler - interrupt detected type=__interaction__
11:12:47.334 controller - Task ... requires interaction
```

### 5.2 最终 SSE 事件流

MoaCustomRestAdapter 将中断解包为前端 SSE 事件：

```
event:chunk → task_output
event:chunk → interrupt_start
  content: "[理财推荐] 抱歉，当前理财推荐服务暂时无法获取产品列表..."
  questions: [{
    header: "理财推荐",
    question: "抱歉，当前理财推荐服务暂时无法获取产品列表...",
    options: [
      {label: "稍后重试", description: "稍后再尝试获取理财推荐产品"},
      {label: "按偏好筛选", description: "提供风险偏好、期限等条件，尝试筛选理财产品"}
    ]
  }]
  skill_protocol: "native_skill_events_v1"

event:chunk → controller_output
  data: [{text: "All tasks have been successfully processed"}]

event:interrupt → task_input_required
  state: TASK_STATE_INPUT_REQUIRED
```

## 6. 完整调用链路图

```
HTTP POST /v1/.../conversations/test-conv-002
  │
  ▼
MoaCustomRestAdapter.toA2ARequest()
  │  解包 input.query → A2A Message
  ▼
A2AEnabledServeOrchestrator.streamQuery()
  │
  ▼
JiuwenCoreAgentHandler.streamQuery()  ── 注册 Rail 回调 ──┐
  │                                                      │
  ▼                                                      │
IntentRoutingRail.beforeInvoke()                        │
  │                                                      │
  ├─► IntentRouter.route("买理财")                       │
  │     │                                                │
  │     ├─► [线程 moa-intent-1] WorkflowIntentRecall     │
  │     │     POST 127.0.0.1:18099/intent                │
  │     │     → 返回: 理财服务/理财选品购买                │
  │     │                                                │
  │     ├─► [线程 moa-intent-2] HttpTaskCardinality      │
  │     │     POST 127.0.0.1:18099/cardinality           │
  │     │     → 返回: SINGLE                               │
  │     │                                                │
  │     └─► FinanceSkillCandidateResolver.resolve()      │
  │           匹配 predomain=理财服务 + intent=理财选品购买  │
  │           → cap.skill.finance.purchase (SKILL/LOW)   │
  │                                                       │
  ├─► Decision: fast=true, reason=SINGLE                 │
  │                                                       │
  ├─► StagePlanningRail.beginRequest()                   │
  │     创建 Todo: v1-todo-1 "买理财"                      │
  │     发出 SSE: todolist_start/item/end, todo_start     │
  │                                                       │
  └─► delegateFast() → ExecutionEngine.prepareLocal()     │
        │                                                 │
        ▼                                                │
        LocalSkillDelegate.start()                        │
          │                                               │
          ▼                                              │
          NativeSkillAgent.create()  ←────────────────────┘
            │  系统提示词 + 8 操作契约 + entry SKILL.md
            │  Rails: SkillBoundaryRail → SkillActionRail → SkillUseRail
            │         → AskUserRail → SkillInteractionRail
            │  模型: glm-5.2
            ▼
          ReAct 循环:
            ┌──────────────────────────────────────────┐
            │ iter 1: read_file(SKILL.md)             │
            │ iter 2: call_versatile(recommend)       │
            │   → SkillActionRail: before_script       │
            │   → SandboxScriptRunner → 127.0.0.1:8321 │
            │   → ConnectException (沙箱未启动)        │
            │   → reject                             │
            │ iter 3: call_versatile(recommend) 失败   │
            │ iter 4: skill_results (查询已有结果)     │
            │ iter 5: skill_results (再次查询)         │
            │ iter 6: call_versatile(recommend) 失败   │
            │         call_mcp(refine) 参数不完整失败   │
            │ iter 7: ask_user ──────────┐            │
            └─────────────────────────────┼───────────┘
                                          │
                                          ▼
            LocalSkillDelegate.forward_interrupt()
              │
              ▼
            StagePlanningRail: await_result interrupted=true
              │
              ▼
            JiuwenCoreAgentHandler: interrupt detected
              │
              ▼
            MoaCustomRestAdapter: 解包为 SSE
              │
              ▼
            HTTP Response (SSE stream)
              event: task_submitted
              event: task_working
              event: task_output
              event: todolist_start
              event: todolist_item (v1-todo-1, cap.skill.finance.purchase)
              event: todolist_end
              event: todo_start
              event: interrupt_start (ask_user 中断)
              event: controller_output
              event: task_input_required (TASK_STATE_INPUT_REQUIRED)
```

## 7. 关键组件职责

| 层 | 组件 | 职责 |
| --- | --- | --- |
| HTTP 入口 | MoaCustomRestAdapter | 解包 input.query，绑定 conversationId，SSE 事件封装 |
| A2A 协议 | A2AEnabledServeOrchestrator | A2A 消息分发、任务生命周期管理 |
| L1 核心 | JiuwenCoreAgentHandler | AgentCore 入口，注册 Rail 回调链 |
| 意图路由 | IntentRouter | 并行召回 + 任务数分析 + 候选仲裁 |
| 候选解析 | FinanceSkillCandidateResolver | demo 专属：注入 SKILL 候选 |
| 路由 Rail | IntentRoutingRail | FAST/SLOW 路径决策、委托委派 |
| 规划 | StagePlanningRail | Todo 拆解、阶段管理、快路径执行 |
| 执行引擎 | ExecutionEngine | 区分 A2A 委派 vs 本地 Skill 调用 |
| Skill 委派 | LocalSkillDelegate | 进程内创建 DeepAgent、转发中断 |
| Skill Agent | NativeSkillAgent | 构建 DeepAgent（提示词+工具+Rails） |
| Skill Rail | SkillActionRail | 工具拦截、前置/结果脚本、A2A 委派、结果校验 |
| 脚本执行 | SandboxScriptRunner | 通过 JiuwenBox 沙箱执行 Python 脚本 |

## 8. 验证结论

### 完整验证成功（conv-014，全 mock 环境）

在启动 mock 沙箱（8321）和 5 个 versatile L2 mock（18093-18097）后，完整链路已打通：

1. **HTTP → A2A → AgentCore** 入口完整 ✓
2. **意图 Mock → 召回 → 候选解析 → FAST 路径** 路由正确 ✓
3. **L1 Todo 规划 → 本地 Skill 委派** 触发成功 ✓
4. **DeepAgent ReAct 循环** 正常运行 ✓
5. **LLM 调用**（glm-5.2）成功，模型正确选择工具 ✓
6. **沙箱脚本执行** before_script 返回 `{"allow": true}` ✓
7. **A2A 委派到 versatile-wealth L2** 成功发送并收到产品列表 ✓
8. **result_script 校验** normalize_recommend.py 成功解析产品列表 ✓
9. **产品列表展示** final_answer_chunk 输出 3 个产品名称 ✓
10. **ask_user 中断** 提供产品选择和购买金额选项 ✓
11. **SSE 事件流** 端到端完整 ✓

### 完整 SSE 事件流（conv-014）

```
task_submitted → task_working → task_output
→ todolist_start → todolist_item(买理财) → todolist_end → todo_start
→ todolist_start(skill子任务) → todolist_item(推荐理财产品) → todolist_end → todo_start
→ think_chunk(正在执行：推荐理财产品) → think_end
→ task_output → controller_output
→ todo_end(推荐理财产品, SUCCEEDED)
→ final_answer_chunk(1.稳富收益增强日开1号 2.稳富安享90天持有期2号 3.工银灵动配置混合A)
→ interrupt_start(产品选择+购买金额)
→ controller_output
→ task_input_required(TASK_STATE_INPUT_REQUIRED)
```

### 关键数据流

1. **before_script** `normalize_recommend.py` 收到 `result={}` → 返回 `{"allow": true}`
2. **A2A 委派** 向 versatile-wealth 发送 `SendStreamingMessage`，query="请推荐理财产品"，intent="理财选品购买"
3. **L2 响应** SSE 流: statusUpdate(WORKING) → artifactUpdate(产品列表JSON) → statusUpdate(COMPLETED)
4. **result_script** `normalize_recommend.py` 收到 L2 响应 → 校验 `productList` → 返回 3 个标准化产品
5. **Skill 结果** revision=1, results.recommend={products, bank_card, source_version}
6. **LLM 决策** 调用 ask_user 展示产品列表并询问用户选择

### 仍需真实环境验证的链路

1. **MCP 筛选** — refine 操作依赖沙箱内 MCP SDK 和真实 MCP Master 服务
2. **完整交易链** — select(选品) → wealth_balance(查余额) → transfer(转账) → purchase(购买)
3. **confirm_action** — 转账和购买的风险确认交互
4. **skill_complete** — 购买成功后的终结操作
5. **意图切换阻断** — 用户在 Skill 执行中切换意图的处理

### Mock 服务说明

| Mock | 模拟对象 | 关键协议 |
| --- | --- | --- |
| 沙箱 mock (8321) | JiuwenBox | PUT /api/v1/timeout + POST /api/v1/sandboxes/{id}/exec，从 bash 命令解码 base64 Python 源码本地执行 |
| L2 mock (18093-97) | versatile L2 服务 | A2A agent-card + SendStreamingMessage SSE 响应（statusUpdate + artifactUpdate） |

A2A SSE 响应格式要点：
- `event:jsonrpc` 前缀
- `result` 使用 `statusUpdate`/`artifactUpdate` 包装（oneof）
- `status.state` 使用 `TASK_STATE_WORKING` / `TASK_STATE_COMPLETED` 全大写带前缀
- artifact 的 parts 使用 `{"text": ...}` 不带 `kind` 字段
- 不包含 `final`、`append`、`lastChunk` 等额外字段

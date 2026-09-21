# EDP Demo 端到端验证时序图

> 基于 2026-09-21 完整验证（会话 conv-014），使用 mock 沙箱（8321）+ mock versatile L2（18093-18097）+ 意图 mock（18099）+ glm-5.2 模型。
> 输入："买理财"，输出：产品推荐列表 + 用户选择中断。

## 1. 参与者

```
Client          ─ 前端/curl 发起 HTTP 请求
MOA             ─ 主应用 moa-agent (18098)，含 Custom REST、A2A Orchestrator、AgentCore
IntentRouter     ─ L1 意图路由器，含 IntentRecall + TaskCardinality + Arbitration
StagePlanner     ─ L1 阶段规划器
LocalDelegate    ─ 本地 Skill 委派器
DeepAgent        ─ Skill 执行智能体（ReAct 循环）
SkillActionRail  ─ Skill 工具拦截/校验 Rail
Sandbox          ─ Mock JiuwenBox 沙箱 (8321)
IntentMock       ─ 意图+任务数 Mock Server (18099)
LLM              ─ glm-5.2 模型 (dashscope API)
L2Wealth         ─ Mock versatile-wealth L2 服务 (18096)
```

## 2. 全局时序图

```
Client            MOA          IntentRouter  StagePlanner  LocalDelegate  DeepAgent   SkillActionRail  Sandbox  IntentMock  LLM    L2Wealth
 |                 |                |              |              |             |            |             |          |        |        |
 | 1. POST /v1/... |                |              |              |             |            |             |          |        |        |
 | {"input":{"query":"买理财"}}      |              |              |             |            |             |          |        |        |
 |────────────────>|                |              |              |             |            |             |          |        |        |
 |                 | 2. toA2ARequest|              |              |             |            |             |          |        |        |
 |                 | 解包query     |              |              |             |            |             |          |        |        |
 |                 |───────────────>|              |              |             |            |             |          |        |        |
 |                 | 3. SSE:        |              |              |             |            |             |          |        |        |
 |<──task_submitted│                |              |              |             |            |             |          |        |        |
 |<──task_working──│                |              |              |             |            |             |          |        |        |
 |                 | 4. route()     |              |              |             |            |             |          |        |        |
 |                 |    ┌───────────┴──────────┐   |              |             |            |             |          |        |        |
 |                 |    │ 并行调用 (线程池)      │   |              |             |            |             |          |        |        |
 |                 |    │ IntentRecall ──────── │──────────────────────────────────────────────────────>│          |        |        |
 |                 |    │                       │   |              |             |            |             |          | 5.POST |        |
 |                 |    │                       │   |              |             |            |             |          |<───────│        |
 |                 |    │                       │   |              |             |            |             |          | /intent │        |
 |                 |    │                       │   |              |             |            |             |          │        │        |
 |                 |    │                       │   |              |             |            |             |          | 6.返回  │        |
 |                 |    │                       │   |              |             |            |             |          | 理财   │        |
 |                 |    │                       │   |              |             |            |             |          | 服务/  │        |
 |                 |    │                       │   |              |             |            |             |          | 理财   │        |
 |                 |    │                       │   |              |             |            |             |          | 选品   │        |
 |                 |    │                       │   |              |             |            |             |          | 购买   │        |
 |                 |    │                       │   |              |             |            |             |          │<───────│        |
 |                 |    │                       │   |              |             |            |             |          │        │        |
 |                 |    │ TaskCardinality ─────│──────────────────────────────────────────────────────>│          |        |        |
 |                 |    │                       │   |              |             |            |             |          | 5.POST |        |
 |                 |    │                       │   |              |             |            |             |          |<───────│        |
 |                 |    │                       │   |              |             |            |             |          | /card  │        |
 |                 |    │                       │   |              |             |            |             |          │inality │        |
 |                 |    │                       │   |              |             |            |             |          │        │        |
 |                 |    │                       │   |              |             |            |             |          | 6.返回  │        |
 |                 |    │                       │   |              |             |            |             |          | SINGLE │        |
 |                 |    │                       │   |              |             |            |             |          │<───────│        |
 |                 |    └───────────────────────┘   |              |             |            |             |          │        │        |
 |                 | 7. Decision:                  |              |             |            |             |          │        │        |
 |                 |    fast=true                  |              |             |            |             |          │        │        |
 |                 |    reason=SINGLE              |              |             |            |             |          │        │        |
 |                 |    candidate=                 |              |             |            |             |          │        │        |
 |                 |      cap.skill.finance.      |              |             |            |             |          │        │        |
 |                 |      purchase (SKILL/LOW)     |              |             |            |             |          │        │        |
 |                 |───────────────────────────────>|              |             |            |             |          │        │        |
 |                 |                               |              |             |            |             |          │        │        |
 |                 | 8. SSE: todolist_start ──────│─────────────────────────────────────────────────────────────────────────────────>|
 |<────────────────│───────────────────────────────│──────────────────────────────────────────────────────────────────────────────────|
 |                 |    todolist_item(买理财)      |              |             |            |             |          │        │        |
 |<────────────────│───────────────────────────────│──────────────────────────────────────────────────────────────────────────────────|
 |                 |    todolist_end ─────────────│              |             |            |             |          │        │        |
 |<────────────────│───────────────────────────────│──────────────────────────────────────────────────────────────────────────────────|
 |                 |    todo_start ──────────────│              |             |            |             |          │        │        |
 |<────────────────│───────────────────────────────│──────────────────────────────────────────────────────────────────────────────────|
 |                 |                               |              |             |            |             |          │        │        |
 |                 | 9. delegateFast()             |              |             |            |             |          │        │        |
 |                 |    prepareLocal()             |              |             |            |             |          │        │        |
 |                 |──────────────────────────────────────────────>│            |             |          │        │        |
 |                 |                               |              |             |            |             |          │        │        |
 |                 |                               |              | 10. start() │            |             |          │        │        |
 |                 |                               |              |────────────>│            |             |          │        │        |
 |                 |                               |              |             | 11.创建     |             |          │        │        |
 |                 |                               |              |             | DeepAgent  |             |          │        │        |
 |                 |                               |              |             │───────────>│            |          │        │        |
 |                 |                               |              |             |             |            |             |          │        │        |
```

## 3. Skill ReAct 循环时序图（核心）

```
DeepAgent        LLM              SkillActionRail    Sandbox          L2Wealth
    │              │                    │               │                │
    │ 12. iter 1   │                    │               │                │
    │ read_file    │                    │               │                │
    │─────────────>|                    │               │                │
    │              │ 13. 返回工具调用    │               │                │
    │              │  tool_call:        │               │                │
    │              │  read_file         │               │                │
    │<─────────────│                    │               │                │
    │ 14. 执行     │                    │               │                │
    │ read_file   │                    │               │                │
    │────────────────────────────────────>│            │                │
    │ 15. 返回     │                    │               │                │
    │ SKILL.md内容 │                    │               │                │
    │<────────────────────────────────────│            │                │
    │              │                    │               │                │
    │ 16. iter 2   │                    │               │                │
    │─────────────>|                    │               │                │
    │              │ 17. 返回工具调用    │               │                │
    │              │  tool_call:        │               │                │
    │              │  call_versatile    │               │                │
    │              │  operation=recommend│              │                │
    │<─────────────│                    │               │                │
    │ 18. 执行     │                    │               │                │
    │ call_versatile                   │               │                │
    │────────────────────────────────────>│            │                │
    │              │                    │               │                │
    │              │              ┌─── before_script 阶段 ───┐            │
    │              │              │ 19. stage=before_script │           │
    │              │              │     script=normalize_   │           │
    │              │              │     recommend.py        │           │
    │              │              │────────────────────────>│           │
    │              │              │                        │           │
    │              │              │                        │ 20. PUT   │
    │              │              │                        │ /api/v1/  │
    │              │              │                        │ timeout   │
    │              │              │                        │─────────>│
    │              │              │                        │<─────────│
    │              │              │                        │           │
    │              │              │                        │ 21. POST  │
    │              │              │                        │ /sandboxes│
    │              │              │                        │ /{id}/exec│
    │              │              │                        │─────────>│
    │              │              │                        │           │ 22. 解码base64
    │              │              │                        │           │ 执行Python:
    │              │              │                        │           │ normalize_
    │              │              │                        │           │ recommend.py
    │              │              │                        │           │ SKILL_INPUT:
    │              │              │                        │           │ {result:{}}
    │              │              │                        │           │ → allow=true
    │              │              │                        │<─────────│
    │              │              │ 23. stdout:           │           │
    │              │              │ {"allow":true}        │           │
    │              │              │<──────────────────────│           │
    │              │              │ 24. 校验 allow=true  │           │
    │              │              │     允许继续          │           │
    │              │              └────────────────────────┘           │
    │              │              ┌─── prepare_delegate 阶段 ────────┐│
    │              │              │ 25. 渲染 query-template:          ││
    │              │              │     "请推荐理财产品"               ││
    │              │              │ 26. 构造 InterruptRequest:       ││
    │              │              │     _interrupt_kind=a2a_delegate  ││
    │              │              │     agentName=versatile-wealth    ││
    │              │              │     intent=理财选品购买           ││
    │              │              │ 27. 发出中断，Skill挂起           ││
    │              │              │     等待A2A委派结果               ││
    │              │              └──────────────────────────────────┘│
    │              │                    │               │                │
    │              │              ┌─── A2A 委派阶段 ─────────────────┐  │
    │              │              │ 28. A2AEnabledServeOrchestrator  │  │
    │              │              │     .handleInterrupt()           │  │
    │              │              │ 29. RemoteInvocationBatch        │  │
    │              │              │     Coordinator.submit()         │  │
    │              │              │ 30. A2ARemoteAgentClient         │  │
    │              │              │     .callOutcome()               │  │
    │              │              │     查找versatile-wealth的      │  │
    │              │              │     AgentCard(已发现注册)        │  │
    │              │              │ 31. POST SendStreamingMessage    │  │
    │              │              │     到 http://127.0.0.1:18096/a2a│  │
    │              │              │──────────────────────────────────────>│
    │              │              │                        │           │ 32. 解析请求:
    │              │              │                        │           │ parts[0].text
    │              │              │                        │           │ = JSON{query,
    │              │              │                        │           │ intent,
    │              │              │                        │           │ idempotencyKey}
    │              │              │                        │           │ 33. 识别操作:
    │              │              │                        │           │ query含"推荐"
    │              │              │                        │           │ → op=recommend
    │              │              │                        │           │ 34. 查找预设:
    │              │              │                        │           │ L2_RESPONSES
    │              │              │                        │           │ [versatile-wealth]
    │              │              │                        │           │ [理财选品购买]
    │              │              │                        │           │ [recommend]
    │              │              │                        │           │ 35. 返回SSE流:
    │              │              │                        │           │
    │              │              │                        │           │ event:jsonrpc
    │              │              │                        │           │ data:{"result":{
    │              │              │                        │           │  "statusUpdate":{
    │              │              │                        │           │   "taskId":"...",
    │              │              │                        │           │   "status":{
    │              │              │                        │           │    "state":
    │              │              │                        │           │    "TASK_STATE_WORKING"
    │              │              │                        │           │   }}}}
    │              │              │                        │           │
    │              │              │                        │           │ event:jsonrpc
    │              │              │                        │           │ data:{"result":{
    │              │              │                        │           │  "artifactUpdate":{
    │              │              │                        │           │   "artifact":{
    │              │              │                        │           │    "parts":[{
    │              │              │                        │           │     "text":"{...产品列表...}"
    │              │              │                        │           │    }]}}}}}
    │              │              │                        │           │
    │              │              │                        │           │ event:jsonrpc
    │              │              │                        │           │ data:{"result":{
    │              │              │                        │           │  "statusUpdate":{
    │              │              │                        │           │   "status":{
    │              │              │                        │           │    "state":
    │              │              │                        │           │    "TASK_STATE_COMPLETED"
    │              │              │                        │           │   }}}}
    │              │              │                        │           │
    │              │              │<──────────────────────────────────────│
    │              │              │ 36. A2A SDK 解包SSE流  │           │
    │              │              │     提取artifact中的  │           │
    │              │              │     text → LinkedHashMap│          │
    │              │              └────────────────────────┘           │
    │              │                    │               │                │
    │              │              ┌─── result_script 阶段 ─────────────┐│
    │              │              │ 37. stage=result_script            ││
    │              │              │     script=normalize_              ││
    │              │              │     recommend.py                  ││
    │              │              │     SKILL_INPUT:                   ││
    │              │              │     {arguments:{action:recommend}, ││
    │              │              │      result:{...L2响应...}}        ││
    │              │              │────────────────────────>│          │
    │              │              │                        │           │
    │              │              │                        │ 38. POST   │
    │              │              │                        │ /sandboxes │
    │              │              │                        │ /{id}/exec│
    │              │              │                        │─────────>│
    │              │              │                        │           │ 39. 解码执行:
    │              │              │                        │           │ normalize_
    │              │              │                        │           │ recommend.py
    │              │              │                        │           │
    │              │              │                        │           │ unpack(raw,
    │              │              │                        │           │  "productList")
    │              │              │                        │           │ → 找到
    │              │              │                        │           │   productList
    │              │              │                        │           │ product_list()
    │              │              │                        │           │ → 3个标准化产品
    │              │              │                        │           │ 返回:
    │              │              │                        │           │ {success:true,
    │              │              │                        │           │  data:{products,
    │              │              │                        │           │   bank_card,
    │              │              │                        │           │   source_version},
    │              │              │                        │           │  message:"1.稳富...
    │              │              │                        │           │   2.稳富安享...
    │              │              │                        │           │   3.工银灵动..."}
    │              │              │                        │<─────────│
    │              │              │ 40. stdout(61字节): │           │
    │              │              │ {"success":true,     │           │
    │              │              │  "data":{...},       │           │
    │              │              │  "message":"1.稳富..."│           │
    │              │              │<──────────────────────│           │
    │              │              └────────────────────────┘           │
    │              │                    │               │                │
    │              │              ┌─── result_processor 阶段 ─────────┐ │
    │              │              │ 41. 校验 success=true             │ │
    │              │              │ 42. 校验 data 通过 output-schema  │ │
    │              │              │ 43. 保存到 SkillSession:          │ │
    │              │              │     results.recommend=data        │ │
    │              │              │     revision++ → 1               │ │
    │              │              │     resultVersions.recommend=1   │ │
    │              │              │ 44. emit: todo_end(SUCCEEDED)     │ │
    │              │              │ 45. emit: final_answer_chunk     │ │
    │              │              │     content="1.稳富收益增强..."   │ │
    │              │              │ 46. 返回 receipt(success,data)   │ │
    │              │              └──────────────────────────────────┘ │
    │<─────────────────────────────────────│               │                │
    │ 47. 工具结果   │                    │               │                │
    │ 返回给LLM     │                    │               │                │
    │              │                    │               │                │
    │ 48. iter 3   │                    │               │                │
    │─────────────>|                    │               │                │
    │              │ 49. LLM判断推荐    │               │                │
    │              │     已成功,需要    │               │                │
    │              │     询问用户选择    │               │                │
    │              │ 50. 返回工具调用:   │               │                │
    │              │     ask_user        │               │                │
    │              │     questions=[    │               │                │
    │              │       产品选择,    │               │                │
    │              │       购买金额]    │               │                │
    │<─────────────│                    │               │                │
    │ 51. 执行     │                    │               │                │
    │ ask_user     │                    │               │                │
    │───(AskUserRail处理,产生中断)──────│               │                │
    │              │                    │               │                │
    │ 52. DeepAgent中断                │               │                │
    │     控制权返回LocalDelegate       │               │                │
    │<─────────────────────────────────────│            │                │
    │              │                    │               │                │
    │              │              ┌─── 中断转发阶段 ─────────────────┐  │
    │              │              │ 53. forward_interrupt()         │  │
    │              │              │     kind=ask_user               │  │
    │              │              │ 54. StagePlanningRail           │  │
    │              │              │     await_result(interrupted)   │  │
    │              │              │ 55. JiuwenCoreAgentHandler      │  │
    │              │              │     interrupt detected          │  │
    │              │              │ 56. task requires interaction   │  │
    │              │              └──────────────────────────────────┘  │
    │              │                    │               │                │
    │              │ 57. SSE 事件流      │               │                │
    │<─────────────────────────────────────│──────────────────────────────────│
    │              │                    │               │                │
    │  event:interrupt → task_input_required                │                │
    │  state: TASK_STATE_INPUT_REQUIRED                     │                │
    │<──────────────────────────────────────────────────────────────────────────|
    │                 │                    │               │                │
```

## 4. 关键数据流详解

### 4.1 意图路由数据流

```
用户输入: "买理财"
    │
    ▼
IntentRouter.route("买理财", explicit="", session)
    │
    ├──[线程 moa-intent-1]──> WorkflowIntentRecall
    │     POST http://127.0.0.1:18099/intent
    │     body: {"question":"买理财","sessionId":"<uuid>","parameters":{"cls":{"curValue":"1001"}}}
    │     ← 返回: {"result":{"outParams":{"intents":[
    │         {"predomain":"理财服务","intent_name":"理财选品购买","description":"..."}
    │       ]}}}
    │
    ├──[线程 moa-intent-2]──> HttpTaskCardinalityAnalysis
    │     POST http://127.0.0.1:18099/cardinality
    │     body: {"query":"买理财"}
    │     ← 返回: {"cardinality":"SINGLE"}
    │
    └──> FinanceSkillCandidateResolver.resolve()
          predomain="理财服务" + intent="理财选品购买"
          → 匹配! 注入候选 cap.skill.finance.purchase
          候选: {id=cap.skill.finance.purchase, adapter=skill-agent,
                 intent=理财组合办理, kind=SKILL, risk=LOW}

Decision: fast=true, reason=SINGLE, candidates=[cap.skill.finance.purchase]
```

### 4.2 before_script 数据流（沙箱执行 normalize_recommend.py）

```
SkillActionRail.resolveInterrupt()
    │
    │ 工具调用: call_versatile(operation=recommend, arguments={action:"recommend"})
    │ response=null (首次调用)
    │
    ├── stage=prepare_input
    │   scriptInput = {
    │     "revision": 0,
    │     "metadata": {},
    │     "arguments": {"action": "recommend"},
    │     "results": {},
    │     "versions": {},
    │     "execution_id": "conv-014:v1-todo-1",
    │     "result": {}              ← 空，表示前置校验
    │   }
    │
    ├── stage=before_script
    │   scripts.run(session, normalize_recommend.py, scriptInput)
    │   │
    │   └──> SandboxScriptRunner.run()
    │         sandbox.code().executeCode(
    │           source=normalize_recommend.py源码(base64编码在bash命令中),
    │           language="python",
    │           timeout=30,
    │           env={"SKILL_INPUT": <scriptInput JSON>, "PYTHONIOENCODING":"utf-8"},
    │           options={}
    │         )
    │         │
    │         └──> JiuwenBox 沙箱 mock
    │               POST /api/v1/sandboxes/{id}/exec
    │               body: {"command":["bash","-lc","python3 -c \"import base64,sys;exec(base64.b64decode('...').decode())\""],
    │                      "env":{"SKILL_INPUT":"...","PYTHONIOENCODING":"utf-8"}}
    │               ← 200: {"stdout":"{\"allow\": true}","stderr":"","exit_code":0}
    │
    │   返回: {"allow": true}  ← 前置校验通过
    │
    ├── 校验: allow == true ✓
    │
    └── stage=prepare_delegate (A2A委派)
```

### 4.3 A2A 委派数据流

```
SkillActionRail 构造 InterruptRequest:
    │
    │ query = render("请推荐理财产品", arguments={action:"recommend"})
    │        = "请推荐理财产品"
    │ intent = operation.intent() = "理财选品购买"
    │ adapter = operation.adapter() = "versatile-wealth"
    │ idempotencyKey = "conv-014:v1-todo-1:call_xxx"
    │
    └──> InterruptRequest {
            message: "{\"query\":\"请推荐理财产品\",\"intent\":\"理财选品购买\",\"idempotencyKey\":\"...\"}",
            context: {_interrupt_kind:"a2a_delegate", agentName:"versatile-wealth"}
          }

LocalSkillDelegate 转发中断 → L1 的 IntentRoutingRail
    │
    └──> A2AEnabledServeOrchestrator.handleInterrupt()
          │
          └──> RemoteInvocationBatchCoordinator.execute()
                │
                └──> A2ARemoteAgentClient.callOutcome(agentName="versatile-wealth")
                      │ 查找已注册的 AgentCard
                      │ url = http://127.0.0.1:18096/a2a
                      │
                      └──> POST http://127.0.0.1:18096/a2a
                            JSON-RPC: SendStreamingMessage
                            body: {
                              "jsonrpc":"2.0",
                              "id":"<uuid>",
                              "method":"SendStreamingMessage",
                              "params":{
                                "message":{
                                  "contextId":"conv-014",
                                  "role":"ROLE_USER",
                                  "parts":[{"text":"{\"query\":\"请推荐理财产品\",...}"}]
                                }
                              }
                            }

                            ← SSE 响应 (3帧):
                              ┌─ event:jsonrpc
                              │  data:{"jsonrpc":"2.0","id":"...","result":{
                              │    "statusUpdate":{
                              │      "taskId":"...",
                              │      "status":{"state":"TASK_STATE_WORKING"}
                              │    }}}
                              │
                              ├─ event:jsonrpc
                              │  data:{"jsonrpc":"2.0","id":"...","result":{
                              │    "artifactUpdate":{
                              │      "artifact":{
                              │        "parts":[{"text":"{\"queryStatus\":\"成功\",\"productList\":[
                              │          {\"productCode\":\"WF2025001\",\"productName\":\"稳富收益增强日开1号\",\"minBuyAmount\":\"1000\"},
                              │          {\"productCode\":\"WF2025002\",\"productName\":\"稳富安享90天持有期2号\",\"minBuyAmount\":\"5000\"},
                              │          {\"productCode\":\"WF2025003\",\"productName\":\"工银灵动配置混合A\",\"minBuyAmount\":\"10000\"}
                              │        ],\"bankCardNumber\":\"6222021234567890123\"}"}]
                              │      }
                              │    }}}
                              │
                              └─ event:jsonrpc
                                 data:{"jsonrpc":"2.0","id":"...","result":{
                                   "statusUpdate":{
                                     "status":{"state":"TASK_STATE_COMPLETED"}
                                   }}}

A2A SDK 解包 SSE → 提取 artifact parts[0].text → LinkedHashMap
    │
    └──> 返回给 SkillActionRail: response = LinkedHashMap
```

### 4.4 result_script 数据流（沙箱执行 normalize_recommend.py 二次调用）

```
SkillActionRail.resolveInterrupt() (回调)
    │
    │ response = LinkedHashMap (L2响应)
    │ stage=validate_callback → 校验操作和参数匹配 ✓
    │
    ├── stage=result_input
    │   scriptInput = {
    │     "revision": 0,
    │     "arguments": {"action": "recommend"},
    │     "results": {},
    │     "versions": {},
    │     "execution_id": "conv-014:v1-todo-1",
    │     "result": {                          ← L2 响应数据
    │       "queryStatus": "成功",
    │       "productList": [
    │         {"productCode":"WF2025001","productName":"稳富收益增强日开1号","minBuyAmount":"1000"},
    │         {"productCode":"WF2025002","productName":"稳富安享90天持有期2号","minBuyAmount":"5000"},
    │         {"productCode":"WF2025003","productName":"工银灵动配置混合A","minBuyAmount":"10000"}
    │       ],
    │       "bankCardNumber": "6222021234567890123"
    │     }
    │   }
    │
    ├── stage=result_script
    │   scripts.run(session, normalize_recommend.py, scriptInput)
    │   │
    │   └──> 沙箱执行 normalize_recommend.py
    │         SKILL_INPUT env = scriptInput JSON
    │         │
    │         └──> run(params):
    │               arguments = {action:"recommend"}  ✓
    │               raw = params["result"]  ← L2响应
    │               raw != {} → 进入解析
    │               │
    │               payload = unpack(raw, "productList")
    │                 遍历raw,检查每个dict:
    │                   - queryStatus="成功" → 不在failed列表 ✓
    │                   - 找到 productList 字段 ✓
    │                 payload = {productList:[...], bankCardNumber:"6222..."}
    │               │
    │               products = product_list(payload["productList"])
    │                 3个产品,每个提取 productCode→product_id, productName→name, minBuyAmount→min_amount
    │                 格式化金额: "1000" → "1,000.00" (Decimal)
    │                 去重检查 ✓
    │                 返回3个标准化产品对象
    │               │
    │               bank_card = card("6222021234567890123")  ← 校验4-32位数字 ✓
    │               source_version = 0
    │               │
    │               data = {
    │                 "products": [
    │                   {"product_id":"WF2025001","name":"稳富收益增强日开1号","min_amount":"1,000.00"},
    │                   {"product_id":"WF2025002","name":"稳富安享90天持有期2号","min_amount":"5,000.00"},
    │                   {"product_id":"WF2025003","name":"工银灵动配置混合A","min_amount":"10,000.00"}
    │                 ],
    │                 "bank_card": "6222021234567890123",
    │                 "source_version": 0
    │               }
    │               message = "1. 稳富收益增强日开1号\n2. 稳富安享90天持有期2号\n3. 工银灵动配置混合A"
    │               │
    │               return {"success": true, "data": data, "message": message}
    │
    │   沙箱 stdout: {"success":true,"data":{...},"message":"1.稳富..."}
    │
    ├── stage=result_processor
    │   processed = {"success":true, "data":{...}, "message":"..."}
    │   校验 success == true ✓
    │   processorCode = "none"
    │
    ├── stage=result_schema
    │   data = processed["data"]
    │   SkillSchema.validate(outputSchema, data)
    │   校验: products(数组≤20) ✓, bank_card(字符串≤32) ✓, source_version(整数≥0) ✓
    │
    ├── stage=result_size
    │   Json.write(data).length ≤ 65536 ✓
    │
    └── stage=save_result
        state.results["recommend"] = data
        state.revision = 1
        state.resultVersions["recommend"] = 1
        state.pending.clear()
        SkillEvents.step(SUCCEEDED)
        SkillEvents.emit(final_answer_chunk, message)
        返回 receipt({success:true, data:data})
```

### 4.5 ask_user 中断数据流

```
DeepAgent iter 3:
    │
    │ LLM 收到工具结果(receipt)
    │   tool message: {"success":true,"data":{"products":[...],"bank_card":"...","source_version":0}}
    │
    │ LLM 判断: 推荐成功，需要让用户选择产品和金额
    │
    └──> tool_call: ask_user
         questions: [
           {
             header: "选择产品",
             question: "请选择您想购买的理财产品：",
             options: [
               {label:"稳富收益增强日开1号", description:"产品编号 WF2025001，起购金额 1,000.00 元"},
               {label:"稳富安享90天持有期2号", description:"产品编号 WF2025002，起购金额 5,000.00 元"},
               {label:"工银灵动配置混合A", description:"产品编号 WF2025003，起购金额 10,000.00 元"}
             ]
           },
           {
             header: "购买金额",
             question: "请输入您想购买的金额（元）：",
             options: [
               {label:"按起购金额购买", description:"使用所选产品的最低起购金额进行购买"},
               {label:"自定义金额", description:"输入您希望购买的具体金额，需不低于产品起购金额"}
             ]
           }
         ]

AskUserRail 拦截 → 产生 AskUserRequest 中断
    │
    └──> LocalSkillDelegate.forward_interrupt()
          │
          └──> StagePlanningRail: await_result(interrupted=true)
                │
                └──> JiuwenCoreAgentHandler: interrupt detected (type=__interaction__)
                      │
                      └──> MoaCustomRestAdapter: 解包为前端 SSE

最终 SSE 响应:
    event:chunk → interrupt_start
      content: "[选择产品] 请选择您想购买的理财产品：..."
      questions: [{header, question, options}]
      skill_protocol: "native_skill_events_v1"

    event:chunk → controller_output
      data: [{text:"All tasks have been successfully processed"}]

    event:interrupt → task_input_required
      state: "TASK_STATE_INPUT_REQUIRED"
```

## 5. SkillActionRail 状态机

```
call_versatile/recommend 首次调用 (response=null):
    ┌──────────────────────────────────────────────────────────────┐
    │                                                              │
    │  validate_request ──→ prepare_input                          │
    │       │                    │                                 │
    │       │                    ▼                                 │
    │       │              before_script                            │
    │       │              (沙箱执行前置脚本)                       │
    │       │              normalize_recommend.py                  │
    │       │              result={} → allow=true                  │
    │       │                    │                                 │
    │       │                    ▼                                 │
    │       │              emit_start                              │
    │       │              (SSE: todo_start, think_chunk)           │
    │       │                    │                                 │
    │       │                    ▼                                 │
    │       │              prepare_delegate                         │
    │       │              (构造A2A中断,挂起等待L2响应)              │
    │       │                    │                                 │
    │       └────────────────────┘                                  │
    │                    │                                         │
    │              [A2A委派到L2,Skill挂起]                          │
    │                    │                                         │
    │                    ▼                                         │
    │           validate_callback                                  │
    │           (L2响应返回,校验操作/参数匹配)                        │
    │                    │                                         │
    │                    ▼                                         │
    │              result_input                                    │
    │              (合并L2响应到scriptInput.result)                 │
    │                    │                                         │
    │                    ▼                                         │
    │              result_script                                   │
    │              (沙箱执行结果脚本)                                 │
    │              normalize_recommend.py                          │
    │              result={产品列表} → 校验+标准化                    │
    │                    │                                         │
    │                    ▼                                         │
    │           result_processor                                    │
    │           (校验success=true)                                 │
    │                    │                                         │
    │                    ▼                                         │
    │           result_schema                                      │
    │           (JSON Schema校验output)                             │
    │                    │                                         │
    │                    ▼                                         │
    │           result_size                                        │
    │           (≤65536字节)                                        │
    │                    │                                         │
    │                    ▼                                         │
    │           save_result                                        │
    │           (revision++,保存结果)                               │
    │                    │                                         │
    │                    ▼                                         │
    │           emit: todo_end(SUCCEEDED)                          │
    │           emit: final_answer_chunk(message)                  │
    │           return receipt(success,data)                       │
    │                                                              │
    └──────────────────────────────────────────────────────────────┘

    失败路径(任一stage异常):
    │                    ▼
    │           reject → emit: todo_end(FAILED)
    │           return {success:false, error:"...", stage:"..."}
```

## 6. 完整 SSE 事件时间线

| 时间(ms) | 事件 | 内容 |
| --- | --- | --- |
| 0 | task_submitted | taskId, state=SUBMITTED |
| ~1 | task_working | state=WORKING |
| ~3 | task_output | (空content) |
| ~5 | todolist_start | phase=READY, revision=1 |
| ~5 | todolist_item | v1-todo-1, "买理财", PENDING, cap.skill.finance.purchase |
| ~5 | todolist_end | phase=READY |
| ~5 | todo_start | v1-todo-1, RUNNING |
| ~3.3s | todolist_start | scope=conv-014:v1-todo-1, mode=append (skill子任务) |
| ~3.3s | todolist_item | "推荐理财产品", RUNNING, parent=v1-todo-1 |
| ~3.3s | todolist_end | skill子任务 |
| ~3.3s | todo_start | "推荐理财产品", RUNNING (skill子任务) |
| ~3.3s | think_chunk | "正在执行：推荐理财产品" |
| ~3.3s | think_end | |
| ~3.3s | task_output | (空) |
| ~3.3s | controller_output | "All tasks have been successfully processed" |
| ~3.3s | task_output | (空,多个) |
| ~3.3s | todo_end | "推荐理财产品", **SUCCEEDED** |
| ~3.3s | **final_answer_chunk** | "1. 稳富收益增强日开1号\n2. 稳富安享90天持有期2号\n3. 工银灵动配置混合A" |
| ~8.8s | **interrupt_start** | 产品选择+购买金额, questions(2组options) |
| ~8.8s | task_output | (空) |
| ~8.8s | controller_output | "All tasks have been successfully processed" |
| ~8.8s | **task_input_required** | state=TASK_STATE_INPUT_REQUIRED |

> 注: 时间相对于 task_submitted 的偏移。~3.3s 为 LLM 首次推理+沙箱+L2委派总耗时，~8.8s 为 LLM 第二次推理(决定ask_user)耗时。

## 7. Mock 服务协议规范

### 7.1 沙箱 Mock (端口 8321)

```
PUT /api/v1/timeout
  → 200 {"ok": true}

POST /api/v1/sandboxes
  → 200 {"id":"mock-sandbox-001","name":"mock","status":"running"}

POST /api/v1/sandboxes/{id}/exec
  请求: {"command":["bash","-lc","python3 -c \"...base64...\""],
         "env":{"SKILL_INPUT":"<JSON>","PYTHONIOENCODING":"utf-8"},
         "timeout_seconds":30}
  处理: 从command解码base64 Python源码,本地subprocess执行
  响应: 200 {"stdout":"<JSON>","stderr":"","exit_code":0}

GET /health
  → 200 {"status":"ok"}
```

### 7.2 L2 Mock (端口 18093-18097)

```
GET /.well-known/agent-card.json
  → 200 AgentCard JSON (必须匹配A2A SDK字段名)

POST /a2a  (SendStreamingMessage)
  请求: JSON-RPC 2.0, method=SendStreamingMessage
  响应: SSE text/event-stream

  帧1 - statusUpdate(WORKING):
    event:jsonrpc
    data:{"jsonrpc":"2.0","id":"<req-id>","result":{
      "statusUpdate":{
        "taskId":"<uuid>",
        "contextId":"<ctx>",
        "status":{"state":"TASK_STATE_WORKING"}
      }}}

  帧2 - artifactUpdate(业务数据):
    event:jsonrpc
    data:{"jsonrpc":"2.0","id":"<req-id>","result":{
      "artifactUpdate":{
        "taskId":"<uuid>",
        "contextId":"<ctx>",
        "artifact":{
          "artifactId":"<uuid>",
          "parts":[{"text":"<业务JSON>"}]
        }
      }}}

  帧3 - statusUpdate(COMPLETED):
    event:jsonrpc
    data:{"jsonrpc":"2.0","id":"<req-id>","result":{
      "statusUpdate":{
        "taskId":"<uuid>",
        "contextId":"<ctx>",
        "status":{"state":"TASK_STATE_COMPLETED"}
      }}}
```

### 7.3 AgentCard 字段规范

```json
{
  "name": "versatile-wealth",
  "description": "Mock versatile-wealth",
  "provider": {"organization": "", "url": ""},
  "version": "1.0",
  "documentationUrl": null,
  "capabilities": {
    "streaming": true,
    "pushNotifications": false,
    "extendedAgentCard": false,
    "extensions": []
  },
  "defaultInputModes": ["text"],
  "defaultOutputModes": ["text"],
  "skills": [{
    "id": "mock-versatile-wealth",
    "name": "versatile-wealth",
    "description": "versatile-wealth",
    "tags": [],
    "examples": [],
    "inputModes": ["text"],
    "outputModes": ["text"],
    "securityRequirements": []
  }],
  "securitySchemes": {},
  "securityRequirements": [],
  "iconUrl": null,
  "supportedInterfaces": [{
    "protocolBinding": "JSONRPC",
    "url": "http://127.0.0.1:18096/a2a",
    "tenant": null,
    "protocolVersion": "1.0"
  }],
  "signatures": [],
  "url": "http://127.0.0.1:18096/a2a",
  "preferredTransport": "JSONRPC",
  "additionalInterfaces": []
}
```

关键字段对照(来自主应用自身agent-card的序列化格式):
- `supportedInterfaces` 用 `protocolBinding` 而非 `protocol`
- `capabilities` 用 `extendedAgentCard` 而非 `stateless`
- `securityRequirements` 而非 `security`
- skills 每个 item 需要 `examples`, `inputModes`, `outputModes`, `securityRequirements`

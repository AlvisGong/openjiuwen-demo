# 理财推荐 Skill 引擎执行场景——端到端调用栈梳理

> 证据来源：
> - 运行日志：`demo\moa_agent\logs\run\run.log`，会话 `fin-run-e2e-852922`，2026-10-15 16:19:07 – 16:20:08
> - Mock L2 侧日志：`mock_versatile_server.py` 运行输出（18093–18097）
> - 源码路径约定（均相对 `d:\work\code-proj\openjiuwen-fin\moa\`）：
>   - `MOA` = `0.1.1\common\agents\moa-agent\src\main\java\com\moaagent`
>   - `EXT` = `0.1.1\common\agent-runtime-ext-java`
>   - `RT` = `agent-runtime-java\service\agent-service-app\src\main\java\com\openjiuwen\service\app`
>   - `CORE` = `agent-core-java\src\main\java\com\openjiuwen`
>   - `DEMO` = `demo\moa_agent\moa-edp\src\main\java\com\customer\finance\moaagent`
>
> 文中调用栈行号均经源码核对；日志行均摘自本次运行实例，保留原始时间戳与线程名。
> 版本口径：MOA 底座代码在 `0.1.1\` 与 `phoenix\` 下各有一份，本文一律以 `0.1.1\` 为准（运行实例日志格式与其源码完全吻合）。

---

## 1. 运行环境与请求入口

| 服务 | 端口 | 说明 |
|---|---|---|
| finance-moa-agent 主服务 | 18098 | Spring Boot，`--spring.profiles.active=demo` + `skill-local.example.yml` |
| 沙箱脚本服务 | 8321 | SandboxScriptRunner 目标（jiuwenbox） |
| mock L2 ×5 | 18093–18097 | versatile-general/account/transfer/wealth/creditcard，A2A JSON-RPC SSE |
| 意图 mock | 18099 | 本次运行未走外部意图流（见 §3 步骤 4 说明） |

请求入口（三轮相同，仅 query 不同）：

```bash
POST http://127.0.0.1:18098/v1/demo/agents/finance-moa-agent/conversations/fin-run-e2e-852922
Content-Type: application/json

{"query":"我要买理财","stream":true}
```

SSE 事件流（`event:chunk` / `event:interrupt` / `event:final`）由 `MoaCustomRestAdapter` 投影输出。

---

## 2. 三轮总览

| 轮次 | 输入 | 时间 | 主链路 | 终态 |
|---|---|---|---|---|
| 第一轮 | 我要买理财 | 16:19:07 → 16:19:24 | 意图召回 SINGLE → FAST → v1-todo-1 → **recommend**(A2A versatile-wealth) → ask_user 中断 | `TASK_STATE_INPUT_REQUIRED` |
| 第二轮 | 我选择稳盈财富1号，购买金额10000元 | 16:19:39 → 16:19:47 | 恢复 ask_user → **select**(SCRIPT) → **wealth_balance**(A2A versatile-account) → confirm_action 中断 | `TASK_STATE_INPUT_REQUIRED` |
| 第三轮 | 确认 | 16:20:01 → 16:20:08 | 恢复 confirm_action → **purchase**(A2A versatile-wealth) → finish | `TASK_STATE_COMPLETED` |

operation 修订号推进（`SkillActionRail#saveSuccessfulResult`，`MOA\agent\skill\SkillActionRail.java:492`，:503 `revision+1`）：

```
recommend(rev=1) → select(rev=2) → wealth_balance(rev=3) → purchase(rev=4)
```

---

## 3. 第一轮调用栈：我要买理财 → 推荐 → ask_user 中断

### 3.1 调用栈（方法级）

```
[HTTP 入口层]
 ① CustomRestHandler#handle                        EXT\agent-service-app\agent-service-app-custom-rest\...\CustomRestAutoConfiguration.java:88-90
    │  :88 @PostMapping(query-path)，:90 handle 签名，SSE/阻塞分流
 ② MoaCustomRestAdapter#toA2ARequest               MOA\runtime\customrest\MoaCustomRestAdapter.java:81
    │  body{"query","stream"} → A2A Message(TextPart, contextId=fin-run-e2e-852922)
 ③ CustomRestA2ABridge#executeStream               EXT\...\CustomRestA2ABridge.java:147
    │  调 requestHandler.onMessageSendStream，进入 A2A 协议栈
[A2A 协议层]
 ④ A2AMessageContext：A2A NEW task（16:19:07.173）
 ⑤ A2AProtocolAdapter#toServeRequest               RT\controller\a2a\A2AProtocolAdapter.java:49
 ⑥ A2AAgentExecutor#execute → executeStreaming       RT\controller\a2a\A2AAgentExecutor.java:154/273
    │  task_admitted → A2A execute START resume=false stream=true
[编排层]
 ⑦ A2AEnabledServeOrchestrator#streamQuery         RT\orchestrator\A2AEnabledServeOrchestrator.java:181
    │  中断-恢复 while 循环调度
 ⑧ JiuwenCoreAgentExtHandler#streamQuery           EXT\...\ext\agentfw\JiuwenCoreAgentExtHandler.java:168
    │  installBeforeRun(:256) 安装远端 A2A 工具；super.streamQuery
 ⑨ JiuwenCoreAgentHandler#streamQuery → forwardStream   agent-runtime-java\service\agent-service-adapters\agent-service-adapters-agentcore\src\main\java\com\openjiuwen\service\adapters\agentcore\agentfw\JiuwenCoreAgentHandler.java:248/271
    │  模板方法；executeAgentStreaming = Runner.runAgentStreaming
[主 DeepAgent 层]
 ⑩ 主智能体（MoaRuntimeAutoConfiguration#moaAgentHandler 组装   MOA\runtime\orchestration\MoaRuntimeAutoConfiguration.java:210-241
    │  tools = StagePlanningRail.tools() + IntentRoutingRail.fastTool()
    │  rails = IntentRoutingRail / StagePlanningRail / LocalDelegationRail / InterruptEventRail 等
 ⑪ ReActAgent#stream                                CORE\core\singleagent\agents\ReActAgent.java:1080
    │  提交 STREAM_EXECUTOR 线程池（:97-98，react-agent-stream-22）
    │  railedModelStreamCall(:600) 逐 rail 钩子触发
[意图识别层]（两路并行，RoutingServices.java:163）
 ⑫ IntentRoutingRail#beforeInvoke → route          MOA\agent\routing\IntentRoutingRail.java:120/158
 ⑬ IntentRouter#route                               MOA\agent\routing\IntentRouter.java:147
 ⑭ PackIntentRecognition#recognize                 MOA\agent\routing\recognition\PackIntentRecognition.java:35
    │  [MOA-MATCH] source=pack rawCount=9 → 唯一候选
    │  {id=cap.skill.finance.purchase, adapter=skill-agent, intent=理财组合办理, kind=SKILL, risk=LOW}
    │  ※ 本实例走本地意图包召回；demo 另有 WorkflowIntentRecognition（HTTP 18099 意图流，
    │     DEMO\intent\WorkflowIntentRecognition.java:47，:83 调 FinanceSkillCandidateResolver#resolve，
    │     DEMO\FinanceSkillCandidateResolver.java:27）实现，本次运行未启用
 ⑮ ModelTaskCardinalityAnalysis                    MOA\agent\routing\cardinality\ModelTaskCardinalityAnalysis.java:76
    │  [MOA-CARDINALITY] source=MODEL outcome=SINGLE elapsedMs=3611（模型判定任务数）
[路由决策层]
 ⑯ DefaultRequestRoutingPolicy#decide               MOA\agent\routing\policy\DefaultRequestRoutingPolicy.java:48
    │  :58 directFastEligible = (candidates==1 && SINGLE) = true
    │  :70-72 Decision.fast(reason=SINGLE)
 ⑰ IntentRoutingRail：[MOA-ROUTE] path=FAST（:257）→ delegateFast(:305)
[规划层]
 ⑱ StagePlanningRail#startFast                     MOA\agent\planning\StagePlanningRail.java:862
    │  :867 todo.setId("v1-todo-1") → execute(:524) → startExecution(:570)
    │  [MOA-EXEC] action=prepare
[本地 Skill 委派层]
 ⑲ ExecutionEngine#startLocal                       MOA\agent\execution\ExecutionEngine.java:81
    │  executionId = "fin-run-e2e-852922:v1-todo-1"
 ⑳ LocalSkillDelegate#advance                       MOA\agent\skill\LocalSkillDelegate.java:105
    │  :130 [MOA-LOCAL-DELEGATE] action=start；:132 Runner.runAgentStreaming
 ㉑ NativeSkillConfiguration 工厂 → NativeSkillAgent#create
    │  MOA\runtime\skill\NativeSkillConfiguration.java:88；MOA\agent\skill\NativeSkillAgent.java:63
    │  :72-77 rails = SkillBoundaryRail/SkillNarrationRail/SkillActionRail/SkillUseRail/AskUserRail/SkillInteractionRail
    │  tools = SkillTools.create + AskUserTool（call_versatile/call_mcp/confirm_action/ask_user…）
    │  子 agent 跑在 react-agent-stream-23
[recommend 操作（A2A 型）]  —— SkillActionRail#executeBusiness :298
 ㉒ before_script：SandboxScriptRunner#run            MOA\agent\skill\SandboxScriptRunner.java:50
    │  [SKILL-SANDBOX] stdoutLength=16（入参校验通过，16:19:21.433）
 ㉓ A2A 委派：startOperation A2A 分支               MOA\agent\skill\SkillActionRail.java:358
    │  :382 action=start type=A2A；:394 delegate_requested adapter=versatile-wealth intent=理财选品购买
    │  委派 envelope = {"intent":"理财选品购买","query":"请推荐理财产品",
    │                   "idempotencyKey":"fin-run-e2e-852922:v1-todo-1:call_a5f0dc6d7d094d4d8d02ae40"}
    │  → ToolInterruptException(_interrupt_kind="a2a_delegate") → ReActAgent#commitInterrupt
    │    (CORE\...\ReActAgent.java:1187，持久化 ToolInterruptionState)
 ㉔ 中断上抛：LocalSkillDelegate#completeStep→forward  MOA\agent\skill\LocalSkillDelegate.java:227/342
    │  :248-252 [MOA-LOCAL-DELEGATE] forward_interrupt kind=a2a_delegate
    │  LocalDelegationRail#afterInvoke (MOA\agent\execution\local\LocalDelegationRail.java:49)
    │  → LocalDelegationIds#external (...\LocalDelegationIds.java:45)
    │    toolCallId 改写为 moa-local-skill--base64(v1-todo-1).base64(call_id)
    │  StagePlanningRail :604 [MOA-EXEC] action=await_result interrupted=true
 ㉕ A2A 远程执行：A2AEnabledServeOrchestrator#handleInterrupt
    │  RT\orchestrator\A2AEnabledServeOrchestrator.java:361；isCoordinatorInterrupt(:651 判 _interrupt_kind)
 ㉖ RemoteInvocationBatchCoordinator#execute → start   RT\orchestrator\RemoteInvocationBatchCoordinator.java:151/367
    │  :380 RemoteCall(versatile-wealth, message=envelope)；:385 client.callOutcome
 ㉗ A2ARemoteAgentClient#callOutcome → submitInvocation  RT\controller\a2a\client\A2ARemoteAgentClient.java:254/324
    │  buildSendParams(:151) parts[0]=TextPart → JSON-RPC SendStreamingMessage → 127.0.0.1:18096（16:19:21.476）
 ㉘ mock 返回 artifact（业务 JSON，225B）→ BatchCoordinator 批次就绪 → 同轮恢复：
    │  LocalSkillDelegate action=resume（16:19:21.616）→ delegate_returned responseLength=225（16:19:21.723）
 ㉙ result_script：SkillActionRail :514 → evaluateResult(:533-548)
    │  SandboxScriptRunner 执行 normalize_recommend.py（[SKILL-SANDBOX] stdoutLength=457，16:19:21.845）
 ㉚ saveSuccessfulResult(:492) :503 revision+1 → [SKILL-ACTION] complete revision=1（16:19:21.848）
    │  SkillSession.save（MOA\agent\skill\SkillSession.java:94，KEY="__native_skill_execution__" :23
    │  results["recommend"]=产品目录, resultVersions["recommend"]=1）
[ask_user 中断（真实用户中断）]
 ㉛ 子 agent 下一动作 ask_user → AskUserRail#resolveInterrupt  CORE\harness\rails\interrupt\AskUserRail.java:47
    │  userInput==null → buildAskRequest(:91) → BaseInterruptRail#applyDecision(:226) :256 throw ToolInterruptException
 ㉜ SkillInteractionRail#onToolException            MOA\agent\skill\SkillInteractionRail.java:28
    │  → SkillEvents#emit → EventEmitters#emit(MOA\agent\interaction\EventEmitters.java:73) → session.writeStream
 ㉝ LocalSkillDelegate forward_interrupt kind=null（childCall=call_2898b180…=ask_user，16:19:24.926）
 ㉞ Orchestrator "forwarding local interrupt to client interruptKind=null"（16:19:24.940）
 ㉟ A2AAgentExecutor#handleStreamingChunk :346 → :359 emitter.requiresInput
    │  → 写入 TASK_STATE_INPUT_REQUIRED；task_released（16:19:24.958）
```

### 3.2 关键日志（run.log 原始行，节选）

```text
16:19:07.173 [pool-7-thread-7]      A2AMessageContext      A2A NEW task taskId=2083167b-... contextId=fin-run-e2e-852922
16:19:07.174 [pool-7-thread-7]      A2AAgentExecutor       A2A execute START ... resume=false stream=true
16:19:07.250 [react-agent-stream-22] IntentRouter          [MOA-INTENT] source=analysis explicitProvided=false timeoutMs=60000
16:19:07.254 [moa-intent-3]         PackIntentRecognition  [MOA-MATCH] source=pack rawCount=9 candidateCount=1
                                                          matches=[{id=cap.skill.finance.purchase,...,kind=SKILL,risk=LOW}]
16:19:10.866 [moa-intent-4]         ModelTaskCardinalityAnalysis [MOA-CARDINALITY] source=MODEL outcome=SINGLE elapsedMs=3611
16:19:10.867 [react-agent-stream-22] DefaultRequestRoutingPolicy [MOA-INTENT] cardinality=SINGLE candidateCount=1 directFastEligible=true
16:19:10.869 [react-agent-stream-22] IntentRoutingRail     [MOA-ROUTE] path=FAST reason=SINGLE
16:19:10.876 [react-agent-stream-22] StagePlanningRail     [MOA-EXEC] revision=1 todoId=v1-todo-1 action=prepare
16:19:10.911 [react-agent-stream-22] LocalSkillDelegate    [MOA-LOCAL-DELEGATE] action=start
16:19:21.274 [react-agent-stream-23] SkillActionRail        recommend stage=before_script action=begin
16:19:21.433 [react-agent-stream-23] SandboxScriptRunner    [SKILL-SANDBOX] stage=execute gatewayCode=0 stdoutLength=16
16:19:21.437 [react-agent-stream-23] SkillActionRail        recommend action=start type=A2A；delegate_requested adapter=versatile-wealth
16:19:21.447 [react-agent-stream-22] LocalSkillDelegate    forward_interrupt kind=a2a_delegate childCall=call_a5f0dc6d…
16:19:21.448 [react-agent-stream-22] StagePlanningRail    [MOA-EXEC] action=await_result interrupted=true
16:19:21.476 [pool-7-thread-7]      A2ARemoteAgentClient   A2A call agent=versatile-wealth streaming=true contextId=fin-run-e2e-852922
16:19:21.616 [react-agent-stream-24] LocalSkillDelegate    action=resume          ← 同轮内 A2A 结果返回后恢复
16:19:21.723 [react-agent-stream-25] SkillActionRail        recommend delegate_returned responseLength=225
16:19:21.724 [react-agent-stream-25] SkillActionRail        recommend result_script begin
16:19:21.845 [react-agent-stream-25] SandboxScriptRunner    [SKILL-SANDBOX] stdoutLength=457   ← normalize_recommend.py
16:19:21.848 [react-agent-stream-25] SkillActionRail        recommend complete revision=1
16:19:24.926 [react-agent-stream-24] LocalSkillDelegate    forward_interrupt kind=null childCall=call_2898b180… ← ask_user
16:19:24.940 [pool-7-thread-7]      A2AEnabledServeOrchestrator forwarding local interrupt to client interruptKind=null
16:19:24.940 [pool-7-thread-7]      A2AAgentExecutor         A2A interrupt detected message=[选择产品] 为您推荐以下理财产品…
16:19:24.958 [pool-7-thread-7]      A2AAgentExecutor         task_released
```

### 3.3 SSE 输出（前端视角，关键事件）

```text
task_submitted → task_working
todolist_item  v1-todo-1「我要买理财」候选 cap.skill.finance.purchase（PENDING→RUNNING）
todolist_item  (append) 推荐理财产品 call_a5f0dc6d…（RUNNING→SUCCEEDED）
final_answer_chunk "1. 稳盈财富1号\n2. 稳盈财富2号"
interrupt_start content="[选择产品] … [购买金额] …"
               questions=[选择产品(稳盈财富1号/稳盈财富2号), 购买金额(1,000元/5,000元/10,000元)]
               interrupt_id="moa-local-skill--djEtdG9kby0x.Y2FsbF8yODk4YjE4MGQzYWQ0MGU3OGRlMmM2NzA"
event:interrupt task_input_required → TASK_STATE_INPUT_REQUIRED
```

> interrupt_id 格式：`moa-local-skill--base64(v1-todo-1).base64(call_2898b180d3ad40e78de2c670)`，由 `LocalDelegationIds#external`（:45）生成，是下一轮恢复的凭据。

### 3.4 Mock L2 侧证据（versatile-wealth:18096 收到的 A2A 报文）

```json
{"jsonrpc":"2.0","id":"67be1274-…","method":"SendStreamingMessage","params":{"message":{
  "contextId":"fin-run-e2e-852922","role":"ROLE_USER",
  "parts":[{"text":"{\"intent\":\"理财选品购买\",\"query\":\"请推荐理财产品\",\"idempotencyKey\":\"fin-run-e2e-852922:v1-todo-1:call_a5f0dc6d7d09…\"}"}]}}}
```

mock 应答（`_build_result` wealth 分支，A2A SSE artifactUpdate）：

```json
{"success":true,"productList":[
  {"productCode":"WF001","productName":"稳盈财富1号","minBuyAmount":"1000"},
  {"productCode":"WF002","productName":"稳盈财富2号","minBuyAmount":"5000"}],
 "bankCardNumber":"6222021234567890123"}
```

---

## 4. 第二轮调用栈：选品+金额 → select + wealth_balance → confirm_action 中断

### 4.1 调用栈（方法级）

```
[HTTP 入口层] — 与第一轮 ①~⑨ 相同，差异点：
 ④' A2AMessageContext：A2A RESUME（historySize=2，16:19:39.854）
 ⑥' A2AAgentExecutor：A2A execute START resume=true（textLen=20）
[恢复匹配层]
 ㊀ A2AEnabledServeOrchestrator#tryResumePending   RT\orchestrator\A2AEnabledServeOrchestrator.java:225
    │  识别本会话挂起的本地中断（pending toolCallId = moa-local-skill--…call_2898b180…）
    │  runAgentAndCaptureInterrupt(:257) 带 resume userInputs 重入主 agent
    │  ※ 意图识别未重跑（本轮无 [MOA-INTENT]/[MOA-ROUTE]/[MOA-MATCH] 日志）
[恢复校验层 — 统一恢复凭据校验口]
 ㊁ IntentRoutingRail#beforeToolCall               MOA\agent\routing\IntentRoutingRail.java:339
    │  :362 call.getId()==plan.getPendingCall() 防重（同一 pending 不得重复消费）
    │  Skill 中断恢复 → 直接续接（非 Skill 结果才做意图跳变检测）；:376-386 finishFast
 ㊂ StagePlanningRail#resumeExecution              MOA\agent\planning\StagePlanningRail.java:656
    │  :665 engine.resumeLocal（MOA\agent\execution\ExecutionEngine.java:99 → delegate.resume）
 ㊃ LocalSkillDelegate#resume                       [MOA-LOCAL-DELEGATE] action=resume（16:19:39.887）
    │  子 agent（NativeSkillAgent）从 ask_user 挂起点恢复，用户输入"稳盈财富1号/10000元"
    │  作为 ask_user 工具结果注入
[select 操作（SCRIPT 型，同步完成不中断）]
 ㊄ SkillActionRail#startOperation SCRIPT 分支      MOA\agent\skill\SkillActionRail.java:384-386
    │  before_script 由 validateBeforeScript(:399) 执行 → SandboxScriptRunner#run：validate_selection.py
    │  脚本入参 scriptInput(:653) = {arguments, results, versions, metadata, result, execution_id, revision}
    │  [SKILL-SANDBOX] stdoutLength=295 → complete revision=2（16:19:42.665）
[wealth_balance 操作（A2A 型）]
 ㊅ before_script（[SKILL-SANDBOX] stdoutLength=16，16:19:44.729）
 ㊆ A2A 委派：delegate_requested adapter=versatile-account intent=查询账户余额（16:19:44.734）
    │  委派 query 由 query-template 渲染："查询尾号为0123的卡的余额"
    │  idempotencyKey="fin-run-e2e-852922:v1-todo-1:call_d4d575fadf464342b68697b0"
 ㊇ a2a_delegate 中断上抛（forward_interrupt，16:19:44.740）
 ㊈ RemoteInvocationBatchCoordinator batch 3e9235ac → A2ARemoteAgentClient call versatile-account
    │  textLen=137（16:19:44.745）→ 127.0.0.1:18094
 ㊉ mock 返回 bankCardBalanceList → 同轮恢复：delegate_returned responseLength=225（16:19:44.992）
 ㊋ result_script：fund_planning.py normalize_balance（[SKILL-SANDBOX] stdoutLength=209，16:19:45.131）
    │  校验：queryStatus=="成功"、bankCardNumber 尾号==0123 且恰 1 行、
    │  currencyCode=="001" 人民币可用余额 → 产出 {bank_card, balance:"10000.00", currency:"CNY",
    │  selection_version}，契约校验 assets\edp\skills\contracts\wealth_balance.yml（additionalProperties:false）
 ㊌ complete revision=3（16:19:45.132）
[confirm_action 中断（真实用户中断，购买授权）]
 ㊍ SkillActionRail#executeBusiness :304 confirm_action 分支
    │  purchase operation 配置 confirmation-required: true（skill-local.example.yml）
    │  → ToolInterruptException → SkillInteractionRail#onToolException → interrupt_start
 ㊎ forward_interrupt kind=null（childCall=call_4a6685a3…，16:19:47.251）
 ㊏ Orchestrator forwarding local interrupt → A2AAgentExecutor requiresInput
    │  → TASK_STATE_INPUT_REQUIRED → task_released（16:19:47.290）
```

### 4.2 关键日志

```text
16:19:39.854 [pool-7-thread-8]  A2AMessageContext       A2A RESUME historySize=2
16:19:39.854 [pool-7-thread-8]  A2AAgentExecutor        A2A execute START resume=true（textLen=20）
16:19:39.887 [react-agent-stream-26] LocalSkillDelegate  [MOA-LOCAL-DELEGATE] action=resume
16:19:42.528 [react-agent-stream-27] SkillActionRail      select action=start type=SCRIPT
16:19:42.530 [react-agent-stream-27] SkillActionRail      select result_script begin
16:19:42.662 [react-agent-stream-27] SandboxScriptRunner  [SKILL-SANDBOX] stdoutLength=295   ← validate_selection.py
16:19:42.665 [react-agent-stream-27] SkillActionRail      select complete revision=2
16:19:44.614 [react-agent-stream-27] SkillActionRail      wealth_balance before_script begin
16:19:44.729 [react-agent-stream-27] SandboxScriptRunner  [SKILL-SANDBOX] stdoutLength=16
16:19:44.734 [react-agent-stream-27] SkillActionRail      wealth_balance delegate_requested adapter=versatile-account
16:19:44.740 [react-agent-stream-26] LocalSkillDelegate  forward_interrupt kind=a2a_delegate childCall=call_d4d575fa…
16:19:44.745 [pool-7-thread-8]  A2ARemoteAgentClient     A2A call agent=versatile-account textLen=137
16:19:44.992 [react-agent-stream-29] SkillActionRail      wealth_balance delegate_returned responseLength=225
16:19:44.993 [react-agent-stream-29] SkillActionRail      wealth_balance result_script begin
16:19:45.131 [react-agent-stream-29] SandboxScriptRunner  [SKILL-SANDBOX] stdoutLength=209   ← fund_planning.py
16:19:45.132 [react-agent-stream-29] SkillActionRail      wealth_balance complete revision=3
16:19:47.251 [react-agent-stream-28] LocalSkillDelegate   forward_interrupt kind=null childCall=call_4a6685a3… ← confirm_action
16:19:47.274 [pool-7-thread-8]  A2AEnabledServeOrchestrator forwarding local interrupt interruptKind=null
16:19:47.274 [pool-7-thread-8]  A2AAgentExecutor          A2A interrupt detected message=确认执行：购买所选理财产品
16:19:47.290 [pool-7-thread-8]  A2AAgentExecutor          task_released
```

### 4.3 SSE 输出（关键事件）

```text
todolist_item (append) 校验产品与购买金额 call_830f4635…（RUNNING→SUCCEEDED）
final_answer_chunk "已记录产品和金额，将核实资金情况；尚未购买。"
todolist_item (append) 查询理财卡可用余额 call_d4d575fa…（RUNNING→SUCCEEDED）
interrupt_start content="确认执行：购买所选理财产品\n- 产品编号：WF001\n- 产品名称：稳盈财富1号\n- 购买金额（元）：10000.00\n请回复"确认"或"拒绝"。"
               questions=[{options:["确认","拒绝"]}]
               interrupt_id="moa-local-skill--djEtdG9kby0x.Y2FsbF80YTY2ODVhMzU1NWU0ODc3ODE2ODliODc"
event:interrupt task_input_required → TASK_STATE_INPUT_REQUIRED
```

### 4.4 Mock L2 侧证据（versatile-account:18094 收到的 A2A 报文）

```json
"parts":[{"text":"{\"intent\":\"查询账户余额\",\"query\":\"查询尾号为0123的卡的余额\",\"idempotencyKey\":\"fin-run-e2e-852922:v1-todo-1:call_d4d5…\"}"}]
```

mock 应答（`_build_result` account 分支，对齐 fund_planning.py 契约）：

```json
{"success":true,"business_data":{"queryStatus":"成功","bankCardBalanceList":[
  {"bankCardNumber":"6222021234567890123","currencyBalanceList":[
    {"currencyCode":"001","currencyName":"人民币可用余额","balance":"10000.00"}]}]}}
```

---

## 5. 第三轮调用栈：确认 → purchase → 终态完成

### 5.1 调用栈（方法级）

```
[HTTP 入口层] — 同第二轮恢复路径：
 ④' A2AMessageContext：A2A RESUME（historySize=4，16:20:01.768）；A2A execute START resume=true（textLen=2）
 ㊀ tryResumePending → ㊁ IntentRoutingRail#beforeToolCall(:339) 凭据校验
    │  → ㊂ StagePlanningRail#resumeExecution(:656) → resumeLocal → ㊃ LocalSkillDelegate action=resume（16:20:01.897）
[confirm_action 恢复 → purchase]
 ㊄ 用户输入"确认"作为 confirm_action 工具结果注入 → SkillActionRail#executeBusiness(:298)
    │  :304 confirm_action 分支校验通过 → 进入下一 operation purchase
[purchase 操作（A2A 型，terminal: true）]
 ㊅ before_script（[SKILL-SANDBOX] stdoutLength=16，16:20:03.772）
 ㊆ A2A 委派：delegate_requested adapter=versatile-wealth intent=理财选品购买（16:20:03.777）
    │  委派 query（query-template）："购买理财产品：产品名称：稳盈财富1号，产品代码：WF001，金额：10000.00元"
    │  idempotencyKey="fin-run-e2e-852922:v1-todo-1:call_c1650fed506e402490c10305"
 ㊇ a2a_delegate 中断上抛（forward_interrupt，16:20:03.804）
 ㊈ BatchCoordinator batch 4e2c6c25 → A2ARemoteAgentClient call versatile-wealth textLen=159（16:20:03.817）
 ㊉ mock 18096：`re.search` 从委派 query 提取产品名/代码/金额 → 返回 productBuyResponse
    │  → 同轮恢复 delegate_returned responseLength=172（16:20:04.017）
 ㊋ result_script：fund_planning.py normalize_purchase（[SKILL-SANDBOX] stdoutLength=290，16:20:04.171）
    │  校验：buyStatus=="购买成功"、failCause 空、productCode/productName/buyAmount 与选品一致；
    │  guard()（fund_planning.py:85）：card(card_tail)==card(wealth_card)[-4:]、
    │  money(balance=10000.00)>=amount(10000) 资金充足
 ㊌ complete revision=4（16:20:04.175）
[终局收敛]
 ㊍ SkillActionRail：action=finish status=success（purchase terminal=true，16:20:04.176）
 ㊎ LocalSkillDelegate：action=complete status=success（16:20:06.207）
 ㊏ StagePlanningRail#completeExecution(:686) [MOA-EXEC] v1-todo-1 status=SUCCEEDED（16:20:06.212）
 ㊐ 主 agent 生成终答 → A2A task_completed → task_released（16:20:08.277）
```

### 5.2 关键日志

```text
16:20:01.768 [pool-7-thread-9]  A2AMessageContext       A2A RESUME historySize=4
16:20:01.769 [pool-7-thread-9]  A2AAgentExecutor        A2A execute START resume=true（textLen=2）
16:20:01.897 [react-agent-stream-30] LocalSkillDelegate  [MOA-LOCAL-DELEGATE] action=resume
16:20:03.655 [react-agent-stream-31] SkillActionRail      purchase before_script begin
16:20:03.772 [react-agent-stream-31] SandboxScriptRunner  [SKILL-SANDBOX] stdoutLength=16
16:20:03.777 [react-agent-stream-31] SkillActionRail      purchase action=start type=A2A；delegate_requested adapter=versatile-wealth
16:20:03.804 [react-agent-stream-30] LocalSkillDelegate  forward_interrupt kind=a2a_delegate childCall=call_c1650fed…
16:20:03.817 [pool-7-thread-9]  A2ARemoteAgentClient     A2A call agent=versatile-wealth streaming=true textLen=159
16:20:04.017 [react-agent-stream-33] SkillActionRail      purchase delegate_returned responseLength=172
16:20:04.019 [react-agent-stream-33] SkillActionRail      purchase result_script begin
16:20:04.171 [react-agent-stream-33] SandboxScriptRunner  [SKILL-SANDBOX] stdoutLength=290   ← fund_planning.py
16:20:04.175 [react-agent-stream-33] SkillActionRail      purchase complete revision=4
16:20:04.176 [react-agent-stream-33] SkillActionRail      action=finish status=success success=true
16:20:06.207 [react-agent-stream-32] LocalSkillDelegate   action=complete status=success
16:20:06.212 [react-agent-stream-32] StagePlanningRail   [MOA-EXEC] todoId=v1-todo-1 action=result status=SUCCEEDED
16:20:08.277 [pool-7-thread-9]  A2AAgentExecutor         task_released
```

### 5.3 SSE 输出（关键事件）

```text
todolist_item (append) 购买所选理财产品 call_c1650fed…（risk=HIGH，RUNNING→SUCCEEDED）
final_answer_chunk "已收到所选理财产品的购买成功回执。"
todo_end v1-todo-1 COMPLETED
  result={"protocol":"native_skill_v1","data":{"operation":"purchase","success":true,
         "summary":{"product_id":"WF001","amount":"10000.00","buy_status":"购买成功",
                    "receipt_binding":"CONFIRMED_REQUEST"},"version":4,"result_ref":"purchase@4"}}
event:final task_completed → TASK_STATE_COMPLETED
```

### 5.4 Mock L2 侧证据（versatile-wealth:18096 收到的 A2A 报文）

```json
"parts":[{"text":"{\"intent\":\"理财选品购买\",\"query\":\"购买理财产品：产品名称：稳盈财富1号，产品代码：WF001，金额：10000.00元\",\"idempotencyKey\":\"fin-run-e2e…\"}"}]
```

mock 应答（`_build_result` purchase 分支，从 query 正则提取回执字段）：

```json
{"success":true,"business_data":{"productBuyResponse":{
  "buyStatus":"购买成功","failCause":"","productCode":"WF001",
  "productName":"稳盈财富1号","buyAmount":"10000.00"}}}
```

---

## 6. 公共机制说明

### 6.1 两类中断的统一模型（工具调用中断 + 恢复）

| | a2a_delegate 中断 | 真实用户中断（ask_user / confirm_action） |
|---|---|---|
| 产生点 | `SkillActionRail#startOperation` A2A 分支（:358，envelope 携带 `_interrupt_kind="a2a_delegate"`） | `AskUserRail#resolveInterrupt`(:47) / `SkillActionRail#executeBusiness` :304 confirm_action 分支 |
| 上抛路径 | LocalSkillDelegate#forward(:342) → LocalDelegationIds#external(:45) 改写凭据 → Orchestrator#handleInterrupt(:361) → **BatchCoordinator** 异步出站 | 同左，但 kind=null，Orchestrator "forwarding local interrupt to client" |
| 恢复方式 | **同轮内**：mock 返回 → BatchCoordinator 批次就绪 → delegate resume（毫秒级，见 16:19:21.476→21.616） | **跨轮**：SSE `TASK_STATE_INPUT_REQUIRED` 终态挂起，等下一轮用户消息 |
| 恢复校验 | — | `IntentRoutingRail#beforeToolCall`(:339)：:362 pendingCall 防重；非 Skill 结果才做意图跳变检测；:376-386 finishFast |
| 恢复后回到 | `SkillActionRail#processResult` → result_script | NativeSkillAgent 对应工具挂起点（用户输入注入为工具结果） |

### 6.2 状态持久化

- 子 agent 执行态：`SkillSession`（`MOA\agent\skill\SkillSession.java`，session key `"__native_skill_execution__"` :23），字段 revision(:37)/results(:42)/resultVersions(:48)/pending(:54)/receipts(:57)/approvals(:52)。三个 A2A operation revision 依次 1→2→3→4，即存于此。
- 中断挂起态：`ReActAgent#commitInterrupt`（`CORE\...\ReActAgent.java:1187`）把 `ToolInterruptionState` 写入 session checkpointer（run.log 16:19:07 "Create new agent checkpointer store"）。
- 规划态：StagePlan 存 session（key `"__moa_stage_plan__"`），v1-todo-1 跨三轮不变，pendingCall 逐轮更新。

### 6.3 线程模型（对应日志线程名）

| 线程名 | 归属 | 职责 |
|---|---|---|
| `pool-7-thread-N` | A2A HTTP worker | 每轮请求入口→编排（阻塞至终态） |
| `react-agent-stream-N` | ReActAgent STREAM_EXECUTOR（ReActAgent.java:97-98） | 主/子 DeepAgent 流式执行（22/23 主任务，24-33 交替） |
| `moa-intent-N` | 意图并行执行器 | PackIntentRecognition（-3）与 ModelTaskCardinalityAnalysis（-4）并行 |
| `task-deep_agent_task_*` | controller 任务线程 | 主任务与 local-skill 子任务生命周期（requires interaction / completed 事件） |
| `HttpClient-*-Worker-N` | 远程 A2A client 回调 | BatchCoordinator 接收 mock SSE 状态流 |

### 6.4 SCRIPT 与 A2A 两类 operation 的执行差异

| | SCRIPT（select） | A2A（recommend/wealth_balance/purchase） |
|---|---|---|
| 分派点 | `SkillActionRail#startOperation` SCRIPT 分支(:384-386) | A2A 分支(:358) |
| 执行体 | `SandboxScriptRunner#run`(:50)：SKILL_INPUT 环境变量注入(:58) + `sandbox.code().executeCode(python)` → 127.0.0.1:8321；环境变量经 `ScriptEnvironmentBindings` 白名单（:100，ScriptEnvironmentProvider SPI 可扩展） | `RemoteInvocationBatchCoordinator` → `A2ARemoteAgentClient`（JSON-RPC SendStreamingMessage）→ mock L2 |
| 中断 | 无（同步完成） | 必产生 a2a_delegate 中断（同轮恢复） |
| 结果处理 | result_script（validate_selection.py，stdout 295B） | delegate_returned 后 result_script（normalize_*.py / fund_planning.py，stdout 457/209/290B） |

### 6.5 本次联调的 Mock 契约对齐（背景备注）

wealth_balance/purchase 环节曾因 mock 返回扁平结构触发 `MISSING_OR_AMBIGUOUS_RESULT`（fund_planning.py `unpack` 深度搜索找不到标记字段）。本次运行前已将 `mock_versatile_server.py#_build_result` 三个分支对齐业务契约（account→`bankCardBalanceList`、purchase→`productBuyResponse`、purchase 字段用 `re.search` 从委派 query 提取），重启 mock 后第二轮、第三轮均一次通过。

---

## 7. 源码速查表

| 环节 | 类#方法 | 位置 |
|---|---|---|
| REST 入口 | CustomRestHandler#handle | `EXT\...\CustomRestAutoConfiguration.java:88-90` |
| body→A2A | MoaCustomRestAdapter#toA2ARequest | `MOA\runtime\customrest\MoaCustomRestAdapter.java:81` |
| SSE 投影 | MoaCustomRestAdapter#STATUS_EVENT_MAP / detectEventType | 同上 `:50 / :455` |
| A2A 准入 | A2AAgentExecutor#execute / executeStreaming / handleStreamingChunk | `RT\controller\a2a\A2AAgentExecutor.java:154/273/346`（requiresInput :359） |
| 编排循环 | A2AEnabledServeOrchestrator#streamQuery / tryResumePending / handleInterrupt | `RT\orchestrator\A2AEnabledServeOrchestrator.java:181/225/361` |
| 主 agent 执行 | JiuwenCoreAgentExtHandler#streamQuery | `EXT\...\JiuwenCoreAgentExtHandler.java:168` |
| ReAct 循环 | ReActAgent#stream / railedModelStreamCall / commitInterrupt | `CORE\core\singleagent\agents\ReActAgent.java:1080/600/1187` |
| 主 agent 组装 | MoaRuntimeAutoConfiguration#moaAgentHandler | `MOA\runtime\orchestration\MoaRuntimeAutoConfiguration.java:210-241` |
| 意图路由 | IntentRouter#route / PackIntentRecognition#recognize / ModelTaskCardinalityAnalysis | `MOA\agent\routing\IntentRouter.java:147`、`recognition\PackIntentRecognition.java:35`、`cardinality\ModelTaskCardinalityAnalysis.java:76` |
| 路由决策 | DefaultRequestRoutingPolicy#decide | `MOA\agent\routing\policy\DefaultRequestRoutingPolicy.java:48` |
| 路由/恢复口 | IntentRoutingRail#beforeInvoke / beforeToolCall | `MOA\agent\routing\IntentRoutingRail.java:120/339` |
| 规划 | StagePlanningRail#startFast / startExecution / resumeExecution / completeExecution | `MOA\agent\planning\StagePlanningRail.java:862/570/656/686` |
| 执行引擎 | ExecutionEngine#startLocal / resumeLocal / dispatch | `MOA\agent\execution\ExecutionEngine.java:81/99/129` |
| 本地委派 | LocalSkillDelegate#advance / completeStep / forward | `MOA\agent\skill\LocalSkillDelegate.java:105/227/342` |
| 子 agent | NativeSkillAgent#create | `MOA\agent\skill\NativeSkillAgent.java:63` |
| operation 状态机 | SkillActionRail#executeBusiness / startOperation / saveSuccessfulResult | `MOA\agent\skill\SkillActionRail.java:298/358/492` |
| 沙箱 | SandboxScriptRunner#run / resolveEnvironment | `MOA\agent\skill\SandboxScriptRunner.java:50/100` |
| 中断基建 | AskUserRail#resolveInterrupt / BaseInterruptRail#applyDecision / SkillInteractionRail#onToolException | `CORE\harness\rails\interrupt\AskUserRail.java:47`、`BaseInterruptRail.java:226`；`MOA\agent\skill\SkillInteractionRail.java:28` |
| 委派凭据 | LocalDelegationRail / LocalDelegationIds | `MOA\agent\execution\local\LocalDelegationRail.java:30/49`、`LocalDelegationIds.java:45/59` |
| 远程 A2A | RemoteInvocationBatchCoordinator / A2ARemoteAgentClient | `RT\orchestrator\RemoteInvocationBatchCoordinator.java:151/367`、`RT\controller\a2a\client\A2ARemoteAgentClient.java:151/254/324` |
| Skill 状态 | SkillSession / SkillOperation | `MOA\agent\skill\SkillSession.java:23`、`SkillOperation.java:19` |
| Demo 定制 | FinanceSkillCandidateResolver#resolve / WorkflowIntentRecognition | `DEMO\FinanceSkillCandidateResolver.java:27`、`DEMO\intent\WorkflowIntentRecognition.java:47` |
| Mock L2 | mock_versatile_server.py#_build_result | `demo\moa_agent\moa-edp\mock_versatile_server.py:158` |
| 业务脚本 | fund_planning.py / validate_selection.py / normalize_recommend.py | `demo\moa_agent\moa-edp\assets\edp\skills\fund_planning_skill\scripts\` |
| operation 配置 | skill-local.example.yml（8 个 operation） | `demo\moa_agent\moa-edp\assets\edp\config\skill-local.example.yml` |

---

## 8. 文档校验记录

本文档经过三重校验：

1. **行号锚点核对**：全部 33 组"类#方法 + 文件:行号"锚点逐条对照源码复核（±5 行窗口直读）。结果：1 处实质不符已修正（`SkillActionRail#startOperation` SCRIPT 分支 :399 → :384-386，:399 实为 `validateBeforeScript` 定义）；9 处 ±1~4 行偏差已修正（多为注解行/签名行/常量表条目行之争）；其余全部行号级精确一致。
2. **凭据格式实证**：SSE `interrupt_id` 中 base64 段实际解码验证——`djEtdG9kby0x`→`v1-todo-1`、`Y2FsbF8yODk4YjE4MGQzYWQ0MGU3OGRlMmM2NzA=`→`call_2898b180d3ad40e78de2c670`、`Y2FsbF80YTY2ODVhMzU1NWU0ODc3ODE2ODliODc=`→`call_4a6685a3555e487781689b87`，与 run.log `forward_interrupt` 的 childCall 一一对应，`moa-local-skill--base64(parent).base64(child)` 格式断言成立（URL-safe Base64 无 padding，`LocalDelegationIds.java:24/:95-96`）。
3. **语义断言核对**：tools/rails 组装（`MoaRuntimeAutoConfiguration:219-220`）、六 rail 清单（`NativeSkillAgent:72-77`）、idempotencyKey 格式（`ExecutionEngine:143`/`SkillActionRail:391-393`）、意图/任务数并行（`RoutingServices:155-163`）、yml 8 个 operation 及 recommend/select/wealth_balance/purchase 脚本与 query-template、mock `_build_result` 三分支、`fund_planning.py guard()` 校验逻辑（:90 `card(card_tail)==card(wealth_card)[-4:]`、:111 `money(balance)>=amount`）——均与源码相符。

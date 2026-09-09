# EdpaEventRail 功能分析：思维链事件发射机制

> 分析对象：`edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/rail/EdpaEventRail.java`
> 分析目标：事件发射职责、代码实现、使用场景与运行时效果

---

## 一、核心定位

`EdpaEventRail` 是 EDPAgent 的**思维链事件发射 Rail**，**优先级 priority=80**（低于 TaskPlanningRail 的 90 和 EdpaTodoRail 的 95），在 DeepAgent 的 ReAct 循环中介入模型调用和工具调用的前后，向前端推送结构化事件流。

核心职责：**将 LLM 内部推理过程翻译成前端可感知的事件流**（思考过程、工具执行、任务进度、中断确认等），使前端能实时展示思维链动画、任务列表变化和工具执行状态。

---

## 二、事件类型体系

**文件**：`EdpaEventType.java`

共 20 种事件类型，严格配对：

| 类别 | 事件类型 | 配对规则 | 说明 |
|------|---------|----------|------|
| 会话生命周期 | `conversation_start` ↔ `conversation_end` | 1:1 | 会话级，每轮一对 |
| 思维链 | `think_start` → `think_chunk` → `think_end` | 每轮 LLM 一对 | 推理过程展示 |
| 最终回答 | `final_answer_start` → `final_answer_chunk` → `final_answer_end` | 1:1 | 任务完成后的回答 |
| 业务工具 | `tool_start` ↔ `tool_end` | 1:1 | call_versatile/call_mcp 执行 |
| 任务列表 | `todolist_start` → `todolist_item` ×N → `todolist_end` | 1:N item | 任务列表快照 |
| 单任务执行 | `todo_start` ↔ `todo_end` | 按状态转移 | 单个任务开始/完成 |
| 用户中断 | `interrupt_start` ↔ `interrupt_end` | 跨轮配对 | ask_user 触发 |
| 异常 | `error_event` | 无配对 | 异常终止 |

---

## 三、七大 Hook 职责

### 3.1 beforeInvoke() — 会话开始

**代码位置**：`EdpaEventRail.java#L329-L385`

```
beforeInvoke(ctx)
  ├── 清理上次会话残留状态
  │     ├── conversationClosed.remove(sid)
  │     ├── responseTemplate.remove(sid)
  │     └── cleanupStaleTodoDirs(sid)    ← 清理非当前会话的旧 .todo 目录
  │
  ├── A2A 续传检测
  │     └── a2aResuming=true → 跳过 conversation_start（同请求内不重复）
  │
  ├── 发射 conversation_start
  │     → emit(CONVERSATION_START, {})
  │
  ├── 静默初始化 todo 状态追踪
  │     ├── lastTodolistFingerprint ← 空指纹
  │     └── prevTodoStatus ← 空快照
  │     → 不发跨轮 todolist（Rule 9：前端跨轮自行持久化）
  │
  └── 发射 request_start
        → emit(INTERRUPT_START, {content:"您的请求已收到。", interrupt_id:"response_template"})
```

### 3.2 beforeModelCall() — 模型调用前

**代码位置**：`EdpaEventRail.java#L389-L412`

```
beforeModelCall(ctx)
  └── 缓存用户原始 query 文本
        ├── 从 messages 提取最后一条 UserMessage
        ├── 与上次缓存的 _edp_user_input 比较
        │     → 不同 → 重置 think_turn_count=0（新用户请求入口）
        └── 写入 _edp_user_input
```

**不发任何事件**。think_start 移到 afterModelCall（设计文档 §7.1）。

### 3.3 afterModelCall() — 模型调用后（核心）

**代码位置**：`EdpaEventRail.java#L430-L480`

```
afterModelCall(ctx)
  ├── 1. 检测 LLM 返回了 todo_create → 标记发射 planning_start
  │     └── emit(INTERRUPT_START, {content:"我们正在为您进行规划。", ...})
  │
  ├── 2. 发射 think 对（每轮一对，严格 pair）
  │     ├── 检测是否"只调用 todo_modify"（延迟 think 到 afterToolCall）
  │     │     → 是 → 暂存 KEY_PENDING_THINK
  │     │     → 否 → emitThinkPair(ctx, sid, thinkContent)
  │     │
  │     └── emitThinkPair 内部：
  │           ├── emit(THINK_START, {})
  │           ├── fixed_script 模式 → 分帧发射 think_chunk
  │           │   ├── 按阶段选择话术（planning/executing/resuming）
  │           │   ├── 按 query_patterns 匹配关键词
  │           │   ├── splitFixedScriptsIntoFrames() → 按字符数切帧
  │           │   └── 帧间 sleep（控制节奏，min_interval_ms 或 tokens_between_frames*25ms）
  │           └── emit(THINK_END, {})
  │
  └── 3. finish_reason=stop 且无 tool_calls → 发 final_answer 对
        ├── emit(FINAL_ANSWER_START, {})
        ├── emit(FINAL_ANSWER_CHUNK, {content: LLM 输出})
        └── emit(FINAL_ANSWER_END, {})
```

### 3.4 beforeToolCall() — 工具调用前

**代码位置**：`EdpaEventRail.java#L484-L560`

```
beforeToolCall(ctx)
  ├── 非业务工具（todo_create/todo_modify/ask_user）→ 不发 tool_start
  │
  ├── PLAN_FIRST blocked（未规划 todo，EdpaTodoRail 拦截）
  │     → 不发 tool_start（工具未真正执行）
  │     → 标记发射 planning_start
  │     └── 记录 skippedToolCallId（供 afterToolCall 跳过 tool_end）
  │
  └── 业务工具（call_versatile/call_mcp）→ 发 tool_start
        ├── 缓存 query_intent + query_description
        ├── 解析 tool_start 话术内容
        │     ├── 优先 query_intent_tool_text[effectiveIntent].tool_start
        │     ├── 回退 query_description
        │     └── 最终回退 general_scripts.tool_start
        └── emit(TOOL_START, {tool: toolName, content: toolStartContent})
```

### 3.5 afterToolCall() — 工具调用后

**代码位置**：`EdpaEventRail.java#L630-L700`

```
afterToolCall(ctx)
  ├── 业务工具 → emitBusinessToolEnd()
  │     ├── PLAN_FIRST blocked → 跳过 tool_end（skippedToolCallId）
  │     ├── 解析 tool_end 话术（query_intent → query_intent_tool_text[effectiveIntent].tool_end）
  │     ├── emit(TOOL_END, {tool, data, content})
  │     └── 快照 responseTemplate 到类级 map（防 ReAct 重建 extra 丢失）
  │
  ├── ask_user 中断恢复 → handleAskUserResume()
  │     ├── emit(INTERRUPT_END, {tool, interrupt_id})
  │     └── 标记 KEY_JUST_RESUMED（供下轮 think 选择 resuming 话术）
  │
  └── todo_create/todo_modify → emitTodoEvents()
        ├── 加载当前 todos
        ├── 指纹去重（fp 变化才重推 todolist）
        ├── 检测状态转移（prevTodoStatus → 当前 status）
        │
        ├── ① 发 todo_end（IN_PROGRESS→COMPLETED/CANCELLED 的任务）
        ├── ①.5 延迟 think（在 todo_end 之后、todolist 之前发射）
        ├── ② 发 todolist_start → todolist_item×N → todolist_end（指纹变化时）
        └── ③ 发 todo_start（PENDING→IN_PROGRESS 的任务）
```

### 3.6 onModelException() — 模型异常

**代码位置**：`EdpaEventRail.java#L868-L895`

```
onModelException(ctx)
  ├── 关闭 unclosed think_start（保 Rule 2）
  │     └── thinkOpen=true → emit(THINK_END, {})
  │
  ├── emit(ERROR_EVENT, {stage:"model", error_type:"...", content:"..."})
  └── emitConversationEnd()
```

### 3.7 onToolException() — 工具异常

**代码位置**：`EdpaEventRail.java#L901-L955`

```
onToolException(ctx)
  ├── ToolInterruptException（正常中断，如 ask_user）
  │     ├── 生成 interrupt_id（UUID）
  │     ├── 解析 ask_user 话术
  │     ├── emit(INTERRUPT_START, {tool, content, interrupt_id})
  │     └── interruptActive=true（供下轮 interrupt_end 配对）
  │     → 不发 error_event，conversation_end 由 afterInvoke 发射
  │
  └── 其他异常
        ├── 关闭 unclosed tool_start（保 Rule 6）
        │     └── toolOpen=true → emit(TOOL_END, {status:"failed"})
        ├── emit(ERROR_EVENT, {stage:"tool", error_type:"...", content:"..."})
        └── emitConversationEnd()
```

### 3.8 afterInvoke() — 会话结束

**代码位置**：`EdpaEventRail.java#L1031-L1100`

```
afterInvoke(ctx)
  ├── A2A 续传 → 跳过 conversation_end
  │
  ├── 出口话术（response_template）
  │     ├── 从类级 map 读取 responseTemplate[sid]
  │     ├── 有值且无中断挂起 → emit(INTERRUPT_START, {content, interrupt_id:"response_template"})
  │     └── 有中断挂起 → 跳过（content 冗余）
  │
  ├── emitConversationEnd(ctx, sid, exitContent)
  │
  └── 清理本轮状态
        ├── lastTodolistFingerprint.remove(sid)
        ├── thinkOpen.remove(sid)
        ├── toolOpen.remove(sid)
        ├── conversationClosed.remove(sid)
        ├── prevTodoStatus.remove(sid)
        └── interruptActive/interruptIdMap（跨轮持久化，非中断时清理）
```

---

## 四、事件发射机制：emit()

**代码位置**：`EdpaEventRail.java#L2137-L2160`

```java
private void emit(AgentCallbackContext ctx, EdpaEventType type, Map<String, Object> payload) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("event", type.wireName());
    event.put("timestamp", System.currentTimeMillis());
    event.put("conversation_id", sessionId(ctx));
    event.putAll(payload);

    // 日志含 content 截断预览（脱敏）
    String contentPreview = desensitizeSensitiveFields(
            abbreviate(String.valueOf(payload.getOrDefault("content", "")), 120));
    LOGGER.info("[EDPAgent] stream payload [{}]: {}", type.wireName(), contentPreview);

    // 通过 Session 的 writeStream 推送到前端
    ctx.getSession().writeStream(new OutputSchema("custom", 0, event));
}
```

事件通过 `ctx.getSession().writeStream()` 推送到前端，前端按 `event` 字段路由到不同的 UI 组件渲染。

---

## 五、使用场景举例：wealth-demo 理财购买

### 用户输入

```
"帮我推荐一款理财产品"
```

### 完整事件流时序

```
─── 第 1 轮 ReAct（LLM 思考 + 规划）───

beforeInvoke:
  → conversation_start
  → request_start（content: "您的请求已收到。"）

beforeModelCall:
  → 缓存用户 query "帮我推荐一款理财产品"
  → 重置 think_turn_count=0

afterModelCall:
  → LLM 返回 reasoning: "用户想推荐理财，需要创建任务列表..."
  → 检测到 todo_create → 标记 planning_start
  → emit planning_start（content: "我们正在为您进行规划。"）
  → emit think_start
  → think_chunk（fixed_script 模式，planning 阶段话术）
  │   → 帧1: "正"
  │   → 帧2: "在"
  │   → 帧3: "分"
  │   → 帧4: "析"
  │   → 帧5: "您"
  │   → 帧6: "的"
  │   → 帧7: "需"
  │   → 帧8: "求"
  │   → 帧9: "..."
  │   （每帧间隔 50ms，chars_per_frame=4）
  → think_end

beforeToolCall (todo_create):
  → 非业务工具，不发 tool_start
  → EdpaTodoRail.enrichTasks() 增强 catalog_id 参数

  [Core 执行 todo_create，创建 4 个任务]

afterToolCall (todo_create):
  → emitTodoEvents()
  → 加载 todos: [product_recommend=PENDING, interact_finance_rec=PENDING, ...]
  → 指纹变化（空 → 有4个任务）
  → emit todolist_start（content: "已生成任务规划"）
  → emit todolist_item × 4
  │   → {id:"uuid-001", content:"推荐理财产品", status:"PENDING"}
  │   → {id:"uuid-002", content:"交互式理财筛选", status:"PENDING"}
  │   → {id:"uuid-003", content:"确定购买产品和金额", status:"PENDING"}
  │   → {id:"uuid-004", content:"查询理财账户余额并购买", status:"PENDING"}
  → emit todolist_end（content: "任务规划完成"）
  → 状态转移检测：无 PENDING→IN_PROGRESS，不发 todo_start


─── 第 2 轮 ReAct（执行第一个任务）───

beforeModelCall:
  → query 未变，think_turn_count=1

afterModelCall:
  → LLM 返回 reasoning: "先执行 product_recommend，调用 call_versatile..."
  → LLM 返回 tool_calls: [todo_modify(product_recommend→IN_PROGRESS), call_versatile(query_intent="理财推荐")]
  → 检测到 todo_modify + call_versatile（非 onlyTodoModify）→ 正常发射 think
  → emit think_start
  → think_chunk（executing 阶段话术: "正在分析执行结果..."）
  → think_end

beforeToolCall (todo_modify):
  → 非业务工具，不发 tool_start

  [Core 执行 todo_modify，product_recommend → IN_PROGRESS]

afterToolCall (todo_modify):
  → emitTodoEvents()
  → 状态转移检测：product_recommend PENDING→IN_PROGRESS
  → emit todo_start（content: "开始执行：推荐理财产品"）
  → 指纹变化 → emit todolist_start + todolist_item×4 + todolist_end

beforeToolCall (call_versatile):
  → 业务工具 → 缓存 query_intent="理财推荐"
  → 解析 tool_start 话术: query_intent_tool_text["理财推荐"].tool_start
  → emit tool_start（content: "正在获取理财产品列表..."）

  [Core 执行 call_versatile，返回理财产品列表]

afterToolCall (call_versatile):
  → emit tool_end（content: "已获取理财产品列表", data: ...）
  → 快照 responseTemplate


─── 第 3 轮 ReAct（完成推荐，用户筛选）───

afterModelCall:
  → LLM 返回 reasoning: "推荐完成，标记 COMPLETED，开始筛选..."
  → LLM 返回 tool_calls: [todo_modify(product_recommend→COMPLETED, interact_finance_rec→IN_PROGRESS)]

beforeToolCall (todo_modify):
  → 非业务工具，不发 tool_start

  [Core 执行 todo_modify]

afterToolCall (todo_modify):
  → emitTodoEvents()
  → 状态转移检测：
  │   product_recommend: IN_PROGRESS→COMPLETED → emit todo_end（content: "推荐理财产品 已完成"）
  │   interact_finance_rec: PENDING→IN_PROGRESS → emit todo_start（content: "开始执行：交互式理财筛选"）
  → 指纹变化 → emit todolist_start + todolist_item×4 + todolist_end
  │   → product_recommend=COMPLETED, interact_finance_rec=IN_PROGRESS, ...


  → LLM 调用 ask_user（中断等待用户选择产品）
  → onToolException: ToolInterruptException
  → emit interrupt_start（content: "需要您确认以下信息", interrupt_id: "uuid-xxx"）
  → interruptActive=true

afterInvoke:
  → interruptActive=true → 不清理 interrupt 状态（跨轮持久化）
  → emit conversation_end
  → 会话挂起，等待用户输入


─── 用户回复（中断恢复 + 继续执行）───

"我选1号产品17元"

beforeInvoke:
  → conversation_start（新会话）
  → request_start

beforeModelCall:
  → 新 query "我选1号产品17元" → 重置 think_turn_count=0

afterModelCall:
  → LLM 命中动态路径1（shortcut_skip_filter）
  → LLM 返回 tool_calls: [todo_modify(interact_finance_rec→CANCELLED, product_select→IN_PROGRESS)]

beforeToolCall (ask_user 恢复):
  → _skip_tool=true（中断恢复，不真正执行 ask_user）
  → 非业务工具，不发 tool_start

afterToolCall (ask_user 恢复):
  → handleAskUserResume()
  → emit interrupt_end（tool: "ask_user", interrupt_id: "uuid-xxx"）
  → 标记 KEY_JUST_RESUMED（下轮 think 用 resuming 话术）

  [Core 执行 todo_modify]

afterToolCall (todo_modify):
  → emitTodoEvents()
  → 状态转移：
  │   interact_finance_rec: IN_PROGRESS→CANCELLED → emit todo_end（content: "交互式理财筛选 已完成"）
  │   product_select: PENDING→IN_PROGRESS → emit todo_start（content: "开始执行：确定购买产品和金额"）
  → emit todolist_start + todolist_item×4 + todolist_end

  ... 继续执行 product_select 和 fund_planning ...


─── 最终完成 ──

afterToolCall (todo_modify):
  → 最后一个任务 fund_planning → COMPLETED
  → emitTodoEvents()
  → 状态转移：fund_planning IN_PROGRESS→COMPLETED → emit todo_end
  → 指纹变化 → emit todolist_start + todolist_item×4 + todolist_end
  │   → 全部 COMPLETED/CANCELLED

afterModelCall:
  → LLM 无 tool_calls → emit final_answer 对
  → emit final_answer_start
  → emit final_answer_chunk（content: "【需求概述】已为您购买1号理财产品17元..."）
  → emit final_answer_end

afterInvoke:
  → 无中断挂起
  → 出口话术 response_template → emit interrupt_start（content: "..."）
  → emit conversation_end
  → 清理所有状态
```

---

## 六、前端事件流渲染效果

前端收到的事件流（简化）：

```json
[
  {"event":"conversation_start","timestamp":1722000000000,"conversation_id":"sess-001"},
  {"event":"interrupt_start","content":"您的请求已收到。","interrupt_id":"response_template"},
  {"event":"interrupt_start","content":"我们正在为您进行规划。","interrupt_id":"response_template"},
  {"event":"think_start"},
  {"event":"think_chunk","content":"正"},
  {"event":"think_chunk","content":"在"},
  {"event":"think_chunk","content":"分"},
  {"event":"think_chunk","content":"析"},
  {"event":"think_end"},
  {"event":"todolist_start","content":"已生成任务规划"},
  {"event":"todolist_item","id":"uuid-001","content":"推荐理财产品","status":"PENDING"},
  {"event":"todolist_item","id":"uuid-002","content":"交互式理财筛选","status":"PENDING"},
  {"event":"todolist_item","id":"uuid-003","content":"确定购买产品和金额","status":"PENDING"},
  {"event":"todolist_item","id":"uuid-004","content":"查询理财账户余额并购买","status":"PENDING"},
  {"event":"todolist_end","content":"任务规划完成"},

  {"event":"think_start"},
  {"event":"think_chunk","content":"正在分析执行结果..."},
  {"event":"think_end"},
  {"event":"todo_start","content":"开始执行：推荐理财产品"},
  {"event":"todolist_start","content":"已生成任务规划"},
  {"event":"todolist_item","id":"uuid-001","content":"推荐理财产品","status":"IN_PROGRESS"},
  {"event":"todolist_item","id":"uuid-002","content":"交互式理财筛选","status":"PENDING"},
  {"event":"todolist_end","content":"任务规划完成"},
  {"event":"tool_start","tool":"call_versatile","content":"正在获取理财产品列表..."},
  {"event":"tool_end","tool":"call_versatile","content":"已获取理财产品列表","data":{...}},

  {"event":"todo_end","content":"推荐理财产品 已完成"},
  {"event":"todo_start","content":"开始执行：交互式理财筛选"},
  {"event":"todolist_start"},
  {"event":"todolist_item","id":"uuid-001","content":"推荐理财产品","status":"COMPLETED"},
  {"event":"todolist_item","id":"uuid-002","content":"交互式理财筛选","status":"IN_PROGRESS"},
  {"event":"todolist_end"},

  {"event":"interrupt_start","tool":"ask_user","content":"需要您确认以下信息","interrupt_id":"uuid-xxx"},
  {"event":"conversation_end"},

  {"event":"conversation_start"},
  {"event":"interrupt_start","content":"您的请求已收到。","interrupt_id":"response_template"},
  {"event":"interrupt_end","tool":"ask_user","interrupt_id":"uuid-xxx"},
  {"event":"think_start"},
  {"event":"think_chunk","content":"当前业务步骤已为您处理完毕"},
  {"event":"think_end"},
  {"event":"todo_end","content":"交互式理财筛选 已完成"},
  {"event":"todo_start","content":"开始执行：确定购买产品和金额"},
  {"event":"todolist_start"},
  {"event":"todolist_item","id":"uuid-002","content":"交互式理财筛选","status":"CANCELLED"},
  {"event":"todolist_item","id":"uuid-003","content":"确定购买产品和金额","status":"IN_PROGRESS"},
  {"event":"todolist_end"},

  {"event":"todo_end","content":"确定购买产品和金额 已完成"},
  {"event":"todo_end","content":"查询理财账户余额并购买 已完成"},
  {"event":"todolist_start"},
  {"event":"todolist_item","id":"uuid-004","content":"查询理财账户余额并购买","status":"COMPLETED"},
  {"event":"todolist_end"},

  {"event":"final_answer_start"},
  {"event":"final_answer_chunk","content":"【需求概述】已为您购买1号理财产品17元..."},
  {"event":"final_answer_end"},
  {"event":"interrupt_start","content":"response_template话术","interrupt_id":"response_template"},
  {"event":"conversation_end"}
]
```

---

## 七、关键设计规则

| 规则 | 说明 | 代码体现 |
|------|------|----------|
| **Rule 1：conversation 配对** | 每个会话必须有一对 conversation_start/conversation_end | beforeInvoke 发 start，afterInvoke 发 end |
| **Rule 2：think 严格 pair** | 每轮 LLM 推理一对 think_start/think_end，无 quirk | afterModelCall 发 pair，onModelException 关闭 unclosed |
| **Rule 3：think_end 在 final_answer 之前** | 思考完成后才能输出回答 | afterModelCall 先发 think pair 再发 final_answer |
| **Rule 6：tool 严格 pair** | 业务工具一对 tool_start/tool_end | beforeToolCall 发 start，afterToolCall 发 end |
| **Rule 7：interrupt 跨轮 pair** | 中断在 onToolException 发 start，恢复在 afterToolCall 发 end | interruptActive 跨轮持久化 |
| **Rule 8：异常不破坏 pair** | 异常时先关闭所有 unclosed 的 start，再发 error_event | onModelException/onToolException 先关再报错 |
| **Rule 9：不发跨轮 todolist** | conversation_start 时不重放 todolist | beforeInvoke 只静默初始化状态 |
| **Rule 12：todolist 逐条发射** | 每次状态变化必发 todolist_start→item×N→end | emitTodoEvents 指纹变化时发射 |
| **Rule 13：planning_start 检测** | 检测 todo_create 或 PLAN_FIRST 时发射 | afterModelCall 检测 + beforeToolCall PLAN_FIRST |

---

## 八、与 DeepAgent 的集成关系

```
DeepAgent 实例
  ├── ReActAgent（核心推理引擎）
  │     └── 工具调用循环（ReAct loop）
  │           ├── beforeInvoke ← EdpaEventRail（priority=80）
  │           │     └── conversation_start + request_start
  │           │
  │           ├── beforeModelCall ← EdpaEventRail
  │           │     └── 缓存 query，不发事件
  │           │
  │           ├── [LLM 推理]
  │           │
  │           ├── afterModelCall ← EdpaEventRail
  │           │     └── planning_start + think pair + final_answer pair
  │           │
  │           ├── beforeToolCall ← EdpaEventRail（在 EdpaTodoRail 之后执行）
  │           │     └── tool_start（仅业务工具）
  │           │
  │           ├── [Core 工具执行]
  │           │
  │           ├── afterToolCall ← EdpaEventRail
  │           │     └── tool_end / interrupt_end / todolist + todo_start/end
  │           │
  │           ├── onModelException ← EdpaEventRail
  │           │     └── think_end(unclosed) + error_event + conversation_end
  │           │
  │           ├── onToolException ← EdpaEventRail
  │           │     └── interrupt_start（正常中断）/ tool_end(failed) + error_event + conversation_end
  │           │
  │           └── afterInvoke ← EdpaEventRail
  │                 └── response_template + conversation_end + 状态清理
  │
  └── ctx.getSession().writeStream()
        └── OutputSchema("custom", 0, event)
              └── → SSE/WebSocket → 前端事件流渲染
```

### Rail 执行顺序

| 优先级 | Rail | 职责 |
|--------|------|------|
| 95 | EdpaTodoRail | 参数增强 + 守卫 + 依赖闭包 |
| 90 | TaskPlanningRail | Core 的 Todo 工具执行 |
| 80 | **EdpaEventRail** | **思维链事件发射** |
| 70 | ExecutionLimitRail | tool_limits 超限拦截 |
| 40 | ScriptsRail | 话术合规把关 |

EdpaEventRail priority=80 < 90，在 TaskPlanningRail 之后执行，保证 afterToolCall 时读到刷新后的 todo 缓存。

---

## 九、关键代码文件索引

| 文件 | 职责 |
|------|------|
| [EdpaEventRail.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/rail/EdpaEventRail.java) | 事件发射 Rail（7 个 Hook） |
| [EdpaEventType.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/config/EdpaEventType.java) | 事件类型枚举（20 种） |
| [SysScriptsConfig.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/config/SysScriptsConfig.java) | 话术配置模型（scriptconfig.yaml） |
| [ScriptResolver.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/config/ScriptResolver.java) | 话术解析器（query_intent → 话术映射） |
| [ScriptConstants.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/config/ScriptConstants.java) | 话术常量定义 |
| [EdpaAgentEnhancer.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/enhancer/EdpaAgentEnhancer.java) | Rail 注册入口 |
| [wealth-demo/scriptconfig.yaml](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/scenarios/wealth-demo/governance/scriptconfig.yaml) | 理财场景话术配置 |

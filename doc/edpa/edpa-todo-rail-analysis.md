# EdpaTodoRail 功能分析：结合 DeepAgent 使用效果与场景

> 分析对象：`edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/rail/EdpaTodoRail.java`
> 分析目标：功能职责、代码链路、使用场景与运行时效果

---

## 一、核心定位

`EdpaTodoRail` 是 EDPAgent 的 Todo 增强 Rail，**优先级 priority=95**（高于 TaskPlanningRail 的 90），在 DeepAgent 的 ReAct 循环中介入工具调用的前后。

它解决三个核心问题：

1. **LLM 不愿规划** — 业务工具调用前强制要求先创建 todo
2. **catalog_id 到 UUID 的依赖映射** — LLM 用 catalog_id 创建任务，Rail 自动还原 UUID 依赖关系
3. **运行时状态感知** — 动态注入当前 todo 状态，引导 LLM 按序执行

---

## 二、三大职责

### 职责 1：init() — 注入两段 Prompt

**代码位置**：`EdpaTodoRail.java#L155-L184`

```
init(agent)
  ├── 注入 edpa_todo_summary（priority=88）
  │     → "## 任务目录指引\n### 可用 catalog_id\n- **product_recommend**：推荐理财产品..."
  │     → LLM 看到可用 catalog_id 列表，知道可以用 catalog_id 创建任务
  │
  └── 注入 edpa_path_rules（priority=30）
        → "## 动态路径选择规则\n### 路径1：用户首次推荐即明确选择..."
        → LLM 知道什么条件下可以跳过某些任务
```

**todo summary prompt 构建**（`buildTodoSummaryPrompt()`）：

```java
sb.append("## 任务目录指引\n\n");
sb.append("### 可用 catalog_id\n\n");
for (TodoEntry entry : todolist.getEntries()) {
    sb.append("- **").append(entry.getCatalogId()).append("**：");
    sb.append(entry.getContent());
    if (entry.getDescription() != null && !entry.getDescription().isEmpty()) {
        sb.append(" — ").append(entry.getDescription());
    }
    if (!entry.getDependsOn().isEmpty()) {
        appendDependsOn(sb, entry.getDependsOn());
    }
    sb.append("\n");
}
```

**路径规则 prompt 构建**（`buildPathRulesPrompt()`）：

```java
sb.append("## 动态路径选择规则\n\n");
for (DynamicPath path : paths) {
    sb.append("### 路径").append(index).append("：").append(path.getDescription()).append("\n");
    sb.append("- 触发条件：").append(path.getTrigger()).append("\n");
    sb.append("- 操作：\n");
    sb.append("  1. 调用 todo_modify 将 ").append(String.join("、", path.getSkipSteps()))
            .append(" 对应的任务标记为 cancelled\n");
    sb.append("  2. ").append(path.getRedirect()).append("，继续后续任务\n");
}
```

### 职责 2：beforeToolCall() — 参数增强 + 规划前置守卫

**代码位置**：`EdpaTodoRail.java#L214-L272`

```
beforeToolCall(ctx)
  ├── 1. injectActiveTodoStatus(ctx)
  │     → 从 TodoStorage 加载当前会话的 todo 列表
  │     → pushSteering("当前任务状态：[product_recommend=COMPLETED, interact_finance_rec=IN_PROGRESS...]")
  │     → LLM 在下一轮思考中能看到已有任务和当前进度
  │     → 签名去重：todoId:status 拼接，状态没变化时不重复注入
  │
  ├── 2. enforcePlanBeforeBusinessTool(ctx, inputs, toolName)
  │     → 如果 toolName 是 call_mcp/call_versatile 且当前会话没有 todo
  │     → 阻止执行（_skip_tool=true），返回合成结果 PLAN_FIRST
  │     → pushSteering("你必须先调用 todo_create...")
  │     → LLM 被强制引导先规划
  │     → 已规划则清理 PLAN_FIRST_BLOCK 标记，放行
  │
  ├── 3. injectRealSessionId(args, realSid)
  │     → LLM 不传 session_id 时注入转义后的真实 sessionId
  │     → 避免 .todo/default/ 所有会话共用互相覆盖
  │     → sanitizeSessionId: 替换 \\/:*?"<>| 为 _
  │
  ├── 4. normalizeTodoModifyArgs(inputs, args, toolName)
  │     → LLM 有时用 updates[].task_id，Core 只认 todos[].id
  │     → 自动转换为 todos 格式，task_id → id
  │
  └── 5. enrichAndValidateTasks(inputs, args, toolName, ctx)
        ├── enrichTasks(args.get("tasks"))
        │     → 遍历 tasks[]，对每个带 catalog_id 的 task
        │     → 从 todolist.findByCatalogId(cid) 查找 entry
        │     → 填充 content/activeForm/description（enrichArgs）
        │     → meta_data 写入 catalog_id + skill 锚点
        │     → 不设 depends_on（UUID 此时未生成，在 afterToolCall 还原）
        │
        └── max_subtasks 校验
              → countTasks(args.get("tasks")) > actrule.getMaxSubtasks()
              → _skip_tool=true，返回 MAX_SUBTASKS_EXCEEDED
```

**enrichArgs 参数增强细节**：

```java
private static boolean enrichArgs(Map<String, Object> args, TodoEntry entry) {
    // 填充 content（LLM 只给了 catalog_id，没给 content）
    if (entry.getContent() != null && !args.containsKey("content")) {
        args.put("content", entry.getContent());
    }
    // 填充 activeForm（Core 必填字段，取 content 兜底）
    if (entry.getContent() != null && !args.containsKey("activeForm")) {
        args.put("activeForm", entry.getContent());
    }
    // 填充 description
    if (entry.getDescription() != null && !args.containsKey("description")) {
        args.put("description", entry.getDescription());
    }
    // meta_data 写入 catalog_id 锚点 + skill
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("catalog_id", entry.getCatalogId());
    meta.put("skill", entry.getSkill());
    args.put("meta_data", meta);
}
```

### 职责 3：afterToolCall() — 依赖闭包 + 完成检测

**代码位置**：`EdpaTodoRail.java#L558-L606`

```
afterToolCall(ctx)
  ├── todo_create 执行后：
  │     ├── buildAnchors(todos)
  │     │   → 从 todos 的 meta_data.catalog_id 建 {catalog_id: uuid} 映射
  │     │   → 例：{"product_recommend": "uuid-001", "interact_finance_rec": "uuid-002", ...}
  │     │
  │     ├── resolveDependencyMap(anchors, todolist)
  │     │   → 查 catalog 的 depends_on，替换 catalog_id 为真实 UUID
  │     │   → 例：interact_finance_rec depends_on ["product_recommend"]
  │     │         → 替换为 ["uuid-001"]
  │     │   → fail-fast：被依赖的 catalog_id 在 anchors 中找不到时抛异常
  │     │
  │     └── applyDependencies(todos, depMap) → 写回 storage
  │         → todo.json 中 depends_on 全部是合法 UUID
  │
  └── todo_modify 执行后：
        └── injectFinalAnswerDirective(ctx, sessionId, todos)
              → 检测所有任务是否全部 COMPLETED/DONE/CANCELLED
              → 是 → pushSteering("所有任务已完成。请直接输出最终回答...")
              → 引导 LLM 不再调用工具，直接输出 final_answer
              → 否 → 不注入，LLM 继续执行
```

**依赖闭包纯静态逻辑**：

```java
// 从 todos 的 meta_data.catalog_id 建 {catalog_id: uuid} anchors
static Map<String, String> buildAnchors(List<TodoItem> todos) {
    Map<String, String> anchors = new LinkedHashMap<>();
    for (TodoItem item : todos) {
        Map<String, Object> meta = item.getMetaData();
        if (meta != null && meta.containsKey("catalog_id")) {
            String catalogId = String.valueOf(meta.get("catalog_id"));
            anchors.put(catalogId, item.getId());
        }
    }
    return anchors;
}

// 从 anchors + todolist 还原每个 todo 应有的 depends_on（UUID 形式）
static Map<String, List<String>> resolveDependencyMap(
        Map<String, String> anchors, EdpaTodolist todolist) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : anchors.entrySet()) {
        String cid = e.getKey();
        String uuid = e.getValue();
        TodoEntry entry = todolist.findByCatalogId(cid);
        if (entry == null) continue;
        List<String> deps = entry.getDependsOn();
        if (deps.isEmpty()) {
            result.put(uuid, List.of());
            continue;
        }
        List<String> depUuids = new ArrayList<>();
        for (String dep : deps) {
            String depUuid = anchors.get(dep);
            if (depUuid == null) {
                throw new IllegalStateException("依赖 catalog_id 未找到: " + dep);
            }
            depUuids.add(depUuid);
        }
        result.put(uuid, depUuids);
    }
    return result;
}
```

---

## 三、使用场景举例：wealth-demo 理财购买

### 场景配置

**文件**：`scenarios/wealth-demo/governance/actrule.yaml`

配置了 4 个 catalog_id 和 2 条动态路径：

```yaml
todolist_entries:
  - catalog_id: product_recommend      # 推荐理财产品（起点，无依赖）
    content: "推荐理财产品"
    description: "根据用户需求推荐合适的理财产品"
    depends_on: []
    skill: "product_recommend_skill"

  - catalog_id: interact_finance_rec   # 交互式筛选（依赖 product_recommend）
    content: "交互式理财筛选"
    description: "用户从推荐结果中交互式筛选产品"
    depends_on: [product_recommend]
    skill: "interact_finance_rec_skill"

  - catalog_id: product_select          # 确定购买产品和金额（依赖 interact_finance_rec）
    content: "确定购买产品和金额"
    description: "用户确认最终购买的产品和金额"
    depends_on: [interact_finance_rec]
    skill: "product_select_skill"

  - catalog_id: fund_planning          # 查询余额并购买（依赖 product_select）
    content: "查询理财账户余额并购买"
    description: "查询余额，资金不足则筹划，最后购买理财产品"
    depends_on: [product_select]
    skill: "fund_planning_skill"

todolist_dynamic_paths:
  - path_id: shortcut_skip_filter
    description: "用户首次推荐即明确选择，跳过交互式筛选"
    trigger: "product_recommend 返回结果中用户已明确选择某个产品"
    skip_steps: [interact_finance_rec]
    redirect: "product_recommend → product_select"

  - path_id: skip_purchase_if_no_balance
    description: "账户余额为零，跳过购买步骤"
    trigger: "fund_planning 查询余额结果为 0"
    skip_steps: [fund_planning]
    redirect: "product_select → 结束（提示用户充值）"
```

### 执行流程

#### 用户输入

```
"帮我推荐一款理财产品，17元购买1号产品"
```

#### 第 1 轮（LLM 思考 → 规划）

**LLM 看到 prompt 中注入的 edpa_todo_summary**：

```
## 任务目录指引

### 可用 catalog_id

- **product_recommend**：推荐理财产品 — 根据用户需求推荐合适的理财产品
- **interact_finance_rec**：交互式理财筛选 — 用户从推荐结果中交互式筛选产品（depends_on: product_recommend）
- **product_select**：确定购买产品和金额 — 用户确认最终购买的产品和金额（depends_on: interact_finance_rec）
- **fund_planning**：查询理财账户余额并购买 — 查询余额，资金不足则筹划，最后购买理财产品（depends_on: product_select）
```

**LLM 决定调用 todo_create**：

```json
todo_create({
  tasks: [
    {catalog_id: "product_recommend"},
    {catalog_id: "interact_finance_rec"},
    {catalog_id: "product_select"},
    {catalog_id: "fund_planning"}
  ]
})
```

**beforeToolCall 拦截 — 参数增强**：

```
enrichTasks() 遍历每个 task，按 catalog_id 填充：

task 1: {catalog_id:"product_recommend"}
  → content: "推荐理财产品"
  → activeForm: "推荐理财产品"
  → description: "根据用户需求推荐合适的理财产品"
  → meta_data: {catalog_id:"product_recommend", skill:"product_recommend_skill"}

task 2: {catalog_id:"interact_finance_rec"}
  → content: "交互式理财筛选"
  → activeForm: "交互式理财筛选"
  → description: "用户从推荐结果中交互式筛选产品"
  → meta_data: {catalog_id:"interact_finance_rec", skill:"interact_finance_rec_skill"}

task 3, 4 同理...

max_subtasks 校验：4 < 50（框架上限），通过
```

**afterToolCall — 依赖闭包**：

```
buildAnchors(todos) → {
  "product_recommend":     "uuid-001",
  "interact_finance_rec":  "uuid-002",
  "product_select":        "uuid-003",
  "fund_planning":         "uuid-004"
}

resolveDependencyMap(anchors, todolist)：
  uuid-002 depends_on ["uuid-001"]    // interact_finance_rec → product_recommend
  uuid-003 depends_on ["uuid-002"]    // product_select → interact_finance_rec
  uuid-004 depends_on ["uuid-003"]    // fund_planning → product_select

applyDependencies(todos, depMap) → 写回 storage
todo.json 中 depends_on 全部是合法 UUID DAG
```

**落盘后的 todo.json**（简化）：

```json
[
  {
    "id": "uuid-001",
    "content": "推荐理财产品",
    "status": "PENDING",
    "depends_on": [],
    "meta_data": {"catalog_id": "product_recommend", "skill": "product_recommend_skill"}
  },
  {
    "id": "uuid-002",
    "content": "交互式理财筛选",
    "status": "PENDING",
    "depends_on": ["uuid-001"],
    "meta_data": {"catalog_id": "interact_finance_rec", "skill": "interact_finance_rec_skill"}
  },
  {
    "id": "uuid-003",
    "content": "确定购买产品和金额",
    "status": "PENDING",
    "depends_on": ["uuid-002"],
    "meta_data": {"catalog_id": "product_select", "skill": "product_select_skill"}
  },
  {
    "id": "uuid-004",
    "content": "查询理财账户余额并购买",
    "status": "PENDING",
    "depends_on": ["uuid-003"],
    "meta_data": {"catalog_id": "fund_planning", "skill": "fund_planning_skill"}
  }
]
```

#### 第 2 轮（LLM 执行第一个任务）

**LLM 调用 call_versatile**：

```json
call_versatile(query_intent="理财选品购买", params={...})
```

**beforeToolCall 守卫**：

```
enforcePlanBeforeBusinessTool(ctx, inputs, "call_versatile")
  → hasPlannedTodos(ctx) → true（当前会话有 4 个 todo）
  → 清理 PLAN_FIRST_BLOCK 标记
  → 放行

injectActiveTodoStatus(ctx)
  → 加载 todos
  → pushSteering("[当前任务状态] product_recommend=PENDING, interact_finance_rec=PENDING, 
                   product_select=PENDING, fund_planning=PENDING")
```

LLM 先调用 `todo_modify` 将 `product_recommend` 置为 IN_PROGRESS，再执行 `call_versatile`。

#### 第 3 轮（LLM 完成推荐，准备筛选）

```
LLM 调用 todo_modify（product_recommend → COMPLETED, interact_finance_rec → IN_PROGRESS）
```

**afterToolCall 检测**：

```
injectFinalAnswerDirective(ctx, sessionId, todos)
  → todos: product_recommend=COMPLETED, interact_finance_rec=IN_PROGRESS,
           product_select=PENDING, fund_planning=PENDING
  → 不是全部完成 → 不注入 final_answer 指令
```

#### 第 4 轮（动态路径命中）

**用户说**：

```
"不用筛选了，我就买1号产品17元"
```

**LLM 看到 prompt 中注入的 edpa_path_rules**：

```
## 动态路径选择规则

### 路径1：用户首次推荐即明确选择，跳过交互式筛选（shortcut_skip_filter）
- 触发条件：product_recommend 返回结果中用户已明确选择某个产品
- 操作：
  1. 调用 todo_modify 将 interact_finance_rec 对应的任务标记为 cancelled
  2. product_recommend → product_select，继续后续任务

### 路径2：账户余额为零，跳过购买步骤（skip_purchase_if_no_balance）
- 触发条件：fund_planning 查询余额结果为 0
- 操作：
  1. 调用 todo_modify 将 fund_planning 对应的任务标记为 cancelled
  2. product_select → 结束（提示用户充值）
```

**LLM 命中路径 1** → 调用 `todo_modify(interact_finance_rec → CANCELLED)`，然后继续执行 `product_select`。

#### 第 5 轮（最终完成）

```
LLM 调用 todo_modify：
  - product_select → COMPLETED
  - fund_planning → COMPLETED
```

**afterToolCall 检测**：

```
injectFinalAnswerDirective(ctx, sessionId, todos)
  → todos: product_recommend=COMPLETED, interact_finance_rec=CANCELLED,
           product_select=COMPLETED, fund_planning=COMPLETED
  → 全部 COMPLETED/CANCELLED → allCompleted=true
  → pushSteering("所有任务已完成。请直接输出最终回答（final_answer），
                  总结执行结果，不要再调用任何工具。")
```

LLM 输出 final_answer：

```
【需求概述】用户请求推荐理财产品并购买1号产品17元。
【规划过程】创建了4步任务列表：推荐→筛选→选品→购买。
【任务执行情况】推荐完成，用户明确选择跳过筛选，直接选定1号产品17元，
  余额查询通过并完成购买。
【结果汇总】已成功购买1号理财产品17元。
```

---

## 四、规划前置守卫的反例场景

### 用户输入

```
"帮我查一下账户余额"
```

### LLM 跳过规划直接调用业务工具（错误行为）

```json
call_versatile(query_intent="查询账户余额", params={...})
```

### beforeToolCall 守卫拦截

```
enforcePlanBeforeBusinessTool(ctx, inputs, "call_versatile")
  → hasPlannedTodos(ctx) → false（当前会话没有 todo）
  → _skip_tool = true（阻止真实执行）
  → KEY_PLAN_FIRST_BLOCK = true（标记为真 blocked）
  → 返回合成结果：
    {"error":"PLAN_FIRST",
     "message":"BLOCKED: 业务工具 call_versatile 被blocked。
      你必须先调用 todo_create 按 catalog_id 创建任务列表，
      规划完整执行步骤后，才能调用业务工具。
      请立即调用 todo_create，不要直接回答用户。"}
  → pushSteering("系统强制要求：执行任何业务工具前必须先用 todo_create...")
```

### LLM 下一轮被引导修正

```
LLM（被迫修正）：
  todo_create({ tasks: [{catalog_id:"product_recommend"}, ...] })
  → 然后再调用 call_versatile
```

---

## 五、max_subtasks 超限场景

### LLM 创建了 60 个任务（actrule.max_subtasks=50）

```json
todo_create({ tasks: [60个任务] })
```

### beforeToolCall 拦截

```
enrichAndValidateTasks()
  → countTasks(args.get("tasks")) = 60
  → 60 > 50（actrule.max_subtasks）
  → _skip_tool = true
  → 返回合成结果：
    {"error":"MAX_SUBTASKS_EXCEEDED",
     "message":"子任务数量 60 超过上限 50，请精简任务列表后重试。"}
```

---

## 六、动态状态注入机制

### injectActiveTodoStatus()

**代码位置**：`EdpaTodoRail.java#L1017-L1043`

```
每次工具调用前：
  ├── 从 TodoStorage 加载当前会话的 todo 列表
  ├── 签名去重：todoId:status 拼接，与上次签名比较
  │     → 状态没变化 → 不重复注入（避免 steering 队列膨胀）
  │     → 状态有变化 → 继续注入
  ├── buildTodoStatusSummary(todos)
  │     → "[当前任务状态] product_recommend=COMPLETED, 
  │        interact_finance_rec=IN_PROGRESS, ..."
  └── pushSteering(summary)
        → LLM 在下一轮思考中看到当前进度
```

**效果**：LLM 不会重复执行已完成的任务，也不会跳过未完成的任务。

---

## 七、与 DeepAgent 的集成关系

```
DeepAgent 实例
  ├── ReActAgent（核心推理引擎）
  │     ├── PromptBuilder
  │     │     ├── edpa_todo_summary（priority=88）    ← EdpaTodoRail.init() 注入
  │     │     ├── edpa_path_rules（priority=30）      ← EdpaTodoRail.init() 注入
  │     │     ├── edpa_scripts_cancel_rules           ← ScriptsRail 注入
  │     │     ├── edpa_scripts_business_rules         ← ScriptsRail 注入
  │     │     └── base_protocol                        ← PlanrulePromptBuilder 注入
  │     │
  │     └── 工具调用循环（ReAct loop）
  │           ├── beforeToolCall ← EdpaTodoRail（priority=95，最先执行）
  │           │     ├── injectActiveTodoStatus       → pushSteering（状态注入）
  │           │     ├── enforcePlanBeforeBusinessTool → 守卫拦截（PLAN_FIRST）
  │           │     ├── injectRealSessionId          → sessionId 隔离
  │           │     ├── normalizeTodoModifyArgs      → updates→todos 格式转换
  │           │     ├── enrichTasks                  → catalog_id 参数增强
  │           │     └── max_subtasks 校验            → 超限拦截
  │           │
  │           ├── Core TodoTool 执行（todo_create/todo_modify）
  │           │     （EdpaTodoRail 增强后的参数传入 Core 执行）
  │           │
  │           └── afterToolCall ← EdpaTodoRail
  │                 ├── dependency closure           → catalog_id→UUID 依赖映射
  │                 └── injectFinalAnswerDirective   → 全部完成检测
  │
  └── TodoStorage（KvTodoStorage/FileTodoStorage）
        └── .todo/{sessionId}/todo.json
              ├── 任务列表（含 meta_data.catalog_id 锚点）
              └── depends_on（UUID 形式，由 afterToolCall 还原）
```

### Rail 执行顺序

| 优先级 | Rail | 职责 |
|--------|------|------|
| 95 | **EdpaTodoRail** | 参数增强 + 守卫 + 依赖闭包 |
| 90 | TaskPlanningRail | Core 的 Todo 工具执行 |
| 88 | edpa_todo_summary | catalog_id 目录 prompt |
| 70 | ExecutionLimitRail | tool_limits 超限拦截 |
| 40 | edpa_scripts_business_rules | 业务话术规则 |
| 30 | edpa_path_rules | 动态路径规则 prompt |
| 30 | edpa_scripts_cancel_rules | 取消规则 |

---

## 八、关键设计总结

| 设计点 | 解决的问题 | 实现方式 |
|--------|-----------|----------|
| **catalog_id 参数增强** | LLM 不需要手写 content/description | beforeToolCall 按 catalog_id 从 todolist 填充 |
| **依赖闭包** | catalog_id 是字符串，depends_on 需要 UUID | afterToolCall 用 anchors 映射替换 |
| **规划前置守卫** | LLM 跳过规划直接调工具 | beforeToolCall 拦截 call_mcp/call_versatile，返回 PLAN_FIRST |
| **动态状态注入** | LLM 不知道当前任务进度 | beforeToolCall 从 storage 加载，pushSteering |
| **签名去重** | 状态没变化时重复注入导致 steering 膨胀 | todoId:status 签名比较，没变化则跳过 |
| **动态路径选择** | 用户行为变化需跳过某些任务 | init() 注入路径规则 prompt，LLM 按 prompt 主动跳过 |
| **max_subtasks 校验** | LLM 创建过多任务 | beforeToolCall 检查数量，超限阻止 |
| **final_answer 引导** | LLM 完成任务后不知道该结束 | afterToolCall 检测全部完成，pushSteering |
| **sessionId 隔离** | 所有会话共用 .todo/default/ | beforeToolCall 注入转义后的真实 sessionId |
| **参数格式兼容** | LLM 有时用 updates[].task_id | normalizeTodoModifyArgs 自动转换为 todos[].id |

---

## 九、关键代码文件索引

| 文件 | 职责 |
|------|------|
| [EdpaTodoRail.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/rail/EdpaTodoRail.java) | Todo 增强 Rail（参数增强 + 守卫 + 依赖闭包） |
| [EdpaTodolist.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/config/EdpaTodolist.java) | catalog_id 配置模型（entries + dynamicPaths） |
| [ActRuleConfig.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/config/ActRuleConfig.java) | actrule.yaml 配置模型（含 todolist_entries） |
| [TodoSessionResolver.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/enhancer/TodoSessionResolver.java) | sessionId 转义工具 |
| [EdpaAgentEnhancer.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/enhancer/EdpaAgentEnhancer.java) | Rail 注册入口（buildRails） |
| [EdpaExtHandler.java](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/handler/EdpaExtHandler.java) | 初始化入口（loadTodoDataLayer） |
| [wealth-demo/actrule.yaml](file:///d:/work/code-proj/openjiuwen-fin/deep-agent-demo/agent-solution/common/agents/edp-agent-java/scenarios/wealth-demo/governance/actrule.yaml) | 理财场景配置（4 catalog_id + 2 动态路径） |

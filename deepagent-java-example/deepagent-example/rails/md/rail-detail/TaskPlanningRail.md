# TaskPlanningRail 详细解读

## 一、核心定位

TaskPlanningRail 是 DeepAgent Harness 层的**任务规划与进度管理 Rail**，为 Agent 提供 todo 工具（创建/修改/查询/列表）和模型路由能力，使 Agent 能够**自动拆解复杂任务、跟踪执行进度、按步骤推进、并在多模型间动态路由**。

| 属性 | 值 |
|------|---|
| 继承 | DeepAgentRail → AgentRail，实现 TaskIterationRail |
| 优先级 | 90 |
| 配置名 | `task_planning` |
| 核心能力 | todo 工具注册 + 规划提示注入 + 模型路由 + 进度重复提醒 + 任务快照持久化 |

---

## 二、类结构

```
TaskPlanningRail extends DeepAgentRail implements TaskIterationRail
  │
  ├── 构造参数
  │     isProgressRepeatEnabled: boolean    ← 是否启用进度重复提醒（默认 false）
  │     listToolCallInterval: int           ← 每隔多少次工具调用提醒一次（默认 20）
  │     modelSelection: Map<String, String> ← 模型选择映射 {modelId: description}
  │
  ├── 实例字段
  │     owner: DeepAgent                    ← 所属 DeepAgent
  │     todoTool: TodoTool                  ← todo 工具操作对象
  │     tools: List<Tool>                   ← 注册的 4 个工具
  │     toolCallCounts: Map<String, Integer>← 每会话工具调用计数
  │     todosCache: Map<String, List<TodoItem>> ← todo 缓存
  │     usageRecords: Map<String, ModelUsageRecord> ← 模型用量记录
  │     defaultLlm: Model                   ← 默认 LLM（首次捕获）
  │
  ├── Prompt Section
  │     "todo" (priority=90)               ← 规划提示 + 模型选择策略
  │
  └── 生命周期钩子
        init()                → 注册 4 个 todo 工具
        uninit()              → 注销工具 + 移除提示 + 清空状态
        beforeModelCall()     → 注入规划提示 + 模型路由
        afterModelCall()      → 记录模型用量
        afterToolCall()       → 刷新 todo 缓存 + 进度重复提醒
        afterInvoke()         → 清理会话状态
        afterTaskIteration()  → 同步 TaskPlan + 写快照
```

---

## 三、TaskIterationRail 接口

```java
public interface TaskIterationRail {
    default void afterTaskIteration(TaskIterationContext ctx) {}
}
```

这是 DeepAgent **外循环专用**的回调接口，在外循环每轮迭代结束后被调用。TaskPlanningRail 实现此接口，用于在外循环迭代结束后同步任务计划状态并持久化快照。

---

## 四、四大核心能力

### 4.1 Todo 工具注册

init() 中注册 4 个工具到 Agent：

| 工具名 | 功能 | 关键参数 |
|--------|------|---------|
| `todo_create` | 创建任务列表（整体替换） | `session_id`, `tasks`（任务数组） |
| `todo_list` | 列出未完成的活跃任务 | `session_id` |
| `todo_get` | 获取指定任务详情 | `session_id`, `id` |
| `todo_modify` | 修改任务（支持多种 action） | `session_id`, `action`, `updates` 等 |

**todo_modify 支持的 action**：

| action | 功能 | 参数 |
|--------|------|------|
| `update` | 批量更新任务字段 | `todos: [{task_id, status, content, ...}]` |
| `append` | 追加新任务 | `todos: [{content, activeForm, description, ...}]` |
| `insert_after` | 在指定任务后插入 | `todo_data: {target_id, items}` |
| `insert_before` | 在指定任务前插入 | `todo_data: {target_id, items}` |
| `delete` | 删除任务 | `ids: [taskId1, taskId2]` |
| `cancel` | 取消任务 | `ids: [taskId1, taskId2]` |

### 4.2 规划提示注入

beforeModelCall() 中通过 `injectTodoPrompt()` 注入规划指导提示到 System Prompt：

**中文提示核心内容**：

```
使用 todo 工具（todo_create、todo_modify、todo_list）拆解和管理工作。

**何时创建任务列表 — 以下情况立即调用 todo_create：**
- 用户明确要求使用待办清单，或提供了多个待完成事项
- 任务需要 3 个或更多步骤
- 任务具有规划性质（多步骤实现、功能开发等）

**识别到规划需求后，在开始执行前立即调用 todo_create。**

**任务管理规则：**
- 实时更新状态：任务状态变化时立即调用 todo_modify
- 同一时间只能有一个任务处于 in_progress，完成后再开始下一个
- 批量更新：将多个状态变更合并为一次 todo_modify 调用
- 不再需要的任务用 todo_modify 标记为 cancelled
- 可通过调用 todo_list 了解当前任务规划进展

**将任务标记为已完成前：**
- 必须仔细验证工作已全部完成（如运行测试用例）
- 以下情况绝对不能标记为已完成：部分实现、测试失败、存在未解决的错误等
- 标记完成后，检查实现过程中是否发现新的后续任务，及时通过 todo_modify 追加
```

**注入方式**：双重注入——既通过 `PromptBuilder.addSection()` 添加到 System Prompt，又通过 `injectTodoMessage()` 在 messages 列表头部插入 SystemMessage（防止 PromptBuilder 被覆盖时丢失）。

### 4.3 模型路由

当配置了 `modelSelection` 映射时，TaskPlanningRail 实现**按任务动态切换 LLM 模型**：

```java
// beforeModelCall() 中的模型路由逻辑
TodoItem inProgress = loadTodos(sessionId).stream()
    .filter(item -> item.getStatus() == TodoStatus.IN_PROGRESS)
    .findFirst().orElse(null);

String modelId = inProgress != null ? inProgress.getSelectedModelId() : null;

if (modelId != null && modelSelection.containsKey(modelId)) {
    Model resolvedModel = Runner.resourceMgr().getModel(modelId);
    reactAgent.setLlm(resolvedModel);  // 动态切换模型
}
```

**路由流程**：

```
1. 读取当前 in_progress 的 TodoItem
2. 获取其 selectedModelId 字段
3. 如果 modelSelection 包含该 modelId → 从资源管理器获取模型实例
4. 调用 reactAgent.setLlm(resolvedModel) 动态替换当前模型
5. 如果没有 selectedModelId → 恢复默认模型
```

**模型选择策略提示**（注入到 System Prompt）：

```
## 模型选择策略

当前可用模型：
 - selected_model_id: qwen-turbo: 适合简单任务，成本低，速度快
 - selected_model_id: qwen-max: 适合复杂任务，推理能力强，效果好

### 选择原则
创建子任务时，阅读每个模型的描述，根据任务复杂度为 selected_model_id 字段选择合适的模型 ID：
- 描述中标注适合简单任务、成本低、速度快等的模型，用于翻译、摘要、格式转换等无需深度推理的任务
- 描述中标注适合复杂任务、推理能力强、效果好等的模型，用于代码生成、逻辑分析、策略规划等任务
- 不填则使用 Agent 默认模型

### 执行质量保障
若某个子任务执行结果质量不佳，应通过 todo_modify 工具将该任务的 selected_model_id 修改为描述更强的模型 ID，然后重新执行该任务。
```

**afterModelCall()** 记录每个模型的 token 用量：

```java
usageRecords.computeIfAbsent(modelId, id -> ModelUsageRecord.builder().modelId(id).build())
    .add(usage);
```

### 4.4 进度重复提醒

当 `isProgressRepeatEnabled=true` 时，每隔 `listToolCallInterval` 次工具调用，自动向上下文注入进度提醒：

```java
// afterToolCall() 中
int count = toolCallCounts.getOrDefault(sessionId, 0) + 1;
if (count % listToolCallInterval == 0) {
    List<TodoItem> todos = loadTodos(sessionId);
    ctx.getContext().addMessages(new UserMessage(buildProgressReminder(todos)));
}
```

**buildProgressReminder() 生成的提醒**：

```
以下是当前任务规划中所有任务的内容和状态：

id: task1 |status: COMPLETED |content: 搜索对公贷款政策
id: task2 |status: IN_PROGRESS |content: 生成融资方案
id: task3 |status: PENDING |content: 格式化输出

正在执行的任务为：

生成融资方案

请查看上述任务进度，确保计划正在正确执行。如果有任务卡住或需要调整，请及时更新
```

---

## 五、afterTaskIteration — 任务快照持久化

在外循环每轮迭代结束后，TaskPlanningRail 执行两个关键操作：

### 5.1 同步 TaskPlan → TodoItem

```java
public void afterTaskIteration(TaskIterationContext ctx) {
    // 1. 从迭代结果中解析 TaskPlan
    TaskPlan plan = resolveTaskPlan(ctx);
    
    // 2. 将 TaskPlan 的任务状态同步到 TodoItem
    syncTodosFromTaskPlan(sessionId, plan);
    
    // 3. 写入快照文件
    List<TodoItem> todos = loadTodos(sessionId);
    writeTaskPlanSnapshot(TaskPlanSnapshot.from(ctx, todos));
}
```

**syncTodosFromTaskPlan()** 的同步逻辑：

```
遍历 TaskPlan.tasks：
  → 按 id 匹配 TodoItem
  → 如果 TaskPlan 中状态变了 → 更新 TodoItem 状态
  → 如果 TaskPlan 中有 resultSummary → 更新 TodoItem.resultSummary
  → 有变更则保存到文件
```

### 5.2 TaskPlanSnapshot 持久化

快照保存到 `{workspace}/.task_plan/{sessionId}.json`，包含：

```json
{
  "session_id": "sess_123",
  "task_id": "task_456",
  "round": 3,
  "follow_up": false,
  "updated_at": "2026-06-25T10:30:00Z",
  "todos": [
    {"id": "t1", "content": "搜索政策", "status": "COMPLETED"},
    {"id": "t2", "content": "生成方案", "status": "IN_PROGRESS"}
  ],
  "result": { "task_plan": {} },
  "usage_metadata": { "prompt_tokens": 1000, "completion_tokens": 500 },
  "token_usage": 1500
}
```

---

## 六、TodoItem 数据模型

```java
public class TodoItem {
    String id;                  // 唯一标识（UUID）
    String content;             // 任务内容
    String activeForm;          // 进行中描述（如"正在搜索政策"）
    String description;         // 详细描述
    TodoStatus status;          // 状态：PENDING/TODO/IN_PROGRESS/COMPLETED/DONE/CANCELLED
    List<String> dependsOn;     // 依赖的任务 ID 列表
    String resultSummary;       // 结果摘要
    Map<String, Object> metaData; // 扩展元数据
    String selectedModelId;     // 指定执行的模型 ID
    String priority;            // 优先级：low/medium/high
}
```

**TodoStatus 状态机**：

```
PENDING/TODO ──→ IN_PROGRESS ──→ COMPLETED/DONE
                    │
                    └──→ CANCELLED
```

**关键约束**：同一时间**最多只能有一个**任务处于 `IN_PROGRESS` 状态（`validateSingleInProgress()` 校验）。

---

## 七、TaskPlan 数据模型

```java
public class TaskPlan {
    String goal;                // 总目标
    List<TodoItem> tasks;       // 任务列表
    String currentTaskId;       // 当前执行的任务 ID
}
```

**核心方法**：

| 方法 | 功能 |
|------|------|
| `getNextTask()` | 获取下一个可执行任务（依赖已全部完成） |
| `markInProgress(taskId)` | 标记任务进行中 |
| `markCompleted(taskId, summary)` | 标记任务完成并记录摘要 |
| `markCancelled(taskId, reason)` | 取消任务并记录原因 |
| `getProgressSummary()` | 获取进度摘要（如 "2/5 completed"） |
| `toMarkdown()` | 生成 Markdown 格式进度报告 |

---

## 八、配置方式

### 8.1 YAML 配置

```yaml
rails:
  task_planning:
    enable_progress_repeat: true    # 启用进度重复提醒
    list_tool_call_interval: 20     # 每 20 次工具调用提醒一次
    model_selection:                # 模型选择映射
      qwen-turbo: "适合简单任务，成本低，速度快"
      qwen-max: "适合复杂任务，推理能力强，效果好"
      deepseek-v3: "适合代码生成，逻辑分析"
```

### 8.2 代码配置

```java
// 基础配置（无模型路由）
TaskPlanningRail rail = new TaskPlanningRail();
deepAgent.registerRail(rail);

// 启用进度提醒
TaskPlanningRail rail = new TaskPlanningRail(true, 15);

// 完整配置（进度提醒 + 模型路由）
Map<String, String> modelSelection = new LinkedHashMap<>();
modelSelection.put("qwen-turbo", "适合简单任务，成本低，速度快");
modelSelection.put("qwen-max", "适合复杂任务，推理能力强，效果好");
TaskPlanningRail rail = new TaskPlanningRail(true, 20, modelSelection);
deepAgent.registerRail(rail);
```

### 8.3 HarnessConfigBuilder 自动创建

```java
private static TaskPlanningRail createTaskPlanningRail(Path root, RailResourceSchema spec) {
    Map<String, Object> config = railConfig(spec);
    return new TaskPlanningRail(
        booleanValue(config.get("enable_progress_repeat"), false),
        intValue(config.get("list_tool_call_interval"), 20),
        stringMap(config.get("model_selection"))
    );
}
```

---

## 九、完整执行流程示例

### 场景：用户请求"拜访华为科技生成营销方案，并出一份融资方案"

```
┌─ init() ──────────────────────────────────────────────────────┐
│  注册 4 个 todo 工具到 Agent                                    │
│  todoTool = new TodoTool(workspace/.todo)                      │
└──────────────────────────────────────────────────────────────┘
                          ↓
┌─ beforeModelCall() [第1次] ───────────────────────────────────┐
│  1. injectTodoPrompt() → 注入规划指导提示到 System Prompt        │
│     "使用 todo 工具拆解和管理工作...何时创建任务列表..."            │
│  2. 模型路由 → 无 in_progress 任务，使用默认模型                  │
└──────────────────────────────────────────────────────────────┘
                          ↓
┌─ LLM 调用 ───────────────────────────────────────────────────┐
│  LLM 看到规划提示，判断需要拆解任务                                │
│  → 调用 todo_create(tasks=[                                   │
│       {content: "搜索华为科技信息", status: IN_PROGRESS},        │
│       {content: "生成营销方案", status: PENDING},                │
│       {content: "搜索对公贷款政策", status: PENDING},             │
│       {content: "生成融资方案", status: PENDING}                 │
│     ])                                                         │
└──────────────────────────────────────────────────────────────┘
                          ↓
┌─ afterToolCall() ─────────────────────────────────────────────┐
│  1. 刷新 todosCache                                            │
│  2. 进度提醒计数 +1（未达间隔，不提醒）                            │
└──────────────────────────────────────────────────────────────┘
                          ↓
┌─ beforeModelCall() [第2次] ───────────────────────────────────┐
│  1. injectTodoPrompt() → 再次注入规划提示                        │
│  2. 模型路由 → 读取 in_progress 任务 "搜索华为科技信息"            │
│     → selectedModelId=null → 使用默认模型                       │
└──────────────────────────────────────────────────────────────┘
                          ↓
┌─ LLM 调用 ───────────────────────────────────────────────────┐
│  → 调用 search_tool("华为科技")                                 │
│  → 调用 todo_modify(action=update, todos=[                     │
│       {task_id: "t1", status: COMPLETED},                      │
│       {task_id: "t2", status: IN_PROGRESS}                     │
│     ])                                                         │
└──────────────────────────────────────────────────────────────┘
                          ↓
┌─ beforeModelCall() [第3次] ───────────────────────────────────┐
│  模型路由 → in_progress="生成营销方案"                            │
│  → selectedModelId="qwen-max" → 切换到 qwen-max 模型           │
└──────────────────────────────────────────────────────────────┘
                          ↓
┌─ LLM 调用 [qwen-max] ───────────────────────────────────────┐
│  → 生成营销方案...                                              │
│  → todo_modify(t2 → COMPLETED, t3 → IN_PROGRESS)              │
└──────────────────────────────────────────────────────────────┘
                          ↓
          ... 继续执行 t3, t4 ...
                          ↓
┌─ afterInvoke() ──────────────────────────────────────────────┐
│  清理 toolCallCounts、todosCache、usageRecords                   │
│  恢复 defaultLlm                                               │
└──────────────────────────────────────────────────────────────┘
                          ↓
┌─ afterTaskIteration() ───────────────────────────────────────┐
│  1. 解析 TaskPlan（从迭代结果或输入中）                            │
│  2. syncTodosFromTaskPlan() → 同步状态到 TodoItem               │
│  3. writeTaskPlanSnapshot() → 持久化到 .task_plan/sess.json     │
└──────────────────────────────────────────────────────────────┘
```

---

## 十、使用场景

### 场景 1：多步骤任务自动拆解

```
用户："帮我完成一个完整的用户注册功能，包括前端表单、后端接口、数据库表和单元测试"

Agent 自动拆解：
  todo_create([
    {content: "设计数据库表结构", status: IN_PROGRESS},
    {content: "实现后端注册接口", status: PENDING, depends_on: ["t1"]},
    {content: "实现前端注册表单", status: PENDING, depends_on: ["t2"]},
    {content: "编写单元测试", status: PENDING, depends_on: ["t2"]},
    {content: "端到端验证", status: PENDING, depends_on: ["t3", "t4"]}
  ])
```

### 场景 2：模型路由 — 简单任务用快模型，复杂任务用强模型

```
配置：
  model_selection:
    qwen-turbo: "适合翻译、摘要等简单任务"
    qwen-max: "适合代码生成、逻辑推理等复杂任务"

Agent 拆解后：
  todo_create([
    {content: "翻译需求文档", selected_model_id: "qwen-turbo"},
    {content: "生成核心代码", selected_model_id: "qwen-max"},
    {content: "生成API文档", selected_model_id: "qwen-turbo"}
  ])

执行时：
  → 翻译需求文档：使用 qwen-turbo（快、便宜）
  → 生成核心代码：使用 qwen-max（强、精准）
  → 生成API文档：使用 qwen-turbo（快、便宜）
```

### 场景 3：进度重复提醒 — 长任务防偏航

```
配置：enable_progress_repeat=true, list_tool_call_interval=20

执行过程中，每 20 次工具调用后自动注入：

  "以下是当前任务规划中所有任务的内容和状态：
   id: t1 |status: COMPLETED |content: 搜索政策
   id: t2 |status: IN_PROGRESS |content: 生成方案
   id: t3 |status: PENDING |content: 格式化输出
   正在执行的任务为：生成方案
   请查看上述任务进度，确保计划正在正确执行。"

→ LLM 自我检查进度，防止遗漏或偏航
```

### 场景 4：质量保障 — 低质量结果升级模型重做

```
执行流程：
  1. 用 qwen-turbo 生成代码 → 质量不佳
  2. LLM 判断结果不好 → todo_modify(t2, selected_model_id="qwen-max")
  3. 下一轮 beforeModelCall → 切换到 qwen-max
  4. 用 qwen-max 重新生成 → 质量提升
  5. 标记完成，继续后续任务
```

### 场景 5：任务快照持久化 — 断点续执行

```
外循环第 1 轮执行后：
  → afterTaskIteration() → 写入 .task_plan/sess_123.json
  → 记录：round=1, todos=[t1:COMPLETED, t2:IN_PROGRESS, t3:PENDING]

如果会话中断，下次恢复时：
  → loadTaskPlanSnapshot("sess_123")
  → 获取到 t1 已完成、t2 进行中
  → 从 t2 继续执行，无需从头开始
```

### 场景 6：动态追加任务

```
执行过程中发现新需求：
  → todo_modify(action=append, todos=[
       {content: "添加权限校验", activeForm: "正在添加权限校验", description: "..."}
     ])
  → 新任务追加到列表末尾，Agent 继续执行
```

---

## 十一、设计要点总结

| 设计要点 | 实现方式 |
|---------|---------|
| **自动规划引导** | beforeModelCall 注入规划提示，指导 LLM 何时/如何使用 todo 工具 |
| **双重注入保障** | PromptBuilder.addSection() + messages 头部插入 SystemMessage，防止提示丢失 |
| **模型动态路由** | 读取 in_progress 任务的 selectedModelId → reactAgent.setLlm() 动态切换 |
| **模型用量追踪** | afterModelCall 记录每个模型的 UsageMetadata，用于成本分析 |
| **进度防偏航** | 每隔 N 次工具调用注入进度提醒，LLM 自检任务执行状态 |
| **单任务聚焦** | validateSingleInProgress() 确保同一时间只有一个 IN_PROGRESS 任务 |
| **任务依赖** | TodoItem.dependsOn 支持任务间依赖，getNextTask() 按依赖拓扑排序 |
| **外循环同步** | afterTaskIteration 将 TaskPlan 状态同步到 TodoItem，保持一致性 |
| **快照持久化** | TaskPlanSnapshot 保存到文件，支持断点续执行和进度回溯 |
| **质量保障闭环** | 模型选择策略提示 + 低质量结果升级模型重做，形成质量保障闭环 |
| **可配置** | 三个构造参数控制行为，YAML/代码/HarnessConfigBuilder 三种配置方式 |

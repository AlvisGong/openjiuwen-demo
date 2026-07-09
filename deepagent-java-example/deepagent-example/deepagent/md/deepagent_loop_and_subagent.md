# DeepAgent 循环机制与子 Agent 委派详解

> 基于 `agent-core-java` 源码分析,澄清 DeepAgent 的循环架构、todo list 的真实作用、以及同步/异步子 Agent 委派的差异。
> 关联样例:[FiveBanksSearchExample.java](../../examples/deepagent-example/deepagent/FiveBanksSearchExample.java)

---

## 一、DeepAgent 是"双层循环"吗?

### 结论:默认配置下**不是**

很多人误以为 DeepAgent 是「外层 todo list 循环 + 内层完成单个 todo 的 ReAct 循环」。实际上默认配置 `enableTaskLoop(false)` 下,**只有一个 ReAct 循环**,todo list 只是状态数据,不是循环结构。

### 真实的执行流程(单循环)

查看 [ReActAgent.java](../../src/main/java/com/openjiuwen/core/singleagent/agents/ReActAgent.java) 的主循环:

```java
for (int iteration = startIteration; iteration + 1 < config.getMaxIterations(); iteration++) {
    // 1. 调用大模型思考
    AssistantMessage aiMessage = callModel(ctx, context, systemMessages, tools);
    // 2. 如果有工具调用,执行工具(todo_create/web_free_search/fetch_webpage 等)
    if (aiMessage.getToolCalls() != null && !aiMessage.getToolCalls().isEmpty()) {
        List<ToolExecutionEntry> results = executeToolCallEntries(...);
    } else {
        // 3. 没有工具调用 → 返回最终答案,循环结束
        return result;
    }
}
```

执行示意:

```
┌─────────────────────────────────────────────────────────────┐
│   单层 ReAct 循环(maxIterations=30)                        │
│                                                             │
│   iter 1: 思考 → 调用 todo_create(规划5家银行)             │
│   iter 2: 思考 → 调用 todo_modify(标记工行 in_progress)    │
│   iter 3: 思考 → 调用 web_free_search("工商银行 Q1 财报")  │
│   iter 4: 思考 → 调用 fetch_webpage(url)                   │
│   iter 5: 思考 → 调用 todo_modify(标记工行 completed)      │
│   iter 6: 思考 → 调用 todo_modify(标记农行 in_progress)    │
│   ...                                                       │
│   iter 30: 输出最终 Markdown 报告                           │
└─────────────────────────────────────────────────────────────┘
```

### TaskPlanningRail 的真实作用

[TaskPlanningRail.java](../../src/main/java/com/openjiuwen/harness/rails/TaskPlanningRail.java) 中 `beforeModelCall` 做的是:

1. **注入 todo 工具**:`todo_create` / `todo_list` / `todo_modify`
2. **注入提示词**:告诉大模型"何时创建任务列表"、"任务管理规则"
3. **每次模型调用前**注入当前 todo 进度:

```
以下是当前任务规划中所有任务的内容和状态：
[ ] 工商银行 Q1 财报分析
[x] 农业银行 Q1 财报分析  ← 已完成
[ ] 中国银行 Q1 财报分析
正在执行的任务为：工商银行 Q1 财报分析
```

`isProgressRepeatEnabled=true, listToolCallInterval=2` 的作用 — 每隔 2 次工具调用,强制把 todo 进度塞进上下文,让模型"看到"自己进行到哪一步了。

### 真正的"外层循环 + 内层 ReAct"需要启用 TaskLoop

需要 `enableTaskLoop(true)`,会注入 [TaskCompletionRail](../../src/main/java/com/openjiuwen/harness/rails/TaskCompletionRail.java) + [TaskLoopController](../../src/main/java/com/openjiuwen/harness/task_loop/TaskLoopController.java):

```
┌─ 外层 TaskLoopController round 循环 ────────────────────────┐
│                                                            │
│  Round 1 (task1: 工行财报)                                 │
│  ├─ TaskCompletionRail 注入完成信号 "<promise>...</promise>"│
│  ├─ prepareRound() → 开始新一轮                             │
│  │  ┌─ 内层 ReAct 循环 ────────────────────────────────┐  │
│  │  │  iter 1: search → iter 2: fetch → ...           │  │
│  │  │  iter N: 输出 <promise>task1完成</promise>       │  │
│  │  └─────────────────────────────────────────────────┘  │
│  ├─ promiseMatches() 检测到完成信号                        │
│  └─ resolveCompletion() 结束 Round 1                       │
│                                                            │
│  Round 2 (task2: 农行财报)  ← 框架自动切换到下一个 task     │
│  └─ ...                                                    │
└────────────────────────────────────────────────────────────┘
```

这里 Round 才是真正的外层循环,每个 Round 对应一个 task,内层是独立的 ReAct 循环。

---

## 二、todo item 不是 TaskLoop 中的 task

这是两套独立的机制,容易混淆:

| 概念 | 默认配置(TaskPlanning) | TaskLoop 模式 |
|------|---------------------|---------------|
| 配置 | `enableTaskPlanning(true)`<br>`enableTaskLoop(false)` | `enableTaskLoop(true)` |
| 外层循环 | **无** | Round 循环(TaskLoopController) |
| 内层循环 | 单一 ReAct 循环 | ReAct 循环(每 Round 一个) |
| todo item | **仅记事本** | 可关联到 Round task |
| 框架强制顺序 | 否(模型自主) | 是(Round 串行) |
| 完成判定 | 模型自主停止 | `<promise>` 信号 |

### 一个典型误区

用户看到的 6 个 todo item 都是 PENDING 状态,`result_summary` 写着"搜索工具不可用" — 说明模型**只在记事本上规划了**,但实际调用 `web_free_search` 失败了,框架并**没有自动重试或调度下一个 todo**,因为根本没有调度器。

**todo list 只是"记事本",不是"任务调度器"**。真正的"外层 Round + 内层 ReAct"双循环需要 `enableTaskLoop(true)`,会通过 `<promise>` 完成信号驱动框架自动切换 task。

---

## 三、子 Agent 委派:同步 TaskTool vs 异步 SessionSpawn

这是**两种不同的子 Agent 调用模式**,不是同一个东西的两个名字。

### 配置开关(互斥二选一)

查看 [HarnessFactory.java#L113-L120](../../src/main/java/com/openjiuwen/harness/factory/HarnessFactory.java):

```java
if (!subagents.isEmpty()) {
    if (source.isEnableAsyncSubagent()) {
        addDefaultRailIfAbsent(rails, SessionRail.class, SessionRail::new);      // 异步
    } else {
        addDefaultRailIfAbsent(rails, SubagentRail.class, SubagentRail::new);   // 同步
    }
}
```

- `enableAsyncSubagent=false` → 注入 **SubagentRail** (同步)
- `enableAsyncSubagent=true` → 注入 **SessionRail** (异步)

---

### 模式一:同步 SubagentRail(默认)

#### 注入的工具: 1 个 `task_tool`

查看 [SubagentRail.java](../../src/main/java/com/openjiuwen/harness/rails/SubagentRail.java):

```java
TaskTool taskTool = new TaskTool(deepAgent);
Tool tool = new LocalFunction(card("task_tool", ...),
    inputs -> taskTool.delegate(
        stringValue(inputs.get("subagent_type")),       // 子Agent类型
        stringValue(inputs.get("task_description")),    // 任务描述
        stringValue(inputs.get("parent_session_id"))    // 父session
    ));
```

#### 执行方式:阻塞调用

查看 [TaskTool.java](../../src/main/java/com/openjiuwen/harness/tools/TaskTool.java):

```java
public ToolOutput delegate(String subagentType, String taskDescription, String parentSessionId) {
    DeepAgent subagent = parentAgent.createSubagent(subagentType, subSessionId);
    Map<String, Object> result = subagent.invoke(...);   // ← 阻塞,等子Agent跑完
    ...
}
```

#### 流程示意

```
主Agent ReAct循环
  ├─ iter 1: 调用 task_tool(subagent_type="research", task="搜索工行财报")
  │           ↓ 阻塞等待
  │           ┌─ 子Agent ReAct循环 ──────────────┐
  │           │  search → fetch → 返回结果       │
  │           └──────────────────────────────────┘
  │           ↓ 子Agent完成,结果返回
  ├─ iter 2: 拿到结果,继续下一步
  └─ ...
```

**特点**: 主 Agent 在子 Agent 执行期间**完全阻塞**,等子 Agent 跑完拿到结果才继续。简单直观,但无法并行。

---

### 模式二:异步 SessionRail

#### 注入的工具: 3 个

查看 [SessionRail.java](../../src/main/java/com/openjiuwen/harness/rails/SessionRail.java):

```java
tools.add(new LocalFunction(card("sessions_list"),    inputs -> listTool.list()));        // 列出所有会话
tools.add(new LocalFunction(card("sessions_cancel"),  inputs -> cancelTool.cancel(...))); // 取消会话
tools.add(new LocalFunction(card("sessions_spawn"),   inputs -> spawn(taskTool, inputs))); // 派发任务
```

#### 执行方式:非阻塞派发 + 后台执行

查看 [SessionRail.java#L105-L123](../../src/main/java/com/openjiuwen/harness/rails/SessionRail.java):

```java
private ToolOutput spawn(TaskTool taskTool, Map<String, Object> inputs) {
    String taskId = UUID.randomUUID().toString();   // 生成任务ID
    ToolOutput output = taskTool.delegate(...);      // 还是调用delegate,但...
    toolkit.upsertRunning(taskId, subSessionId, description);  // 注册到运行中
    if (output.isSuccess()) {
        toolkit.markCompleted(taskId, ...);          // 标记完成
    }
    return ToolOutput.builder()
        .data(Map.of("task_id", taskId, ...))        // 立即返回task_id
        .build();
}
```

#### 流程示意

```
主Agent ReAct循环
  ├─ iter 1: 调用 sessions_spawn("搜索工行财报")
  │           ↓ 立即返回 task_id="abc-123"
  ├─ iter 2: 调用 sessions_spawn("搜索农行财报")   ← 不用等上一个完成
  │           ↓ 立即返回 task_id="def-456"
  ├─ iter 3: 调用 sessions_spawn("搜索中行财报")
  │           ↓ 立即返回 task_id="ghi-789"
  ├─ iter 4: 调用 sessions_list() 查看进度
  │           ↓ 返回 [{task_id:"abc-123", status:"completed"}, ...]
  └─ iter 5: 所有任务完成,汇总结果
```

**特点**: 主 Agent **不阻塞**,派发任务后立即拿到 `task_id` 继续。可通过 `sessions_list` 轮询进度,通过 `sessions_cancel` 取消任务。

---

### 关键区别对比

| 维度 | 同步 SubagentRail | 异步 SessionRail |
|------|-------------------|------------------|
| 配置 | `enableAsyncSubagent=false` | `enableAsyncSubagent=true` |
| 注入工具 | `task_tool` | `sessions_spawn` / `sessions_list` / `sessions_cancel` |
| 调用方式 | **阻塞** (`delegate` 同步返回结果) | **非阻塞** (`spawn` 立即返回 task_id) |
| 主 Agent 行为 | 等子 Agent 跑完才继续 | 派发后立即继续 |
| 并行能力 | **无** (串行执行) | **有** (可同时派发多个) |
| 进度查询 | 不需要(结果直接返回) | `sessions_list` 查询 |
| 任务取消 | 不支持 | `sessions_cancel` 支持 |
| 实现复杂度 | 简单 | 复杂(需管理会话状态) |
| 适合场景 | 强依赖前置结果 | 可并行独立任务 |

---

## 四、三种机制横向对比

| 概念 | 默认 TaskPlanning | TaskLoop 模式 | 子 Agent 模式 |
|------|------------------|--------------|--------------|
| 配置 | `enableTaskPlanning(true)`<br>`enableTaskLoop(false)` | `enableTaskLoop(true)` | `subagents(...)` |
| 外层循环 | **无** | Round 循环(TaskLoopController) | 主 Agent ReAct 循环 |
| 内层循环 | 单一 ReAct 循环 | ReAct 循环(每 Round 一个) | 子 Agent ReAct 循环 |
| todo item | **仅记事本** | 可关联到 Round task | 不直接关联 |
| 框架强制顺序 | 否(模型自主) | 是(Round 串行) | 否(看主 Agent 如何调用) |
| 完成判定 | 模型自主停止 | `<promise>` 信号 | 子 Agent 返回结果 |

---

## 五、五种行财报分析的架构选择

[FiveBanksSearchExample.java](../../examples/deepagent-example/deepagent/FiveBanksSearchExample.java) 中:

```java
DeepAgentConfig config = DeepAgentConfig.builder()
    .enableTaskLoop(false)
    .enableTaskPlanning(true)
    // 没有 .subagents(...) 配置
    // 没有 .enableAsyncSubagent(...)
    .build();
```

**没有配置任何子 Agent**,所以两个 Rail 都不会注入。5 家银行的分析都在**主 Agent 的单个 ReAct 循环**里串行完成,靠 `todo` 工具跟踪进度。

### 三种可选架构对比

| 架构 | 配置 | 优点 | 缺点 |
|------|------|------|------|
| **单 Agent + todo** (当前样例) | `enableTaskPlanning(true)` | 实现简单,可读性强 | 串行,速度慢 |
| **TaskLoop 双循环** | `enableTaskLoop(true)` | 框架强制按 task 顺序执行,有完成信号保证 | 仍串行,无并行加速 |
| **异步子 Agent** | `subagents(...)` + `enableAsyncSubagent(true)` | **5 家银行可并行,理论 5 倍速** | 实现复杂,需管理会话状态 |

五大行财报分析这种**5 个独立任务**的场景,**异步子 Agent 模式效率最高**(5 倍速)。当前样例为了**可读性和可跑通**,选择了最简单的单 Agent 方案。

---

## 六、一句话总结

- **默认配置**:单层 ReAct 循环 + todo 记事本,依赖大模型自觉按清单执行
- **TaskLoop 模式**:真正的外层 Round + 内层 ReAct 双循环,通过 `<promise>` 完成信号驱动
- **同步子 Agent** (`task_tool`):主 Agent 打电话给子 Agent,**等对方说完**才挂电话继续
- **异步子 Agent** (`sessions_spawn`):主 Agent **发微信**给多个子 Agent,各自处理,主 Agent 随时查看进度

选择哪种架构,取决于任务的独立性、并行度需求和可控性要求。

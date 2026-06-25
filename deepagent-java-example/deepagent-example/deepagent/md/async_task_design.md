# DeepAgent 长时异步任务队列设计

## 一、先说问题：为什么普通 Agent 不够用？

普通 Agent（如 ReActAgent）是**同步阻塞**的：用户发一个问题，Agent 执行完返回结果。一次交互，一次执行，简单直接。

但真实业务场景中，Agent 面临三个核心挑战：

| 挑战 | 具体问题 |
|------|---------|
| **执行时间长** | Agent 可能要调多个工具、跑多个工作流，一次执行可能几分钟甚至几十分钟。同步阻塞 = 用户一直等着，什么都做不了 |
| **执行过程中需要干预** | Agent 走偏了需要纠偏（Steer），有新需求需要追加（Follow-up），出错了需要中止（Abort）。同步模型下，执行期间无法干预 |
| **一个任务可能衍生多个子任务** | "生成营销方案"可能拆成：调研市场→分析竞品→生成方案。子任务之间有依赖，需要排队、串行执行 |

**如果用同步模型**：要么用户一直等，要么每次只能做一件事，要么中间出了问题只能从头来。

---

## 二、设计思路：三层架构

DeepAgent 的长时异步任务队列分三层，每层解决一个层面的问题：

```
┌─────────────────────────────────────────────────────────────┐
│  第 1 层：Controller（任务调度层）                             │
│  TaskScheduler + TaskManager + EventQueue                    │
│  解决：异步执行、任务生命周期管理、事件路由                       │
├─────────────────────────────────────────────────────────────┤
│  第 2 层：TaskLoop（外循环控制层）                             │
│  TaskLoopController + LoopQueues + LoopCoordinator           │
│  解决：多轮迭代、Steer/Follow-up/Abort 三种控制语义            │
├─────────────────────────────────────────────────────────────┤
│  第 3 层：DeepAgent（业务执行层）                              │
│  DeepAgent.runTaskLoop() + ReActAgent（内循环）               │
│  解决：具体任务执行、上下文管理、工具调用                        │
└─────────────────────────────────────────────────────────────┘
```

---

## 三、每层具体怎么工作的

### 第 1 层：Controller — 让任务异步跑起来

**问题**：Agent 执行可能要几分钟，不能让调用方一直阻塞等。

**方案**：`TaskScheduler` 把任务提交和执行解耦。

```
用户发请求 → TaskManager.addTask(status=SUBMITTED) → 立即返回 task_id
                                                          ↓
TaskScheduler 定时扫描 SUBMITTED 任务 → 在虚拟线程上异步执行 → 流式输出结果
```

关键设计：
- **虚拟线程执行**：每个任务在独立虚拟线程上跑，不占用平台线程
- **流式输出**：执行过程中产生的中间结果通过 `session.writeStream()` 实时推送
- **超时控制**：`config.getTaskTimeout()` 设置超时，watchdog 线程到时中断
- **事件驱动**：执行完成后发布 `TASK_COMPLETION` / `TASK_INTERACTION` / `TASK_FAILED` 事件

### 第 2 层：TaskLoop — 让外循环可控

**问题**：Agent 执行期间，调用方需要实时干预（纠偏、追加、中止）。

**方案**：`TaskLoopController` + `LoopQueues` 提供三种控制语义。

```
LoopQueues 内部结构：
  steering  (ArrayDeque)     ← Steer 纠偏队列
  isFollowUp (ArrayDeque)    ← Follow-up 追加队列
  events    (PriorityQueue)  ← 优先级事件队列（ABORT=0 > STEER=1 > FOLLOWUP=10）
```

**优先级设计**：`DeepLoopEvent` 按优先级排序，确保紧急事件优先处理：

| 事件类型 | 优先级 | 含义 |
|---------|--------|------|
| ABORT | 0（最高） | 立即中止，不管当前在做什么 |
| STEER | 1 | 纠偏指令，注入到下一次 LLM 调用 |
| FOLLOWUP | 10（最低） | 追加任务，当前迭代结束后执行 |

**外循环的运行逻辑**（`DeepAgent.runTaskLoop()`）：

```java
while (coordinator.shouldContinue() && rounds.size() < maxRounds) {
    // 1. 执行一轮内循环
    Map<String, Object> roundResult = executeCoreLoopRound(query, isFollowUp, session, ...);
    rounds.add(roundResult);

    // 2. 更新循环状态
    coordinator.incrementIteration();
    coordinator.addTokenUsage(resolveTokenUsage(roundResult));

    // 3. 检查是否有 Follow-up
    List<String> followUps = loopController.drainFollowUp(sessionId);
    if (followUps.isEmpty()) {
        continue;  // 没有追加任务，检查是否该停止
    }

    // 4. 取出第一个 Follow-up 作为下一轮的 query，其余放回队列
    currentQuery = followUps.remove(0);
    for (String followUpQuery : followUps) {
        loopController.enqueueFollowUp(sessionId, followUpQuery);
    }
    isFollowUp = true;
}
```

**停止条件评估链**（`LoopCoordinator`）：

```java
public boolean shouldContinue() {
    if (isAborted) return false;  // 被中止
    for (StopConditionEvaluator evaluator : evaluators) {
        if (evaluator.shouldStop(ctx)) return false;  // 满足某个停止条件
    }
    return true;
}
```

| 评估器 | 停止条件 |
|--------|---------|
| CompletionPromiseEvaluator | Agent 输出匹配了完成承诺（如"任务已完成"） |
| MaxRoundsEvaluator | 迭代次数超过上限 |
| TimeoutEvaluator | 总执行时间超时 |

### 第 3 层：DeepAgent — 把 Controller 和 TaskLoop 串起来

**问题**：Controller 只管"任务调度"，TaskLoop 只管"循环控制"，谁来把它们串起来？

**方案**：`CoreTaskLoopEventExecutor` 是桥梁——它是一个 TaskExecutor，注册到 Controller 的 TaskExecutorRegistry 中，当 Controller 调度到 `deep_agent_task` 类型的任务时，就调用 DeepAgent 来执行。

```
Controller 调度任务
  → TaskExecutorRegistry.getTaskExecutor("deep_agent_task")
  → CoreTaskLoopEventExecutor.executeAbility()
    → DeepAgent.invoke()  ← 真正执行
    → 返回 ControllerOutputChunk（流式）
```

**关键交互流程**：

```
1. 用户发请求
   → EventQueue.publishEvent(INPUT)
   → TaskLoopEventHandler.handleInput()
     → TaskManager.addTask(status=SUBMITTED)

2. TaskScheduler 扫描到 SUBMITTED 任务
   → CoreTaskLoopEventExecutor.executeAbility()
     → DeepAgent.invoke()
       → DeepAgent.runTaskLoop()
         → while (shouldContinue) {
             executeCoreLoopRound()  ← 每轮通过 EventQueue 提交子任务
             drainFollowUp()         ← 检查追加队列
             coordinator.shouldContinue()  ← 检查停止条件
           }

3. 用户中途发 Steer
   → EventQueue.publishEvent(TASK_INTERACTION)
   → TaskLoopEventHandler.handleTaskInteraction()
     → controller.enqueueSteering(sessionId, message)
   → 下一轮内循环时，Steering 消息注入到 LLM 的输入中

4. 任务完成
   → CoreTaskLoopEventExecutor 输出 TASK_COMPLETION 事件
   → TaskLoopEventHandler.handleTaskCompletion()
     → controller.resolveCompletion(round, result)
   → DeepAgent.runTaskLoop() 的 awaitRoundCompletion() 收到结果
```

---

## 四、解决的核心问题

| 问题 | 没有任务队列 | 有任务队列 |
|------|------------|-----------|
| **长时间执行** | 调用方阻塞等待，超时断连 | 异步提交，task_id 返回，流式获取结果 |
| **中途干预** | 无法干预，只能等执行完或杀进程 | Steer 纠偏、Follow-up 追加、Abort 中止 |
| **多任务排队** | 只能串行，一个做完才能做下一个 | Follow-up 队列自动排队，一个做完自动做下一个 |
| **资源控制** | 无限制，可能无限循环 | MaxRounds + Timeout + TokenBudget 三重保护 |
| **状态持久化** | 进程挂了就全丢了 | TaskPlanSnapshot 可保存到磁盘，支持恢复 |
| **与前端集成** | 只能返回最终结果 | 流式输出中间过程，前端实时展示 |

---

## 五、适用场景

### 场景 1：超级智能体 — 多步骤复杂任务

```
用户："拜访华为科技，生成营销方案；根据对公贷款政策出融资方案；生成下周四出行日程"

DeepAgent 外循环：
  Round 1: 拜访华为科技 → 调搜索工具 → 调工作流 → 生成营销方案
  Round 2: (Follow-up) 对公贷款政策 → 调搜索工具 → 调工作流 → 生成融资方案
  Round 3: (Follow-up) 下周四出行日程 → 调日历工具 → 生成日程

每个 Round 都是异步执行，中间可 Steer 纠偏
```

### 场景 2：代码重构 — 长时间执行 + 中途纠偏

```
用户："重构 X 模块"

Round 1: Agent 分析代码结构，规划重构步骤
  → 用户发现方向不对，发 Steer："不要改接口层，只改实现层"
Round 2: Agent 根据纠偏继续执行
  → Agent 执行了 5 分钟还没完
  → 用户发 Abort："先停，方案需要再讨论"
```

### 场景 3：工作流编排 — 子智能体协作

```
用户："处理这笔贷款申请"

Round 1: DeepAgent 调用"资质审查"工作流（子智能体 Skill）
  → 工作流中有 QA 节点，需要用户确认
  → TaskInteractionEvent → 前端展示确认界面
  → 用户确认 → Steer 注入确认结果
Round 2: DeepAgent 调用"风险评估"工作流
Round 3: (Follow-up) DeepAgent 调用"合同生成"工作流
```

### 场景 4：定时/批量任务 — 后台自动执行

```
系统定时触发："每天凌晨分析昨日交易数据，生成风险报告"

TaskManager.addTask() → TaskScheduler 异步调度
  → DeepAgent 执行分析任务
  → 完成后通过事件通知下游系统
  → 如果超时（TimeoutEvaluator），自动中止并告警
```

---

## 六、一句话总结

**DeepAgent 的长时异步任务队列 = Controller 异步调度 + TaskLoop 外循环控制 + DeepAgent 业务执行，三层协作解决"Agent 任务耗时长、执行中需干预、多任务需排队"三大问题，让 Agent 从"一问一答的同步工具"变成"可干预、可排队、可持久化的异步任务引擎"。**

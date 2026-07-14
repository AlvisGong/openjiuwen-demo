# DeepAgent Harness 源码分析:为什么需要这套软件工程能力

> 本文基于 DeepAgent Java 源码,从代码层面证明 harness 存在的必要性。
> 每一个 Rail 的存在,都是一个 LLM 缺陷的铁证。

---

## 一、源码证据总览:30+ 个 Rail,每个都在"补 LLM 的窟窿"

```
harness/rails/
├── task_loop/                         ← 执行循环治理(防跑飞)
│   ├── TaskLoopController.java
│   ├── TimeoutEvaluator.java          ← 你遇到过的"第九轮超时"
│   ├── TokenBudgetEvaluator.java      ← token 预算控制
│   ├── MaxRoundsEvaluator.java        ← 最大轮数控制
│   └── CompletionPromiseEvaluator.java
├── ProgressiveToolRail.java           ← 工具治理(防上下文爆炸)
├── ContextAssembleRail.java           ← 上下文治理(防膨胀)
├── ContextProcessorRail.java
├── VerificationRail.java              ← 验证治理(防幻觉)
├── VerificationContractRail.java
├── SecurityRail.java                  ← 安全治理(防乱删)
├── security/PermissionInterruptRail.java
├── TaskPlanningRail.java              ← 任务规划(防无序)
├── SubagentRail.java                  ← 子agent委派(上下文防火墙)
├── MemoryRail.java                    ← 记忆治理(防失忆)
├── CodingMemoryRail.java
├── ExternalMemoryRail.java
├── HeartbeatRail.java
├── evolution/EvolutionRail.java       ← 演化治理(反馈复利)
├── interrupt/ (7个文件)               ← 中断治理(人工介入)
├── SkillUseRail.java                  ← 技能治理
├── SkillCreateRail.java
├── TeamSkillRail.java
└── TeamSkillCreateRail.java
```

**如果 LLM 本身就够了,为什么要写这么多 Rail?**
——每个 Rail 的存在,都是一个"LLM 缺陷"的铁证。

---

## 二、逐个对应:源码在解决什么问题

### 问题 1:LLM 会无限循环、跑飞 → 执行循环治理

**源码证据**:`task_loop/TimeoutEvaluator.java`

```java
public boolean shouldStop(StopEvaluationContext context) {
    return context != null && context.getElapsedSeconds() >= timeoutSeconds;
}
```

这就是你之前遇到"第九轮超时约5分钟"的原因——harness 在保护你不被 LLM 拖垮。
如果没有这个 Rail,LLM 会无限重试直到把你的 token 预算烧光。

同类的还有:
- `TokenBudgetEvaluator`:token 预算控制
- `MaxRoundsEvaluator`:最大轮数控制

**LLM 自己知道什么时候该停吗?不知道。** 它会一直"再试一次"。必须靠 harness 强制刹车。

---

### 问题 2:Skill 太多上下文爆炸 → 工具治理

**源码证据**:`rails/ProgressiveToolRail.java` 的 `filterCallableTools()`

```java
Set<String> callable = new LinkedHashSet<>();
callable.addAll(metaToolNames);          // search_tools, load_tools
callable.addAll(alwaysVisibleTools);     // 永久可见
callable.addAll(readVisibleTools(session)); // 被 load 过的
// 只保留 callable 集合中的工具
```

**如果没有这个 Rail**,100 个 skill 全注入,上下文 1/3 被吃掉,LLM 还会选错。

| skill 数量 | 平均描述 | 占用 token | 占 128K 上下文 |
|-----------|---------|-----------|--------------|
| 3 | 200 字 | ~600 | 0.5% |
| 20 | 200 字 | ~4,000 | 3% |
| 100 | 200 字 | ~20,000 | **16%** |
| 200 | 200 字 | ~40,000 | **31%** |

---

### 问题 3:上下文膨胀、信息退化 → 上下文治理

**源码证据**:`rails/ContextAssembleRail.java`

```java
private static final int MAX_WORKSPACE_ENTRIES = 80;
private static final int MAX_CONTEXT_FILES = 8;
private static final int WORKSPACE_PRIORITY = 30;
private static final int TOOLS_PRIORITY = 40;
private static final int CONTEXT_PRIORITY = 50;
```

**如果没有这个 Rail**,LLM 的上下文会被无关文件、旧日志填满,进入 HumanLayer 研究的"笨蛋区"。

HumanLayer 实证研究:18 个主流模型在上下文增长时性能均显著下降,且当上下文中存在低语义相关性的干扰信息时,退化更加陡峭。上下文会随着工作推进而"腐烂"——当膨胀到一定程度,Agent 就进入了所谓的"笨蛋区",即使是简单任务也开始出错。

harness 用优先级和数量上限,保证 LLM 每轮看到的是**最相关的内容**。

---

### 问题 4:LLM 会幻觉、不能自检 → 验证治理

**源码证据**:`rails/VerificationRail.java`

```java
public static final Set<String> DEFAULT_ALLOWED_TOOLS =
    Set.of("read_file", "bash", "grep", "glob", "list_files",
           "web_search", "web_fetch", "todo_create", ...);

private static final String REMINDER_CONTENT = """
=== VERIFICATION AGENT - ACTIVE CONSTRAINTS ===
1. You CANNOT create, modify, or delete project files.
2. Every check MUST include a 'Command run' block with verbatim terminal output.
3. You MUST end your final response with exactly one of:
   VERDICT: PASS / VERDICT: FAIL / VERDICT: PARTIAL
4. Reading code is NOT verification. Run commands and show actual output.
""";
```

**这段代码揭示了 LLM 的三个缺陷**:

1. 会"假装验证"——只读代码就说 OK,所以强制"Run commands"
2. 会修改证据——所以禁止"CANNOT create, modify, or delete"
3. 会含糊其辞——所以强制"exactly one of PASS/FAIL/PARTIAL"

**LLM 自己会验证吗?不会。** 它会说"看起来没问题"。必须靠 harness 强制它跑命令、看真实输出、给明确结论。

---

### 问题 5:LLM 会乱删文件、执行危险命令 → 安全治理

**源码证据**:`rails/SecurityRail.java`

```java
private static final Set<String> DEFAULT_WRITE_COMMAND_TOKENS = Set.of(
    ">", ">>", "rm", "rmdir", "mv", "cp", "mkdir", "touch",
    "chmod", "chown", "git add", "git commit", "git push",
    "npm install", "pip install", "mvn install"
);
```

**如果没有这个 Rail**,LLM 可能一个 `rm -rf /` 就把你的项目删了,或者 `git push --force` 覆盖远程。harness 用白名单拦截所有写操作。

---

### 问题 6:LLM 不会自动拆解多步任务 → 任务规划

**源码证据**:`rails/TaskPlanningRail.java`

```java
public class TaskPlanningRail extends DeepAgentRail implements TaskIterationRail {
    private final Map<String, List<TodoItem>> todosCache = new HashMap<>();
    private final Map<String, ModelUsageRecord> usageRecords = new LinkedHashMap<>();
    private TodoTool todoTool;
    ...
}
```

**如果没有这个 Rail**,你给 LLM "分析五家银行财报",它可能直接搜一家就开始写结论,忘了其他四家。harness 用 `todo_create/list/modify` 强制它规划、跟踪、完成每一步。

---

### 问题 7:上下文防火墙 → 子agent委派

**源码证据**:`rails/SubagentRail.java`

```java
public class SubagentRail extends DeepAgentRail {
    // 注册 task_tool,让父 agent 能委派子 agent
    Tool tool = new LocalFunction(..., inputs ->
        taskTool.delegate(
            stringValue(inputs.get("subagent_type")), ...));
}
```

**如果没有这个 Rail**,所有任务都在一个上下文里做,父 agent 会被子任务的日志污染。

这是"子 Agent 上下文防火墙"的实现:
- 父 Agent 使用高推理模型(如 Opus/Codex),只做规划、委派、汇总
- 子 Agent 使用快速模型,在独立干净的上下文中执行具体任务
- 完成后只返回压缩摘要 + 源引用
- 子 Agent 在 Git Worktree 中隔离执行,互不干扰

这种模式可以节省 60-70% 的总成本,同时保持代码质量。

---

### 问题 8:无状态、无项目记忆 → 记忆治理

**源码证据**:4 个 Rail 专门处理记忆

```
MemoryRail.java          ← 通用记忆
CodingMemoryRail.java    ← 编码记忆
ExternalMemoryRail.java  ← 外部记忆
HeartbeatRail.java       ← 心跳持久化
```

**如果没有这些 Rail**,每次会话都从零开始,你昨天告诉它的架构约定今天就忘。harness 用 4 个 Rail 把记忆持久化、分层管理。

---

### 问题 9:反馈不积累 → 演化治理

**源码证据**:`rails/evolution/EvolutionRail.java`

```java
public class EvolutionRail extends DeepAgentRail {
    private final List<String> toolTrace = new ArrayList<>();
    private final boolean isAccumulateTrajectory;

    public void afterToolCall(AgentCallbackContext ctx) {
        toolTrace.add(inputs.getToolName());  // 记录每次工具调用
        if (evolutionTrigger == AFTER_TOOL_CALL) {
            runEvolution(ctx);  // 触发演化
        }
    }
}
```

**如果没有这个 Rail**,系统就是无状态的——每个需求都得从零开始。`isAccumulateTrajectory` 让反馈产生复利,每次执行都在为下一次积累优势。

---

### 问题 10:需要人工介入 → 中断治理

**源码证据**:`rails/interrupt/` 目录 7 个文件

```
AskUserRail.java          ← 问用户
ConfirmInterruptRail.java ← 确认操作
ApproveResult.java        ← 批准
RejectResult.java         ← 拒绝
InterruptDecision.java    ← 决策
```

**如果没有这些 Rail**,LLM 要么闷头干完(可能干错),要么每步都问(烦死你)。harness 用中断机制让 LLM 在关键节点请求人工确认。

---

## 三、回到本质:harness 在解决什么

把这些 Rail 汇总,会发现它们解决的是**同一类问题的不同侧面**:

| LLM 的根本缺陷 | harness 的解法 | 对应 Rail |
|--------------|--------------|----------|
| 会无限循环 | 强制刹车 | Timeout / TokenBudget / MaxRounds |
| 会选错工具 | 工具过滤 | ProgressiveToolRail |
| 会上下文膨胀 | 上下文限量 | ContextAssembleRail |
| 会幻觉 | 强制验证 | VerificationRail |
| 会乱删文件 | 安全拦截 | SecurityRail |
| 不会拆任务 | 强制规划 | TaskPlanningRail |
| 会污染上下文 | 防火墙隔离 | SubagentRail |
| 会失忆 | 记忆持久化 | MemoryRail x4 |
| 不积累反馈 | 演化记录 | EvolutionRail |
| 不懂何时求助 | 中断机制 | interrupt/ x7 |

---

## 四、对应 ETCSV 治理模型

| 组件 | 全称 | 职责 | 对应 Rail |
|------|------|------|----------|
| **E** | Execution Loop | 驱动"思考-行动-观察"主循环 | TaskLoopController / TimeoutEvaluator |
| **T** | Tool Registry | 定义工具能力边界 | ProgressiveToolRail / SkillUseRail |
| **C** | Context Manager | 维护上下文质量与体积 | ContextAssembleRail / SubagentRail |
| **S** | State Store | 持久化状态,支持中断恢复 | MemoryRail / HeartbeatRail |
| **L** | Lifecycle Hooks | 关键时机插入强规则拦截 | SecurityRail / interrupt/ |
| **V** | Evaluation Interface | 可观测、可分析的评估体系 | VerificationRail / EvolutionRail |

---

## 五、与三种工程的关系

回顾三种工程的演进:

| 工程 | 核心问题 | 人类角色 | 局限 |
|------|---------|---------|------|
| **Prompt Engineering** | 怎么跟模型说话? | 用户雕琢措辞 | 单次交互、无状态、手艺活 |
| **Context Engineering** | 模型应该看到什么? | Builder 设计动态上下文系统 | 仍只管输入侧 |
| **Harness Engineering** | 整个环境如何运作? | 设计约束、反馈、验证、治理 | 治理面最全 |

**Harness 不是替代前两者,而是在其之上加上约束、反馈、验证、治理。**

Prompt 写得再好,也穷尽不了隐式规则;上下文工程管得再好,也无法阻止 LLM 幻觉。只有 harness 把规则编码到环境里,让 Agent **自己验证对错**,而不是靠 LLM 的"直觉"。

---

## 六、为什么 DeepAgent 必须自己构建,不能依赖外部

### 1. harness 与 Agent 是紧耦合的

```
外部 CI/CD  ────→  人类开发者(松耦合,事后检查)
harness     ────→  Agent(紧耦合,事前+事中+事后)
```

harness 必须在 Agent 的**每次工具调用前、每次推理前、每次推理后**插入 Hook,这种紧耦合无法用外部工具实现。

### 2. harness 需要理解 Agent 的执行语义

`ProgressiveToolRail.beforeModelCall` 需要知道:
- 当前是第几轮推理
- 当前 session 的 visible tools 状态
- 当前要调用的工具列表

这些信息只有 Agent 框架内部才有,外部工具无法介入。

### 3. harness 需要随 Agent 一起演化

skill 热加载、子 Agent 委派、上下文防火墙……这些能力必须与 Agent 的执行循环**同生共死**,不能是外挂。

---

## 七、一句话总结

**DeepAgent 构建 harness 的软件工程能力,是因为 LLM 有 10 类自身无法克服的缺陷:会跑飞、会选错、会膨胀、会幻觉、会乱删、不会规划、会污染、会失忆、不积累、不求助。**

源码中 30+ 个 Rail 的存在,就是这些缺陷的铁证——每一个 Rail 都在补一个 LLM 的窟窿。

**harness 不是增强 LLM,而是给 LLM 装操作系统**,让它从"聊天玩具"变成"可靠的工程执行体"。这不是可选优化,是 LLM 走向生产的必经之路。

---

## 参考资料

- 源码:`src/main/java/com/openjiuwen/harness/rails/` 30+ Rail 类
- [五大挑战详解](harness_five_challenges.md)
- [三种工程演进](three_engineering_evolution.md)
- [Skill 扩展性挑战](skill_scaling_challenges.md)
- HumanLayer "Skill Issue: Harness Engineering for Coding Agents"
- Andrej Karpathy on Context Engineering (2025.06)

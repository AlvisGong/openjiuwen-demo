# 意图中控（MOA Agent）整体流程与 SPI 扩展分析

> 底座模块：`moa/phoenix`（`com.customer.platform:moa-agent-library`）
> 业务扩展模块：`moa/demo/moa_agent/moa-agent`（`com.customer.finance:moa-agent-extension`）

---

## 一、整体流程概览

意图中控（MOA Agent）是一个基于 LLM 的意图路由与任务编排引擎，核心理念是通过 **Rail（轨道）回调链** + **状态机** 驱动意图识别、规划、执行全链路。

流程分两条路径：

- **快路径（FAST）**：单任务、单候选 → 跳过模型规划，直接委派执行
- **慢路径（SLOW）**：多任务 / 多候选 → LLM 驱动的阶段规划（plan→bind→finalize→execute）

路径选择由 `IntentRouter.Decision` 决定，关键判据是 **任务数（TaskCardinality）** 和 **候选数**。

### 完整流程节点图

```
用户请求 (HTTP POST)
    │
    ▼
┌─────────────────────────────────────────────────┐
│ MoaCustomRestAdapter.toA2ARequest()              │  HTTP 解包为 A2A 消息
└──────────────────────┬──────────────────────────┘
                       ▼
┌─────────────────────────────────────────────────┐
│ DeepAgent.invoke()                               │  L1 智能体入口
└──────────────────────┬──────────────────────────┘
                       ▼
┌─────────────────────────────────────────────────┐
│ Rail 链 beforeInvoke (按 priority 降序)           │
│                                                   │
│  IntentRoutingRail [2000]                        │
│    ├─ 检查"继续计划" / 服务带出查询                │
│    ├─ IntentRouter.route(query, intent, session) │
│    │    ├─ 显式 intent 唯一命中 → 快路径            │
│    │    └─ 并发执行 ↓                              │
│    │       ┌────────────────┐                    │
│    │       │ IntentRecall    │ ◄── SPI ①        │
│    │       │  .recall()      │                    │
│    │       └────────────────┘                    │
│    │       ┌────────────────────┐                │
│    │       │ TaskCardinality    │ ◄── SPI ②      │
│    │       │  Analysis.classify │                │
│    │       └────────────────────┘                │
│    │    │                                          │
│    │    ├─ IntentCandidateResolver ◄── SPI ③     │
│    │    │  (召回意图 → 候选卡解析)                   │
│    │    │                                          │
│    │    ├─ SINGLE + 1候选 → 快路径                  │
│    │    ├─ SINGLE + 多候选 → 仲裁                  │
│    │    │    └─ IntentArbitration ◄── SPI ④     │
│    │    │       ├─ 仲裁 1项 → arbitrated_single   │
│    │    │       ├─ 仲裁 2~3项 → fast_select       │
│    │    │       └─ 无匹配/失败 → 慢路径             │
│    │    └─ MULTIPLE / UNKNOWN → 慢路径             │
│    │                                              │
│    ├─ 快路径: planning.startFast()                 │
│    │    → ExecutionEngine.dispatch()               │
│    │    → publishFastInterrupt() 等待 L2 回调      │
│    │                                              │
│    └─ 慢路径: planning.beginRequest()              │
│         → 交给模型规划                              │
└──────────────────────┬──────────────────────────┘
                       │
          ┌────────────┴─────────────┐
          ▼ 慢路径                     ▼ 快路径
┌──────────────────┐    ┌──────────────────────────┐
│ RequestCapability │    │ [等待 L2 回调]            │
│ Rail [-10000]     │    │ IntentRoutingRail        │
│ beforeModelCall   │    │  .beforeToolCall()       │
│ 注入候选+工具白名单 │    │  → planning.finishFast() │
└────────┬─────────┘    │  → 终答                    │
         ▼              └──────────────────────────┘
┌──────────────────────────────┐
│ 模型调用 stage_plan            │
│ StagePlanningRail [1000]      │
│  .beforeToolCall()            │
│  → advancePlan()              │
│                               │
│ 阶段1: stage_plan             │
│   创建任务清单 (1~32 项)        │
│   phase: EMPTY→SELECTING      │
│         →BINDING              │
├───────────────────────────────┤
│ 阶段2: stage_bind_next (重复)  │
│   绑定下一个 DRAFT todo 候选    │
│   ├─ CapabilityDiscovery      │ ◄── SPI ⑤
│   │   .recall() (补充召回)     │
│   ├─ TaskCandidateSelector    │ ◄── SPI ⑥
│   │   .select() (子任务候选筛选)│
│   └─ 多候选时中断澄清           │
│   phase: BINDING→BOUND        │
├───────────────────────────────┤
│ 阶段3: stage_finalize          │
│   锁定计划                     │
│   HIGH 风险 → plan_confirmation│
│   中断等待用户确认              │
│   phase: BOUND→CONFIRMING     │
│         →READY                │
├───────────────────────────────┤
│ 阶段4: stage_execute_next      │
│   (重复, 串行执行)              │
│   ├─ TaskConditions.evaluate() │
│   │   (条件检查)               │
│   ├─ ExecutionEngine.dispatch()│
│   │   → A2A 委派 / 本地委派     │
│   │   ◄── SPI ⑦ LocalAgentDelegate│
│   ├─ A2ADelegateResponse       │
│   │   Listener.onResponse()    │ ◄── SPI ⑧
│   ├─ SkillScriptRunner.run()   │ ◄── SPI ⑨
│   │   (Skill 脚本执行)         │
│   └─ ScriptEnvironmentProvider │ ◄── SPI ⑩
│       .resolve() (脚本环境变量) │
│   phase: READY→EXECUTING       │
│         →READY/COMPLETED/FAILED│
├───────────────────────────────┤
│ 阶段5: 意图跳变检测              │
│   IntentSwitch.detected()     │
│   → suspend() 挂起当前计划      │
│   → 重新 route()               │
└───────────────────────────────┘
```

---

## 二、Rail 编排链路

Rail 是运行时回调钩子，按 `priority` 从大到小依次执行。在 `MoaRuntimeAutoConfiguration.moaAgentHandler()` 中组装：

| Rail | priority | 类型 | 职责 |
|---|---|---|---|
| `IntentRoutingRail` | 2000 | AgentRail | 请求路由分流、快路径执行、意图切换 |
| `StagePlanningRail` | 1000 | BaseInterruptRail | 阶段工具接管、绑定、确认、执行 |
| `LocalDelegationRail` | 100 | DeepAgentRail | 本地 Skill 委派的父子中断标识适配 |
| `ThinkingEventRail` | 50 | DeepAgentRail | 思考事件、终答事件、阶段进度文案 |
| `InterruptEventRail` | 50 | DeepAgentRail | 候选选择/风险确认的中断事件输出 |
| `RequestCapabilityRail` | -10000 | AgentRail | 请求级候选注入、工具白名单过滤 |

### 核心调用链路

**快路径（FAST）**

```
IntentRoutingRail.beforeInvoke()
  → IntentRouter.route() → fast=true
  → planning.startFast() → ExecutionEngine.dispatch()
  → publishFastInterrupt() (等待 L2 回调)

[回调到达]
IntentRoutingRail.beforeToolCall()
  → planning.finishFast() → 终答
```

**快路径选择（FAST_SELECT）**

```
SINGLE + 多候选 → 仲裁保留 2~3 项
  → FastCandidateSelection.start() (phase=SELECTING)
  → 中断等待用户选择

[用户选择]
IntentRoutingRail.beforeToolCall()
  → CandidateSelection.resolve()
  → CandidateChoices.remember()
  → delegateFast() (转入单任务快路径)
```

**慢路径（SLOW）**

```
MULTIPLE/UNKNOWN → planning.beginRequest()
RequestCapabilityRail.beforeModelCall() → 注入候选 + 工具白名单

模型调用 stage_plan
  → StagePlanningRail.create() → phase=BINDING

模型调用 stage_bind_next (重复)
  → bind() → phase=BOUND

模型调用 stage_finalize
  → finalizePlan() → HIGH 风险时中断确认 → phase=READY

模型调用 stage_execute_next (重复)
  → execute() → ExecutionEngine.dispatch() → phase=EXECUTING
  → [L2 回调] → completeExecution() → READY/COMPLETED/FAILED
```

---

## 三、SPI 扩展点完整清单

框架 **不使用 `@SPI` 注解**，而是采用 Spring Boot `@ConditionalOnMissingBean` + `@FunctionalInterface` 的扩展点设计模式。业务方在自己的 starter 中注册 Bean 即可替换默认实现。

| # | SPI 接口 | 包路径 | 方法签名 | 底座默认实现 | 扩展场景 |
|---|---|---|---|---|---|
| ① | `IntentRecall` | `routing.recall.IntentRecall` | `List<CandidateCard> recall(String query, Session session)` | `PackIntentRecall`（本地 YAML 关键词召回） | 接入外部意图工作流 HTTP 服务 |
| ② | `TaskCardinalityAnalysis` | `routing.cardinality.TaskCardinalityAnalysis` | `TaskCardinality classify(String query)` | `ModelTaskCardinalityAnalysis`（LLM 分类） | 业务已有任务数 HTTP 服务 |
| ③ | `IntentCandidateResolver` | `routing.recall.resolution.IntentCandidateResolver` | `List<CandidateCard> resolve(RecalledIntent intent, CandidateResolutionContext context)` | `DefaultIntentCandidateResolver`（精确匹配+动态 HIGH 风险候选） | 特定领域意图接管解析 |
| ④ | `IntentArbitration` | `routing.arbitration.IntentArbitration` | `List<Integer> select(String query, List<CandidateCard> candidates)` | `ModelIntentArbitration`（LLM 筛选 1~3 项） | 自定义仲裁规则（透传/规则引擎） |
| ⑤ | `CapabilityDiscovery` | `discovery.CapabilityDiscovery` | `List<CandidateCard> recall(String request)` | `CapabilityDiscovery.catalog(pack)`（目录召回） | 企业 ARD 适配、补充召回 |
| ⑥ | `TaskCandidateSelector` | `planning.selection.TaskCandidateSelector` | `List<CandidateCard> select(String todoQuery, List<CandidateCard> candidates, ...)` | `DefaultTaskCandidateSelector` | 子任务级候选筛选 |
| ⑦ | `LocalAgentDelegate` | `execution.LocalAgentDelegate` | `Step start(...)` / `Step resume(...)` | `LocalSkillDelegate`（同进程 DeepAgent 子智能体） | 自定义本地委派执行 |
| ⑧ | `A2ADelegateResponseListener` | `execution.A2ADelegateResponseListener` | `void onResponse(A2ADelegateResponse response)` | 空实现 `response -> {}` | 记录业务历史、回调通知 |
| ⑨ | `SkillScriptRunner` | `skill.SkillScriptRunner` | `Map<String,Object> run(Session, String source, Map<String,Object> input)` | `SandboxScriptRunner`（沙箱执行） | 自定义脚本执行引擎 |
| ⑩ | `ScriptEnvironmentProvider` | `skill.environment.ScriptEnvironmentProvider` | `Map<String,String> resolve(ScriptEnvironmentContext context)` | `SpringScriptEnvironmentProvider`（从 Spring Environment 读取） | 动态注入脚本环境变量 |

---

## 四、各 SPI 扩展点详解与代码对应

### SPI ① IntentRecall — 意图召回

**底座接口定义**（`phoenix`）:
```java
@FunctionalInterface
public interface IntentRecall {
    List<CandidateCard> recall(String query, Session session);
}
```

**底座默认实现**：`PackIntentRecall` — 基于 YAML 候选目录的关键词召回

**demo 业务扩展实现**：`WorkflowIntentRecall`
- 文件：`demo/.../intent/WorkflowIntentRecall.java`
- 实现 `IntentRecall` 接口
- 业务逻辑：
  1. 调用 `FindIntentByWf.invoke()` 向外部意图工作流发送 HTTP POST
  2. 从 `IntentConversationHistory.question()` 拼接最近 4 轮对话上下文
  3. 解析返回的 `intents` 数组，构造 `RecalledIntent`
  4. 通过 `IntentCandidateResolver` 解析为 `CandidateCard` 列表
  5. 按 `candidate.id()` 去重后返回
- 开关：`customer.finance.external-intent-enabled=true`

**辅助类 `FindIntentByWf`**（非 SPI，内部依赖）:
- 负责实际 HTTP 调用外部意图服务
- 请求体：`{"question":"...", "sessionId":"uuid", "parameters":{"cls":{"curValue":"1001"}}}`
- 响应解析：校验 `code=="0"`，提取 `result.outParams.intents`
- adapter 映射：通过 `domainAdapters` 配置将 predomain 映射为 adapter 名

### SPI ② TaskCardinalityAnalysis — 任务数判断

**底座接口定义**:
```java
@FunctionalInterface
public interface TaskCardinalityAnalysis {
    TaskCardinality classify(String query);
}
// TaskCardinality 枚举: SINGLE / MULTIPLE / UNKNOWN
```

**底座默认实现**：`ModelTaskCardinalityAnalysis` — 使用 LLM 分类

**demo 业务扩展实现**：`HttpTaskCardinalityAnalysis`
- 文件：`demo/.../cardinality/HttpTaskCardinalityAnalysis.java`
- 业务逻辑：
  1. HTTP POST 调用外部任务数分析服务（`customer.task-cardinality.url`）
  2. 请求体：`{"query":"..."}`
  3. 解析响应 `cardinality` 字段，通过 `TaskCardinality.valueOf()` 转为枚举
  4. 任何异常降级返回 `UNKNOWN`
- 开关：`customer.finance.external-cardinality-enabled=true`（默认关闭）

### SPI ③ IntentCandidateResolver — 意图候选解析

**底座接口定义**:
```java
@FunctionalInterface
public interface IntentCandidateResolver {
    List<CandidateCard> resolve(RecalledIntent intent, CandidateResolutionContext context);
}
```

**底座默认实现**：`DefaultIntentCandidateResolver` — 先精确匹配目录 adapter+intent，未匹配则构造动态 HIGH 风险候选

**demo 业务扩展实现**：`FinanceSkillCandidateResolver`
- 文件：`demo/.../FinanceSkillCandidateResolver.java`
- 继承 `DefaultIntentCandidateResolver`，覆写 `resolve()`
- 业务逻辑：
  1. 定义理财购买意图集合：`{"理财选品购买", "购买理财", "买理财"}`
  2. 当 `predomain` 为"理财服务"且 `intentName` 在购买意图集合中 → 返回 `cap.skill.finance.purchase` 候选卡
  3. 其他意图调用 `super.resolve()` 走底座默认解析
- 静态方法 `withDemoCandidate()`：在候选目录中注入理财 Skill 候选卡
- 开关：总开关 `moa.demo.finance-skill-enabled=true` + `moa.skill-agent.enabled=true`

### SPI ④ IntentArbitration — 候选意图仲裁

**底座接口定义**:
```java
@FunctionalInterface
public interface IntentArbitration {
    List<Integer> select(String query, List<CandidateCard> candidates) throws Exception;
}
```

**底座默认实现**：`ModelIntentArbitration` — 使用 LLM 筛选 1~3 项

**demo 业务扩展实现**：`PassthroughIntentArbitration`
- 文件：`demo/.../arbitration/PassthroughIntentArbitration.java`
- 业务逻辑：透传模式，直接返回所有候选的从 1 开始的序号列表
  ```java
  IntStream.rangeClosed(1, candidates.size()).boxed().toList();
  ```
- 不调用仲裁模型，不排序、不截断、不丢弃任何候选
- 开关：`customer.finance.arbitration-passthrough-enabled=true`（默认关闭）

### SPI ⑤ CapabilityDiscovery — 能力发现/补充召回

**底座接口定义**:
```java
@FunctionalInterface
public interface CapabilityDiscovery {
    List<CandidateCard> recall(String request);
}
```

**底座默认实现**：`CapabilityDiscovery.catalog(pack)` — 静态工厂方法，基于目录召回

**demo 业务扩展实现**：`FinanceCapabilityDiscovery`
- 文件：`demo/.../discovery/FinanceCapabilityDiscovery.java`
- 业务逻辑：
  1. 先走 fallback（底座默认目录召回）
  2. 如果请求文本包含"理财组合办理"，在默认召回结果基础上补充 `cap.skill.finance.purchase` 候选卡
  3. 按 `card.id()` 去重，不丢弃其他候选
- 开关：`customer.finance.capability-discovery-enabled=true`（默认关闭）

### SPI ⑥ TaskCandidateSelector — 子任务候选筛选

**底座接口定义**:
```java
public interface TaskCandidateSelector {
    List<CandidateCard> select(String todoQuery, List<CandidateCard> candidates, ...);
}
```

**底座默认实现**：`DefaultTaskCandidateSelector`

**demo 业务扩展实现**：`FinanceTaskCandidateSelector`
- 文件：`demo/.../planning/FinanceTaskCandidateSelector.java`
- 继承 `DefaultTaskCandidateSelector`，覆写 `select()`
- 业务逻辑：
  1. 当子任务 query 精确匹配"理财组合办理"（`strip()` 后 equals）时，仅筛选出 `cap.skill.finance.purchase` 组合 Skill 卡
  2. 找不到组合 Skill 时返回空列表
  3. 其他 query 调用 `super.select()` 走底座默认筛选
- 开关：`customer.finance.task-candidate-selector-enabled=true`（默认关闭）

### SPI ⑦ LocalAgentDelegate — 本地智能体委派

**底座接口定义**:
```java
public interface LocalAgentDelegate {
    Step start(Session session, String parentCallId, LocalExecutionRequest request);
    Step resume(Session session, String parentCallId, Object answer);
}
// Step = (InterruptRequest interruption, Object result) — 中断或结果二选一
```

**底座默认实现**：`LocalSkillDelegate` — 同进程 DeepAgent 子智能体

**demo 业务扩展**：未提供自定义实现，使用底座默认

### SPI ⑧ A2ADelegateResponseListener — A2A 委派响应监听

**底座接口定义**:
```java
@FunctionalInterface
public interface A2ADelegateResponseListener {
    void onResponse(A2ADelegateResponse response);
}
```

**底座默认实现**：空实现 `response -> {}`

**demo 业务扩展实现**：`IntentConversationHistory`
- 文件：`demo/.../intent/IntentConversationHistory.java`
- 业务逻辑：
  1. 每次 A2A 子代理返回结果时，记录 `query`、`answer`（快照）、`metadata`（userAgent）到 session 状态
  2. 会话状态键 `moa_intent_history`，最多保留 5 轮对话
  3. `question()` 静态方法：读取最近 4 轮历史，按 `用户:query` / `客服:answer` 格式拼接，供意图召回使用
  4. answer 快照：字符串原样保存，对象/数组通过 Jackson 深拷贝
  5. 多行文本压平为单行，单行截断 8000 字符
- 开关：总开关 `moa.demo.finance-skill-enabled=true`

### SPI ⑨ SkillScriptRunner — 沙箱脚本执行

**底座接口定义**:
```java
public interface SkillScriptRunner {
    Map<String, Object> run(Session session, String source, Map<String, Object> input);
    // default 重载支持 ScriptEnvironmentContext
}
```

**底座默认实现**：`SandboxScriptRunner`

**demo 业务扩展**：未提供自定义实现，使用底座默认

### SPI ⑩ ScriptEnvironmentProvider — 脚本环境变量提供器

**底座接口定义**:
```java
@FunctionalInterface
public interface ScriptEnvironmentProvider {
    Map<String, String> resolve(ScriptEnvironmentContext context);
}
```

**底座默认实现**：`SpringScriptEnvironmentProvider` — 从 Spring Environment 读取配置引用

**demo 业务扩展**：未提供自定义实现，使用底座默认

---

## 五、业务扩展模块（demo）SPI 注册机制

### 注册方式

demo 模块 **不使用 JDK 原生 `META-INF/services/`**，而是使用 Spring Boot 的 `AutoConfiguration.imports` 机制。

注册文件：`demo/.../resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
```
com.customer.finance.moaagent.FinanceDemoConfiguration
```

### 核心配置类 `FinanceDemoConfiguration`

- 注解：`@AutoConfiguration(before = {IntentCandidateConfiguration.class, MoaRuntimeAutoConfiguration.class})`
  - 确保业务配置先于底座装配，底座默认实现通过 `@ConditionalOnMissingBean` 让位
- 总开关：`@ConditionalOnProperty(name = "moa.demo.finance-skill-enabled", havingValue = "true")`

### 注册的 8 个 Bean

| Bean 方法 | 返回类型 | SPI 接口 | 开关条件 |
|---|---|---|---|
| `financeIntentConversationHistory()` | `A2ADelegateResponseListener` | SPI ⑧ | 总开关 ON |
| `financeDemoCandidatePack(...)` | `CandidatePack` | 候选目录 | 总开关 ON |
| `financeDemoIntentCandidateResolver(...)` | `IntentCandidateResolver` | SPI ③ | 总开关 ON + skill-agent.enabled |
| `financeIntentRecall(...)` | `IntentRecall` | SPI ① | external-intent-enabled=true |
| `financeIntentArbitration()` | `IntentArbitration` | SPI ④ | arbitration-passthrough-enabled=true |
| `financeCapabilityDiscovery(...)` | `CapabilityDiscovery` | SPI ⑤ | capability-discovery-enabled=true |
| `financeTaskCandidateSelector()` | `TaskCandidateSelector` | SPI ⑥ | task-candidate-selector-enabled=true |
| `financeTaskCardinality(...)` | `TaskCardinalityAnalysis` | SPI ② | external-cardinality-enabled=true |

### 三级优先级链

所有 Bean 使用 `@ConditionalOnMissingBean` 实现三级优先级：

```
业务自定义 Bean（最高优先级）
    ↓ 未注册时
本模块实现（FinanceDemoConfiguration）
    ↓ 未启用时
MOA 底座默认实现（MoaRuntimeAutoConfiguration）
```

---

## 六、demo 业务场景：理财购买完整流程

### 理财组合 Skill 候选卡

`FinanceSkillCandidateResolver.withDemoCandidate()` 注入自定义能力卡：

| 属性 | 值 |
|---|---|
| ID | `cap.skill.finance.purchase` |
| 名称 | 理财购买 |
| 类型 | SKILL（本地 Skill 委派） |
| 风险 | LOW |
| adapter | 绑定 skill-agent 的 agent-id |

### 8 步理财购买 Skill 流程

```
recommend (推荐产品, A2A→versatile-wealth)
    → refine (MCP 筛选, SCRIPT 调用 call_mcp.py)
    → refine_bind (二次选品+核实办理卡, A2A→versatile-wealth)
    → select (校验产品+金额, SCRIPT 调用 validate_selection.py)
    → wealth_balance (查理财卡余额, A2A→versatile-account)
    → default_balance (查默认卡余额, A2A→versatile-account)
    → transfer (补足资金, A2A→versatile-transfer, 需确认)
    → purchase (购买产品, A2A→versatile-wealth, 需确认+终态)
```

### 完整调用示例

用户输入"理财组合办理"：

```
1. HttpTaskCardinalityAnalysis.classify("理财组合办理")
   → MULTIPLE（外部任务数服务判断为多任务）
   → 慢路径

2. WorkflowIntentRecall.recall("理财组合办理", session)
   → HTTP 调用意图工作流 → 返回意图: predomain=理财服务, intent_name=理财选品购买
   → IntentCandidateResolver.resolve()
     → FinanceSkillCandidateResolver 检测 predomain=理财服务 + intent_name=理财选品购买
     → 命中 cap.skill.finance.purchase 候选卡
   → 返回候选列表 [cap.skill.finance.purchase]

3. 慢路径规划
   → stage_plan: 创建任务清单
   → stage_bind_next: 
     → FinanceTaskCandidateSelector.select("理财组合办理", candidates)
     → 精确匹配 → 筛选出 cap.skill.finance.purchase
     → 绑定候选
   → stage_finalize: LOW 风险 → 直接 READY（无需确认）
   → stage_execute_next:
     → ExecutionEngine.dispatch() → SKILL 类型 → LocalSkillDelegate
     → NativeSkillAgent 执行 8 步流程
     → 每步 A2A 回调 → A2ADelegateResponseListener.onResponse()
       → IntentConversationHistory 记录 query/answer 到会话历史
```

---

## 七、状态机生命周期

`StagePlanningRail` 管理的核心状态机：

```
EMPTY → SELECTING → BINDING → BOUND → CONFIRMING → READY → EXECUTING → COMPLETED
                                                    ↓            ↓
                                                 REJECTED     FAILED
                                                              ↓
                                                          SUSPENDED
                                                          NO_EXECUTABLE
                                                          BLOCKED
                                                          CANCELLED
```

| 阶段 | 触发工具 | 状态转换 | SPI 参与 |
|---|---|---|---|
| 任务规划 | `stage_plan` | EMPTY→SELECTING→BINDING | — |
| 候选绑定 | `stage_bind_next` | BINDING→BOUND | CapabilityDiscovery, TaskCandidateSelector |
| 最终确认 | `stage_finalize` | BOUND→CONFIRMING→READY | — |
| 串行执行 | `stage_execute_next` | READY→EXECUTING→READY/COMPLETED/FAILED | LocalAgentDelegate, A2ADelegateResponseListener, SkillScriptRunner, ScriptEnvironmentProvider |
| 意图跳变 | L2 返回 `not_in_scope` | →SUSPENDED→重新 route | IntentRecall, TaskCardinalityAnalysis, IntentArbitration |

---

## 八、配置项参考

### 底座配置（`moa.orchestration`）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `candidate-pack` | `classpath:candidates/l1-pack.yaml` | 候选包位置 |
| `workspace` | `moa-agent` | DeepAgent 工作区 |
| `analysis-timeout` | `5s` | 分析超时 |
| `arbitration-timeout` | `30s` | 仲裁超时 |
| `max-iterations` | `100` | 慢路径循环上限 |
| `carryout-threshold` | `3` | 服务带出阈值 |

### 业务开关配置（demo）

| 配置项 | 默认值 | SPI | 说明 |
|---|---|---|---|
| `moa.demo.finance-skill-enabled` | false | 全部 | 业务总开关 |
| `moa.skill-agent.enabled` | false | SPI ③ | Skill Agent 开关 |
| `customer.finance.external-intent-enabled` | false | SPI ① | 外部意图召回 |
| `customer.finance.external-cardinality-enabled` | false | SPI ② | 外部任务数分析 |
| `customer.finance.arbitration-passthrough-enabled` | false | SPI ④ | 透传仲裁 |
| `customer.finance.capability-discovery-enabled` | false | SPI ⑤ | 能力发现 |
| `customer.finance.task-candidate-selector-enabled` | false | SPI ⑥ | 子任务候选筛选 |

### 意图工作流配置（`customer.intent-workflow`）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `url` | `http://127.0.0.1:18099/intent` | 意图工作流服务地址 |
| `token` | `""` | 鉴权 token |
| `ssl-verify` | `true` | SSL 验证 |
| `connect-timeout` | `5s` | 连接超时 |
| `request-timeout` | `30s` | 请求超时 |
| `domain-adapters` | `Map.of()` | predomain → adapter 映射 |
| `default-adapter` | `versatile-general` | 默认 adapter |

---

## 九、总结

### 意图中控流程节点与 SPI 扩展点对照

| 流程节点 | 底座实现 | SPI 扩展 | demo 是否实现 |
|---|---|---|---|
| **意图召回** | PackIntentRecall | `IntentRecall` | ✅ WorkflowIntentRecall |
| **任务数判断** | ModelTaskCardinalityAnalysis | `TaskCardinalityAnalysis` | ✅ HttpTaskCardinalityAnalysis |
| **候选解析** | DefaultIntentCandidateResolver | `IntentCandidateResolver` | ✅ FinanceSkillCandidateResolver |
| **候选仲裁** | ModelIntentArbitration | `IntentArbitration` | ✅ PassthroughIntentArbitration |
| **能力发现/补充召回** | CapabilityDiscovery.catalog | `CapabilityDiscovery` | ✅ FinanceCapabilityDiscovery |
| **子任务候选筛选** | DefaultTaskCandidateSelector | `TaskCandidateSelector` | ✅ FinanceTaskCandidateSelector |
| **本地委派执行** | LocalSkillDelegate | `LocalAgentDelegate` | ❌ 使用底座默认 |
| **A2A 响应监听** | 空实现 | `A2ADelegateResponseListener` | ✅ IntentConversationHistory |
| **Skill 脚本执行** | SandboxScriptRunner | `SkillScriptRunner` | ❌ 使用底座默认 |
| **脚本环境变量** | SpringScriptEnvironmentProvider | `ScriptEnvironmentProvider` | ❌ 使用底座默认 |

### 关键设计要点

1. **Spring Boot 条件装配**：所有 SPI 通过 `@ConditionalOnMissingBean` 实现三级优先级（业务自定义 > 本模块 > 底座默认），无需 `@Primary` 或同名覆盖
2. **双路径架构**：快路径（单任务零模型调用）和慢路径（模型规划+状态机），由 `IntentRouter.Decision` 自动分流
3. **状态机驱动**：`StagePlanningRail` 严格状态机，模型只能调用 5 个阶段工具，`afterModelCall` 拦截模型提前结束
4. **Rail 编排**：6 个 Rail 按 priority 降序执行，职责隔离（路由 2000 / 规划 1000 / 委派 100 / 事件 50 / 候选注入 -10000）
5. **会话持久化**：所有状态通过 Session State 的 JSON 字符串持久化，无独立数据库
6. **独立开关**：每个 SPI 扩展点有独立开关，可按需启停，不影响其他扩展点

# IntentRecall 候选召回实现分析

> 对应代码：[WorkflowIntentRecall.java](../src/main/java/com/moaagent/agent/routing/recall/WorkflowIntentRecall.java)、[FindIntentByWf.java](../src/main/java/com/moaagent/agent/routing/recall/FindIntentByWf.java)、[DefaultIntentCandidateResolver.java](../src/main/java/com/moaagent/agent/routing/recall/resolution/DefaultIntentCandidateResolver.java)

---

## 1. 接口定义

[IntentRecall.java](../src/main/java/com/moaagent/agent/routing/recall/IntentRecall.java) 是一个函数式接口：

```java
@FunctionalInterface
public interface IntentRecall {
    List<CandidateCard> recall(String query, Session session);
}
```

生产环境由 `WorkflowIntentRecall` 实现，测试环境由 `StageFlowIT.ResourceConfiguration` 内存硬编码覆盖。

---

## 2. 完整调用链路

```
IntentRouter.route(query, explicit, session)
    │
    ├─ 并行提交到线程池（moa-intent-*，4线程，超时 5s）
    │
    ▼
IntentRecall.recall(query, session)          ← 函数式接口入口
    │
    ▼
WorkflowIntentRecall.recall(query, session)  ← 生产实现
    │
    ├─ 第一步：会话上下文增强
    │   FindIntentByWf.invoke(inputs, session)
    │   ├─ IntentConversationHistory.question(session, query)
    │   │   → 拼接最近5轮历史 + 当前query
    │   └─ IntentConversationHistory.record(session, query, result)
    │       → 回调后保存本轮记录到 session state
    │
    ├─ 第二步：调用意图工作流 HTTP 服务
    │   FindIntentByWf.request(workflowQuestion)
    │   ├─ 构造请求体 JSON
    │   ├─ HTTP POST → intent-workflow.url
    │   └─ parseResponse() 解析响应
    │       → 返回 agents 列表（每个含 adapter/intent_name/intent_desc）
    │
    ├─ 第三步：候选匹配
    │   遍历每个 agent，调用 DefaultIntentCandidateResolver.resolve()
    │   ├─ 从 l1-pack.yaml 按 adapter + intent 精确匹配
    │   ├─ 匹配到 → 返回包内完整候选卡
    │   └─ 未匹配 → 动态生成候选卡（HIGH 风险）
    │
    └─ 第四步：去重返回
        LinkedHashMap 按 candidate.id() 去重
        → List<CandidateCard>
```

---

## 3. 第一步：会话上下文增强

[IntentConversationHistory](../src/main/java/com/moaagent/agent/conversation/IntentConversationHistory.java) 在 `FindIntentByWf.invoke()` 中被调用：

```java
public Map<String, Object> invoke(Map<String, Object> inputs, Session session) {
    String question = inputs.get("query");                              // 当前用户 query
    String workflowQuestion = IntentConversationHistory.question(session, question);
    Map<String, Object> result = request(workflowQuestion);            // 传增强后的文本给工作流
    IntentConversationHistory.record(session, question, result);       // 保存本轮记录
    return result;
}
```

### question() — 拼接上下文

```java
public static String question(Session session, String query) {
    List<Map<String, Object>> turns = read(session);                    // 从 session state 读取历史
    if (turns.isEmpty()) return query;                                  // 无历史 → 直接返回当前 query

    List<String> lines = new ArrayList<>();
    // 取最近 MAX_TURNS(5) 轮
    for (Map<String, Object> turn : turns.subList(...)) {
        lines.add("用户:" + line(turn.get("query")));                   // 历史用户 query
        lines.add("客服:意图召回结果（非业务执行结果）:" + line(turn.get("result"))); // 历史召回结果
        for (String reply : replies(turn)) {
            lines.add("客服:" + reply);                                 // 历史客服回复
        }
    }
    lines.add("用户:" + line(query));                                   // 当前 query
    return String.join("\n", lines);
}
```

### 示例

假设会话已有 1 轮历史，当前 query 为 `"还款500元"`：

```
用户:先查余额，再还款500元
客服:意图召回结果（非业务执行结果）:{"agents":[{"intent_name":"查询账户余额",...},{"intent_name":"信用卡还款",...}],"total":2}
客服:已为您查询余额，当前余额10000元
用户:还款500元
```

意图工作流能看到上下文，从而判断"还款"指代的是上一轮召回的"信用卡还款"还是其他。

### record() — 保存本轮记录

```java
public static void record(Session session, String query, Map<String, Object> result) {
    if (result.containsKey("error")) return;                           // 失败不记录
    List<Map<String, Object>> turns = read(session);
    turns.add(Map.of("query", query, "result", json(result), "replies", List.of()));
    save(session, turns);                                              // 最多保留 5 轮
}
```

### 数据格式

| 字段 | 类型 | 说明 |
|------|------|------|
| `query` | String | 本轮用户原始请求 |
| `result` | String (JSON) | 本轮召回结果（agents 列表 + total） |
| `replies` | List\<String\> | 后续客服回复（通过 `reply()` 追加） |

存储在 `session.getState("moa_intent_history")` 中，最多保留 5 轮，每行截断 8000 字符。

---

## 4. 第二步：调用意图工作流 HTTP 服务

[FindIntentByWf.request()](../src/main/java/com/moaagent/agent/routing/recall/FindIntentByWf.java#L92) 发送 HTTP POST：

### 请求构造

```java
String body = mapper.writeValueAsString(Map.of(
    "question", workflowQuestion,                                    // 增强后的多行文本
    "sessionId", UUID.randomUUID().toString(),                      // 每次请求生成新 UUID
    "parameters", Map.of("cls", Map.of("curValue", "1001"))));      // 业务线参数
```

### 请求示例

```json
{
  "question": "用户:先查余额，再还款500元\n客服:意图召回结果:{...}\n用户:还款500元",
  "sessionId": "a3f7b2c1-...",
  "parameters": {"cls": {"curValue": "1001"}}
}
```

### HTTP 配置

| 配置项 | 默认值 | 来源 |
|--------|--------|------|
| URL | `http://127.0.0.1:18099/intent` | `moa.intent-workflow.url` |
| Content-Type | `application/json` | `moa.intent-workflow.content-type` |
| Token | （空） | `moa.intent-workflow.token` |
| 连接超时 | 5s | `moa.intent-workflow.connect-timeout` |
| 请求超时 | 30s | `moa.intent-workflow.request-timeout` |
| SSL 验证 | true | `moa.intent-workflow.ssl-verify` |

当 `ssl-verify=false` 时，降级使用 `HttpsURLConnection` + 信任所有证书的 `SSLSocketFactory`。

### 响应解析 — parseResponse()

工作流响应格式：

```json
{
  "code": "0",
  "result": {
    "outParams": {
      "intents": [
        {"predomain": "账户管理", "intent_name": "查询账户余额", "intent_desc": "查询本人账户余额"},
        {"predomain": "信用卡服务", "intent_name": "信用卡还款", "intent_desc": "用本人借记卡还款"}
      ]
    }
  }
}
```

解析逻辑：

```java
// 1. 校验 code == "0"
if (!"0".equals(response.path("code").asText())) → failure("workflow_business_error")

// 2. 提取 intents 数组
JsonNode intents = response.path("result").path("outParams").path("intents");

// 3. 每个 intent 转为 agent map
for (JsonNode intent : intents) {
    String predomain = intent.path("predomain").asText("");          // "账户管理"
    String intentName = intent.path("intent_name").asText("");       // "查询账户余额"
    String description = intent.path("intent_desc").asText("");       // "查询本人账户余额"

    // predomain → adapter 映射
    String adapter = properties.domainAdapters()
        .getOrDefault(predomain.trim(), properties.defaultAdapter());
    // domainAdapters: "[账户管理]" → "versatile-account"
    //                 "[信用卡服务]" → "versatile-creditcard"
    //                 "[转账服务]" → "versatile-transfer"
    //                 "[理财服务]" → "versatile-wealth"
    //                 default → "versatile-general"

    agent.put("adapter", adapter);           // "versatile-account"
    agent.put("intent_name", intentName);    // "查询账户余额"
    agent.put("intent_desc", description);   // "查询本人账户余额"
    agent.put("predomain", predomain);       // "账户管理"
}
```

### 错误处理

| 错误类型 | 触发条件 | 返回 |
|---------|---------|------|
| `invalid_query` | query 为空或非字符串 | `{error, agents:[], total:0}` |
| `workflow_http_error` | HTTP 状态码非 2xx | 同上 |
| `workflow_invalid_response` | 响应非 JSON / 缺 code / intents 非数组 | 同上 |
| `workflow_business_error` | code != "0" | 同上 |
| `workflow_io_error` | IOException | 同上 |
| `workflow_interrupted` | 线程中断 | 同上 |

返回 `error` 字段后，`WorkflowIntentRecall.recall()` 会抛 `IllegalStateException`，被 `IntentRouter` 捕获后返回 `Decision(fast=false, candidates=[], reason="analysis_unavailable")`，路由层终止并提示用户重试。

---

## 5. 第三步：候选匹配 — DefaultIntentCandidateResolver

[DefaultIntentCandidateResolver](../src/main/java/com/moaagent/agent/routing/recall/resolution/DefaultIntentCandidateResolver.java) 把工作流返回的抽象意图映射为候选包中的可执行候选卡：

```java
public List<CandidateCard> resolve(RecalledIntent intent, CandidateResolutionContext context) {
    // 从 l1-pack.yaml 中按 adapter + intent 双字段精确匹配
    CandidateCard candidate = context.catalog().cards().stream()
            .filter(known -> known.adapter().equals(intent.adapter())      // "versatile-account"
                    && known.intent().equals(intent.intentName()))          // "查询账户余额"
            .findFirst()
            // 未匹配到 → 动态生成候选
            .orElseGet(() -> new CandidateCard(
                    CandidateCard.dynamicId(intent.adapter(), intent.intentName()),
                    intent.name(),            // 工作流返回的 intent_name
                    intent.description(),     // 工作流返回的 intent_desc
                    List.of(context.query()), // utterances = 当前 query
                    List.of(intent.intentName()),  // keywords = intent 名称
                    intent.adapter(),        // adapter
                    intent.intentName(),     // intent
                    CandidateCard.Risk.HIGH, // 动态候选一律 HIGH
                    null));                  // 无 resource
    return List.of(candidate);
}
```

### 匹配逻辑

**双字段精确匹配**：`adapter` + `intent` 必须完全一致。

工作流返回的 intent 经过 `FindIntentByWf` 转换后：
- `adapter`：由 `predomain` 通过 `domainAdapters` 映射得到
- `intentName`：直接取工作流的 `intent_name` 字段

在 `l1-pack.yaml` 中：
```yaml
versatile: { adapter: versatile-account, intent: 查询账户余额 }
```

只有 `adapter="versatile-account"` 且 `intent="查询账户余额"` 都匹配，才会返回该候选卡。

### 场景示例

#### 示例 1：精确匹配

工作流返回：
```json
{"predomain": "账户管理", "intent_name": "查询账户余额", "intent_desc": "查询本人账户余额"}
```

→ `adapter="versatile-account"`, `intent="查询账户余额"`

匹配 `l1-pack.yaml` 中的 `cap.account.balance.query`：

```yaml
- id: cap.account.balance.query
  name: 查询账户余额
  description: 查询本人名下借记卡、活期账户的可用余额与账户余额
  utterances: [查一下余额, 我的余额是多少, 卡里还有多少钱, 帮我查下存款]
  keywords: [余额, 存款, 账户, 多少钱]
  risk: LOW
  versatile: { adapter: versatile-account, intent: 查询账户余额 }
  outputs:
    available_balance: { path: /bankCardBalanceList/*/currencyBalanceList/*/balance, type: DECIMAL, unit: CNY }
```

返回包内完整候选卡。比工作流的抽象意图丰富了：`keywords`（二次召回打分）、`utterances`（精确匹配）、`outputs`（条件执行契约）、`risk`（LOW）。

#### 示例 2：未匹配，动态生成

假设工作流返回了一个 `l1-pack.yaml` 中没有的新意图：

```json
{"predomain": "账户管理", "intent_name": "账户挂失", "intent_desc": "冻结本人账户"}
```

→ `adapter="versatile-account"`, `intent="账户挂失"`，候选包中无此组合。

动态生成：

```java
CandidateCard(
  id = "cap.dynamic.<nameUUID>",       // 基于 "versatile-account" + "账户挂失" 的确定性 UUID
  name = "账户挂失",
  description = "冻结本人账户",
  utterances = ["当前query"],           // 只有用户当前输入
  keywords = ["账户挂失"],             // 只有 intent 名称
  adapter = "versatile-account",
  intent = "账户挂失",
  risk = HIGH,                         // 动态候选一律 HIGH 风险
  resource = null,                     // 无 skill 资源
  outputs = Map.of()                   // 无结果契约
)
```

动态候选仍能委派到 L2（adapter 已确定），但 HIGH 风险会触发 `stage_finalize` 的 `plan_confirmation` 中断。

#### 示例 3：多意图各匹配

工作流返回两个 intent：

```json
[
  {"predomain": "账户管理", "intent_name": "查询账户余额", "intent_desc": "查余额"},
  {"predomain": "信用卡服务", "intent_name": "信用卡还款", "intent_desc": "还信用卡"}
]
```

- intent 1 → `adapter="versatile-account"`, `intent="查询账户余额"` → 匹配 `cap.account.balance.query`
- intent 2 → `adapter="versatile-creditcard"`, `intent="信用卡还款"` → 候选包中 `cap.creditcard.repay` 的 `adapter="versatile-account"`，不匹配 → 动态生成

### 包内候选 vs 动态候选对比

| 属性 | 包内候选 | 动态候选 |
|------|---------|---------|
| id | 人工定义（如 `cap.account.balance.query`） | `cap.dynamic.<UUID>` |
| keywords | 人工标注多个 | 只有 intent 名称一个 |
| utterances | 人工标注多个 | 只有当前 query 一个 |
| risk | 人工标注 LOW/HIGH | 一律 HIGH |
| outputs | 有结果契约（路径、类型、单位、校验规则） | 无 |
| 二次召回打分 | keyword 覆盖率高 | 通常只能完全匹配才得分 |
| resource | SKILL 类型有下载资源 | 无 |

---

## 6. 第四步：去重返回

[WorkflowIntentRecall.recall()](../src/main/java/com/moaagent/agent/routing/recall/WorkflowIntentRecall.java#L44-L74) 用 `LinkedHashMap` 去重：

```java
Map<String, CandidateCard> candidates = new LinkedHashMap<>();
for (Object item : agents) {
    // ... 解析 + resolve ...
    for (CandidateCard candidate : resolved) {
        candidates.putIfAbsent(candidate.id(), candidate);  // 按 id 去重，保留首次
    }
}
// 日志记录
LOGGER.info("[MOA-MATCH] rawCount={} candidateCount={} skippedEmptyIntent={} filteredIntentCount={} matches={} elapsedMs={}",
        agents.size(), candidates.size(), skippedEmptyIntent, filteredIntentCount, ...);
return List.copyOf(candidates.values());
```

### 去重场景

| 情况 | 说明 |
|------|------|
| 工作流返回重复 intent | 同一 adapter+intent 出现多次 → `putIfAbsent` 只保留首次 |
| 不同 predomain 映射到同一 adapter | 两个 intent 的 adapter 相同但 intent 不同 → 候选 id 不同，都保留 |
| intent 为空 | `"null".equals(intent)` → `skippedEmptyIntent++`，跳过 |

---

## 7. 测试环境差异

测试中 `IntentRecall` 被 `@Primary` Bean 覆盖：

```java
@Bean
@Primary
IntentRecall testIntentRecall() {
    return (query, session) -> {
        if (query.equals("先查下余额，再转50元给李四")) {
            // 直接返回 l1-pack.yaml 中的候选，跳过工作流和 resolver
            return CandidatePack.loadFromClasspath("candidates/l1-pack.yaml").cards().stream()
                    .filter(card -> List.of("cap.account.balance.query", "cap.transfer").contains(card.id()))
                    .toList();
        }
        // ...其他硬编码分支...
        return List.of();
    };
}
```

| 维度 | 生产环境 | 测试环境 |
|------|---------|---------|
| 召回方式 | HTTP 调用意图工作流 | 内存硬编码按 query 匹配 |
| 上下文增强 | IntentConversationHistory 拼接5轮 | 无 |
| 候选匹配 | DefaultIntentCandidateResolver 按 adapter+intent 匹配 | 直接返回包内候选 |
| 动态候选 | 支持（工作流返回未知意图时） | 不支持（直接返回空或预定义） |
| 超时保护 | IntentRouter 线程池 5s 超时 | 无 |
| 错误降级 | 工作流失败 → analysis_unavailable | 无 |

---

## 8. 关键设计总结

| 设计点 | 说明 |
|--------|------|
| **5 轮对话上下文** | 意图工作流能看到历史 query 和召回结果，支持指代消解（如"还款"指代上一轮的候选） |
| **adapter + intent 双匹配** | 精确字段匹配，确保意图到候选的确定性映射 |
| **动态候选兜底** | 工作流返回的未知意图不被丢弃，动态生成 HIGH 风险候选，仍可委派到 L2 |
| **IntentConversationHistory** | 记录在 session state 中，跨轮次共享，最多 5 轮 |
| **LinkedHashMap 去重** | 按 candidate.id() 去重，保留首次出现，顺序稳定 |
| **错误降级** | 工作流 HTTP 失败 → `error` 字段 → `IllegalStateException` → `analysis_unavailable` → 终止并提示重试 |
| **SSL 降级** | `ssl-verify=false` 时用 `HttpsURLConnection` + 信任所有证书，适配自签名证书环境 |

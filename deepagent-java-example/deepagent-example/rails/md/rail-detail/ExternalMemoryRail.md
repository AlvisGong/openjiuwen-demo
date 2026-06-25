# ExternalMemoryRail 详细解读

## 一、核心定位

ExternalMemoryRail 是 DeepAgent Harness 层的**外部长期记忆接入 Rail**，负责将外部记忆服务（OpenJiuwen / Mem0 / OpenViking）的能力桥接到 Agent 的执行生命周期中，实现**跨会话记忆的自动预取（prefetch）和同步（syncTurn）**。

| 属性 | 值 |
|------|---|
| 继承 | MemoryRail → DeepAgentRail → AgentRail |
| 优先级 | 75 |
| 配置名 | `external_memory` |
| 核心能力 | prefetch 预取 + syncTurn 同步 + 工具注册 + System Prompt 注入 |

---

## 二、类结构

```
ExternalMemoryRail extends MemoryRail
  │
  ├── 核心字段
  │     provider: MemoryProvider          ← 外部记忆服务提供者
  │     userId: String                    ← 用户标识（隔离维度 1）
  │     scopeId: String                   ← 作用域标识（隔离维度 2）
  │     sessionId: String                 ← 会话标识（隔离维度 3）
  │     isInitialized: boolean            ← provider 是否已初始化
  │     prefetchCache: String             ← prefetch 结果缓存（避免重复调用）
  │     syncFailures: int                 ← syncTurn 连续失败次数
  │     ownedTools: List<Tool>            ← 本 Rail 注册的工具
  │     ownedToolNames: Set<String>       ← 本 Rail 注册的工具名
  │
  ├── Prompt Section
  │     "external_memory" (priority=54)   ← 静态提示（provider.systemPromptBlock()）
  │     "external_memory_prefetch" (priority=55) ← 动态提示（prefetch 结果）
  │
  └── 生命周期钩子
        init()              → 注册 provider 工具 + 注入静态提示
        uninit()            → 注销工具 + 移除提示 + 关闭 provider
        beforeInvoke()      → 初始化 provider
        beforeModelCall()   → prefetch 预取 + 注入 <memory-context>
        afterInvoke()       → syncTurn 同步本轮对话
```

---

## 三、MemoryProvider 接口

ExternalMemoryRail 的所有记忆操作都委托给 `MemoryProvider` 接口：

```java
public interface MemoryProvider {
    String getName();                                    // 提供者名称
    boolean isAvailable();                               // 是否可用
    void initialize(Map<String, Object> kwargs);         // 初始化
    List<Map<String, Object>> getToolSchemas();          // 工具 schema 列表
    String handleToolCall(String toolName, Map args);    // 处理工具调用
    String prefetch(String query, Map<String, Object> kwargs);  // 预取相关记忆
    void syncTurn(String userMsg, String assistantMsg, Map kwargs); // 同步对话
    default String systemPromptBlock() { return ""; }    // 静态提示块
    default void shutdown() {}                           // 关闭
    default void onSessionEnd(List<Map> messages) {}     // 会话结束回调
    default boolean isInitialized() { return false; }    // 是否已初始化
}
```

### 三种内置 Provider

| Provider | 名称 | 工具 | prefetch 策略 | syncTurn 策略 |
|----------|------|------|-------------|-------------|
| **OpenJiuwenMemoryProvider** | `openjiuwen` | `ltm_search`, `ltm_search_summary` | 向量检索用户记忆 + 历史摘要 | 将对话消息添加到 LongTermMemory |
| **Mem0MemoryProvider** | `mem0` | `mem0_profile`, `mem0_search`, `mem0_conclude` | Mem0 API 搜索 | Mem0 API add（自动推断记忆） |
| **OpenVikingMemoryProvider** | `openviking` | `viking_search`, `viking_read`, `viking_browse`, `viking_remember`, `viking_add_resource` | Viking API 搜索 | 追加会话消息到 Viking |

---

## 四、生命周期详解

### 4.1 init() — 注册工具 + 注入静态提示

```java
public void init(Object agent) {
    // 1. 注册 provider 提供的工具到 Agent
    registerProviderTools(deepAgent);
    // 2. 注入 provider 的静态提示到 System Prompt
    injectStaticProviderPrompt();
}
```

**registerProviderTools()** 的流程：

```
provider.getToolSchemas()  →  遍历每个 schema
  → 构建 ToolCard（id="external_memory_{providerName}_{toolName}"）
  → 创建 LocalFunction（调用 invokeProviderTool()）
  → deepAgent.registerHarnessTool(tool)
  → 记录到 ownedTools / ownedToolNames
```

**injectStaticProviderPrompt()** 的流程：

```
provider.systemPromptBlock()  →  获取静态提示文本
  → 添加 PromptSection("external_memory", priority=54)
```

以 OpenJiuwenMemoryProvider 为例，注入的静态提示：

```
# Long-Term Memory System
Use ltm_search to search long-term memory and ltm_search_summary to recall history summaries.
```

### 4.2 beforeInvoke() — 初始化 Provider

```java
public void beforeInvoke(AgentCallbackContext ctx) {
    prefetchCache = null;           // 清除 prefetch 缓存
    if (!isInitialized) {
        provider.initialize(providerScope());  // 传入 {user_id, scope_id, session_id}
        isInitialized = true;
    }
}
```

**providerScope()** 提供三维度隔离：

```java
Map.of(
    "user_id", userId,       // 用户级隔离
    "scope_id", scopeId,     // 作用域级隔离（如不同业务线）
    "session_id", sessionId  // 会话级隔离
);
```

### 4.3 beforeModelCall() — prefetch 预取

这是 ExternalMemoryRail 最核心的能力：**在每次 LLM 调用前，自动从长期记忆中检索与当前 query 相关的内容，注入到上下文中**。

```java
public void beforeModelCall(AgentCallbackContext ctx) {
    // 1. 移除上一轮的 prefetch section
    owner.getAgent().getPromptBuilder().removeSection(PREFETCH_SECTION);

    // 2. 解析当前用户 query
    String query = resolveUserText(ctx);

    // 3. 调用 provider.prefetch()（有缓存则用缓存）
    String rawContext = prefetchCache != null
        ? prefetchCache
        : provider.prefetch(query, providerScope());
    prefetchCache = rawContext;

    // 4. 构建 <memory-context> 块
    String content = buildMemoryContextBlock(rawContext);

    // 5. 注入到 System Prompt（PromptSection）
    owner.getAgent().getPromptBuilder()
        .addSection(new PromptSection(PREFETCH_SECTION, ..., priority=55));

    // 6. 注入到 messages（SystemMessage）
    injectSystemMessage(ctx, content);
}
```

**buildMemoryContextBlock()** 生成的格式：

```
<memory-context>
[System note: recalled memory context from long-term memory, NOT new user input.]

## Related Memories
- [USER_PROFILE] 用户张三，偏好中文交流 (score: 0.85)
- [VARIABLE] 项目名=星河计划 (score: 0.72)

## Related History Summaries
- 上次讨论了融资方案，生成了v2版本 (score: 0.68)
</memory-context>
```

**关键设计**：
- `[System note: ...]` 标注这是记忆召回，不是用户新输入，防止 LLM 误判
- prefetch 结果有缓存（`prefetchCache`），同一 invoke 内多次 model call 不重复调用
- prefetch 是**机会性的**（opportunistic），失败不阻塞执行

### 4.4 afterInvoke() — syncTurn 同步

```java
public void afterInvoke(AgentCallbackContext ctx) {
    // 1. 连续失败 5 次则停止同步
    if (syncFailures >= 5) return;

    // 2. 提取用户 query 和 Agent 输出
    String query = resolveUserText(ctx);
    String output = extractAssistantOutput(ctx);

    // 3. 调用 provider.syncTurn() 同步到外部记忆
    provider.syncTurn(query, output, providerScope());
    syncFailures = 0;  // 成功则重置失败计数
}
```

**syncTurn 的作用**：将本轮对话（用户问题 + Agent 回答）同步到外部记忆服务，让记忆系统从中提取和更新用户画像、变量、摘要等。

**容错设计**：
- syncTurn 失败不抛异常，只累加 `syncFailures`
- 连续失败 5 次后自动停止同步，避免无效调用
- Mem0MemoryProvider 还有独立的**熔断器**（consecutiveFailures ≥ 5 → breakerOpen 120秒）

### 4.5 uninit() — 清理

```java
public void uninit(Object agent) {
    // 1. 注销所有注册的工具
    for (Tool tool : ownedTools) {
        deepAgent.unregisterHarnessTool(tool);
    }
    // 2. 移除 Prompt Section
    deepAgent.getAgent().getPromptBuilder().removeSection("external_memory");
    deepAgent.getAgent().getPromptBuilder().removeSection("external_memory_prefetch");
    // 3. 关闭 provider
    provider.shutdown();
    // 4. 清空状态
    ownedTools.clear();
    ownedToolNames.clear();
    isInitialized = false;
    prefetchCache = null;
}
```

---

## 五、三种 Provider 详解

### 5.1 OpenJiuwenMemoryProvider

基于 OpenJiuwen 自研的 LongTermMemory 引擎，**无需外部 API 调用**，直接使用本地向量库。

| 能力 | 实现 |
|------|------|
| **工具** | `ltm_search`（搜索用户记忆）、`ltm_search_summary`（搜索历史摘要） |
| **prefetch** | 同时检索用户记忆（top 5）+ 历史摘要（top 3），阈值 0.3 |
| **syncTurn** | 将 UserMessage + AssistantMessage 添加到 LongTermMemory，由 LTM 自动提取记忆 |
| **后端** | LongTermMemory 单例 → FragmentMemoryManager / VariableManager / SummaryManager |
| **存储** | 向量库（Milvus/PGVector）+ KV 存储（Redis/NOS）+ SQL |

**prefetch 输出示例**：

```
## Related Memories
- [USER_PROFILE] 用户张三，偏好简洁风格 (score: 0.85)
- [VARIABLE] 融资方案版本=v2 (score: 0.72)
- [EPISODIC_MEMORY] 2026-06-24 讨论了对公贷款政策 (score: 0.65)

## Related History Summaries
- 用户询问融资方案，Agent 生成了v2版本 (score: 0.68)
```

**ltm_search 工具参数**：

```json
{
  "name": "ltm_search",
  "parameters": {
    "query": "搜索查询内容",
    "num": 5,
    "threshold": 0.3
  }
}
```

### 5.2 Mem0MemoryProvider

基于 [Mem0](https://mem0.ai) 云端记忆服务，通过 REST API 交互。

| 能力 | 实现 |
|------|------|
| **工具** | `mem0_profile`（获取全部记忆）、`mem0_search`（语义搜索）、`mem0_conclude`（存储事实） |
| **prefetch** | 调用 `/v3/memories/search/` API，top 5 |
| **syncTurn** | 调用 `/v3/memories/add/` API，传入对话消息，Mem0 自动推断记忆 |
| **熔断器** | consecutiveFailures ≥ 5 → breakerOpen 120 秒 |

**配置参数**：

```yaml
provider: mem0
api_key: "your-mem0-api-key"
user_id: "user123"
agent_id: "agent456"
rerank: true
base_url: "https://api.mem0.ai"  # 可自定义
```

**mem0_conclude 工具**：Agent 可主动调用存储事实，如"用户偏好函数式编程风格"。

### 5.3 OpenVikingMemoryProvider

基于华为 OpenViking 知识库服务，支持**全域搜索 + URI 读取 + 知识库浏览 + 显式记忆 + 资源索引**。

| 能力 | 实现 |
|------|------|
| **工具** | `viking_search`、`viking_read`、`viking_browse`、`viking_remember`、`viking_add_resource` |
| **prefetch** | 调用 Viking `/api/v1/search/find` API，top 5 |
| **syncTurn** | 通过 `appendSessionMessage` 追加对话到 Viking 会话 |
| **搜索模式** | auto（自动）、fast（快速）、deep（深度） |
| **读取级别** | abstract（摘要）、overview（概览）、full（全文） |

**viking_search 工具参数**：

```json
{
  "name": "viking_search",
  "parameters": {
    "query": "搜索查询词",
    "mode": "auto",
    "top_k": 10
  }
}
```

**viking_read 工具参数**：

```json
{
  "name": "viking_read",
  "parameters": {
    "uri": "viking://knowledge/loan-policy",
    "detail": "overview"
  }
}
```

**viking_remember 工具**：显式存储事实，支持分类（preference/entity/event/case/pattern）。

---

## 六、配置方式

### 6.1 YAML 配置

在 DeepAgent 的 harness 配置文件中：

```yaml
rails:
  external_memory:
    provider: openjiuwen       # openjiuwen / mem0 / openviking
    user_id: "user_123"
    scope_id: "finance"
    session_id: "session_456"
    # OpenJiuwen 特有配置
    agent_memory_config:
      ...
    # Mem0 特有配置
    api_key: "your-mem0-api-key"
    agent_id: "my-agent"
    rerank: true
    # OpenViking 特有配置
    endpoint: "https://viking.example.com"
    account: "my-account"
```

### 6.2 代码配置

```java
// 方式 1：使用 OpenJiuwen 自研记忆
MemoryProvider provider = new OpenJiuwenMemoryProvider();
ExternalMemoryRail rail = new ExternalMemoryRail(provider, "user_123", "finance", "session_456");
deepAgent.registerRail(rail);

// 方式 2：使用 Mem0 云端记忆
MemoryProvider provider = new Mem0MemoryProvider("api-key", "user_123", "agent_456", true);
ExternalMemoryRail rail = new ExternalMemoryRail(provider, "user_123", "finance", "session_456");
deepAgent.registerRail(rail);

// 方式 3：使用 OpenViking 知识库
MemoryProvider provider = new OpenVikingMemoryProvider(
    "https://viking.example.com", "api-key", "account", "user", "agent");
ExternalMemoryRail rail = new ExternalMemoryRail(provider, "user_123", "finance", "session_456");
deepAgent.registerRail(rail);
```

### 6.3 HarnessConfigBuilder 自动创建

HarnessConfigBuilder 根据配置文件自动创建 ExternalMemoryRail：

```java
private static ExternalMemoryRail createExternalMemoryRail(Path root, RailResourceSchema spec) {
    Map<String, Object> config = railConfig(spec);
    MemoryProvider provider = createMemoryProvider(config);
    return new ExternalMemoryRail(
        provider,
        stringValue(firstPresent(config, {"user_id", "userId"}), "__default__"),
        stringValue(firstPresent(config, {"scope_id", "scopeId"}), "__default__"),
        stringValue(firstPresent(config, {"session_id", "sessionId"}), "__default__")
    );
}

private static MemoryProvider createMemoryProvider(Map<String, Object> config) {
    String providerName = stringValue(firstPresent(config, {"provider", "provider_name"}), "");
    return switch (providerName.toLowerCase()) {
        case "openjiuwen", "jiuwen", "default" -> new OpenJiuwenMemoryProvider(...);
        case "mem0" -> new Mem0MemoryProvider();
        case "openviking", "viking" -> new OpenVikingMemoryProvider();
        default -> throw new IllegalArgumentException("Unknown provider: " + providerName);
    };
}
```

---

## 七、与 MemoryRail 的区别

| 维度 | MemoryRail（内置轻量记忆） | ExternalMemoryRail（外部长期记忆） |
|------|--------------------------|--------------------------------|
| **存储位置** | 本地文件 + Embedding 索引 | 外部服务（向量库 / 云端 API） |
| **记忆范围** | 会话内 | 跨会话 |
| **工具** | `memory_search`/`memory_get`/`write_memory`/`edit_memory`/`read_memory` | 由 Provider 决定（如 `ltm_search`/`mem0_search`/`viking_search`） |
| **prefetch** | 无 | 有（beforeModelCall 自动预取） |
| **syncTurn** | 无 | 有（afterInvoke 自动同步） |
| **优先级** | 80 | 75（比 MemoryRail 先执行） |
| **适用场景** | 会话内知识片段存储/检索 | 跨会话用户画像/历史偏好/长期知识 |

---

## 八、完整执行流程示例

```
用户（第二次访问）："帮我出一份融资方案"

┌─ beforeInvoke() ─────────────────────────────────────────────┐
│  provider.initialize({user_id: "zhangsan", scope_id: "finance"})│
│  → 连接外部记忆服务                                            │
└──────────────────────────────────────────────────────────────┘
                          ↓
┌─ beforeModelCall() ──────────────────────────────────────────┐
│  query = "帮我出一份融资方案"                                    │
│  provider.prefetch("帮我出一份融资方案", {user_id: "zhangsan"})  │
│  → 检索到：                                                    │
│    - 用户画像：偏好简洁风格                                      │
│    - 变量：项目名=星河计划                                       │
│    - 历史摘要：上次讨论了融资方案v1                               │
│                                                               │
│  注入 <memory-context> 块到 System Prompt：                     │
│  <memory-context>                                             │
│  [System note: recalled from long-term memory, NOT new input] │
│  ## Related Memories                                          │
│  - [USER_PROFILE] 偏好简洁风格 (score: 0.85)                    │
│  - [VARIABLE] 项目名=星河计划 (score: 0.72)                     │
│  ## Related History Summaries                                 │
│  - 上次讨论了融资方案v1 (score: 0.68)                            │
│  </memory-context>                                            │
└──────────────────────────────────────────────────────────────┘
                          ↓
┌─ LLM 调用 ───────────────────────────────────────────────────┐
│  LLM 看到的上下文：                                             │
│  System Prompt + <memory-context> + 用户 query                 │
│  → LLM 知道用户偏好简洁风格，项目叫星河计划，上次讨论过v1          │
│  → 生成更精准的融资方案v2                                        │
└──────────────────────────────────────────────────────────────┘
                          ↓
┌─ afterInvoke() ──────────────────────────────────────────────┐
│  query = "帮我出一份融资方案"                                    │
│  output = "根据星河计划的需求，融资方案v2如下..."                   │
│  provider.syncTurn(query, output, {user_id: "zhangsan"})      │
│  → 将本轮对话同步到外部记忆                                      │
│  → LongTermMemory 自动提取：                                    │
│    - 更新变量：融资方案版本=v2                                    │
│    - 新增情景记忆：2026-06-25 生成了融资方案v2                     │
└──────────────────────────────────────────────────────────────┘
```

---

## 九、使用场景

### 场景 1：金融顾问 Agent — 跨会话记住用户画像

```
第一次会话：
  用户："我叫张三，偏好简洁风格"
  → syncTurn → 记忆系统存储：USER_PROFILE(张三, 偏好简洁)

第二次会话：
  用户："帮我出一份融资方案"
  → prefetch → 召回"偏好简洁风格"
  → LLM 生成简洁版融资方案（无需用户重复说明偏好）
```

### 场景 2：客服 Agent — 记住历史交互

```
用户："我的订单到哪了？"
  → prefetch → 召回"用户ID=U123, 最近订单=O456"
  → Agent 直接查询订单 O456，不用再问用户信息

用户（下次）："上次那个订单呢？"
  → prefetch → 召回"上次询问了订单O456"
  → Agent 直接回答，无需用户重复描述
```

### 场景 3：代码助手 — 记住项目约定

```
第一次会话：
  用户："这个项目用函数式风格，禁止 var 关键字"
  → syncTurn → 记忆系统存储：VARIABLE(coding_style=functional, no_var=true)

后续会话：
  用户："重构 X 模块"
  → prefetch → 召回"coding_style=functional, no_var=true"
  → Agent 生成的代码自动遵循函数式风格，不使用 var
```

### 场景 4：知识问答 — Viking 知识库检索

```
用户："公司差旅报销标准是什么？"
  → prefetch → Viking 搜索 → 返回"差旅报销规范 v3.2"相关段落
  → Agent 基于知识库内容回答，而非依赖 LLM 自身知识
```

### 场景 5：多用户隔离 — 同一 Agent 服务不同用户

```
用户 A（user_id=A）："记住我的偏好是详细风格"
  → syncTurn → 存储到 user_id=A 的记忆空间

用户 B（user_id=B）："帮我出一份方案"
  → prefetch → 从 user_id=B 的记忆空间检索
  → 不会混入用户 A 的偏好
```

---

## 十、设计要点总结

| 设计要点 | 实现方式 |
|---------|---------|
| **Provider 抽象** | MemoryProvider 接口统一三种后端，ExternalMemoryRail 不感知具体实现 |
| **自动 prefetch** | beforeModelCall 自动检索，无需 Agent 主动调用工具 |
| **自动 syncTurn** | afterInvoke 自动同步，无需 Agent 主动调用工具 |
| **三维度隔离** | user_id + scope_id + session_id 实现用户/业务/会话级隔离 |
| **记忆标注** | `<memory-context>` + `[System note: NOT new user input]` 防止 LLM 误判 |
| **prefetch 缓存** | 同一 invoke 内多次 model call 复用 prefetch 结果 |
| **容错设计** | prefetch 失败不阻塞；syncTurn 连续失败 5 次自动停止；Mem0 有独立熔断器 |
| **工具自动注册** | provider.getToolSchemas() → 自动注册到 Agent，无需硬编码 |
| **提示自动注入** | provider.systemPromptBlock() → 静态提示；prefetch 结果 → 动态提示 |
| **可插拔** | 切换 Provider 只需改配置（openjiuwen/mem0/openviking），无需改代码 |

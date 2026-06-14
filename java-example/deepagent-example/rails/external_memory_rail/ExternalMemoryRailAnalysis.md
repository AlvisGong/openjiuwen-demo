# ExternalMemoryRail 解读

## 1. 功能概述

`ExternalMemoryRail` 是 DeepAgent 框架中负责 **外部长期记忆管理** 的护栏（Rail）组件。它继承自 `MemoryRail`，通过 `MemoryProvider` 接口将外部记忆服务（如 Mem0、OpenJiuwen LTM 等）接入 Agent，使 Agent 具备跨会话的长期记忆能力。

核心能力：

- **自动记忆召回（Prefetch）**：每次 LLM 调用前，根据用户查询自动从外部记忆服务召回相关内容，注入上下文
- **自动对话同步（SyncTurn）**：每次对话结束后，将用户消息和 Agent 回复自动同步到外部记忆服务
- **工具注册**：将 MemoryProvider 提供的工具（如 `ltm_search`、`ltm_save`、`mem0_search` 等）注册为 Agent 可调用的工具
- **系统提示注入**：将 MemoryProvider 提供的系统提示块注入到 Agent 的 Prompt 中，告知 LLM 可用记忆能力

## 2. 类继承关系

```
AgentRail (核心框架)
  └── DeepAgentRail (抽象层，提供 init/uninit/priority)
        └── MemoryRail (内置记忆，基于本地 LiteMemory)
              └── ExternalMemoryRail (外部记忆，基于 MemoryProvider 接口)
```

`ExternalMemoryRail` 与父类 `MemoryRail` 的区别：
- `MemoryRail` 使用 **本地 LiteMemory**（文件/向量索引），工具固定为 `memory_search`、`memory_get`、`write_memory` 等
- `ExternalMemoryRail` 使用 **外部 MemoryProvider**，工具由 Provider 动态定义，支持 Mem0、OpenJiuwen LTM 等第三方记忆服务

## 3. 生命周期回调

`ExternalMemoryRail` 实现了 AgentRail 的四个关键回调，形成完整的记忆闭环：

### 3.1 `init(agent)` — 初始化

在 Agent 初始化阶段执行：
1. 保存 `DeepAgent` 引用（`this.owner = deepAgent`）
2. 将 MemoryProvider 提供的工具注册到 Agent（`registerProviderTools`）
3. 将 Provider 的系统提示块注入到 PromptBuilder（`injectStaticProviderPrompt`）

### 3.2 `beforeInvoke(ctx)` — 调用前

在每次 Agent invoke 前执行：
- 惰性初始化 MemoryProvider（调用 `provider.initialize(providerScope())`）
- 清除 prefetch 缓存（`prefetchCache = null`）

### 3.3 `beforeModelCall(ctx)` — 模型调用前（核心）

在每次 LLM 调用前执行，这是 **自动记忆召回** 的核心逻辑：
1. 移除上一次的 prefetch PromptSection（避免累积）
2. 从回调上下文提取用户查询文本
3. 调用 `provider.prefetch(query, providerScope())` 获取相关记忆内容
4. 将召回内容封装为 `<memory-context>` XML 标签块
5. 注入到 PromptBuilder（作为 `external_memory_prefetch` Section）
6. 同时通过 `injectSystemMessage()` 注入到对话消息中

记忆上下文格式：
```xml
<memory-context>
[System note: recalled memory context from long-term memory, NOT new user input.]

...（从外部记忆服务召回的内容）...
</memory-context>
```

关键设计：`prefetchCache` 缓存机制，避免同一轮中重复调用 prefetch。

### 3.4 `afterInvoke(ctx)` — 调用后

在每次 Agent invoke 完成后执行，这是 **自动对话同步** 的逻辑：
1. 提取用户查询文本和 Agent 回复内容
2. 调用 `provider.syncTurn(query, output, providerScope())` 将对话同步到外部记忆
3. 失败容错：连续失败 5 次后停止同步（`syncFailures >= 5`），避免反复重试拖慢 Agent

### 3.5 `uninit(agent)` — 反初始化

在 Agent 销毁阶段执行：
1. 反注册所有已注册的工具
2. 移除注入的 PromptSection（`external_memory` 和 `external_memory_prefetch`）
3. 调用 `provider.shutdown()` 关闭外部服务连接
4. 清空所有内部状态

## 4. 工具注册机制

`registerProviderTools()` 从 `provider.getToolSchemas()` 动态获取工具定义，为每个工具：

- 生成工具 ID：`external_memory_{providerName}_{toolName}`
- 构建 `ToolCard`（名称、描述、参数 Schema）
- 创建 `LocalFunction`，调用逻辑委托给 `provider.handleToolCall(toolName, inputs)`
- 通过 `deepAgent.registerHarnessTool()` 注册到 Agent

不同 Provider 提供的工具示例：

| Provider | 工具 |
|----------|------|
| `Mem0MemoryProvider` | `mem0_search`、`mem0_profile`、`mem0_conclude` |
| `OpenJiuwenMemoryProvider` | `ltm_search`、`ltm_search_summary` |
| `LocalFileMemoryProvider` | `ltm_search`、`ltm_save` |

## 5. 记忆隔离维度

`ExternalMemoryRail` 构造函数接受三个隔离维度：

```java
public ExternalMemoryRail(MemoryProvider provider, String userId, String scopeId, String sessionId)
```

| 参数 | 含义 | 默认值 |
|------|------|--------|
| `userId` | 用户维度隔离，不同用户拥有独立记忆空间 | `__default__` |
| `scopeId` | 项目/空间维度隔离，同一用户的不同项目记忆独立 | `__default__` |
| `sessionId` | 会话维度隔离 | `__default__` |

这三个维度组合为 `providerScope()` Map，传递给 Provider 的所有方法调用：
```java
Map.of("user_id", userId, "scope_id", scopeId, "session_id", sessionId)
```

## 6. MemoryProvider 接口

`ExternalMemoryRail` 的外部记忆能力完全委托给 `MemoryProvider` 接口：

```java
public interface MemoryProvider {
    String getName();                                    // Provider 名称
    boolean isAvailable();                               // 是否可用
    void initialize(Map<String, Object> kwargs);         // 初始化
    List<Map<String, Object>> getToolSchemas();          // 工具 Schema 列表
    String handleToolCall(String toolName, Map args);    // 处理工具调用
    String prefetch(String query, Map kwargs);           // 预取相关记忆
    void syncTurn(String userMsg, String assistantMsg, Map kwargs); // 同步对话轮次
    String systemPromptBlock();                          // 系统提示块（默认空）
    void shutdown();                                     // 关闭（默认空）
    void onSessionEnd(List<Map> messages);               // 会话结束回调（默认空）
}
```

框架内置三种 Provider 实现：

| Provider | 说明 |
|----------|------|
| `Mem0MemoryProvider` | 基于 Mem0 REST API，需 API Key，支持搜索/存储/用户画像 |
| `OpenJiuwenMemoryProvider` | 基于 OpenJiuwen 内部长期记忆服务 |
| `LocalFileMemoryProvider`（示例） | 本地文件记忆，适合测试和简单场景 |

## 7. PromptSection 优先级

`ExternalMemoryRail` 注入两个 PromptSection，优先级设计确保记忆内容在系统提示中处于合理位置：

| Section | 名称 | 优先级 | 内容 |
|---------|------|--------|------|
| 静态提示 | `external_memory` | 54 | Provider 的 `systemPromptBlock()`（如 "Mem0 Memory Active. Use mem0_search..."） |
| 动态召回 | `external_memory_prefetch` | 55 | 每轮 prefetch 的 `<memory-context>` 块 |

与父类 `MemoryRail` 的 Section（名称 `memory`，优先级 55）不冲突。

## 8. 使用场景

### 8.1 跨会话个性化记忆

用户在多轮对话中告诉 Agent 个人信息（姓名、偏好、项目背景等），Agent 通过 `syncTurn` 自动保存，后续会话通过 `prefetch` 自动召回，实现"记住用户"的能力。

### 8.2 项目知识积累

在软件开发场景中，Agent 可积累项目决策、架构偏好、代码规范等知识，跨会话复用。

### 8.3 对话历史检索

通过 `prefetch` 在每次 LLM 调用前自动检索历史相关对话，减少信息丢失。

### 8.4 第三方记忆服务集成

通过 `MemoryProvider` 接口对接企业内部记忆服务或第三方 SaaS（如 Mem0），实现企业级记忆管理。

## 9. 如何使用

### 9.1 基本用法

```java
// 1. 创建 MemoryProvider
MemoryProvider provider = new Mem0MemoryProvider("your-api-key", "user-1", "agent-1", false);

// 2. 创建 ExternalMemoryRail
ExternalMemoryRail memoryRail = new ExternalMemoryRail(
    provider,
    "user-1",       // userId
    "scope-1",      // scopeId
    "session-1"     // sessionId
);

// 3. 将 Rail 添加到 DeepAgent 配置
List<Object> rails = new ArrayList<>();
rails.add(memoryRail);

DeepAgentConfig config = DeepAgentConfig.builder()
    .systemPrompt("你是一个有长期记忆能力的助手...")
    .rails(rails)
    .build();

// 4. 创建 DeepAgent
DeepAgent agent = HarnessFactory.createDeepAgent(card, config, workspace);

// 5. 运行 — 记忆自动工作
agent.run(Map.of("query", "你还记得我的名字吗？"));
```

### 9.2 自定义 MemoryProvider

实现 `MemoryProvider` 接口即可接入任何记忆后端：

```java
public class MyCustomProvider implements MemoryProvider {
    @Override
    public String getName() { return "my_custom"; }

    @Override
    public void initialize(Map<String, Object> kwargs) { /* 连接数据库 */ }

    @Override
    public List<Map<String, Object>> getToolSchemas() {
        return List.of(
            Map.of("name", "my_search", "description", "搜索记忆", "parameters", ...),
            Map.of("name", "my_save", "description", "保存记忆", "parameters", ...)
        );
    }

    @Override
    public String handleToolCall(String toolName, Map<String, Object> args) {
        // 处理工具调用，返回 JSON 字符串
    }

    @Override
    public String prefetch(String query, Map<String, Object> kwargs) {
        // 根据查询召回相关记忆，返回文本
    }

    @Override
    public void syncTurn(String userMsg, String assistantMsg, Map<String, Object> kwargs) {
        // 将对话同步到记忆后端
    }

    @Override
    public String systemPromptBlock() {
        return "# My Memory\nUse my_search to find, my_save to store.";
    }
}
```

### 9.3 完整示例

参考项目中提供的完整可运行示例：
- [ExternalMemoryRailExample.java](../../../examples/myexample/rails/external_memory_rail/ExternalMemoryRailExample.java)
- [LocalFileMemoryProvider.java](../../../examples/myexample/rails/external_memory_rail/LocalFileMemoryProvider.java)

运行方式：
```bash
# 多轮对话模式
java myexample.rails.external_memory_rail.ExternalMemoryRailExample

# 单次查询模式
java myexample.rails.external_memory_rail.ExternalMemoryRailExample --query "我叫张三，请记住我的名字"
```

## 10. 工作流全景

```
┌─────────────────────────────────────────────────────────────┐
│                    DeepAgent 运行流程                         │
│                                                             │
│  用户提问                                                    │
│    │                                                        │
│    ▼                                                        │
│  beforeInvoke ──→ 惰性初始化 provider.initialize()           │
│    │                                                        │
│    ▼                                                        │
│  beforeModelCall ──→ provider.prefetch(query)               │
│    │                  → 注入 <memory-context> 到 LLM 上下文   │
│    ▼                                                        │
│  LLM 思考 & 行动（可调用 ltm_search / ltm_save 等工具）       │
│    │                  → provider.handleToolCall()             │
│    ▼                                                        │
│  afterInvoke ──→ provider.syncTurn(userMsg, assistantMsg)   │
│    │             → 对话内容自动保存到记忆                       │
│    ▼                                                        │
│  返回结果                                                    │
│                                                             │
│  uninit ──→ provider.shutdown() + 反注册工具 + 清除 Prompt    │
└─────────────────────────────────────────────────────────────┘
```

## 11. 容错设计

| 场景 | 处理方式 |
|------|----------|
| Provider 初始化失败 | `isInitialized = false`，后续 prefetch/syncTurn 被跳过 |
| Prefetch 失败 | 静默忽略（`catch Exception ignored`），不影响 Agent 主流程 |
| SyncTurn 失败 | `syncFailures` 计数，连续失败 5 次后停止同步 |
| 工具调用失败 | 返回 `Map.of("error", ex.getMessage())`，LLM 可感知错误并调整策略 |
| Provider shutdown 失败 | 静默忽略（`catch Exception ignored`） |
| Provider 不可用 | `provider == null` 时所有回调短路返回 |

这些容错机制确保外部记忆服务异常不会阻断 Agent 的核心执行流程。

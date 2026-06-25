# DeepAgent 上下文动态管理：为什么这么设计、解决什么问题、用在什么场景

## 一、先说问题：为什么需要上下文动态管理？

LLM 有一个根本限制：**上下文窗口有限**（通常 128K~200K token）。而 Agent 在实际运行中面临三类上下文问题：

| 问题 | 具体表现 |
|------|---------|
| **上下文膨胀** | Agent 每轮调用工具、读文件、生成回复，消息越积越多，很快超出窗口上限 |
| **信息丢失** | 压缩/截断时，关键信息（用户画像、任务状态、工具结果）可能被丢弃，导致 Agent "失忆" |
| **信息缺失** | Agent 只有会话内的短期记忆，不知道用户的历史偏好、领域知识、外部文档 |

**如果没有上下文动态管理**：Agent 要么因 token 超限报错，要么丢失关键上下文导致回答质量下降，要么无法利用历史和外部知识。

---

## 二、设计思路：三层能力

DeepAgent 的上下文动态管理分三层，每层解决一个层面的问题：

```
┌──────────────────────────────────────────────────────────────────────────┐
│  第 1 层：上下文引擎（ContextEngine）                                      │
│  解决：上下文膨胀 — 通过多级压缩管线，在 token 预算内保留最大信息量           │
├──────────────────────────────────────────────────────────────────────────┤
│  第 2 层：长期记忆（LongTermMemory + ExternalMemoryRail）                  │
│  解决：信息丢失 — 跨会话持久化用户画像、语义记忆、变量，按需召回              │
├──────────────────────────────────────────────────────────────────────────┤
│  第 3 层：外部知识库（KnowledgeBase + RAG）                                │
│  解决：信息缺失 — 从文档、向量库、图谱中检索领域知识，注入上下文              │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 三、第 1 层：上下文引擎 — 解决"上下文膨胀"

### 核心问题

Agent 执行过程中，消息不断累积：
- 每次工具调用产生 ToolMessage（可能几千 token）
- 每次模型回复产生 AssistantMessage
- 多轮迭代后，总量轻松超过 200K token

### 设计方案

**ContextEngine** 是上下文管理的总入口，负责：
1. 注册和配置处理器（ContextProcessor）
2. 为每个会话创建隔离的 ModelContext
3. 应用处理器链进行压缩/卸载/截断

```
ContextEngine
  ├── PROCESSOR_FACTORY_MAP（全局注册表）
  │     "MessageSummaryOffloader" → 工厂函数
  │     "DialogueCompressor"     → 工厂函数
  │     "CurrentRoundCompressor" → 工厂函数
  │     "RoundLevelCompressor"   → 工厂函数
  │     "ToolResultBudgetProcessor" → 工厂函数
  │     "MicroCompactProcessor"  → 工厂函数
  │     "FullCompactProcessor"   → 工厂函数
  │
  ├── contextPool（会话级上下文池）
  │     sessionId_contextId → SessionModelContext
  │
  └── createContext() → 创建/缓存 ModelContext
```

**SessionModelContext** 是核心实现类，每次 LLM 调用前执行 `buildContextWindow()`：

```
SessionModelContext.buildContextWindow()
  → 1. 从 messageBuffer 取出全部消息
  → 2. 依次执行 processors 链
  → 3. 返回 ContextWindow（systemMessages + contextMessages + tools）
```

**ContextWindow** 是最终发给 LLM 的数据结构：

```java
ContextWindow {
    systemMessages,    // 系统指令（不压缩）
    contextMessages,   // 对话历史（可压缩/截断/卸载）
    tools,             // 工具定义
    statistic          // 统计信息
}
```

### 双管线设计

通过 ContextProcessorRail 构建处理器链：

| 管线 | 处理器 | 策略 | 适用场景 |
|------|--------|------|---------|
| **标准管线** | MessageSummaryOffloader → DialogueCompressor → CurrentRoundCompressor → RoundLevelCompressor | LLM 摘要压缩为主 | 通用 Agent，对话质量优先 |
| **SessionMemory 管线** | ToolResultBudgetProcessor → MicroCompactProcessor → FullCompactProcessor | 规则压缩优先 + LLM 兜底 | 长时任务，工具调用密集 |

### 关键机制

1. **Offload + Reload**：超大工具结果卸载到磁盘/内存，保留 `[[OFFLOAD: handle=xxx, type=in_memory]]` 标记。Agent 需要时可通过 `reload_original_context_messages` 工具重新加载
2. **messagesToKeep**：每个处理器保留最近 N 条消息不压缩，确保当前上下文完整
3. **状态持久化**：压缩状态通过 `StatefulContext.saveState()/loadState()` 持久化到 Session，重启后可恢复

---

## 四、第 2 层：长期记忆 — 解决"信息丢失"

### 核心问题

会话结束后，所有上下文消息丢失。下次对话，Agent 不知道：
- 用户是谁、有什么偏好
- 之前讨论过什么
- 积累了哪些变量（如"我的项目名是 X"）

### 设计方案

**LongTermMemory** 是单例引擎，管理跨会话的持久化记忆：

```
LongTermMemory（单例）
  ├── 存储后端
  │     kvStore（Redis/NOS）     ← 变量、配置
  │     vectorStore（Milvus/PG） ← 语义检索
  │     dbStore（SQL）           ← 结构化数据
  │
  ├── 记忆类型（MemoryType）
  │     USER_PROFILE      ← 用户画像（显式：姓名/偏好；隐式：行为推断）
  │     SEMANTIC_MEMORY   ← 语义记忆（知识片段）
  │     EPISODIC_MEMORY   ← 情景记忆（事件经历）
  │     VARIABLE          ← 变量（键值对，如"项目名=X"）
  │     SUMMARY           ← 会话摘要
  │
  ├── 管理器
  │     FragmentMemoryManager  ← 片段记忆读写
  │     VariableManager        ← 变量读写
  │     SummaryManager         ← 摘要读写
  │     WriteManager           ← 统一写入入口
  │     SearchManager          ← 跨类型搜索
  │
  └── LLM 提取
        LongTermMemoryExtractor ← 从对话中提取记忆片段
```

**ExternalMemoryRail** 是长期记忆的接入层，在 DeepAgent 的 Rail 生命周期中自动工作：

```
ExternalMemoryRail 生命周期：
  init()         → 注册 provider 的工具到 Agent（如 search_memory, add_memory）
  beforeInvoke() → 初始化 provider（连接外部记忆服务）
  beforeModelCall() → prefetch：根据用户 query 预取相关记忆，注入 <memory-context> 块
  afterInvoke()  → syncTurn：将本轮对话同步到外部记忆服务
```

**prefetch 机制**：每次 LLM 调用前，ExternalMemoryRail 自动用用户 query 从长期记忆中检索相关内容，注入到 System Prompt：

```
<memory-context>
[System note: recalled memory context from long-term memory, NOT new user input.]

用户张三，偏好中文交流，项目名是"星河计划"，上次讨论了融资方案...
</memory-context>
```

**三种 MemoryProvider 实现**：

| Provider | 说明 |
|----------|------|
| OpenJiuwenMemoryProvider | OpenJiuwen 自研记忆服务 |
| OpenVikingMemoryProvider | 华为 Viking 记忆服务 |
| Mem0MemoryProvider | Mem0 开源记忆服务 |

---

## 五、第 3 层：外部知识库 — 解决"信息缺失"

### 核心问题

Agent 只有会话内的上下文，不知道：
- 公司内部文档（政策、流程、规范）
- 领域知识库（产品手册、技术文档）
- 实时数据（数据库、API 返回结果）

### 设计方案

**KnowledgeBase + RAG** 提供外部知识检索能力：

```
知识检索管线：
  文档入库                         知识检索
  ┌──────────────┐               ┌──────────────┐
  │ Parser       │               │ QueryRewriter│
  │ ├ PDF        │               │      ↓       │
  │ ├ Word       │               │ Retriever    │
  │ ├ HTML       │               │ ├ Vector     │ ← 向量检索
  │ ├ Excel      │               │ ├ Sparse     │ ← 关键词检索
  │ └ Markdown   │               │ ├ Graph      │ ← 图谱检索
  │      ↓       │               │ └ Hybrid     │ ← 混合检索
  │ Chunker      │               │      ↓       │
  │ ├ Sentence   │               │ Reranker     │
  │ ├ Char       │               │ ├ Lexical    │
  │ └ Hybrid     │               │ ├ Chat       │
  │      ↓       │               │ └ Standard   │
  │ Embedding    │               │      ↓       │
  │ ├ OpenAI     │               │ Top-K 结果   │
  │ ├ Dashscope  │               └──────────────┘
  │ └ VLLM       │
  │      ↓       │
  │ Indexer      │
  │ ├ Milvus     │
  │ ├ Chroma     │
  │ ├ PGVector   │
  │ └ InMemory   │
  └──────────────┘
```

**KnowledgeRetrievalExecutable** 是工作流中的知识检索组件：

```java
// 在工作流中使用知识检索
KnowledgeRetrievalExecutable.invoke(inputs, session, context)
  → 1. 懒初始化 KnowledgeBase（SimpleKnowledgeBase / GraphKnowledgeBase）
  → 2. 调用 SimpleKnowledgeBase.retrieveMultiKbWithSource() 多知识库联合检索
  → 3. 返回格式化的检索结果
```

**ContextAssembleRail** 在每次 LLM 调用前动态注入上下文：

```
ContextAssembleRail.beforeModelCall()
  → buildWorkspaceSection()  ← 工作区文件结构（最多 80 个条目）
  → buildToolsSection()      ← 可用工具列表及描述
  → buildContextSection()    ← context/ 目录下的文件内容（最多 8 个文件，每个 4000 字符）
  → 注入到 System Prompt
```

这意味着：把知识文档放到 `workspace/context/` 目录下，Agent 就能自动读取。

---

## 六、三层协作的完整流程

```
用户："根据对公贷款政策，帮我出一份融资方案"

┌─ 第 3 层：外部知识库 ──────────────────────────────────────────┐
│  ContextAssembleRail.beforeModelCall()                         │
│  → 读取 workspace/context/对公贷款政策.md，注入 System Prompt     │
│  ExternalMemoryRail.beforeModelCall()                          │
│  → prefetch("融资方案") → 检索到用户画像"偏好简洁风格"             │
│  → 注入 <memory-context> 块                                    │
└───────────────────────────────────────────────────────────────┘
                              ↓
┌─ Agent 执行（内循环 ReAct） ────────────────────────────────────┐
│  Round 1: LLM 调用 → 生成融资方案初稿                            │
│  Round 2: 调用 search_loan_policy 工具 → 获取最新利率             │
│  Round 3: 调用 generate_document 工具 → 生成正式文档              │
│  ...（多轮工具调用，消息不断累积）                                 │
└───────────────────────────────────────────────────────────────┘
                              ↓
┌─ 第 1 层：上下文引擎 ──────────────────────────────────────────┐
│  SessionModelContext.buildContextWindow()                       │
│  → MessageSummaryOffloader: 单条工具结果 >10K → LLM 摘要         │
│  → DialogueCompressor: 历史总量 >100K → LLM 压缩历史对话         │
│  → CurrentRoundCompressor: 当前轮 >100K → 增量记忆块压缩         │
│  → RoundLevelCompressor: 总量 >230K → 多轮递归压缩               │
│  → 最终 ContextWindow < 128K token，可以发给 LLM                 │
└───────────────────────────────────────────────────────────────┘
                              ↓
┌─ 第 2 层：长期记忆 ────────────────────────────────────────────┐
│  ExternalMemoryRail.afterInvoke()                               │
│  → syncTurn("融资方案", "生成结果...") → 将本轮对话同步到长期记忆   │
│  → LongTermMemoryExtractor 提取关键信息：                        │
│    - 用户偏好：简洁风格                                          │
│    - 变量：融资方案版本=v2                                       │
│    - 情景记忆：2026-06-25 生成了融资方案                          │
└───────────────────────────────────────────────────────────────┘
```

---

## 七、解决的核心问题总结

| 问题 | 没有上下文动态管理 | 有上下文动态管理 |
|------|------------------|-----------------|
| **上下文膨胀** | token 超限报错，或粗暴截断丢失信息 | 多级渐进压缩，在预算内保留最大信息量 |
| **关键信息丢失** | 压缩/截断时无差别丢弃 | messagesToKeep 保护近期消息，Offload+Reload 按需恢复 |
| **跨会话失忆** | 每次对话从零开始 | LongTermMemory 持久化用户画像、变量、摘要 |
| **缺乏领域知识** | 只能依赖 LLM 自身知识 | RAG 检索外部文档，ContextAssembleRail 自动注入 |
| **记忆不可控** | 无 | MemoryProvider 接口统一抽象，支持多种后端 |
| **压缩不可观测** | 不知道压缩了什么 | ContextProcessorStateRecorder 记录压缩过程 |

---

## 八、适用场景

### 场景 1：金融顾问 Agent — 三层全用

```
用户："帮我出一份融资方案"

第 3 层：从知识库检索"对公贷款政策"文档 → 注入上下文
第 2 层：prefetch 检索到"用户偏好简洁风格" → 注入记忆
第 1 层：多轮工具调用后上下文膨胀 → 自动压缩
执行后：syncTurn 将对话同步到长期记忆 → 下次可用
```

### 场景 2：代码助手 — 上下文引擎 + 长期记忆

```
用户："重构 X 模块"

第 1 层：Agent 读了 20 个文件，工具结果累积 300K token
  → MessageSummaryOffloader 摘要大文件内容
  → DialogueCompressor 压缩历史对话
  → 最终控制在 128K 以内

第 2 层：用户说"我偏好函数式风格"
  → VariableManager 存储变量 "coding_style=functional"
  → 下次对话 prefetch 自动注入
```

### 场景 3：客服 Agent — 长期记忆为主

```
用户："我的订单到哪了？"

第 2 层：prefetch 检索到用户画像
  → "用户ID=U123，最近订单=O456，偏好电话联系"
  → Agent 直接查询订单 O456，不用再问用户信息

执行后：syncTurn 记录"用户询问了订单O456状态"
  → 下次用户问"上次那个订单呢"，Agent 能回忆起来
```

### 场景 4：知识问答 — 外部知识库为主

```
用户："公司差旅报销标准是什么？"

第 3 层：KnowledgeRetrievalExecutable 从向量库检索
  → 返回"差旅报销规范 v3.2"相关段落
  → ContextAssembleRail 注入到 System Prompt

第 1 层：如果检索结果很长（>10K token）
  → MessageSummaryOffloader 自动摘要
```

---

## 九、一句话总结

**DeepAgent 的上下文动态管理 = ContextEngine 多级压缩解决膨胀 + LongTermMemory 持久化解决失忆 + KnowledgeBase RAG 检索解决知识缺失，三层协作让 Agent 在有限 token 窗口内，既记得住历史、又找得到知识、还不超预算。**

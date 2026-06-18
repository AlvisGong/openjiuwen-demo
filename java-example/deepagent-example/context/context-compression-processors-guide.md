# 上下文压缩四大处理器详解

## 处理器链协同关系

```
上下文膨胀
   ↓
① MessageSummaryOffloader — 先处理单条大消息（工具返回）
   ↓ 仍然超预算？
② DialogueCompressor — 压缩已完成的整轮对话
   ↓ 仍然超预算？
③ CurrentRoundCompressor — 增量压缩当前轮次的已完成部分
   ↓ 仍然超预算？
④ RoundLevelCompressor — 兜底强压（最多3遍）
```

触发机制：`SessionModelContext.addMessages()` 遍历 processors，若 `processor.triggerAddMessages() == true`，则执行 `onAddMessages()`。

---

## 1. MessageSummaryOffloader — 工具结果卸载器

### 核心功能

当工具调用返回大量内容时，不把原始结果全部塞进上下文，而是用 LLM 压缩摘要，原始数据卸载到文件系统/内存，可按需加载。

### 触发机制

`triggerAddMessages()` 遍历新增消息，只要有一条消息满足：
- 角色属于 `offloadMessageType`（默认 `["tool"]`）
- 不属于 `protectedToolNames` 的工具结果
- Token 数 > `largeMessageThreshold`

### 压缩策略

先判断内容适合 **抽取式(extractive)** 还是 **摘要式(abstractive)** 压缩，然后生成摘要 + 卸载信息描述（category/description/inferability）。压缩结果附带 `[offloaded_info]` 标记，告知 agent 原始数据的类别、描述和可推断性等级。

### 参数详解

| 参数 | 默认值 | 含义 | 实际配置建议 |
|---|---|---|---|
| `tokensThreshold` | 20000 | 上下文总 token 超过此值才触发卸载（继承自父类） | 设为模型上下文窗口的 50%-70%，如 128K 模型设 60000-80000 |
| `largeMessageThreshold` | 1000 | 单条消息 token 超过此值才被视为"大消息"进行卸载 | 工具返回通常 500-5000 token，设 1000-2000 合理 |
| `offloadMessageType` | `["tool"]` | 哪些角色的消息会被卸载 | 仅 `"tool"` 最常见；如需要也卸载 assistant 的冗长回复可加 `"assistant"` |
| `protectedToolNames` | `["reload_original_context_messages"]` | 这些工具的结果不会被卸载 | 空列表 = 全部可卸载；加上关键工具名（如 `search`、`read_file`）保护其原始结果 |
| `messagesToKeep` | null(=全部保留) | 保留最近 N 条消息不被处理 | 设 4-6，确保最近的工具调用结果保持完整 |
| `keepLastRound` | true | 最后一轮对话（最近 user-assistant 交互）始终保留 | **保持 true**，最后一轮是当前工作焦点 |
| `summaryMaxTokens` | 900 | 卸载摘要的 token 上限 | 900-1500，太小丢失信息，太大压缩不够 |
| `enablePreciseStep` | false | 是否用 LLM 精确推断当前步骤意图 | true = 更精准但多一次 LLM 调用；false = 用最近 user 消息作为意图 |
| `stepSummaryMaxContextMessages` | 8 | 精确步骤推断时最多取多少条上下文消息 | 默认 8 即可，增大则 LLM 调用成本上升 |
| `contentMaxCharsForCompression` | 200000 | 传给压缩 LLM 的最大字符数 | 200K 字符约对应 50K token，超过会智能截断（保留头+中+尾） |

---

## 2. DialogueCompressor — 历史对话块压缩器

### 核心功能

将已经完成的 ReAct 对话轮次（user → assistant(tool calls) → tool results → assistant final reply）整体压缩为 `[DIALOGUE_MEMORY_BLOCK]` 内存块，保留关键信息（意图、结果、决策），丢弃冗余过程。

### 触发机制

`triggerAddMessages()` 检查：
- 消息数 > `messagesThreshold`，或
- 总 token > `tokensThreshold`

### 压缩方式

将完整对话轮次识别为 `DialogueRound`，按 block 编号发给 LLM 执行多块压缩，每块生成独立摘要。压缩后原始多轮消息被替换为一条 `UserMessage`（带 `[DIALOGUE_MEMORY_BLOCK]` 标记）。

内存块包含元信息：
- `processor: DialogueCompressor`
- `type: historical_memory_block`
- `scope: historical_dialogue_block`
- `authority: 参考内存，非强制真相源`
- `conflict_priority: 新信息优先于此块`

### 压缩优先级（Prompt 内置）

1. 任务目标和用户意图
2. 正确继续的关键事实基础
3. 未完成工作
4. 块边界交接状态
5. 关键决策、约束、变更、修正
6. 重要文件、产出、工具结果
7. 辅助细节

### 参数详解

| 参数 | 默认值 | 含义 | 实际配置建议 |
|---|---|---|---|
| `messagesThreshold` | null | 消息数超过此值触发（不设则仅按 token 触发） | 通常不设，靠 token 阈值控制更精准 |
| `tokensThreshold` | 10000 | 总 token 超过此值触发 | 设为上下文窗口的 60%-70%，128K 模型设 80000-100000 |
| `messagesToKeep` | null | 保留最近 N 条不压缩 | 设 6-10，保证最近 2-3 轮对话完整 |
| `keepLastRound` | true | 最后一轮完整 user-assistant 交互始终保留 | **保持 true** |
| `compressionTargetTokens` | 1800 | 每个压缩块的目标 token 数 | 1000-2000，取决于信息密度；执行类任务需更大 |
| `customCompressionPrompt` | null | 自定义压缩 prompt | 一般不需要，默认 prompt 设计很完善 |

---

## 3. CurrentRoundCompressor — 当前轮次增量压缩器

### 核心功能

当当前对话轮次过长（agent 在一个 user query 下反复调用工具），将已完成的部分压缩为 `[CURRENT_ROUND_MEMORY_BLOCK]` 内存块，保留"增量进展"而非全量快照。

### 核心设计思想

- 输出是**增量内存块**，不是全量总结——只记录新增、变更、未完成的工作
- 与前序内存块组合使用，不重复已有信息
- 压缩后会尝试**合并**历史积累的多个内存块（当 >= `summaryMergeMinBlocks` 个连续内存块时触发合并）

### 触发机制

`triggerAddMessages()` 检查总 token > `tokensThreshold`，且消息数 >= `messagesToKeep`。

### 压缩上下文结构

```
User Query
↓
Accumulated Memory Blocks  (持久内存；不可重写)
↓
Selected Messages  (唯一压缩目标)
↓
Recent Messages  (边界上下文；不吸收)
```

### 输出结构（7 段式）

1. **User Requirements** — 正在服务的原始用户需求、约束、验收标准
2. **Current Status** — 已完成工作、关键信息获取、文件/产出
3. **Open Work** — 进行中工作、待办任务、优先级排序
4. **Important Findings** — 决策与变更、约束、错误与修复、无效尝试
5. **Strategy State** — 已尝试/候选/已否决/需重新评估的策略
6. **Tool / Action State** — 已用工具、关键输入、结果摘要、时效约束
7. **Contextual Bridging** — 连续性、前向影响、空白与风险

### 参数详解

| 参数 | 默认值 | 含义 | 实际配置建议 |
|---|---|---|---|
| `tokensThreshold` | 100000 | 总 token 超过此值触发 | 设为上下文窗口 70%-80%，128K 模型设 90000-100000 |
| `messagesToKeep` | 3 | 保留最近 N 条不压缩 | 3-6，确保当前正在进行的工具交互完整 |
| `minSelectedTokensForCompression` | 20000 | 待压缩段 token < 此值则跳过（太小不值得压缩） | 5000-20000；太低会导致频繁压缩短片段，得不偿失 |
| `compressionTargetTokens` | 4000 | 单次压缩的目标 token 数 | 2000-4000；这是**增量信息**的目标量，不是全量 |
| `summaryMergeTargetTokens` | 4000 | 合并多个内存块后的目标 token 数 | 略低于 `compressionTargetTokens`，如 3000-4000 |
| `accumulatedSummaryTokenLimit` | 20000 | 累积内存块总 token 超过此值才触发合并 | 设为 `compressionTargetTokens * 5~10`，保证积累足够信息后才合并 |
| `summaryMergeMinBlocks` | 3 | 至少多少个连续内存块才触发合并 | 3-5；太少合并频繁且信息损失大，太多内存块堆积占空间 |
| `priorContextWindowSize` | 10 | 给压缩 LLM 提供的最近上下文消息数 | 5-10；提供足够的上下文让 LLM 理解当前意图 |
| `customCompressionPrompt` | null | 自定义压缩 prompt | 一般不需要 |

---

## 4. RoundLevelCompressor — 兜底级 Token 预算压缩器

### 核心功能

当所有其他处理器都无法把 token 降到模型上下文窗口以内时，此处理器作为**最后防线**，按严格 token 预算强制压缩，最多执行 3 遍逐步加重的压缩，直到 token 降到目标以下。

### 触发机制

`triggerAddMessages()` / `triggerGetContextWindow()` 检查上下文窗口总 token（含 system prompt + tools）> `triggerTotalTokens`。

### 三遍渐进压缩流程

1. **第一遍**: 用常规 prompt 压缩，每块目标 `firstPassTargetTokens`（较宽松，保留更多信息）
2. **第二遍**: 若仍超预算 → 用激进 prompt，每块目标 `secondPassTargetTokens`（中等压缩）
3. **第三遍**: 若仍超 → 更激进，每块目标 `thirdPassTargetTokens`（最低限度保留）

对超长内容先按 `truncateHeadRatio` 截断头部再送 LLM（保留尾部信息密度更高的部分）。

### 压缩优先级（Prompt 内置）

1. 正在进行的 ReAct 状态和精确交接点
2. 未完成工作、阻碍、待执行动作、最后具体操作
3. 关键事实、约束、决策、修正、输出
4. 已完成工作的持久结论
5. 辅助历史细节（仅在预算允许时）

### 参数详解

| 参数 | 默认值 | 含义 | 实际配置建议 |
|---|---|---|---|
| `triggerTotalTokens` | 230000 | 上下文窗口总 token（含 system+tools）超过此值触发 | 设为**模型最大上下文窗口 - 10%~20%缓冲**，如 128K 模型设 110000-120000 |
| `targetTotalTokens` | 160000 | 压缩后目标总 token | 设为 `triggerTotalTokens * 70%`，如 128K 模型设 90000-100000 |
| `keepRecentMessages` | 0 | 保留最近 N 条不压缩 | **必须设 > 0**！建议 4-6；默认 0 在极端压缩下会丢失当前工作状态 |
| `compressionCallMaxTokens` | 250000 | 传给压缩 LLM 的最大 token | 略高于 `triggerTotalTokens`，保证 LLM 能看到足够上下文 |
| `firstPassTargetTokens` | 30000 | 第一遍压缩每块目标 | 较宽松，保留更多信息 |
| `secondPassTargetTokens` | 20000 | 第二遍压缩每块目标 | 中等压缩 |
| `thirdPassTargetTokens` | 10000 | 第三遍压缩每块目标（最激进） | 最低限度信息保留 |
| `truncateHeadRatio` | 0.2 | 超长内容截断时，头部保留比例 | 0.1-0.3；工具返回通常头尾信息密度高 |

---

## 实际生产环境配置建议（128K 模型为例）

```java
// 1. MessageSummaryOffloader — 工具结果卸载
processorSpecs.add(new ContextEngine.ProcessorSpec("MessageSummaryOffloader",
        MessageSummaryOffloaderConfig.builder()
                .tokensThreshold(60000)              // 上下文 50% 时开始卸载
                .largeMessageThreshold(1500)         // 单条 > 1500 token 就卸载
                .offloadMessageType(List.of("tool"))
                .protectedToolNames(List.of())       // 或加上关键工具名
                .messagesToKeep(6)
                .keepLastRound(true)
                .summaryMaxTokens(1200)
                .enablePreciseStep(false)            // 精度与成本的权衡
                .model(modelConfig)
                .modelClient(modelClientConfig)
                .build()));

// 2. DialogueCompressor — 历史对话块压缩
processorSpecs.add(new ContextEngine.ProcessorSpec("DialogueCompressor",
        DialogueCompressorConfig.builder()
                .tokensThreshold(80000)              // 上下文 60% 时压缩历史
                .messagesToKeep(8)                   // 保留最近 2-3 轮
                .compressionTargetTokens(1500)       // 每块目标 1500 token
                .model(modelConfig)
                .modelClient(modelClientConfig)
                .build()));

// 3. CurrentRoundCompressor — 当前轮次增量压缩
processorSpecs.add(new ContextEngine.ProcessorSpec("CurrentRoundCompressor",
        CurrentRoundCompressorConfig.builder()
                .tokensThreshold(95000)              // 上下文 75% 时增量压缩
                .messagesToKeep(4)                   // 保留最近 4 条
                .minSelectedTokensForCompression(10000) // 低于 10000 token 不压缩
                .compressionTargetTokens(3000)       // 增量目标 3000 token
                .summaryMergeTargetTokens(3000)      // 合并目标 3000 token
                .accumulatedSummaryTokenLimit(15000) // 累积 15000 token 后合并
                .summaryMergeMinBlocks(3)            // 3 个连续块才合并
                .priorContextWindowSize(8)           // 8 条上下文窗口
                .model(modelConfig)
                .modelClient(modelClientConfig)
                .build()));

// 4. RoundLevelCompressor — 兜底强压
processorSpecs.add(new ContextEngine.ProcessorSpec("RoundLevelCompressor",
        RoundLevelCompressorConfig.builder()
                .triggerTotalTokens(115000)          // 模型窗口 128K - 10K 缓冲
                .targetTotalTokens(90000)            // 压缩到 70%
                .keepRecentMessages(4)               // 必须设 > 0
                .firstPassTargetTokens(25000)        // 第一遍宽松
                .secondPassTargetTokens(15000)       // 第二遍中等
                .thirdPassTargetTokens(8000)         // 第三遍最激进
                .model(modelConfig)
                .modelClient(modelClientConfig)
                .build()));
```

## 关键原则

- **阈值层层递进**: Offloader 最早触发（50%窗口）→ DialogueCompressor（60%）→ CurrentRoundCompressor（75%）→ RoundLevelCompressor（90%），确保每层只在自己负责的范围内工作
- **避免重复压缩**: 每个 processor 只处理自己识别的目标，不重复处理已被其他 processor 压缩过的内容
- **信息完整性优先**: 所有压缩 prompt 都遵循"任务目标 > 关键事实 > 未完成工作 > 决策 > 辅助细节"的优先级
- **keepRecentMessages 必须设 > 0**: 尤其 RoundLevelCompressor 默认为 0，极端压缩下会丢失当前工作状态
- **压缩模型选择**: 压缩用的 LLM 不需要是最强模型，中等能力模型即可胜任摘要任务，降低成本

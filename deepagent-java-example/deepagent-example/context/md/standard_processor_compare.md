# 三大压缩处理器对比：DialogueCompressor vs CurrentRoundCompressor vs RoundLevelCompressor

## 一、核心定位对比

| 维度 | DialogueCompressor | CurrentRoundCompressor | RoundLevelCompressor |
|------|-------------------|----------------------|---------------------|
| **压缩对象** | 历史已完成的 API Round | 当前轮次中的已完成 API Round | 所有满足条件的连续 Round |
| **触发阈值** | 总量 > 100K token | 总量 > 100K token | 总量 > 230K token |
| **压缩策略** | 按 Round 逐块压缩，每块独立摘要 | 增量记忆块，与已有摘要衔接 | 多轮递归压缩 + 激进压缩兜底 |
| **压缩粒度** | 每个 API Round → 一条摘要消息 | 当前轮已完成部分 → 一条增量摘要 | 多个 Round → 合并为一条新 Round |
| **输出标记** | `[DIALOGUE_MEMORY_BLOCK]` | `[CURRENT_ROUND_MEMORY_BLOCK]` | `[ROUND_LEVEL_MEMORY_BLOCK]` |
| **触发时机** | `onAddMessages`（消息写入时） | `onAddMessages`（消息写入时） | `onAddMessages` + `onGetContextWindow`（双重触发） |
| **兜底能力** | 无兜底，压缩失败则跳过 | 无兜底，压缩失败则跳过 | 三阶段递归 + 激进 + 截断兜底 |

---

## 二、压缩范围的区别（图解）

假设消息列表如下：

```
[0]  SystemMessage
[1]  UserMessage              ← Round 1
[2]  AssistantMessage(最终)    ← Round 1 结束
[3]  UserMessage              ← Round 2
[4]  AssistantMessage(工具调用)
[5]  ToolMessage
[6]  AssistantMessage(最终)    ← Round 2 结束
[7]  UserMessage              ← Round 3（当前轮）
[8]  AssistantMessage(工具调用)
[9]  ToolMessage
[10] AssistantMessage(工具调用)
[11] ToolMessage
[12] AssistantMessage(最终)    ← Round 3 结束
```

### DialogueCompressor 压缩范围

```
[1]~[6] → 历史已完成的 Round 1 + Round 2
保留：[7]~[12]（当前轮 + messagesToKeep 条消息）
```

### CurrentRoundCompressor 压缩范围

```
[8]~[11] → 当前轮（Round 3）中保留区之前的部分
保留：[7]（当前轮起始 UserMessage）+ [12]（最近 messagesToKeep 条）
```

### RoundLevelCompressor 压缩范围

```
[1]~[12] → 所有满足条件的连续 Round（无差别压缩）
保留：keepRecentMessages 条最近消息
```

---

## 三、压缩策略的区别

### 3.1 DialogueCompressor — "按块摘要"

- 将每个历史 API Round 独立压缩为一条摘要消息
- 每个 Round 的摘要包含：用户需求、最终结果、关键发现、决策变更
- **Round 之间互不干扰**，压缩 Round 1 不影响 Round 2 的摘要

```
压缩前：
  Round 1: UserMessage → AssistantMessage(最终)           [2000 tokens]
  Round 2: UserMessage → AssistantMessage → ToolMessage → AssistantMessage(最终)  [5000 tokens]

压缩后：
  [DIALOGUE_MEMORY_BLOCK] Round 1 摘要: 用户查询天气，结果晴天25°C  [300 tokens]
  [DIALOGUE_MEMORY_BLOCK] Round 2 摘要: 用户查询航班，结果CA1234已订  [400 tokens]
```

### 3.2 CurrentRoundCompressor — "增量记忆块"

- 将当前轮次中已完成的 API Round 压缩为**增量记忆块**
- 增量记忆块会**追加到已有记忆块之后**，形成连续的记忆链
- 压缩时会参考**已有记忆块**（prior_summaries），确保不重复

```
压缩前：
  [已有 DIALOGUE_MEMORY_BLOCK] 之前的历史摘要...
  UserMessage（当前轮起始）
    → AssistantMessage(工具调用) → ToolMessage   [3000 tokens]
    → AssistantMessage(工具调用) → ToolMessage   [4000 tokens]
    → AssistantMessage(工具调用) → ToolMessage   [2000 tokens]
  保留区：最近 3 条消息

压缩后：
  [已有 DIALOGUE_MEMORY_BLOCK] 之前的历史摘要...
  [CURRENT_ROUND_MEMORY_BLOCK] 增量摘要：已读取3个文件，获取了配置信息...  [500 tokens]
  保留区：最近 3 条消息
```

**与 DialogueCompressor 的关键区别**：CurrentRoundCompressor 的摘要会与已有摘要**衔接**，避免重复记录。

### 3.3 RoundLevelCompressor — "多轮递归 + 激进兜底"

- 不区分历史/当前，对所有满足条件的 Round 进行压缩
- **三阶段递归压缩**：

```
阶段 1：L0→L1 递归压缩
  将 compress_level=0 的 Round 压缩为 compress_level=1 的摘要
  目标：firstPassTargetTokens

  如果仍超预算 ↓

阶段 2：激进压缩（保留最近消息）
  用更激进的提示词重新压缩，保留 keepRecentMessages 条
  目标：secondPassTargetTokens

  如果仍超预算 ↓

阶段 3：激进压缩（不保留）
  用最激进的提示词压缩全部内容，不保留任何最近消息
  目标：thirdPassTargetTokens

  如果仍超预算 ↓

兜底：截断（truncateToTarget）
  直接从头部截断消息，直到满足预算
```

---

## 四、场景举例

### 场景 1：短对话（3 轮，60K token）

```
用户：帮我查北京天气
Agent：[调用天气工具] 北京晴天25°C
用户：帮我订机票
Agent：[调用订票工具] CA1234已订
用户：写一份出差报告
Agent：[生成报告] 报告已完成
```

**触发情况**：总量 60K < 100K，三个压缩器均不触发。

---

### 场景 2：中等对话（5 轮，120K token）

```
Round 1: 用户查询天气 → Agent 调用工具返回结果
Round 2: 用户查询航班 → Agent 调用工具返回结果
Round 3: 用户查询酒店 → Agent 调用工具返回结果
Round 4: 用户要求写行程 → Agent 生成行程
Round 5: 用户要求写报告 → Agent 正在生成（当前轮）
```

**触发情况**：

| 压缩器 | 是否触发 | 压缩内容 |
|--------|----------|----------|
| DialogueCompressor | ✅ 120K > 100K | Round 1~4（历史已完成 Round）→ 4 条摘要 |
| CurrentRoundCompressor | ✅ 120K > 100K | Round 5 中保留区之前的工具调用 → 1 条增量摘要 |
| RoundLevelCompressor | ❌ 120K < 230K | 不触发 |

**压缩后效果**：

```
[DIALOGUE_MEMORY_BLOCK] Round 1: 查询天气，北京晴天25°C
[DIALOGUE_MEMORY_BLOCK] Round 2: 查询航班，CA1234已订
[DIALOGUE_MEMORY_BLOCK] Round 3: 查询酒店，如家已订
[DIALOGUE_MEMORY_BLOCK] Round 4: 生成行程，北京3日行程已出
[CURRENT_ROUND_MEMORY_BLOCK] 正在生成出差报告，已读取行程和费用数据...
UserMessage（当前轮）
AssistantMessage（最近 3 条保留）
```

---

### 场景 3：长对话（15 轮，250K token）

```
Round 1~5:  历史对话（已被 DialogueCompressor 压缩为摘要）
Round 6~10: 历史对话（已被 DialogueCompressor 压缩为摘要）
Round 11~13: 中间对话
Round 14:   上一轮
Round 15:   当前轮（Agent 正在执行复杂代码重构）
```

**触发情况**：

| 压缩器 | 是否触发 | 压缩内容 |
|--------|----------|----------|
| DialogueCompressor | ✅ 250K > 100K | Round 11~14 → 摘要 |
| CurrentRoundCompressor | ✅ 250K > 100K | Round 15 中已完成部分 → 增量摘要 |
| RoundLevelCompressor | ✅ 250K > 230K | **所有 Round**（含已压缩的摘要）→ 多轮递归压缩 |

**RoundLevelCompressor 的三阶段压缩**：

```
阶段 1（L0→L1）：
  将 compress_level=0 的 Round 11~15 压缩为 compress_level=1
  目标：firstPassTargetTokens（如 180K）
  结果：250K → 190K → 仍超预算

阶段 2（激进压缩，保留最近）：
  用更激进的提示词重新压缩，保留最近 6 条消息
  目标：secondPassTargetTokens（如 160K）
  结果：190K → 155K → 满足预算 ✅
```

---

### 场景 4：极端长对话（30 轮，400K token）

```
大量历史 Round + 当前正在执行复杂任务
```

**RoundLevelCompressor 的完整三阶段 + 兜底**：

```
阶段 1（L0→L1）：400K → 300K → 仍超 230K
阶段 2（激进保留最近）：300K → 250K → 仍超 230K
阶段 3（激进全量）：250K → 200K → 满足预算 ✅

如果阶段 3 后仍超预算：
  兜底截断：从头部删除消息，直到满足 160K 目标
```

---

## 五、三者在管线中的协作关系

```
标准管线执行顺序：

MessageSummaryOffloader → 先处理单条超大消息
    ↓
DialogueCompressor → 压缩历史已完成 Round（100K 阈值，最先触发）
    ↓
CurrentRoundCompressor → 压缩当前轮已完成部分（100K 阈值，与 Dialogue 互补）
    ↓
RoundLevelCompressor → 兜底压缩（230K 阈值，最后防线）
```

**设计逻辑**：

1. **DialogueCompressor** 先处理历史 Round，因为历史 Round 最容易压缩且信息密度最低
2. **CurrentRoundCompressor** 处理当前轮的中间过程，保留增量记忆
3. **RoundLevelCompressor** 是最后防线，当前两个压缩器压缩后仍超预算时，进行更激进的压缩

三者形成**由轻到重、由历史到当前**的渐进压缩策略，尽量保留最重要的信息。

---

## 六、压缩提示词对比

### DialogueCompressor 提示词核心

```
你是一个任务数据保存专家，专注于高保真压缩历史 ReAct 块。

压缩职责：
- 保留对正确完成和继续任务最有用的信息
- 保留行动连续性和任务关键事实基础
- 保留未完成工作、交接状态、决策、约束、修正、关键发现
- 保留用户原始需求、约束、验收标准和偏好

信息优先级：
1. 任务目标和用户意图
2. 正确继续所需的关键事实基础
3. 未完成/进行中的工作
4. 块边界的交接状态
5. 关键决策、约束、变更和修正
6. 重要文件、制品、资源、输出和工具结果
7. 支撑细节

输出：每个 Round 独立摘要，目标长度 ≤ compression_target_tokens
```

### CurrentRoundCompressor 提示词核心

```
你是一个任务数据保存专家，负责为长时间运行的 Agent 任务生成高保真增量记忆块。

上下文结构：
  User Query
  ↓
  Accumulated Memory Blocks（持久记忆，不要重写）
  ↓
  Selected Messages（唯一要压缩的内容）
  ↓
  Recent Messages（边界上下文，仅作参考）

输出结构（强制）：
1. 用户需求 — 正在服务的原始需求
2. 当前状态 — 已完成工作、关键信息、文件/制品
3. 进行中工作 — 活跃子任务、最后操作、部分结果（交接桥）
4. 重要发现 — 决策变更、约束、错误修复、无效尝试
5. 策略状态 — 已尝试/候选/已拒绝的方法
6. 工具/动作状态 — 已使用工具、关键输入/输出

关键：增量追加，不重写已有记忆块
```

### RoundLevelCompressor 提示词核心

```
递归压缩提示词：
  你是一个兜底上下文压缩专家。
  压缩明确列出的目标，使整个任务适应严格的上下文预算。
  优先级：进行中状态 > 未完成工作 > 关键事实 > 持久结论 > 历史细节

激进压缩提示词：
  你是一个硬预算兜底压缩专家。
  上下文在早期压缩后仍超预算。
  更激进地压缩，同时保持任务可恢复。
```

---

## 七、compress_level 机制

RoundLevelCompressor 引入了 `compress_level` 概念，记录在消息的 metadata 中：

| compress_level | 含义 |
|----------------|------|
| 0 | 原始未压缩消息 |
| 1 | 经过一次 LLM 压缩 |
| 2 | 经过两次 LLM 压缩（激进） |
| 3+ | 经过多次压缩（极激进） |

**递归压缩逻辑**：

```
L0 → L1：将 compress_level=0 的 Round 压缩为 compress_level=1
L1 → L2：如果仍超预算，将 compress_level=1 的 Round 再次压缩为 compress_level=2
L2 → L3：如果仍超预算，将 compress_level=2 的 Round 再次压缩为 compress_level=3
...
```

每次递归压缩都会进一步减少 token，但信息损失也更大。DialogueCompressor 和 CurrentRoundCompressor 不使用 compress_level。

---

## 八、总结

| 对比维度 | DialogueCompressor | CurrentRoundCompressor | RoundLevelCompressor |
|----------|-------------------|----------------------|---------------------|
| **角色** | 历史清理工 | 当前轮记忆管家 | 最终兜底防线 |
| **压缩对象** | 历史已完成 Round | 当前轮已完成部分 | 全部 Round |
| **压缩方式** | 逐块独立摘要 | 增量记忆块追加 | 多轮递归 + 激进 + 截断 |
| **信息保留度** | 高（每块独立保留） | 高（增量衔接） | 逐级降低（递归压缩） |
| **LLM 调用次数** | 1 次（批量压缩） | 1 次（增量压缩） | 1~3+ 次（递归压缩） |
| **失败处理** | 跳过，交给下一个处理器 | 跳过，交给下一个处理器 | 截断兜底，保证不超预算 |
| **适用场景** | 多轮对话的历史清理 | 长时间单轮任务的中间压缩 | 极端长对话的最后防线 |

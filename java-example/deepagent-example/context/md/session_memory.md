# SessionMemory 管线三处理器详解

## 一、管线总览

SessionMemory 管线由三个处理器组成，形成**逐级升级**的上下文压缩策略：

```
ToolResultBudgetProcessor  →  MicroCompactProcessor  →  FullCompactProcessor
     (轻量卸载)                  (规则清除)                 (LLM全量压缩兜底)
```

设计哲学：**先低成本处理，再高成本兜底**。优先用无 LLM 调用的规则方法削减 token，只有规则方法无法解决时才调用 LLM 做全量压缩。

---

## 二、ToolResultBudgetProcessor —— 按轮次卸载超预算工具结果

### 2.1 核心定位

**按 API Round 粒度控制工具结果的 token 预算**。当某个 Round 内的工具结果总量超过阈值时，将最大的工具结果卸载到磁盘，只保留预览片段。

### 2.2 关键配置

| 参数 | 默认值 | 含义 |
|------|--------|------|
| `tokensThreshold` | 50000 | 每个 Round 的工具结果 token 上限 |
| `largeMessageThreshold` | 10000 | 单条消息超过此值才被视为卸载候选 |
| `trimSize` | 3000 | 卸载后保留的预览字符数 |
| `toolNameAllowlist` | null | 白名单工具名，不参与卸载 |

### 2.3 触发条件

```java
// 遍历所有 Round，检查是否有 Round 的工具结果总量超限
boolean triggerAddMessages(ModelContext context, List<BaseMessage> messagesToAdd) {
    return !roundsExceedingBudget(allMessages, context).isEmpty();
}
```

触发逻辑：
1. 用 `ContextUtils.findAllDialogueRound()` 将消息按 Round 分组
2. 计算每个 Round 内所有 ToolMessage 的 token 总量
3. 如果某个 Round 的工具结果总量 > `tokensThreshold`(50K)，且该 Round 内存在可卸载候选 → 触发

### 2.4 压缩策略

```java
// 按轮次迭代，将超预算 Round 中最大的工具结果逐个卸载
for (int[] roundRange : iterRoundRanges(updatedMessages)) {
    List<Integer> roundModified = shrinkRoundToBudget(updatedMessages, roundRange[0], roundRange[1], context);
}
```

卸载流程：
1. **从最后一个 Round 开始向前检查**（`iterRoundRanges` 逆序遍历）
2. 对超预算的 Round，收集所有可卸载的 ToolMessage（大小 > `largeMessageThreshold` 且不在白名单）
3. **按消息大小降序排列**，优先卸载最大的
4. 将原始内容持久化到磁盘（`workspace/context/{sessionId}_context/offload/`），生成唯一 handle
5. 原始 ToolMessage 替换为带预览的标记消息：

```
<persisted-output>
Output too large (123456 bytes).
[[OFFLOAD: handle=abc123, type=filesystem, path=/path/to/offload.json]]
Preview (first 3000 chars):
...前3000字符预览...
</persisted-output>
```

6. 循环卸载直到 Round 内工具结果总量 ≤ `tokensThreshold`

### 2.5 场景举例

**场景：代码分析任务，调用了多次 grep/read_file**

```
Round 3 消息：
  AssistantMessage → tool_calls: [grep("error"), grep("warning"), read_file("log.txt")]
  ToolMessage(grep "error")   → 8K tokens
  ToolMessage(grep "warning") → 12K tokens   ← 超过 largeMessageThreshold(10K)
  ToolMessage(read_file)      → 35K tokens   ← 超过 largeMessageThreshold(10K)

Round 3 工具结果总量 = 8K + 12K + 35K = 55K > tokensThreshold(50K)

→ 触发卸载
→ 按大小排序：read_file(35K) > grep "warning"(12K)
→ 先卸载 read_file：保留前 3000 字符预览，35K 持久化到磁盘
→ 检查：8K + 12K + ~3K(预览) = 23K < 50K ✓
→ 停止卸载
```

---

## 三、MicroCompactProcessor —— 纯规则清除陈旧工具结果

### 3.1 核心定位

**按工具名分组，当某个工具的结果数量超过阈值时，用纯规则清除陈旧结果，不调用 LLM**。这是"零成本"的上下文压缩手段。

### 3.2 关键配置

| 参数 | 默认值 | 含义 |
|------|--------|------|
| `triggerThreshold` | 5 | 每个工具超过 `keepRecentPerTool + triggerThreshold` 条时触发 |
| `compactableToolNames` | grep, glob, read_file, web_search, web_fetch | 参与压缩的工具名列表 |
| `keepRecentPerTool` | 15 | 每个工具保留最近的结果数 |
| `clearedMarker` | `[Old tool result content cleared]` | 清除后的替换文本 |

### 3.3 触发条件

```java
boolean triggerAddMessages(ModelContext context, List<BaseMessage> messagesToAdd) {
    // 前提：最后一个 API Round 必须已完成
    if (!apiRound(allMessages)) return false;
    // 检查是否有任何工具的结果数超过阈值
    return hasAnyToolExceedThreshold(allMessages);
}
```

触发逻辑：
1. **必须等待一个完整的 API Round 结束**（`apiRound` 检查）
2. 按工具名分组统计未清除的 ToolMessage 数量
3. 如果某个工具的结果数 > `keepRecentPerTool(15) + triggerThreshold(5) = 20` → 触发

### 3.4 压缩策略

```java
// 按工具名分组，清除超出保留数量的陈旧结果
Map<String, List<Integer>> grouped = collectCompactableIndicesByTool(messages);
for (List<Integer> indices : grouped.values()) {
    if (indices.size() > threshold) {
        // 保留最后 keepRecentPerTool 条，清除前面的
        result.addAll(indices.subList(0, indices.size() - keepRecentPerTool));
    }
}
```

清除方式：将 ToolMessage 的 content 替换为 `clearedMarker`（`[Old tool result content cleared]`），保留 role、name、toolCallId、metadata 等元信息。

### 3.5 场景举例

**场景：长时间代码搜索任务**

```
消息列表中 grep 工具结果（按时间顺序）：
  grep #1  → 50 行匹配结果
  grep #2  → 30 行匹配结果
  ...
  grep #18 → 40 行匹配结果
  grep #19 → 25 行匹配结果
  grep #20 → 35 行匹配结果
  grep #21 → 45 行匹配结果   ← 总共 21 条

  21 > keepRecentPerTool(15) + triggerThreshold(5) = 20 → 触发

  清除策略：保留最近 15 条，清除前 6 条
  grep #1~#6 → content 替换为 "[Old tool result content cleared]"
  grep #7~#21 → 保留原始内容
```

**为什么只清除 compactableToolNames 中的工具？**

因为这些工具（grep、glob、read_file 等）的结果通常是**一次性的查询结果**——用完即弃，旧结果的价值很低。而像 write_file、edit_file 等工具的结果可能包含重要的操作确认信息，不应被清除。

---

## 四、FullCompactProcessor —— LLM 全量压缩 + 状态重新注入兜底

### 4.1 核心定位

**当上下文总量超限时，使用 LLM 生成对话摘要，替换历史消息，并重新注入关键状态信息**。这是 SessionMemory 管线的最终兜底手段。

### 4.2 关键配置

| 参数 | 默认值 | 含义 |
|------|--------|------|
| `triggerTotalTokens` | 180000 | 总 token 超过此值触发 |
| `compressionCallMaxTokens` | 200000 | 压缩调用 LLM 的最大 token 预算 |
| `messagesToKeep` | 10 | 保留最近 N 条消息不压缩 |
| `isKeepToolMessagePairs` | true | 保留区是否保持 ToolMessage 与对应 AssistantMessage 的配对 |
| `isSessionMemoryEnabled` | true | 是否优先使用 SessionMemory 替代 LLM 摘要 |
| `stateSnapshotMaxChars` | 4000 | 状态重新注入内容的最大字符数 |

### 4.3 触发条件

```java
boolean triggerAddMessages(ModelContext context, List<BaseMessage> messagesToAdd) {
    // 前提：最后一个 API Round 必须已完成
    if (!apiRound(candidateMessages)) return false;
    // 总 token 超过阈值
    return candidateTokens > triggerTotalTokens;
}
```

### 4.4 压缩策略 —— 双路径设计

FullCompactProcessor 有两条压缩路径，**优先尝试 SessionMemory 路径，失败则回退到 FullCompact 路径**：

```
                    ┌─────────────────────────┐
                    │  触发：总量 > 180K token  │
                    └────────────┬────────────┘
                                 │
                    ┌────────────▼────────────┐
                    │ sessionMemoryEnabled?    │
                    └────┬──────────────┬─────┘
                    Yes  │              │ No
                         ▼              ▼
            ┌──────────────────┐   ┌──────────────────┐
            │ SessionMemory路径 │   │ FullCompact路径   │
            │ (用笔记文件替代)   │   │ (LLM生成摘要)     │
            └────────┬─────────┘   └────────┬─────────┘
                     │                      │
                     ▼                      ▼
            ┌──────────────────┐   ┌──────────────────┐
            │ token ≤ 阈值?     │   │ 生成摘要成功?     │
            └────┬───────┬─────┘   └────┬───────┬─────┘
              Yes │       │ No         Yes │       │ No
                  ▼       ▼               ▼       ▼
               采用    回退到          采用    返回 null
              SM路径   FullCompact    FC路径   (压缩失败)
```

#### 路径 A：SessionMemory 路径

当 `isSessionMemoryEnabled=true` 时优先尝试：

1. 从 Session 状态中加载 SessionMemory 运行时信息（`memory_path`、`notes_upto_message_id`、`is_extracting`）
2. 读取 SessionMemory 笔记文件内容
3. 根据 `notes_upto_message_id` 确定哪些消息已被笔记覆盖
4. 保留笔记覆盖点之后的消息
5. 构建新的消息列表：`[prefix] + [boundary] + [sessionMemoryMessage] + [preservedMessages] + [stateReinject]`
6. 检查新列表的 token 是否 ≤ `triggerTotalTokens`，如果是则采用

#### 路径 B：FullCompact 路径

当 SessionMemory 不可用或 token 仍超限时使用：

1. **找到压缩边界**：查找最后一个 `[FULL_COMPACT_BOUNDARY]` 标记
2. **分割消息**：边界之前为 prefix（保留），边界之后为 activeMessages（压缩目标）
3. **准备压缩输入**：去除边界消息、状态消息、媒体消息
4. **截断适配预算**：如果压缩输入超过 `compressionCallMaxTokens`，按 API Round 粒度从头部丢弃，直到适配
5. **LLM 生成摘要**：调用 LLM 生成 `<analysis>` + `<summary>` 格式的摘要
6. **选择保留消息**：保留最近 `messagesToKeep(10)` 条消息
7. **构建新消息列表**：`[prefix] + [boundary] + [summaryMessage] + [retainedMessages] + [stateReinject]`

### 4.5 状态重新注入机制

FullCompactProcessor 的独特之处在于**压缩后重新注入关键状态信息**，确保 LLM 不会丢失重要的工作上下文：

```java
stateReinjector.registerBuilder("skills", "SKILLS", FullCompactProcessorUtil::buildSkillReinjectedContent);
stateReinjector.registerBuilder("task_status", "TASK_STATUS", FullCompactProcessorUtil::buildTaskStatusReinjectedContent);
stateReinjector.registerBuilder("plan_mode", "PLAN_MODE", FullCompactProcessorUtil::buildPlanModeReinjectedContent);
```

| 状态 | 标签 | 内容 | 何时注入 |
|------|------|------|----------|
| **Skills** | `[SKILLS]` | 最近调用 read_file 读取 SKILL.md 的 API Round 内容 | FullCompact 路径 + SessionMemory 路径 |
| **Task Status** | `[TASK_STATUS]` | 外循环迭代数、待处理 Follow-up 数、停止原因 | 仅 FullCompact 路径 |
| **Plan Mode** | `[PLAN_MODE]` | 当前规划模式、前一个模式、计划标识符 | 仅 FullCompact 路径 |

**Skills 重新注入的细节**：

```java
// 从压缩目标消息中，找到包含 read_file(*SKILL.md) 的 Round
// 保留最近 reinjectRecentSkills(3) 个不重复的 Skill Round
// 将这些 Round 序列化后注入到新消息列表末尾
```

这确保了即使对话被压缩，LLM 仍然知道有哪些 Skill 可用。

### 4.6 场景举例

**场景：长时间代码重构任务，上下文膨胀到 200K token**

```
压缩前消息列表（200K token）：
  [0]  SystemMessage (系统提示)
  [1]  UserMessage (重构需求)
  [2]  AssistantMessage (分析代码)
  ...
  [95] AssistantMessage (工具调用: read_file("skill.md"))
  [96] ToolMessage (Skill 内容)
  [97] AssistantMessage (最终)
  ...
  [150] AssistantMessage (工具调用: grep)
  [151] ToolMessage (grep 结果)
  [152] AssistantMessage (最终)
  ...
  [198] AssistantMessage (工具调用: edit_file)
  [199] ToolMessage (编辑确认)
  [200] AssistantMessage (最终)  ← 最近 10 条保留

压缩过程：
  1. 总量 200K > triggerTotalTokens(180K) → 触发
  2. 尝试 SessionMemory 路径：
     - 读取笔记文件 → 内容为空 → 跳过
  3. 使用 FullCompact 路径：
     - 无已有 boundary → activeMessages = 全部消息
     - 准备压缩输入：去除 boundary/state 消息
     - 截断适配：从头部丢弃旧 Round 直到 ≤ 200K
     - LLM 生成摘要："用户要求重构 X 模块，已完成 Y 和 Z..."
     - 保留最近 10 条消息 [191]~[200]
     - 重新注入 Skills 状态（Round [95]~[97] 的 Skill 内容）
     - 重新注入 Task Status（迭代数=3, follow-up=1）
     - 重新注入 Plan Mode（mode=auto）

压缩后消息列表（约 60K token）：
  [0]  SystemMessage
  [1]  SystemMessage [FULL_COMPACT_BOUNDARY]
  [2]  UserMessage (摘要: "This session is continued from a previous conversation...")
  [3]  UserMessage (摘要内容: <analysis>...<summary>...)
  [4]  AssistantMessage (最近10条中的第1条)
  ...
  [13] AssistantMessage (最近10条中的最后1条)
  [14] UserMessage [FULL_COMPACT_STATE] [SKILLS] (Skill 内容)
  [15] UserMessage [FULL_COMPACT_STATE] [TASK_STATUS] (任务状态)
  [16] UserMessage [FULL_COMPACT_STATE] [PLAN_MODE] (规划模式)
```

---

## 五、三处理器协作关系

```
消息流入 ContextEngine
        │
        ▼
┌─────────────────────────────────────┐
│ ToolResultBudgetProcessor           │
│ 触发：某 Round 工具结果 > 50K token  │
│ 动作：卸载最大工具结果到磁盘          │
│ 成本：无 LLM 调用                    │
│ 效果：削减单个 Round 的膨胀          │
└─────────────────┬───────────────────┘
                  │ 处理后的消息流
                  ▼
┌─────────────────────────────────────┐
│ MicroCompactProcessor               │
│ 触发：某工具结果数 > 20 条            │
│ 动作：清除陈旧结果，保留最近 15 条     │
│ 成本：无 LLM 调用                    │
│ 效果：削减跨 Round 的结果堆积         │
└─────────────────┬───────────────────┘
                  │ 处理后的消息流
                  ▼
┌─────────────────────────────────────┐
│ FullCompactProcessor                │
│ 触发：总 token > 180K                │
│ 动作：LLM 生成摘要 + 状态重新注入     │
│ 成本：1 次 LLM 调用                  │
│ 效果：全量压缩，token 大幅下降         │
└─────────────────────────────────────┘
```

### 协作示例：完整的压缩流程

**场景：Agent 执行了一个复杂的代码分析任务，进行了 30 轮工具调用**

```
初始状态：200K token，25 个 Round

第 1 步：ToolResultBudgetProcessor
  Round 15 的工具结果总量 = 65K > 50K
  → 卸载 read_file 返回的 40K 内容到磁盘
  → Round 15 降至约 28K
  → 总量降至约 163K

第 2 步：MicroCompactProcessor
  grep 工具有 22 条结果 > 20 条阈值
  → 清除前 7 条陈旧 grep 结果
  → 总量降至约 150K

此时总量 150K < 180K → FullCompactProcessor 不触发

...Agent 继续执行，又增加了 10 轮...

总量增长到 190K > 180K

第 3 步：FullCompactProcessor 触发
  → 尝试 SessionMemory：笔记文件可用
  → 用笔记内容替代历史消息
  → 保留笔记覆盖点之后的 10 条消息
  → 重新注入 Skills + Task Status + Plan Mode
  → 总量降至约 50K
```

---

## 六、与标准管线对比

| 维度 | SessionMemory 管线 | 标准管线 |
|------|-------------------|----------|
| 处理器数量 | 3 个 | 4 个 |
| LLM 调用 | 仅 FullCompact 调用 | DialogueCompressor + CurrentRoundCompressor + RoundLevelCompressor 均调用 |
| 压缩粒度 | Round 级别（卸载/清除/全量） | Round 级别 + 当前轮增量 + 多轮递归 |
| 状态保持 | 通过 SessionMemory 笔记 + 状态重新注入 | 通过 LLM 摘要保留语义 |
| 适用场景 | 长会话、工具密集型任务 | 精细化压缩、多轮对话 |
| 压缩成本 | 低（2/3 处理器无 LLM 调用） | 较高（3/4 处理器需 LLM 调用） |
| 兜底能力 | FullCompact 全量压缩 + 状态注入 | RoundLevelCompressor 多轮递归压缩 |

---

## 七、设计精髓总结

1. **逐级升级**：先用零成本规则方法（卸载、清除），再用高成本 LLM 方法（全量压缩），最小化 LLM 调用开销
2. **按需触发**：每个处理器独立判断触发条件，不互相依赖
3. **Round 感知**：ToolResultBudgetProcessor 和 MicroCompactProcessor 都以 API Round 为粒度操作，保证消息结构的完整性
4. **状态不丢失**：FullCompactProcessor 通过状态重新注入机制，确保压缩后 LLM 仍能获取 Skill、任务状态、规划模式等关键信息
5. **双路径兜底**：FullCompact 优先使用 SessionMemory 笔记替代历史，失败时回退到 LLM 摘要，提供两层保障

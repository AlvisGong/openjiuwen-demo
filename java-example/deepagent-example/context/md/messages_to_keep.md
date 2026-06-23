# messagesToKeep 参数详解

## 一、核心含义

`messagesToKeep` = **从消息列表末尾起，保留最近 N 条消息不参与压缩**。

它定义了一条"保护线"——保护线以内的消息是 LLM 最近正在处理的上下文，不能被压缩；保护线以外的消息才是压缩目标。

---

## 二、各处理器的默认值

| 处理器 | 默认值 | 说明 |
|--------|--------|------|
| DialogueCompressor | `10` | 保留最近 10 条消息 |
| CurrentRoundCompressor | `3` | 保留最近 3 条消息 |
| FullCompactProcessor | `10` | 保留最近 10 条消息 |

---

## 三、作用机制

### 3.1 DialogueCompressor 中的 messagesToKeep

```java
int getCompressIdx(List<BaseMessage> messages) {
    int keepIndex = messagesToKeep == null ? messages.size() : messages.size() - messagesToKeep;
    // keepIndex 就是压缩的上界，只压缩 keepIndex 之前的消息
}
```

### 3.2 CurrentRoundCompressor 中的 messagesToKeep

```java
// 压缩结束位置 = 保留区起始 - 1
int keepStartIdx = Math.max(0, contextMessages.size() - messagesToKeep);
int endIdx = keepStartIdx - 1;  // 压缩到保留区之前

// 触发判断：如果总消息数 < messagesToKeep，不触发
if (messageSize < messagesToKeep) {
    return false;
}

// 压缩起始判断：如果最后一个 UserMessage 在保留区内，不压缩
int keepIndex = messages.size() - messagesToKeep;
if (compressedIdx >= keepIndex) {
    return -1;  // 在保留区内，不压缩
}
```

---

## 四、图解说明

### 场景 1：CurrentRoundCompressor（messagesToKeep=3）

```
消息列表（共 13 条）：

[0]  SystemMessage
[1]  UserMessage              ← Round 1
[2]  AssistantMessage
[3]  ToolMessage
[4]  AssistantMessage(最终)
[5]  UserMessage              ← Round 2（当前轮起始）
[6]  AssistantMessage(工具调用)
[7]  ToolMessage
[8]  AssistantMessage(工具调用)
[9]  ToolMessage
[10] AssistantMessage(工具调用)  ┐
[11] ToolMessage                │ 保留区（最近 3 条）
[12] AssistantMessage(最终)     ┘

keepStartIdx = 13 - 3 = 10
endIdx = 10 - 1 = 9

压缩范围：[6]~[9]（当前轮中保留区之前的消息）
保留区：[10][11][12]（最近 3 条，不压缩）
```

### 场景 2：DialogueCompressor（messagesToKeep=10）

```
消息列表（共 20 条）：

[0]  SystemMessage
[1]  UserMessage              ← Round 1
[2]  AssistantMessage
[3]  UserMessage              ← Round 2
[4]  AssistantMessage
[5]  ToolMessage
[6]  AssistantMessage(最终)
[7]  UserMessage              ← Round 3
[8]  AssistantMessage
[9]  ToolMessage
[10] AssistantMessage(最终)     ┐
[11] UserMessage               │
[12] AssistantMessage          │
[13] ToolMessage               │ 保留区（最近 10 条）
[14] AssistantMessage(最终)     │
[15] UserMessage               │
[16] AssistantMessage          │
[17] ToolMessage               │
[18] AssistantMessage(最终)     ┘
[19] (新消息即将写入)

keepIndex = 20 - 10 = 10

压缩范围：[1]~[9]（Round 1 + Round 2 + Round 3）
保留区：[10]~[19]（最近 10 条，不压缩）
```

### 场景 3：messagesToKeep 导致不触发

```
消息列表（共 8 条，messagesToKeep=10）：

[0] SystemMessage
[1] UserMessage
[2] AssistantMessage
[3] ToolMessage
[4] AssistantMessage(最终)
[5] UserMessage
[6] AssistantMessage
[7] ToolMessage

总消息数 8 < messagesToKeep 10 → 不触发压缩
原因：消息总量太少，全部保留
```

---

## 五、messagesToKeep 的影响

| 设置 | 效果 | 风险 |
|------|------|------|
| **值越大** | 保留更多最近消息，LLM 上下文更完整 | 压缩空间变小，可能无法有效降低 token |
| **值越小** | 压缩空间更大，token 降幅更显著 | LLM 可能丢失重要的近期上下文 |
| **值 = 0** | 所有消息都可压缩 | 极端情况，LLM 可能完全丢失当前状态 |

---

## 六、与 keepLastRound 的配合

DialogueCompressor 还有一个 `keepLastRound` 参数，与 `messagesToKeep` 配合使用：

```java
int getCompressIdx(List<BaseMessage> messages) {
    int keepIndex = messagesToKeep == null ? messages.size() : messages.size() - messagesToKeep;
    if (!isKeepLastRound) {
        return keepIndex;  // 只按 messagesToKeep 保留
    }
    // keepLastRound=true 时，还要找到最后一个完整 Round 的结束位置
    Integer lastFinalAssistantIdx = findLastFinalAssistantIdx(messages);
    return Math.min(lastFinalAssistantIdx, keepIndex);
    // 取两者中更小的，确保当前轮不被压缩
}
```

| keepLastRound | 效果 |
|---------------|------|
| `false` | 只按 messagesToKeep 条数保留，可能截断当前轮 |
| `true` | 额外保证当前轮（最后一个完整 Round）不被压缩 |

---

## 七、实际配置建议

| 场景 | CurrentRoundCompressor | DialogueCompressor | 原因 |
|------|----------------------|--------------------|------|
| 代码重构（长任务） | 5 | 10 | 需要更多近期上下文保持代码连贯性 |
| 问答咨询（短轮次） | 3 | 6 | 每轮消息少，保留少量即可 |
| 数据分析（工具密集） | 3 | 10 | 工具结果多但可压缩，保留关键结论即可 |

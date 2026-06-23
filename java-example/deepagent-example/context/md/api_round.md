# API Round 与上下文压缩中的"轮次"概念解析

## 一、三个"轮次"概念的区别

在 DeepAgent 的上下文压缩体系中，存在三个容易混淆的"轮次"概念：

| 概念 | 范围 | 定义者 | 说明 |
|------|------|--------|------|
| **当前轮次** | 最后一个 UserMessage 之后的消息 | `CurrentRoundCompressor.getCompressIdx()` | CurrentRoundCompressor 的压缩范围 |
| **API Round** | UserMessage → Assistant+Tool* → Assistant(最终) | `SessionMemoryManager.groupCompletedApiRounds()` | 一次完整的 LLM 调用链 |
| **ReActAgent 循环** | 可能包含多个 API Round | `ReActAgent.run()` | 一次完整的 Reasoning-Acting 循环 |

---

## 二、API Round 的精确定义

### 2.1 源码定义

API Round 由 `SessionMemoryManager.groupCompletedApiRounds()` 定义：

```java
// SessionMemoryManager.java:285
public static List<int[]> groupCompletedApiRounds(List<BaseMessage> messages) {
    List<int[]> rounds = new ArrayList<>();
    Integer currentStart = null;
    Set<String> pendingToolCallIds = null;

    for (int index = 0; index < messages.size(); index++) {
        BaseMessage message = messages.get(index);

        // 起始：遇到 UserMessage 且无待处理的工具调用
        if (currentStart == null) {
            currentStart = index;
        } else if (message instanceof UserMessage && pendingToolCallIds == null) {
            currentStart = index;  // 新的 UserMessage 开始新的 Round
        }

        // AssistantMessage 分支
        if (message instanceof AssistantMessage assistantMessage) {
            List<ToolCall> toolCalls = assistantMessage.getToolCalls();
            if (!toolCalls.isEmpty()) {
                // 带工具调用：记录待处理的 tool_call_id
                pendingToolCallIds = new HashSet<>();
                for (ToolCall toolCall : toolCalls) {
                    pendingToolCallIds.add(toolCall.getId());
                }
                if (pendingToolCallIds.isEmpty()) {
                    rounds.add(new int[]{currentStart, index + 1});
                    currentStart = null;
                }
                continue;
            }
            // 不带工具调用：Round 结束
            rounds.add(new int[]{currentStart, index + 1});
            currentStart = null;
            pendingToolCallIds = null;
            continue;
        }

        // ToolMessage 分支：从待处理列表中移除
        if (message instanceof ToolMessage toolMessage && pendingToolCallIds != null) {
            pendingToolCallIds.remove(toolMessage.getToolCallId());
            if (pendingToolCallIds.isEmpty()) {
                // 所有工具调用都已回复，但 Round 还未结束（等待 Assistant 最终回复）
            }
        }
    }
    return rounds;
}
```

### 2.2 API Round 的结构

```
一个完整的 API Round：

UserMessage                          ← Round 起始
  → AssistantMessage(tool_calls)     ← LLM 决定调用工具
  → ToolMessage                      ← 工具执行结果
  → AssistantMessage(tool_calls)     ← LLM 继续调用工具
  → ToolMessage                      ← 工具执行结果
  → AssistantMessage(无 tool_calls)  ← Round 结束（LLM 最终回复）
```

**关键判定规则**：

| 消息类型 | 对 Round 的影响 |
|----------|----------------|
| `UserMessage` | 开始新的 Round（当无待处理工具调用时） |
| `AssistantMessage(带 tool_calls)` | 记录待处理的 tool_call_id，Round 继续 |
| `ToolMessage` | 从待处理列表移除对应 id，Round 继续 |
| `AssistantMessage(无 tool_calls)` | Round 结束 |

### 2.3 API Round 示例

**示例 1：简单 Round（一次 LLM 调用即完成）**

```
[0] UserMessage: "今天天气怎么样"
[1] AssistantMessage: "今天北京晴天，气温25°C"  ← 无 tool_calls，Round 结束

→ 1 个 API Round: [0, 2)
```

**示例 2：带工具调用的 Round**

```
[0] UserMessage: "帮我查一下北京天气"
[1] AssistantMessage(tool_calls: [weather_query])  ← 调用工具
[2] ToolMessage(tool_call_id: weather_query)       ← 工具结果
[3] AssistantMessage: "北京今天晴天，25°C"          ← 无 tool_calls，Round 结束

→ 1 个 API Round: [0, 4)
```

**示例 3：多轮工具调用的 Round**

```
[0] UserMessage: "对比北京和上海天气"
[1] AssistantMessage(tool_calls: [weather_beijing])    ← 第1次工具调用
[2] ToolMessage(tool_call_id: weather_beijing)
[3] AssistantMessage(tool_calls: [weather_shanghai])   ← 第2次工具调用
[4] ToolMessage(tool_call_id: weather_shanghai)
[5] AssistantMessage: "北京25°C晴天，上海22°C多云"      ← Round 结束

→ 1 个 API Round: [0, 6)
```

**示例 4：Steer 注入导致多个 Round**

```
[0] UserMessage: "查北京天气"
[1] AssistantMessage(tool_calls: [weather_query])
[2] ToolMessage(tool_call_id: weather_query)
[3] AssistantMessage: "北京今天晴天，25°C"              ← Round 1 结束
[4] UserMessage(Steer): "也查一下上海的"                ← 新 Round 开始
[5] AssistantMessage(tool_calls: [weather_shanghai])
[6] ToolMessage(tool_call_id: weather_shanghai)
[7] AssistantMessage: "上海今天多云，22°C"              ← Round 2 结束

→ 2 个 API Round: [0, 4), [4, 8)
```

---

## 三、CurrentRoundCompressor 中的"当前轮次"

### 3.1 压缩范围确定

```java
// CurrentRoundCompressor.java:572
int getCompressIdx(List<BaseMessage> messages) {
    // 从后往前找最后一个 UserMessage
    int compressedIdx = -1;
    for (int index = messages.size() - 1; index >= 0; index--) {
        if (messages.get(index) instanceof UserMessage) {
            compressedIdx = index;
            break;
        }
    }
    if (compressedIdx == messages.size() - 1) {
        return -1;  // 最后一条就是 UserMessage，没有可压缩的内容
    }
    if (compressedIdx < 0) {
        return -1;  // 没有 UserMessage
    }
    int keepIndex = messages.size() - messagesToKeep;
    if (compressedIdx >= keepIndex) {
        return -1;  // 在保留区内，不压缩
    }
    return compressedIdx;
}
```

### 3.2 压缩范围示意

```
消息列表（messagesToKeep=3）：

[0] SystemMessage
[1] UserMessage          ← 上一轮
[2] AssistantMessage
[3] ToolMessage
[4] AssistantMessage     ← 上一轮结束
[5] UserMessage          ← 当前轮起始（getCompressIdx 返回 5）
[6] AssistantMessage     ← 当前轮：LLM 调用工具
[7] ToolMessage          ← 当前轮：工具结果
[8] AssistantMessage     ← 当前轮：LLM 继续调用工具
[9] ToolMessage          ← 当前轮：工具结果
[10] AssistantMessage    ← 当前轮：LLM 最终回复

保留区（最近 3 条）：[8][9][10] 不压缩
压缩范围：[6][7]（当前轮中保留区之前的部分）
```

### 3.3 进一步精确到 API Round 边界

```java
// CurrentRoundCompressor.java:604
actualEndIdx = findLastCompletedApiRoundEndIdx(workingMessages, startIdx, actualEndIdx);
```

压缩时不会在 API Round 中间截断，而是找到最后一个**完整的 API Round** 的结束位置，只压缩完整的 Round。

---

## 四、DialogueCompressor 中的"轮次"

DialogueCompressor 的"轮次"与 API Round 定义一致，但它关注的是**历史对话中已完成的 Round**：

```
消息列表：

[0] SystemMessage
[1] UserMessage          ← Round 1
[2] AssistantMessage     ← Round 1 结束
[3] UserMessage          ← Round 2
[4] AssistantMessage(tool_calls)
[5] ToolMessage
[6] AssistantMessage     ← Round 2 结束
[7] UserMessage          ← Round 3（当前轮，不压缩）
[8] AssistantMessage     ← 当前轮

DialogueCompressor 压缩范围：Round 1 + Round 2（历史已完成 Round）
保留区：messagesToKeep 条最近消息 + 当前轮
```

---

## 五、RoundLevelCompressor 中的"轮次"

RoundLevelCompressor 的粒度最粗，它将**多个连续的 API Round** 合并压缩为一个新 Round：

```
压缩前（3 个 API Round）：

Round 1: UserMessage → AssistantMessage
Round 2: UserMessage → AssistantMessage(tool_calls) → ToolMessage → AssistantMessage
Round 3: UserMessage → AssistantMessage

压缩后（1 个新 Round）：

New UserMessage: "以下是对话摘要：..."
New AssistantMessage: "已理解上述上下文，继续执行..."
```

**RoundLevelCompressor 的触发条件**：

- 累计 token 超过 `tokensThreshold`（默认 230K）
- 存在至少 `roundsThreshold` 个连续完整 Round

---

## 六、三种压缩器的"轮次"对比

| 压缩器 | 压缩粒度 | 压缩范围 | 保留策略 |
|--------|----------|----------|----------|
| **CurrentRoundCompressor** | 当前轮次内的 API Round | 最后一个 UserMessage 之后、保留区之前 | `messagesToKeep` 条最近消息 |
| **DialogueCompressor** | 历史已完成的 API Round | 当前轮之前的所有历史 Round | 当前轮 + `messagesToKeep` 条 |
| **RoundLevelCompressor** | 多个连续 API Round 合并 | 所有满足条件的连续 Round 窗口 | `keepRecentMessages` 条 |

---

## 七、与 ReActAgent 循环的关系

```
DeepAgent 外循环一轮迭代
    │
    └─ ReActAgent 内循环
        │
        ├─ LLM 推理 #1 → AssistantMessage(tool_calls)
        │   └─ 工具执行 → ToolMessage
        │
        ├─ LLM 推理 #2 → AssistantMessage(tool_calls)
        │   └─ 工具执行 → ToolMessage
        │
        ├─ Steer 注入 → UserMessage（新的 API Round 开始）
        │
        ├─ LLM 推理 #3 → AssistantMessage(tool_calls)
        │   └─ 工具执行 → ToolMessage
        │
        └─ LLM 推理 #4 → AssistantMessage(最终回复)

    = 2 个 API Round（Steer 在中间注入了新 UserMessage）
    = 1 次 ReActAgent 循环
    = DeepAgent 外循环的 1 轮迭代
```

**总结**：

- **1 次 ReActAgent 循环 ≥ 1 个 API Round**（正常情况下 = 1，Steer 注入时 > 1）
- **CurrentRoundCompressor 压缩的是 API Round，不是 ReActAgent 循环**
- **API Round 是上下文压缩体系中最核心的边界概念**

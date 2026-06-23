# ContextProcessorRail 深度解析

## 一、ContextProcessorRail 在 DeepAgent 中的定位

`ContextProcessorRail` 是 DeepAgent harness 层的核心 Rail 之一，负责**上下文处理管线的构建、注入与生命周期管理**。它是连接 DeepAgent 外层 harness 与内层 ReActAgent 上下文引擎的桥梁。

```
┌──────────────────────────────────────────────────────────────────┐
│  DeepAgent (外循环)                                               │
│                                                                  │
│  ┌─ Harness Rails ─────────────────────────────────────────────┐ │
│  │  ContextAssembleRail (priority=85) → 动态组装上下文信息     │ │
│  │  ContextProcessorRail (priority=85) → 构建上下文处理管线     │ │
│  │  SessionRail / SecurityRail / ...    → 其他护栏              │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                           ↓ 注入                                 │
│  ┌─ ReActAgent (内循环) ──────────────────────────────────────┐ │
│  │  ReActAgentConfig                                           │ │
│  │    └─ contextProcessors: [ProcessorSpec, ...]               │ │
│  │         └─ SessionModelContext                               │ │
│  │              ├─ addMessages() → 写入拦截                     │ │
│  │              └─ getContextWindow() → 读出拦截                │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

**核心职责**：

| 职责 | 说明 |
|------|------|
| 构建处理器管线 | 根据配置（preset/sessionMemory）选择预设处理器组合 |
| 注入到 ReActAgent | 将处理器规格写入 `ReActAgentConfig`，驱动内循环的上下文处理 |
| 卸载提示注入 | 在 System Prompt 中注入上下文重载说明，告知 LLM 如何使用 reload 工具 |
| 工具上下文修复 | 修复因中断导致的 AssistantMessage-ToolMessage 不配对问题 |
| SessionMemory 调度 | 当启用 sessionMemory 时，在每次模型调用后调度记忆更新 |

---

## 二、类结构与构造参数

### 2.1 类继承关系

```
AgentRail (核心基类，定义生命周期钩子)
  └─ DeepAgentRail (DeepAgent 专用基类，默认 priority=50)
       └─ ContextProcessorRail (priority=85)
            └─ AutoHarnessContextRail (子类，跳过 prompt 注入和 uninit)
```

### 2.2 构造参数

```java
public ContextProcessorRail(
    boolean isPreset,              // 是否使用预设处理器管线
    List<String> processorKeys,    // 额外追加的处理器名称列表
    boolean isSessionMemoryEnabled // 是否启用 SessionMemory
)
```

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `isPreset` | boolean | true | 是否自动安装预设处理器管线 |
| `processorKeys` | List\<String\> | 空列表 | 额外追加的处理器名称，如 `"ToolResultBudgetProcessor"` |
| `isSessionMemoryEnabled` | boolean | false | 是否启用 SessionMemory 跨轮次记忆管理 |

### 2.3 通过 HarnessConfig 创建

`ContextProcessorRail` 由 `HarnessConfigBuilder` 根据 harness 配置文件自动创建：

```java
// HarnessConfigBuilder.java:323
private static ContextProcessorRail createContextProcessorRail(
        Path root, HarnessConfig.RailResourceSchema spec) {
    Map<String, Object> config = railConfig(spec);
    return new ContextProcessorRail(
        booleanValue(config.get("preset"), true),
        stringList(config.get("processor_keys")),
        booleanValue(config.get("session_memory_enabled"), false)
    );
}
```

对应的 YAML 配置示例：

```yaml
rails:
  context_processor:
    preset: true
    processor_keys:
      - ToolResultBudgetProcessor
    session_memory_enabled: false
```

---

## 三、生命周期钩子详解

`ContextProcessorRail` 实现了 6 个生命周期钩子，覆盖了 ReAct 内循环的关键节点：

```
ReAct 内循环执行流程
    │
    ├─ beforeInvoke() ──────→ fixIncompleteToolContext()  修复工具上下文
    │
    ├─ beforeModelCall() ───→ injectOffloadSection()     注入卸载提示
    │
    │   ┌─ LLM 推理 ─┐
    │   └──────────────┘
    │
    ├─ afterModelCall() ────→ sessionMemoryManager       调度 SessionMemory 更新
    │                           .maybeScheduleUpdate()
    │
    ├─ onModelException() ──→ fixIncompleteToolContext()  异常时也修复工具上下文
    │
    └─ (uninit) ────────────→ 清空处理器、移除卸载提示
```

### 3.1 init() — 处理器管线构建与注入

```java
// ContextProcessorRail.java:74
@Override
public void init(Object agent) {
    if (!(agent instanceof DeepAgent deepAgent)) {
        return;
    }
    this.owner = deepAgent;
    installedProcessors.clear();
    List<ContextEngine.ProcessorSpec> specs;
    if (deepAgent.getAgent().getConfig() instanceof ReActAgentConfig config) {
        specs = buildProcessorSpecs(config);
        installedProcessors.addAll(specs);
        config.configureContextProcessors(new ArrayList<>(installedProcessors));
        deepAgent.getAgent().configure(config);
    } else {
        specs = buildProcessorSpecs(null);
        installedProcessors.addAll(specs);
    }
}
```

**执行流程**：

1. 检查 agent 是否为 `DeepAgent` 实例
2. 调用 `buildProcessorSpecs()` 根据配置构建处理器规格列表
3. 将规格列表保存到 `installedProcessors`
4. 通过 `config.configureContextProcessors()` 注入到 `ReActAgentConfig`
5. 调用 `deepAgent.getAgent().configure(config)` 使配置生效

**关键点**：处理器规格注入到 `ReActAgentConfig` 后，`ContextEngine.createContext()` 会根据规格创建实际的 `ContextProcessor` 实例，挂载到 `SessionModelContext` 上。

### 3.2 uninit() — 清理与反注册

```java
// ContextProcessorRail.java:93
@Override
public void uninit(Object agent) {
    if (agent instanceof DeepAgent deepAgent) {
        if (deepAgent.getAgent().getConfig() instanceof ReActAgentConfig config) {
            config.configureContextProcessors(List.of());  // 清空处理器
            deepAgent.getAgent().configure(config);
        }
        deepAgent.getAgent().getPromptBuilder().removeSection(OFFLOAD_SECTION);  // 移除卸载提示
    }
    installedProcessors.clear();
    owner = null;
}
```

**清理动作**：
1. 将 `ReActAgentConfig` 的处理器列表置空
2. 移除 System Prompt 中的 `offload` Section
3. 清空 `installedProcessors`
4. 释放 `owner` 引用

### 3.3 beforeInvoke() — 工具上下文修复

```java
// ContextProcessorRail.java:105
@Override
public void beforeInvoke(AgentCallbackContext ctx) {
    fixIncompleteToolContext(ctx);
}
```

在每次工具调用前，修复因中断或异常导致的 `AssistantMessage`（含 ToolCall）与 `ToolMessage` 不配对问题。详见第六节。

### 3.4 beforeModelCall() — 卸载提示注入

```java
// ContextProcessorRail.java:112
@Override
public void beforeModelCall(AgentCallbackContext ctx) {
    injectOffloadSection();
}
```

在每次 LLM 调用前，向 System Prompt 注入上下文重载说明。详见第五节。

### 3.5 afterModelCall() — SessionMemory 调度

```java
// ContextProcessorRail.java:119
@Override
public void afterModelCall(AgentCallbackContext ctx) {
    if (!isSessionMemoryEnabled || sessionMemoryManager == null || ctx == null) {
        return;
    }
    sessionMemoryManager.maybeScheduleUpdate(
        ctx.getSession(), ctx.getContext(), owner != null ? owner.getWorkspace() : null);
}
```

当启用 `sessionMemory` 时，在每次 LLM 调用后检查是否需要更新 SessionMemory：

1. 检查 `isSessionMemoryEnabled` 开关
2. 调用 `sessionMemoryManager.maybeScheduleUpdate()` 判断是否满足更新条件
3. `maybeScheduleUpdate()` 内部逻辑：
   - 截取上下文窗口到最近完成的 API 轮次
   - 计算 token 增量和工具调用增量
   - 如果增量超过阈值，触发 SessionMemory 更新

### 3.6 onModelException() — 异常时修复

```java
// ContextProcessorRail.java:130
@Override
public void onModelException(AgentCallbackContext ctx) {
    fixIncompleteToolContext(ctx);
}
```

与 `beforeInvoke()` 相同的修复逻辑，确保异常路径下也能修复工具上下文。

---

## 四、处理器管线构建 — buildProcessorSpecs()

### 4.1 两种预设管线

`buildProcessorSpecs()` 根据 `isPreset` 和 `isSessionMemoryEnabled` 两个开关选择不同的处理器组合：

```
                    isPreset?
                   /        \
                 true       false
                  |           |
         isSessionMemory?    仅安装 processorKeys
          /          \       中指定的处理器
        true         false
         |            |
   SessionMemory    标准管线
     管线
```

#### 预设 A：标准管线（isPreset=true, isSessionMemoryEnabled=false）

```java
// ContextProcessorRail.java:237-278
// 1. MessageSummaryOffloader — 大消息自适应压缩卸载
putSpec(specs, "MessageSummaryOffloader", MessageSummaryOffloaderConfig.builder()
    .tokensThreshold(60000)          // 总 token 超 60K 时触发
    .largeMessageThreshold(60000)    // 单条消息超 60K token 时卸载
    .offloadMessageType(List.of("tool"))
    .protectedToolNames(List.of("read_file:*SKILL.md", "reload_original_context_messages"))
    .messagesToKeep(null)
    .keepLastRound(false)
    .model(modelConfig).modelClient(modelClientConfig)
    .build());

// 2. DialogueCompressor — 对话压缩
putSpec(specs, "DialogueCompressor", DialogueCompressorConfig.builder()
    .tokensThreshold(100000)         // 总 token 超 100K 时触发
    .messagesToKeep(10)
    .keepLastRound(false)
    .compressionTargetTokens(1800)
    .model(modelConfig).modelClient(modelClientConfig)
    .build());

// 3. CurrentRoundCompressor — 当前轮次增量压缩
putSpec(specs, "CurrentRoundCompressor", CurrentRoundCompressorConfig.builder()
    .tokensThreshold(100000)
    .messagesToKeep(3)
    .model(modelConfig).modelClient(modelClientConfig)
    .build());

// 4. RoundLevelCompressor — 轮次级兜底压缩
putSpec(specs, "RoundLevelCompressor", RoundLevelCompressorConfig.builder()
    .triggerTotalTokens(230000)      // 触发阈值 230K token
    .targetTotalTokens(160000)       // 目标压缩到 160K token
    .keepRecentMessages(6)
    .model(modelConfig).modelClient(modelClientConfig)
    .build());
```

**管线特点**：4 个处理器形成由轻到重的分层防御，从单条消息卸载 → 对话压缩 → 轮次压缩 → 兜底压缩。

#### 预设 B：SessionMemory 管线（isPreset=true, isSessionMemoryEnabled=true）

```java
// ContextProcessorRail.java:222-234
// 1. ToolResultBudgetProcessor — 工具结果预算控制
putSpec(specs, "ToolResultBudgetProcessor", ToolResultBudgetProcessorConfig.builder().build());

// 2. MicroCompactProcessor — 微压缩（清除陈旧工具结果）
putSpec(specs, "MicroCompactProcessor", MicroCompactProcessorConfig.builder().build());

// 3. FullCompactProcessor — 全量压缩兜底
putSpec(specs, "FullCompactProcessor", FullCompactProcessorConfig.builder()
    .model(modelConfig).modelClient(modelClientConfig)
    .build());
```

**管线特点**：3 个处理器，侧重预算控制和轻量压缩，配合 SessionMemory 跨轮次记忆管理。

### 4.2 processorKeys 追加机制

无论使用哪种预设管线，`processorKeys` 中指定的处理器都会被追加（如果不在预设中）：

```java
// ContextProcessorRail.java:296-300
for (String key : processorKeys) {
    if (!specs.containsKey(key)) {  // 去重：已存在的不重复添加
        putSpec(specs, key, defaultConfigFor(key, modelConfig, modelClientConfig));
    }
}
```

**示例**：配置 `processorKeys: ["ToolResultBudgetProcessor"]` 时，标准管线会变成 5 个处理器：

```
MessageSummaryOffloader → DialogueCompressor → CurrentRoundCompressor
→ RoundLevelCompressor → ToolResultBudgetProcessor（追加）
```

### 4.3 defaultConfigFor() — 处理器默认配置映射

```java
// ContextProcessorRail.java:341-367
private static Object defaultConfigFor(
    String key, ModelRequestConfig modelConfig, ModelClientConfig modelClientConfig) {
    return switch (key) {
        case "MessageSummaryOffloader" -> MessageSummaryOffloaderConfig.builder()
            .model(modelConfig).modelClient(modelClientConfig).build();
        case "DialogueCompressor" -> DialogueCompressorConfig.builder()
            .model(modelConfig).modelClient(modelClientConfig).build();
        case "CurrentRoundCompressor" -> CurrentRoundCompressorConfig.builder()
            .model(modelConfig).modelClient(modelClientConfig).build();
        case "RoundLevelCompressor" -> RoundLevelCompressorConfig.builder()
            .model(modelConfig).modelClient(modelClientConfig).build();
        case "MicroCompactProcessor" -> MicroCompactProcessorConfig.builder().build();
        case "FullCompactProcessor" -> FullCompactProcessorConfig.builder()
            .model(modelConfig).modelClient(modelClientConfig).build();
        case "ToolResultBudgetProcessor" -> ToolResultBudgetProcessorConfig.builder().build();
        default -> throw new IllegalArgumentException("Unknown context processor: " + key);
    };
}
```

**关键设计**：通过 `processorKeys` 追加的处理器使用默认配置（仅设置 model/modelClient），而预设管线中的处理器使用精细调优的配置参数。

---

## 五、卸载提示注入 — injectOffloadSection()

```java
// ContextProcessorRail.java:369-394
private void injectOffloadSection() {
    if (owner == null) {
        return;
    }
    if (installedProcessors.isEmpty()) {
        owner.getAgent().getPromptBuilder().removeSection(OFFLOAD_SECTION);
        return;
    }
    String language = owner.getWorkspace().getLanguage();
    String content =
        "en".equalsIgnoreCase(language)
            ? "## Context Reload\n\n"
                  + "Some older messages may be offloaded. Use reload_original_context_messages"
                  + " with the exact offload handle when you need original content."
            : "## 上下文重载\n\n"
                  + "部分历史消息可能被卸载。需要原始内容时，使用 reload_original_context_messages"
                  + " 并提供准确的 offload handle。";
    owner.getAgent().getPromptBuilder().addSection(
        new PromptSection(OFFLOAD_SECTION, Map.of(language, content), 60));
}
```

**执行逻辑**：

| 条件 | 行为 |
|------|------|
| `owner == null` | 不注入 |
| `installedProcessors` 为空 | 移除已有的 offload Section |
| `installedProcessors` 非空 | 注入/更新 offload Section（优先级 60） |

**注入内容**：告知 LLM 上下文中可能存在卸载消息，以及如何使用 `reload_original_context_messages` 工具恢复原始内容。

**中英文支持**：根据 `workspace.getLanguage()` 选择中文或英文提示。

---

## 六、工具上下文修复 — fixIncompleteToolContext()

这是 `ContextProcessorRail` 最复杂的逻辑，用于修复因中断或异常导致的 AssistantMessage-ToolMessage 不配对问题。

### 6.1 问题场景

在 ReAct 内循环中，LLM 可能一次返回多个 ToolCall，但工具执行可能因中断、异常而只完成了部分：

```
正常流程：
  AssistantMessage [ToolCall A, ToolCall B, ToolCall C]
  ToolMessage A (result)
  ToolMessage B (result)
  ToolMessage C (result)

中断场景：
  AssistantMessage [ToolCall A, ToolCall B, ToolCall C]
  ToolMessage A (result)
  ← 中断发生，B 和 C 没有结果
```

如果 B、C 的 ToolMessage 缺失，LLM 下次调用时会认为还有未完成的工具调用，导致行为异常。

### 6.2 修复算法

```java
// ContextProcessorRail.java:188-242
public static void fixIncompleteToolContext(AgentCallbackContext ctx) {
    ModelContext context = ctx != null ? ctx.getContext() : null;
    if (context == null) return;

    List<BaseMessage> messages = context.getMessages();
    if (messages == null || messages.isEmpty()) return;

    // 1. 弹出所有消息
    List<BaseMessage> popped = context.popMessages(messages.size(), true);

    List<ToolCall> pending = new ArrayList<>();              // 等待结果的 ToolCall
    Map<String, ToolMessage> delayedTools = new LinkedHashMap<>(); // 延迟的 ToolMessage

    // 2. 逐条重新添加，修复配对关系
    for (BaseMessage message : popped) {
        if (message instanceof AssistantMessage assistant) {
            flushPending(context, pending, delayedTools);  // 先处理挂起的 ToolCall
            context.addMessages(assistant);
            if (assistant.getToolCalls() != null) {
                for (ToolCall call : assistant.getToolCalls()) {
                    call.setArguments(ensureJsonArguments(call.getArguments())); // 修复参数格式
                    pending.add(call);
                }
            }
            continue;
        }
        if (message instanceof ToolMessage toolMessage) {
            if (pending.isEmpty()) {
                context.addMessages(toolMessage);  // 没有挂起的 ToolCall，直接添加
            } else if (toolMessage.getToolCallId() != null
                    && toolMessage.getToolCallId().equals(pending.get(0).getId())) {
                context.addMessages(toolMessage);  // 匹配第一个挂起的 ToolCall
                pending.remove(0);
            } else {
                delayedTools.put(toolMessage.getToolCallId(), toolMessage); // 延迟处理
            }
            continue;
        }
        flushPending(context, pending, delayedTools);
        context.addMessages(message);
    }
    flushPending(context, pending, delayedTools);  // 处理剩余的挂起 ToolCall
}
```

### 6.3 flushPending() — 处理挂起的 ToolCall

```java
// ContextProcessorRail.java:396-415
private static void flushPending(
    ModelContext context, List<ToolCall> pending, Map<String, ToolMessage> delayedTools) {
    if (pending.isEmpty()) return;

    Set<String> flushed = new LinkedHashSet<>();
    for (ToolCall call : List.copyOf(pending)) {
        ToolMessage delayed = delayedTools.remove(call.getId());
        if (delayed != null) {
            context.addMessages(delayed);  // 找到延迟的 ToolMessage，添加
        } else {
            // 没有对应的 ToolMessage，生成中断占位消息
            context.addMessages(
                ToolMessage.builder()
                    .toolCallId(call.getId())
                    .content("[Tool execution interrupted] Tool "
                        + call.getName()
                        + " was interrupted during execution, no result available.")
                    .build());
        }
        flushed.add(call.getId());
    }
    pending.removeIf(call -> flushed.contains(call.getId()));

    // 将剩余未匹配的 ToolMessage 也添加回去
    for (ToolMessage toolMessage : delayedTools.values()) {
        context.addMessages(toolMessage);
    }
    delayedTools.clear();
}
```

**核心逻辑**：

| 情况 | 处理 |
|------|------|
| ToolCall 有对应的延迟 ToolMessage | 从 delayedTools 中取出并添加 |
| ToolCall 没有对应的 ToolMessage | 生成 `[Tool execution interrupted]` 占位消息 |
| ToolMessage 没有对应的 ToolCall | 直接添加回上下文 |

### 6.4 ensureJsonArguments() — ToolCall 参数格式修复

```java
// ContextProcessorRail.java:167-186
public static String ensureJsonArguments(Object arguments) {
    if (arguments instanceof String string) {
        String trimmed = string.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}") ? string : "{}";
    }
    if (arguments instanceof Map<?, ?> map) {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            entries.add("\"" + String.valueOf(entry.getKey())
                + "\":\"" + String.valueOf(entry.getValue()) + "\"");
        }
        return "{" + String.join(",", entries) + "}";
    }
    return "{}";
}
```

确保每个 ToolCall 的 arguments 都是合法的 JSON 字符串，防止因参数格式错误导致 LLM 调用失败。

---

## 七、SessionMemory 集成

### 7.1 SessionMemoryManager 的角色

当 `isSessionMemoryEnabled=true` 时，`ContextProcessorRail` 在每次 `afterModelCall()` 后调用 `SessionMemoryManager.maybeScheduleUpdate()`，判断是否需要将上下文中的关键信息提取到 SessionMemory 中。

### 7.2 更新调度逻辑

```
afterModelCall()
    │
    ├─ isSessionMemoryEnabled? ── No ──→ 直接返回
    │
    └─ Yes
        └─ sessionMemoryManager.maybeScheduleUpdate(session, context, workspace)
              │
              ├─ 截取上下文窗口到最近完成的 API 轮次
              │   (truncateContextWindowToCompletedApiRound)
              │
              ├─ shouldUpdate() 判断是否满足更新条件
              │   ├─ 未初始化：当前 token >= triggerTokens → 触发
              │   └─ 已初始化：token 增量 >= triggerAddTokens
              │                且 toolCall 增量 >= toolMin → 触发
              │
              └─ 满足条件 → 设置 is_extracting=true，等待后续提取
```

### 7.3 SessionMemory 管线与标准管线的差异

| 维度 | 标准管线 | SessionMemory 管线 |
|------|----------|-------------------|
| 处理器数量 | 4 个 | 3 个 |
| 压缩策略 | 多层渐进压缩（卸载→对话→轮次→兜底） | 预算控制+微压缩+全量兜底 |
| 是否需要 LLM | 是（压缩用 LLM 生成摘要） | FullCompact 需要，其他不需要 |
| 跨轮次记忆 | 无 | 通过 SessionMemoryManager 管理 |
| 适用场景 | 单次长任务 | 多轮对话、跨会话持久化 |

---

## 八、AutoHarnessContextRail 子类

`AutoHarnessContextRail` 是 `ContextProcessorRail` 的子类，专为 AutoHarness 场景定制：

```java
// AutoHarnessContextRail.java
public class AutoHarnessContextRail extends ContextProcessorRail {
    @Override
    public void beforeModelCall(AgentCallbackContext ctx) {
        // 跳过 workspace/context prompt 注入，避免与 auto-harness identity prompts 冲突
    }

    @Override
    public void uninit(Object agent) {
        // 保留已安装的 prompt sections，匹配 Python 的 noop uninit
    }
}
```

**差异点**：

| 行为 | ContextProcessorRail | AutoHarnessContextRail |
|------|---------------------|----------------------|
| `beforeModelCall()` | 注入 offload Section | 跳过（避免与 identity prompt 冲突） |
| `uninit()` | 清空处理器 + 移除 offload Section | 不做任何清理 |
| 其他钩子 | 正常执行 | 继承父类逻辑 |

---

## 九、priority=85 的设计考量

`ContextProcessorRail` 的优先级为 85，在 DeepAgent 的 Rail 链中处于较后位置：

| Rail | Priority | 说明 |
|------|----------|------|
| SessionRail | 95 | 会话管理，最先执行 |
| ContextAssembleRail | - | 上下文组装 |
| **ContextProcessorRail** | **85** | 上下文处理管线 |
| SecurityRail | - | 安全护栏 |
| SkillCreateRail | - | Skill 创建 |

**priority 越小越先执行**。85 意味着 ContextProcessorRail 在大多数 Rail 之后执行，确保：
1. 其他 Rail（如 SessionRail）已完成初始化
2. 上下文组装（ContextAssembleRail）已完成
3. 处理器注入时，Agent 的基础配置已就绪

---

## 十、完整执行流程图

```
DeepAgent 启动
    │
    ├─ init() ──────────────────────────────────────────────────────┐
    │   ├─ buildProcessorSpecs(config)                              │
    │   │   ├─ isPreset=true, isSessionMemoryEnabled=false         │
    │   │   │   → 标准管线：MessageSummaryOffloader +               │
    │   │   │     DialogueCompressor + CurrentRoundCompressor +     │
    │   │   │     RoundLevelCompressor                              │
    │   │   ├─ isPreset=true, isSessionMemoryEnabled=true          │
    │   │   │   → SessionMemory管线：ToolResultBudgetProcessor +    │
    │   │   │     MicroCompactProcessor + FullCompactProcessor      │
    │   │   └─ processorKeys 追加                                   │
    │   ├─ config.configureContextProcessors(specs)                 │
    │   └─ agent.configure(config)                                  │
    │                                                                │
    │  ReAct 内循环 ─────────────────────────────────────────────── │
    │   │                                                            │
    │   ├─ beforeInvoke() ─→ fixIncompleteToolContext()             │
    │   │                      修复 Assistant-Tool 配对              │
    │   │                                                            │
    │   ├─ beforeModelCall() ─→ injectOffloadSection()              │
    │   │                        注入上下文重载提示                   │
    │   │                                                            │
    │   │   ┌─ LLM 推理 ─┐                                          │
    │   │   └──────────────┘                                         │
    │   │                                                            │
    │   ├─ afterModelCall() ─→ sessionMemoryManager                 │
    │   │                       .maybeScheduleUpdate()               │
    │   │                       (仅 sessionMemory 启用时)            │
    │   │                                                            │
    │   ├─ onModelException() ─→ fixIncompleteToolContext()         │
    │   │                          异常路径也修复                     │
    │   │                                                            │
    │   └─ 循环...                                                   │
    │                                                                │
    ├─ uninit() ────────────────────────────────────────────────────┘
    │   ├─ config.configureContextProcessors(List.of())
    │   ├─ promptBuilder.removeSection("offload")
    │   ├─ installedProcessors.clear()
    │   └─ owner = null
    │
    └─ DeepAgent 销毁
```

---

## 十一、设计精髓总结

| 设计原则 | 体现 |
|----------|------|
| **桥接模式** | ContextProcessorRail 作为 harness 层与 context 层的桥梁，将处理器规格从配置转换为运行时实例 |
| **预设+扩展** | 提供两种预设管线（标准/SessionMemory），同时支持 processorKeys 追加自定义处理器 |
| **防御性编程** | fixIncompleteToolContext 在 beforeInvoke 和 onModelException 两个钩子中调用，确保正常和异常路径都能修复 |
| **声明式配置** | 通过 YAML 配置 preset/processorKeys/sessionMemoryEnabled 三个参数即可定制管线，无需编写代码 |
| **中英文适配** | injectOffloadSection 根据 workspace.language 选择提示语言 |
| **关注点分离** | Rail 只负责管线构建和生命周期管理，实际的压缩/卸载逻辑由 ContextProcessor 子类实现 |
| **优雅降级** | ensureJsonArguments 确保参数格式合法，flushPending 为缺失的 ToolMessage 生成占位消息 |

---

# 附录：两种管线对比与场景选择

## A.1 核心区别

| 维度 | 标准管线 | SessionMemory 管线 |
|------|----------|-------------------|
| **触发条件** | `isPreset=true, isSessionMemoryEnabled=false` | `isPreset=true, isSessionMemoryEnabled=true` |
| **处理器数量** | 4 个 | 3 个 |
| **核心策略** | 多层渐进压缩（卸载→对话→轮次→兜底） | 预算控制+轻量清除+全量兜底 |
| **是否需要 LLM** | 4 个中有 3 个需要 LLM 生成摘要 | 仅 FullCompact 需要 LLM |
| **信息可恢复性** | MessageSummaryOffloader 卸载可恢复，其他不可恢复 | ToolResultBudgetProcessor 卸载可恢复，其他不可恢复 |
| **跨轮次记忆** | 无 | 有（SessionMemoryManager） |

## A.2 处理器组成对比

**标准管线**（4 个处理器，由轻到重）：

```
MessageSummaryOffloader (单条>60K token → LLM自适应压缩+卸载)
    ↓
DialogueCompressor (总量>100K token → LLM压缩历史对话)
    ↓
CurrentRoundCompressor (总量>100K token → LLM增量记忆块压缩)
    ↓
RoundLevelCompressor (总量>230K token → LLM多轮递归+激进压缩兜底)
```

**SessionMemory 管线**（3 个处理器）：

```
ToolResultBudgetProcessor (轮次>50K token → 按轮次卸载超预算工具结果)
    ↓
MicroCompactProcessor (工具结果>20条 → 纯规则清除陈旧结果，无LLM调用)
    ↓
FullCompactProcessor (总量超限 → LLM全量压缩+状态重新注入兜底)
```

## A.3 策略差异的本质

**标准管线**：**"精细压缩"** — 用多个处理器逐层处理，每层只处理自己关心的部分，尽量保留更多信息

```
单条消息太大?  → MessageSummaryOffloader 只处理这条
历史对话太多?  → DialogueCompressor 只压缩对话
当前轮次太大?  → CurrentRoundCompressor 只压缩本轮
整体还是太大?  → RoundLevelCompressor 兜底压缩
```

**SessionMemory 管线**：**"快速裁剪"** — 用规则快速清除冗余，只在最后才用 LLM 全量压缩

```
工具结果超预算? → ToolResultBudgetProcessor 卸载到内存（可恢复）
陈旧结果太多?  → MicroCompactProcessor 直接清除（不可恢复，但极快）
还是太大?      → FullCompactProcessor 全量压缩兜底
```

## A.4 适用场景

| 场景 | 推荐管线 | 原因 |
|------|----------|------|
| **单次长任务**（如代码重构、数据分析） | 标准管线 | 任务持续时间长，消息累积多，需要精细的分层压缩，避免一次性丢失过多信息 |
| **多轮对话**（如客服、咨询） | SessionMemory 管线 | 跨轮次需要记忆管理，工具结果多为查询类，适合预算控制和快速清除 |
| **工具调用密集型**（如代码搜索、文件分析） | SessionMemory 管线 | grep/read_file 等工具产生大量结果，MicroCompact 能快速清除陈旧结果，ToolResultBudget 按预算卸载 |
| **LLM 调用成本敏感** | SessionMemory 管线 | 只有 FullCompact 需要 LLM，其他处理器纯规则驱动，压缩过程不产生额外 LLM 调用成本 |
| **信息完整性要求高** | 标准管线 | MessageSummaryOffloader 的自适应压缩（抽取式/摘要式）比直接清除保留更多信息，卸载后可 reload 恢复 |
| **跨会话持久化** | SessionMemory 管线 | SessionMemoryManager 在 afterModelCall 中调度记忆更新，支持跨会话状态保持 |

## A.5 选择决策树

```
是否需要跨轮次/跨会话记忆?
    ├─ 是 → SessionMemory 管线
    └─ 否
        │
        工具调用是否密集（grep/read_file/web_search 等）?
        ├─ 是 → SessionMemory 管线（MicroCompact 快速清除陈旧结果）
        └─ 否
            │
            信息完整性是否关键（不能丢失关键上下文）?
            ├─ 是 → 标准管线（精细压缩，卸载可恢复）
            └─ 否 → SessionMemory 管线（更轻量，LLM 调用更少）
```

## A.6 典型配置示例

**代码重构任务**（标准管线）：

```yaml
rails:
  context_processor:
    preset: true
    session_memory_enabled: false
```

**银行智能客服**（SessionMemory 管线）：

```yaml
rails:
  context_processor:
    preset: true
    session_memory_enabled: true
```

**混合场景**（标准管线 + 追加 ToolResultBudgetProcessor）：

```yaml
rails:
  context_processor:
    preset: true
    session_memory_enabled: false
    processor_keys:
      - ToolResultBudgetProcessor
```

这样既保留标准管线的精细压缩能力，又增加了工具结果预算控制。

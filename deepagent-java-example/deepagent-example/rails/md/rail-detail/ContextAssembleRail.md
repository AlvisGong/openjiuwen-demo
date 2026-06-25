# ContextAssembleRail 深度解析

## 一、ContextAssembleRail 在 DeepAgent 中的定位

`ContextAssembleRail` 是 DeepAgent harness 层的核心 Rail 之一，负责**在每次 LLM 调用前动态组装上下文信息**，将工作区结构、可用工具列表、上下文文件内容注入到 System Prompt 中，使 LLM 始终能看到最新的执行环境。

```
┌──────────────────────────────────────────────────────────────────┐
│  DeepAgent (外循环)                                               │
│                                                                  │
│  ┌─ Harness Rails ─────────────────────────────────────────────┐ │
│  │  ContextAssembleRail (priority=85) → 动态组装上下文信息      │ │
│  │  ContextProcessorRail (priority=85) → 构建上下文处理管线     │ │
│  │  SessionRail / SecurityRail / ...    → 其他护栏              │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                           ↓ 注入                                 │
│  ┌─ ReActAgent (内循环) ──────────────────────────────────────┐ │
│  │  SystemPromptBuilder                                         │ │
│  │    ├─ Section: workspace (priority=30)  ← 工作区目录树       │ │
│  │    ├─ Section: tools (priority=40)      ← 可用工具列表       │ │
│  │    ├─ Section: context (priority=50)    ← 上下文文件内容     │ │
│  │    └─ Section: offload (priority=60)    ← 卸载重载提示       │ │
│  │                                                              │ │
│  │  ModelCallInputs.messages                                     │ │
│  │    └─ [SystemMessage(workspace), SystemMessage(tools), ...]  │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

**核心职责**：让 LLM 在每次推理时都能感知到**最新的**工作环境，包括文件结构变化、工具注册变化、上下文文件内容变化。

---

## 二、类结构与常量定义

### 2.1 类继承关系

```
AgentRail (核心基类，定义生命周期钩子)
  └─ DeepAgentRail (DeepAgent 专用基类，默认 priority=50)
       └─ ContextAssembleRail (priority=85)
```

### 2.2 常量定义

```java
// ContextAssembleRail.java:35-40
private static final int WORKSPACE_PRIORITY = 30;       // workspace Section 优先级
private static final int TOOLS_PRIORITY = 40;           // tools Section 优先级
private static final int CONTEXT_PRIORITY = 50;         // context Section 优先级
private static final int MAX_WORKSPACE_ENTRIES = 80;    // 工作区目录树最大条目数
private static final int MAX_CONTEXT_FILES = 8;         // 上下文文件最大数量
```

| 常量 | 值 | 说明 |
|------|-----|------|
| `WORKSPACE_PRIORITY` | 30 | workspace Section 在 System Prompt 中的排序优先级，越小越靠前 |
| `TOOLS_PRIORITY` | 40 | tools Section 优先级 |
| `CONTEXT_PRIORITY` | 50 | context Section 优先级 |
| `MAX_WORKSPACE_ENTRIES` | 80 | 工作区目录树最多展示 80 个条目（2 层深度） |
| `MAX_CONTEXT_FILES` | 8 | 上下文文件目录最多读取 8 个文件 |

### 2.3 实例字段

```java
private DeepAgent owner;  // 持有对 DeepAgent 的引用
```

---

## 三、生命周期钩子详解

### 3.1 init() — 绑定 DeepAgent 引用

```java
// ContextAssembleRail.java:55
@Override
public void init(Object agent) {
    if (agent instanceof DeepAgent deepAgent) {
        this.owner = deepAgent;
    }
}
```

在 DeepAgent 初始化阶段，将 `owner` 指向当前 DeepAgent 实例，后续所有钩子都通过 `owner` 访问工作区、Agent、PromptBuilder 等资源。

### 3.2 uninit() — 清理所有 Section

```java
// ContextAssembleRail.java:64
@Override
public void uninit(Object agent) {
    if (agent instanceof DeepAgent deepAgent) {
        for (String sectionName : sectionNames()) {
            deepAgent.getAgent().getPromptBuilder().removeSection(sectionName);
        }
    }
    owner = null;
}
```

清理动作：
1. 遍历 `sectionNames()`（`["workspace", "tools", "context"]`），逐一移除
2. 释放 `owner` 引用

### 3.3 beforeModelCall() — 核心钩子，动态组装上下文

```java
// ContextAssembleRail.java:75
@Override
public void beforeModelCall(AgentCallbackContext ctx) {
    if (owner == null) {
        return;
    }
    List<String> injected = new ArrayList<>();
    String language = owner.getWorkspace().getLanguage();
    addSection("workspace", buildWorkspaceSection(language), WORKSPACE_PRIORITY, injected);
    addSection("tools", buildToolsSection(language, ctx), TOOLS_PRIORITY, injected);
    addSection("context", buildContextSection(language), CONTEXT_PRIORITY, injected);
    injectSystemMessages(ctx, injected);
}
```

**执行流程**：

```
beforeModelCall()
    │
    ├─ 1. buildWorkspaceSection()  → 扫描文件系统，生成工作区目录树
    │      ↓
    │   addSection("workspace", content, 30, injected)
    │      → removeSection("workspace")  先移除旧内容
    │      → addSection(new PromptSection(...))  注入新内容
    │      → injected.add(content)  记录注入内容
    │
    ├─ 2. buildToolsSection()  → 获取可用工具列表
    │      ↓
    │   addSection("tools", content, 40, injected)
    │
    ├─ 3. buildContextSection()  → 读取上下文文件内容
    │      ↓
    │   addSection("context", content, 50, injected)
    │
    └─ 4. injectSystemMessages()  → 将 Section 内容作为 SystemMessage 注入到消息列表
```

**关键设计**：每次 `beforeModelCall` 都先 `removeSection` 再 `addSection`，确保注入的内容始终是**最新的**。

---

## 四、三个 Section 的构建逻辑详解

### 4.1 workspace Section — 工作区目录树

```java
// ContextAssembleRail.java:117
private String buildWorkspaceSection(String language) {
    Path root = owner.getWorkspace().root();
    List<String> entries = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(root, 2)) {   // 最多遍历 2 层深度
        stream
            .filter(path -> !path.equals(root))          // 排除根目录自身
            .sorted(Comparator.comparing(path -> root.relativize(path).toString()))  // 按路径排序
            .limit(MAX_WORKSPACE_ENTRIES)                // 最多 80 条
            .forEach(path -> {
                String rel = root.relativize(path).toString().replace('\\', '/');
                entries.add((Files.isDirectory(path) ? "- [dir] " : "- [file] ") + rel);
            });
    } catch (IOException ignored) {
        entries.add("- (workspace listing unavailable)");
    }
    String title = "en".equalsIgnoreCase(language) ? "## Workspace" : "## 工作区";
    return title + "\n\nRoot: " + root + "\n\n" + String.join("\n", entries);
}
```

**生成示例**（中文）：

```markdown
## 工作区

Root: /home/user/project

- [dir] src
- [file] src/App.java
- [file] src/Config.java
- [dir] src/utils
- [file] src/utils/Helper.java
- [file] README.md
- [file] pom.xml
```

**生成示例**（英文）：

```markdown
## Workspace

Root: /home/user/project

- [dir] src
- [file] src/App.java
- [file] src/Config.java
- [dir] src/utils
- [file] src/utils/Helper.java
- [file] README.md
- [file] pom.xml
```

**关键设计点**：

| 设计点 | 说明 |
|--------|------|
| **2 层深度** | `Files.walk(root, 2)` 只遍历 2 层，避免大型项目目录树过大 |
| **80 条上限** | `limit(MAX_WORKSPACE_ENTRIES)` 防止目录条目过多占用 token |
| **路径分隔符统一** | `replace('\\', '/')` 将 Windows 反斜杠转为正斜杠，保证跨平台一致性 |
| **排序** | 按相对路径字符串排序，确保输出稳定可预测 |
| **异常容错** | `IOException` 时输出 `(workspace listing unavailable)`，不中断流程 |
| **实时扫描** | 每次调用都重新扫描文件系统，反映最新状态 |

### 4.2 tools Section — 可用工具列表

```java
// ContextAssembleRail.java:136
private String buildToolsSection(String language, AgentCallbackContext ctx) {
    List<ToolInfo> tools = new ArrayList<>();
    // 优先从当前调用的 inputs 中获取工具列表
    if (ctx != null
        && ctx.getInputs() instanceof ModelCallInputs inputs
        && inputs.getTools() != null) {
        tools.addAll(inputs.getTools());
    }
    // 如果 inputs 中没有，则从 AbilityManager 获取
    if (tools.isEmpty()) {
        tools.addAll(owner.getAgent().getAbilityManager().listToolInfo());
    }
    List<String> lines =
        tools.stream()
            .map(tool ->
                "- " + tool.getName()
                    + (tool.getDescription() == null || tool.getDescription().isBlank()
                        ? ""
                        : ": " + tool.getDescription()))
            .toList();
    String title = "en".equalsIgnoreCase(language) ? "## Available Tools" : "## 可用工具";
    return title + "\n\n" + String.join("\n", lines);
}
```

**生成示例**：

```markdown
## 可用工具

- read_file: Read the contents of a file
- write_file: Write content to a file
- execute_code: Execute code in a sandbox
- grep: Search for patterns in files
- glob: Find files matching a pattern
- reload_original_context_messages: Retrieve offloaded messages
```

**工具来源优先级**：

```
1. ctx.getInputs() → ModelCallInputs.getTools()   (当前调用的工具列表)
2. owner.getAgent().getAbilityManager().listToolInfo()  (Agent 注册的全部能力)
```

**关键设计点**：

| 设计点 | 说明 |
|--------|------|
| **双来源** | 优先从当前调用的 inputs 获取（可能经过过滤），回退到 AbilityManager 全量获取 |
| **AbilityManager** | 统一管理 ToolCard、WorkflowCard、AgentCard、McpServerConfig 四类能力 |
| **描述可选** | 工具描述为空时只显示名称，避免无意义的冒号 |
| **动态性** | 每次调用前重新获取，反映工具注册的实时变化（如 Skill 热加载后新增工具） |

### 4.3 context Section — 上下文文件内容

```java
// ContextAssembleRail.java:155
private String buildContextSection(String language) {
    Path contextDir = owner.getWorkspace().getNodePath("context");  // workspace_root/context/
    String title = "en".equalsIgnoreCase(language) ? "## Context Files" : "## 上下文文件";
    if (!Files.isDirectory(contextDir)) {
        return title + "\n\n(no context files)";
    }
    List<String> parts = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(contextDir, 1)) {  // 只遍历 1 层
        stream
            .filter(Files::isRegularFile)      // 只读取文件，不递归子目录
            .sorted()                           // 按文件名排序
            .limit(MAX_CONTEXT_FILES)           // 最多 8 个文件
            .forEach(path -> parts.add("### " + path.getFileName() + "\n\n" + readSnippet(path)));
    } catch (IOException ignored) {
        parts.add("(context files unavailable)");
    }
    return title + "\n\n" + String.join("\n\n", parts);
}
```

**文件内容读取**：

```java
// ContextAssembleRail.java:171
private String readSnippet(Path path) {
    try {
        String content = Files.readString(path);
        return content.length() <= 4000 ? content : content.substring(0, 4000) + "\n...";
    } catch (IOException ignored) {
        return "";
    }
}
```

**生成示例**：

```markdown
## 上下文文件

### business_rules.md

1. 所有文件操作必须在 workspace 目录内执行
2. 禁止删除 .config 目录下的配置文件
3. 代码修改后必须运行测试验证

### api_spec.yaml

openapi: 3.0.0
info:
  title: User Service API
  version: 1.0.0
paths:
  /users:
    get:
      summary: List users
...
```

**关键设计点**：

| 设计点 | 说明 |
|--------|------|
| **固定目录** | 上下文文件存放在 `workspace_root/context/` 目录下 |
| **1 层深度** | 只读取 context 目录下的直接文件，不递归子目录 |
| **8 个文件上限** | 防止过多文件占用 token |
| **4000 字符截断** | 每个文件内容最多 4000 字符，超出部分截断并添加 `...` 标记 |
| **Markdown 格式** | 每个文件用 `### filename` 作为标题，内容紧跟其后 |
| **异常容错** | 目录不存在时输出 `(no context files)`，读取失败时跳过 |

---

## 五、addSection() — Section 注入机制

```java
// ContextAssembleRail.java:96
private void addSection(String name, String content, int priority, List<String> injected) {
    owner.getAgent().getPromptBuilder().removeSection(name);  // ① 先移除旧 Section
    if (content == null || content.isBlank()) {                // ② 内容为空则不注入
        return;
    }
    String language = owner.getWorkspace().getLanguage();
    owner.getAgent().getPromptBuilder()
        .addSection(
            new PromptSection(
                name,
                Map.of(
                    language == null || language.isBlank()
                        ? PromptSection.DEFAULT_LANGUAGE
                        : language,
                    content),
                priority));                                    // ③ 添加新 Section
    injected.add(content);                                     // ④ 记录注入内容
}
```

**执行步骤**：

```
addSection("workspace", "## 工作区\n\n...", 30, injected)
    │
    ├─ ① removeSection("workspace")  → 移除上一次注入的旧 workspace Section
    │
    ├─ ② content 为空?  → 是：直接返回，不注入
    │                    → 否：继续
    │
    ├─ ③ addSection(new PromptSection("workspace", {"cn": "## 工作区\n\n..."}, 30))
    │      → 写入 SystemPromptBuilder 的 sections Map
    │      → 后续 SystemPromptBuilder.build() 时按 priority 排序渲染
    │
    └─ ④ injected.add(content)  → 记录内容，供 injectSystemMessages() 使用
```

**关键设计**：`removeSection` + `addSection` 的组合确保每次调用都是**覆盖更新**，而非追加。这意味着 LLM 每次看到的都是**最新的**工作区状态、工具列表和上下文文件内容。

---

## 六、injectSystemMessages() — 消息级注入

```java
// ContextAssembleRail.java:179
private void injectSystemMessages(AgentCallbackContext ctx, List<String> sections) {
    if (!(ctx.getInputs() instanceof ModelCallInputs inputs) || sections.isEmpty()) {
        return;
    }
    List<Object> messages =
        inputs.getMessages() == null ? new ArrayList<>() : new ArrayList<>(inputs.getMessages());
    for (String section : sections) {
        messages.add(new SystemMessage(section));  // 追加 SystemMessage
    }
    inputs.setMessages(messages);
}
```

**双重注入机制**：

`ContextAssembleRail` 通过**两种途径**将上下文信息传递给 LLM：

| 途径 | 机制 | 作用 |
|------|------|------|
| **PromptBuilder Section** | `addSection()` → `SystemPromptBuilder` | 写入 System Prompt 模板，每次 `build()` 时自动渲染 |
| **SystemMessage 注入** | `injectSystemMessages()` → `ModelCallInputs.messages` | 在当前调用的消息列表中追加 SystemMessage |

**为什么需要双重注入？**

1. **PromptBuilder Section**：持久化注入，所有后续调用都会自动包含，除非显式移除
2. **SystemMessage 注入**：一次性注入，仅在当前调用生效，确保本次调用一定能看到最新内容

这种双重保障确保即使 PromptBuilder 的渲染时机有延迟，当前调用的消息列表中也包含了最新的上下文信息。

---

## 七、辅助方法

### 7.1 sectionNames() — Section 名称列表

```java
// ContextAssembleRail.java:78
public List<String> sectionNames() {
    return List.of("workspace", "tools", "context");
}
```

返回所有管理的 Section 名称，用于 `uninit()` 时的批量清理。

### 7.2 describe() — 描述信息

```java
// ContextAssembleRail.java:83
public String describe() {
    return "Assemble workspace, tools, and context prompt sections";
}
```

### 7.3 hasContextSections() — 检查 Section 是否已安装

```java
// ContextAssembleRail.java:87
public boolean hasContextSections() {
    return owner != null
        && owner.getAgent().getPromptBuilder().hasSection("workspace")
        && owner.getAgent().getPromptBuilder().hasSection("tools")
        && owner.getAgent().getPromptBuilder().hasSection("context");
}
```

检查三个 Section 是否都已安装到 PromptBuilder 中。任一缺失则返回 false。

---

## 八、Workspace 类与 ContextAssembleRail 的协作

```java
// Workspace.java
public class Workspace {
    private String rootPath = "./";      // 工作区根路径
    private String language = "cn";      // 语言设置
    private Map<String, String> links = new LinkedHashMap<>();  // 链接映射

    public Path root() {
        return Path.of(rootPath).toAbsolutePath().normalize();
    }

    public Path getNodePath(String nodeName) {
        return root().resolve(nodeName);  // 如 root()/context/
    }
}
```

**ContextAssembleRail 对 Workspace 的使用**：

| 方法 | 用途 |
|------|------|
| `owner.getWorkspace().root()` | 获取工作区根路径，用于扫描目录树 |
| `owner.getWorkspace().getLanguage()` | 获取语言设置，决定 Section 标题的中英文 |
| `owner.getWorkspace().getNodePath("context")` | 获取上下文文件目录路径（`root/context/`） |

---

## 九、SystemPromptBuilder 的 Section 机制

`ContextAssembleRail` 通过 `SystemPromptBuilder` 管理 Prompt Section：

```java
// SystemPromptBuilder.java
public class SystemPromptBuilder {
    private final Map<String, PromptSection> sections = new LinkedHashMap<>();

    // 添加/替换 Section
    public SystemPromptBuilder addSection(PromptSection section) {
        sections.put(section.getName(), section);  // 同名覆盖
        return this;
    }

    // 移除 Section
    public SystemPromptBuilder removeSection(String name) {
        sections.remove(name);
        return this;
    }

    // 构建 System Prompt：按 priority 排序后拼接
    public String build() {
        List<PromptSection> ordered = getSectionsForBuild();
        ordered.sort(Comparator.comparingInt(PromptSection::getPriority));
        List<String> parts = new ArrayList<>();
        for (PromptSection section : ordered) {
            String rendered = section.render(language);
            if (rendered != null && !rendered.trim().isEmpty()) {
                parts.add(rendered);
            }
        }
        return String.join("\n\n", parts);
    }
}
```

**Section 排序示例**（priority 越小越靠前）：

```
priority=10  → identity (智能体身份)
priority=20  → safety (安全规则)
priority=30  → workspace (工作区目录树)     ← ContextAssembleRail 注入
priority=40  → tools (可用工具列表)         ← ContextAssembleRail 注入
priority=50  → context (上下文文件内容)     ← ContextAssembleRail 注入
priority=60  → offload (卸载重载提示)       ← ContextProcessorRail 注入
priority=70  → skills (技能描述)
```

---

## 十、完整执行流程图

```
DeepAgent 启动
    │
    ├─ init() ────────────────────────────────────────────────┐
    │   └─ owner = deepAgent                                  │
    │                                                          │
    │  ReAct 内循环 ────────────────────────────────────────── │
    │   │                                                      │
    │   ├─ beforeModelCall() ─────────────────────────────────┤
    │   │   │                                                  │
    │   │   ├─ buildWorkspaceSection("cn")                    │
    │   │   │   └─ Files.walk(root, 2) → 生成目录树           │
    │   │   │   └─ "## 工作区\n\nRoot: ...\n\n- [dir] src..." │
    │   │   │                                                  │
    │   │   ├─ addSection("workspace", content, 30)           │
    │   │   │   ├─ removeSection("workspace")  移除旧内容     │
    │   │   │   └─ addSection(PromptSection)   注入新内容     │
    │   │   │                                                  │
    │   │   ├─ buildToolsSection("cn", ctx)                   │
    │   │   │   └─ AbilityManager.listToolInfo() → 工具列表   │
    │   │   │   └─ "## 可用工具\n\n- read_file: ..."         │
    │   │   │                                                  │
    │   │   ├─ addSection("tools", content, 40)               │
    │   │   │                                                  │
    │   │   ├─ buildContextSection("cn")                      │
    │   │   │   └─ Files.walk(context/, 1) → 读取文件内容     │
    │   │   │   └─ "## 上下文文件\n\n### rules.md\n\n..."    │
    │   │   │                                                  │
    │   │   ├─ addSection("context", content, 50)             │
    │   │   │                                                  │
    │   │   └─ injectSystemMessages(ctx, injected)            │
    │   │       └─ messages.add(new SystemMessage(section))   │
    │   │                                                      │
    │   │   ┌─ LLM 推理（看到最新的工作区、工具、上下文）──┐   │
    │   │   └────────────────────────────────────────────────┘   │
    │   │                                                      │
    │   └─ 循环...（每次 beforeModelCall 都重新组装）          │
    │                                                          │
    ├─ uninit() ──────────────────────────────────────────────┤
    │   ├─ removeSection("workspace")                          │
    │   ├─ removeSection("tools")                              │
    │   ├─ removeSection("context")                            │
    │   └─ owner = null                                        │
    │                                                          │
    └─ DeepAgent 销毁                                         ┘
```

---

## 十一、场景样例

### 场景 1：Agent 创建新文件后，LLM 自动感知

| 步骤 | 事件 | workspace Section |
|------|------|-------------------|
| 1 | 首次 LLM 调用 | `- [dir] src\n- [file] src/App.java` |
| 2 | Agent 调用 `write_file("src/utils/Helper.java", ...)` | 无变化（尚未调用 LLM） |
| 3 | 下次 `beforeModelCall()` | `- [dir] src\n- [dir] src/utils\n- [file] src/App.java\n- [file] src/utils/Helper.java` |

**效果**：LLM 在步骤 3 的推理中能看到新创建的文件，无需手动刷新。

### 场景 2：Skill 热加载后工具列表更新

| 步骤 | 事件 | tools Section |
|------|------|---------------|
| 1 | 初始状态 | `- read_file\n- write_file\n- grep` |
| 2 | Skill 热加载注册了新工具 `analyze_data` | 无变化（尚未调用 LLM） |
| 3 | 下次 `beforeModelCall()` | `- read_file\n- write_file\n- grep\n- analyze_data: Analyze data files` |

**效果**：LLM 在步骤 3 能看到新增的工具，可以自主决定是否使用。

### 场景 3：上下文文件被修改

| 步骤 | 事件 | context Section |
|------|------|-----------------|
| 1 | 初始状态 | `### business_rules.md\n\n1. 禁止删除配置文件` |
| 2 | 用户修改了 `business_rules.md`，新增规则 | 无变化（尚未调用 LLM） |
| 3 | 下次 `beforeModelCall()` | `### business_rules.md\n\n1. 禁止删除配置文件\n2. 修改后必须运行测试` |

**效果**：LLM 始终看到最新的业务规则，避免基于过时规则做决策。

### 场景 4：大型项目的目录树截断

| 步骤 | 事件 | workspace Section |
|------|------|-------------------|
| 1 | 项目有 200 个文件 | `Files.walk(root, 2).limit(80)` |
| 2 | 只展示前 80 个条目 | 展示前 80 个，超出部分不展示 |

**效果**：即使项目很大，也不会因为目录树过大而浪费 token。

---

## 十二、与 ContextProcessorRail 的协作

`ContextAssembleRail` 和 `ContextProcessorRail` 同为 priority=85，分别负责上下文管理的不同层面：

| 维度 | ContextAssembleRail | ContextProcessorRail |
|------|--------------------|--------------------|
| **职责** | 动态组装上下文信息（注入什么） | 动态处理上下文大小（压缩什么） |
| **注入方式** | PromptBuilder Section + SystemMessage | ReActAgentConfig ProcessorSpec |
| **触发时机** | beforeModelCall | init + beforeModelCall + afterModelCall |
| **关注点** | 信息的新鲜度和完整性 | 信息的体积和 token 预算 |
| **方向** | 向上下文中**添加**信息 | 从上下文中**压缩/卸载**信息 |

两者形成**一增一减**的动态平衡：

```
ContextAssembleRail:  向 System Prompt 注入 workspace/tools/context → 增加 token
ContextProcessorRail: 通过处理器管线压缩/卸载上下文 → 减少 token
```

---

## 十三、设计精髓总结

| 设计原则 | 体现 |
|----------|------|
| **实时感知** | 每次 beforeModelCall 都重新扫描文件系统和工具注册，确保 LLM 看到最新环境 |
| **覆盖更新** | removeSection + addSection 组合，保证同一 Section 不会重复堆积 |
| **双重注入** | PromptBuilder Section（持久化）+ SystemMessage（一次性），双重保障信息传递 |
| **资源限制** | 目录树 80 条上限、文件 4000 字符截断、上下文文件 8 个上限，防止 token 浪费 |
| **异常容错** | 文件扫描失败、目录不存在等场景均有降级处理，不中断 Agent 执行 |
| **中英文适配** | 根据 workspace.language 选择 Section 标题语言 |
| **关注点分离** | 只负责信息组装，不负责压缩/卸载（由 ContextProcessorRail 负责） |
| **声明式配置** | 上下文文件只需放入 `context/` 目录即可自动注入，无需编码 |

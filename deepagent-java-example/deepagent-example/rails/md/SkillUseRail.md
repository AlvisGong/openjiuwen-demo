# SkillUseRail 详细解读

## 一、核心定位

SkillUseRail 是 DeepAgent harness 层中负责**Skill 注册、发现、注入和访问控制**的 Rail。它让 Agent 能够根据任务描述自动发现和选择合适的 Skill，无需硬编码。

---

## 二、类结构

```
SkillUseRail extends DeepAgentRail
├── 实例字段
│   ├── owner: DeepAgent              — 所属 Agent
│   ├── skillManager: SkillManager    — Skill 注册表
│   ├── listSkillTool: ListSkillTool  — 列出 Skill 的工具
│   ├── skillTool: SkillTool          — 读取 Skill 内容的工具
│   ├── skillsRoot: Path              — Skill 根目录
│   ├── skillMode: String             — Skill 模式 (all/auto_list/none)
│   ├── configuredSkillDirectories    — 配置的 Skill 目录列表
│   ├── remoteSkillSources            — 远程 Skill 源列表
│   ├── enabledSkills: Set<String>    — 启用的 Skill 白名单
│   ├── disabledSkills: Set<String>   — 禁用的 Skill 黑名单
│   └── tools: List<Tool>             — 注册到 Agent 的工具
│
└── 内部类
    └── RemoteSkillSource (record)    — 远程 Skill 源定义
```

---

## 三、构造参数

```java
public SkillUseRail(
    List<String> skillDirectories,      // Skill 目录列表
    String skillMode,                   // Skill 模式
    List<String> enabledSkills,         // 启用的 Skill 白名单
    List<String> disabledSkills,        // 禁用的 Skill 黑名单
    List<RemoteSkillSource> remoteSkillSources  // 远程 Skill 源
)
```

| 参数 | 含义 | 示例 |
|------|------|------|
| `skillDirectories` | 本地 Skill 目录路径列表 | `["skills", "/opt/skills"]` |
| `skillMode` | Skill 注入模式 | `"all"` / `"auto_list"` / `"none"` |
| `enabledSkills` | 白名单，为空表示全部启用 | `["marketing", "loan"]` |
| `disabledSkills` | 黑名单，优先级高于白名单 | `["internal_tool"]` |
| `remoteSkillSources` | GitHub 远程 Skill 源 | `[RemoteSkillSource("org", "repo", "main", "skills", "token")]` |

---

## 四、生命周期钩子

### 4.1 init —— 初始化

```java
public void init(Object agent) {
    owner = deepAgent;
    skillMode = configuredSkillDirectories.isEmpty()
            ? normalizeMode(deepAgent.getConfig().getSkillMode())
            : skillMode;
    skillsRoot = resolveSkillsRoot(deepAgent);
    skillManager = new SkillManager(deepAgent.getCard().getId());
    registerConfiguredSkills(deepAgent);   // 注册所有 Skill

    // 注册两个 Harness 工具
    tools.add(new LocalFunction(card("list_skill", ...), inputs -> listSkill(inputs)));
    tools.add(new LocalFunction(card("skill_tool", ...), inputs -> readSkill(inputs)));
    for (Tool tool : tools) {
        deepAgent.registerHarnessTool(tool);
    }
}
```

初始化流程：
1. 确定 Skill 模式（构造参数优先，否则从 AgentConfig 获取）
2. 解析 Skill 根目录
3. 创建 SkillManager 并注册所有 Skill
4. 注册 `list_skill` 和 `skill_tool` 两个工具到 Agent

### 4.2 beforeModelCall —— 注入 Skill 提示词

```java
public void beforeModelCall(AgentCallbackContext ctx) {
    if (owner == null || skillManager == null || skillManager.count() == 0 || "none".equals(skillMode)) {
        removePromptSection();
        return;
    }
    String prompt = buildSkillPrompt(language, skillMode, configuredSkills(skillManager.getAll()));
    owner.getAgent().addPromptBuilderSection(SKILL_SECTION, prompt, SKILL_SECTION_PRIORITY);
    // 同时注入到消息列表头部
    if (!isPromptAlreadyInjected) {
        inputs.getMessages().add(0, new SystemMessage(prompt));
    }
}
```

每次 LLM 调用前，将 Skill 列表和选择指引注入到 System Prompt 中。

### 4.3 uninit —— 清理

```java
public void uninit(Object agent) {
    // 注销工具、移除 Prompt Section、清空状态
}
```

---

## 五、Skill 模式详解

| 模式 | 行为 | 适用场景 |
|------|------|----------|
| **`all`** | 在 System Prompt 中列出所有 Skill 的名称和描述，LLM 直接看到完整列表 | Skill 数量少（< 20），LLM 可直接判断 |
| **`auto_list`** | 只提示"调用 list_skill 发现 Skill"，LLM 需主动调用工具查询 | Skill 数量多（> 20），避免 Prompt 过长 |
| **`none`** | 不注入任何 Skill 信息 | 不需要 Skill 的场景 |

**`all` 模式注入的 Prompt 示例（中文）**：

```
你已配备任务技能。
当当前任务匹配下列技能时，先调用 skill_tool 阅读技能说明并遵循其中流程。

0. 技能名称: marketing; 描述: 生成营销方案; 目录: /skills/marketing
1. 技能名称: loan; 描述: 根据对公贷款政策出融资方案; 目录: /skills/loan
2. 技能名称: travel; 描述: 生成出行日程; 目录: /skills/travel
```

**`auto_list` 模式注入的 Prompt 示例（中文）**：

```
你已配备任务技能。
当需要判断当前任务适合哪个技能时，先调用 list_skill；选定后调用 skill_tool 阅读 SKILL.md 再执行。
```

---

## 六、Skill 注册流程

```
registerConfiguredSkills(deepAgent)
    │
    ├── 收集 Skill 目录
    │   ├── configuredSkillDirectories（构造参数）
    │   └── deepAgent.getConfig().getSkillDirectories()（配置文件）
    │
    ├── syncRemoteSkills() —— 同步远程 Skill
    │   └── uploadRemoteSkill() → RemoteSkillUtil.uploadSkillFromGitHub()
    │
    └── skillManager.register(root) —— 扫描目录注册
        ├── 如果目录下直接有 SKILL.md → 注册为单个 Skill
        └── 否则扫描子目录，每个含 SKILL.md 的子目录注册为一个 Skill
```

### Skill 目录结构示例

```
skills/
├── marketing/
│   └── SKILL.md          ← 注册为 Skill: marketing
├── loan/
│   └── SKILL.md          ← 注册为 Skill: loan
└── travel/
    ├── SKILL.md           ← 注册为 Skill: travel
    └── templates/
        └── schedule.md
```

### SKILL.md 格式

```markdown
---
description: 生成营销方案
---
# Marketing Skill
...技能详细说明和工作流程...
```

SkillManager 从 SKILL.md 的 YAML front matter 中提取 `description` 字段作为 Skill 描述。

---

## 七、两个 Harness 工具

### 7.1 list_skill —— 列出可用 Skill

```
输入: { "query": "营销" }  (可选)
输出: [
  { "name": "marketing", "description": "生成营销方案", "directory": "/skills/marketing" }
]
```

- 无 query → 返回所有 Skill 名称列表
- 有 query → 按名称和描述模糊匹配

### 7.2 skill_tool —— 读取 Skill 内容

```
输入: { "skill_name": "marketing", "relative_file_path": "SKILL.md" }
输出: {
  "success": true,
  "data": {
    "skill_directory": "/skills/marketing",
    "skill_content": "# Marketing Skill\n..."
  }
}
```

**安全校验**：readSkill 会检查 skill_name 是否在 `configuredSkills` 列表中，不在则拒绝访问。

---

## 八、enabledSkills / disabledSkills 过滤机制

```java
private List<Skill> configuredSkills(List<Skill> skills) {
    return skills.stream()
            .filter(skill -> enabledSkills.isEmpty() || enabledSkills.contains(value(skill.getName())))
            .filter(skill -> !disabledSkills.contains(value(skill.getName())))
            .toList();
}
```

过滤逻辑：
1. 如果 `enabledSkills` 为空 → 不过滤（全部启用）
2. 如果 `enabledSkills` 非空 → 只保留白名单中的 Skill
3. `disabledSkills` 优先级高于 `enabledSkills` → 即使在白名单中，也在黑名单中的 Skill 仍被排除

**配置方式**（HarnessConfig）：

```yaml
rails:
  - name: skill_use
    config:
      skills_dir: ["skills"]
      skill_mode: "auto_list"
      enabled_skills: ["marketing", "loan", "travel"]
      disabled_skills: ["internal_tool"]
```

---

## 九、远程 Skill 源

```java
public record RemoteSkillSource(
    String owner,     // GitHub 仓库 owner
    String repo,      // GitHub 仓库名
    String ref,       // 分支/Tag，默认 "HEAD"
    String directory, // 仓库内 Skill 目录路径
    String token      // 访问 Token
)
```

支持从 GitHub 仓库拉取 Skill，通过 `RemoteSkillUtil.uploadSkillFromGitHub()` 下载到本地 `skillsRoot` 后注册。

**配置示例**：

```yaml
rails:
  - name: skill_use
    config:
      remote_skills:
        - owner: "my-org"
          repo: "agent-skills"
          ref: "main"
          directory: "skills/finance"
          token: "ghp_xxxx"
```

---

## 十、能否满足不同用户访问不同 Skill？

### 10.1 当前实现分析

**结论：当前 SkillUseRail 不支持按用户维度区分 Skill 访问权限。**

原因：

1. **SkillUseRail 是 Agent 级别的 Rail**，在 `init()` 阶段一次性注册所有 Skill，与 Agent 生命周期绑定，不区分用户
2. **enabledSkills / disabledSkills 是静态配置**，在构造时确定，运行时不可变
3. **代码中无任何用户/Session 维度的过滤逻辑**——`configuredSkills()` 方法只根据名称白/黑名单过滤，不涉及用户身份
4. **beforeModelCall 注入的 Prompt 是全局的**，所有用户看到的 Skill 列表相同

### 10.2 实现用户级 Skill 访问控制的可行方案

| 方案 | 实现方式 | 改动量 | 优缺点 |
|------|----------|--------|--------|
| **方案 A：多 Agent 实例** | 为不同用户群体部署不同的 DeepAgent 实例，每个实例配置不同的 enabledSkills | 小 | 简单直接，但无法动态调整，实例数量膨胀 |
| **方案 B：动态 enabledSkills** | 在 beforeModelCall 中根据 Session 的用户信息动态计算 enabledSkills | 中 | 需要扩展 SkillUseRail，增加用户-Skill 映射接口 |
| **方案 C：Skill 权限拦截** | 在 readSkill 中增加用户权限校验，拒绝未授权访问 | 中 | 只控制读取，Prompt 中仍会暴露 Skill 名称 |
| **方案 D：动态 Skill 注册** | 根据 Session 用户信息，在每次请求时动态注册/注销 Skill | 大 | 最彻底，但改动大，影响性能 |

### 10.3 方案 B 的扩展思路（推荐）

```java
// 扩展 SkillUseRail，增加用户级 Skill 过滤
public class SkillUseRail extends DeepAgentRail {
    
    // 新增：用户-Skill 权限映射接口
    private SkillAccessControl skillAccessControl;
    
    @Override
    public void beforeModelCall(AgentCallbackContext ctx) {
        // 获取当前 Session 的用户信息
        String userId = extractUserId(ctx);
        
        // 根据用户过滤 Skill
        List<Skill> accessibleSkills = configuredSkills(skillManager.getAll());
        if (userId != null && skillAccessControl != null) {
            accessibleSkills = accessibleSkills.stream()
                .filter(skill -> skillAccessControl.canAccess(userId, skill.getName()))
                .toList();
        }
        
        String prompt = buildSkillPrompt(language, skillMode, accessibleSkills);
        // ... 注入 Prompt
    }
}

// 用户权限接口
public interface SkillAccessControl {
    boolean canAccess(String userId, String skillName);
}
```

**配置示例**：

```yaml
rails:
  - name: skill_use
    config:
      skills_dir: ["skills"]
      skill_mode: "auto_list"
      skill_access_control: "com.example.RoleBasedSkillAccess"
```

---

## 十一、完整执行流程图

```
Agent 启动
    │
    ▼
SkillUseRail.init()
    ├── 解析 skillMode、skillsRoot
    ├── 创建 SkillManager
    ├── 同步远程 Skill（GitHub → 本地）
    ├── 扫描本地 Skill 目录 → 注册到 SkillManager
    ├── 注册 list_skill 工具
    └── 注册 skill_tool 工具
    │
    ▼
每次 LLM 调用前
    │
    ▼
SkillUseRail.beforeModelCall()
    ├── skillMode == "none"? → 移除 Prompt Section
    ├── skillManager.count() == 0? → 移除 Prompt Section
    ├── configuredSkills() → 应用 enabledSkills/disabledSkills 过滤
    ├── buildSkillPrompt() → 根据 skillMode 生成 Prompt
    │   ├── "all" → 列出所有 Skill 名称+描述
    │   └── "auto_list" → 提示调用 list_skill 发现
    └── 注入到 System Prompt Section + 消息列表头部
    │
    ▼
LLM 决策
    │
    ├── (auto_list 模式) 调用 list_skill → 返回匹配的 Skill 列表
    │
    ├── 调用 skill_tool(skill_name, "SKILL.md") → 读取技能说明
    │   └── 安全校验：skill_name 必须在 configuredSkills 中
    │
    └── 按照 Skill 说明执行任务
```

---

## 十二、设计精髓总结

1. **描述驱动发现**：Skill 通过 SKILL.md 的 description 字段暴露给 LLM，LLM 根据任务语义自动匹配，无需硬编码
2. **两级工具设计**：`list_skill`（发现）+ `skill_tool`（读取），在 Skill 数量多时避免 Prompt 过长
3. **白/黑名单过滤**：enabledSkills / disabledSkills 提供静态的 Skill 访问控制
4. **远程 Skill 支持**：通过 RemoteSkillSource 从 GitHub 拉取 Skill，支持团队共享
5. **Prompt Section 机制**：通过 `addPromptBuilderSection` 动态注入/移除，与 Agent 的 Prompt 管理无缝集成

**当前局限**：SkillUseRail 的过滤机制是**静态的、Agent 级别的**，不支持按用户/Session 维度动态控制 Skill 访问权限。如需实现多用户不同 Skill 访问，需要扩展 `beforeModelCall` 钩子，引入用户级权限映射。

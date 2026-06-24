# SkillUseRail Skill 热加载机制解读

## 整体架构

Skill 热加载由 `SkillUseRail` 和 `SkillManager` 协同实现，核心思路是 **mtime 签名对比 + 增量刷新**，在 DeepAgent 每次调用 LLM 之前自动检测变更。

```
DeepAgent.run()
  → ReAct 循环，每轮调用 LLM
    → beforeModelCall 回调
      → SkillUseRail.beforeModelCall()
        → 签名对比 → 增量刷新 → 注入 prompt
```

## 触发时机：beforeModelCall

`SkillUseRail.beforeModelCall()` (line 202-217) 是热加载的唯一触发点，在每次 LLM 调用前执行：

```java
public void beforeModelCall(AgentCallbackContext ctx) {
    // 1. 快速退出：无 owner / 无 skillManager / 0 个 skill / mode=none
    if (owner == null || skillManager == null || skillManager.count() == 0 || "none".equals(skillMode)) {
        removePromptSection();
        return;
    }

    // 2. 构建当前签名（扫描磁盘）
    List<Map.Entry<String, Long>> currentSignature = buildCurrentSignature();

    // 3. 签名对比
    if (signaturesEqual(currentSignature, skillsSnapshotSignature)) {
        // 未变化 → 仅注入 prompt，不做刷新
        injectSkillPrompt(ctx);
        return;
    }

    // 4. 签名变化 → 增量刷新 + 更新缓存签名 + 注入 prompt
    prepareSkills();
    skillsSnapshotSignature = currentSignature;
    injectSkillPrompt(ctx);
}
```

**设计要点**：不需要轮询，在实际需要使用 skill 信息时才检查，兼顾及时性和低开销。

## 签名采集：buildSnapshotSignature

`SkillManager.buildSnapshotSignature()` (line 243-267) 扫描 skill root 目录，构建签名：

```java
public List<Map.Entry<String, Long>> buildSnapshotSignature(List<Path> roots) {
    List<Map.Entry<String, Long>> entries = new ArrayList<>();
    for (Path root : roots) {
        File[] subdirs = root.toFile().listFiles(File::isDirectory);
        Arrays.sort(subdirs, Comparator.comparing(File::getName));  // 按名称排序
        for (File subdir : subdirs) {
            File skillMd = new File(subdir, "SKILL.md");
            if (!skillMd.exists()) {
                skillMd = new File(subdir, "Skill.md");  // 大小写兼容
            }
            if (!skillMd.exists()) continue;  // 无 SKILL.md 的目录跳过
            String key = subdir.toPath().toAbsolutePath().normalize().toString();
            entries.add(Map.entry(key, skillMd.lastModified()));  // (路径, mtime)
        }
    }
    return entries;
}
```

签名格式：`[(skill_a_dir_path, mtime1), (skill_b_dir_path, mtime2), ...]`

**签名能检测到的变更类型**：
- 新增 skill：签名长度变长（新条目出现）
- 删除 skill：签名长度变短（条目消失）
- 修改 skill：某条目的 mtime 值变化

## 签名对比：signaturesEqual

`SkillUseRail.signaturesEqual()` (line 331-348) 逐条对比：

```java
private static boolean signaturesEqual(List<Map.Entry<String, Long>> a, List<Map.Entry<String, Long>> b) {
    if (a == null && b == null) return true;
    if (a == null || b == null) return false;
    if (a.size() != b.size()) return false;  // 长度不同 → 有增删
    for (int i = 0; i < a.size(); i++) {
        if (!a.get(i).getKey().equals(b.get(i).getKey())
                || a.get(i).getValue() != b.get(i).getValue()) {  // ⚠ 见下方 Bug 分析
            return false;
        }
    }
    return true;
}
```

## 增量刷新：refreshIncrementally

`SkillManager.refreshIncrementally()` (line 153-210) 是热加载的核心，只做最小变更：

### 三种变更场景的处理

| 场景 | 判断条件 | 处理方式 |
|------|---------|---------|
| **新增** | `updateAtCache` 无此 key | 全新加载 Skill，加入 registry 和 cache |
| **修改** | `updateAtCache` 有此 key，但 mtime 不同 | 重新加载 Skill，覆盖 registry 旧值，更新 cache |
| **删除** | `updateAtCache` 有此 key，但磁盘扫描未发现 | 从 registry 移除，从 cache 移除 |
| **未变** | key 存在且 mtime 相同 | 跳过，不重新加载 |

```java
public void refreshIncrementally(List<Path> roots) {
    Set<String> discoveredKeys = new LinkedHashSet<>();  // 本次扫描发现的 key
    List<String> orderedKeys = new ArrayList<>();        // 保持顺序

    for (Path root : roots) {
        File[] subdirs = root.toFile().listFiles(File::isDirectory);
        Arrays.sort(subdirs, Comparator.comparing(File::getName));
        for (File subdir : subdirs) {
            File skillMd = new File(subdir, "SKILL.md");
            if (!skillMd.exists()) continue;

            String key = subdir.toPath().toAbsolutePath().normalize().toString();
            long mtime = skillMd.lastModified();

            discoveredKeys.add(key);
            orderedKeys.add(key);

            // 新增或修改：mtime 不匹配才重新加载
            Long cachedMtime = updateAtCache.get(key);
            if (cachedMtime == null || cachedMtime != mtime) {  // ⚠ 这里 != 没问题（见下方分析）
                Skill skill = createSkillFromPath(skillMd.toPath());
                if (skill != null) {
                    skill.setUpdateAt(mtime);
                    registry.put(skill.getName(), skill);
                    updateAtCache.put(key, mtime);
                }
            }
        }
    }

    // 删除：缓存中有但磁盘没发现的
    Set<String> staleKeys = new LinkedHashSet<>(updateAtCache.keySet());
    staleKeys.removeAll(discoveredKeys);
    for (String key : staleKeys) {
        Skill stale = findSkillByDirectory(key);
        if (stale != null) registry.remove(stale.getName());
        updateAtCache.remove(key);
    }

    skillOrder.clear();
    skillOrder.addAll(orderedKeys);
}
```

## Skill 加载：createSkillFromPath

`SkillManager.createSkillFromPath()` (line 350-365) 从 SKILL.md 文件创建 Skill 对象：

```java
private Skill createSkillFromPath(Path path) {
    String descriptionText = loadDescription(path);
    if (descriptionText != null) {
        Path skillDir = path.getParent();
        return Skill.builder()
                .name(skillDir.getFileName().toString())   // skill 名 = 目录名
                .description(descriptionText)              // 描述从 YAML front matter 读取
                .directory(skillDir.toString())             // skill 目录路径
                .build();
    }
    return null;
}
```

`loadDescription()` (line 370-394) 从 SKILL.md 的 YAML front matter 中提取 `description:` 字段。

## Prompt 注入：injectSkillPrompt

`SkillUseRail.injectSkillPrompt()` (line 350-365) 将 skill 信息注入到 system prompt 中：

```java
private void injectSkillPrompt(AgentCallbackContext ctx) {
    String prompt = buildSkillPrompt(language, skillMode, configuredSkills(skillManager.getAllInOrder()));
    owner.getAgent().addPromptBuilderSection(SKILL_SECTION, prompt, SKILL_SECTION_PRIORITY);
    if (ctx != null && ctx.getInputs() instanceof ModelCallInputs inputs && inputs.getMessages() != null) {
        // 防止重复注入：仅在 messages 中未包含 skill 信息时才添加
        boolean isPromptAlreadyInjected = inputs.getMessages().stream()
                .filter(SystemMessage.class::isInstance)
                .anyMatch(content -> content.contains("技能名称:") || content.contains("Skill name:"));
        if (!isPromptAlreadyInjected) {
            inputs.getMessages().add(0, new SystemMessage(prompt));
        }
    }
}
```

中文 prompt 格式（skillMode="all"）：
```
你已配备任务技能。
当当前任务匹配下列技能时，先调用 skill_tool 阅读技能说明并遵循其中流程。
0. 技能名称: skill_a; 描述: Modified Skill A; 目录: /path/to/skill_a
1. 技能名称: skill_b; 描述: Skill B; 目录: /path/to/skill_b
```

## Bug 分析：Long 类型引用比较

`signaturesEqual` line 342：

```java
a.get(i).getValue() != b.get(i).getValue()
```

`getValue()` 返回 `Long`（包装类型），两边都是 Long 对象，`!=` 是**引用比较**而非值比较。

- `Long` 缓存范围 -128~127，超出范围的值不会缓存
- 文件 mtime 是毫秒级时间戳（如 `1782304350826`），远超缓存范围
- 两个不同 Long 对象即使值相同，`!=` 也返回 `true`

**后果**：`signaturesEqual` 对 mtime 的比较永远认为不相等 → 每次 `beforeModelCall` 都触发 `prepareSkills()` → 增量刷新退化为全量刷新。

**对比**：`refreshIncrementally` line 184 的 `cachedMtime != mtime` 没有问题，因为 `mtime` 是 `long` 基本类型，`cachedMtime` 会自动拆箱，实际执行的是值比较。

**修复建议**：

```java
// 方案1：改用 equals
|| !a.get(i).getValue().equals(b.get(i).getValue())

// 方案2：改用 long 基本类型（需调整签名数据结构）
// Map.Entry<String, Long> → 自定义结构用 long
```

## 数据流全景图

```
                    SkillUseRail                    SkillManager
                    ──────────                      ───────────
初始化:
  init(agent)
    → new SkillManager(id)
    → reloadSkills()
      → prepareSkills()
        → collectSkillRoots()                      → refreshIncrementally(roots)
          → 返回 [skillsDir path]                    → 扫描子目录 + SKILL.md
                                                    → 构建/更新 registry + updateAtCache + skillOrder
        → buildCurrentSignature()                  → buildSnapshotSignature(roots)
          → 返回 [(path, mtime)...]                   → 扫描子目录，返回 [(path, mtime)...]
      → skillsSnapshotSignature = 签名

运行时 (每轮 LLM 调用):
  beforeModelCall(ctx)
    → buildCurrentSignature()                      → buildSnapshotSignature(roots)
    → signaturesEqual(当前, 缓存)?
      → 相同 → injectSkillPrompt(ctx)
      → 不同 → prepareSkills()                     → refreshIncrementally(roots)
               → skillsSnapshotSignature = 当前       → 新增/修改/删除/跳过
               → injectSkillPrompt(ctx)
```

## 配置过滤：configuredSkills

`registeredSkillNames()` 和 `injectSkillPrompt()` 都通过 `configuredSkills()` 过滤：

```java
private List<Skill> configuredSkills(List<Skill> skills) {
    return skills.stream()
            .filter(skill -> enabledSkills.isEmpty() || enabledSkills.contains(skill.getName()))
            .filter(skill -> !disabledSkills.contains(skill.getName()))
            .toList();
}
```

- `enabledSkills` 为空 → 所有 skill 都可用（默认行为）
- `enabledSkills` 非空 → 仅启用列表中的 skill
- `disabledSkills` → 排除列表中的 skill

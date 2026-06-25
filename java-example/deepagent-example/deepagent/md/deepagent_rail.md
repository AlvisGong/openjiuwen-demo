# DeepAgent 内置 Rail 全景介绍

DeepAgent 的 Rail 机制是其 Harness 层的核心扩展点，每个 Rail 是一个可插拔的"护栏"，在 Agent 执行生命周期的关键节点（init、beforeInvoke、beforeModelCall、afterModelCall、beforeToolCall、afterToolCall、afterInvoke）注入自定义逻辑。

所有内置 Rail 继承自 DeepAgentRail，按功能可分为 **6 大类**：

---

## 一、上下文管理类（3 个）

### 1. ContextProcessorRail — 上下文压缩管线构建器

| 属性 | 说明 |
|------|------|
| **优先级** | 50 |
| **功能** | 构建、注入、管理上下文处理管线（标准管线 / SessionMemory 管线），在每次 LLM 调用前自动执行压缩 |
| **核心钩子** | `init()` → 注册处理器；`beforeModelCall()` → 触发 `buildContextWindow()` |
| **使用场景** | 长会话场景，Agent 多轮调用工具后上下文膨胀，需要在 token 预算内保留最大信息量 |

### 2. ContextAssembleRail — 上下文动态组装器

| 属性 | 说明 |
|------|------|
| **优先级** | 50 |
| **功能** | 在每次 LLM 调用前，动态注入工作区文件结构、可用工具列表、context/ 目录下的文件内容到 System Prompt |
| **核心钩子** | `beforeModelCall()` → `buildWorkspaceSection()` + `buildToolsSection()` + `buildContextSection()` |
| **使用场景** | 需要让 Agent 感知工作区结构和外部知识文档的场景（如代码助手需要知道项目文件树） |

### 3. HeartbeatRail — 心跳检测

| 属性 | 说明 |
|------|------|
| **优先级** | 80 |
| **功能** | 当 Agent 以 heartbeat 模式运行时，读取 `HEARTBEAT.md` 文件内容注入 System Prompt，Agent 需处理心跳内容或回复 `HEARTBEAT_OK` |
| **核心钩子** | `beforeModelCall()` → 判断是否心跳运行 → 注入心跳提示 |
| **使用场景** | 定时巡检场景，系统定期唤醒 Agent 检查是否有待处理事项（如监控告警处理、定时数据同步） |

---

## 二、记忆管理类（3 个）

### 4. MemoryRail — 内置轻量记忆

| 属性 | 说明 |
|------|------|
| **优先级** | 80 |
| **功能** | 基于 Embedding 的轻量记忆系统，提供 `memory_read`/`memory_write`/`memory_edit` 工具，支持语义检索 |
| **核心钩子** | `init()` → 初始化 MemoryIndexManager；`beforeModelCall()` → 注入记忆提示 |
| **使用场景** | Agent 需要在会话内存储和检索结构化知识片段（如会议纪要、技术决策记录） |

### 5. ExternalMemoryRail — 外部长期记忆

| 属性 | 说明 |
|------|------|
| **优先级** | 75 |
| **功能** | 对接外部记忆服务（OpenJiuwen/Viking/Mem0），提供 `prefetch`（预取相关记忆注入上下文）和 `syncTurn`（同步本轮对话到长期记忆）能力 |
| **核心钩子** | `init()` → 注册 provider 工具；`beforeModelCall()` → prefetch；`afterInvoke()` → syncTurn |
| **使用场景** | 跨会话记忆场景，Agent 需要记住用户画像、历史偏好、之前讨论的内容 |

### 6. CodingMemoryRail — 编码记忆

| 属性 | 说明 |
|------|------|
| **优先级** | 80 |
| **功能** | 继承 MemoryRail，专门为编码场景设计，提供 `coding_memory_read`/`coding_memory_write`/`coding_memory_edit` 工具，自动索引代码文件 |
| **核心钩子** | `init()` → 创建 CodingMemoryToolContext；`beforeModelCall()` → 注入编码记忆提示 |
| **使用场景** | 代码助手场景，Agent 需要记住代码结构、API 用法、项目约定 |

---

## 三、任务控制类（4 个）

### 7. TaskPlanningRail — 任务规划与进度管理

| 属性 | 说明 |
|------|------|
| **优先级** | 90 |
| **功能** | 提供 `todo_create`/`todo_list`/`todo_modify` 工具，支持任务拆解、进度跟踪、模型路由（不同 todo 项用不同 LLM）、进度提醒 |
| **核心钩子** | `init()` → 注册 todo 工具；`beforeModelCall()` → 注入 todo 提示 + 模型路由；`afterToolCall()` → 进度提醒；`afterTaskIteration()` → 快照持久化 |
| **使用场景** | 复杂多步骤任务，需要 Agent 自主拆解任务、跟踪进度、定期回顾 |

### 8. TaskCompletionRail — 任务完成信号

| 属性 | 说明 |
|------|------|
| **优先级** | 10 |
| **功能** | 定义"完成承诺"（completionPromise），Agent 完成任务时输出 `<promise>xxx</promise>` 标签，Rail 检测到后触发外循环停止评估 |
| **核心钩子** | `beforeModelCall()` → 注入完成信号提示；`applyTaskInstruction()` → 包装用户 query |
| **使用场景** | 需要明确知道 Agent 何时完成任务并自动停止的场景（如自动化流水线） |

### 9. AgentModeRail — Agent 模式切换

| 属性 | 说明 |
|------|------|
| **优先级** | 60 |
| **功能** | 支持 Normal/Plan 两种模式切换，Plan 模式下 Agent 只做规划不执行写操作，Normal 模式下正常执行。提供 `switch_mode`/`enter_plan_mode`/`exit_plan_mode` 工具 |
| **核心钩子** | `init()` → 注册模式切换工具；`beforeModelCall()` → 注入模式提示；`beforeToolCall()` → Plan 模式下拦截写工具 |
| **使用场景** | 需要先规划再执行的场景（如代码重构、系统设计），避免 Agent 一边想一边改导致混乱 |

### 10. SessionRail — 多会话管理

| 属性 | 说明 |
|------|------|
| **优先级** | 95 |
| **功能** | 提供 `sessions_list`/`sessions_cancel`/`sessions_spawn` 工具，支持 Agent 并行派生子会话执行子任务 |
| **核心钩子** | `init()` → 注册会话管理工具 |
| **使用场景** | Agent 需要并行处理多个独立子任务的场景（如同时调研多个竞品） |

---

## 四、Skill 与子智能体类（5 个）

### 11. SkillUseRail — Skill 注册与发现

| 属性 | 说明 |
|------|------|
| **优先级** | 100 |
| **功能** | 注册 Skill 目录下的技能，提供 `list_skill`/`skill_tool` 工具，Agent 根据任务描述自动选择匹配的 Skill。支持 enabledSkills/disabledSkills 访问控制 |
| **核心钩子** | `init()` → 扫描 Skill 目录 + 注册工具；`beforeModelCall()` → 注入 Skill 提示到 System Prompt |
| **使用场景** | 动态规划智能体，Agent 需要根据任务自动选择调用哪个工作流/子智能体，无需硬编码 |

### 12. SkillCreateRail — Skill 自动创建

| 属性 | 说明 |
|------|------|
| **优先级** | 85 |
| **功能** | 继承 EvolutionRail，当 Agent 工具调用次数或多样性超过阈值时，自动提议创建新 Skill，将重复操作固化为可复用技能 |
| **核心钩子** | `onBeforeInvoke()` → 重置状态；`onAfterToolCall()` → 累计工具调用；`onAfterInvoke()` → 判断是否触发 Skill 创建 |
| **使用场景** | Agent 频繁执行相似操作时，自动沉淀为 Skill，提升后续执行效率 |

### 13. TeamSkillRail — 团队协作 Skill

| 属性 | 说明 |
|------|------|
| **优先级** | 80 |
| **功能** | 继承 EvolutionRail，当所有团队任务完成时（`view_task` 工具返回全部完成），自动触发进化分析 |
| **核心钩子** | `onAfterToolCall()` → 检测 `view_task` 结果是否全部完成 |
| **使用场景** | 多 Agent 协作场景，团队任务全部完成后自动触发复盘和 Skill 沉淀 |

### 14. TeamSkillCreateRail — 团队 Skill 创建

| 属性 | 说明 |
|------|------|
| **优先级** | 85 |
| **功能** | 继承 EvolutionRail，当团队协作中 Agent 数量超过阈值时，自动提议创建团队级 Skill |
| **核心钩子** | `onAfterToolCall()` → 检测团队规模是否满足创建条件 |
| **使用场景** | 大规模多 Agent 协作，需要将协作模式固化为可复用 Skill |

### 15. SubagentRail — 子智能体调用

| 属性 | 说明 |
|------|------|
| **优先级** | 95 |
| **功能** | 注册 `task_tool` 工具，Agent 可通过 `subagent_type` + `task_description` 调用配置的子智能体 |
| **核心钩子** | `init()` → 注册 task_tool，描述中列出可用子智能体 |
| **使用场景** | 超级智能体场景，主 Agent 调度多个专业子智能体（如审查 Agent、验证 Agent） |

---

## 五、安全与验证类（4 个）

### 16. SecurityRail — 安全护栏

| 属性 | 说明 |
|------|------|
| **优先级** | 80 |
| **功能** | 只读模式控制，拦截写工具（write_file、edit_file、bash 写命令等），防止 Agent 在只读模式下执行破坏性操作 |
| **核心钩子** | `beforeToolCall()` → 校验工具是否为写操作 → 拦截或放行 |
| **使用场景** | 只读审查场景（如代码审查、数据分析），需要 Agent 只看不改 |

### 17. VerificationRail — 验证 Agent 约束

| 属性 | 说明 |
|------|------|
| **优先级** | 95 |
| **功能** | 限制验证 Agent 只能使用只读工具，强制每个验证步骤包含命令输出，必须以 `VERDICT: PASS/FAIL/PARTIAL` 结尾 |
| **核心钩子** | `init()` → 注入验证约束提示；`beforeToolCall()` → 拦截非允许工具 |
| **使用场景** | 代码验证场景，需要独立 Agent 验证实现是否正确，不能自己改自己验 |

### 18. VerificationContractRail — 验证契约

| 属性 | 说明 |
|------|------|
| **优先级** | 88 |
| **功能** | 强制 Agent 在非平凡实现后必须调用验证子智能体（`verification_agent`），验证通过后才能报告完成 |
| **核心钩子** | `beforeModelCall()` → 注入验证门控提示 |
| **使用场景** | 高质量代码生成场景，确保每次实现都经过独立验证 |

### 19. PermissionInterruptRail — 权限中断

| 属性 | 说明 |
|------|------|
| **优先级** | 90 |
| **功能** | 基于 PermissionEngine 检查工具调用权限，ALLOW 放行、DENY 拒绝、ASK 暂停等待用户确认 |
| **核心钩子** | `resolveInterrupt()` → 检查权限 → 放行/拒绝/中断等待用户 |
| **使用场景** | 敏感操作场景（如删除文件、执行 Shell 命令），需要用户确认后才能执行 |

---

## 六、工具与集成类（6 个）

### 20. ProgressiveToolRail — 渐进式工具加载

| 属性 | 说明 |
|------|------|
| **优先级** | 70 |
| **功能** | 默认只暴露核心工具，Agent 通过 `search_tools`/`load_tools` 按需发现和加载更多工具，减少 System Prompt 长度 |
| **核心钩子** | `init()` → 注册元工具；`beforeModelCall()` → 注入工具导航提示 |
| **使用场景** | 工具数量很多的场景（如 100+ 工具），避免一次性把所有工具描述塞进 System Prompt |

### 21. ToolTrackingRail — 工具调用追踪

| 属性 | 说明 |
|------|------|
| **优先级** | 5 |
| **功能** | 在工具调用前后通过 `session.writeStream()` 发送 `tool_call`/`tool_result` 事件，供前端实时展示 |
| **核心钩子** | `beforeToolCall()` → 发送 tool_call 事件；`afterToolCall()` → 发送 tool_result 事件 |
| **使用场景** | 前端需要实时展示 Agent 工具调用过程的场景 |

### 22. McpRail — MCP 资源集成

| 属性 | 说明 |
|------|------|
| **优先级** | 95 |
| **功能** | 注册 `list_mcp_resources`/`read_mcp_resource` 工具，Agent 可访问 MCP（Model Context Protocol）协议的外部资源 |
| **核心钩子** | `init()` → 注册 MCP 工具 |
| **使用场景** | 需要访问 MCP 协议提供的外部数据源（如数据库、文件系统、API） |

### 23. LspRail — LSP 语言服务集成

| 属性 | 说明 |
|------|------|
| **优先级** | 60 |
| **功能** | 集成 LSP（Language Server Protocol），提供代码智能功能（跳转定义、查找引用、诊断等） |
| **核心钩子** | `init()` → 启动 LSPServerManager；`uninit()` → 关闭所有语言服务器 |
| **使用场景** | 代码助手场景，Agent 需要精确的代码导航和诊断能力 |

### 24. SysOperationRail — 系统操作

| 属性 | 说明 |
|------|------|
| **优先级** | 60 |
| **功能** | 暴露系统操作工具（占位 Rail，具体工具由 SysOperation 体系注册） |
| **使用场景** | Agent 需要执行系统级操作（如环境配置、服务管理） |

### 25. BrowserRuntimeRail — 浏览器运行时

| 属性 | 说明 |
|------|------|
| **优先级** | 默认 |
| **功能** | 管理 BrowserAgentRuntime，为 Agent 提供浏览器自动化能力 |
| **使用场景** | Agent 需要操作浏览器（如网页抓取、UI 测试、表单填写） |

---

## 七、中断交互类（2 个）

### 26. AskUserRail — 用户询问中断

| 属性 | 说明 |
|------|------|
| **优先级** | 90 |
| **功能** | 当 Agent 调用 `ask_user` 工具时，暂停执行等待用户输入，用户回复后继续 |
| **核心钩子** | `resolveInterrupt()` → 无用户输入则中断，有则放行 |
| **使用场景** | Agent 执行过程中需要向用户提问（如"请确认是否继续"） |

### 27. ConfirmInterruptRail — 确认中断

| 属性 | 说明 |
|------|------|
| **优先级** | 90 |
| **功能** | 对指定工具调用弹出确认提示，用户确认后执行，拒绝则跳过 |
| **核心钩子** | `resolveInterrupt()` → 无用户输入则中断等待确认，用户可 approve/reject |
| **使用场景** | 敏感工具调用前需要用户确认（如删除操作、发送邮件） |

---

## Rail 优先级总览

```
优先级 100: SkillUseRail              ← Skill 提示最先注入
优先级  95: SubagentRail, SessionRail, VerificationRail, McpRail
优先级  90: TaskPlanningRail, AskUserRail, ConfirmInterruptRail, PermissionInterruptRail
优先级  88: VerificationContractRail
优先级  85: SkillCreateRail, TeamSkillCreateRail
优先级  80: SecurityRail, MemoryRail, CodingMemoryRail, HeartbeatRail, TeamSkillRail, ExternalMemoryRail
优先级  75: ProgressiveToolRail（规则）
优先级  70: ProgressiveToolRail（导航）
优先级  60: AgentModeRail, LspRail, SysOperationRail, EvolutionRail
优先级  50: ContextProcessorRail, ContextAssembleRail
优先级  10: TaskCompletionRail
优先级   5: ToolTrackingRail           ← 追踪最后执行
```

优先级越高越先执行。**SkillUseRail 最先注入 Skill 提示**，**ToolTrackingRail 最后追踪工具调用**，**ContextProcessorRail 在中间执行压缩**——这个顺序确保了提示注入在压缩之前完成，追踪在所有逻辑之后。

---

## Rail 生命周期钩子一览

| 钩子 | 触发时机 | 典型用途 |
|------|---------|---------|
| `init(agent)` | Agent 初始化时 | 注册工具、初始化资源 |
| `uninit(agent)` | Agent 销毁时 | 释放资源、注销工具 |
| `beforeInvoke(ctx)` | Agent.invoke() 执行前 | 重置状态、注入初始指令 |
| `beforeModelCall(ctx)` | 每次 LLM 调用前 | 注入 System Prompt、模型路由 |
| `afterModelCall(ctx)` | 每次 LLM 调用后 | 记录 usage、提取信息 |
| `beforeToolCall(ctx)` | 每次工具调用前 | 安全校验、权限检查、中断 |
| `afterToolCall(ctx)` | 每次工具调用后 | 追踪、进度更新、进化触发 |
| `afterInvoke(ctx)` | Agent.invoke() 执行后 | 同步记忆、清理状态 |
| `afterTaskIteration(ctx)` | 外循环每轮结束后（TaskIterationRail） | 快照持久化、任务同步 |

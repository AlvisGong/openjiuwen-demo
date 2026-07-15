# Qoder Harness Engineering 实践指南

> 核心命题:与其教 Agent 怎么做,不如让它自己验证做得对不对。靠代码、linter、测试保证正确性,而非靠 LLM 的"直觉"。

## 一、问题本质:Agent 不是不够聪明,而是"看不见"

### 典型场景

Agent 写 200 行代码 → lint 失败 → 修复 → 新问题 → 循环三次 → 上下文被错误日志塞满 → **忘记最初任务目标**。

### 为什么"教得更好"有天花板

| 手段 | 局限 |
|------|------|
| 更详细的 system prompt | 无法穷尽代码库的所有隐式规则 |
| 更大的上下文窗口 | 装不下整个仓库的架构决策 |
| 规范文档放钉钉/Notion | **AI 读不到** |
| 依赖 AI 的"常识" | 不同模型差异大,不可靠 |

**Harness 的解法**:把团队约定从"希望被遵守"变成"不遵守就报错"——机械化检查不会出错、不会遗忘、也不会被上下文压缩掉。

## 二、核心隐喻:仓库是 Agent 的操作系统

**类比**:CPU 很强大,但没有操作系统它就是一块无方向的芯片;LLM 推理能力很强,但不知道 internal/types/ 不能 import internal/config/。**Harness 就是给 LLM 装的"操作系统"**。

### 四条关键原则

| 原则 | 内容 |
|------|------|
| **1. 仓库是唯一事实来源** | Aone 讨论、钉钉口头约定、架构师脑子里的蓝图,对 Agent 都不存在。不在仓库里 = Agent 看不见 = 就会违反。一切编码到仓库作为版本化文件提交到 Git |
| **2. AGENTS.md 是地图不是手册** | 控制在 ~100 行,只做索引和指路;详细内容放 docs/ 按需加载。短小精悍 → 不容易腐烂 |
| **3. 约束只管架构边界** | 不规定设计模式或函数写法,只编码层级方向(Layer 0 类型 → Layer 1-2 工具/配置 → Layer 3 业务 → Layer 4+ HTTP/CLI)。规则一条:高层 import 低层,反过来不行。**中心化约束,本地自治** |
| **4. 人设计系统,Agent 执行** | 人的价值从"写出正确代码"变成"设计出让 Agent 能可靠产出正确代码的环境" |

## 三、落地架构:两个引擎协作

### 引擎分工

| 引擎 | 职责 |
|------|------|
| **harness-creator** | 分析代码库,生成基础设施(文档、lint 脚本、目录结构);首次运行按文档覆盖率/lint 覆盖率打 0-100 分 |
| **harness-executor** | 在基础设施中执行开发任务;启动时先看 AGENTS.md 在不在,不在就自动喊 creator 来搭 |

### 项目结构

```
my-project/
├── AGENTS.md              ← 导航地图(~100行)
├── docs/
│   ├── ARCHITECTURE.md    ← 架构、层级、依赖规则
│   ├── DEVELOPMENT.md     ← 构建/测试/lint 命令
│   ├── PRODUCT_SENSE.md   ← 业务上下文
│   ├── design-docs/       ← 组件设计文档
│   └── exec-plans/        ← 执行计划(active / completed)
├── scripts/
│   ├── lint-deps.*        ← 层级依赖检查
│   ├── lint-quality.*     ← 代码质量规则
│   ├── verify/            ← 端到端功能验证
│   └── validate.py        ← 统一验证管道
└── harness/
    ├── tasks/             ← 任务状态和检查点
    ├── trace/             ← 执行轨迹和失败记录
    └── memory/            ← 经验教训存储
```

### executor 工作流

```
检测环境 → 加载上下文 → 制定计划 → 人类批准 → 执行 → 验证 → 完成
```

"人类批准"不是走过场:executor 创建执行计划文件(任务目标/影响范围/分阶段步骤/验证方式/回退策略),人扫一眼觉得不对可直接改方向。

## 四、验证体系:事前预防 + 事后检查

### 四类验证

```
build → lint-arch → test → verify
  │        │         │       │
  │        │         │       └─ 端到端功能验证(用户路径对不对)
  │        │         └─ 单元/集成测试
  │        └─ 架构和质量合规
  └─ 代码能否编译
```

| 验证类 | 内容 |
|--------|------|
| **lint-deps** | 依赖方向检查:core/ 不能 import ui/,api/ 和 cli/ 不能互相引用 |
| **lint-quality** | 质量规则:单文件 ≤500 行、禁止 console.log/print()、禁止硬编码品牌字符串 |
| **test** | 单元/集成测试 |
| **verify** | 端到端功能验证:不是"函数返回值对不对",而是"用户执行操作最终结果对不对" |

### 事前预防:在写代码前先问"能不能做"

**核心洞察**:层级违反在 50 行代码写完后才被 linter 抓到,修复代价大(撤销改动、重新设计,消耗 ~10 次 tool call);如果写代码前先问一句"这样做合法吗",两次交互就够了。

```bash
python3 scripts/verify_action.py --action "create file internal/types/user.go"
# ✓ VALID: internal/types/ is Layer 0, user.go follows naming convention

python3 scripts/verify_action.py --action "import internal/core from internal/handler"
# ✗ INVALID: internal/handler (L4) cannot import internal/core (L3)
#   Fix: handler should depend on core through interfaces
```

**何时需要预验证**:涉及"在新位置创建文件"或"添加跨包 import"时必做;改函数体/加测试文件不需要。

### 报错信息即教学

```
差:Forbidden import in core/types/user.go         ← 不知道怎么办
好:core/types/user.go imports core/config (Layer 0 → Layer 2).
   Layer 0 packages must have NO internal dependencies.
   Fix: Move config-dependent logic to a higher layer,
        or pass the config value as a parameter.    ← 规则+原因+修法
```

**一条好的报错本身就是一次教学。**

### 修复循环

- 验证挂了 → executor 自动进入修复循环(分析错误→改代码→重新验证),1-3 轮收敛
- 同一错误转 3 圈还没过 → **停下来交给人**(再挣扎上下文窗口要爆了)
- 踩坑经验:故意引入违规测试 lint / 永不靠禁用规则"解决"问题 / 测试只跑受影响包

## 五、上下文是最贵的资源:协调者绝不写代码

### 核心铁律

> **中等复杂度以上的任务,协调者(Coordinator)绝不写代码。**

**为什么**:LLM 上下文窗口有限。第 40 次 tool call 时,早期关键信息已被压缩丢弃;到第 60 次,Agent 可能忘了最初任务目标。

### 两层 Agent 架构

| 角色 | 职责 | 上下文策略 |
|------|------|-----------|
| **协调者** | 规划、委派、汇总 | 只保留摘要,不碰代码 |
| **子代理** | 从干净上下文执行 | 干完就释放,详细上下文丢掉 |

**信息经过压缩和筛选,而不是无差别堆在上下文里。**

### "就改一行"陷阱

协调者发现小问题心想"直接改"→ 一次编辑变五次 → 五次变二十次 → 上下文耗尽。**代码牵连性远超直觉**。发现协调者在用 Edit/Write 工具,立刻停下来启动子代理,**没有例外**。

### 复杂度分级执行

| 复杂度 | 例子 | 执行方式 |
|--------|------|---------|
| 简单 | 改 typo、加日志 | 直接做 |
| 中等 | 多文件一致性修改 | 委派子代理 |
| 复杂 | 重构、新模块 | 委派 + Git Worktree 隔离(成功合并,失败丢掉) |

**快速判断**:能用一句话描述且不含"和"字的直接做;需要清单跟踪的委派;需要设计决策的委派+隔离。

### 模型分级委派

不是所有任务都要用最强模型——重命名变量 vs 重构认证模块,能力要求完全不同。

| 任务类型 | 推荐模型 | 理由 |
|---------|---------|------|
| 快速执行(改 typo/简单重命名) | Claude Haiku | 快和便宜 |
| 深度推理(复杂重构/架构变更) | GPT-5.3 Codex / Claude Opus | 质量远比速度重要 |
| 代码检索(定位相关文件) | Gemini 3 Flash | 速度第一 |
| 协调者本身 | 中等模型 | 不写代码,只调度 |

**总成本降低 60-70%,复杂任务质量不打折扣。**

### 交叉 Review:用不同模型当"另一双眼睛"

机械化验证抓不到的问题:竞态条件、边界遗漏、不必要的复杂度、命名不清。

**关键:用不同模型 review**——同一模型对自己产出"视而不见",换不同架构和训练数据的模型,思维盲区重叠小。

- 时机:编码完成 + 机械验证通过后,协调者接受结果前
- 成本:约为编码成本的 10-20%(只需读 diff 和文档)
- 副作用:review 发现的问题记录到 harness/trace/,反复出现就编码成新 lint 规则

### 检查点机制

每完成一个阶段 + 跑过验证就存档,**包括已有的架构决策**。任务中断后下一个 Agent 从检查点恢复,不会做出跟前面矛盾的选择。

## 六、Harness 自我进化:让系统从失败中学习

### 闭环机制

```
Agent 执行 → 验证抓到问题 → Critic 分析模式 → Refiner 更新规则 → 下一个 Agent 受益
```

每次验证失败结构化保存到 `harness/trace/failures/`。Critic 脚本定期分析,找模式和根因(如:internal/cache 被 7 次违规 import → 根因是没加入层级映射表 → 建议加入 Layer 1)。Refiner 据此更新:补遗漏包到 linter、改写含糊错误信息、补缺失文档。

### 三种记忆

| 记忆类型 | 内容 | 价值 |
|---------|------|------|
| **情景记忆** | 具体事件和教训(如 macOS 下 /var 是 /private/var 符号链接,会导致路径比较失败) | 10 秒加载省下一个重试循环 |
| **程序记忆** | 成功操作步骤(如"添加 API 端点的标准五步流程,成功率 90%") | 新子代理执行同类任务先查这里 |
| **失败记忆** | 供 Critic 分析用 | 驱动 Harness 进化 |

### 轨迹编译:从 LLM 到确定性脚本

同一类任务被成功执行 ≥3 次且步骤高度一致(如"添加 API 端点"每次都是:创建类型文件→写服务方法→加 handler→注册路由→写测试),这个模式可"编译"成确定性脚本:

```bash
make add-endpoint NAME=foo
```

以后同类任务直接跑脚本,**LLM 都省了**。脚本失败再回退到 Agent 模式。

## 七、一句话总结

**Qoder 的 Harness Engineering 把仓库当作 Agent 的操作系统,通过四条原则(仓库是唯一事实来源 / AGENTS.md 是地图 / 约束只管架构边界 / 人设计系统 Agent 执行)+ 两个引擎(creator 建基础设施 / executor 在其中执行)+ 四类验证(build/lint-arch/test/verify)+ 事前预防(verify_action)+ 两层架构(协调者不写代码 / 子代理隔离执行)+ 模型分级 + 交叉 review + 自我进化闭环(Critic/Refiner + 三种记忆 + 轨迹编译),把团队约定从"希望被遵守"变成"不遵守就报错",让 Agent 在奖励不确定的代码环境中可靠产出正确代码。**

> **竞争优势不再是 Prompt,而是 Trajectory。这些积累,换个模型复制不来。**

> 参考资料：Qoder 工程实践：Harness Engineering 指南 https://mp.weixin.qq.com/s/Et3WwNtEXEgxjaQHrQFDyQ
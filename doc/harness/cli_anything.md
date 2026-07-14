# CLI-Anything:群体智能的基础设施

> 来源:香港大学数据智能实验室(HKUDS),github.com/HKUDS/CLI-Anything

---

## 一、问题诊断:Agent 的"GUI 墙"

> "AI Agent 能推理、能写代码、能搜索,但让它打开 GIMP 去掉一张图的背景,或者用 Blender 渲染一个 3D 场景?它做不到。"

这句话点出了当前 Agent 的核心瓶颈:**Agent 是文本生物,但大量生产力软件是 GUI 形态**。

### 问题的本质

```
Agent 能接触的世界              人类真实使用的软件
─────────────────              ─────────────────
✓ 文本/代码                     ✓ Word/Excel/PPT
✓ 命令行工具                    ✓ Photoshop/GIMP
✓ HTTP API                     ✓ Blender/Maya
✓ 文件系统                      ✓ Premiere/Audacity
                                ✓ 各种企业 SaaS
[文本世界]  ←—— 墙 ——→  [GUI 世界]
```

**Agent 之所以"做不到",不是能力不够,是接口不通**。GUI 是给鼠标和眼睛设计的,Agent 没有眼睛也没有鼠标。

### 现有方案的缺陷

| 方案             | 做法           | 问题                  |
|----------------|--------------|----------------------|
| 屏幕截图 + 视觉模型 | 看截图点坐标     | 慢、脆、不可靠           |
| RPA/UI 自动化    | 模拟点击        | 脆弱,UI 一变就崩        |
| 调用软件的 SDK     | 用官方 API      | 不是所有软件都有,API 碎片化 |

CLI-Anything 的洞察是:**绝大多数软件的底层能力其实都在源代码里,只是被 GUI 层封印了**。

---

## 二、解决方案:把 GUI 软件反向暴露为 CLI

> "CLI-Anything 是一个 Claude Code 插件,能分析任意软件的源代码,自动生成一套生产级的命令行接口(CLI)"

### 核心思路

```
传统路径:  GUI 软件 → 人类点击 → 结果
CLI 路径:  GUI 软件 → CLI 接口 → Agent 调用 → 结果
                              ↑
                   CLI-Anything 自动生成这一层
```

它不是包装 GUI,而是**绕过 GUI,直接调用软件后端能力**。

### 为什么是 CLI 而不是 API?

- **CLI 是 Agent 最自然的接口**:一行文本输入,一行文本输出
- **CLI 无状态、可组合**:`blender render --scene input.blend --output out.png`
- **CLI 跨平台、可远程**:SSH、容器、沙箱都能跑
- **CLI 可自文档化**:`--help` 就是能力声明

### 关键细节:"生产级" CLI

> "可以调用真实的应用后端,包括 LibreOffice 生成真正的 PDF、Blender 渲染真正的 3D 场景"

注意"真正的"三个字——不是模拟,不是截图,是调用软件底层引擎产生的真实输出。这意味着:
- LibreOffice 的 PDF 渲染引擎(不是 reportlab 重新排版)
- Blender 的 Cycles/Eevee 渲染器(不是 matplotlib 画图)
- Audacity/sox 的音频处理(不是 ffmpeg 简单转码)

**Agent 第一次能用到专业软件的真实能力**。

---

## 三、自动化流水线:7 阶段全自动

> "一条命令完成全部工作:`/cli-anything <path-or-repo>`,经过分析→设计→实现→测试→文档→发布的 7 阶段全自动流水线"

### 这条流水线的本质

```
输入:任意软件的源代码仓库
        ↓
[1. 分析]  静态分析代码,识别可暴露的核心能力
        ↓
[2. 设计]  为每个能力设计 CLI 命令签名(参数、选项、子命令)
        ↓
[3. 实现]  生成 Python CLI 包装代码(likely 用 click/typer)
        ↓
[4. 测试]  自动生成测试用例,验证 CLI 真能调用后端
        ↓
[5. 文档]  生成 SKILL.md + 用户文档
        ↓
[6. 发布]  打包为可 pip install 的 Python 包
        ↓
输出:可分发的 CLI 包 + SKILL.md
```

### 关键洞察

这是**元自动化**——用一个 Agent 流程自动化"为软件生成 Agent 接口"这件事本身。它把"让 Agent 能用某个软件"的成本从**人工写 SDK 的几周**降到**一条命令的几分钟**。

---

## 四、SKILL.md:群体智能的协议层

> "每个生成的 CLI 都自带 SKILL.md,一份机器可读的能力描述文件。这意味着 Agent 可以在运行时自动发现其他 Agent 能做什么,动态组建协作关系。"

**这是整段描述中最重要的一句话**。

### SKILL.md 是什么

以 pptx skill 为例,SKILL.md 的典型形态:

```yaml
---
name: pptx
description: "Use this skill any time a .pptx file is involved..."
license: Proprietary
---
# PPTX Skill
## Quick Reference
| Task | Guide |
|------|-------|
| Read/analyze content | python -m markitdown |
| Edit or create from template | editing.md |
| Create from scratch | pptxgenjs.md |
```

**SKILL.md 本质是一份"能力合约"**,包含:
- **能力声明**:这个 skill 能做什么
- **触发条件**:什么场景该用它
- **使用方式**:具体怎么调用
- **元数据**:版本、依赖、许可证

### 为什么这是"群体智能的基础设施"

#### 传统 Agent 协作的问题

```
Agent A: 我需要渲染 3D 场景,谁能做?
Agent B: 我能!
Agent C: 我也能!
Agent A: 你具体能做什么?输入输出是什么?
Agent B: ……(沉默,因为它没有能力描述)
```

**没有能力描述,Agent 之间就无法协商,只能靠人类预先编排**。

#### SKILL.md 解决了什么

```
Agent A: 我需要渲染 3D 场景
        ↓
        扫描周围所有 SKILL.md
        ↓
        发现 Agent B 的 SKILL.md:
        name: blender_render
        capabilities:
          - input: .blend file
          - output: .png/.exr
          - engines: [cycles, eevee]
          - max_resolution: 8K
        ↓
        直接调用 Agent B 的 CLI,无需人类介入
```

**SKILL.md 是 Agent 之间的"名片 + 合同"**,让能力发现从"人类预编排"变成"运行时自动协商"。

#### 类比:互联网的 DNS + HTTP

| 互联网          | Agent 生态                 |
|---------------|---------------------------|
| DNS 域名系统    | SKILL.md 命名规范           |
| HTTP 协议      | CLI 接口约定(stdin/stdout) |
| 网页 meta 标签  | SKILL.md 的 frontmatter    |
| 爬虫发现网页    | Agent 扫描 SKILL.md 发现能力 |
| 超链接连接网页  | Agent 调用 Agent 形成**能力网络** |

**CLI-Anything 在做的,是给 Agent 生态建 DNS+HTTP**。

### 与 DeepAgent 的关系

```
CLI-Anything 生成的每个 CLI 都带 SKILL.md
        ↓
DeepAgent 把这些 CLI 注册为 skill
        ↓
ProgressiveToolRail 通过 search_tools/load_tools 发现和加载
        ↓
LLM 在运行时动态决定调用哪个 skill
```

**CLI-Anything 是 skill 的"工厂",DeepAgent 是 skill 的"消费者"**。两者结合,形成完整的生态闭环。

---

## 五、为什么叫"群体智能的基础设施"

### "群体智能"指什么

群体智能(Swarm Intelligence)的核心特征:
1. **去中心化**:没有中央调度,每个 agent 自主决策
2. **局部感知**:每个 agent 只知道局部信息
3. **简单规则**:个体规则简单,但群体涌现复杂行为
4. **动态协作**:任务来了临时组队,任务结束解散

类比蚁群:单只蚂蚁只会跟着信息素走,但蚁群能找到最短路径。

### Agent 群体智能需要什么基础设施

```
┌─────────────────────────────────────────────┐
│  群体智能的三个前提                          │
├─────────────────────────────────────────────┤
│  1. 个体有能力   ← CLI-Anything 生成的 CLI  │
│  2. 能力可被发现 ← SKILL.md                 │
│  3. 能力可被调用 ← CLI 接口约定              │
└─────────────────────────────────────────────┘
            ↓
  Agent 能在运行时:
  - 发现其他 Agent 的能力(读 SKILL.md)
  - 调用其他 Agent 的能力(执行 CLI)
  - 组建临时协作链(一个 Agent 的输出是另一个的输入)
  - 解散后无需清理(CLI 是无状态的)
```

### 与传统多 Agent 系统的区别

| 传统多 Agent       | CLI-Anything 路线          |
|------------------|---------------------------|
| 中央编排器         | 无中央,自组织               |
| Agent 间用 RPC/消息 | Agent 间用 CLI/管道         |
| 手动定义 Agent 关系 | 运行时动态发现              |
| Agent 紧耦合       | Agent 松耦合(通过 SKILL.md) |
| 加 Agent 要改编排代码 | 加 Agent 只要发布 CLI 包    |

---

## 六、一句话总结

**CLI-Anything 解决的是 Agent 生态的"能力发现"问题——它把任意 GUI 软件反向暴露为带 SKILL.md 的 CLI,让 Agent 能像浏览器爬网页一样,在运行时自动发现和调用彼此的能力。** 这不是某个具体工具,而是群体智能的协议层基础设施,相当于 Agent 互联网的 DNS+HTTP。

# 系统操作工具 (SysOp Tools) 参考文档

> 15 个系统操作工具，
> 分为 **代码执行 (code)**、**文件系统 (fs)**、**Shell 命令 (shell)** 三大类。

---

## 1. 代码执行类 (code)

| 工具 ID | 名称 | 描述 | 参数 | 返回类型 | 实现类 |
|---------|------|------|------|----------|--------|
| `{sysOpId}.code.executeCode` | executeCode | 执行一段代码（Python 或 JavaScript），等待执行完成后返回完整结果（stdout、stderr、退出码） | `code` (string): 要执行的源代码<br>`language` (string): 编程语言，支持 `python` / `javascript`<br>`timeout` (integer): 最大执行时间（秒），默认 300<br>`environment` (object): 自定义环境变量<br>`options` (object): 扩展配置（如 `encoding`、`forceFile`） | `ExecuteCodeResult` — 包含 stdout、stderr、exitCode、执行耗时 | `LocalCodeOperation` |
| `{sysOpId}.code.executeCodeStream` | executeCodeStream | 流式执行代码，实时逐行返回 stdout/stderr 输出，适用于长时间运行的代码或需要实时查看执行过程的场景 | `code` (string): 要执行的源代码<br>`language` (string): 编程语言，支持 `python` / `javascript`<br>`timeout` (integer): 最大执行时间（秒），默认 300<br>`environment` (object): 自定义环境变量<br>`options` (object): 扩展配置 | `Iterator<ExecuteCodeStreamResult>` — 流式迭代器，每个 chunk 包含 delta 文本、类型（stdout/stderr） | `LocalCodeOperation` |

**实现机制**: 使用 `ProcessBuilder` 启动子进程执行代码。长代码自动写入临时文件后执行，支持超时中断和进程清理。

---

## 2. 文件系统类 (fs)

| 工具 ID | 名称 | 描述 | 参数 | 返回类型 | 实现类 |
|---------|------|------|------|----------|--------|
| `{sysOpId}.fs.readFile` | readFile | 读取文件内容，支持文本/二进制模式，可按行范围、头部/尾部行数截取 | `path` (string): 文件路径<br>`mode` (string): 读取模式，`text`（文本）或 `bytes`（二进制）<br>`head` (integer): 从开头读取的行数<br>`tail` (integer): 从末尾读取的行数<br>`lineRange` (array[int]): 行范围 [start, end]（1-indexed，含首尾）<br>`encoding` (string): 字符编码，默认 utf-8<br>`chunkSize` (integer): 单次最大读取字节数（0=不限）<br>`options` (object): 扩展配置 | `ReadFileResult` — 包含文件路径、内容、读取模式 | `LocalFsOperation` |
| `{sysOpId}.fs.readFileStream` | readFileStream | 流式读取文件内容，逐块返回数据，适用于大文件读取场景 | 同 `readFile` 参数 | `Iterator<ReadFileStreamResult>` — 流式迭代器，每个 chunk 包含文本片段 | `LocalFsOperation` |
| `{sysOpId}.fs.writeFile` | writeFile | 写入内容到文件，支持文本/二进制模式，可自动创建文件、设置权限 | `path` (string): 文件路径<br>`content` (object): 写入内容（文本模式为 string，二进制模式为 bytes）<br>`mode` (string): 写入模式，`text` 或 `bytes`<br>`prependNewline` (boolean): 在内容前插入换行<br>`appendNewline` (boolean): 在内容后追加换行<br>`createIfNotExist` (boolean): 文件不存在时自动创建<br>`permissions` (string): 八进制权限码（如 `644`）<br>`encoding` (string): 字符编码<br>`options` (object): 扩展配置 | `WriteFileResult` — 包含写入路径、写入字节数 | `LocalFsOperation` |
| `{sysOpId}.fs.uploadFile` | uploadFile | 将本地文件上传到目标路径，支持覆盖、自动创建父目录、保留权限 | `localPath` (string): 本地源文件路径<br>`targetPath` (string): 目标路径<br>`overwrite` (boolean): 是否覆盖已存在文件<br>`createParentDirs` (boolean): 自动创建父目录<br>`preservePermissions` (boolean): 保留文件权限<br>`chunkSize` (integer): 分块大小<br>`options` (object): 扩展配置 | `UploadFileResult` — 包含源路径、目标路径、文件大小 | `LocalFsOperation` |
| `{sysOpId}.fs.uploadFileStream` | uploadFileStream | 流式上传文件，逐块传输数据，适用于大文件上传 | 同 `uploadFile` 参数 | `Iterator<UploadFileStreamResult>` — 流式迭代器 | `LocalFsOperation` |
| `{sysOpId}.fs.downloadFile` | downloadFile | 从源路径下载文件到本地路径，支持覆盖、自动创建父目录 | `sourcePath` (string): 源文件路径<br>`localPath` (string): 本地目标路径<br>`overwrite` (boolean): 是否覆盖<br>`createParentDirs` (boolean): 自动创建父目录<br>`preservePermissions` (boolean): 保留权限<br>`chunkSize` (integer): 分块大小<br>`options` (object): 扩展配置 | `DownloadFileResult` — 包含源路径、本地路径、文件大小 | `LocalFsOperation` |
| `{sysOpId}.fs.downloadFileStream` | downloadFileStream | 流式下载文件，逐块接收数据，适用于大文件下载 | 同 `downloadFile` 参数 | `Iterator<DownloadFileStreamResult>` — 流式迭代器 | `LocalFsOperation` |
| `{sysOpId}.fs.listFiles` | listFiles | 列出指定路径下的文件，支持递归、深度限制、排序和类型过滤 | `path` (string): 目录路径<br>`recursive` (boolean): 是否递归遍历<br>`maxDepth` (integer): 最大递归深度<br>`sortBy` (string): 排序字段（name/size/time）<br>`sortDescending` (boolean): 是否降序<br>`fileTypes` (array[string]): 文件类型过滤（如 `["py","java"]`）<br>`options` (object): 扩展配置 | `ListFilesResult` — 包含文件列表（名称、大小、修改时间等） | `LocalFsOperation` |
| `{sysOpId}.fs.listDirectories` | listDirectories | 列出指定路径下的子目录，支持递归、深度限制和排序 | `path` (string): 目录路径<br>`recursive` (boolean): 是否递归<br>`maxDepth` (integer): 最大递归深度<br>`sortBy` (string): 排序字段<br>`sortDescending` (boolean): 是否降序<br>`options` (object): 扩展配置 | `ListDirsResult` — 包含目录列表 | `LocalFsOperation` |
| `{sysOpId}.fs.searchFiles` | searchFiles | 在指定路径下按 glob 模式搜索文件，支持排除模式 | `path` (string): 搜索根路径<br>`pattern` (string): glob 匹配模式（如 `*.java`）<br>`excludePatterns` (array[string]): 排除模式列表 | `SearchFilesResult` — 包含匹配文件列表 | `LocalFsOperation` |

**实现机制**: 使用 Java NIO (`java.nio.file`) 实现所有文件操作。路径通过 `resolvePath()` 解析，支持沙箱限制（`restrictToSandbox=true` 时限制在工作目录内）。

---

## 3. Shell 命令类 (shell)

| 工具 ID | 名称 | 描述 | 参数 | 返回类型 | 实现类 |
|---------|------|------|------|----------|--------|
| `{sysOpId}.shell.executeCmd` | executeCmd | 执行 Shell 命令并等待完成，返回 stdout、stderr 和退出码。内置危险命令检测和白名单过滤 | `command` (string): 要执行的 Shell 命令<br>`cwd` (string): 工作目录，默认当前目录<br>`timeout` (integer): 执行超时（秒），默认 300<br>`environment` (object): 自定义环境变量<br>`options` (object): 扩展配置（如 `encoding`、`shellType`） | `ExecuteCmdResult` — 包含 stdout、stderr、exitCode、执行耗时 | `LocalShellOperation` |
| `{sysOpId}.shell.executeCmdStream` | executeCmdStream | 流式执行 Shell 命令，实时逐行返回 stdout/stderr 输出，适用于长时间运行的命令 | `command` (string): 要执行的 Shell 命令<br>`cwd` (string): 工作目录<br>`timeout` (integer): 执行超时（秒），默认 300<br>`environment` (object): 自定义环境变量<br>`options` (object): 扩展配置 | `Iterator<ExecuteCmdStreamResult>` — 流式迭代器，每个 chunk 包含 delta 文本、类型 | `LocalShellOperation` |
| `{sysOpId}.shell.executeCmdBackground` | executeCmdBackground | 在后台执行 Shell 命令，立即返回进程 PID，不等待命令完成。支持优雅停机（grace 参数） | `command` (string): 要执行的 Shell 命令<br>`cwd` (string): 工作目录<br>`environment` (object): 自定义环境变量<br>`grace` (number): 优雅停机等待时间（秒），用于进程终止时给予缓冲<br>`options` (object): 扩展配置 | `ExecuteCmdBackgroundResult` — 包含 PID、启动状态 | `LocalShellOperation` |

**实现机制**: 使用 `ProcessBuilder` 启动 Shell 子进程。自动检测系统 Shell 类型（bash/sh/cmd），内置危险命令模式检测（如 `rm -rf /`）和白名单过滤。超时后强制终止进程。

---

## 4. 工具 ID 生成规则

工具 ID 格式为 `{sysOpId}.{category}.{methodName}`，其中：

- **sysOpId**: 由 `AgentCard.name + "_" + AgentCard.id` 组成，例如 `deep_agent_a2a_server_deep_agent_a2a_server`
- **category**: 工具类别，`code` / `fs` / `shell`
- **methodName**: 工具方法名，如 `executeCode`、`readFile`、`executeCmd`

示例完整 ID：`deep_agent_a2a_server_deep_agent_a2a_server.shell.executeCmd`

---

## 5. 注入流程（以下为示例，用户可以指定注入）

```
createDeepAgent()
  ├── createSysOperation(sysOpId, workDir)     → 注册 SysOperation 到 ResourceMgr
  │     └── Runner.resourceMgr().addSysOperation(card, tag)
  │     └── 自动注册 15 个 ToolCard 到 ResourceMgr
  ├── HarnessFactory.createDeepAgent(card, config, workspace)
  └── injectSysOpTools(deepAgent, sysOpId)     → 手动注入 ToolCard 到 AbilityManager
        └── Runner.resourceMgr().getSysOpToolCards(sysOpId, null, null)
        └── deepAgent.getAgent().getAbilityManager().add(toolCard)
```


---

## 6. 安全机制

| 机制 | 说明 |
|------|------|
| **危险命令检测** | Shell 工具内置危险模式匹配（如 `rm -rf /`、`mkfs`、`dd` 等），检测到时拒绝执行 |
| **白名单过滤** | 可配置 Shell 命令白名单，仅允许执行白名单内的命令 |
| **沙箱限制** | `restrictToSandbox=true` 时，文件操作限制在工作目录内，禁止访问外部路径 |
| **超时保护** | 所有执行类工具默认 300 秒超时，超时后强制终止子进程 |
| **工作目录隔离** | `LocalWorkConfig.workDir` 指定工作目录，`restrictToWorkDir` 控制是否限制路径 |

---


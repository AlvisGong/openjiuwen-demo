# OpenSandbox 适配方案

> 基于当前 JiuwenBox 沙箱调用链和阿里 OpenSandbox API 规范的完整分析，制定适配方案。
>
> 分析时间: 2026-09-14

---

## 一、当前架构回顾

当前沙箱调用链分三层：

```
ReActAgent (工具层)
  → DecoratingSandboxClient (治理装饰层: timeout/retry/circuitBreaker/audit)
    → Core SandboxClient (agent-core-java, JiuwenBox provider)
      → HTTP POST http://127.0.0.1:8321/api/v1/sandboxes/.../execute
        → JiuwenBox Docker (bubblewrap 隔离)
```

关键分层：

| 层 | 模块 | 职责 |
|---|---|---|
| **Runtime 层** | `agent-service-adapters-agentcore` | 配置映射 + 治理装饰，不实现后端协议。`DefaultAgentCoreSandboxClientFactory` 把 YAML 配置映射为 Core 的 `SandboxGatewayConfig`/`SandboxLauncherConfig`/`SandboxIsolationConfig`，然后用 `new SandboxClient(config)` 创建 Core 客户端，再包上 `DecoratingSandboxClient`。 |
| **Core 层** | `agent-core-java` | 根据 `sandboxType` 选择 provider，负责实际 HTTP 调用到 JiuwenBox 的 `/api/v1/sandboxes` 端点。 |
| **后端层** | JiuwenBox Docker | FastAPI Server + bubblewrap 进程隔离。 |

### 关键源码文件

| 文件 | 角色 |
|---|---|
| `SandboxDemoApplication.java` | 启动入口, `@Bean agentHandler` |
| `DecoratedSandboxToolRegistrar.java` | 注册 3 个 Sandbox 工具 (readFile/executeCmd/executeCode) 到 ReActAgent |
| `DecoratingSandboxClient.java` | 治理装饰器: 为每个操作套用 `ExternalCallExecutor` |
| `DefaultAgentCoreSandboxClientFactory.java` | 创建 `DecoratingSandboxClient`，配置映射为 Core Config |
| `AgentCoreSandboxClientFactory.java` | 工厂 SPI 接口 |
| `ExternalCallExecutor.java` | 外部调用治理: timeout/retry/circuitBreaker/audit |
| `AgentCoreAdaptersAutoConfiguration.java` | Spring 自动装配 SandboxFactory |
| `application-sandbox.yml` | Sandbox 配置文件 |

### 当前配置 (application-sandbox.yml)

```yaml
openjiuwen:
  service:
    external:
      sandbox:
        enabled: true
        timeout-ms: 30000
        retry: {max: 1, backoff-ms: 200}
        circuit-breaker: {enabled: true, failure-threshold: 3, reset-timeout-ms: 30000}
        audit: {enabled: true}
        servers:
          - server-id: default
            service-url: http://127.0.0.1:8321
            sandbox-type: jiuwenbox
            launcher-type: pre_deploy
            on-stop: delete
            root-path: .
```

### DecoratingSandboxClient 装饰器模式

```
DecoratingSandboxClient (最外层, 治理装饰器)
|  职责: 为每个操作套用 ExternalCallExecutor (timeout/retry/circuitBreaker/audit)
|
+-- fsOperation: DecoratingSandboxFsOperation
|   |  -> executor.execute("fs", "readFile", true, () -> delegate.readFile(...))
|   |  -> executor.execute("fs", "writeFile", false, () -> delegate.writeFile(...))
|   |  -> executor.execute("fs", "listFiles", true, () -> delegate.listFiles(...))
|   |
|   +-- delegate: Core SandboxFsOperation (agent-core-java, JiuwenBox provider)
|
+-- shellOperation: DecoratingSandboxShellOperation
|   |  -> executor.execute("shell", "executeCmd", false, () -> delegate.executeCmd(...))
|   |  -> executor.execute("shell", "executeCmdBackground", false, ...)
|   |
|   +-- delegate: Core SandboxShellOperation (agent-core-java, JiuwenBox provider)
|
+-- codeOperation: DecoratingSandboxCodeOperation
|   |  -> executor.execute("code", "executeCode", false, () -> delegate.executeCode(...))
|   |
|   +-- delegate: Core SandboxCodeOperation (agent-core-java, JiuwenBox provider)
|
+-- executor: ExternalCallExecutor
    -> adapterType = "Sandbox"
    -> targetId = "default"
    -> policy = SandboxPolicy (timeout/retry/circuitBreaker/audit)
```

---

## 二、OpenSandbox 与 JiuwenBox 的关键差异

| 维度 | JiuwenBox | OpenSandbox |
|---|---|---|
| **架构** | 单体 FastAPI Server (8321 端口) | 三组件分离: Lifecycle Server (8080) + execd (沙箱内) + egress (沙箱内) |
| **沙箱生命周期** | 隐式创建，provider 内部管理 | 显式 `POST /v1/sandboxes` 创建，返回 sandboxId；支持 pause/resume/snapshot |
| **认证** | 无认证或自定义 | `OPEN-SANDBOX-API-KEY` Header (Lifecycle) + `X-EXECD-ACCESS-TOKEN` (execd) |
| **命令执行** | `POST /api/v1/sandboxes/{id}/execute`，同步返回 | `POST /command`，SSE 流式输出；支持前台/后台模式 |
| **代码执行** | `POST /api/v1/sandboxes/{id}/execute_code`，同步返回 | `POST /code/context` 创建上下文 + `POST /code` 流式执行 |
| **文件操作** | `POST /api/v1/sandboxes/{id}/read_file` 等 | `GET/POST /files/*` + `GET/POST /directories/*` RESTful 风格 |
| **沙箱寻址** | provider 直接用 gatewayUrl | 先调 Lifecycle API 创建 sandbox → 获取 execd endpoint → 再调 execd API |
| **两步寻址** | 不需要 | 需要: Lifecycle Server 管理生命周期，execd 在沙箱容器内执行操作 |
| **隔离技术** | bubblewrap (进程级) | Docker / gVisor / Kata / Firecracker (容器/微虚机级) |
| **流式输出** | 不支持 | SSE (Server-Sent Events) 标准流式 |
| **凭证安全** | 环境变量直接传入 | Credential Vault: 沙箱内假 Key，egress sidecar 出站注入真 Key |
| **网络策略** | 可选 namespace 隔离 | egress 白名单 + Credential Vault 绑定 |
| **沙箱状态** | 无显式状态 | Pending → Running → Pausing → Paused → Stopping → Terminated |
| **资源限制** | CPU/内存/进程数上限 | Kubernetes 风格: cpu/memory/gpu |
| **Java SDK** | 无 | 有: `com.alibaba.opensandbox:sandbox` |

---

## 三、适配方案

适配的核心挑战在于：**OpenSandbox 的三组件分离架构和两步寻址机制与当前 Core SandboxClient 的单端点模型不兼容**。

### 3.1 整体架构

```
                           适配范围
                    ┌──────────────────────────────┐
                    │   agent-core-java 层          │
                    │   (新增 OpenSandbox           │
                    │    Provider)                  │
                    └───────────┬──────────────────┘
                                │
  Runtime 层 (不改动)            │           后端层 (部署 OpenSandbox)
  ─────────────────────         │           ─────────────────────
  DecoratingSandboxClient       │           OpenSandbox Lifecycle Server
  ExternalCallExecutor          │           + execd (沙箱内)
  DefaultAgentCoreSandboxFactory│           + egress sidecar
  SandboxGatewayConfig ─────────┘
  (sandbox-type=opensandbox)
```

### 3.2 Runtime 层 — 仅需配置适配，不改代码

Runtime 层的设计已经足够抽象，`sandbox-type` 只是一个传递给 Core 的字符串标识，Runtime 不注册 provider。

`DefaultAgentCoreSandboxClientFactory.toCoreConfig()` 已经会将以下字段全部写入 Core 配置：
- `server.getSandboxType()` → `SandboxLauncherConfig.sandboxType`
- `server.getExtraParams()` → `SandboxLauncherConfig.extraParams`
- `server.getParams()` → `SandboxGatewayConfig.params`
- `server.getServiceUrl()` → `SandboxGatewayConfig.gatewayUrl` / `SandboxLauncherConfig.gatewayUrl` / `baseUrl`
- `auth` 信息通过 `PreparedOutboundSecurity.injectParams(params)` 注入到 `SandboxGatewayConfig.params`

因此 Runtime 层不需要改代码，只需要改配置值。

#### 配置变更 (application-sandbox.yml)

```yaml
openjiuwen:
  service:
    external:
      sandbox:
        enabled: true
        timeout-ms: 30000
        retry:
          max: 1
          backoff-ms: 200
        circuit-breaker:
          enabled: true
          failure-threshold: 3
          reset-timeout-ms: 30000
        audit:
          enabled: true
        servers:
          - server-id: default
            service-url: http://127.0.0.1:8080      # ← 改为 OpenSandbox Lifecycle Server 端口
            sandbox-type: opensandbox                # ← 改为 opensandbox
            launcher-type: external                  # ← OpenSandbox 是外部托管，非 pre_deploy
            on-stop: delete
            root-path: /workspace
            auth:
              type: apikey
              token: ${OPEN_SANDBOX_API_KEY}         # ← OpenSandbox API Key
            extra-params:
              image: opensandbox/code-interpreter:v1.1.0  # ← 指定沙箱镜像
              execd-port: "44772"                      # ← execd 默认端口
              cpu: "500m"
              memory: "512Mi"
```

### 3.3 Core 层 — 新增 OpenSandbox Provider（主要工作量）

这是适配的核心。需要在 `agent-core-java` 中注册一个新的 `sandbox-type=opensandbox` provider，实现 `SandboxFsOperation`、`SandboxShellOperation`、`SandboxCodeOperation` 三个接口。

#### 3.3.1 两步寻址机制

OpenSandbox 与 JiuwenBox 最大的区别是需要两步寻址：

```
步骤1: Lifecycle API (8080端口)
  POST /v1/sandboxes → 返回 {sandboxId, execd endpoint}

步骤2: execd API (execd端口，在沙箱容器内)
  POST /command → 执行命令 (SSE)
  POST /code → 执行代码 (SSE)
  GET/POST /files/* → 文件操作
```

Provider 需要内部管理这个两步寻址：

```
OpenSandboxProvider (新建)
├── SandboxLifecycleManager
│   ├── createSandbox()     → POST /v1/sandboxes (返回 sandboxId + execd endpoint)
│   ├── getSandbox()        → GET /v1/sandboxes/{id}
│   ├── deleteSandbox()     → DELETE /v1/sandboxes/{id}
│   ├── pauseSandbox()      → POST /v1/sandboxes/{id}/pause
│   ├── resumeSandbox()     → POST /v1/sandboxes/{id}/resume
│   └── renewExpiration()   → POST /v1/sandboxes/{id}/renew-expiration
│
├── OpenSandboxFsOperation (implements SandboxFsOperation)
│   ├── readFile()          → GET /files/download?path=...
│   ├── writeFile()         → POST /files/upload (multipart)
│   ├── listFiles()         → GET /directories/list?path=...
│   ├── searchFiles()       → GET /files/search?pattern=...
│   └── ... (映射到 /files/* 和 /directories/* 端点)
│
├── OpenSandboxShellOperation (implements SandboxShellOperation)
│   ├── executeCmd()        → POST /command (SSE → 收集 stdout/stderr → 同步返回)
│   ├── executeCmdStream()  → POST /command (SSE → 透传 Iterator)
│   └── executeCmdBackground() → POST /command (后台模式) + GET /command/status/{id}
│
└── OpenSandboxCodeOperation (implements SandboxCodeOperation)
    ├── executeCode()       → POST /code/context + POST /code (SSE → 同步返回)
    ├── executeCodeStream() → POST /code/context + POST /code (SSE → 透传 Iterator)
    └── (上下文管理: GET/DELETE /code/contexts)
```

#### 3.3.2 认证适配

OpenSandbox 有两层认证：

| 层 | Header | 来源 |
|---|---|---|
| Lifecycle API | `OPEN-SANDBOX-API-KEY` | YAML 配置 `auth.token` |
| execd API | `X-EXECD-ACCESS-TOKEN` | 创建沙箱时 Lifecycle API 返回 |

Provider 需要在 `createSandbox()` 时保存返回的 execd access token，后续所有 execd 调用都带上这个 Header。

当前 Runtime 的 `ExternalOutboundSecuritySupport` 已经支持 `auth.type=apikey` + `auth.token`，会通过 `PreparedOutboundSecurity.injectParams(params)` 注入到 `SandboxGatewayConfig.params` 中，Provider 可以从 params 中取出。

#### 3.3.3 SSE 流式响应适配

OpenSandbox 的命令执行和代码执行都使用 SSE 流式输出，而当前 Core 的 `SandboxShellOperation.executeCmd()` 返回同步的 `ExecuteCmdResult`。Provider 需要：

- **同步模式**：消费完整 SSE 流，将 `stdout`/`stderr` 事件聚合为字符串，等 `execution_complete` 事件后组装 `ExecuteCmdResult` 返回
- **流式模式**：将 SSE 事件转为 `Iterator<ExecuteCmdStreamResult>`，透传给上层

SSE 事件类型映射：

| OpenSandbox SSE 事件 | Core Result 字段 |
|---|---|
| `init` | 初始化信息 (流式模式透传) |
| `stdout` | `ExecuteCmdResult.stdout` |
| `stderr` | `ExecuteCmdResult.stderr` |
| `execution_complete` | `ExecuteCmdResult.exitCode` |
| `error` | `ExecuteCmdResult.error` |
| `status` | 进度信息 (流式模式透传) |
| `result` | 代码执行结果 (ExecuteCodeResult) |

#### 3.3.4 沙箱生命周期管理

JiuwenBox 的 `launcher-type=pre_deploy` 意味着沙箱在 provider 初始化时已部署好。OpenSandbox 需要显式创建沙箱实例。

**方案A（推荐）：懒创建 + 会话亲和**
- Provider 首次收到操作请求时，调用 `POST /v1/sandboxes` 创建沙箱
- 缓存 sandboxId 和 execd endpoint
- 利用 OpenSandbox 的 `pause`/`resume` 实现会话亲和：Agent 对话暂停时 pause，恢复时 resume
- `on-stop=delete` 时在 Provider close 时调 `DELETE /v1/sandboxes/{id}`

**方案B：预创建池**
- Provider 初始化时预创建一批沙箱
- 从池中分配，用完归还或销毁
- 适合高并发场景，但增加复杂度

建议先用方案A，因为 Runtime 的 `containerScope=SESSION` 已经表达了会话级隔离的意图。

#### 3.3.5 镜像与资源规格映射

OpenSandbox 创建沙箱时需要指定镜像和资源限制。通过 `extraParams` 传递：

| Runtime extraParams | OpenSandbox POST /v1/sandboxes Body |
|---|---|
| `image` | `image` 字段 |
| `cpu` | `resources.cpu` |
| `memory` | `resources.memory` |
| `gpu` | `resources.gpu` |
| `timeout` | `timeout` (ISO 8601 duration) |
| `env` | `env` 字段 |
| `entrypoint` | `entrypoint` 字段 |

### 3.4 文件操作接口映射

| Core SandboxFsOperation 方法 | OpenSandbox execd 端点 | 备注 |
|---|---|---|
| `readFile(path, mode, head, tail, lineRange, encoding, chunkSize, opts)` | `GET /files/download?path={path}` | 支持 range/head/tail 参数 |
| `readFileStream(path, ...)` | `GET /files/download?path={path}` | 支持 range 请求，转 Iterator |
| `writeFile(path, content, mode, ...)` | `POST /files/upload` (multipart) | |
| `listFiles(path, recurse, maxDepth, ...)` | `GET /directories/list?path={path}&depth={maxDepth}` | |
| `listDirectories(path, recurse, maxDepth, ...)` | `GET /directories/list?path={path}&only_dirs=true` | |
| `searchFiles(path, pattern, excludePatterns)` | `GET /files/search?pattern={pattern}` | 支持 glob |
| `uploadFile(localPath, targetPath, ...)` | `POST /files/upload` | |
| `uploadFileStream(localPath, targetPath, ...)` | `POST /files/upload` | |
| `downloadFile(sourcePath, localPath, ...)` | `GET /files/download?path={sourcePath}` | |
| `downloadFileStream(sourcePath, localPath, ...)` | `GET /files/download?path={sourcePath}` | |

### 3.5 命令执行接口映射

| Core SandboxShellOperation 方法 | OpenSandbox execd 端点 | 备注 |
|---|---|---|
| `executeCmd(cmd, cwd, timeout, env, opts)` | `POST /command` | SSE 消费 → 同步返回 |
| `executeCmdStream(cmd, cwd, ...)` | `POST /command` | SSE → Iterator 透传 |
| `executeCmdBackground(cmd, cwd, ...)` | `POST /command` (后台模式) | + `GET /command/status/{id}` 轮询 |

OpenSandbox `POST /command` 请求体映射：

```json
{
  "command": "{cmd}",
  "cwd": "{cwd}",
  "timeout": {timeout},
  "env": {env},
  "background": false
}
```

### 3.6 代码执行接口映射

OpenSandbox 的代码执行是有状态的（先创建 context，再在 context 中执行），与 Core 的无状态 `executeCode` 模型不同。

**适配策略**：每次 `executeCode` 调用时：
1. `POST /code/context` 创建临时上下文
2. `POST /code` 在该上下文中执行代码（消费 SSE）
3. `DELETE /code/contexts/{context_id}` 清理上下文

或者优化为：复用上下文（按 language 维度缓存 context），减少创建开销。

| Core SandboxCodeOperation 方法 | OpenSandbox execd 端点 | 备注 |
|---|---|---|
| `executeCode(code, language, timeout, env, opts)` | `POST /code/context` + `POST /code` + `DELETE /code/contexts/{id}` | SSE 消费 → 同步返回 |
| `executeCodeStream(code, language, ...)` | `POST /code/context` + `POST /code` | SSE → Iterator 透传 |

---

## 四、调用链对比

### 当前 JiuwenBox 调用链

```
POST /v1/query
→ ReActAgent → LLM 选择 executeCmd
→ DecoratingSandboxClient.shell().executeCmd()
→ ExternalCallExecutor.execute("shell","executeCmd", ...)
→ Core SandboxClient (jiuwenbox provider)
→ HTTP POST 8321/api/v1/sandboxes/{id}/execute
→ JiuwenBox: bubblewrap 执行
← {exit_code, stdout, stderr}
```

### 适配后 OpenSandbox 调用链

```
POST /v1/query
→ ReActAgent → LLM 选择 executeCmd
→ DecoratingSandboxClient.shell().executeCmd()              ← 不变
→ ExternalCallExecutor.execute("shell","executeCmd", ...)   ← 不变
→ Core SandboxClient (opensandbox provider)                 ← 新 provider
→ [首次] POST 8080/v1/sandboxes → 获取 sandboxId + execd endpoint
→ POST {execd_endpoint}/command (SSE)                       ← 新协议
→ OpenSandbox: Docker/gVisor 执行
← SSE: stdout/stderr/execution_complete
← 组装为 ExecuteCmdResult
```

---

## 五、不改动代码的确认

以下代码**不需要修改**：

| 文件 | 原因 |
|---|---|
| `DecoratingSandboxClient.java` | 装饰器与后端无关，只做治理包装 |
| `DefaultAgentCoreSandboxClientFactory.java` | `toCoreConfig` 已把 `sandboxType`/`extraParams`/`auth` 全部透传给 Core |
| `AgentCoreExternalProperties.java` | `SandboxServer` 的字段足够覆盖 OpenSandbox 配置需求 |
| `ExternalCallExecutor.java` | 治理逻辑与后端无关 |
| `DecoratedSandboxToolRegistrar.java` | 工具注册逻辑调用 `client.shell().executeCmd()` 等，与后端无关 |
| `SandboxDemoApplication.java` | 启动入口与后端无关 |
| `AgentCoreAdaptersAutoConfiguration.java` | 自动装配逻辑不变 |
| `application-sandbox.yml` | 只需改配置值，不需要改结构 |

---

## 六、实施路径

```
阶段1: 部署 OpenSandbox
├── Docker 环境准备
├── uvx opensandbox-server init-config ~/.sandbox.toml --example docker
├── uvx opensandbox-server (启动 8080 端口)
└── curl http://127.0.0.1:8080/health 验证

阶段2: Core 层新增 OpenSandbox Provider (agent-core-java)
├── 实现 OpenSandboxSandboxClient (extends SandboxClient)
│   ├── 两步寻址: Lifecycle → execd
│   ├── 沙箱生命周期管理 (create/pause/resume/delete)
│   └── 认证管理 (API Key + execd token)
├── 实现 OpenSandboxFsOperation (extends SandboxFsOperation)
│   └── 映射到 /files/* 和 /directories/* 端点
├── 实现 OpenSandboxShellOperation (extends SandboxShellOperation)
│   └── 映射到 /command 端点 + SSE 消费
├── 实现 OpenSandboxCodeOperation (extends SandboxCodeOperation)
│   └── 映射到 /code/context + /code 端点 + SSE 消费
├── 注册 Provider (sandbox-type=opensandbox)
└── 单元测试 + 集成测试

阶段3: Runtime 配置适配
├── 修改 application-sandbox.yml (sandbox-type, service-url, auth, extra-params)
├── 验证启动链路: AutoConfiguration → Factory → DecoratingSandboxClient
└── 端到端验证: POST /v1/query → executeCmd → OpenSandbox

阶段4: 高级特性适配 (可选)
├── Credential Vault 集成 (egress sidecar 凭证代理)
├── 沙箱 pause/resume 与 Agent 会话亲和
├── SSE 流式输出透传到 executeCmdStream/executeCodeStream
└── 资源限制 (CPU/memory) 通过 extra-params 配置
```

---

## 七、风险与注意事项

1. **SSE 超时控制**：OpenSandbox 的 SSE 流可能长时间运行，`ExternalCallExecutor` 的 `timeout-ms` 会中断整个调用。需要在 Provider 内部将 SSE 消费与超时绑定，或使用 `executeCmdStream` 的流式模式绕过同步超时。

2. **两步寻址的事务性**：如果 `POST /v1/sandboxes` 成功但后续 execd 调用失败，需要确保沙箱实例被正确清理。Provider 应实现 try-finally 清理逻辑。

3. **execd 端口动态分配**：OpenSandbox 的 execd 端口是在沙箱创建时由 Lifecycle Server 返回的，不能硬编码。Provider 必须从 `POST /v1/sandboxes` 的响应中解析 execd endpoint。

4. **状态映射差异**：OpenSandbox 有 `Pending → Running → Pausing → Paused → Stopping → Terminated` 等状态，而当前 Core SandboxClient 没有显式的沙箱状态管理。Provider 需要在内部处理状态转换。

5. **OpenSandbox Java SDK 可选**：OpenSandbox 已提供 Java/Kotlin SDK（Maven 坐标: `com.alibaba.opensandbox:sandbox`），可以直接使用 SDK 而非手写 HTTP 调用，减少适配工作量。

6. **沙箱预热**：OpenSandbox 冷启动约 130 秒（拉取镜像 + 启动容器），首次操作延迟较高。建议在 Provider 初始化时预创建沙箱，或使用 OpenSandbox 的沙箱池预热功能。

7. **Credential Vault 与 Istio 冲突**：OpenSandbox 的 Credential Vault 依赖 egress sidecar 的透明出站拦截，如果 sandbox pod 同时注入 Istio/Envoy 这类透明 service mesh sidecar，两层拦截会冲突。

---

## 八、OpenSandbox API 速查参考

### Lifecycle API (base path /v1, port 8080)

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/sandboxes` | 创建沙箱 (image/snapshot + resource limits) |
| GET | `/sandboxes` | 列出沙箱 (state/metadata 过滤 + 分页) |
| GET | `/sandboxes/{sandboxId}` | 获取沙箱详情 |
| DELETE | `/sandboxes/{sandboxId}` | 删除沙箱 |
| POST | `/sandboxes/{sandboxId}/pause` | 暂停沙箱 (异步) |
| POST | `/sandboxes/{sandboxId}/resume` | 恢复沙箱 |
| POST | `/sandboxes/{sandboxId}/renew-expiration` | 续期沙箱 TTL |
| PATCH | `/sandboxes/{sandboxId}/metadata` | 修改元数据 |
| GET | `/sandboxes/{sandboxId}/endpoints/{port}` | 获取端口访问端点 |
| POST | `/sandboxes/{sandboxId}/snapshots` | 创建快照 |
| GET | `/snapshots` | 列出快照 |
| GET | `/snapshots/{snapshotId}` | 获取快照详情 |
| DELETE | `/snapshots/{snapshotId}` | 删除快照 |

认证: `OPEN-SANDBOX-API-KEY: your-api-key`

### execd API (port 44772, 沙箱内)

| 方法 | 端点 | 说明 |
|---|---|---|
| GET | `/ping` | 健康检查 |
| POST | `/code/context` | 创建代码执行上下文 |
| POST | `/code` | 执行代码 (SSE) |
| DELETE | `/code` | 中断代码执行 |
| GET | `/code/contexts` | 列出代码执行上下文 |
| DELETE | `/code/contexts/{context_id}` | 删除代码执行上下文 |
| POST | `/command` | 执行 shell 命令 (SSE) |
| DELETE | `/command` | 中断命令执行 |
| GET | `/command/status/{id}` | 获取命令状态 |
| GET | `/command/{id}/logs` | 获取后台命令日志 |
| POST | `/session` | 创建 bash 会话 |
| POST | `/session/{sessionId}/run` | 在 bash 会话中执行命令 (SSE) |
| DELETE | `/session/{sessionId}` | 删除 bash 会话 |
| GET | `/files/info` | 获取文件元数据 |
| DELETE | `/files` | 删除文件 |
| POST | `/files/permissions` | 修改文件权限 |
| POST | `/files/mv` | 移动/重命名文件 |
| GET | `/files/search` | 搜索文件 (glob) |
| POST | `/files/replace` | 批量替换文件内容 |
| POST | `/files/upload` | 上传文件 (multipart) |
| GET | `/files/download` | 下载文件 (支持 range) |
| GET | `/directories/list` | 列出目录内容 |
| POST | `/directories` | 创建目录 |
| DELETE | `/directories` | 递归删除目录 |
| GET | `/metrics` | 获取系统资源指标 |
| GET | `/metrics/watch` | 实时监控指标 (SSE) |

认证: `X-EXECD-ACCESS-TOKEN: token`

### egress API (port 18080, 沙箱内)

| 方法 | 端点 | 说明 |
|---|---|---|
| GET | `/policy` | 获取当前 egress 策略 |
| PATCH | `/policy` | 合并新 egress 规则 |
| DELETE | `/policy` | 删除特定 egress 规则 |

---

## 九、端到端调用链分析

以 `POST /v1/query {"message":"echo hello"}` 为例，追踪从 HTTP 请求到 OpenSandbox 沙箱执行的完整调用栈，标注每一跳的源码位置。

### 9.1 完整调用栈

```
POST /v1/query {"conversation_id":"sb-osb","message":"echo hello","stream":false}
│
├─ [第1跳] QueryMvcController.queryV1(rawBody, headers, servletRequest, response)
│   解析请求体为 QueryRequest, 调用 orchestrator.query(serveRequest)
│
├─ [第2跳] A2AEnabledServeOrchestrator.query(serveRequest)
│   调用 agentHandler.query(current)
│
├─ [第3跳] JiuwenCoreAgentHandler.query(serveRequest)
│   文件: JiuwenCoreAgentHandler.java
│   → FutureTask(() -> {
│       +-- Runner.runAgent(agent, inputs, "sb-osb", null)
│       |   +-- Checkpointer.load("sb-osb") -> 空 (首次)
│       |   +-- ReActAgent.invoke(inputs, session)
│       |       |
│       |       |  -- ReAct Iteration 1/5 --
│       |       |  [Reason] LLM 推理:
│       |       |    可用工具: [readFile, executeCmd, executeCode]
│       |       |    → LLM 输出: tool_call(executeCmd, {command:"echo hello"})
│       |       |
│       |       |  [Act] 执行 executeCmd 工具:
│       |       |    DecoratedSandboxToolRegistrar lambda:
│       |       |    文件: DecoratedSandboxToolRegistrar.java#L107
│       |       |    |
│       |       |    +-- client.shell().executeCmd("echo hello", ".", 0, {}, {})
│       |       |        |
│       |       |        |  client 是 DecoratingSandboxClient
│       |       |        |  shell() 返回 DecoratingSandboxShellOperation
│       |       |        |
│       |       |        +-- [第7跳] executor.execute("shell", "executeCmd", false, () -> ...)
│       |       |            文件: DecoratingSandboxClient.java#L185
│       |       |            文件: ExternalCallExecutor.java#L89
│       |       |            |
│       |       |            +-- [第8跳] ExternalCallExecutor 治理层:
│       |       |            |   +-- circuitKey = "shell.executeCmd"
│       |       |            |   +-- ensureCircuitClosed()  → 无熔断
│       |       |            |   +-- callWithTimeout(callable, "shell", "executeCmd")
│       |       |            |       +-- future = timeoutExecutor.submit(callable)
│       |       |            |       |   → callable 执行:
│       |       |            |       |     delegate.executeCmd("echo hello", ".", 30, {}, {})
│       |       |            |       |
│       |       |            |       |  ════════════════════════════════════════
│       |       |            |       |  ↑ Runtime 层结束 / Core 层开始 ↑
│       |       |            |       |  ↓ 以下为新增的 OpenSandbox Provider ↓
│       |       |            |       |  ════════════════════════════════════════
│       |       |            |       |
│       |       |            |       |  [第9跳] OpenSandboxShellOperation.executeCmd()
│       |       |            |       |   |
│       |       |            |       |   +-- 9a. 确保沙箱已创建 (懒创建)
│       |       |            |       |   |   lifecycleManager.ensureSandbox()
│       |       |            |       |   |   → [首次] POST http://127.0.0.1:8080/v1/sandboxes
│       |       |            |       |   |     Headers: OPEN-SANDBOX-API-KEY: {api_key}
│       |       |            |       |   |     Body: {image:"opensandbox/code-interpreter:v1.1.0",
│       |       |            |       |   |            timeout:"PT30M", resources:{cpu:"500m",memory:"512Mi"}}
│       |       |            |       |   |     ← Response: {sandboxId:"sbx-abc123",
│       |       |            |       |   |                  execdEndpoint:"http://127.0.0.1:44772",
│       |       |            |       |   |                  execdAccessToken:"token-xyz"}
│       |       |            |       |   |   → [后续] 复用已缓存的 handle
│       |       |            |       |   |
│       |       |            |       |   +-- 9b. 调用 execd API 执行命令
│       |       |            |       |   |   POST http://127.0.0.1:44772/command
│       |       |            |       |   |   Headers: X-EXECD-ACCESS-TOKEN: token-xyz
│       |       |            |       |   |   Body: {command:"echo hello", cwd:".", timeout:30, background:false}
│       |       |            |       |   |   ← SSE Stream:
│       |       |            |       |   |     event: stdout
│       |       |            |       |   |     data: "hello\n"
│       |       |            |       |   |     event: execution_complete
│       |       |            |       |   |     data: {"exitCode": 0}
│       |       |            |       |   |
│       |       |            |       |   +-- 9c. 消费 SSE 流，组装同步结果
│       |       |            |       |       consumeSseAndBuildResult(response)
│       |       |            |       |       → 聚合 stdout → "hello\n"
│       |       |            |       |       → 等 execution_complete → exitCode=0
│       |       |            |       |       → 组装 ExecuteCmdResult(exitCode=0, stdout="hello\n")
│       |       |            |       |
│       |       |            |       +-- 返回 ExecuteCmdResult
│       |       |            |
│       |       |            +-- recordSuccess("shell.executeCmd")
│       |       |            +-- auditSuccess("shell", "executeCmd", ...)
│       |       |                日志: EXTERNAL_CALL_AUDIT adapter=Sandbox, success=true,
│       |       |                       method=shell.executeCmd, elapsedMs=..., ...
│       |       |
│       |       |  -- ReAct Iteration 2/5 --
│       |       |  [Reason] LLM 基于工具结果生成最终回答:
│       |       |    → "The command ran successfully, outputting: hello"
│       |       |
│       |       +-- 返回 rawResult
│       |
│       +-- Checkpointer.save("sb-osb", sessionState)
│       +-- toQueryResponse(rawResult, "sb-osb")
│           → {role:"assistant", content:"The command ran successfully..."}
│
└─ HTTP 200
```

### 9.2 第 9 跳详解：OpenSandbox Provider

这是适配方案中新增的部分。`delegate.executeCmd("echo hello", ".", 30, {}, {})` 进入 `OpenSandboxShellOperation`：

```java
// OpenSandboxShellOperation (新建, extends SandboxShellOperation)
@Override
public ExecuteCmdResult executeCmd(String command, String cwd, int timeout,
    Map<String, String> environment, Map<String, Object> options) {

    // 9a. 确保沙箱已创建（懒创建，首次调用时触发）
    SandboxHandle handle = lifecycleManager.ensureSandbox();
    //  → 首次: POST http://127.0.0.1:8080/v1/sandboxes
    //    Headers: OPEN-SANDBOX-API-KEY: {api_key}
    //    Body: {
    //      "image": "opensandbox/code-interpreter:v1.1.0",
    //      "timeout": "PT30M",
    //      "resources": {"cpu":"500m","memory":"512Mi"}
    //    }
    //  ← Response: {
    //      "sandboxId": "sbx-abc123",
    //      "execdEndpoint": "http://127.0.0.1:44772",
    //      "execdAccessToken": "token-xyz"
    //    }
    //  后续: 直接复用已缓存的 handle

    // 9b. 调用 execd API 执行命令
    // → POST http://127.0.0.1:44772/command
    //   Headers: X-EXECD-ACCESS-TOKEN: token-xyz
    //   Body: {
    //     "command": "echo hello",
    //     "cwd": ".",
    //     "timeout": 30,
    //     "env": {},
    //     "background": false
    //   }
    // ← SSE Stream:
    //   event: stdout
    //   data: "hello\n"
    //   event: execution_complete
    //   data: {"exitCode": 0}

    // 9c. 消费 SSE 流，组装同步结果
    ExecuteCmdResult result = consumeSseAndBuildResult(response);
    //  → 聚合 stdout 事件 → "hello\n"
    //  → 聚合 stderr 事件 → ""
    //  → 等 execution_complete → exitCode=0
    //  → 组装: ExecuteCmdResult(exitCode=0, stdout="hello\n", stderr="")

    return result;
}
```

### 9.3 结果回传链路

```
OpenSandboxShellOperation.executeCmd()
  → 返回 ExecuteCmdResult(exitCode=0, stdout="hello\n")
    ↑
ExternalCallExecutor (audit: success=true, method=shell.executeCmd)
  → 返回 ExecuteCmdResult
    ↑
DecoratingSandboxShellOperation.executeCmd()
  → 返回 ExecuteCmdResult
    ↑
DecoratedSandboxToolRegistrar lambda
  → 返回 ExecuteCmdResult 给 ReActAgent
    ↑
-- ReAct Iteration 2/5 --
[Reason] LLM 基于工具结果生成回答:
  → "The command ran successfully, outputting: hello"
    ↑
JiuwenCoreAgentHandler.toQueryResponse()
  → QueryResponse{role:"assistant", content:"The command ran successfully..."}
    ↑
HTTP 200 → 返回给调用方
```

### 9.4 完整链路精简图

```
POST /v1/query {"message":"echo hello"}
│
├─ QueryMvcController                          [agent-service-app]
├─ A2AEnabledServeOrchestrator                 [agent-service-app]
├─ JiuwenCoreAgentHandler.query()              [agent-service-adapters-agentcore]
├─ Runner.runAgent → ReActAgent.invoke         [agent-core-java]
│   │
│   ├─ Iteration 1: LLM → tool_call(executeCmd, {command:"echo hello"})
│   │
│   ├─ DecoratedSandboxToolRegistrar lambda    [example/support]
│   │   └─ client.shell().executeCmd("echo hello", ".", 0, {}, {})
│   │
│   ├─ DecoratingSandboxShellOperation         [agent-service-adapters-agentcore]
│   │   └─ executor.execute("shell","executeCmd", false, () -> delegate.executeCmd(...))
│   │       │
│   │       ├─ ExternalCallExecutor            [agent-service-adapters-common]
│   │       │   ├─ ensureCircuitClosed()
│   │       │   ├─ callWithTimeout(30000ms)
│   │       │   │   └─ delegate.executeCmd("echo hello", ".", 30, {}, {})
│   │       │   │       │
│   │       │   │       │  ════════════════════════════════════════
│   │       │   │       │  ↑ Runtime 层结束 / Core 层开始 ↑
│   │       │   │       │  ↓ 以下为新增的 OpenSandbox Provider ↓
│   │       │   │       │  ════════════════════════════════════════
│   │       │   │       │
│   │       │   │       ├─ OpenSandboxShellOperation.executeCmd()
│   │       │   │       │   ├─ [首次] POST 8080/v1/sandboxes     ← Lifecycle API
│   │       │   │       │   │   Headers: OPEN-SANDBOX-API-KEY
│   │       │   │       │   │   Body: {image, timeout, resources}
│   │       │   │       │   │   ← {sandboxId, execdEndpoint, execdAccessToken}
│   │       │   │       │   │
│   │       │   │       │   ├─ POST {execdEndpoint}/command      ← execd API
│   │       │   │       │   │   Headers: X-EXECD-ACCESS-TOKEN
│   │       │   │       │   │   Body: {command:"echo hello", cwd:".", timeout:30}
│   │       │   │       │   │   ← SSE: stdout="hello\n", execution_complete exitCode=0
│   │       │   │       │   │
│   │       │   │       │   └─ 消费 SSE → ExecuteCmdResult(exitCode=0, stdout="hello\n")
│   │       │   │       │
│   │       │   │       └─ 返回 ExecuteCmdResult
│   │       │   │
│   │       │   ├─ recordSuccess()
│   │       │   └─ auditSuccess("shell","executeCmd")
│   │       │       日志: EXTERNAL_CALL_AUDIT adapter=Sandbox, success=true
│   │       │
│   │       └─ 返回 ExecuteCmdResult
│   │
│   └─ Iteration 2: LLM → "The command ran successfully..."
│
├─ JiuwenCoreAgentHandler.toQueryResponse()
└─ HTTP 200 {"result":{"role":"assistant","content":"The command ran successfully..."}}
```

### 9.5 串联的关键纽带

整条链路靠以下几个机制串起来：

| 纽带 | 机制 | 源码位置 |
|---|---|---|
| YAML → Core 配置 | `sandboxType` 字符串透传 | `DefaultAgentCoreSandboxClientFactory.toCoreConfig()` |
| Core 配置 → Provider 选择 | `new SandboxClient(config)` 根据 `sandboxType` 选 Provider | agent-core-java 内部 |
| Provider → 外部服务 | 两步寻址：Lifecycle API → execd API | `OpenSandboxShellOperation.executeCmd()` (新增) |
| Agent → 工具 | `LocalFunction` lambda 闭包持有 `client` | `DecoratedSandboxToolRegistrar.executeCmdTool()` |
| 治理包装 | `DecoratingSandboxClient` 装饰 `delegate` | `DecoratingSandboxClient` 构造器 |

---

## 十、配置串联分析：sandboxType 如何从 YAML 传递到 ReActAgent

### 10.1 第一环：YAML 配置 → Spring Bean

**文件**: `application-sandbox.yml`

```yaml
openjiuwen:
  service:
    external:
      sandbox:
        enabled: true              # ← 触发条件
        servers:
          - sandbox-type: opensandbox  # ← 关键字符串
```

**文件**: `AgentCoreAdaptersAutoConfiguration.java#L117-L124`

```java
@Bean
@ConditionalOnProperty(prefix = "openjiuwen.service.external.sandbox",
    name = "enabled", havingValue = "true")          // ← enabled=true 时生效
@ConditionalOnMissingBean(AgentCoreSandboxClientFactory.class)
public AgentCoreSandboxClientFactory agentCoreSandboxClientFactory(
    AgentCoreExternalProperties properties,           // ← YAML 绑定的配置
    ExternalOutboundSecuritySupport outboundSecuritySupport) {
    return new DefaultAgentCoreSandboxClientFactory(properties, outboundSecuritySupport);
}
```

`AgentCoreExternalProperties` 通过 `@ConfigurationProperties(prefix = "openjiuwen.service.external")` 绑定 YAML，其中 `sandbox.servers[0].sandboxType = "opensandbox"`。

此时容器中有一个 `AgentCoreSandboxClientFactory` Bean。

### 10.2 第二环：Bean 注入到 SandboxDemoApplication

**文件**: `SandboxDemoApplication.java#L29-L45`

```java
@Bean
AgentHandler agentHandler(
    LlmConfigResolver llmConfigResolver,
    ObjectProvider<ExternalSvcAdapterRegistrar> externalSvcAdapterRegistrarProvider,
    ObjectProvider<AgentCoreSandboxClientFactory> sandboxClientFactoryProvider) {  // ← 注入工厂
    // ...
    ReActAgent agent = ExampleReActAgentFactory.build(AGENT_ID, ...);

    // 工厂存在时，注册沙箱工具
    sandboxClientFactoryProvider.ifAvailable(factory ->
        DecoratedSandboxToolRegistrar.register(agent, factory));   // ← 串联点
    // ...
}
```

`ObjectProvider<AgentCoreSandboxClientFactory>` 会拿到上面创建的 `DefaultAgentCoreSandboxClientFactory` Bean。`ifAvailable` 确保只在 sandbox.enabled=true 时才注册工具。

### 10.3 第三环：工厂创建 SandboxClient

**文件**: `DecoratedSandboxToolRegistrar.java#L57`

```java
SandboxClient client = factory.create(serverId);  // serverId=null → 用第一个 server
```

进入 `DefaultAgentCoreSandboxClientFactory.java#L52-L59`：

```java
public SandboxClient create(String serverId) {
    SandboxPolicy policy = properties.getSandbox();
    Optional<SandboxServer> server = policy.findServer(serverId);

    // ★ Core SandboxClient 构造时读取 sandboxType
    SandboxClient delegate = new SandboxClient(configFor(serverId));

    return new DecoratingSandboxClient(resolvedServerId, delegate, policy);
}
```

`configFor()` 调用 `toCoreConfig()`（`DefaultAgentCoreSandboxClientFactory.java#L72-L102`）：

```java
private SandboxGatewayConfig toCoreConfig(SandboxServer server, SandboxPolicy policy) {
    // ... params 收集 root_path + auth 注入

    SandboxLauncherConfig launcherConfig = SandboxLauncherConfig.builder()
        .sandboxType(server.getSandboxType())   // ← "opensandbox"  ★这就是串联钥匙
        .gatewayUrl(server.getServiceUrl())      // ← "http://127.0.0.1:8080"
        .extraParams(server.getExtraParams())   // ← {image, cpu, memory, ...}
        .build();

    return SandboxGatewayConfig.builder()
        .gatewayUrl(server.getServiceUrl())
        .launcherConfig(launcherConfig)          // ← Core 从这里读 sandboxType
        .params(params)                          // ← auth token 等注入 params
        .build();
}
```

**关键点**：`new SandboxClient(config)` 是 agent-core-java 的构造器。Core 内部会根据 `config.getLauncherConfig().getSandboxType()` 选择对应的 Provider。当 `sandboxType="opensandbox"` 时，Core 会实例化 `OpenSandboxSandboxClient`（适配方案中新增的 Provider）。

### 10.4 第四环：SandboxClient → 三类 Operation

`DecoratingSandboxClient` 包装了 Core 的 `delegate`：

**文件**: `DecoratingSandboxClient.java#L49-L58`

```java
public DecoratingSandboxClient(String serverId, SandboxClient delegate, SandboxPolicy policy) {
    // delegate 就是 Core 的 SandboxClient
    //   sandboxType=opensandbox 时，delegate 内部用的是 OpenSandbox Provider

    this.fsOperation = new DecoratingSandboxFsOperation(
        getConfig(), this.delegate.fs(), executor);
    //                                    ^^^^^^^^^^^^^^^^
    //                                    OpenSandboxFsOperation

    this.shellOperation = new DecoratingSandboxShellOperation(
        getConfig(), this.delegate.shell(), executor);
    //                                      ^^^^^^^^^^^^^^^^
    //                                      OpenSandboxShellOperation

    this.codeOperation = new DecoratingSandboxCodeOperation(
        getConfig(), this.delegate.code(), executor);
    //                                      ^^^^^^^^^^^^^^^^
    //                                      OpenSandboxCodeOperation
}
```

### 10.5 第五环：注册工具到 ReActAgent

**文件**: `DecoratedSandboxToolRegistrar.java#L70-L82`

```java
public static List<ToolCard> register(ReActAgent agent, AgentCoreSandboxClientFactory factory, String serverId) {
    SandboxClient client = factory.create(serverId);  // ← 拿到 DecoratingSandboxClient

    List<LocalFunction> tools = List.of(
        readFileTool(client, serverId),     // ← client.fs().readFile(...)
        executeCmdTool(client, serverId),   // ← client.shell().executeCmd(...)
        executeCodeTool(client, serverId)); // ← client.code().executeCode(...)

    for (LocalFunction tool : tools) {
        registerTool(agent, tool);   // ← 注册到 Agent
    }
    return cards;
}
```

`registerTool` 做了两件事（`DecoratedSandboxToolRegistrar.java#L82-L89`）：

```java
private static void registerTool(ReActAgent agent, LocalFunction tool) {
    // 1. 注册到 Runner 资源管理器
    Result<ToolCard> result = Runner.resourceMgr().addTool(tool, agent.getCard().getId(), true);

    // 2. 添加到 Agent 的能力管理器
    agent.getAbilityManager().add(tool.getCard());
}
```

以 `executeCmdTool` 为例（`DecoratedSandboxToolRegistrar.java#L107-L117`）：

```java
private static LocalFunction executeCmdTool(SandboxClient client, String serverId) {
    // 从 client.shell().listTools() 获取 ToolCard 定义
    ToolCard card = toolCard(client.shell().listTools(), serverId, "shell", EXECUTE_CMD);
    //             ^^^^^^^^^^^^^^^^^^^^^^^^^^^^
    //             OpenSandboxShellOperation.listTools() 返回的 ToolCard
    //             包含参数定义: command, cwd, timeout, environment, options

    return new LocalFunction(card, inputs -> {
        // LLM 调用工具时执行这个 lambda
        ExecuteCmdResult result = client.shell().executeCmd(
            stringValue(inputs, "command", ""),
            stringValue(inputs, "cwd", "."),
            intValue(inputs, "timeout", 0),
            stringMapValue(inputs, "environment"),
            objectMapValue(inputs, "options"));
        //     client.shell() → DecoratingSandboxShellOperation
        //         → ExternalCallExecutor (治理)
        //             → delegate.executeCmd()
        //                 → OpenSandboxShellOperation.executeCmd()  ← 新 Provider
        return result;
    });
}
```

### 10.6 完整串联图

```
application-sandbox.yml
│  sandbox-type: opensandbox
│  service-url: http://127.0.0.1:8080
│  auth.token: ${OPEN_SANDBOX_API_KEY}
│
▼ Spring Boot @ConfigurationProperties 绑定
│
AgentCoreExternalProperties
│  sandbox.servers[0].sandboxType = "opensandbox"
│
▼ @ConditionalOnProperty(enabled=true)
│
DefaultAgentCoreSandboxClientFactory (Bean)
│
▼ SandboxDemoApplication.agentHandler() 注入
│
DecoratedSandboxToolRegistrar.register(agent, factory)
│
├── factory.create(null)
│   │
│   ├── configFor(null) → toCoreConfig()
│   │   │
│   │   └── SandboxGatewayConfig {
│   │         launcherConfig.sandboxType = "opensandbox"  ← 钥匙
│   │         launcherConfig.gatewayUrl  = "http://127.0.0.1:8080"
│   │         launcherConfig.extraParams = {image, cpu, memory}
│   │         params = {auth_token, root_path}
│   │       }
│   │
│   ├── new SandboxClient(config)
│   │   └── Core 根据 sandboxType="opensandbox"
│   │       → 实例化 OpenSandboxSandboxClient
│   │           ├── fs()  → OpenSandboxFsOperation
│   │           ├── shell() → OpenSandboxShellOperation
│   │           └── code() → OpenSandboxCodeOperation
│   │
│   └── new DecoratingSandboxClient(delegate, policy)
│       └── 包装治理层 (timeout/retry/circuitBreaker/audit)
│
├── client.shell().listTools()
│   → OpenSandboxShellOperation.listTools()
│   → 返回 ToolCard {name:"executeCmd", inputParams:[command, cwd, timeout, ...]}
│
├── new LocalFunction(card, inputs -> client.shell().executeCmd(...))
│   └── lambda 闭包持有 client (DecoratingSandboxClient)
│
├── Runner.resourceMgr().addTool(tool, agentId, true)
│   └── 注册到全局资源管理器
│
└── agent.getAbilityManager().add(card)
    └── 添加到 ReActAgent 的工具能力列表
        → LLM 在 ReAct 推理时看到: [readFile, executeCmd, executeCode]
        → LLM 选择 executeCmd 时，执行 lambda → client.shell().executeCmd()
            → DecoratingSandboxShellOperation (治理)
              → ExternalCallExecutor (timeout/audit)
                → OpenSandboxShellOperation.executeCmd() (新 Provider)
                  → POST 8080/v1/sandboxes (Lifecycle)
                  → POST {execd}/command (execd, SSE)
```

**核心机制**：整个串联不依赖任何显式注册代码——`sandboxType` 作为字符串从 YAML 一路透传到 Core 的 `new SandboxClient(config)`，Core 内部的 Provider 注册表根据这个字符串选择实现类。Runtime 层完全不需要知道 OpenSandbox 的存在。

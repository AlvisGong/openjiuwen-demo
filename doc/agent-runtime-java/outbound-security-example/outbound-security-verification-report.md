# Outbound Security Demo 验证报告

> 验证时间: 2026-09-10 15:22-15:27
> 模块: agent-service-demo-outbound-security
> 验证目标: MCP 与 Sandbox 出站 HTTPS + Bearer 鉴权端到端链路

---

## 运行环境

| 组件 | 说明 |
|---|---|
| Java | 21.0.10 |
| Maven | 3.9.14 (D:\Program Files\apache-maven-3.9.14) |
| 模块 | agent-service-demo/example/outbound-security |
| 依赖 | 无需外部服务，内置 mock HTTPS server |

### 模块特点

- 不依赖外部服务（无需 LLM、无需 Spring Boot、无需 Docker）
- 内置 Mock HTTPS Server，自动生成临时 PKCS12 证书
- 口令每次运行随机生成，进程退出后自动删除
- 通过 `mvn exec:java` 直接运行 Java main 方法

---

## 示例1: OutboundSecuritySandboxClientExample

### 1.1 启动方式

```bash
mvn -pl agent-service-demo/example/outbound-security exec:java ^
  -Dexec.mainClass=com.openjiuwen.service.demo.example.outboundsecurity.OutboundSecuritySandboxClientExample
```

> PowerShell 需将 -D 参数用引号包裹：
> `"-Dexec.mainClass=com.openjiuwen.service.demo.example.outboundsecurity.OutboundSecuritySandboxClientExample"`

### 1.2 验证链路

```
OutboundSecuritySandboxClientExample.main()
|
+-- MockOutboundSecureJiuwenBoxServer(DEMO_TOKEN).start(port)
|   -> 启动 Mock HTTPS JiuwenBox Server (随机端口)
|   -> 自动生成 PKCS12 证书 (truststore + keystore)
|   -> 提供 /api/v1/sandboxes/.../read_file 端点 (需 Bearer token)
|
+-- readFileThroughOutboundSecurity(mockServer)
    文件: OutboundSecuritySandboxClientExample.java#L76
    |
    +-- 构造 AgentCoreExternalProperties
    |   -> sandbox.enabled = true
    |   -> sandbox.timeout-ms = 5000
    |   -> retry.max = 0 (不重试)
    |   -> circuitBreaker.enabled = false
    |
    +-- 构造 SandboxServer 配置
    |   -> server-id = "demo-secure-sandbox"
    |   -> service-url = mockServer.baseUrl() (https://127.0.0.1:随机端口)
    |   -> sandbox-type = "jiuwenbox"
    |   -> launcher-type = "pre_deploy"
    |   -> root-path = "."
    |
    +-- 配置 TLS (ExternalTlsConfig)
    |   -> enabled = true
    |   -> trustStore = mockServer 生成的 PKCS12 文件路径
    |   -> trustStorePassword = 随机生成的口令
    |   -> trustStoreType = "PKCS12"
    |   -> verifyHostname = false
    |
    +-- 配置 Auth (ExternalAuthProperties)
    |   -> type = "bearer"
    |   -> token = "demo-outbound-token"
    |
    +-- new DefaultAgentCoreSandboxClientFactory(properties)
    |   文件: DefaultAgentCoreSandboxClientFactory.java#L40
    |   -> properties.getSandbox().validate()
    |
    +-- factory.create("demo-secure-sandbox")
    |   -> 返回 DecoratingSandboxClient
    |       +-- delegate: Core SandboxClient (JiuwenBox provider)
    |       |   -> OkHttp 客户端注入 TLS truststore + Bearer Interceptor
    |       +-- executor: ExternalCallExecutor (timeout=5s)
    |       +-- fsOperation: DecoratingSandboxFsOperation
    |
    +-- client.fs().readFile("/tmp/demo.txt", "text", ..., "UTF-8", 0, Map.of())
        |
        +-> DecoratingSandboxFsOperation.readFile()
        +-> ExternalCallExecutor.execute("fs", "readFile", true, () -> delegate.readFile(...))
            |
            +-> callWithTimeout(callable)
            |   +-> delegate.readFile()
            |   |   +-> Core SandboxClient (JiuwenBox provider)
            |   |   +-> OkHttp HTTPS GET/POST https://127.0.0.1:端口/api/v1/sandboxes/.../read_file
            |   |   |   Headers: Authorization: Bearer demo-outbound-token
            |   |   |   TLS: truststore 验证服务端证书
            |   |   +-> Mock Server 验证 token -> 返回文件内容
            |   |   <- ReadFileResult(content="secure jiuwenbox file:/tmp/demo.txt")
            |   |
            |   <- 返回 ReadFileResult
            |
            +-> auditSuccess("fs", "readFile")
                日志: EXTERNAL_CALL_AUDIT adapter=Sandbox, success=true, method=fs.readFile
```

### 1.3 关键日志

```
Mock outbound secure jiuwenbox server started at https://127.0.0.1:8035 (token=demo-outbound-token)
Passthrough CredentialDecryptor is active: credential is not decrypted, sceneType=14
EXTERNAL_CALL_AUDIT adapter=Sandbox, success=true, target=demo-secure-sandbox,
  method=fs.readFile, attempt=1, elapsedMs=278, request=null, response=ReadFileResult(hash=66cc8ca2)
Outbound secure sandbox demo succeeded, content=secure jiuwenbox file:/tmp/demo.txt
Sandbox read-file content: secure jiuwenbox file:/tmp/demo.txt
```

### 1.4 验证结果

| 验证项 | 预期 | 实际 | 状态 |
|---|---|---|---|
| Mock HTTPS Server 启动 | 随机端口监听 | https://127.0.0.1:8035 | PASS |
| TLS 证书生成 | PKCS12 truststore | 随机口令 + 临时文件 | PASS |
| Bearer 鉴权 | token=demo-outbound-token | token=demo-outbound-token | PASS |
| OkHttp TLS 注入 | truststore 验证服务端证书 | HTTPS 连接成功 | PASS |
| readFile 调用 | 通过 HTTPS + Bearer 读取文件 | 返回文件内容 | PASS |
| ExternalCallExecutor 审计 | EXTERNAL_CALL_AUDIT 日志 | success=true, elapsedMs=278 | PASS |
| 文件内容 | secure jiuwenbox file:/tmp/demo.txt | secure jiuwenbox file:/tmp/demo.txt | PASS |

---

## 示例2: OutboundSecurityMcpClientExample

### 2.1 启动方式

```bash
mvn -pl agent-service-demo/example/outbound-security exec:java ^
  -Dexec.mainClass=com.openjiuwen.service.demo.example.outboundsecurity.OutboundSecurityMcpClientExample
```

### 2.2 验证链路

```
OutboundSecurityMcpClientExample.main()
|
+-- MockOutboundSecureMcpServer(DEMO_TOKEN).start(port)
|   -> 启动 Mock HTTPS MCP JSON-RPC Server (随机端口)
|   -> 自动生成 PKCS12 证书
|   -> 提供 /mcp 端点 (JSON-RPC over HTTPS, 需 Bearer token)
|   -> 注册工具: secure_echo
|
+-- listToolsThroughOutboundSecurity(mockServer)
    文件: OutboundSecurityMcpClientExample.java#L73
    |
    +-- 构造 TLS 配置 (ExternalTlsConfig)
    |   -> enabled = true
    |   -> trustStore = mockServer 生成的 PKCS12 文件路径
    |   -> trustStorePassword = 随机生成的口令
    |   -> trustStoreType = "PKCS12"
    |   -> verifyHostname = false
    |
    +-- 构造 Auth 配置 (ExternalAuthProperties)
    |   -> type = "bearer"
    |   -> token = "demo-outbound-token"
    |
    +-- ExternalOutboundSecuritySupport.createDefault(new PassthroughCredentialDecryptor())
    |   -> 创建出站安全支持实例
    |
    +-- support.prepare(targetRef, tlsConfig, authConfig, Duration.ofSeconds(5))
    |   -> PreparedOutboundSecurity
    |   -> 注入 TLS truststore + Bearer auth 到 Core 配置
    |
    +-- 构造 McpServerConfig
    |   -> serverId = "demo-secure-mcp"
    |   -> serverPath = "https://127.0.0.1:端口/mcp"
    |   -> clientType = "streamable_http"
    |
    +-- prepared.applyAuthToMaps(coreConfig.getAuthHeaders(), ...)
    |   -> 将 Bearer token 写入 authHeaders
    |
    +-- prepared.injectParams(params)
    |   -> 将 TLS 参数注入 params map
    |
    +-- new StreamableHttpClient(coreConfig)
    |   -> Core MCP 客户端 (JDK HttpClient)
    |   -> 使用 authHeaders 中的 Bearer token
    |   -> 使用 params 中的 TLS truststore
    |
    +-- client.connect(1, 5f)
    |   -> HTTPS 连接到 Mock MCP Server
    |   -> 验证服务端证书 (truststore)
    |   -> 发送 Bearer token 鉴权
    |
    +-- client.listTools(5f)
    |   -> JSON-RPC 请求: tools/list
    |   -> HTTPS POST https://127.0.0.1:端口/mcp
    |      Headers: Authorization: Bearer demo-outbound-token
    |   -> Mock Server 验证 token -> 返回工具列表
    |   <- [secure_echo]
    |
    +-- client.disconnect(1f)
    -> 返回 ["secure_echo"]
```

### 2.3 关键日志

```
Mock outbound secure MCP server started at https://127.0.0.1:30705/mcp (token=demo-outbound-token)
Passthrough CredentialDecryptor is active: credential is not decrypted, sceneType=14
Outbound secure MCP demo succeeded, tools=[secure_echo]
Listed MCP tools: [secure_echo]
```

### 2.4 验证结果

| 验证项 | 预期 | 实际 | 状态 |
|---|---|---|---|
| Mock HTTPS Server 启动 | 随机端口监听 /mcp | https://127.0.0.1:30705/mcp | PASS |
| TLS 证书生成 | PKCS12 truststore | 随机口令 + 临时文件 | PASS |
| Bearer 鉴权 | token=demo-outbound-token | token=demo-outbound-token | PASS |
| JDK HttpClient TLS | truststore 验证服务端证书 | HTTPS 连接成功 | PASS |
| authHeaders 注入 | Authorization: Bearer ... | Bearer token 正确发送 | PASS |
| tools/list 调用 | 通过 HTTPS + Bearer 列出工具 | 返回 [secure_echo] | PASS |
| 工具列表 | [secure_echo] | [secure_echo] | PASS |

---

## 两条出站链路对比

| 维度 | Sandbox Client | MCP Client |
|---|---|---|
| **启动类** | OutboundSecuritySandboxClientExample | OutboundSecurityMcpClientExample |
| **Mock Server** | MockOutboundSecureJiuwenBoxServer | MockOutboundSecureMcpServer |
| **HTTP 客户端** | OkHttp (注入 TLS + Bearer Interceptor) | JDK HttpClient (authHeaders) |
| **TLS 注入方式** | DefaultAgentCoreSandboxClientFactory -> Core SandboxClient | ExternalOutboundSecuritySupport.prepare -> McpServerConfig params |
| **Bearer 注入方式** | ExternalAuthProperties -> server.getAuth() | PreparedOutboundSecurity.applyAuthToMaps -> authHeaders |
| **调用操作** | client.fs().readFile(path) | client.listTools() |
| **治理装饰器** | DecoratingSandboxClient + ExternalCallExecutor | 无 (直接调用 Core StreamableHttpClient) |
| **审计日志** | EXTERNAL_CALL_AUDIT adapter=Sandbox | 无 (MCP 不走 ExternalCallExecutor) |
| **验证结果** | PASS (读取文件成功) | PASS (列出工具成功) |

---

## 出站安全架构

```
Java Demo (mvn exec:java)
|
+-- [Sandbox 链路]
|   +-- DefaultAgentCoreSandboxClientFactory
|   |   +-- 构造 SandboxGatewayConfig (含 service-url, sandbox-type)
|   |   +-- 注入 TLS truststore 到 OkHttp
|   |   +-- 注入 Bearer token 到 OkHttp Interceptor
|   |   +-> new SandboxClient(config) [Core, JiuwenBox provider]
|   |   +-> new DecoratingSandboxClient(delegate, policy) [治理装饰器]
|   |
|   +-- client.fs().readFile(path)
|       +-> DecoratingSandboxFsOperation.readFile()
|           +-> ExternalCallExecutor.execute("fs", "readFile", true, () -> ...)
|               +-> Core SandboxClient delegate.readFile()
|                   +-> OkHttp HTTPS POST
|                      |  URL: https://127.0.0.1:port/api/v1/sandboxes/.../read_file
|                      |  Headers: Authorization: Bearer demo-outbound-token
|                      |  TLS: truststore 验证服务端证书
|                      v
|                   MockOutboundSecureJiuwenBoxServer
|                   +-- 验证 Bearer token
|                   +-- 返回文件内容
|
+-- [MCP 链路]
    +-- ExternalOutboundSecuritySupport
    |   +-- prepare(targetRef, tlsConfig, authConfig, timeout)
    |   +-> PreparedOutboundSecurity
    |       +-- TLS: truststore 路径 + 口令
    |       +-- Auth: Bearer token
    |
    +-- prepared.applyAuthToMaps(authHeaders, authQueryParams)
    |   -> authHeaders: {Authorization: "Bearer demo-outbound-token"}
    |
    +-- prepared.injectParams(params)
    |   -> params 包含 TLS 配置
    |
    +-- new StreamableHttpClient(coreConfig)
    |   -> Core MCP 客户端 (JDK HttpClient)
    |   -> 使用 authHeaders 中的 Bearer token
    |   -> 使用 params 中的 TLS truststore
    |
    +-- client.connect() -> client.listTools()
        +-> HTTPS POST https://127.0.0.1:port/mcp
           Headers: Authorization: Bearer demo-outbound-token
           TLS: truststore 验证服务端证书
           v
        MockOutboundSecureMcpServer
        +-- 验证 Bearer token
        +-- 返回 tools/list JSON-RPC 响应
```

---

## 关键源码文件索引

| 文件 | 角色 | 关键方法 |
|---|---|---|
| [OutboundSecuritySandboxClientExample.java](src/main/java/com/openjiuwen/service/demo/example/outboundsecurity/OutboundSecuritySandboxClientExample.java) | Sandbox 出站安全 Demo | main(), runDemo(), readFileThroughOutboundSecurity() |
| [OutboundSecurityMcpClientExample.java](src/main/java/com/openjiuwen/service/demo/example/outboundsecurity/OutboundSecurityMcpClientExample.java) | MCP 出站安全 Demo | main(), runDemo(), listToolsThroughOutboundSecurity() |
| [MockOutboundSecureJiuwenBoxServer.java](src/main/java/com/openjiuwen/service/demo/example/outboundsecurity/MockOutboundSecureJiuwenBoxServer.java) | Mock HTTPS JiuwenBox | start(), stop(), baseUrl(), tlsMaterial() |
| [MockOutboundSecureMcpServer.java](src/main/java/com/openjiuwen/service/demo/example/outboundsecurity/MockOutboundSecureMcpServer.java) | Mock HTTPS MCP | start(), stop(), port(), tlsMaterial() |
| [OutboundTlsMaterialGenerator.java](src/main/java/com/openjiuwen/service/demo/example/outboundsecurity/support/OutboundTlsMaterialGenerator.java) | 临时 PKCS12 证书生成 | generate() |
| [DefaultAgentCoreSandboxClientFactory.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/external/DefaultAgentCoreSandboxClientFactory.java) | 创建 DecoratingSandboxClient | create(), configFor() |
| [DecoratingSandboxClient.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/external/DecoratingSandboxClient.java) | 治理装饰器 | fs(), shell(), code() |
| [ExternalOutboundSecuritySupport.java](../../../../agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/security/ExternalOutboundSecuritySupport.java) | 出站安全支持 | prepare(), PreparedOutboundSecurity |
| [ExternalTlsConfig.java](../../../../agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/security/ExternalTlsConfig.java) | TLS 配置 | trustStore, trustStorePassword, verifyHostname |
| [ExternalAuthProperties.java](../../../../agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/security/ExternalAuthProperties.java) | Auth 配置 | type, token |
| [ExternalCallExecutor.java](../../../../agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/external/ExternalCallExecutor.java) | 外部调用治理 | execute(), callWithTimeout(), auditSuccess() |
| [README.md](README.md) | 使用说明 | 启动方法, YAML 配置 |

# Demo 验证报告对比汇总

> 基于 Sandbox Demo、Outbound Security Demo、Security Demo 三份验证报告整理

---

## 总览对比

| 维度 | Sandbox Demo | Outbound Security Demo | Security Demo |
|---|---|---|---|
| **模块** | agent-service-demo-sandbox | agent-service-demo-outbound-security | agent-service-demo-security |
| **启动类** | SandboxDemoApplication | OutboundSecuritySandboxClientExample / OutboundSecurityMcpClientExample | SecurityDemoApplication |
| **启动方式** | Spring Boot (java -jar) | mvn exec:java (独立 main 方法) | Spring Boot (java -jar) |
| **端口** | 8093 | 无固定端口 (Mock Server 随机端口) | 8095 |
| **是否需外部服务** | 是 (JiuwenBox Docker:8321) | 否 (内置 Mock HTTPS Server) | 否 (仅 LLM) |
| **是否需 LLM** | 是 (glm-5.2) | 否 (不需要 LLM) | 是 (glm-5.2) |
| **是否需 Docker** | 是 (JiuwenBox 容器) | 否 | 否 |
| **核心特性** | 出站调用沙箱工具 (executeCmd/readFile/executeCode) | 出站 TLS + Bearer 鉴权 (MCP + Sandbox) | 入站细粒度鉴权 (FineGrainedAuthorizer) |
| **安全方向** | 出站 (Agent -> JiuwenBox) | 出站 (Agent -> Mock HTTPS Server) | 入站 (用户 -> Agent Service) |
| **验证轮次** | 3 轮接口调用 | 2 个示例各 1 次运行 | 2 轮接口调用 (403 + 200) |
| **验证结果** | 全部 PASS | 全部 PASS | 全部 PASS |

---

## 验证链路对比

### Sandbox Demo 链路

```
用户 POST /v1/query (8093)
  -> QueryMvcController -> Orchestrator -> JiuwenCoreAgentHandler
    -> Runner.runAgent -> ReActAgent
      -> LLM 选择工具 (executeCmd / executeCode)
        -> DecoratedSandboxToolRegistrar lambda
          -> client.shell().executeCmd() / client.code().executeCode()
            -> DecoratingSandboxClient (治理装饰器)
              -> ExternalCallExecutor (timeout/retry/circuitBreaker/audit)
                -> Core SandboxClient (JiuwenBox provider)
                  -> HTTP POST http://127.0.0.1:8321/api/v1/sandboxes/.../execute
                    -> JiuwenBox Docker (bubblewrap 隔离)
                    <- 命令/代码执行结果
              <- EXTERNAL_CALL_AUDIT 审计日志
          <- 工具结果
      <- LLM 基于结果生成回答
    <- QueryResponse
  <- HTTP 200
```

### Outbound Security Demo 链路 (Sandbox)

```
mvn exec:java (无 HTTP 服务)
  -> MockOutboundSecureJiuwenBoxServer.start() (内置 Mock HTTPS)
  -> DefaultAgentCoreSandboxClientFactory.create()
    -> 构造 SandboxServer (service-url=https://127.0.0.1:随机端口)
    -> 注入 TLS truststore (PKCS12)
    -> 注入 Bearer token
    -> new DecoratingSandboxClient(delegate, policy)
      -> OkHttp 客户端注入 TLS + Bearer Interceptor
  -> client.fs().readFile("/tmp/demo.txt")
    -> DecoratingSandboxFsOperation.readFile()
      -> ExternalCallExecutor.execute("fs", "readFile", true, ...)
        -> Core SandboxClient (OkHttp HTTPS + Bearer)
          -> HTTPS GET/POST https://127.0.0.1:端口/api/v1/.../read_file
            Headers: Authorization: Bearer demo-outbound-token
            TLS: truststore 验证服务端证书
          <- 文件内容
      <- EXTERNAL_CALL_AUDIT 审计日志
  <- "secure jiuwenbox file:/tmp/demo.txt"
```

### Outbound Security Demo 链路 (MCP)

```
mvn exec:java (无 HTTP 服务)
  -> MockOutboundSecureMcpServer.start() (内置 Mock HTTPS /mcp)
  -> ExternalOutboundSecuritySupport.prepare(targetRef, tlsConfig, authConfig, timeout)
    -> PreparedOutboundSecurity (TLS + Bearer)
  -> prepared.applyAuthToMaps(authHeaders, authQueryParams)
    -> authHeaders: {Authorization: "Bearer demo-outbound-token"}
  -> prepared.injectParams(params)
    -> params 包含 TLS truststore 配置
  -> new StreamableHttpClient(coreConfig) (Core MCP 客户端)
  -> client.connect() -> client.listTools()
    -> HTTPS POST https://127.0.0.1:端口/mcp
       Headers: Authorization: Bearer demo-outbound-token
       TLS: truststore 验证服务端证书 (JDK HttpClient)
    <- JSON-RPC tools/list 响应
  <- [secure_echo]
```

### Security Demo 链路

```
用户 POST /v1/query (8095)
  -> QueryMvcController.queryV1()
    @AuthorizedResource(resource="query", action="execute")
    -> AuthorizationRequestBuilder.build(headers)
      -> 从 headers 提取 X-User-ID / X-Space-ID / X-Tenant-ID
      -> new AuthorizationRequest("query", "execute", userId, spaceId, tenantId)
    -> FineGrainedAuthorizer.authorize(request)
      [DemoFineGrainedAuthorizer]
      -> if userId == null || userId.isBlank()
         -> deny("X-User-ID header is required")
         -> HTTP 403 ACCESS_DENIED
      -> else
         -> allow()
         -> handleQuery() -> Orchestrator -> JiuwenCoreAgentHandler
           -> Runner.runAgent -> ReActAgent -> LLM
         <- QueryResponse
  <- HTTP 200 (鉴权通过) 或 HTTP 403 (鉴权拒绝)
```

---

## 关键差异对比

### 1. HTTP 客户端与 TLS

| 维度 | Sandbox Demo | Outbound Security (Sandbox) | Outbound Security (MCP) | Security Demo |
|---|---|---|---|---|
| HTTP 客户端 | Core SandboxClient (JiuwenBox provider) | OkHttp (注入 TLS + Bearer) | JDK HttpClient (authHeaders) | 无出站调用 |
| TLS | 无 (HTTP 连接 JiuwenBox) | PKCS12 truststore | PKCS12 truststore | 无 (入站 HTTP) |
| Bearer 鉴权 | 无 | OkHttp Interceptor | authHeaders map | 无 (入站鉴权不同) |
| 证书来源 | 无 | 运行时 keytool 生成 | 运行时 keytool 生成 | 无 |

### 2. 治理与审计

| 维度 | Sandbox Demo | Outbound Security (Sandbox) | Outbound Security (MCP) | Security Demo |
|---|---|---|---|---|
| ExternalCallExecutor | 有 (DecoratingSandboxClient) | 有 (DecoratingSandboxClient) | 无 (直接调用) | 无 |
| timeout | 30s | 5s | 5s | 无 (LLM 60s) |
| retry | max=1 (executeCmd 不重试) | max=0 (不重试) | 无 | 无 |
| circuit breaker | enabled, threshold=3 | disabled | 无 | 无 |
| audit | EXTERNAL_CALL_AUDIT | EXTERNAL_CALL_AUDIT | 无 | 无 |
| 入站鉴权 AOP | 无 | 无 | 无 | FineGrainedAuthorizer |

### 3. 工具与操作

| 维度 | Sandbox Demo | Outbound Security (Sandbox) | Outbound Security (MCP) | Security Demo |
|---|---|---|---|---|
| 注册工具 | readFile, executeCmd, executeCode | 无 (直接调用 client) | 无 (直接调用 client) | 无 (纯对话) |
| LLM 选工具 | 是 (ReActAgent 推理) | 否 (硬编码调用) | 否 (硬编码调用) | 否 (纯对话) |
| 核心操作 | executeCmd / executeCode | fs().readFile() | listTools() | N/A |

### 4. 验证点

| 维度 | Sandbox Demo | Outbound Security (Sandbox) | Outbound Security (MCP) | Security Demo |
|---|---|---|---|---|
| 验证点1 | echo sandbox-file-ok (exit=0) | readFile 返回文件内容 | listTools 返回 [secure_echo] | 无 X-User-ID -> 403 |
| 验证点2 | printf+cat 创建读取文件 | TLS 证书验证 | TLS 证书验证 | 带 X-User-ID -> 200 |
| 验证点3 | Python print (exit=0) | Bearer 鉴权 | Bearer 鉴权 | LLM 正常回答 |
| 验证点4 | JiuwenBox bubblewrap 隔离 | OkHttp TLS 注入 | JDK HttpClient TLS 注入 | /health 不受鉴权影响 |

---

## 文件索引对比

| Demo | 验证报告 | 启动类 | 核心配置 | README |
|---|---|---|---|---|
| Sandbox | sandbox-call-stack-analysis.md | SandboxDemoApplication.java | application-sandbox.yml | example/sandbox/README.md |
| Outbound Security | outbound-security-verification-report.md | OutboundSecuritySandboxClientExample.java / OutboundSecurityMcpClientExample.java | (代码内构造配置) | example/outbound-security/README.md |
| Security | security-verification-report.md | SecurityDemoApplication.java | application-security.yml | example/security/README.md |

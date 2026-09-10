# MCP Demo 端到端调用栈分析

> 以 "请调用 demo_echo 工具，把 text 设置为 hello" 为例，
> 结合运行日志和代码，严格追踪从用户请求到 FastMCP 工具调用再到最终响应的完整调用栈。

---

## 运行环境

| 组件 | 端口 | 说明 |
|---|---|---|
| FastMCP Server (Python) | 18080 | 独立 Python 进程, `mcp==1.28.1`, stateless_http=True, json_response=True |
| Java MCP Demo | 8092 | Spring Boot 应用 `McpDemoApplication`, ReActAgent + MCP 工具 |
| LLM (glm-5.2) | 远程 | dashscope 兼容 OpenAI API |

---

## 一、启动阶段调用栈

### 1.1 Python FastMCP Server 启动

```
python fastmcp_server.py
文件: service/agent-service-demo/example/mcp/server/fastmcp_server.py
│
├── 读取环境变量:
│   DEMO_FASTMCP_HOST = "127.0.0.1"
│   DEMO_FASTMCP_PORT = 18080
│
├── mcp = FastMCP("openjiuwen-demo-fastmcp",
│       stateless_http=True,   ← 无状态模式, 每次请求独立
│       json_response=True)    ← 响应为普通 JSON (非 SSE body)
│
├── 注册 3 个工具:
│   @mcp.tool() demo_echo(text: str) → "demo_echo:{text}"
│   @mcp.tool() demo_delay(delay_ms: int) → 延迟后返回
│   @mcp.tool() demo_fail() → 抛 RuntimeError
│
└── mcp.run(transport="streamable-http")
    → Uvicorn 启动 HTTP 服务, endpoint = http://127.0.0.1:18080/mcp
    日志: "Starting FastMCP server endpoint=http://127.0.0.1:18080/mcp"
```

### 1.2 Java MCP Demo 启动

```
java -jar agent-service-demo-mcp-0.1.2.jar
文件: service/agent-service-demo/example/mcp/src/main/java/.../McpDemoApplication.java
│
├── SpringApplication.run(McpDemoApplication.class, args)
│   加载配置文件 (application.yml → application-base.yml → application-mcp.yml):
│   │
│   │   application-mcp.yml:
│   │   server.port: 8092
│   │   openjiuwen.service.external.mcp:
│   │     timeout-ms: 30000
│   │     retry-tool-calls: false
│   │     retry: {max: 1, backoff-ms: 200}
│   │     circuit-breaker: {enabled: true, failure-threshold: 3, reset-timeout-ms: 30000}
│   │     audit: {enabled: true}
│   │     servers:
│   │       - server-id: demo-mcp
│   │         server-name: demo-mcp-tools
│   │         server-path: http://127.0.0.1:18080/mcp
│   │         client-type: streamable-http
│   │
│   ├── Spring Boot 自动装配:
│   │   LlmAutoConfiguration → LlmConfigResolver Bean
│   │   AgentServiceAutoConfiguration → Controller/Lifecycle Beans
│   │   A2AAutoConfiguration → Orchestrator/Discovery Beans
│   │   ExternalSvcAdapterAutoConfiguration → AgentCoreExternalProperties + DefaultExternalSvcAdapterRegistrar
│   │     (@ConfigurationProperties(prefix = "openjiuwen.service.external"))
│   │
│   └── @Bean agentHandler(llmConfigResolver, externalSvcAdapterRegistrarProvider, externalPropertiesProvider)
│       文件: McpDemoApplication.java#L41
│       │
│       ├── [1] llmConfigResolver.resolveRequired()
│       │   → 从 apiconfig.json + yml 合并配置:
│       │     apiBase = "https://dashscope.aliyuncs.com/compatible-mode/v1"
│       │     apiKey = "sk-84d2..."
│       │     modelName = "glm-5.2"
│       │     systemPrompt = "You are a helpful assistant..."
│       │     temperature = 0.6, topP = 0.8
│       │
│       ├── [2] ExampleReActAgentFactory.build(AGENT_ID, "Demo MCP Agent", "...", llmConfig)
│       │   → 构造 ReActAgent, 配置 LLM 客户端 (provider=OpenAI, model=glm-5.2)
│       │   → systemPrompt 注入 promptTemplate
│       │   → maxIterations=5, contextWindowLimit=10
│       │
│       ├── [3] bindMcpServers(agent, externalProperties)
│       │   文件: McpDemoApplication.java#L56
│       │   │
│       │   ├── externalProperties.getMcp().getServers()
│       │   │   → [{serverId:"demo-mcp", serverName:"demo-mcp-tools", ...}]
│       │   │
│       │   └── for each server:
│       │       McpServerConfig config = McpServerConfig.builder().build()
│       │   config.setServerId("demo-mcp")
│       │   config.setServerName("demo-mcp-tools")
│       │   agent.getAbilityManager().add(config)
│       │     ← 只注册 serverId + serverName, 不含 URL
│       │     ← Agent 的 AbilityManager 知道"有一个叫 demo-mcp 的 MCP Server"
│       │   日志: "Bound MCP server to agent ability manager, serverId=demo-mcp, serverName=demo-mcp-tools"
│       │
│       └── [4] new JiuwenCoreAgentHandler(agent, externalSvcAdapterRegistrar)
│           → 包装 agent + 外部服务注册器
│
├── [ApplicationReadyEvent]
│   │
│   ├── AgentLifecycleBootstrap → InitPhaseExecutor → handler.start()
│   │   │
│   │   ├── JiuwenCoreAgentHandler.start()
│   │   │   ├── RUNNER_STARTED.compareAndSet(false, true) → true
│   │   │   ├── externalSvcAdapterRegistrar.registerToRunner()
│   │   │   │   文件: DefaultExternalSvcAdapterRegistrar.java#L117
│   │   │   │   │
│   │   │   │   ├── properties.getMcp().validate()
│   │   │   │   │   → 校验 timeout/retry/circuitBreaker/servers 配置合法性
│   │   │   │   │
│   │   │   │   ├── registerMcpClientProviders()
│   │   │   │   │   → 注册 3 种 MCP Client 类型到 McpClientFactory:
│   │   │   │   │     "sse" → SseClient::new
│   │   │   │   │     "stdio" → StdioClient::new
│   │   │   │   │     "streamable_http" → StreamableHttpClient::new
│   │   │   │   │   每个 Client 被 DecoratingMcpClient 包装 (装饰器模式)
│   │   │   │   │
│   │   │   │   └── for each server:
│   │   │   │       toCoreConfig(server)              ← DefaultExternalSvcAdapterRegistrar.java#L237
│   │   │   │       │
│   │   │   │       │   构造完整的 McpServerConfig:
│   │   │   │       │     serverId = "demo-mcp"
│   │   │   │       │     serverName = "demo-mcp-tools"
│   │   │   │       │     serverPath = "http://127.0.0.1:18080/mcp"  ← URL!
│   │   │   │       │     clientType = "streamable_http"  ← normalizeClientType("streamable-http")
│   │   │   │       │     authHeaders = {Accept: "application/json"}  ← streamable_http 特有
│   │   │   │       │     params = {}
│   │   │   │       │
│   │   │   │       Runner.resourceMgr().addMcpServer(config, tag, expiryTimeMs)
│   │   │   │       │
│   │   │   │       │   → Core Runner 连接 MCP Server:
│   │   │   │       │     McpClientFactory.create("streamable_http", config)
│   │   │   │       │     → StreamableHttpClient(config) 被 DecoratingMcpClient 包装
│   │   │   │       │     → DecoratingMcpClient.connect(retryTimes=1, timeout=30s)
│   │   │   │       │       → ExternalCallExecutor.execute("mcp", "connect", ...)
│   │   │   │       │         → StreamableHttpClient.connect(30s)
│   │   │   │       │           → POST http://127.0.0.1:18080/mcp (JSON-RPC initialize)
│   │   │   │       │     日志: EXTERNAL_CALL_AUDIT adapter=MCP, success=true, method=mcp.connect, elapsedMs=144
│   │   │   │       │
│   │   │   │       │   → 获取工具列表:
│   │   │   │       │     DecoratingMcpClient.listTools(timeout=30s)
│   │   │   │       │       → ExternalCallExecutor.execute("mcp", "tools/list", ...)
│   │   │   │       │         → StreamableHttpClient.listTools(30s)
│   │   │   │       │           → POST http://127.0.0.1:18080/mcp (JSON-RPC tools/list)
│   │   │   │       │             → 返回 [demo_echo, demo_delay, demo_fail]
│   │   │   │       │     日志: EXTERNAL_CALL_AUDIT adapter=MCP, success=true, method=mcp.tools/list, response=Iterable(size=3)
│   │   │   │       │
│   │   │   │       日志: "Registered external MCP server, serverId=demo-mcp, serverName=demo-mcp-tools"
│   │   │   │
│   │   │   └── Runner.start()
│   │   │       日志: "Succeed to start runner, runnerId=global"
│   │   │
│   │   └── readiness.markAgentLoaded(true)
│   │       日志: "Agent init phase completed for application 'demo-mcp-agent-service', agent_loaded=true"
│   │
│   └── 应用就绪, 等待 HTTP 请求
```

---

## 二、请求处理阶段调用栈

### 2.1 HTTP 入口

```
POST http://127.0.0.1:8092/v1/query
Content-Type: application/json
Body: {"conversation_id":"mcp-demo-c1","message":"请调用 demo_echo 工具，把 text 设置为 hello，并告诉我工具返回了什么","stream":false}
│
└── QueryMvcController.queryV1(rawBody, headers, servletRequest, response)
    文件: service/agent-service-app/.../controller/query/QueryMvcController.java#L86
    │
    └── handleQuery(rawBody, ...)
        ├── objectMapper.readValue(rawBody, QueryRequest.class)
        ├── QueryIngressSupport.validateAndBuild(request, headers) → ServeRequest
        ├── isAgentReady() → true
        ├── orchestratorProvider.getIfAvailable() → A2AEnabledServeOrchestrator
        ├── request.isStream() → false (非流式)
        └── orchestrator.query(serveRequest)  ← 进入编排器
```

### 2.2 Orchestrator → AgentHandler

```
A2AEnabledServeOrchestrator.query(request)
文件: A2AEnabledServeOrchestrator.java#L136
│
├── agentHandler.prepareTask(request) → Optional.empty()
├── syncResumePending → 无待恢复任务
├── agentHandler.query(current)
│   │
│   │  ┌──────────────────────────────────────────────────────┐
│   │  │  JiuwenCoreAgentHandler.query(request)                │
│   │  │  文件: JiuwenCoreAgentHandler.java#L228              │
│   │  │                                                        │
│   │  │  ├── supportsInvoke(agent) → true (ReActAgent)       │
│   │  │  ├── buildInputs(request)                            │
│   │  │  │   → inputs = {query:"请调用 demo_echo...", ...}   │
│   │  │  ├── runnerSession(request) → "mcp-demo-c1"          │
│   │  │  └── Runner.runAgent(agent, inputs, session, null)  │
│   │  │      → 进入 agent-core-java ReActAgent 推理循环       │
│   │  │                                                        │
│   │  │      [Reason] LLM 推理:                              │
│   │  │        输入: system prompt + user message + 工具列表  │
│   │  │        工具列表来源: AbilityManager 懒加载            │
│   │  │          → 之前 bindMcpServers 注册的 "demo-mcp"      │
│   │  │          → Runner 启动时 listTools 获取的 3 个工具    │
│   │  │          → [demo_echo(text), demo_delay(delay_ms),   │
│   │  │             demo_fail()]                             │
│   │  │                                                        │
│   │  │        LLM (glm-5.2) 推理:                            │
│   │  │          user: "请调用 demo_echo 工具，text=hello"    │
│   │  │          → LLM 识别意图, 选择 demo_echo 工具          │
│   │  │          → 输出: tool_call(demo_echo, {text:"hello"}) │
│   │  │                                                        │
│   │  │      [Act] MCP 工具调用:                              │
│   │  │        ReActAgent 执行 tool_call(demo_echo, {text:   │
│   │  │          "hello"})                                    │
│   │  │        → Core Runner 从 AbilityManager 查找 "demo-mcp"│
│   │  │        → 获取已注册的 DecoratingMcpClient              │
│   │  │        → DecoratingMcpClient.callTool(                │
│   │  │            "demo_echo", {text:"hello"}, timeout=30s) │
│   │  │            文件: DecoratingMcpClient.java#L71        │
│   │  │            │                                          │
│   │  │            └── ExternalCallExecutor.execute(         │
│   │  │                  "mcp", "tools/call",                 │
│   │  │                  retryToolCalls=false,                 │
│   │  │                  request={toolName:"demo_echo",       │
│   │  │                    arguments:{text:"hello"}},          │
│   │  │                  () -> delegate.callTool(             │
│   │  │                    "demo_echo", {text:"hello"}, 30s))│
│   │  │                                                      │
│   │  │                ExternalCallExecutor 内部:            │
│   │  │                ├── 熔断器检查 (failure-threshold=3)  │
│   │  │                ├── 超时控制 (30s, 独立线程)          │
│   │  │                ├── 重试 (max=1, backoff=200ms)       │
│   │  │                │   (retryToolCalls=false → 不重试)   │
│   │  │                ├── 执行 delegate.callTool:           │
│   │  │                │   StreamableHttpClient.callTool(    │
│   │  │                │     "demo_echo", {text:"hello"},   │
│   │  │                │     30s)                            │
│   │  │                │   → POST http://127.0.0.1:18080/mcp │
│   │  │                │     (JSON-RPC tools/call)           │
│   │  │                │     Headers:                        │
│   │  │                │       Content-Type: application/json│
│   │  │                │       Accept: application/json      │
│   │  │                │     Body:                           │
│   │  │                │       {"jsonrpc":"2.0","id":N,      │
│   │  │                │        "method":"tools/call",       │
│   │  │                │        "params":{"name":"demo_echo",│
│   │  │                │          "arguments":              │
│   │  │                │            {"text":"hello"}}}      │
│   │  │                │                                    │
│   │  │                │     ──────────────────────→        │
│   │  │                │     Python FastMCP Server:         │
│   │  │                │     demo_echo("hello")             │
│   │  │                │     → return "demo_echo:hello"     │
│   │  │                │     日志: MCP_TOOL_CALL            │
│   │  │                │       tool=demo_echo text_length=5│
│   │  │                │     ←────────────────────────      │
│   │  │                │     Response:                      │
│   │  │                │       {"jsonrpc":"2.0","id":N,     │
│   │  │                │        "result":{"content":[{      │
│   │  │                │          "type":"text",             │
│   │  │                │          "text":"demo_echo:hello"  │
│   │  │                │        }],"isError":false}}        │
│   │  │                │                                    │
│   │  │                └── 审计日志:                         │
│   │  │                  EXTERNAL_CALL_AUDIT                 │
│   │  │                    adapter=MCP, success=true,        │
│   │  │                    target=demo-mcp/demo-mcp-tools,  │
│   │  │                    method=mcp.tools/call,           │
│   │  │                    attempt=1,                        │
│   │  │                    request={toolName:"demo_echo",   │
│   │  │                      arguments:{text:"hello"}},      │
│   │  │                    response="demo_echo:hello"        │
│   │  │                                                      │
│   │  │      [Observe] ReActAgent 获得工具结果:              │
│   │  │        demo_echo 工具返回: "demo_echo:hello"          │
│   │  │                                                      │
│   │  │      [Reason] LLM 基于工具结果生成最终回答:          │
│   │  │        → "工具调用完成！demo_echo 工具返回的结果是:  │
│   │  │           demo_echo:hello..."                        │
│   │  │                                                      │
│   │  │  └── toQueryResponse(rawResult, conversationId)     │
│   │  │      → result = {role:"assistant",                   │
│   │  │          content:"工具调用完成！...demo_echo:hello..."}│
│   │  └──────────────────────────────────────────────────────┘
│   │
├── extractInterruptFromResponse → empty (无中断, 正常完成)
└── return response → HTTP 200
```

### 2.3 HTTP 响应

```
QueryMvcController.handleQuery():
└── writeJson(response, 200, queryResponse)

HTTP 响应:
Status: 200 OK
Content-Type: application/json
Body:
{
  "result": {
    "role": "assistant",
    "content": "工具调用完成！`demo_echo` 工具返回的结果是：\n\ndemo_echo:hello\n\n可以看到，工具在传入的文本 `hello` 前面加上了 `demo_echo:` 这个固定前缀，然后返回了拼接后的结果。"
  },
  "conversation_id": "mcp-demo-c1"
}
```

---

## 三、完整调用栈总览图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              启动阶段                                       │
│                                                                             │
│  Python: fastmcp_server.py                                                  │
│  └→ FastMCP(stateless_http, json_response)                                  │
│     └→ 注册 demo_echo / demo_delay / demo_fail                             │
│     └→ Uvicorn 启动 :18080/mcp                                             │
│                                                                             │
│  Java: McpDemoApplication.main()                                           │
│  └→ Spring Boot 启动                                                       │
│     ├→ application-mcp.yml 绑定 → AgentCoreExternalProperties              │
│     │  (openjiuwen.service.external.mcp.servers)                           │
│     │                                                                       │
│     ├→ @Bean agentHandler():                                               │
│     │  ├→ LlmConfigResolver.resolveRequired() (apiconfig.json + yml)       │
│     │  ├→ ExampleReActAgentFactory.build() (ReActAgent + glm-5.2)         │
│     │  ├→ bindMcpServers() → AbilityManager.add(serverId+serverName)      │
│     │  │  日志: "Bound MCP server to agent ability manager"                │
│     │  └→ new JiuwenCoreAgentHandler(agent, externalSvcAdapterRegistrar)  │
│     │                                                                       │
│     └→ [ApplicationReadyEvent] handler.start()                            │
│        └→ ExternalSvcAdapterRegistrar.registerToRunner()                    │
│           ├→ registerMcpClientProviders()                                  │
│           │  → McpClientFactory.register("streamable_http",               │
│           │      StreamableHttpClient → DecoratingMcpClient 包装)          │
│           ├→ toCoreConfig(server) → 完整 McpServerConfig (含 URL)           │
│           ├→ Runner.resourceMgr().addMcpServer(config)                     │
│           │  ├→ McpClientFactory.create → DecoratingMcpClient              │
│           │  ├→ connect() → POST :18080/mcp (initialize)                   │
│           │  │  日志: EXTERNAL_CALL_AUDIT method=mcp.connect success=true  │
│           │  └→ listTools() → POST :18080/mcp (tools/list)                │
│           │     日志: EXTERNAL_CALL_AUDIT method=mcp.tools/list size=3     │
│           └→ 日志: "Registered external MCP server"                        │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                           请求阶段                                          │
│                                                                             │
│  POST /v1/query {"message":"请调用 demo_echo...text=hello","stream":false} │
│  └→ QueryMvcController → Orchestrator.query()                               │
│     └→ JiuwenCoreAgentHandler.query()                                      │
│        └→ Runner.runAgent() → ReActAgent 推理循环                          │
│           │                                                                 │
│           │  [Reason] LLM (glm-5.2) 推理                                   │
│           │  输入: system prompt + user msg + 工具列表                     │
│           │  (工具列表由 AbilityManager 从 demo-mcp 懒加载)                 │
│           │  → LLM 输出: tool_call(demo_echo, {text:"hello"})              │
│           │                                                                 │
│           │  [Act] MCP 工具调用                                             │
│           │  → DecoratingMcpClient.callTool("demo_echo", {text:"hello"})  │
│           │    │                                                            │
│           │    └→ ExternalCallExecutor (超时/重试/熔断/审计)                │
│           │       └→ StreamableHttpClient.callTool()                       │
│           │          → POST http://127.0.0.1:18080/mcp                     │
│           │            JSON-RPC: method=tools/call                         │
│           │            params: {name:"demo_echo", arguments:{text:"hello"}}│
│           │            │                                                    │
│           │            │  ──────────────→                                    │
│           │            │  Python FastMCP: demo_echo("hello")                │
│           │            │  → return "demo_echo:hello"                        │
│           │            │  日志: MCP_TOOL_CALL tool=demo_echo               │
│           │            │  ←──────────────                                    │
│           │            │  Response: {content:[{text:"demo_echo:hello"}]}  │
│           │            │                                                    │
│           │          ← EXTERNAL_CALL_AUDIT method=mcp.tools/call           │
│           │             success=true response="demo_echo:hello"            │
│           │                                                                 │
│           │  [Observe] 工具结果: "demo_echo:hello"                          │
│           │                                                                 │
│           │  [Reason] LLM 基于结果生成最终回答                              │
│           │  → "工具调用完成！demo_echo 工具返回的结果是..."                │
│           │                                                                 │
│           └→ toQueryResponse → QueryResponse{content:"..."}               │
│                                                                             │
│  HTTP 200 → 用户收到 "demo_echo:hello" 及 LLM 的解释                        │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 四、DecoratingMcpClient 装饰器模式

DecoratingMcpClient 是 MCP 工具调用的核心装饰器，包装了 Core 原始 Client，注入治理能力：

```
DecoratingMcpClient (装饰器)
├── 字段:
│   ├── McpServerConfig config        ← Server 配置 (serverId, serverName, serverPath, clientType)
│   ├── McpClient delegate            ← 被装饰的原始 Client (StreamableHttpClient)
│   ├── McpPolicy policy              ← 治理策略 (timeout/retry/circuitBreaker/audit)
│   └── ExternalCallExecutor executor ← 执行器 (超时/重试/熔断/审计)
│
├── connect(retryTimes, timeout)
│   └── executor.execute("mcp", "connect", true, () → delegate.connect(timeout))
│       日志: EXTERNAL_CALL_AUDIT adapter=MCP, method=mcp.connect
│
├── listTools(timeout)
│   └── executor.execute("mcp", "tools/list", true, () → delegate.listTools(timeout))
│       日志: EXTERNAL_CALL_AUDIT adapter=MCP, method=mcp.tools/list
│
├── callTool(toolName, arguments, timeout)        ← 工具调用入口
│   └── executor.execute("mcp", "tools/call", retryToolCalls,
│       request={toolName, arguments},
│       () → delegate.callTool(toolName, arguments, timeout))
│       日志: EXTERNAL_CALL_AUDIT adapter=MCP, method=mcp.tools/call
│
└── resolveTimeout(requested)
    → 如果调用方指定了有效 timeout, 用调用方的
    → 否则用 policy.timeoutMs (30000ms → 30s)
```

### ExternalCallExecutor 治理能力

```
ExternalCallExecutor.execute(operationType, method, shouldRetry, request, callable)
│
├── 熔断器检查 (CircuitState)
│   failure-threshold=3 → 连续3次失败后熔断, reset-timeout-ms=30000 (30s后半开)
│   如果熔断器开启 → 直接抛 MCP_CIRCUIT_OPEN
│
├── 超时控制
│   Future.get(policy.timeoutMs, TimeUnit.MILLISECONDS)
│   超时 → 抛 MCP_TIMEOUT, 记录失败到熔断器
│
├── 重试 (可选)
│   max=1, backoff-ms=200
│   retryToolCalls=false → tools/call 不重试
│   retry.max=1 → 其他操作 (connect/listTools) 重试1次
│
├── 审计日志
│   成功: EXTERNAL_CALL_AUDIT adapter=MCP, success=true, target=X, method=Y, attempt=N, elapsedMs=M
│   失败: EXTERNAL_CALL_AUDIT adapter=MCP, success=false, ...
│
└── 执行 callable (delegate 的实际方法)
```

---

## 五、McpServerConfig 两阶段配置

| 阶段 | 代码位置 | 设置的字段 | 用途 |
|---|---|---|---|
| **bindMcpServers** | `McpDemoApplication.java#L62` | `serverId`, `serverName` (仅此两个) | 注册到 Agent AbilityManager (让 LLM 知道有工具可用) |
| **registerToRunner** | `DefaultExternalSvcAdapterRegistrar.java#L237` (toCoreConfig) | `serverId`, `serverName`, `serverPath`, `clientType`, `authHeaders`, `params` | 注册到 Core Runner (实际连接 MCP Server) |

### toCoreConfig 的完整配置转换

```java
// DefaultExternalSvcAdapterRegistrar.java#L237
McpServerConfig config = McpServerConfig.builder().build();
config.setServerId("demo-mcp");                          // ← serverId
config.setServerName("demo-mcp-tools");                  // ← serverName
config.setServerPath("http://127.0.0.1:18080/mcp");     // ← URL!
config.setClientType("streamable_http");                 // ← normalizeClientType("streamable-http")
// streamable_http 特有: 注入 Accept header
config.getAuthHeaders().put("Accept", "application/json");
// 安全配置 (TLS/Auth) 应用到 authHeaders 和 authQueryParams
// params 包含额外参数
```

---

## 六、运行日志对应

| 日志 | 来源代码 | 含义 |
|---|---|---|
| `Bound MCP server to agent ability manager, serverId=demo-mcp` | `McpDemoApplication.bindMcpServers()` | Agent AbilityManager 注册了 MCP Server 名字 |
| `EXTERNAL_CALL_AUDIT adapter=MCP, method=mcp.connect, success=true, elapsedMs=144` | `DecoratingMcpClient.connect()` → `ExternalCallExecutor` | Runner 启动时连接 FastMCP |
| `EXTERNAL_CALL_AUDIT adapter=MCP, method=mcp.tools/list, success=true, response=Iterable(size=3)` | `DecoratingMcpClient.listTools()` | 获取到 3 个工具 |
| `Registered external MCP server, serverId=demo-mcp` | `DefaultExternalSvcAdapterRegistrar.registerToRunner()` | 完整配置注册到 Core Runner |
| `MCP_TOOL_CALL tool=demo_echo text_length=5` | `fastmcp_server.py` 的 `demo_echo()` | Python 侧工具被调用 (只记录长度, 不记录原文) |

---

## 七、关键源码文件索引

| 文件 | 角色 |
|---|---|
| [fastmcp_server.py](server/fastmcp_server.py) | Python 独立 MCP Server, 3 个工具 |
| [McpDemoApplication.java](src/main/java/com/openjiuwen/service/demo/example/mcp/McpDemoApplication.java) | Java 启动入口, @Bean agentHandler, bindMcpServers |
| [application-mcp.yml](application-mcp.yml) | MCP 配置: server-path, client-type, timeout, retry, circuit-breaker, audit |
| [AgentCoreExternalProperties.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/external/AgentCoreExternalProperties.java) | @ConfigurationProperties 绑定 openjiuwen.service.external.* |
| [DefaultExternalSvcAdapterRegistrar.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/external/DefaultExternalSvcAdapterRegistrar.java) | registerToRunner(): 注册 Client 工厂 + 完整 McpServerConfig 到 Runner |
| [DecoratingMcpClient.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/external/DecoratingMcpClient.java) | 装饰器: connect/listTools/callTool + 超时/重试/熔断/审计 |
| [ExternalCallExecutor.java](../../../../agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/external/ExternalCallExecutor.java) | 治理执行器: 熔断/超时/重试/审计日志 |
| [DefaultAgentCoreMcpClientDecoratorFactory.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/external/DefaultAgentCoreMcpClientDecoratorFactory.java) | 工厂: StreamableHttpClient → DecoratingMcpClient 包装 |
| [JiuwenCoreAgentHandler.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/agentfw/JiuwenCoreAgentHandler.java) | 适配器: buildInputs → Runner.runAgent → toQueryResponse |
| [A2AEnabledServeOrchestrator.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/A2AEnabledServeOrchestrator.java) | 编排器: agentHandler.query() |
| [QueryMvcController.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/controller/query/QueryMvcController.java) | HTTP 入口: POST /v1/query |
| [ExampleReActAgentFactory.java](../../support/src/main/java/com/openjiuwen/service/demo/example/support/ExampleReActAgentFactory.java) | 构建 ReActAgent, 配置 LLM |

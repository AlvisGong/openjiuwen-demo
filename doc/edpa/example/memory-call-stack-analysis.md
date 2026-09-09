# Memory Demo 端到端调用栈分析

> 以 "Please remember: I like drinking latte." 和 "What do I like to drink?" 两次请求为例，
> 结合运行日志和代码，严格追踪从 HTTP 请求到 mem0 云服务的完整调用栈。
>
> 验证时间: 2026-09-09，服务端口 8094 正常运行。

---

## 运行环境

| 组件 | 端口/地址 | 说明 |
|---|---|---|
| Java Memory Demo | localhost:8094 | MemoryDemoApplication, ReActAgent + Memory 生命周期 |
| mem0 Cloud API | https://api.mem0.ai | 长期记忆存储服务 (mem0 API key: m0-GOsemn...) |
| LLM (glm-5.2) | dashscope 远程 | OpenAI 兼容 API |

---

## 启动阶段

### 1.1 JVM 启动 -> Spring Boot 装配

```
java -jar agent-service-demo-memory-0.1.2.jar
文件: example/memory/src/main/java/.../MemoryDemoApplication.java#L42
环境变量:
  OPENJIUWEN_API_CONFIG = apiconfig.json (LLM 配置)
  MEM0_ENDPOINT = https://api.mem0.ai
  MEM0_API_KEY = m0-GOsemn...
|
+-- SpringApplication.run(MemoryDemoApplication.class, args)
|   加载配置 (application.yml -> base.yml -> memory.yml -> mem0.yml):
|   |
|   |   application-memory.yml:
|   |     server.port: 8094
|   |     openjiuwen.service.middleware.memory:
|   |       enabled: true               <- 启用记忆
|   |       request-scoped-session: true <- 请求级 session (user_id 传入 Core)
|   |       timeout-ms: 15000
|   |       retry: {max: 2, backoff-ms: 500}
|   |       circuit-breaker: {enabled: true, failure-threshold: 5, reset-timeout-ms: 120000}
|   |       audit: {enabled: true}
|   |
|   |   application-mem0.yml:
|   |     provider: mem0
|   |     endpoint: ${MEM0_ENDPOINT:https://api.mem0.ai}
|   |     encrypted-api-key: ${MEM0_API_KEY:}
|   |     rerank: false
|   |
|   +-- Spring Boot 自动装配:
|   |   LlmAutoConfiguration -> LlmConfigResolver
|   |   AgentServiceAutoConfiguration -> Controller/Lifecycle
|   |   MemoryAdaptersAutoConfiguration -> MemoryStore Bean (mem0 provider)
|   |     文件: MemoryAdaptersAutoConfiguration.java#L86
|   |     |
|   |     +-- @Bean memoryStore(providers, middlewareProperties, credentialDecryptor)
|   |     |   +-- credentialDecryptor.decrypt(encryptedApiKey, MEMORY_API_KEY)
|   |     |   |   -> 解密 API key: m0-GOsemn...
|   |     |   +-- MemoryStoreFactory(providers).create(apiKey, memory)
|   |     |   |   -> 路由到 Mem0MemoryStoreProvider (provider="mem0")
|   |     |   |   -> new Mem0MemoryStore(apiKey, memory, governedApi)
|   |     |   |     文件: Mem0MemoryStore.java#L47
|   |     |   |     +-- baseUrl = "https://api.mem0.ai"
|   |     |   |     +-- apiKey = "m0-GOsemn..."
|   |     |   |     +-- api = new GovernedMem0Api(endpoint, policy, "token", apiKey, "v3")
|   |     |   |           文件: GovernedMem0Api.java#L85
|   |     |   |           +-- executor = new ExternalCallExecutor("Memory", endpoint, policy, ...)
|   |     |   |           |   文件: ExternalCallExecutor.java#L53
|   |     |   |           |   -> 治理: timeout=15s, retry.max=2, circuitBreaker, audit
|   |     |   |           +-- httpClient = HttpClient.newBuilder().connectTimeout(15s).build()
|   |     |   |           +-- authHeaderMode = "token" (Authorization: Token xxx)
|   |     |   |           +-- pathStyle = "v3" (mem0 cloud: /v3/memories/...)
|   |     |   |
|   |     |   +-- 返回 MemoryStore 实例
|   |     |
|   |     +-- 同时创建 MemoryStoreMemoryProvider (Core MemoryProvider 桥接)
|   |       文件: MemoryAdaptersAutoConfiguration.java#L92
|   |       @Bean runtimeMemoryProvider(memoryStore, middlewareProperties)
|   |       -> new MemoryStoreMemoryProvider(memoryStore, middlewareProperties.getMemory())
|   |         文件: MemoryStoreMemoryProvider.java#L48
|   |         -> prefetch -> memoryStore.search()
|   |         -> syncTurn -> memoryStore.add()
|   |
|   +-- @Bean agentHandler(...):
|       文件: MemoryDemoApplication.java#L47
|       |
|       +-- memoryStoreProvider.getIfAvailable()
|       |   -> 返回 MemoryStore 实例 (mem0 provider, 非 null)
|       |
|       +-- llmConfigResolver.resolveRequired()
|       |   -> ResolvedLlmConfig (glm-5.2, dashscope, systemPrompt+MEMORY_SYSTEM_PROMPT_SUFFIX)
|       |   -> systemPrompt 追加了记忆提示:
|       |     "# Long-term Memory\nBefore each request, the service may include
|       |      a <memory-context> block. Use it as factual context for this user.
|       |      You may also call memory_search to find memories and memory_add to store
|       |      durable facts. Use memory_get... Use memory_delete..."
|       |
|       +-- ExampleReActAgentFactory.build(AGENT_ID, "Demo Memory Agent", "...", agentLlmConfig)
|       |   文件: ExampleReActAgentFactory.java#L40
|       |   |
|       |   +-- ReActAgentConfig.builder()
|       |   |   .promptTemplate([{role:"system", content: systemPrompt+MEMORY_SUFFIX}])
|       |   |   .maxIterations(config.getMaxIterations())
|       |   |   .configureModelClient(provider, apiKey, apiBase, modelName, sslVerify)
|       |   |   .configureContextEngine(null, contextWindowLimit, false)
|       |   |   -> 设置 ModelClientConfig (timeout, sslVerify)
|       |   |   -> 设置 ModelRequestConfig (temperature, topP)
|       |   |
|       |   +-- AgentCard.builder().id("demo-memory-agent").name("Demo Memory Agent").build()
|       |   +-- new ReActAgent(card) -> agent.configure(agentConfig) -> 返回 ReActAgent
|       |
|       +-- MemoryToolRegistrar.register(agent, memoryStore, true)
|       |   文件: MemoryToolRegistrar.java#L99
|       |   |
|       |   +-- registerSchemaTool(agent, memoryStore, SEARCH_SCHEMA, cards)
|       |   |   -> toolId = "external_memory_mem0_memory_search"
|       |   |   -> ToolCard: name="memory_search", description="Search memories by meaning."
|       |   |   -> LocalFunction: (inputs, kwargs) -> invokeTool(memoryStore, "memory_search", ...)
|       |   |   -> Runner.resourceMgr().addTool(tool, agent.getCard().getId(), true)
|       |   |   -> agent.getAbilityManager().add(tool.getCard())
|       |   |
|       |   +-- registerSchemaTool(agent, memoryStore, ADD_SCHEMA, cards)
|       |   |   -> toolId = "external_memory_mem0_memory_add"
|       |   |   -> 同上注册流程
|       |   |
|       |   +-- registerSchemaTool(agent, memoryStore, GET_SCHEMA, cards)
|       |   |   -> toolId = "external_memory_mem0_memory_get"
|       |   |
|       |   +-- registerSchemaTool(agent, memoryStore, DELETE_SCHEMA, cards)
|       |       -> toolId = "external_memory_mem0_memory_delete"
|       |
|       |   日志:
|       |     "add resource succeed, id=external_memory_mem0_memory_search, type=tool"
|       |     "add resource succeed, id=external_memory_mem0_memory_add, type=tool"
|       |     "add resource succeed, id=external_memory_mem0_memory_get, type=tool"
|       |     "add resource succeed, id=external_memory_mem0_memory_delete, type=tool"
|       |
|       +-- new MemoryAwareJiuwenCoreAgentHandler(agent, externalRegistrar, requestScopedSession=true)
|       |   文件: MemoryAwareJiuwenCoreAgentHandler.java#L17
|       |   -> 继承 JiuwenCoreAgentHandler
|       |   -> 覆盖 useRequestScopedSession(ServeRequest) -> true
|       |     -> 使 Runner 使用 AgentSessionApi.create() 而非裸 sessionId
|       |     -> user_id 等环境变量传入 Core session, 记忆工具可按用户访问
|       |
|       +-- memoryProvider instanceof MemoryStoreMemoryProvider -> true
|           -> new MemoryLifecycleAgentHandler(coreHandler, runtimeMemoryProvider)
|           -> 包装链: MemoryLifecycle(prefetch+syncTurn) -> MemoryAwareHandler -> JiuwenCoreHandler
|
+-- [ApplicationReadyEvent] handler.start()
|   -> JiuwenCoreAgentHandler.start()
|   文件: JiuwenCoreAgentHandler.java#L155
|   +-- externalSvcAdapterRegistrar.registerToRunner()
|   +-- Runner.start() -> "agent_loaded=true"
|
+-- 等待 HTTP 请求
```

### 1.2 Bean 关系图

```
Spring ApplicationContext
|
+-- LlmConfigResolver (from LlmAutoConfiguration)
|   -> resolveRequired() -> ResolvedLlmConfig
|
+-- MemoryStore (from MemoryAdaptersAutoConfiguration.memoryStore)
|   +-- Mem0MemoryStore
|       +-- GovernedMem0Api
|           +-- ExternalCallExecutor (timeout/retry/circuitBreaker/audit)
|
+-- MemoryProvider (from MemoryAdaptersAutoConfiguration.runtimeMemoryProvider)
|   +-- MemoryStoreMemoryProvider
|       +-- prefetch() -> MemoryStore.search()
|       +-- syncTurn() -> MemoryStore.add()
|
+-- AgentHandler (from MemoryDemoApplication.agentHandler)
    +-- MemoryLifecycleAgentHandler
        +-- delegate: MemoryAwareJiuwenCoreAgentHandler
        |   +-- super: JiuwenCoreAgentHandler
        |       +-- agent: ReActAgent
        |           +-- 工具: memory_search / memory_add / memory_get / memory_delete
        |           +-- LLM: glm-5.2 via dashscope
        +-- memoryProvider: MemoryStoreMemoryProvider
```

---

## 请求1：写入记忆（conversation_id=mem-test-c1, user_id=alice）

### 实际请求

```
POST http://localhost:8094/v1/query
Body: {"conversation_id":"mem-test-c1","user_id":"alice","message":"Please remember: I like drinking latte.","stream":false}
```

### 实际响应

```json
{
    "result": {
        "role": "assistant",
        "content": "Got it! I've confirmed that your preference for latte is safely stored in my memory."
    },
    "conversation_id": "mem-test-c1"
}
```

### 2.1 HTTP 入口 -> Orchestrator

```
POST /v1/query
|
+-- QueryMvcController.queryV1(rawBody, headers, servletRequest, response)
    文件: QueryMvcController.java#L78
    |
    +-- objectMapper.readValue(rawBody, QueryRequest.class)
    |   -> QueryRequest{conversationId:"mem-test-c1", userId:"alice", message:"Please remember...", stream:false}
    |
    +-- QueryIngressSupport.validateAndBuild(request, headers)
    |   文件: QueryIngressSupport.java#L50
    |   |
    |   +-- request.normalizeMessages()
    |   |   -> 将 message 简写转为 messages: [{role:"user", content:"Please remember..."}]
    |   +-- request.getConversationId() 非空 -> 通过校验
    |   +-- applyTenantHeaders(request, headers)
    |   |   -> 如果有 X-User-ID/X-Space-ID/X-Tenant-ID 头则覆盖
    |   +-- ServeRequest.fromQueryRequest(request)
    |       文件: ServeRequest.java#L37
    |       -> ServeRequest{conversationId, messages, userId, spaceId, tenantId, stream}
    |
    +-- validateAndBuildMetadata(serveRequest, headers, servletRequest, rawBody)
    |   -> 构建 metadata: {headers:{...}, query:{...}, path:"/v1/query", body:{...}}
    |
    +-- isAgentReady() -> true
    |
    +-- request.isStream() -> false -> 同步路径
    |
    +-- orchestrator.query(serveRequest)
        文件: A2AEnabledServeOrchestrator.java#L131
```

### 2.2 Orchestrator -> MemoryLifecycleAgentHandler

```
A2AEnabledServeOrchestrator.query(request)
文件: A2AEnabledServeOrchestrator.java#L131
|
+-- log.info("Orchestrator query START conversationId={}", "mem-test-c1")
|
+-- agentHandler.prepareTask(request)
|   -> MemoryLifecycleAgentHandler 没有覆盖 prepareTask
|   -> JiuwenCoreAgentHandler.prepareTask (默认返回 Optional.empty())
|   -> taskToken = Optional.empty()
|
+-- syncResumePending(current, NOOP_OBSERVER)
|   -> 检查是否有 pending 的 A2A shadow task -> 无
|   -> 返回 QueryResumeResult(response=null, request=Optional.of(current))
|
+-- agentHandler.query(current)
    |
    |  agentHandler 实际是 MemoryLifecycleAgentHandler (包装层)
    |  文件: MemoryLifecycleAgentHandler.java#L62
    |
    +-- [Step 1] memoryScope(request)
    |   文件: MemoryLifecycleAgentHandler.java#L167
    |   |
    |   +-- scope = new LinkedHashMap<>()
    |   +-- scope.putAll(request.getMetadata()) -> metadata 中的 headers/query/path/body
    |   +-- putIfNotBlank(scope, "conversation_id", "mem-test-c1")
    |   +-- putIfNotBlank(scope, "session_id", "mem-test-c1")
    |   +-- putIfNotBlank(scope, "user_id", "alice")
    |   +-- -> scope = {conversation_id:"mem-test-c1", user_id:"alice", session_id:"mem-test-c1", ...}
    |
    +-- [Step 2] withPrefetchedMemory(request, "Please remember: I like drinking latte.", scope)
    |   文件: MemoryLifecycleAgentHandler.java#L107
    |   |
    |   +-- prefetch("Please remember: I like drinking latte.", scope)
    |   |   文件: MemoryLifecycleAgentHandler.java#L116
    |   |   |
    |   |   +-- memoryProvider == null -> false
    |   |   +-- memoryProvider.isAvailable() -> true (mem0 store apiKey 非空)
    |   |   +-- query == null || query.isBlank() -> false
    |   |   |
    |   |   +-- initializeProvider(scope)
    |   |   |   文件: MemoryLifecycleAgentHandler.java#L154
    |   |   |   +-- memoryProvider.isInitialized() -> false (首次调用)
    |   |   |   +-- initialized.compareAndSet(false, true) -> true
    |   |   |   +-- memoryProvider.initialize(scope)
    |   |   |       文件: MemoryStoreMemoryProvider.java#L63
    |   |   |       -> hasInitialized = true
    |   |   |
    |   |   +-- memoryProvider.prefetch("Please remember: I like drinking latte.", scope)
    |   |       文件: MemoryStoreMemoryProvider.java#L78
    |   |       |
    |   |       +-- isAvailable() -> true
    |   |       +-- query 非空
    |   |       +-- MemorySearchRequest(scope, query, topK=5, rerank=false, options)
    |   |       |
    |   |       +-- memoryStore.search(request)
    |   |           文件: Mem0MemoryStore.java#L91
    |   |           |
    |   |           +-- ensureAvailable() -> apiKey 非空 -> 通过
    |   |           +-- query 非空 -> 通过
    |   |           +-- topK = normalizeTopK(5) -> 5
    |   |           +-- shouldRerank = false
    |   |           |
    |   |           +-- api.searchMemories(baseUrl, apiKey, query, options)
    |   |               文件: GovernedMem0Api.java#L244
    |   |               |
    |   |               +-- body = {query:"Please remember...", filters:{user_id:"alice"}, rerank:false, top_k:5}
    |   |               |
    |   |               +-- executor.execute("memory", "search", shouldRetry=true, () -> send(...))
    |   |                   文件: ExternalCallExecutor.java#L89
    |   |                   |
    |   |                   +-- circuitKey = "memory.search"
    |   |                   +-- maxAttempts = 1 + 2 = 3 (retry.max=2)
    |   |                   +-- ensureCircuitClosed() -> 无熔断
    |   |                   |
    |   |                   +-- callWithTimeout(callable, "memory", "search")
    |   |                       +-- future = timeoutExecutor.submit(callable)
    |   |                       |   -> callable 执行:
    |   |                       |     HttpClient.send(
    |   |                       |       POST https://api.mem0.ai/v3/memories/search/
    |   |                       |       Headers: Authorization: Token m0-GOsemn...
    |   |                       |       Body: {"query":"Please remember...","filters":{"user_id":"alice"},"rerank":false,"top_k":5}
    |   |                       |     )
    |   |                       +-- future.get(15000, MILLISECONDS)
    |   |                           -> 返回: {"results": []} (alice 还没有记忆)
    |   |                   |
    |   |                   +-- recordSuccess("memory.search")
    |   |                   +-- auditSuccess("memory", "search", ...)
    |   |                       日志: EXTERNAL_CALL_AUDIT adapter=Memory, success=true, method=memory.search, ...
    |   |                   |
    |   |                   <- 返回: List<Map> = []
    |   |                   |
    |   |   <- formatPrefetch([]) -> "" (空)
    |   |   <- memoryContext = ""
    |   |   <- memoryContext.isBlank() -> true -> 返回原始 request (不修改消息)
    |   |
    |   +-- -> request 原样返回 (effectiveRequest = request)
    |
    +-- [Step 3] delegate.query(effectiveRequest)
    |   |
    |   |  delegate 是 MemoryAwareJiuwenCoreAgentHandler -> JiuwenCoreAgentHandler
    |   |  文件: JiuwenCoreAgentHandler.java#L228
    |   |
    |   |  query() 方法体 (L228-L302):
    |   |  +-- FutureTask<QueryResponse> execution = new FutureTask<>(() -> {
    |   |  |
    |   |  +-- supportsInvoke(agent) -> true (ReActAgent 有 invoke 方法)
    |   |  |
    |   |  +-- executeAgent(buildInputs(request), runnerSession(request))
    |   |  |   文件: JiuwenCoreAgentHandler.java#L297
    |   |  |   |
    |   |  |   |  executeAgent 调用 Runner.runAgent(agent, inputs, session, null)
    |   |  |   |
    |   |  |   +-- buildInputs(request)
    |   |  |   |   文件: JiuwenCoreAgentHandler.java#L449
    |   |  |   |   |
    |   |  |   |   +-- inputs = {
    |   |  |   |       conversation_id: "mem-test-c1",
    |   |  |   |       messages: [{role:"user", content:"Please remember: I like drinking latte."}],
    |   |  |   |       user_id: "alice",
    |   |  |   |       space_id: null,
    |   |  |   |     }
    |   |  |   |   +-- metadata.get("runtime.remoteToolResults") -> null (无 A2A resume)
    |   |  |   |   +-- inputs.put("query", "Please remember: I like drinking latte.")
    |   |  |   |
    |   |  |   +-- runnerSession(request)
    |   |  |   |   文件: JiuwenCoreAgentHandler.java#L500
    |   |  |   |   |
    |   |  |   |   +-- sessionId = "mem-test-c1"
    |   |  |   |   +-- hasAgentCard(agent) -> true (ReActAgent.getCard())
    |   |  |   |   +-- useRequestScopedSession(request) -> true (MemoryAware 覆盖)
    |   |  |   |   |   -> 不走 sessionId 字符串路径
    |   |  |   |   +-- card = agent.getCard() (AgentCard)
    |   |  |   |   +-- sessionEnvs(request)
    |   |  |   |   |   +-- readAgentConfigEnvs(agent) -> agent config 中的 envs
    |   |  |   |   |   +-- requestEnvs(request) -> {conversation_id, user_id, space_id, tenant_id}
    |   |  |   |   |   +-- 合并: {agent_config_envs..., conversation_id:"mem-test-c1", user_id:"alice", ...}
    |   |  |   |   +-- AgentSessionApi.create("mem-test-c1", envs, card, [StreamMode.OUTPUT])
    |   |  |   |       -> 创建 Core session, user_id 传入 session envs
    |   |  |   |
    |   |  |   +-- Runner.runAgent(agent, inputs, session, null)
    |   |  |       -> ReActAgent.invoke(inputs, session)
    |   |  |       -> ReAct 推理循环 (最多 maxIterations 轮):
    |   |  |         |
    |   |  |         |  -- Iteration 1/5 --
    |   |  |         |  [Reason] LLM 推理:
    |   |  |         |    system: "...# Long-term Memory\n...memory_add to store durable facts..."
    |   |  |         |    user: "Please remember: I like drinking latte."
    |   |  |         |    可用工具: [memory_search, memory_add, memory_get, memory_delete]
    |   |  |         |
    |   |  |         |    -> LLM 输出: tool_call(memory_add, {content:"User likes drinking latte"})
    |   |  |         |
    |   |  |         |  [Act] 执行 memory_add 工具:
    |   |  |         |    MemoryToolRegistrar.invokeTool(memoryStore, "memory_add", inputs, scope)
    |   |  |         |    文件: MemoryToolRegistrar.java#L160
    |   |  |         |    |
    |   |  |         |    +-- invokeAdd(memoryStore, inputs, scope)
    |   |  |         |        文件: MemoryToolRegistrar.java#L203
    |   |  |         |        |
    |   |  |         |        +-- content = requiredString(inputs, "content") -> "User likes drinking latte"
    |   |  |         |        +-- scope = resolveScope(kwargs)
    |   |  |         |        |   -> 从 kwargs 中提取 user_id, conversation_id 等
    |   |  |         |        +-- memoryStore.add(MemoryAddRequest(scope, [{role:"user", content:"..."}], {infer:false}))
    |   |  |         |            文件: Mem0MemoryStore.java#L69
    |   |  |         |            |
    |   |  |         |            +-- toMem0Messages([{role:"user", content:"User likes drinking latte"}])
    |   |  |         |            |   -> [{role:"user", content:"User likes drinking latte"}]
    |   |  |         |            +-- shouldInfer = false
    |   |  |         |            +-- api.addMemoryRecords(baseUrl, apiKey, messages, toMem0Scope(scope), false)
    |   |  |         |                文件: GovernedMem0Api.java#L282
    |   |  |         |                |
    |   |  |         |                +-- body = {user_id:"alice", messages:[...], infer:false}
    |   |  |         |                +-- executor.execute("memory", "add", shouldRetry, () -> send(...))
    |   |  |         |                    |
    |   |  |         |                    +-- callWithTimeout -> HttpClient.send(
    |   |  |         |                        POST https://api.mem0.ai/v3/memories/add/
    |   |  |         |                        Headers: Authorization: Token m0-GOsemn...
    |   |  |         |                        Body: {"messages":[{"role":"user","content":"User likes drinking latte"}],
    |   |  |         |                               "user_id":"alice","infer":false}
    |   |  |         |                      )
    |   |  |         |                      -> 返回: {"results": [...]} (记忆写入成功)
    |   |  |         |
    |   |  |         |                日志: EXTERNAL_CALL_AUDIT adapter=Memory, success=true,
    |   |  |         |                       method=memory.add, elapsedMs=~1500, ...
    |   |  |         |
    |   |  |         |  -- Iteration 2/5 --
    |   |  |         |  [Reason] LLM 基于工具结果生成最终回答:
    |   |  |         |    -> LLM 输出: "Got it! I've confirmed that your preference for latte is safely stored..."
    |   |  |         |
    |   |  |         +-- -> 返回 rawResult
    |   |  |
    |   |  +-- toQueryResponse(rawResult, "mem-test-c1")
    |   |      文件: JiuwenCoreAgentHandler.java#L334
    |   |      -> result = {role:"assistant", content:"Got it! I've confirmed..."}
    |   |      -> return new QueryResponse(result, "mem-test-c1")
    |   |
    |   +-- execution.run() -> execution.get() -> 返回 QueryResponse
    |
    +-- [Step 4] syncTurn(originalUserQuery, assistantText(response), scope)
    |   文件: MemoryLifecycleAgentHandler.java#L134
    |   |
    |   +-- assistantText(response)
    |   |   文件: MemoryLifecycleAgentHandler.java#L223
    |   |   -> response.getResult() -> {role:"assistant", content:"Got it!..."}
    |   |   -> firstPresent(map, "content", "output", "response") -> "Got it! I've confirmed..."
    |   |   -> assistantText = "Got it! I've confirmed that your preference for latte..."
    |   |
    |   +-- memoryProvider.syncTurn("Please remember: I like drinking latte.", "Got it!...", scope)
    |       文件: MemoryStoreMemoryProvider.java#L89
    |       |
    |       +-- isAvailable() -> true
    |       +-- messages = [{role:"user", content:"Please remember..."}, {role:"assistant", content:"Got it!..."}]
    |       +-- options = {infer:true}  <- 让 mem0 自动提取记忆
    |       +-- memoryStore.add(MemoryAddRequest(scope, messages, {infer:true}))
    |           -> POST https://api.mem0.ai/v3/memories/add/
    |             Body: {messages:[{user},{assistant}], user_id:"alice", infer:true}
    |           -> 返回: 记忆写入成功
    |
    |       日志: EXTERNAL_CALL_AUDIT adapter=Memory, success=true, method=memory.add, ...
    |
    +-- 返回 QueryResponse

+-- batchCoordinator.completeResume(current) -> 无 A2A resume
+-- extractInterruptFromResponse(response) -> 空 (无 _interrupt)
+-- return response -> 回到 QueryMvcController

-> writeJson(response, 200, queryResponse)
-> HTTP 200: {"result":{"role":"assistant","content":"Got it!..."},"conversation_id":"mem-test-c1"}
```

### 2.3 请求1中的 mem0 API 调用

| 序号 | HTTP 方法 | 路径 | 触发者 | infer | 说明 |
|---|---|---|---|---|---|
| 1 | POST | /v3/memories/search/ | prefetch (生命周期) | - | 搜索 alice 的记忆，返回空 |
| 2 | POST | /v3/memories/add/ | LLM tool_call memory_add | false | LLM 主动写入 "User likes drinking latte" |
| 3 | POST | /v3/memories/add/ | syncTurn (生命周期) | true | 请求后写入本轮 user+assistant 对话 |

---

## 请求2：跨会话查询（conversation_id=mem-test-c2, 同一 user_id=alice）

### 实际请求

```
POST http://localhost:8094/v1/query
Body: {"conversation_id":"mem-test-c2","user_id":"alice","message":"What do I like to drink?","stream":false}
```

### 实际响应

```json
{
    "result": {
        "role": "assistant",
        "content": "You like drinking **latte**!"
    },
    "conversation_id": "mem-test-c2"
}
```

### 3.1 完整调用栈

```
POST /v1/query
|
+-- QueryMvcController -> Orchestrator -> MemoryLifecycleAgentHandler.query()
    |
    +-- [Step 1] memoryScope(request) -> {user_id:"alice", conversation_id:"mem-test-c2", session_id:"mem-test-c2", ...}
    |
    +-- [Step 2] withPrefetchedMemory(request, "What do I like to drink?", scope)
    |   |
    |   +-- prefetch("What do I like to drink?", scope)
    |   |   |
    |   |   +-- initializeProvider(scope)
    |   |   |   +-- memoryProvider.isInitialized() -> true (已在请求1中初始化)
    |   |   |
    |   |   +-- memoryProvider.prefetch("What do I like to drink?", scope)
    |   |       文件: MemoryStoreMemoryProvider.java#L78
    |   |       |
    |   |       +-- MemorySearchRequest(scope, "What do I like to drink?", topK=5, rerank=false, options)
    |   |       +-- memoryStore.search(request)
    |   |           +-- api.searchMemories(...)
    |   |               -> POST https://api.mem0.ai/v3/memories/search/
    |   |                 Body: {"query":"What do I like to drink?","filters":{"user_id":"alice"},"rerank":false,"top_k":5}
    |   |               -> 返回: {"results": [{"memory":"User likes drinking latte", ...}]}
    |   |               <- 检索到记忆!
    |   |               |
    |   |           日志: EXTERNAL_CALL_AUDIT adapter=Memory, success=true, method=memory.search, ...
    |   |           |
    |   |   <- formatPrefetch(records)
    |   |     文件: MemoryStoreMemoryProvider.java#L105
    |   |     -> "## Long-term Memory\n- User likes drinking latte"
    |   |   <- memoryContext = "## Long-term Memory\n- User likes drinking latte"
    |   |
    |   +-- enrichLastUserMessage(messages, memoryContext, originalUserQuery)
    |       文件: MemoryLifecycleAgentHandler.java#L180
    |       |
    |       +-- copyMessages(source) -> 深拷贝消息列表
    |       +-- 从后往前找到最后一条 role="user" 的消息
    |       +-- message.put("content", formatUserMessage(memoryContext, originalUserQuery))
    |           文件: MemoryLifecycleAgentHandler.java#L193
    |           |
    |           +-- 修改后 content:
    |               <memory-context>
    |               ## Long-term Memory
    |               - User likes drinking latte
    |               </memory-context>
    |
    |               <user-message>
    |               What do I like to drink?
    |               </user-message>
    |
    +-- [Step 3] delegate.query(effectiveRequest)
    |   |
    |   |  JiuwenCoreAgentHandler.query(effectiveRequest)
    |   |  +-- buildInputs -> inputs={
    |   |  |     conversation_id:"mem-test-c2",
    |   |  |     messages:[{role:"user", content:"<memory-context>..."}],
    |   |  |     user_id:"alice",
    |   |  |     query:"<memory-context>..."
    |   |  |   }
    |   |  +-- runnerSession -> AgentSessionApi.create("mem-test-c2", envs={user_id:"alice",...}, card, [OUTPUT])
    |   |  +-- Runner.runAgent -> ReActAgent.invoke:
    |   |      |
    |   |      |  -- Iteration 1/5 --
    |   |      |  [Reason] LLM 推理:
    |   |      |    system: "...# Long-term Memory\n...Use it as factual context for this user..."
    |   |      |    user: "<memory-context>\n## Long-term Memory\n- User likes drinking latte\n</memory-context>\n
    |   |      |           <user-message>What do I like to drink?</user-message>"
    |   |      |    可用工具: [memory_search, memory_add, memory_get, memory_delete]
    |   |      |
    |   |      |    -> LLM 看到 <memory-context> 中的 "latte", 直接回答 (无需调用工具):
    |   |      |      "You like drinking **latte**!"
    |   |      |
    |   |      +-- toQueryResponse -> QueryResponse{content:"You like drinking **latte**!"}
    |   |
    |   +-- 返回 QueryResponse
    |
    +-- [Step 4] syncTurn("What do I like to drink?", "You like drinking **latte**!", scope)
    |   +-- memoryProvider.syncTurn(...)
    |       -> memoryStore.add(MemoryAddRequest(scope, [user+assistant], {infer:true}))
    |       -> POST https://api.mem0.ai/v3/memories/add/ (infer=true)
    |       日志: EXTERNAL_CALL_AUDIT adapter=Memory, success=true, method=memory.add, ...
    |
    +-- 返回 QueryResponse

-> HTTP 200: {"result":{"role":"assistant","content":"You like drinking **latte**!"},"conversation_id":"mem-test-c2"}
```

### 3.2 请求2中的 mem0 API 调用

| 序号 | HTTP 方法 | 路径 | 触发者 | infer | 说明 |
|---|---|---|---|---|---|
| 1 | POST | /v3/memories/search/ | prefetch (生命周期) | - | 搜索 alice 的记忆，返回 "User likes drinking latte" |
| 2 | POST | /v3/memories/add/ | syncTurn (生命周期) | true | 请求后写入本轮 user+assistant 对话 |

> 注意：请求2中 LLM 没有调用任何记忆工具，因为 prefetch 已经注入了记忆上下文，
> LLM 直接从 `<memory-context>` 中获取了答案。这是两条路径的设计意图：
> 生命周期路径自动提供记忆，LLM 工具路径作为补充。

---

## 完整调用栈总览图

```
+--------------------------------------------------------------------------+
|                    请求1: 写入记忆 (mem-test-c1)                          |
|                                                                          |
|  POST /v1/query (user_id=alice, "I like drinking latte")                 |
|  +-> QueryMvcController.handleQuery()                                     |
|     +-> QueryIngressSupport.validateAndBuild() -> ServeRequest            |
|     +-> A2AEnabledServeOrchestrator.query()                               |
|        +-> MemoryLifecycleAgentHandler.query()                           |
|           |                                                              |
|           +-> [prefetch] memoryStore.search()                            |
|           |  -> MemoryStoreMemoryProvider.prefetch()                     |
|           |    -> Mem0MemoryStore.search()                                |
|           |      -> GovernedMem0Api.searchMemories()                     |
|           |        -> ExternalCallExecutor.execute("memory","search")     |
|           |          -> POST mem0.ai/v3/memories/search/                  |
|           |  日志: EXTERNAL_CALL_AUDIT method=memory.search              |
|           |  -> 空 (alice 是新用户)                                       |
|           |                                                              |
|           +-> [delegate.query] JiuwenCoreAgentHandler                    |
|           |  -> buildInputs -> Runner.runAgent -> ReActAgent.invoke       |
|           |     -- Iteration 1 --                                        |
|           |     -> LLM: tool_call(memory_add, {content:"User likes..."}) |
|           |        -> MemoryToolRegistrar.invokeAdd()                     |
|           |          -> Mem0MemoryStore.add()                            |
|           |            -> GovernedMem0Api.addMemoryRecords()            |
|           |              -> ExternalCallExecutor.execute("memory","add") |
|           |                -> POST mem0.ai/v3/memories/add/ (infer=false)|
|           |     -- Iteration 2 --                                        |
|           |     -> LLM: "Got it! I've confirmed..." (最终回答)           |
|           |                                                              |
|           +-> [syncTurn] memoryProvider.syncTurn()                      |
|              -> MemoryStoreMemoryProvider.syncTurn()                     |
|                -> Mem0MemoryStore.add()                                   |
|                  -> GovernedMem0Api.addMemoryRecords()                   |
|                    -> ExternalCallExecutor.execute("memory","add")        |
|                      -> POST mem0.ai/v3/memories/add/ (infer=true)       |
|                                                                          |
|  <- 用户收到: "Got it! I've confirmed that your preference..."           |
+--------------------------------------------------------------------------+

+--------------------------------------------------------------------------+
|                请求2: 跨会话查询 (mem-test-c2, 同一 user_id)              |
|                                                                          |
|  POST /v1/query (user_id=alice, "What do I like to drink?")             |
|  +-> QueryMvcController -> Orchestrator -> MemoryLifecycleAgentHandler   |
|     |                                                                    |
|     +-> [prefetch] memoryStore.search()                                  |
|     |  -> POST mem0.ai/v3/memories/search/                               |
|     |  -> [{memory:"User likes drinking latte"}]  <- 检索到记忆!          |
|     |                                                                    |
|     +-> enrichLastUserMessage: 注入 <memory-context>                     |
|     |  修改后 user message:                                              |
|     |    <memory-context>                                                 |
|     |    ## Long-term Memory                                              |
|     |    - User likes drinking latte                                      |
|     |    </memory-context>                                                |
|     |    <user-message>What do I like to drink?</user-message>           |
|     |                                                                    |
|     +-> [delegate.query] JiuwenCoreAgentHandler -> Runner -> ReActAgent  |
|     |  -> LLM: 看到 <memory-context> 中的 "latte"                         |
|     |     -> 直接回答 (无需调用工具): "You like drinking **latte**!"       |
|     |                                                                    |
|     +-> [syncTurn] memoryProvider.syncTurn() -> memoryStore.add()        |
|        -> POST mem0.ai/v3/memories/add/ (infer=true)                     |
|                                                                          |
|  <- 用户收到: "You like drinking **latte**!"                              |
+--------------------------------------------------------------------------+
```

---

## AgentHandler 包装链

```
MemoryLifecycleAgentHandler (最外层)
|  职责: 请求前 prefetch + 请求后 syncTurn
|  +-- query() -> memoryScope -> withPrefetchedMemory -> delegate.query -> syncTurn
|  +-- streamQuery() -> 同上，但在 onComplete 回调中执行 syncTurn
|
+-- delegate: MemoryAwareJiuwenCoreAgentHandler
    |  职责: 覆盖 useRequestScopedSession() -> true
    |  -> 使 Runner 使用 AgentSessionApi.create() 创建请求级 session
    |  -> user_id 等环境变量传入 Core session envs
    |  -> 记忆工具可从 session envs 获取 user_id
    |
    +-- super: JiuwenCoreAgentHandler
        |  职责: buildInputs -> runnerSession -> Runner.runAgent -> toQueryResponse
        |
        |  query() 方法:
        |  +-- FutureTask(() -> {
        |  |   +-- supportsInvoke(agent) -> true (ReActAgent 有 invoke)
        |  |   +-- executeAgent(inputs, session)
        |  |   |   -> Runner.runAgent(agent, inputs, session, null)
        |  |   |     -> ReActAgent.invoke(inputs, session)
        |  |   +-- toQueryResponse(rawResult, convId)
        |  |       -> {role:"assistant", content:"..."}
        |  |  })
        |  +-- execution.run() -> execution.get()
        |
        |  buildInputs():
        |  +-- inputs = {conversation_id, messages, user_id, space_id, tenant_id}
        |  +-- metadata.get("runtime.remoteToolResults")
        |  |   -> null -> inputs.put("query", lastUserQuery)
        |  |   -> 非 null -> inputs.put("query", InteractiveInput)
        |  +-- 返回 inputs
        |
        |  runnerSession():
        |  +-- sessionId = conversationId
        |  +-- useRequestScopedSession -> true
        |  |   -> AgentSessionApi.create(sessionId, envs, card, [StreamMode.OUTPUT])
        |  +-- envs = {agent_config_envs..., conversation_id, user_id, space_id, tenant_id}
        |
        +-- agent: ReActAgent (agent-core-java)
            +-- 配置: ReActAgentConfig (promptTemplate, maxIterations, modelClient, contextEngine)
            +-- 工具: memory_search / memory_add / memory_get / memory_delete
            |   (由 MemoryToolRegistrar 注册, 通过 MemoryStore 调用 mem0)
            +-- LLM: glm-5.2 via dashscope OpenAI 兼容 API
```

---

## 两条记忆路径

| 路径 | 触发者 | 代码 | 调用链 | 执行时机 |
|---|---|---|---|---|
| **生命周期路径** | MemoryLifecycleAgentHandler | prefetch + syncTurn | MemoryProvider.prefetch/syncTurn -> MemoryStoreMemoryProvider -> MemoryStore.search/add -> GovernedMem0Api -> ExternalCallExecutor -> HTTP -> mem0 API | 每轮请求**自动**执行，不需要 LLM 决策 |
| **LLM 工具路径** | ReActAgent LLM 决策 | tool_call | MemoryToolRegistrar.invokeTool -> MemoryStore.search/add/get/delete -> GovernedMem0Api -> ExternalCallExecutor -> HTTP -> mem0 API | LLM **主动**决定是否调用，参数由 LLM 生成 |

### 职责分离设计

- **生命周期路径** (MemoryLifecycleAgentHandler + MemoryStoreMemoryProvider):
  - prefetch: 请求前**自动搜索**相关记忆，注入 `<memory-context>` 到 user message
  - syncTurn: 请求后**自动写入**本轮 user+assistant 对话，让 mem0 提取记忆 (infer=true)
  - 不需要 LLM 参与，对 LLM 透明

- **LLM 工具路径** (MemoryToolRegistrar -> MemoryStore):
  - LLM 可主动调用 memory_search 搜索记忆
  - LLM 可主动调用 memory_add 写入事实 (如用户明确说 "记住这个")
  - LLM 可主动调用 memory_get/delete 管理记忆
  - 参数由 LLM 生成，调用时机由 LLM 决策

---

## mem0 Cloud API 路径

| 操作 | HTTP | 路径 | Body 关键字段 |
|---|---|---|---|
| search | POST | /v3/memories/search/ | query, filters{user_id}, rerank, top_k |
| add | POST | /v3/memories/add/ | messages[{role,content}], user_id, infer |
| get | GET | /v1/memories/{memory_id}/ | - |
| delete | DELETE | /v1/memories/{memory_id}/ | - |

> 路径风格由 `pathStyle` 配置决定:
> - `v3` (默认, mem0 Cloud): /v3/memories/search/, /v3/memories/add/, /v1/memories/{id}/
> - `open` (自建 Mem0 OSS): /search, /memories, /memories/{id}

---

## 外部调用治理 (ExternalCallExecutor)

所有 mem0 HTTP 调用都经过 `ExternalCallExecutor` 统一治理:

| 治理能力 | 配置项 | 默认值 | 代码位置 |
|---|---|---|---|
| **timeout** | timeout-ms | 15000ms (memory) | ExternalCallExecutor.callWithTimeout() |
| **retry** | retry.max / retry.backoff-ms | 2 / 500ms | ExternalCallExecutor.execute() 循环 |
| **circuit breaker** | circuit-breaker.enabled / failure-threshold / reset-timeout-ms | true / 5 / 120000ms | ExternalCallExecutor.ensureCircuitClosed() |
| **audit** | audit.enabled | true | ExternalCallExecutor.auditSuccess/auditFailure() |

审计日志格式:
```
EXTERNAL_CALL_AUDIT adapter=Memory, success=true, target=https://api.mem0.ai,
  method=memory.search, attempt=1, elapsedMs=544, request=..., response=...
```

---

## 运行日志对照表

| 日志 | 来源代码 | 含义 |
|---|---|---|
| `add resource succeed, id=external_memory_mem0_memory_search` | MemoryToolRegistrar.registerTool() -> Runner.resourceMgr().addTool() | memory_search 工具注册成功 |
| `add resource succeed, id=external_memory_mem0_memory_add` | 同上 | memory_add 工具注册成功 |
| `EXTERNAL_CALL_AUDIT adapter=Memory, method=memory.search` | ExternalCallExecutor.auditSuccess() | prefetch 搜索记忆 (请求前) |
| `[LLM] tool_call: memory_add` | ReActAgent 推理循环 | LLM 决定调用 memory_add 工具 |
| `Executing tool: memory_add` | ReActAgent 工具执行 | 开始执行 memory_add 工具 |
| `EXTERNAL_CALL_AUDIT adapter=Memory, method=memory.add` (第1次) | MemoryToolRegistrar.invokeAdd() -> MemoryStore.add() | LLM 工具执行写入 (infer=false) |
| `EXTERNAL_CALL_AUDIT adapter=Memory, method=memory.add` (第2次) | MemoryStoreMemoryProvider.syncTurn() -> MemoryStore.add() | syncTurn 写入本轮对话 (infer=true) |
| `ReAct iteration 1/5` | ReActAgent 推理循环 | ReAct 第1轮迭代 |
| `ReAct iteration 2/5` | ReActAgent 推理循环 | ReAct 第2轮迭代 (基于工具结果生成回答) |
| `[LLM] <<< response` | ReActAgent | LLM 生成最终回答 |

---

## 关键源码文件索引

| 文件 | 角色 | 关键方法 |
|---|---|---|
| [MemoryDemoApplication.java](src/main/java/com/openjiuwen/service/demo/example/memory/MemoryDemoApplication.java) | 启动入口, @Bean agentHandler, 4层包装 | agentHandler(), withMemorySystemPrompt() |
| [MemoryLifecycleAgentHandler.java](src/main/java/com/openjiuwen/service/demo/example/memory/MemoryLifecycleAgentHandler.java) | 生命周期桥接: prefetch + syncTurn + 注入 `<memory-context>` | query(), streamQuery(), prefetch(), syncTurn(), enrichLastUserMessage() |
| [MemoryAwareJiuwenCoreAgentHandler.java](src/main/java/com/openjiuwen/service/demo/example/memory/MemoryAwareJiuwenCoreAgentHandler.java) | 覆盖 useRequestScopedSession -> true | useRequestScopedSession() |
| [MemoryToolRegistrar.java](../../support/src/main/java/com/openjiuwen/service/demo/example/support/MemoryToolRegistrar.java) | 注册 4 个记忆工具到 ReActAgent | register(), registerSchemaTool(), invokeTool(), invokeSearch(), invokeAdd() |
| [ExampleReActAgentFactory.java](../../support/src/main/java/com/openjiuwen/service/demo/example/support/ExampleReActAgentFactory.java) | 构建 ReActAgent (LLM+工具+配置) | build() |
| [MemoryStoreMemoryProvider.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/memory/MemoryStoreMemoryProvider.java) | Core MemoryProvider 桥接: prefetch->search, syncTurn->add | prefetch(), syncTurn(), formatPrefetch() |
| [Mem0MemoryStore.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/memory/mem0/Mem0MemoryStore.java) | mem0 MemoryStore 实现 | search(), add(), get(), delete() |
| [GovernedMem0Api.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/memory/mem0/GovernedMem0Api.java) | mem0 HTTP 客户端 (治理) | searchMemories(), addMemoryRecords(), getMemory(), deleteMemory() |
| [ExternalCallExecutor.java](../../../../agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/external/ExternalCallExecutor.java) | 外部调用治理: timeout/retry/circuitBreaker/audit | execute(), callWithTimeout(), ensureCircuitClosed(), auditSuccess() |
| [MemoryAdaptersAutoConfiguration.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/autoconfigure/MemoryAdaptersAutoConfiguration.java) | Spring 自动装配 MemoryStore + MemoryProvider | memoryStore(), runtimeMemoryProvider() |
| [JiuwenCoreAgentHandler.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/agentfw/JiuwenCoreAgentHandler.java) | 适配器: buildInputs -> Runner.runAgent -> toQueryResponse | query(), buildInputs(), runnerSession(), toQueryResponse() |
| [A2AEnabledServeOrchestrator.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/A2AEnabledServeOrchestrator.java) | 编排器 (A2A 增强) | query(), streamQuery() |
| [QueryMvcController.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/controller/query/QueryMvcController.java) | HTTP 入口 | queryV1(), handleQuery() |
| [QueryIngressSupport.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/controller/query/QueryIngressSupport.java) | 请求校验和 DTO 映射 | validateAndBuild(), applyTenantHeaders() |
| [application-mem0.yml](application-mem0.yml) | mem0 云服务配置 | provider, endpoint, apiKey, rerank |
| [application-memory.yml](application-memory.yml) | 记忆中间件配置 | enabled, request-scoped-session, timeout, retry, circuitBreaker, audit |

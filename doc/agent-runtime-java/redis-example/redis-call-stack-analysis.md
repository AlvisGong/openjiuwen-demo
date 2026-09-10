# Redis Demo 端到端调用栈分析

> 以 "My name is Zhang San." 和 "What is my name?" 两轮对话为例，
> 结合运行日志和代码，严格追踪从 HTTP 请求到 Redis Checkpointer 的完整调用栈。
>
> 验证时间: 2026-09-10，服务端口 8091 正常运行。

---

## 运行环境

| 组件 | 端口/地址 | 说明 |
|---|---|---|
| Java Redis Demo | localhost:8091 | RedisDemoApplication, ReActAgent + Redis Checkpointer |
| Redis | 127.0.0.1:6379 | standalone, database 0, 无密码, TTL=604800s (7天) |
| LLM (glm-5.2) | dashscope 远程 | OpenAI 兼容 API |

---

## 启动阶段

### 1.1 JVM 启动 -> Spring Boot 装配

```
java -jar agent-service-demo-redis-0.1.2.jar
文件: example/redis/src/main/java/.../RedisDemoApplication.java#L30
|
+-- SpringApplication.run(RedisDemoApplication.class, args)
|   加载配置 (application.yml -> base.yml -> base_local.yml -> redis-checkpointer.yml):
|   |
|   |   application-base.yml (公共基础配置):
|   |     server.port: 8090 (被 redis-checkpointer.yml 覆盖为 8091)
|   |     openjiuwen.service.llm: auto-discover=true, provider=OpenAI, ...
|   |     openjiuwen.service.middleware:
|   |       checkpointer.type: in_memory (被 redis-checkpointer.yml 覆盖为 redis)
|   |       redis.default: {type:standalone, host:127.0.0.1, port:6379, database:0, timeout-ms:3000}
|   |
|   |   application-base_local.yml (本地 LLM 覆盖):
|   |     openjiuwen.service.llm:
|   |       auto-discover: false
|   |       provider: OpenAI
|   |       api-key: sk-84d285...
|   |       api-base: https://dashscope.aliyuncs.com/compatible-mode/v1
|   |       model-name: glm-5.2
|   |       ssl-verify: true
|   |
|   |   application-redis-checkpointer.yml (最后 import, 覆盖优先级最高):
|   |     server.port: 8091
|   |     openjiuwen.service.middleware.checkpointer:
|   |       type: redis
|   |       redis-ref: default
|   |       ttl-seconds: 604800 (7天)
|   |
|   +-- Spring Boot 自动装配:
|   |
|   |   1. LlmAutoConfiguration -> LlmConfigResolver
|   |      -> 解析 LLM 配置: glm-5.2, dashscope, systemPrompt
|   |
|   |   2. RedisMiddlewareAutoConfiguration (关键!)
|   |      文件: RedisMiddlewareAutoConfiguration.java#L30
|   |      @ConditionalOnProperty(checkpointer.type=redis) -> 满足
|   |      @Bean runtimeRedisClient(properties, decryptor)
|   |      |
|   |      +-- redisRef = "default"
|   |      +-- endpoint = RedisConnectionAssembler.resolve(properties, "default")
|   |      |   -> ResolvedRedisEndpoint{type:standalone, host:127.0.0.1, port:6379, database:0}
|   |      +-- password = decryptor.decrypt("", REDIS_PASSWORD) -> "" (无密码)
|   |      +-- endpoint.isCluster() -> false
|   |      +-- return new JedisPooledRuntimeRedisClient(
|   |              RedisJedisClientFactory.createPooled(endpoint, password))
|   |          文件: RedisJedisClientFactory.java#L66
|   |          -> new JedisPooled(127.0.0.1:6379, clientConfig, pooledConnectionConfig())
|   |          -> JedisPooled (线程安全的连接池客户端)
|   |      |
|   |      +-- 同时创建 RedisDatasourceDiagnostics
|   |          -> 启动时输出:
|   |            "Runtime Redis datasource selected: redis-ref=default,
|   |             endpoint-type=standalone, RuntimeRedisClient=JedisPooledRuntimeRedisClient,
|   |             ttl-seconds=604800, ref=default, type=standalone,
|   |             host=127.0.0.1, port=6379, database=0, timeoutMs=3000,
|   |             passwordConfigured=false"
|   |
|   |   3. MiddlewareAdaptersAutoConfiguration (关键!)
|   |      文件: MiddlewareAdaptersAutoConfiguration.java#L37
|   |      @Bean middlewareAdapterRegistrar(properties, decryptor, redisClientProvider)
|   |      |
|   |      +-- DefaultMiddlewareAdapterRegistrar(properties, decryptor, runtimeRedisClient)
|   |      |   文件: DefaultMiddlewareAdapterRegistrar.java#L24
|   |      |
|   |      +-- registrar.applyToRunnerConfig(RunnerConfig.getRunnerConfig())
|   |          文件: DefaultMiddlewareAdapterRegistrar.java#L34
|   |          |
|   |          +-- AgentCoreCheckpointerConfigAssembler.build(properties, decryptor, redisClient)
|   |             文件: AgentCoreCheckpointerConfigAssembler.java#L37
|   |             |
|   |             +-- type = normalizeType("redis") -> "redis"
|   |             +-- TYPE_REDIS.equals(type) -> true
|   |             +-- buildRedisConf(properties, decryptor, redisClient)
|   |             |   文件: AgentCoreCheckpointerConfigAssembler.java#L57
|   |             |   |
|   |             |   +-- redisRef = "default"
|   |             |   +-- endpoint = RedisConnectionAssembler.resolve(properties, "default")
|   |             |   +-- password = decryptor.decrypt("", REDIS_PASSWORD) -> ""
|   |             |   +-- connection = RedisConnectionAssembler.buildConnectionMap(endpoint, "")
|   |             |   |   -> {url:"redis://127.0.0.1:6379/0", host:"127.0.0.1", port:6379, ...}
|   |             |   +-- connection.put("redis_client", redisClient)
|   |             |   |   -> 将 JedisPooledRuntimeRedisClient 放入 connection map
|   |             |   +-- conf = {
|   |             |       connection: {url, host, port, redis_client, ...},
|   |             |       ttl: {default_ttl: 10080.0 (604800/60=10080分钟), refresh_on_read: false}
|   |             |     }
|   |             |   +-- return conf
|   |             |
|   |             +-- return {type:"redis", conf:conf}
|   |             |
|   |          +-- runnerConfig.setCheckpointerConfig({type:"redis", conf:conf})
|   |             -> RunnerConfig 全局单例上设置了 redis checkpointer 配置
|   |
|   |   4. AgentServiceAutoConfiguration -> Controller/Lifecycle/Orchestrator
|   |
|   |   5. @Bean agentHandler(...)
|   |      文件: RedisDemoApplication.java#L35
|   |      |
|   |      +-- llmConfigResolver.resolveRequired()
|   |      |   -> ResolvedLlmConfig (glm-5.2, dashscope)
|   |      |
|   |      +-- ExampleReActAgentFactory.build("demo-redis-agent", "Demo Redis Agent", "...", llmConfig)
|   |      |   -> ReActAgent (含 LLM 配置, systemPrompt)
|   |      |
|   |      +-- new JiuwenCoreAgentHandler(agent, externalSvcAdapterRegistrar)
|   |          -> middlewareAdapterRegistrar = null (已由 Spring 自动配置完成)
|   |          -> externalSvcAdapterRegistrar = noop (无 MCP/Remote)
|   |
|   +-- [ApplicationReadyEvent] handler.start()
|       -> JiuwenCoreAgentHandler.start()
|       文件: JiuwenCoreAgentHandler.java#L179
|       |
|       +-- middlewareAdapterRegistrar == null -> 跳过 (配置已由 Spring 装配)
|       +-- externalSvcAdapterRegistrar.registerToRunner() -> noop
|       +-- Runner.start()
|           文件: RunnerImpl (agent-core-java)
|           |
|           +-- "Begin to initializing checkpointer with type: redis"
|           +-- CheckpointerFactory.create("redis", conf)
|           |   -> 从 RunnerConfig.checkpointerConfig 获取 type 和 conf
|           |   -> 创建 RedisCheckpointer (agent-core-java 扩展)
|           |   -> RedisCheckpointer 内部使用 JedisPooled 客户端
|           |   -> TTL = 10080 分钟 (7天)
|           |
|           +-- "Succeed to initializing checkpointer with type: redis"
|           +-- "Succeed to start runner, runnerId=global"
|           +-- "agent_loaded=true"
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
+-- RuntimeRedisClient (from RedisMiddlewareAutoConfiguration)
|   +-- JedisPooledRuntimeRedisClient
|       +-- JedisPooled (连接池, 127.0.0.1:6379)
|
+-- RedisDatasourceDiagnostics (启动诊断)
|
+-- MiddlewareAdapterRegistrar (from MiddlewareAdaptersAutoConfiguration)
|   +-- DefaultMiddlewareAdapterRegistrar
|       -> applyToRunnerConfig(RunnerConfig)
|       -> AgentCoreCheckpointerConfigAssembler.build()
|       -> RunnerConfig.checkpointerConfig = {type:"redis", conf:{connection, ttl}}
|
+-- AgentHandler (from RedisDemoApplication.agentHandler)
    +-- JiuwenCoreAgentHandler
        +-- agent: ReActAgent
        |   +-- LLM: glm-5.2 via dashscope
        |   +-- 无额外工具 (纯对话)
        +-- middlewareAdapterRegistrar: null (Spring 已处理)
        +-- externalSvcAdapterRegistrar: noop
```

### 1.3 配置加载优先级

```
application.yml (import 声明)
  |
  +-- application-base.yml (port=8090, checkpointer=in_memory, redis.default 配置)
  |
  +-- application-base_local.yml (LLM: glm-5.2, dashscope)  <- 覆盖 LLM 配置
  |
  +-- application-redis-checkpointer.yml (port=8091, checkpointer=redis)  <- 最后加载, 覆盖优先级最高
```

> 关键: Spring 配置 import 顺序决定覆盖优先级，后加载的覆盖先加载的。
> `application-redis-checkpointer.yml` 最后 import，因此 port 和 checkpointer.type 被覆盖。

---

## 请求1：写入会话（conversation_id=redis-c1, 第1轮）

### 实际请求

```
POST http://localhost:8091/v1/query
Body: {"conversation_id":"redis-c1","message":"My name is Zhang San. Please remember it.","stream":false}
```

### 实际响应

```json
{
    "result": {
        "role": "assistant",
        "content": "Got it, Zhang San. I will remember your name."
    },
    "conversation_id": "redis-c1"
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
    |   -> QueryRequest{conversationId:"redis-c1", message:"My name is Zhang San...", stream:false}
    |
    +-- QueryIngressSupport.validateAndBuild(request, headers)
    |   文件: QueryIngressSupport.java#L50
    |   |
    |   +-- request.normalizeMessages()
    |   |   -> 将 message 简写转为 messages: [{role:"user", content:"My name is Zhang San..."}]
    |   +-- request.getConversationId() 非空 -> 通过校验
    |   +-- ServeRequest.fromQueryRequest(request)
    |       -> ServeRequest{conversationId:"redis-c1", messages:[...], stream:false}
    |
    +-- validateAndBuildMetadata(serveRequest, headers, servletRequest, rawBody)
    |   -> metadata = {headers, query, path:"/v1/query", body}
    |
    +-- isAgentReady() -> true (agent_loaded=true)
    |
    +-- request.isStream() -> false -> 同步路径
    |
    +-- orchestrator.query(serveRequest)
        文件: A2AEnabledServeOrchestrator.java#L131
```

### 2.2 Orchestrator -> JiuwenCoreAgentHandler

```
A2AEnabledServeOrchestrator.query(request)
文件: A2AEnabledServeOrchestrator.java#L131
|
+-- log.info("Orchestrator query START conversationId={}", "redis-c1")
|
+-- agentHandler.prepareTask(request) -> Optional.empty() (无 task scope)
|
+-- syncResumePending(current, NOOP_OBSERVER)
|   -> 无 pending A2A shadow task
|   -> 返回 QueryResumeResult(response=null, request=Optional.of(current))
|
+-- agentHandler.query(current)
    |
    |  agentHandler 是 JiuwenCoreAgentHandler (无包装层, 不像 Memory Demo 有 MemoryLifecycle)
    |  文件: JiuwenCoreAgentHandler.java#L283
    |
    +-- FutureTask<QueryResponse> execution = new FutureTask<>(() -> {
    |   |
    |   +-- supportsInvoke(agent) -> true (ReActAgent 有 invoke 方法)
    |   |
    |   +-- executeAgent(buildInputs(request), runnerSession(request))
    |   |   文件: JiuwenCoreAgentHandler.java#L176
    |   |   |
    |   |   |  executeAgent 调用 Runner.runAgent(agent, inputs, session, null)
    |   |   |
    |   |   +-- buildInputs(request)
    |   |   |   文件: JiuwenCoreAgentHandler.java#L449
    |   |   |   |
    |   |   |   +-- inputs = {
    |   |   |       conversation_id: "redis-c1",
    |   |   |       messages: [{role:"user", content:"My name is Zhang San..."}],
    |   |   |       user_id: null,
    |   |   |       space_id: null,
    |   |   |     }
    |   |   |   +-- metadata.get("runtime.remoteToolResults") -> null
    |   |   |   +-- inputs.put("query", "My name is Zhang San. Please remember it.")
    |   |   |
    |   |   +-- runnerSession(request)
    |   |   |   文件: JiuwenCoreAgentHandler.java#L500
    |   |   |   |
    |   |   |   +-- sessionId = "redis-c1"
    |   |   |   +-- hasAgentCard(agent) -> true (ReActAgent.getCard())
    |   |   |   +-- useRequestScopedSession(request) -> false (Redis Demo 未覆盖)
    |   |   |   |   -> 走 sessionId 字符串路径 (不创建 AgentSessionApi)
    |   |   |   +-- return sessionId = "redis-c1"
    |   |   |
    |   |   +-- Runner.runAgent(agent, inputs, "redis-c1", null)
    |   |       |
    |   |       +-- Runner 查找或创建 session "redis-c1"
    |   |       |   |
    |   |       |   +-- Checkpointer.load("redis-c1")
    |   |       |   |   -> RedisCheckpointer 从 Redis 读取 session 状态
    |   |       |   |   -> Redis GET key: "redis-c1:*"
    |   |       |   |   -> 首次请求, Redis 中无数据 -> 返回空 session
    |   |       |   |
    |   |       |   +-- 创建新 session (无历史上下文)
    |   |       |   +-- ContextEngine 初始化 (ReActAgent 的上下文引擎)
    |   |       |
    |   |       +-- ReActAgent.invoke(inputs, session)
    |   |           |
    |   |           |  -- ReAct Iteration 1/5 --
    |   |           |  [Reason] LLM 推理:
    |   |           |    system: "You are a helpful assistant. Answer concisely and accurately."
    |   |           |    user: "My name is Zhang San. Please remember it."
    |   |           |    可用工具: 无 (Redis Demo 未注册任何工具)
    |   |           |
    |   |           |    -> LLM 输出: "Got it, Zhang San. I will remember your name."
    |   |           |    (无需工具调用, 直接回答)
    |   |           |
    |   |           +-- ReActAgent 返回结果
    |   |       |
    |   |       +-- Checkpointer.save("redis-c1", sessionState)
    |   |           |   -> RedisCheckpointer 将 session 状态写入 Redis
    |   |           |   -> Redis SET key: "redis-c1:..." value: <序列化的 session 状态>
    |   |           |   -> TTL = 604800s (7天)
    |   |           |   -> 包含本轮对话: user:"My name is Zhang San..." + assistant:"Got it..."
    |   |           |
    |   |           |   日志: (Runner 内部, 无 EXTERNAL_CALL_AUDIT, 因为 Redis 不走 ExternalCallExecutor)
    |   |
    |   +-- toQueryResponse(rawResult, "redis-c1")
    |       文件: JiuwenCoreAgentHandler.java#L334
    |       -> result = {role:"assistant", content:"Got it, Zhang San. I will remember your name."}
    |       -> return new QueryResponse(result, "redis-c1")
    |
    +-- execution.run() -> execution.get() -> 返回 QueryResponse
    |
    +-- extractInterruptFromResponse(response) -> 空 (无 _interrupt)
    +-- return response

-> writeJson(response, 200, queryResponse)
-> HTTP 200: {"result":{"role":"assistant","content":"Got it, Zhang San..."},"conversation_id":"redis-c1"}
```

### 2.2 请求1中的 Redis 操作

| 操作 | Redis 命令 | Key 模式 | 说明 |
|---|---|---|---|
| 读取 (load) | GET / SCAN | redis-c1:* | 首次请求, Redis 中无数据, 返回空 session |
| 写入 (save) | SET + EXPIRE | redis-c1:* | 保存本轮对话 (user+assistant), TTL=604800s |

---

## 请求2：跨轮次查询（同一 conversation_id=redis-c1, 第2轮）

### 实际请求

```
POST http://localhost:8091/v1/query
Body: {"conversation_id":"redis-c1","message":"What is my name?","stream":false}
```

### 实际响应

```json
{
    "result": {
        "role": "assistant",
        "content": "Your name is Zhang San."
    },
    "conversation_id": "redis-c1"
}
```

### 3.1 完整调用栈

```
POST /v1/query
|
+-- QueryMvcController -> Orchestrator -> JiuwenCoreAgentHandler.query()
    |
    +-- [buildInputs]
    |   inputs = {conversation_id:"redis-c1", messages:[{user:"What is my name?"}], query:"What is my name?"}
    |
    +-- [runnerSession]
    |   sessionId = "redis-c1" (同一 conversation_id)
    |   useRequestScopedSession -> false
    |   return "redis-c1"
    |
    +-- Runner.runAgent(agent, inputs, "redis-c1", null)
        |
        +-- Checkpointer.load("redis-c1")
        |   |
        |   |   -> RedisCheckpointer 从 Redis 读取 session 状态
        |   |   -> Redis GET/SCAN key: "redis-c1:*"
        |   |   -> 找到第1轮写入的 session 状态!
        |   |   -> 反序列化: 包含 user:"My name is Zhang San..." + assistant:"Got it..."
        |   |
        |   +-- 恢复 session (含第1轮上下文)
        |   +-- ContextEngine 加载历史消息:
        |       messages = [
        |         {role:"user", content:"My name is Zhang San. Please remember it."},
        |         {role:"assistant", content:"Got it, Zhang San. I will remember your name."},
        |         {role:"user", content:"What is my name?"}  <- 本轮新增
        |       ]
        |
        +-- ReActAgent.invoke(inputs, session)
            |
            |  -- ReAct Iteration 1/5 --
            |  [Reason] LLM 推理:
            |    system: "You are a helpful assistant..."
            |    user (历史): "My name is Zhang San. Please remember it."
            |    assistant (历史): "Got it, Zhang San. I will remember your name."
            |    user (本轮): "What is my name?"
            |    可用工具: 无
            |
            |    -> LLM 基于历史上下文回答:
            |      "Your name is Zhang San."
            |    (LLM 从 Redis Checkpointer 恢复的历史中知道名字)
            |
            +-- ReActAgent 返回结果
        |
        +-- Checkpointer.save("redis-c1", sessionState)
            |   -> Redis SET key: "redis-c1:*"
            |   -> 更新 session 状态: 现在包含 2 轮对话 (4条消息)
            |   -> TTL = 604800s (刷新 TTL)
            |
            -> 返回 rawResult

    +-- toQueryResponse -> QueryResponse{content:"Your name is Zhang San."}

-> HTTP 200: {"result":{"role":"assistant","content":"Your name is Zhang San."},"conversation_id":"redis-c1"}
```

### 3.2 请求2中的 Redis 操作

| 操作 | Redis 命令 | Key 模式 | 说明 |
|---|---|---|---|
| 读取 (load) | GET / SCAN | redis-c1:* | 读取第1轮保存的 session 状态，恢复上下文 |
| 写入 (save) | SET + EXPIRE | redis-c1:* | 保存更新后的 session (2轮对话), 刷新 TTL |

> 关键：Redis Checkpointer 使得同一 conversation_id 的多轮对话可以跨请求保持上下文。
> 即使进程重启，只要 Redis 中的 key 未过期 (7天)，会话仍可恢复。

---

## 完整调用栈总览图

```
+--------------------------------------------------------------------------+
|                   请求1: 写入会话 (redis-c1, 第1轮)                       |
|                                                                          |
|  POST /v1/query (conversation_id=redis-c1, "My name is Zhang San")      |
|  +-> QueryMvcController.handleQuery()                                     |
|     +-> QueryIngressSupport.validateAndBuild() -> ServeRequest            |
|     +-> A2AEnabledServeOrchestrator.query()                               |
|        +-> JiuwenCoreAgentHandler.query()                                |
|           +-> buildInputs -> Runner.runAgent(agent, inputs, "redis-c1")   |
|              |                                                           |
|              +-> [Checkpointer.load("redis-c1")]                        |
|              |  -> RedisCheckpointer: GET/SCAN redis-c1:*                  |
|              |  -> 空 (首次请求)                                          |
|              |                                                           |
|              +-> [ReActAgent.invoke]                                     |
|              |  -> LLM: "Got it, Zhang San. I will remember your name."  |
|              |                                                           |
|              +-> [Checkpointer.save("redis-c1")]                        |
|                 -> RedisCheckpointer: SET redis-c1:* = session_state      |
|                 -> TTL = 604800s (7天)                                    |
|                                                                          |
|  <- 用户收到: "Got it, Zhang San. I will remember your name."             |
+--------------------------------------------------------------------------+

+--------------------------------------------------------------------------+
|                请求2: 跨轮次查询 (redis-c1, 第2轮)                        |
|                                                                          |
|  POST /v1/query (conversation_id=redis-c1, "What is my name?")          |
|  +-> QueryMvcController -> Orchestrator -> JiuwenCoreAgentHandler         |
|     +-> buildInputs -> Runner.runAgent(agent, inputs, "redis-c1")        |
|        |                                                                 |
|        +-> [Checkpointer.load("redis-c1")]                              |
|        |  -> RedisCheckpointer: GET/SCAN redis-c1:*                        |
|        |  -> 找到第1轮 session 状态!                                       |
|        |  -> 恢复上下文: user:"My name is Zhang San" + assistant:"Got it"|
|        |                                                                 |
|        +-> [ReActAgent.invoke]                                           |
|        |  -> LLM (看到历史上下文): "Your name is Zhang San."              |
|        |                                                                 |
|        +-> [Checkpointer.save("redis-c1")]                              |
|           -> RedisCheckpointer: SET redis-c1:* = 更新后的 session_state   |
|           -> TTL = 604800s (刷新)                                        |
|                                                                          |
|  <- 用户收到: "Your name is Zhang San."                                  |
+--------------------------------------------------------------------------+
```

---

## Redis Checkpointer 初始化链路

```
配置层:
  application-redis-checkpointer.yml
    checkpointer.type: redis
    checkpointer.redis-ref: default
    checkpointer.ttl-seconds: 604800
    (引用 application-base.yml 中的 redis.default endpoint)

    |
    v

Spring 自动装配:
  RedisMiddlewareAutoConfiguration
    |
    +-> @Bean runtimeRedisClient(properties, decryptor)
    |   +-> RedisConnectionAssembler.resolve(properties, "default")
    |   |   -> ResolvedRedisEndpoint{type:standalone, host:127.0.0.1, port:6379}
    |   +-> RedisJedisClientFactory.createPooled(endpoint, password)
    |   |   -> JedisPooled (连接池客户端)
    |   +-> new JedisPooledRuntimeRedisClient(jedisPooled)
    |       -> 实现 RuntimeRedisClient SPI
    |
    +-> @Bean redisDatasourceDiagnostics(...)
        -> 启动日志: "Runtime Redis datasource selected: ..."
    |
    v

  MiddlewareAdaptersAutoConfiguration
    |
    +-> @Bean middlewareAdapterRegistrar(properties, decryptor, redisClientProvider)
        +-> new DefaultMiddlewareAdapterRegistrar(properties, decryptor, runtimeRedisClient)
        +-> registrar.applyToRunnerConfig(RunnerConfig.getRunnerConfig())
            |
            +-> AgentCoreCheckpointerConfigAssembler.build(properties, decryptor, redisClient)
            |   +-> type = "redis"
            |   +-> buildRedisConf(properties, decryptor, redisClient)
            |   |   +-- connection = {url:"redis://127.0.0.1:6379/0", host, port, redis_client}
            |   |   +-- ttl = {default_ttl:10080.0, refresh_on_read:false}
            |   +-> return {type:"redis", conf:{connection, ttl}}
            |
            +-> runnerConfig.setCheckpointerConfig(config)
                -> RunnerConfig 全局单例设置 checkpointer 配置
    |
    v

运行时初始化:
  JiuwenCoreAgentHandler.start()
    +-> Runner.start()
        +-> CheckpointerFactory.create("redis", conf)
        |   -> 创建 RedisCheckpointer (agent-core-java)
        |   -> 内部使用 JedisPooled 客户端
        |   -> TTL = 10080 分钟
        +-> "Succeed to initializing checkpointer with type: redis"
```

---

## Redis Checkpointer 请求时调用链

```
每次 Runner.runAgent(agent, inputs, sessionId, null) 调用:

+-- [1] Checkpointer.load(sessionId)
|   |
|   |  RedisCheckpointer.load("redis-c1")
|   |  -> 从 Redis 读取 key: "redis-c1:*"
|   |  -> Redis 命令: GET/SCAN
|   |  -> 如果有数据: 反序列化 session 状态, 恢复上下文
|   |  -> 如果无数据: 返回空 session (新会话)
|   |
|   +-- 返回 Session 对象 (含历史消息)
|
+-- [2] ReActAgent.invoke(inputs, session)
|   |
|   |  ContextEngine 加载历史消息 + 本轮消息
|   |  -> LLM 推理 (含完整上下文)
|   |  -> 返回结果
|   |
|   +-- 返回 rawResult
|
+-- [3] Checkpointer.save(sessionId, sessionState)
    |
    |  RedisCheckpointer.save("redis-c1", updatedState)
    |  -> 序列化 session 状态 (含本轮新增的 user+assistant 消息)
    |  -> Redis 命令: SET key="redis-c1:..." value=<serialized>
    |  -> EXPIRE key 604800 (7天)
    |  -> 如果 refresh_on_read=false: 仅在 save 时刷新 TTL
    |
    +-- 返回保存结果
```

---

## 与 Memory Demo 对比

| 维度 | Redis Demo | Memory Demo |
|---|---|---|
| **AgentHandler** | JiuwenCoreAgentHandler (无包装) | MemoryLifecycleAgentHandler (4层包装) |
| **端口** | 8091 | 8094 |
| **核心特性** | Redis Checkpointer (会话状态持久化) | mem0 Memory (长期记忆) |
| **数据存储** | Redis (本地 127.0.0.1:6379) | mem0 Cloud (api.mem0.ai) |
| **持久化内容** | 完整 session 状态 (对话历史 + ContextEngine) | 提取的事实记忆 (如 "User likes latte") |
| **检索方式** | 按 conversation_id 精确加载 | 按语义搜索 (query → 相关记忆) |
| **TTL** | 604800s (7天) | mem0 管理 (无 TTL) |
| **触发时机** | Runner 自动 (load before, save after) | 生命周期自动 (prefetch before, syncTurn after) + LLM 工具 |
| **用户维度** | conversation_id (会话级) | user_id (用户级, 跨会话) |
| **跨进程恢复** | 支持 (Redis 持久化) | 支持 (mem0 云服务) |
| **外部调用治理** | 无 (Redis 直连, 不走 ExternalCallExecutor) | 有 (ExternalCallExecutor: timeout/retry/circuitBreaker/audit) |
| **Runner 配置方式** | Spring 自动装配 + RunnerConfig | Spring 自动装配 + @Bean |

### 设计差异要点

1. **Redis Checkpointer** 是 agent-core-java Runner 层面的机制:
   - Runner.runAgent() 自动执行 load -> invoke -> save
   - 对 Agent 和 Handler 透明, 不需要业务代码参与
   - 保存的是完整 session 状态 (对话历史 + ContextEngine 内部状态)

2. **Memory (mem0)** 是 service 适配层面的机制:
   - MemoryLifecycleAgentHandler 在 query() 前后包装 prefetch/syncTurn
   - 需要业务代码主动注册 (MemoryToolRegistrar)
   - 保存的是 LLM 提取的事实记忆, 跨会话按 user_id 检索

3. **Redis Checkpointer 不走 ExternalCallExecutor**:
   - Redis 是内部基础设施, 不是外部服务
   - RedisCheckpointer 由 agent-core-java 直接调用 JedisPooled
   - 无 timeout/retry/circuitBreaker/audit 治理

---

## 运行日志对照表

| 日志 | 来源代码 | 含义 |
|---|---|---|
| `Runtime Redis datasource selected: redis-ref=default, endpoint-type=standalone, RuntimeRedisClient=JedisPooledRuntimeRedisClient, ttl-seconds=604800, ...` | RedisDatasourceDiagnostics | Redis 客户端初始化诊断 |
| `Started RedisDemoApplication in 7.524 seconds` | Spring Boot | 应用启动完成 |
| `Starting AgentCore Runner` | JiuwenCoreAgentHandler.start() | Runner 开始启动 |
| `Begin to initializing checkpointer with type: redis, runnerId=global` | RunnerImpl | 开始初始化 Redis Checkpointer |
| `Succeed to initializing checkpointer with type: redis` | RunnerImpl | Redis Checkpointer 初始化成功 |
| `Succeed to start runner, runnerId=global` | RunnerImpl | Runner 启动成功 |
| `agent_loaded=true` | InitPhaseExecutor | Agent 就绪 |
| `Orchestrator query START conversationId=redis-c1` | A2AEnabledServeOrchestrator.query() | 请求开始 |

---

## 关键源码文件索引

| 文件 | 角色 | 关键方法 |
|---|---|---|
| [RedisDemoApplication.java](src/main/java/com/openjiuwen/service/demo/example/redis/RedisDemoApplication.java) | 启动入口, @Bean agentHandler | agentHandler() |
| [JiuwenCoreAgentHandler.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/agentfw/JiuwenCoreAgentHandler.java) | 适配器: buildInputs -> Runner.runAgent -> toQueryResponse | query(), start(), buildInputs(), runnerSession() |
| [DefaultMiddlewareAdapterRegistrar.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/middleware/DefaultMiddlewareAdapterRegistrar.java) | 中间件注册器: 写 RunnerConfig | applyToRunnerConfig() |
| [AgentCoreCheckpointerConfigAssembler.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/middleware/AgentCoreCheckpointerConfigAssembler.java) | 组装 Checkpointer 配置 | build(), buildRedisConf() |
| [RedisMiddlewareAutoConfiguration.java](../../../../agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/middleware/redis/RedisMiddlewareAutoConfiguration.java) | Spring 自动装配 Redis 客户端 | runtimeRedisClient() |
| [RedisJedisClientFactory.java](../../../../agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/middleware/redis/RedisJedisClientFactory.java) | 创建 Jedis 客户端 | createPooled(), createCluster() |
| [JedisPooledRuntimeRedisClient.java](../../../../agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/middleware/redis/JedisPooledRuntimeRedisClient.java) | RuntimeRedisClient 实现 (单机) | extends UnifiedJedisRuntimeRedisClient |
| [MiddlewareAdaptersAutoConfiguration.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/autoconfigure/MiddlewareAdaptersAutoConfiguration.java) | Spring 自动装配中间件 | middlewareAdapterRegistrar() |
| [MiddlewareProperties.java](../../../../agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/middleware/MiddlewareProperties.java) | 配置绑定: checkpointer + redis | Checkpointer class, RedisEndpoint class |
| [RedisConnectionAssembler.java](../../../../agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/middleware/redis/RedisConnectionAssembler.java) | Redis 连接参数装配 | resolve(), buildConnectionMap() |
| [A2AEnabledServeOrchestrator.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/A2AEnabledServeOrchestrator.java) | 编排器 | query() |
| [QueryMvcController.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/controller/query/QueryMvcController.java) | HTTP 入口 | queryV1(), handleQuery() |
| [ExampleReActAgentFactory.java](../../support/src/main/java/com/openjiuwen/service/demo/example/support/ExampleReActAgentFactory.java) | 构建 ReActAgent | build() |
| [application-redis-checkpointer.yml](application-redis-checkpointer.yml) | Redis Checkpointer 配置 | type:redis, redis-ref:default, ttl-seconds:604800 |
| [application-base.yml](../config/application-base.yml) | 公共基础配置 | redis.default endpoint, checkpointer.type:in_memory |
| [README.md](README.md) | Redis Demo 使用说明 | 启动方法, smoke 脚本 |

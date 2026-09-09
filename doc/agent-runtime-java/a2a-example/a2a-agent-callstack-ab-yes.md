# A2aAgentADemoApplication 端到端调用栈分析

> 严格按代码执行轨迹梳理，从 JVM 启动入口到 HTTP 请求响应的完整调用栈。
> 源码路径均为实际文件路径，可点击跳转。

---

## 一、启动阶段调用栈

### 1.1 JVM 入口

```
A2aAgentADemoApplication.main(String[] args)
├── 文件: service/agent-service-demo/example/a2a/src/main/java/com/openjiuwen/service/demo/example/a2a/A2aAgentADemoApplication.java#L30
│
├── new SpringApplicationBuilder(A2aAgentADemoApplication.class)
│       .properties("spring.config.import=" + ...)
│       .run(args)
│
│   ├── 加载配置文件（按 spring.config.import 顺序，后者覆盖前者）:
│   │   1. optional:classpath:application-base.yml          ← 基础配置（端口8090/LLM默认值/middleware）
│   │   2. optional:classpath:application-base_local.yml   ← 本地覆盖（可选，gitignored）
│   │   3. optional:classpath:application-a2a-agent-a.yml  ← Agent A 专属配置（覆盖端口为18090/system-prompt/remote-agents/skills）
│   │   4. optional:classpath:application-a2a-redis.local.yml  ← 本地Redis（可选）
│   │
│   ├── yml 文件来源（pom.xml 的 resources 配置）:
│   │   application-base.yml     → 来自 example/config/ 目录，Maven 打包到 classpath
│   │   application-a2a-agent-a.yml → 来自 example/a2a/ 目录，Maven 打包到 classpath
│   │
│   ├── Spring Boot 自动装配（通过 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports）
│   │   按依赖顺序加载以下 AutoConfiguration 类:
│   │
│   ├── [1] CredentialDecryptorAutoConfiguration
│   │   （导入于 LlmAutoConfiguration 的 @Import）
│   │   └── 注册 PassthroughCredentialDecryptor Bean（不解密，原样返回密文）
│   │
│   ├── [2] LlmAutoConfiguration
│   │   文件: service/agent-service-app/src/main/java/com/openjiuwen/service/app/autoconfigure/LlmAutoConfiguration.java
│   │   @EnableConfigurationProperties(LlmProperties.class)
│   │   └── 注册 LlmConfigResolver Bean
│   │       构造参数:
│   │         - LlmProperties (从 openjiuwen.service.llm.* 绑定)
│   │         - Environment
│   │         - CredentialDecryptor (Passthrough)
│   │
│   ├── [3] AgentServiceAutoConfiguration
│   │   文件: service/agent-service-app/src/main/java/com/openjiuwen/service/app/autoconfigure/AgentServiceAutoConfiguration.java
│   │   @EnableConfigurationProperties({ServiceProperties.class, QueryProperties.class, LifecycleProperties.class})
│   │   @ComponentScan(basePackages = "com.openjiuwen.service.app.controller")
│   │   │
│   │   └── 注册以下 Bean:
│   │       ├── AgentHandlerHolder          (@ConditionalOnMissingBean(AgentHandler.class))
│   │       │     ← 不创建！因为 A2aAgentADemoApplication 的 @Bean agentAHandler 已注册 AgentHandler
│   │       ├── AgentServiceIdentity        → DefaultAgentServiceIdentity (从 spring.application.name 读取)
│   │       ├── ActiveStreamRegistry
│   │       ├── DefaultAgentReadiness / AgentReadiness
│   │       ├── AgentLifecycleHooks
│   │       ├── InitPhaseExecutor           (依赖 ObjectProvider<AgentHandler>)
│   │       ├── ShutdownPhaseExecutor       (依赖 ObjectProvider<AgentHandler>)
│   │       ├── ActiveStreamInterruptor     (依赖 ObjectProvider<ServeOrchestrator>)
│   │       ├── DefaultAgentLifecycleManager (聚合 Init/Shutdown/Interruptor)
│   │       └── AgentLifecycleBootstrap     (监听 Spring 生命周期事件)
│   │
│   ├── [4] A2AAutoConfiguration
│   │   文件: service/agent-service-app/src/main/java/com/openjiuwen/service/app/autoconfigure/A2AAutoConfiguration.java
│   │   @AutoConfiguration(after = {AgentServiceAutoConfiguration.class, RedisMiddlewareAutoConfiguration.class})
│   │   @ConditionalOnClass(AgentExecutor.class)
│   │   @EnableConfigurationProperties(A2AProperties.class)
│   │   │
│   │   └── 注册以下 Bean:
│   │       ├── MainEventBus (A2A SDK 事件总线)
│   │       ├── TaskStore → InMemoryTaskStore (默认) 或 RedisTaskStore (Redis 配置时)
│   │       ├── QueueManager (InMemoryQueueManager)
│   │       ├── MainEventBusProcessor → ResilientMainEventBusProcessor
│   │       ├── SpringEnvironmentConfigProvider (A2A 配置提供者)
│   │       ├── A2AProtocolAdapter
│   │       ├── A2AAgentExecutor (依赖 ServeOrchestrator, A2AProtocolAdapter)
│   │       ├── A2AExecutionResources (线程池)
│   │       ├── A2ATaskContinuation
│   │       ├── A2ARemoteAgentCardRegistry (远端 AgentCard 注册表)
│   │       ├── A2ARemoteAgentClient / RemoteAgentCaller (远端调用客户端)
│   │       ├── A2AAgentCardDiscovery (依赖 A2AProperties, A2ARemoteAgentCardRegistry)
│   │       ├── A2AEnabledServeOrchestrator (@ConditionalOnMissingBean(ServeOrchestrator.class))
│   │       │     构造参数:
│   │       │       - AgentHandler ← 注入 A2aAgentADemoApplication 的 agentAHandler Bean
│   │       │       - TaskStore
│   │       │       - RemoteAgentCaller
│   │       │       - ActiveStreamRegistry
│   │       │       - agentId = "demo-a2a-agent-a" (从 ${spring.application.name} 取)
│   │       │       - A2AProperties (maxConcurrency=16, maxQueueSize=256, queueTimeoutSeconds=30)
│   │       │       - A2ATaskContinuation
│   │       └── DefaultRequestHandler (A2A SDK 请求处理器)
│   │
│   ├── [5] A2aAgentADemoApplication 自身的 @Bean 方法执行
│   │   文件: service/agent-service-demo/example/a2a/src/main/java/com/openjiuwen/service/demo/example/a2a/A2aAgentADemoApplication.java#L38
│   │
│   │   agentAHandler(LlmConfigResolver llmConfigResolver)
│   │   │
│   │   ├── [5a] llmConfigResolver.resolveRequired()
│   │   │   文件: service/agent-service-app/src/main/java/com/openjiuwen/service/app/config/llm/LlmConfigResolver.java#L76
│   │   │   │
│   │   │   └── doResolve()
│   │   │       文件: LlmConfigResolver.java#L90
│   │   │       │
│   │   │       ├── properties.getAutoDiscover() → true (application-base.yml 中 auto-discover: true)
│   │   │       │
│   │   │       ├── apiConfigLoader.load(configFile=null, shouldAutoDiscover=true)
│   │   │       │   文件: service/agent-service-app/src/main/java/com/openjiuwen/service/app/config/llm/ApiConfigLoader.java#L60
│   │   │       │   │
│   │   │       │   ├── resolvePath(null, true)
│   │   │       │   │   ├── configFile 为 null → 跳过
│   │   │       │   │   ├── environment.getProperty("OPENJIUWEN_API_CONFIG") → 由启动时环境变量设置
│   │   │       │   │   │     = "d:\work\agent\...\apiconfig.json"
│   │   │       │   │   └── requireConfigFile(envPath, ...) → 返回 Path
│   │   │       │   │
│   │   │       │   └── read(path)
│   │   │       │       └── 解析 apiconfig.json:
│   │   │       │           API_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
│   │   │       │           API_KEY = "sk-84d2..."
│   │   │       │           MODEL_PROVIDER = "OpenAI"
│   │   │       │           MODEL_NAME = "glm-5.2"
│   │   │       │           LLM_SSL_VERIFY = "true"
│   │   │       │
│   │   │       ├── 合并配置（优先级: Spring属性 > apiconfig.json > 默认值）:
│   │   │       │   provider    = "OpenAI" (Spring属性 → apiconfig.json → 默认)
│   │   │       │   apiKey      = "sk-84d2..." (apiconfig.json, Passthrough 不解密)
│   │   │       │   apiBase     = "https://dashscope.aliyuncs.com/..." (apiconfig.json)
│   │   │       │   modelName   = "glm-5.2" (apiconfig.json)
│   │   │       │   sslVerify   = true (application-a2a-agent-a.yml 中未覆盖, base 中为 true)
│   │   │       │   systemPrompt = "You are Agent A...call delegate_to_agentb..." (application-a2a-agent-a.yml 覆盖)
│   │   │       │   temperature  = 0.0 (application-a2a-agent-a.yml 覆盖 base 的 0.6)
│   │   │       │   topP         = 0.8 (base 默认)
│   │   │       │   timeout      = 60s (base 默认)
│   │   │       │   contextWindowLimit = 10 (base 默认)
│   │   │       │   maxIterations = 5 (base 默认)
│   │   │       │
│   │   │       └── 返回 ResolvedLlmConfig (不可变, 缓存)
│   │   │
│   │   ├── [5b] ExampleReActAgentFactory.build(AGENT_ID, "Agent A (A2A Demo)", "...", llmConfig)
│   │   │   文件: service/agent-service-demo/example/support/src/main/java/com/openjiuwen/service/demo/example/support/ExampleReActAgentFactory.java#L35
│   │   │   │
│   │   │   ├── ReActAgentConfig.builder()
│   │   │   │   .promptTemplate([{role:"system", content: systemPrompt}])
│   │   │   │   .maxIterations(5)
│   │   │   │   .build()
│   │   │   │   .configureModelClient("OpenAI", "sk-84d2...", "dashscope URL", "glm-5.2", true)
│   │   │   │   .configureContextEngine(null, 10, false)
│   │   │   │
│   │   │   ├── ModelClientConfig.builder()
│   │   │   │   .timeout(60.0)
│   │   │   │   .verifySsl(true)
│   │   │   │   ... (复制 configureModelClient 的结果)
│   │   │   │
│   │   │   ├── ModelRequestConfig
│   │   │   │   .setTemperature(0.0)
│   │   │   │   .setTopP(0.8)
│   │   │   │
│   │   │   ├── AgentCard.builder().id("demo-a2a-agent-a").name("Agent A (A2A Demo)").description("...").build()
│   │   │   │
│   │   │   └── new ReActAgent(card) → agent.configure(agentConfig)
│   │   │       返回配置完成的 ReActAgent 实例
│   │   │
│   │   ├── [5c] agent.registerRail(new A2aDelegateRail())
│   │   │   文件: service/agent-service-demo/example/a2a/src/main/java/com/openjiuwen/service/demo/example/a2a/A2aDelegateRail.java
│   │   │   │
│   │   │   └── A2aDelegateRail 构造:
│   │   │       super(List.of("delegate_to_agentb"))  ← 注册工具名
│   │   │       getTools().add(delegateCard("delegate_to_agentb", "Delegate a task to the configured Agent B route"))
│   │   │         ← 向 Agent 工具列表添加 ToolCard
│   │   │             ToolCard: id="delegate_to_agentb", name="delegate_to_agentb"
│   │   │             inputParams: {type:"object", properties:{message:{type:"string",...}}, required:["message"]}
│   │   │
│   │   └── [5d] new JiuwenCoreAgentHandler(agent, null, ExternalSvcAdapterRegistrar.noop())
│   │       文件: service/agent-service-adapters/.../agentfw/JiuwenCoreAgentHandler.java#L100
│   │       └── 存储 agent 引用, middlewareAdapterRegistrar=null, externalSvcAdapterRegistrar=noop
│   │
│   │   返回 JiuwenCoreAgentHandler 实例 → 注册为 Spring Bean "agentAHandler"
│   │
│   └── Spring 容器初始化完成
```

### 1.2 ApplicationReadyEvent 触发

```
Spring 发布 ApplicationReadyEvent
│
├── [事件1] AgentLifecycleBootstrap.onApplicationReady()
│   文件: service/agent-service-app/src/main/java/com/openjiuwen/service/app/lifecycle/AgentLifecycleBootstrap.java#L31
│   │
│   └── lifecycleManager.runInitPhase()
│       文件: service/agent-service-app/src/main/java/com/openjiuwen/service/app/lifecycle/DefaultAgentLifecycleManager.java#L38
│       │
│       └── initPhaseExecutor.run()
│           文件: service/agent-service-app/src/main/java/com/openjiuwen/service/app/lifecycle/InitPhaseExecutor.java#L44
│           │
│           ├── agentHandlerProvider.getIfAvailable()
│           │   → 返回 Spring 容器中的 agentAHandler Bean (JiuwenCoreAgentHandler)
│           │
│           ├── isAgentLoaded(handler)
│           │   handler 不是 AgentHandlerHolder → 返回 true
│           │
│           ├── handler.start()
│           │   文件: service/agent-service-adapters/.../agentfw/JiuwenCoreAgentHandler.java#L130
│           │   │
│           │   ├── RUNNER_STARTED.compareAndSet(false, true) → true (首次启动)
│           │   │
│           │   ├── externalSvcAdapterRegistrar.registerToRunner()
│           │   │   → ExternalSvcAdapterRegistrar.noop() → 空操作
│           │   │
│           │   └── Runner.start()
│           │       (agent-core-java 全局 Runner 启动, 初始化 checkpointer 等)
│           │       日志: "Starting AgentCore Runner"
│           │       日志: "Succeed to start runner, runnerId=global"
│           │
│           └── readiness.markAgentLoaded(true)
│               日志: "Agent init phase completed for application 'demo-a2a-agent-a', agent_loaded=true"
│
├── [事件2] A2AAgentCardDiscovery.discoverAll()
│   文件: service/agent-service-app/src/main/java/com/openjiuwen/service/app/controller/a2a/client/A2AAgentCardDiscovery.java#L89
│   │
│   ├── properties.getRemoteAgents() → [{name:"agentb", url:"http://localhost:18091/", timeoutSeconds:300, streaming:true}]
│   │
│   ├── validateRemoteAgents() → 校验 name 和 url 非空
│   │
│   └── for each remote agent:
│       tryDiscover(remote)
│       │
│       └── discoverAndRegister(remote)
│           │
│           ├── fetchCardInternal("http://localhost:18091/")
│           │   └── GET http://localhost:18091/.well-known/agent-card.json
│           │       → 返回 Agent B 的 AgentCard
│           │
│           └── registry.register("agentb", card, 300, true)
│               → 缓存到 A2ARemoteAgentCardRegistry
│               日志: "Discovered remote agent 'agentb'"
│
│       如果 fetch 失败 (Agent B 未启动):
│         → scheduleWithFixedDelay 每 30s 重试
│
└── 应用就绪, 等待 HTTP 请求
```

---

## 二、请求处理阶段调用栈（非流式 query）

### 2.1 HTTP 入口

```
POST http://localhost:18090/v1/query
Content-Type: application/json
Body: {"conversation_id":"a2a-test-1","message":"What is 1+1?","stream":false}
│
└── Spring MVC DispatcherServlet 分发到 QueryMvcController
    文件: service/agent-service-app/src/main/java/com/openjiuwen/service/app/controller/query/QueryMvcController.java
    │
    └── queryV1(@RequestBody rawBody, @RequestHeader headers, servletRequest, response)
        文件: QueryMvcController.java#L86
        │
        └── handleQuery(rawBody, headers, servletRequest, response)
            文件: QueryMvcController.java#L103
            │
            ├── objectMapper.readValue(rawBody, QueryRequest.class)
            │   → 解析 JSON 为 QueryRequest {conversation_id, message, stream=false}
            │
            ├── QueryIngressSupport.validateAndBuild(request, headers)
            │   → 构造 ServeRequest (conversation_id, messages, userId, spaceId 等)
            │
            ├── validateAndBuildMetadata(sr, headers, servletRequest, rawBody)
            │   → 构造 metadata Map (从 headers/query params/body 提取)
            │
            ├── isAgentReady()
            │   → readiness.isAgentLoaded() → true
            │
            ├── orchestratorProvider.getIfAvailable()
            │   → 返回 A2AEnabledServeOrchestrator Bean
            │
            ├── request.isStream() → false (非流式)
            │
            └── orchestrator.query(serveRequest)  ← 核心调用
```

### 2.2 Orchestrator 编排层

```
A2AEnabledServeOrchestrator.query(ServeRequest request)
文件: service/agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/A2AEnabledServeOrchestrator.java#L136
│
├── agentHandler.prepareTask(request)
│   → JiuwenCoreAgentHandler 默认返回 Optional.empty() (无 task-scoped 资源)
│
├── while (true) {  ← 中断-恢复循环
│   │
│   ├── [循环迭代1] syncResumePending(current, NOOP_OBSERVER)
│   │   文件: A2AEnabledServeOrchestrator.java#L340
│   │   │
│   │   ├── isClientToolResume(current) → false (首次请求, 无 remoteToolResults)
│   │   ├── batchCoordinator.resume(current, observer) → Optional.empty() (无待恢复的远端任务)
│   │   └── return QueryResumeResult.continueWith(current)
│   │
│   ├── agentHandler.query(current)  ← 第一次调用 Agent
│   │   │
│   │   │  详见 §2.3 JiuwenCoreAgentHandler.query 调用栈
│   │   │
│   │   └── 返回 QueryResponse:
│   │       result = {
│   │         role: "assistant",
│   │         _interrupt: {
│   │           type: "__interaction__",
│   │           state: "input_required",
│   │           message: "Agent B is ready to calculate 1+1. Continue? Reply yes or no.",
│   │           items: [{toolCallId:"call_xxx", toolName:"delegate_to_agentb", message:"..."}]
│   │         },
│   │         content: "Agent B is ready to calculate 1+1..."
│   │       }
│   │
│   ├── batchCoordinator.completeResume(current)
│   │   → 标记当前 resume 完成
│   │
│   ├── extractInterruptFromResponse(response)
│   │   → response.getResult()._interrupt 存在 → 返回 interruptData Map
│   │
│   ├── interruptData.isEmpty() → false (有中断)
│   │
│   └── handleQueryInterrupt(interruptData, current, response, NOOP_OBSERVER)
│       文件: A2AEnabledServeOrchestrator.java#L445
│       │
│       ├── isCoordinatorInterrupt(interruptData)
│       │   文件: A2AEnabledServeOrchestrator.java#L528
│       │   │
│       │   ├── interruptData.get("items") → 是 List, 非空
│       │   ├── 遍历 items:
│       │   │   item.context._interrupt_kind == "a2a_delegate" → true
│       │   └── 返回 true  ← 是 A2A 委派中断
│       │
│       └── batchCoordinator.execute(interruptData, current, NOOP_OBSERVER)
│           文件: service/agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/RemoteInvocationBatchCoordinator.java#L127
│           │
│           ├── batchMapper.parse(interrupt, request, parentTaskId, observer)
│           │   → 解析中断数据, 提取远端 Agent 名称 "agentb" 和消息内容
│           │   → 构造 RemoteInvocationBatch (包含 Member 列表)
│           │
│           ├── registerBatch(batch) → 注册到 state, 无冲突
│           │
│           ├── for each member: submit(new PendingInvocation(batch, member))
│           │   → RemoteInvocationCoordinatorState 调度
│           │   → 获取并发槽位后执行:
│           │
│           │   client.callOutcome(remoteCall, eventObserver)
│           │   文件: A2ARemoteAgentClient (RemoteAgentCaller 实现)
│           │   │
│           │   ├── resolveJsonRpcUrl("agentb")
│           │   │   → registry.resolveUrl("agentb") → "http://localhost:18091/"
│           │   │
│           │   ├── POST http://localhost:18091/a2a (JSON-RPC)
│           │   │   Body: A2A JSON-RPC message, params 包含用户消息
│           │   │   → Agent B 收到请求, Agent B 内部也走自己的 Orchestrator → AgentHandler 链路
│           │   │   → Agent B 的 CalcInterruptRail 触发中断
│           │   │   → Agent B 返回 INPUT_REQUIRED 中断 (需要用户确认)
│           │   │
│           │   └── 返回 RemoteCallOutcome:
│           │       outcome = INPUT_REQUIRED
│           │       interrupt = {message:"Agent B is ready to calculate 1+1. Continue?", ...}
│           │
│           └── batch.completion 完成 → 返回 BatchResolution:
│               isReadyToResume() = false (远端也返回了 INPUT_REQUIRED)
│               interrupt = 远端的中断数据
│
│   → queryBatchResolution 返回 QueryResumeResult.respond(interruptResponse)
│   → 构造 QueryResponse:
│       result = {
│         role: "assistant",
│         _interrupt: {type, state, message, items},  ← 透传远端 Agent B 的中断
│         content: message
│       }
│
│   → return response  ← 第一次请求结束, 返回中断给客户端
│
└── agentHandler.completeTask(taskToken) → 空操作
```

### 2.3 JiuwenCoreAgentHandler.query 内部调用栈

```
JiuwenCoreAgentHandler.query(ServeRequest request)
文件: service/agent-service-adapters/.../agentfw/JiuwenCoreAgentHandler.java#L228
│
├── FutureTask<QueryResponse> execution = new FutureTask<>(() -> {
│   │
│   ├── supportsInvoke(agent)
│   │   文件: JiuwenCoreAgentHandler.java#L388
│   │   │
│   │   ├── agent != null → true
│   │   ├── agent instanceof String → false
│   │   ├── 遍历 agent.getClass().getMethods():
│   │   │   ReActAgent 有 invoke() 方法 (声明类 != Object.class)
│   │   └── 返回 true → 走同步调用路径
│   │
│   ├── executeAgent(buildInputs(request), runnerSession(request))
│   │   │
│   │   ├── buildInputs(request)
│   │   │   文件: JiuwenCoreAgentHandler.java#L416
│   │   │   │
│   │   │   ├── inputs.put("conversation_id", request.getConversationId())
│   │   │   ├── inputs.put("messages", request.getMessages())
│   │   │   ├── inputs.put("user_id", request.getUserId())
│   │   │   ├── inputs.put("space_id", request.getSpaceId())
│   │   │   │
│   │   │   ├── request.getMetadata().get("runtime.remoteToolResults")
│   │   │   │   → 首次请求: null → 走正常路径
│   │   │   │   → 恢复请求: 是 Map → 走 InteractiveInput 路径
│   │   │   │
│   │   │   ├── [首次请求] inputs.put("query", request.lastUserQuery())
│   │   │   │   = inputs.put("query", "What is 1+1?")
│   │   │   │
│   │   │   └── [恢复请求] InteractiveInput interactiveInput = new InteractiveInput()
│   │   │       interactiveInput.setUserInputs(copyStringMap(resultMap))
│   │   │       inputs.put("query", interactiveInput)
│   │   │
│   │   ├── runnerSession(request)
│   │   │   文件: JiuwenCoreAgentHandler.java#L488
│   │   │   │
│   │   │   ├── resolveSessionId(request) → conversation_id ("a2a-test-1")
│   │   │   ├── hasAgentCard(agent) → true (ReActAgent 有 AgentCard)
│   │   │   ├── useRequestScopedSession(request) → false (默认)
│   │   │   └── 返回 sessionId ("a2a-test-1")
│   │   │
│   │   └── Runner.runAgent(agent, inputs, session, null)
│   │       (agent-core-java 执行 ReActAgent 推理循环)
│   │       │
│   │       │  ┌─── ReAct 循环 (reason → act → observe) ───┐
│   │       │  │                                            │
│   │       │  │  1. LLM 推理 (system-prompt 指示必须调用    │
│   │       │  │     delegate_to_agentb 工具)                │
│   │       │  │     → LLM 输出: tool_call(delegate_to_agentb, {message:"What is 1+1?"}) │
│   │       │  │                                            │
│   │       │  │  2. 工具执行拦截                            │
│   │       │  │     A2aDelegateRail.resolveInterrupt(ctx, toolCall, resumeInput=null)
│   │       │  │     文件: A2aDelegateRail.java#L47          │
│   │       │  │     │                                     │
│   │       │  │     ├── resumeInput == null → 首次调用      │
│   │       │  │     ├── 从 toolCall.getArguments() 提取 message: │
│   │       │  │     │   "What is 1+1?"                     │
│   │       │  │     ├── InterruptRequest.builder()        │
│   │       │  │     │     .message("What is 1+1?")        │
│   │       │  │     │     .context({agentName:"agentb", _interrupt_kind:"a2a_delegate"})│
│   │       │  │     └── return interrupt(request)        │
│   │       │  │         → 触发中断, ReAct 循环暂停       │
│   │       │  │                                            │
│   │       │  └────────────────────────────────────────────┘
│   │       │
│   │       └── 返回原始结果 (包含中断信息)
│   │           → Map: {result_type:"interrupt", state:[{type:"__interaction__", message:"...", ...}]}
│   │
│   └── toQueryResponse(rawResult, conversationId)
│       文件: JiuwenCoreAgentHandler.java#L330
│       │
│       ├── rawResult instanceof Map → true
│       ├── getQueryResponse(conversationId, map)
│       │   文件: JiuwenCoreAgentHandler.java#L367
│       │   │
│       │   ├── map.get("result_type") == "interrupt"
│       │   ├── map.get("state") instanceof List → true
│       │   ├── 遍历 states:
│       │   │   normalizeChunk(state) → 归一化为 Map
│       │   │   isCoreInteraction(normalized) → true (type == "__interaction__")
│       │   │   copyStringMap(interrupt) → 添加到 interrupts 列表
│       │   │
│       │   └── buildInterruptQueryResponse(normalizeInterrupts(interrupts), conversationId)
│       │       文件: JiuwenCoreAgentHandler.java#L393
│       │       │
│       │       └── result = {
│       │             role: "assistant",
│       │             _interrupt: {type:"__interaction__", state:"input_required", message:"...", items:[...]},
│       │             content: interrupt.getOrDefault("message", "")
│       │           }
│       │           返回 QueryResponse
│       │
│       └── 返回 QueryResponse (含 _interrupt)
│
└── execution.run() → execution.get()
    → 返回 QueryResponse 给 Orchestrator
```

---

## 三、中断恢复阶段调用栈

客户端收到中断后, 发送第二次请求 (同一 conversation_id, message="yes"):

### 3.1 远端 Agent B 中断冒泡回客户端

```
[第一次请求结束]

客户端收到响应:
{
  "result": {
    "role": "assistant",
    "_interrupt": {
      "type": "__interaction__",
      "state": "input_required",
      "message": "Agent B is ready to calculate 1+1. Continue? Reply yes or no.",
      "items": [{"toolCallId":"call_xxx", "toolName":"delegate_to_agentb", "message":"..."}]
    },
    "content": "Agent B is ready to calculate 1+1. Continue? Reply yes or no."
  },
  "conversation_id": "a2a-test-1"
}

中断链路:
Agent B 的 CalcInterruptRail 触发中断
  → Agent B 的 Orchestrator 将中断透传给 Agent A 的 Orchestrator (via A2A JSON-RPC)
    → Agent A 的 Orchestrator 的 batchCoordinator 发现远端返回 INPUT_REQUIRED
      → 构造 interruptResponse 返回给客户端
```

### 3.2 客户端发送恢复请求

```
POST http://localhost:18090/v1/query
Body: {"conversation_id":"a2a-test-1","message":"yes","stream":false}
│
└── QueryMvcController.handleQuery()
    └── orchestrator.query(serveRequest)
        │
        A2AEnabledServeOrchestrator.query(request)
        │
        ├── syncResumePending(current, NOOP_OBSERVER)
        │   ├── isClientToolResume(current) → false (metadata 中无 _interrupt)
        │   ├── batchCoordinator.resume(current, observer)
        │   │   → request.metadata 中有 runtime.remoteToolResults 且有 remoteBatchId?
        │   │   → 首次恢复请求: 没有 remoteToolResults
        │   │   → 检查 shadow task 是否存在
        │   │   → 如果存在待恢复的远端任务 → 返回 CompletableFuture
        │   │   └── 远端 Agent B 恢复执行, 用户确认 "yes" 传到 Agent B
        │   │       Agent B 的 CalcInterruptRail.resolveInterrupt(ctx, toolCall, resumeInput="yes")
        │   │       文件: CalcInterruptRail.java#L56
        │   │       │
        │   │       ├── resumeInput != null → true (有恢复输入)
        │   │       ├── normalizeConfirmation("yes") → "yes"
        │   │       ├── AFFIRMATIVE_RESPONSES.contains("yes") → true
        │   │       └── return reject(calculate(expression))
        │   │           → calculate("1+1") → "Calculation completed: 1+1 = 2"
        │   │           → reject → 将结果作为工具返回值, ReAct 循环恢复
        │   │
        │   │   远端 Agent B 完成执行, 返回结果
        │   │   → BatchResolution: isReadyToResume=true, shouldResume=true (tool-call 路径)
        │   │
        │   └── queryBatchResolution(current, resolution, null)
        │       → resolution.shouldResume() == true
        │       → buildBatchResumeRequest(current, resolution)
        │         文件: A2AEnabledServeOrchestrator.java#L512
        │         │
        │         ├── 复制 conversation_id, messages, userId 等
        │         ├── metadata.put("runtime.remoteToolResults", resolution.results())
        │         │   = {"agentb": "Calculation completed: 1+1 = 2"}
        │         ├── metadata.put("runtime.remoteBatchId", resolution.batchId())
        │         └── 返回新的 ServeRequest (带远端结果)
        │       → 返回 QueryResumeResult.continueWith(resume)
        │
        ├── current = resume (带 remoteToolResults 的请求)
        │
        ├── agentHandler.query(current)  ← 第二次调用 Agent
        │   │
        │   ├── buildInputs(request)
        │   │   → request.getMetadata().get("runtime.remoteToolResults") instanceof Map → true!
        │   │   → InteractiveInput interactiveInput = new InteractiveInput()
        │   │   → interactiveInput.setUserInputs({"agentb": "Calculation completed: 1+1 = 2"})
        │   │   → inputs.put("query", interactiveInput)
        │   │   → 这是中断恢复路径, 不是普通查询
        │   │
        │   ├── Runner.runAgent(agent, inputs, session, null)
        │   │   → ReActAgent 恢复执行
        │   │   → A2aDelegateRail.resolveInterrupt(ctx, toolCall, resumeInput="Calculation completed: 1+1 = 2")
        │   │     文件: A2aDelegateRail.java#L49
        │   │     │
        │   │     ├── resumeInput != null → true
        │   │     └── return reject(resumeInput)
        │   │         → 将远端结果直接作为工具返回值
        │   │         → ReAct 循环继续, LLM 基于工具结果生成最终回答
        │   │
        │   └── toQueryResponse(rawResult, conversationId)
        │       → rawResult 是最终回答
        │       → result = {role:"assistant", content:"The result of 1+1 is **2**."}
        │
        ├── extractInterruptFromResponse(response) → empty (无中断)
        │
        └── return response  ← 最终结果返回
```

### 3.3 HTTP 响应

```
QueryMvcController.handleQuery()
├── orchestrator.query() 返回 QueryResponse
└── writeJson(response, 200, queryResponse)
    → objectMapper.writeValue(response.getOutputStream(), queryResponse)

HTTP 响应:
Status: 200 OK
Content-Type: application/json
Body: {
  "result": {
    "role": "assistant",
    "content": "The result of 1+1 is **2**."
  },
  "conversation_id": "a2a-test-1"
}
```

---

## 四、完整调用栈总览图

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         启动阶段                                         │
│                                                                          │
│  main()                                                                  │
│  └→ SpringApplicationBuilder.run()                                       │
│     ├→ 加载 yml 配置 (base → agent-a 覆盖)                               │
│     ├→ LlmAutoConfiguration → LlmConfigResolver Bean                     │
│     ├→ AgentServiceAutoConfiguration → Controller/Lifecycle Beans        │
│     ├→ A2AAutoConfiguration → Orchestrator/Discovery/Registry Beans     │
│     ├→ A2aAgentADemoApplication.agentAHandler()                          │
│     │  ├→ llmConfigResolver.resolveRequired() (apiconfig.json + yml)     │
│     │  ├→ ExampleReActAgentFactory.build() (构造 ReActAgent)             │
│     │  ├→ agent.registerRail(new A2aDelegateRail()) (注册中断 Rail)     │
│     │  └→ new JiuwenCoreAgentHandler(agent) (适配器)                     │
│     │                                                                    │
│     └→ [ApplicationReadyEvent]                                           │
│        ├→ AgentLifecycleBootstrap → InitPhaseExecutor → handler.start()  │
│        │  → Runner.start() (启动 agent-core Runner)                      │
│        └→ A2AAgentCardDiscovery.discoverAll()                             │
│           → GET http://localhost:18091/.well-known/agent-card.json       │
│           → registry.register("agentb", card, 300, true)                 │
└──────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────┐
│                    请求阶段 (第一次: "What is 1+1?")                     │
│                                                                          │
│  POST /v1/query                                                          │
│  └→ QueryMvcController.queryV1()                                         │
│     └→ handleQuery() → orchestrator.query()                              │
│        └→ A2AEnabledServeOrchestrator.query()                            │
│           ├→ agentHandler.prepareTask()                                  │
│           ├→ syncResumePending() → 无待恢复任务                          │
│           ├→ agentHandler.query()                                        │
│           │  └→ JiuwenCoreAgentHandler.query()                           │
│           │     ├→ buildInputs() → inputs={query:"What is 1+1?", ...}    │
│           │     ├→ Runner.runAgent() → ReActAgent 推理                   │
│           │     │  └→ LLM 调用 delegate_to_agentb 工具                  │
│           │     │  └→ A2aDelegateRail.resolveInterrupt() → interrupt()  │
│           │     └→ toQueryResponse() → QueryResponse (含 _interrupt)    │
│           │                                                              │
│           ├→ extractInterruptFromResponse() → 有 a2a_delegate 中断      │
│           └→ handleQueryInterrupt()                                     │
│              └→ batchCoordinator.execute()                               │
│                 ├→ batchMapper.parse() → 解析远端 Agent 名称和消息        │
│                 └→ client.callOutcome()                                 │
│                    └→ POST http://localhost:18091/a2a (JSON-RPC)        │
│                       └→ Agent B 处理 → CalcInterruptRail 中断          │
│                       └→ 返回 INPUT_REQUIRED                            │
│              └→ 返回中断 QueryResponse 给客户端                          │
└──────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────┐
│                    恢复阶段 (第二次: "yes")                             │
│                                                                          │
│  POST /v1/query (conversation_id="a2a-test-1", message="yes")           │
│  └→ QueryMvcController.queryV1()                                         │
│     └→ orchestrator.query()                                              │
│        └→ A2AEnabledServeOrchestrator.query()                             │
│           ├→ syncResumePending()                                         │
│           │  └→ batchCoordinator.resume()                               │
│           │     └→ 远端 Agent B 恢复, CalcInterruptRail 收到 "yes"      │
│           │        └→ AFFIRMATIVE_RESPONSES.contains("yes") → true      │
│           │        └→ reject(calculate("1+1")) → "Calculation completed" │
│           │     └→ BatchResolution: shouldResume=true                   │
│           │     └→ buildBatchResumeRequest() → metadata 含 remoteToolResults │
│           │                                                              │
│           ├→ agentHandler.query(resumeRequest)                           │
│           │  └→ JiuwenCoreAgentHandler.query()                           │
│           │     ├→ buildInputs() → query=InteractiveInput(远端结果)      │
│           │     ├→ Runner.runAgent() → ReActAgent 恢复                   │
│           │     │  └→ A2aDelegateRail.resolveInterrupt(resumeInput=结果) │
│           │     │     └→ reject(resumeInput) → 结果回喂 LLM             │
│           │     │     └→ LLM 生成最终回答                                 │
│           │     └→ toQueryResponse() → QueryResponse (最终结果)          │
│           │                                                              │
│           └→ extractInterruptFromResponse() → empty (无中断)            │
│              └→ return response → HTTP 200 OK                            │
│                                                                          │
│  最终响应: {"result":{"role":"assistant","content":"The result is 2."}} │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 五、关键源码文件索引

| 文件 | 作用 |
|---|---|
| [A2aAgentADemoApplication.java](src/main/java/com/openjiuwen/service/demo/example/a2a/A2aAgentADemoApplication.java) | 启动入口, 定义 @Bean agentAHandler |
| [ExampleReActAgentFactory.java](../../support/src/main/java/com/openjiuwen/service/demo/example/support/ExampleReActAgentFactory.java) | 构建 ReActAgent, 配置 LLM/模型客户端 |
| [A2aDelegateRail.java](src/main/java/com/openjiuwen/service/demo/example/a2a/A2aDelegateRail.java) | Agent A 的中断 Rail, 拦截 delegate_to_agentb 工具调用 |
| [LlmAutoConfiguration.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/autoconfigure/LlmAutoConfiguration.java) | 注册 LlmConfigResolver Bean |
| [LlmConfigResolver.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/config/llm/LlmConfigResolver.java) | 合并 yml + apiconfig.json → ResolvedLlmConfig |
| [ApiConfigLoader.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/config/llm/ApiConfigLoader.java) | 加载 apiconfig.json (环境变量/auto-discover) |
| [AgentServiceAutoConfiguration.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/autoconfigure/AgentServiceAutoConfiguration.java) | 注册 Lifecycle/Controller/Readiness Bean |
| [A2AAutoConfiguration.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/autoconfigure/A2AAutoConfiguration.java) | 注册 A2A SDK/Orchestrator/Discovery Bean |
| [AgentLifecycleBootstrap.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/lifecycle/AgentLifecycleBootstrap.java) | 监听 ApplicationReadyEvent → 触发 init |
| [InitPhaseExecutor.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/lifecycle/InitPhaseExecutor.java) | 执行 init hooks + handler.start() |
| [A2AAgentCardDiscovery.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/controller/a2a/client/A2AAgentCardDiscovery.java) | 启动时拉取远端 AgentCard |
| [QueryMvcController.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/controller/query/QueryMvcController.java) | HTTP 入口, POST /v1/query |
| [A2AEnabledServeOrchestrator.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/A2AEnabledServeOrchestrator.java) | 中断-恢复循环 + 远端委派 |
| [RemoteInvocationBatchCoordinator.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/RemoteInvocationBatchCoordinator.java) | 远端调用 fan-out/fan-in 协调 |
| [JiuwenCoreAgentHandler.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/agentfw/JiuwenCoreAgentHandler.java) | 适配器: ServeRequest → Runner → QueryResponse |
| [A2AProperties.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/config/A2AProperties.java) | 绑定 openjiuwen.service.a2a.* 配置 |
| [application-a2a-agent-a.yml](application-a2a-agent-a.yml) | Agent A 专属配置 |
| [application-base.yml](../../config/application-base.yml) | 共享基础配置 |

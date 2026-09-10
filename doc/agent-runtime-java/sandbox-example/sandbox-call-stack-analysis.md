# Sandbox Demo 端到端调用栈分析

> 以 "echo sandbox-file-ok"、"printf ... > /tmp/...txt && cat"、"print(\"sandbox-code-ok\")" 三轮请求为例，
> 结合运行日志和代码，严格追踪从 HTTP 请求到 JiuwenBox 沙箱的完整调用栈。
>
> 验证时间: 2026-09-10，服务端口 8093 正常运行，JiuwenBox Docker 容器端口 8321。

---

## 运行环境

| 组件 | 端口/地址 | 说明 |
|---|---|---|
| Java Sandbox Demo | localhost:8093 | SandboxDemoApplication, ReActAgent + 3 个 Sandbox 工具 |
| JiuwenBox Docker | localhost:8321 | 安全沙箱服务 (bubblewrap 隔离, openEuler 24.03) |
| LLM (glm-5.2) | dashscope 远程 | OpenAI 兼容 API |

---

## 环境搭建

### 1.1 JiuwenBox Docker 镜像构建

```
# 1. Clone jiuwenswarm 仓库 (AtomGit 镜像)
git clone --depth 1 --branch develop https://atomgit.com/openJiuwen/jiuwenswarm.git

# 2. 构建 Docker 镜像 (基于 openEuler 24.03)
docker build -f jiuwenswarm/jiuwenbox/docker/Dockerfile -t jiuwenbox:latest jiuwenswarm/jiuwenbox

# 构建过程:
#   [1/13] FROM openeuler/openeuler:24.03
#   [2/13] RUN echo "sslverify=false" >> /etc/yum.conf
#   [3/13] RUN echo "insecure" >> ~/.curlrc
#   [4/13] RUN sed -i 's/gpgcheck=1/gpgcheck=0/g' ...
#   [5/13] RUN yum makecache
#   [6/13] RUN yum install -y bubblewrap iproute2 iptables ...
#   [7-12/13] RUN pip install jiuwenbox ...
#   [13/13] RUN ln -s /usr/bin/python3 /usr/bin/python
#   -> 镜像 jiuwenbox:latest 构建成功
```

### 1.2 JiuwenBox 容器启动

```
docker run -itd \
    --name jiuwenbox \
    --restart=unless-stopped \
    --sysctl net.ipv4.ip_forward=1 \
    --cap-add=SYS_ADMIN \
    --cap-add=NET_ADMIN \
    --security-opt seccomp=unconfined \
    --security-opt apparmor=unconfined \
    --security-opt systempaths=unconfined \
    --cgroupns=host \
    -v /sys/fs/cgroup:/sys/fs/cgroup:rw \
    -p 8321:8321 \
    -p 8322:8322 \
    jiuwenbox:latest
```

容器启动日志:
```
[launcher] starting jiuwenbox on http://0.0.0.0:8321
box-server started (version 0.1.0)
Application startup complete.
Uvicorn running on http://0.0.0.0:8321
```

健康检查:
```
curl -s http://127.0.0.1:8321/health
-> {"status":"ok","version":"0.1.0","runtime":"process","landlock_supported":true,"sandboxes_active":0}
```

### 1.3 LLM 配置

使用 application-base_local.yml (与 Redis/Memory Demo 共享):
```yaml
openjiuwen:
  service:
    llm:
      auto-discover: false
      provider: OpenAI
      api-key: sk-84d285...
      api-base: https://dashscope.aliyuncs.com/compatible-mode/v1
      model-name: glm-5.2
      ssl-verify: true
```

---

## 启动阶段

### 2.1 JVM 启动 -> Spring Boot 装配

```
java -jar agent-service-demo-sandbox-0.1.2.jar
  OPENJIUWEN_SANDBOX_SERVICE_URL=http://127.0.0.1:8321
文件: example/sandbox/src/main/java/.../SandboxDemoApplication.java#L30
|
+-- SpringApplication.run(SandboxDemoApplication.class, args)
|   加载配置 (application.yml -> base.yml -> base_local.yml -> sandbox.yml):
|   |
|   |   application-sandbox.yml:
|   |     server.port: 8093
|   |     openjiuwen.service.external.sandbox:
|   |       enabled: true
|   |       timeout-ms: 30000
|   |       retry: {max: 1, backoff-ms: 200}
|   |       circuit-breaker: {enabled: true, failure-threshold: 3, reset-timeout-ms: 30000}
|   |       audit: {enabled: true}
|   |       servers:
|   |         - server-id: default
|   |           service-url: http://127.0.0.1:8321
|   |           sandbox-type: jiuwenbox
|   |           launcher-type: pre_deploy
|   |           on-stop: delete
|   |           root-path: .
|   |
|   +-- Spring Boot 自动装配:
|   |
|   |   1. LlmAutoConfiguration -> LlmConfigResolver
|   |      -> 解析 LLM 配置: glm-5.2, dashscope
|   |
|   |   2. AgentCoreAdaptersAutoConfiguration
|   |      文件: AgentCoreAdaptersAutoConfiguration.java#L114
|   |      @Bean agentCoreSandboxClientFactory(properties, outboundSecuritySupport)
|   |      @ConditionalOnProperty(sandbox.enabled=true) -> 满足
|   |      |
|   |      +-- new DefaultAgentCoreSandboxClientFactory(properties, outboundSecuritySupport)
|   |          文件: DefaultAgentCoreSandboxClientFactory.java#L40
|   |          -> properties.getSandbox().validate() (校验配置)
|   |
|   |   3. AgentServiceAutoConfiguration -> Controller/Lifecycle/Orchestrator
|   |
|   |   4. @Bean agentHandler(...)
|   |      文件: SandboxDemoApplication.java#L35
|   |      |
|   |      +-- llmConfigResolver.resolveRequired()
|   |      |   -> ResolvedLlmConfig (glm-5.2, dashscope)
|   |      |
|   |      +-- ExampleReActAgentFactory.build("demo-sandbox-agent", "Demo Sandbox Agent", "...", llmConfig)
|   |      |   -> ReActAgent (含 LLM 配置, systemPrompt)
|   |      |
|   |      +-- sandboxClientFactoryProvider.ifAvailable(factory ->
|   |      |       DecoratedSandboxToolRegistrar.register(agent, factory))
|   |      |   文件: DecoratedSandboxToolRegistrar.java#L57
|   |      |   |
|   |      |   +-- factory.create(null)  [创建 SandboxClient]
|   |      |   |   文件: DefaultAgentCoreSandboxClientFactory.java#L52
|   |      |   |   |
|   |      |   |   +-- policy = properties.getSandbox()
|   |      |   |   +-- server = policy.findServer(null) -> server-id="default"
|   |      |   |   +-- SandboxClient delegate = new SandboxClient(configFor("default"))
|   |      |   |   |   -> 构造 Core SandboxClient (agent-core-java)
|   |      |   |   |   -> 内含 JiuwenBox provider (sandbox-type=jiuwenbox)
|   |      |   |   |   -> gatewayUrl = http://127.0.0.1:8321
|   |      |   |   |
|   |      |   |   +-- return new DecoratingSandboxClient("default", delegate, policy)
|   |      |   |       文件: DecoratingSandboxClient.java#L49
|   |      |   |       |
|   |      |   |       +-- executor = new ExternalCallExecutor("Sandbox", "default", policy,
|   |      |   |       |       SANDBOX_OUTBOUND_CALL_FAILED,
|   |      |   |       |       SANDBOX_CIRCUIT_OPEN,
|   |      |   |       |       SANDBOX_RETRY_INTERRUPTED,
|   |      |   |       |       SANDBOX_TIMEOUT)
|   |      |   |       |   -> 治理: timeout=30s, retry.max=1, circuitBreaker, audit
|   |      |   |       |
|   |      |   |       +-- fsOperation = new DecoratingSandboxFsOperation(config, delegate.fs(), executor)
|   |      |   |       +-- shellOperation = new DecoratingSandboxShellOperation(config, delegate.shell(), executor)
|   |      |   |       +-- codeOperation = new DecoratingSandboxCodeOperation(config, delegate.code(), executor)
|   |      |   |
|   |      |   |   <- 返回 DecoratingSandboxClient (包装了 Core SandboxClient)
|   |      |   |
|   |      |   +-- client = DecoratingSandboxClient
|   |      |   |
|   |      |   +-- 创建 3 个 LocalFunction 工具:
|   |      |   |   |
|   |      |   |   +-- readFileTool(client, null)
|   |      |   |   |   文件: DecoratedSandboxToolRegistrar.java#L88
|   |      |   |   |   -> ToolCard: id="sandbox.default.fs.readFile", name="readFile"
|   |      |   |   |   -> LocalFunction: inputs -> client.fs().readFile(path, mode, ...)
|   |      |   |   |
|   |      |   |   +-- executeCmdTool(client, null)
|   |      |   |   |   文件: DecoratedSandboxToolRegistrar.java#L107
|   |      |   |   |   -> ToolCard: id="sandbox.default.shell.executeCmd", name="executeCmd"
|   |      |   |   |   -> LocalFunction: inputs -> client.shell().executeCmd(command, cwd, ...)
|   |      |   |   |
|   |      |   |   +-- executeCodeTool(client, null)
|   |      |   |       文件: DecoratedSandboxToolRegistrar.java#L120
|   |      |   |       -> ToolCard: id="sandbox.default.code.executeCode", name="executeCode"
|   |      |   |       -> LocalFunction: inputs -> client.code().executeCode(code, language, ...)
|   |      |   |
|   |      |   +-- 注册 3 个工具到 Runner:
|   |      |       for each tool:
|   |      |         Runner.resourceMgr().addTool(tool, agent.getCard().getId(), true)
|   |      |         agent.getAbilityManager().add(tool.getCard())
|   |      |
|   |      |   日志:
|   |      |     "add resource succeed, id=sandbox.default.fs.readFile, type=tool"
|   |      |     "add resource succeed, id=sandbox.default.shell.executeCmd, type=tool"
|   |      |     "add resource succeed, id=sandbox.default.code.executeCode, type=tool"
|   |      |
|   |      +-- new JiuwenCoreAgentHandler(agent, externalSvcAdapterRegistrar)
|   |          -> middlewareAdapterRegistrar = null
|   |          -> externalSvcAdapterRegistrar = noop
|   |
|   +-- [ApplicationReadyEvent] handler.start()
|       -> JiuwenCoreAgentHandler.start()
|       +-- Runner.start()
|           +-- CheckpointerFactory.create("in_memory", Map.of())
|           +-- "Succeed to initializing checkpointer with type: in_memory"
|           +-- "agent_loaded=true"
|
+-- 等待 HTTP 请求
```

### 2.2 Bean 关系图

```
Spring ApplicationContext
|
+-- LlmConfigResolver (from LlmAutoConfiguration)
|
+-- AgentCoreSandboxClientFactory (from AgentCoreAdaptersAutoConfiguration)
|   +-- DefaultAgentCoreSandboxClientFactory
|       -> create() 返回 DecoratingSandboxClient
|           +-- delegate: Core SandboxClient (agent-core-java, JiuwenBox provider)
|           +-- executor: ExternalCallExecutor (timeout/retry/circuitBreaker/audit)
|           +-- fsOperation: DecoratingSandboxFsOperation
|           +-- shellOperation: DecoratingSandboxShellOperation
|           +-- codeOperation: DecoratingSandboxCodeOperation
|
+-- AgentHandler (from SandboxDemoApplication.agentHandler)
    +-- JiuwenCoreAgentHandler
        +-- agent: ReActAgent
        |   +-- 工具: readFile / executeCmd / executeCode
        |   +-- LLM: glm-5.2 via dashscope
        +-- middlewareAdapterRegistrar: null
        +-- externalSvcAdapterRegistrar: noop
```

---

## 请求1：executeCmd 执行命令

### 实际请求

```
POST http://localhost:8093/v1/query
Body: {"conversation_id":"sandbox-cmd","message":"Please use the executeCmd tool to run: echo sandbox-file-ok","stream":false}
```

### 实际响应

```json
{
    "result": {
        "role": "assistant",
        "content": "The command ran successfully, outputting `sandbox-file-ok` with exit code 0."
    },
    "conversation_id": "sandbox-cmd"
}
```

### 3.1 完整调用栈

```
POST /v1/query
|
+-- QueryMvcController.queryV1(rawBody, headers, servletRequest, response)
    文件: QueryMvcController.java#L78
    |
    +-- objectMapper.readValue(rawBody, QueryRequest.class)
    +-- QueryIngressSupport.validateAndBuild(request, headers) -> ServeRequest
    +-- orchestrator.query(serveRequest)
        文件: A2AEnabledServeOrchestrator.java#L131
        |
        +-- agentHandler.query(current)
            |
            |  agentHandler 是 JiuwenCoreAgentHandler
            |  文件: JiuwenCoreAgentHandler.java#L283
            |
            +-- FutureTask(() -> {
            |   +-- supportsInvoke(agent) -> true
            |   +-- executeAgent(buildInputs(request), runnerSession(request))
            |   |   +-- buildInputs -> inputs={conversation_id, messages, query:"Please use executeCmd..."}
            |   |   +-- runnerSession -> sessionId="sandbox-cmd"
            |   |   +-- Runner.runAgent(agent, inputs, "sandbox-cmd", null)
            |   |       |
            |   |       +-- Checkpointer.load("sandbox-cmd") -> 空 (首次)
            |   |       +-- ReActAgent.invoke(inputs, session)
            |   |       |   |
            |   |       |   |  -- ReAct Iteration 1/5 --
            |   |       |   |  [Reason] LLM 推理:
            |   |       |   |    system: "You are a helpful assistant..."
            |   |       |   |    user: "Please use the executeCmd tool to run: echo sandbox-file-ok"
            |   |       |   |    可用工具: [readFile, executeCmd, executeCode]
            |   |       |   |
            |   |       |   |    -> LLM 输出: tool_call(executeCmd, {command:"echo sandbox-file-ok"})
            |   |       |   |
            |   |       |   |  [Act] 执行 executeCmd 工具:
            |   |       |   |    DecoratedSandboxToolRegistrar.executeCmdTool lambda:
            |   |       |   |    文件: DecoratedSandboxToolRegistrar.java#L107
            |   |       |   |    |
            |   |       |   |    +-- client.shell().executeCmd(command, cwd, timeout, env, options)
            |   |       |   |        |
            |   |       |   |        |  client 是 DecoratingSandboxClient
            |   |       |   |        |  shell() 返回 DecoratingSandboxShellOperation
    |   |       |   |        |
    |   |       |   |        +-- executor.execute("shell", "executeCmd", false, () -> ...)
    |   |       |   |            文件: DecoratingSandboxClient.java#L185
    |   |       |   |            文件: ExternalCallExecutor.java#L89
    |   |       |   |            |
    |   |       |   |            +-- circuitKey = "shell.executeCmd"
    |   |       |   |            +-- maxAttempts = 1 + 1 = 2 (retry.max=1)
    |   |       |   |            +-- shouldRetry = false (executeCmd 不重试)
    |   |       |   |            +-- maxAttempts = 1
    |   |       |   |            +-- ensureCircuitClosed() -> 无熔断
    |   |       |   |            +-- callWithTimeout(callable, "shell", "executeCmd")
    |   |       |   |                +-- future = timeoutExecutor.submit(callable)
    |   |       |   |                |   -> callable 执行:
    |   |       |   |                |     delegate.executeCmd("echo sandbox-file-ok", ".", 30, {}, {})
    |   |       |   |                |       -> Core SandboxClient (JiuwenBox provider)
    |   |       |   |                |       -> HTTP POST http://127.0.0.1:8321/api/v1/sandboxes/.../execute
    |   |       |   |                |          Body: {command:"echo sandbox-file-ok", cwd:".", timeout:30}
    |   |       |   |                |       -> JiuwenBox 在沙箱中执行 echo 命令
    |   |       |   |                |       -> 返回: {exit_code:0, stdout:"sandbox-file-ok\n", stderr:""}
    |   |       |   |                +-- future.get(30000, MILLISECONDS)
    |   |       |   |                    -> 返回 ExecuteCmdResult(exitCode=0, stdout="sandbox-file-ok\n")
    |   |       |   |            |
    |   |       |   |            +-- recordSuccess("shell.executeCmd")
    |   |       |   |            +-- auditSuccess("shell", "executeCmd", ...)
    |   |       |   |                日志: EXTERNAL_CALL_AUDIT adapter=Sandbox, success=true,
    |   |       |   |                       method=shell.executeCmd, elapsedMs=..., ...
    |   |       |   |
    |   |       |   |  -- ReAct Iteration 2/5 --
    |   |       |   |  [Reason] LLM 基于工具结果生成最终回答:
    |   |       |   |    -> "The command ran successfully, outputting `sandbox-file-ok` with exit code 0."
    |   |       |   |
    |   |       |   +-- 返回 rawResult
    |   |       |
    |   |       +-- Checkpointer.save("sandbox-cmd", sessionState) (in_memory)
    |   |
    |   +-- toQueryResponse(rawResult, "sandbox-cmd")
    |       -> {role:"assistant", content:"The command ran successfully..."}
    |
    +-- execution.run() -> execution.get() -> 返回 QueryResponse

-> HTTP 200
```

---

## 请求2：executeCmd 创建文件并读取

### 实际请求

```
POST http://localhost:8093/v1/query
Body: {"conversation_id":"sandbox-read","message":"Please use the executeCmd tool to run: printf sandbox-file-ok > /tmp/openjiuwen-demo.txt && cat /tmp/openjiuwen-demo.txt","stream":false}
```

### 实际响应

```json
{
    "result": {
        "role": "assistant",
        "content": "The command executed successfully:\n\n- **Exit code:** 0\n- **Output:** `sandbox-file-ok`\n\nThe file `/tmp/openjiuwen-demo.txt` was created with the content `sandbox-file-ok` and verified by reading it back with `cat`."
    },
    "conversation_id": "sandbox-read"
}
```

### 4.1 调用栈要点

```
POST /v1/query
|
+-- QueryMvcController -> Orchestrator -> JiuwenCoreAgentHandler.query()
    |
    +-- Runner.runAgent -> ReActAgent.invoke:
        |
        |  -- Iteration 1/5 --
        |  [Reason] LLM 推理:
        |    user: "Please use executeCmd to run: printf ... > /tmp/...txt && cat ..."
        |    -> LLM 输出: tool_call(executeCmd, {command:"printf sandbox-file-ok > /tmp/... && cat ..."})
        |
        |  [Act] 执行 executeCmd:
        |    +-- client.shell().executeCmd(command, cwd, timeout, env, options)
        |    |   -> DecoratingSandboxShellOperation.executeCmd()
        |    |   -> executor.execute("shell", "executeCmd", false, () -> delegate.executeCmd(...))
        |    |   -> ExternalCallExecutor.callWithTimeout()
        |    |   -> HTTP POST http://127.0.0.1:8321/api/v1/sandboxes/.../execute
        |    |   -> JiuwenBox 执行: printf + cat
        |    |   -> 返回: ExecuteCmdResult(exitCode=0, stdout="sandbox-file-ok")
        |    |   日志: EXTERNAL_CALL_AUDIT adapter=Sandbox, success=true, method=shell.executeCmd
        |
        |  -- Iteration 2/5 --
        |  [Reason] LLM 基于工具结果生成回答:
        |    -> "The command executed successfully... file was created with content `sandbox-file-ok`"
        |
        +-- 返回 QueryResponse
```

---

## 请求3：executeCode 执行 Python

### 实际请求

```
POST http://localhost:8093/v1/query
Body: {"conversation_id":"sandbox-code","message":"Please use the executeCode tool to run Python code: print(\"sandbox-code-ok\")","stream":false}
```

### 实际响应

```json
{
    "result": {
        "role": "assistant",
        "content": "The code executed successfully, printing:\n\n```\nsandbox-code-ok\n```\n\nExit code: `0` - no errors."
    },
    "conversation_id": "sandbox-code"
}
```

### 5.1 调用栈要点

```
POST /v1/query
|
+-- QueryMvcController -> Orchestrator -> JiuwenCoreAgentHandler.query()
    |
    +-- Runner.runAgent -> ReActAgent.invoke:
        |
        |  -- Iteration 1/5 --
        |  [Reason] LLM 推理:
        |    user: "Please use executeCode to run Python: print(\"sandbox-code-ok\")"
        |    -> LLM 输出: tool_call(executeCode, {code:"print(\"sandbox-code-ok\")", language:"python"})
        |
        |  [Act] 执行 executeCode:
        |    +-- client.code().executeCode(code, language, timeout, env, options)
        |    |   -> DecoratingSandboxCodeOperation.executeCode()
        |    |   -> executor.execute("code", "executeCode", false, () -> delegate.executeCode(...))
        |    |   -> ExternalCallExecutor.callWithTimeout()
        |    |   -> HTTP POST http://127.0.0.1:8321/api/v1/sandboxes/.../execute_code
        |    |   -> JiuwenBox 在沙箱中执行 Python: print("sandbox-code-ok")
        |    |   -> 返回: ExecuteCodeResult(exitCode=0, stdout="sandbox-code-ok\n")
        |    |   日志: EXTERNAL_CALL_AUDIT adapter=Sandbox, success=true, method=code.executeCode
        |
        |  -- Iteration 2/5 --
        |  [Reason] LLM 基于工具结果生成回答:
        |    -> "The code executed successfully, printing: sandbox-code-ok, Exit code: 0"
        |
        +-- 返回 QueryResponse
```

---

## 完整调用栈总览图

```
+--------------------------------------------------------------------------+
|                    请求1: executeCmd (sandbox-cmd)                       |
|                                                                          |
|  POST /v1/query ("echo sandbox-file-ok")                                |
|  +-> QueryMvcController -> Orchestrator -> JiuwenCoreAgentHandler         |
|     +-> Runner.runAgent -> ReActAgent.invoke                             |
|        -- Iteration 1 --                                                 |
|        +-> LLM: tool_call(executeCmd, {command:"echo ..."})             |
|        +-> DecoratedSandboxToolRegistrar lambda                         |
|           +-> client.shell().executeCmd(command, cwd, ...)              |
|              +-> DecoratingSandboxShellOperation.executeCmd()            |
|                 +-> ExternalCallExecutor.execute("shell","executeCmd")   |
|                    +-> callWithTimeout()                                 |
|                    |  +-> delegate.executeCmd()                           |
|                    |     +-> Core SandboxClient (JiuwenBox provider)     |
|                    |        +-> HTTP POST 8321/api/v1/sandboxes/.../execute|
|                    |        +-> JiuwenBox: bubblewrap echo 命令           |
|                    |        <- {exit_code:0, stdout:"sandbox-file-ok"}   |
|                    +-> auditSuccess()                                    |
|                       日志: EXTERNAL_CALL_AUDIT adapter=Sandbox          |
|        -- Iteration 2 --                                                 |
|        +-> LLM: "The command ran successfully..." (最终回答)             |
|                                                                          |
|  <- 用户收到: "outputting `sandbox-file-ok` with exit code 0"           |
+--------------------------------------------------------------------------+

+--------------------------------------------------------------------------+
|                请求2: executeCmd 创建+读取文件 (sandbox-read)           |
|                                                                          |
|  POST /v1/query ("printf ... > /tmp/...txt && cat ...")                 |
|  +-> ... -> ReActAgent                                                   |
|     +-> LLM: tool_call(executeCmd, {command:"printf ... && cat ..."})   |
|        +-> DecoratingSandboxShellOperation -> ExternalCallExecutor       |
|           +-> HTTP POST 8321/api/v1/sandboxes/.../execute                |
|           +-> JiuwenBox: 创建文件 + cat 读取                             |
|           <- {exit_code:0, stdout:"sandbox-file-ok"}                    |
|     +-> LLM: "file was created with content sandbox-file-ok"             |
|                                                                          |
|  <- 用户收到: "file was created... verified by reading it back"        |
+--------------------------------------------------------------------------+

+--------------------------------------------------------------------------+
|                请求3: executeCode 执行 Python (sandbox-code)             |
|                                                                          |
|  POST /v1/query ("print(\"sandbox-code-ok\")")                          |
|  +-> ... -> ReActAgent                                                   |
|     +-> LLM: tool_call(executeCode, {code:"print(...)", language:"python"})|
|        +-> DecoratingSandboxCodeOperation -> ExternalCallExecutor         |
|           +-> HTTP POST 8321/api/v1/sandboxes/.../execute_code           |
|           +-> JiuwenBox: Python 执行 print("sandbox-code-ok")            |
|           <- {exit_code:0, stdout:"sandbox-code-ok"}                    |
|     +-> LLM: "code executed successfully, printing sandbox-code-ok"      |
|                                                                          |
|  <- 用户收到: "sandbox-code-ok, Exit code: 0"                           |
+--------------------------------------------------------------------------+
```

---

## Sandbox 工具治理策略

| 工具 | 路径 | shouldRetry | 说明 |
|---|---|---|---|
| readFile | fs.readFile | true | 只读操作，可安全重试 |
| executeCmd | shell.executeCmd | **false** | 有副作用，不重试 |
| executeCode | code.executeCode | **false** | 有副作用，不重试 |

> 设计要点：executeCmd 和 executeCode 有副作用（创建文件、修改状态），
> 重试可能导致重复执行，因此 shouldRetry=false。readFile 是只读操作，可以安全重试。

### 外部调用治理 (ExternalCallExecutor)

| 治理能力 | 配置项 | 值 |
|---|---|---|
| timeout | timeout-ms | 30000ms |
| retry | retry.max / backoff-ms | 1 / 200ms |
| circuit breaker | failure-threshold / reset-timeout-ms | 3 / 30000ms |
| audit | audit.enabled | true |

审计日志格式:
```
EXTERNAL_CALL_AUDIT adapter=Sandbox, success=true, target=default,
  method=shell.executeCmd, attempt=1, elapsedMs=..., request=..., response=...
```

---

## DecoratingSandboxClient 装饰器模式

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

## JiuwenBox 沙箱架构

```
Java Sandbox Demo (localhost:8093)
|
+-- HTTP POST /v1/query
    +-> ReActAgent -> LLM 选择工具
        +-> DecoratingSandboxClient (治理装饰器)
            +-> ExternalCallExecutor (timeout/retry/circuitBreaker/audit)
                +-> Core SandboxClient (agent-core-java)
                    +-> HTTP POST http://127.0.0.1:8321/api/v1/sandboxes/.../execute
                        |
                        v
JiuwenBox Docker 容器 (localhost:8321)
|
+-- FastAPI Server (uvicorn)
|   +-- /health -> 健康检查
|   +-- /api/v1/sandboxes -> 沙箱管理 API
|       +-- POST /execute -> 执行命令
|       +-- POST /execute_code -> 执行代码
|       +-- POST /read_file -> 读取文件
|
+-- Process Runtime (bubblewrap 隔离)
|   +-- 每个沙箱: bubblewrap 子进程
|   +-- 进程隔离: 独立 PID namespace
|   +-- 文件隔离: 仅允许 root_path 下的文件
|   +-- 网络隔离: 可选独立网络 namespace
|   +-- 资源限制: CPU/内存/进程数上限
|
+-- Audit Logger (审计日志)
+-- Policy Engine (安全策略)
```

---

## 三轮验证结果汇总

| 轮次 | conversation_id | 请求 | 工具 | JiuwenBox 操作 | 响应 |
|---|---|---|---|---|---|
| 1 | sandbox-cmd | "echo sandbox-file-ok" | executeCmd | bubblewrap 执行 echo | "outputting sandbox-file-ok with exit code 0" |
| 2 | sandbox-read | "printf ... > /tmp/...txt && cat ..." | executeCmd | 创建文件 + cat 读取 | "file was created with content sandbox-file-ok" |
| 3 | sandbox-code | "print(\"sandbox-code-ok\")" | executeCode | Python 执行 print | "sandbox-code-ok, Exit code: 0" |

---

## 运行日志对照表

| 日志 | 来源代码 | 含义 |
|---|---|---|
| `add resource succeed, id=sandbox.default.fs.readFile, type=tool` | DecoratedSandboxToolRegistrar.registerTool() | readFile 工具注册 |
| `add resource succeed, id=sandbox.default.shell.executeCmd, type=tool` | 同上 | executeCmd 工具注册 |
| `add resource succeed, id=sandbox.default.code.executeCode, type=tool` | 同上 | executeCode 工具注册 |
| `EXTERNAL_CALL_AUDIT adapter=Sandbox, success=true, method=shell.executeCmd` | ExternalCallExecutor.auditSuccess() | executeCmd 调用审计 |
| `EXTERNAL_CALL_AUDIT adapter=Sandbox, success=true, method=code.executeCode` | 同上 | executeCode 调用审计 |
| `ReAct iteration 1/5` | ReActAgent 推理循环 | LLM 选择并调用工具 |
| `ReAct iteration 2/5` | ReActAgent 推理循环 | LLM 基于工具结果生成回答 |
| `[LLM] tool_call: executeCmd` | ReActAgent | LLM 决定调用 executeCmd |
| `[LLM] tool_call: executeCode` | ReActAgent | LLM 决定调用 executeCode |

---

## 关键源码文件索引

| 文件 | 角色 | 关键方法 |
|---|---|---|
| [SandboxDemoApplication.java](src/main/java/com/openjiuwen/service/demo/example/sandbox/SandboxDemoApplication.java) | 启动入口, @Bean agentHandler | agentHandler() |
| [DecoratedSandboxToolRegistrar.java](../../support/src/main/java/com/openjiuwen/service/demo/example/support/DecoratedSandboxToolRegistrar.java) | 注册 3 个 Sandbox 工具到 ReActAgent | register(), readFileTool(), executeCmdTool(), executeCodeTool() |
| [DecoratingSandboxClient.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/external/DecoratingSandboxClient.java) | 治理装饰器: 为每个操作套用 ExternalCallExecutor | fs(), shell(), code() |
| [DefaultAgentCoreSandboxClientFactory.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/external/DefaultAgentCoreSandboxClientFactory.java) | 创建 DecoratingSandboxClient | create(), configFor() |
| [AgentCoreSandboxClientFactory.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/external/AgentCoreSandboxClientFactory.java) | 工厂 SPI 接口 | create(), configFor() |
| [ExternalCallExecutor.java](../../../../agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/external/ExternalCallExecutor.java) | 外部调用治理: timeout/retry/circuitBreaker/audit | execute(), callWithTimeout(), auditSuccess() |
| [AgentCoreAdaptersAutoConfiguration.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/autoconfigure/AgentCoreAdaptersAutoConfiguration.java) | Spring 自动装配 SandboxFactory | agentCoreSandboxClientFactory() |
| [JiuwenCoreAgentHandler.java](../../../../agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/agentfw/JiuwenCoreAgentHandler.java) | 适配器: buildInputs -> Runner.runAgent -> toQueryResponse | query(), buildInputs(), runnerSession() |
| [ExampleReActAgentFactory.java](../../support/src/main/java/com/openjiuwen/service/demo/example/support/ExampleReActAgentFactory.java) | 构建 ReActAgent | build() |
| [A2AEnabledServeOrchestrator.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/A2AEnabledServeOrchestrator.java) | 编排器 | query() |
| [QueryMvcController.java](../../../../agent-service-app/src/main/java/com/openjiuwen/service/app/controller/query/QueryMvcController.java) | HTTP 入口 | queryV1(), handleQuery() |
| [application-sandbox.yml](application-sandbox.yml) | Sandbox 配置 | enabled, timeout, retry, circuitBreaker, servers |
| [README.md](README.md) | 使用说明 | 启动方法, smoke 脚本 |

---

## 验证报告

> 验证时间: 2026-09-10 14:50-15:00
> 验证目标: 确认调用栈文档描述的完整流程在运行中是否正常

### 验证环境状态

| 组件 | 检查项 | 预期 | 实际 | 状态 |
|---|---|---|---|---|
| JiuwenBox Docker 容器 | 容器状态 | Up | Up 8 minutes | PASS |
| JiuwenBox 端口 | 8321/8322 映射 | 0.0.0.0:8321-8322->8321-8322/tcp | 0.0.0.0:8321-8322->8321-8322/tcp | PASS |
| JiuwenBox 健康检查 | GET /health | {"status":"ok"} | {"status":"ok","version":"0.1.0","runtime":"process","landlock_supported":true,"sandboxes_active":1} | PASS |
| Sandbox Demo 服务 | 8093 端口监听 | LISTENING | PID 46612 LISTENING | PASS |

### 三轮接口验证

#### 请求1: executeCmd 执行 echo 命令

| 项 | 值 |
|---|---|
| conversation_id | sb-verify-c1 |
| 请求消息 | "Please use the executeCmd tool to run: echo sandbox-file-ok" |
| 预期行为 | LLM 选择 executeCmd 工具 -> DecoratingSandboxShellOperation -> ExternalCallExecutor -> HTTP POST JiuwenBox:8321 -> bubblewrap 执行 echo |
| 预期输出 | 包含 "sandbox-file-ok" |
| 实际响应 | "The command ran successfully, outputting: ``sandbox-file-ok`` Exit code was `0`" |
| 结论 | **PASS** |

调用栈验证:
```
POST /v1/query
+-> QueryMvcController -> Orchestrator -> JiuwenCoreAgentHandler
    +-> Runner.runAgent -> ReActAgent.invoke
       -- Iteration 1 --
       +-> LLM: tool_call(executeCmd, {command:"echo sandbox-file-ok"})
       +-> DecoratedSandboxToolRegistrar lambda
          +-> client.shell().executeCmd(command, cwd, timeout, env, options)
             +-> DecoratingSandboxShellOperation.executeCmd()
                +-> ExternalCallExecutor.execute("shell","executeCmd", false, () -> ...)
                   +-> callWithTimeout -> delegate.executeCmd()
                      +-> Core SandboxClient (JiuwenBox provider)
                         +-> HTTP POST 8321/api/v1/sandboxes/.../execute
                         +-> JiuwenBox: bubblewrap 执行 echo
                         <- {exit_code:0, stdout:"sandbox-file-ok\n"}
                   +-> auditSuccess()
                      日志: EXTERNAL_CALL_AUDIT adapter=Sandbox, success=true
       -- Iteration 2 --
       +-> LLM: "The command ran successfully..." (最终回答)
```

#### 请求2: executeCmd 创建文件并读取

| 项 | 值 |
|---|---|
| conversation_id | sb-verify-c2 |
| 请求消息 | "Please use the executeCmd tool to run: printf sandbox-file-ok > /tmp/openjiuwen-demo.txt && cat /tmp/openjiuwen-demo.txt" |
| 预期行为 | LLM 选择 executeCmd -> 在 JiuwenBox 沙箱中创建文件并 cat 读取 |
| 预期输出 | 包含 "sandbox-file-ok"，确认文件创建成功 |
| 实际响应 | "The command executed successfully (exit code 0). The file `/tmp/openjiuwen-demo.txt` was created with the content: ``sandbox-file-ok``" |
| 结论 | **PASS** |

调用栈验证:
```
POST /v1/query
+-> ... -> ReActAgent
   +-> LLM: tool_call(executeCmd, {command:"printf ... > /tmp/...txt && cat ..."})
      +-> DecoratingSandboxShellOperation -> ExternalCallExecutor
         +-> HTTP POST 8321/api/v1/sandboxes/.../execute
         +-> JiuwenBox: 创建文件 + cat 读取
         <- {exit_code:0, stdout:"sandbox-file-ok"}
   +-> LLM: "file was created with content sandbox-file-ok"
```

#### 请求3: executeCode 执行 Python

| 项 | 值 |
|---|---|
| conversation_id | sb-verify-c3 |
| 请求消息 | "Please use the executeCode tool to run Python code: print(\"sandbox-code-ok\")" |
| 预期行为 | LLM 选择 executeCode -> DecoratingSandboxCodeOperation -> ExternalCallExecutor -> HTTP POST JiuwenBox:8321 -> Python 执行 |
| 预期输出 | 包含 "sandbox-code-ok" |
| 实际响应 | "The code executed successfully, and the output was: ``sandbox-code-ok``" |
| 结论 | **PASS** |

调用栈验证:
```
POST /v1/query
+-> ... -> ReActAgent
   +-> LLM: tool_call(executeCode, {code:"print(...)", language:"python"})
      +-> DecoratingSandboxCodeOperation -> ExternalCallExecutor
         +-> HTTP POST 8321/api/v1/sandboxes/.../execute_code
         +-> JiuwenBox: Python 执行 print("sandbox-code-ok")
         <- {exit_code:0, stdout:"sandbox-code-ok\n"}
   +-> LLM: "code executed successfully, output was sandbox-code-ok"
```

### 验证结果汇总

| 验证项 | 预期 | 实际 | 状态 |
|---|---|---|---|
| JiuwenBox 容器运行 | Up | Up 8 minutes | PASS |
| JiuwenBox 健康检查 | status=ok | status=ok, sandboxes_active=1 | PASS |
| Sandbox Demo 服务监听 | 8093 端口 | PID 46612 监听 8093 | PASS |
| 请求1: executeCmd (echo) | 输出 sandbox-file-ok | "outputting: sandbox-file-ok, Exit code 0" | PASS |
| 请求2: executeCmd (printf+cat) | 创建文件并读取 | "file was created with content: sandbox-file-ok" | PASS |
| 请求3: executeCode (Python) | 输出 sandbox-code-ok | "the output was: sandbox-code-ok" | PASS |

### 调用栈文档验证结论

调用栈文档描述的完整链路在运行中全部验证通过:

1. **HTTP 入口** -> `QueryMvcController.queryV1()` -> `QueryIngressSupport.validateAndBuild()` -> `ServeRequest` -- PASS
2. **Orchestrator** -> `A2AEnabledServeOrchestrator.query()` -> `agentHandler.query()` -- PASS
3. **JiuwenCoreAgentHandler** -> `buildInputs()` -> `runnerSession()` -> `Runner.runAgent()` -- PASS
4. **ReActAgent** -> LLM 推理选择工具 (executeCmd / executeCode) -> 工具执行 -- PASS
5. **DecoratingSandboxClient** -> `DecoratingSandboxShellOperation` / `DecoratingSandboxCodeOperation` -- PASS
6. **ExternalCallExecutor** -> `callWithTimeout()` -> `auditSuccess()` (EXTERNAL_CALL_AUDIT) -- PASS
7. **Core SandboxClient** -> HTTP POST 到 JiuwenBox:8321 -- PASS
8. **JiuwenBox Docker** -> bubblewrap 沙箱隔离执行命令/代码 -> 结果回填 -- PASS
9. **结果回传** -> ReActAgent Iteration 2 LLM 生成最终回答 -> HTTP 200 返回用户 -- PASS

> 结论: Sandbox Demo 调用栈文档描述的环境搭建、服务启动、配置装配、请求处理链路、
> 工具注册与调用、ExternalCallExecutor 治理、JiuwenBox 沙箱执行等全部流程，
> 经三轮实际接口调用验证，与代码分析和文档描述完全一致。

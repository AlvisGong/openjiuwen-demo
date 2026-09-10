# Security Demo 验证报告

> 验证时间: 2026-09-10 15:40-15:45
> 模块: agent-service-demo-security
> 验证目标: 入站细粒度鉴权 (FineGrainedAuthorizer) 端到端链路

---

## 运行环境

| 组件 | 端口/地址 | 说明 |
|---|---|---|
| Java Security Demo | localhost:8095 | SecurityDemoApplication, ReActAgent + 鉴权 AOP |
| LLM (glm-5.2) | dashscope 远程 | OpenAI 兼容 API |

### 模块特点

- 演示入站 **细粒度鉴权 AOP**（`FineGrainedAuthorizer` SPI）
- 默认启用 `security.auth.enabled=true`，TLS 关闭
- Demo 策略: 请求头 `X-User-ID` 缺失时返回 403
- `/health` 端点不受鉴权 AOP 影响

### 配置加载

```
application.yml (import 声明)
  |
  +-- application-base.yml (port=8090, llm, checkpointer=in_memory)
  |
  +-- application-base_local.yml (LLM: glm-5.2, dashscope)  <- 覆盖 LLM
  |
  +-- application-security.yml (port=8095, security.auth.enabled=true)  <- 覆盖端口和鉴权
  |
  +-- application-security_local.yml (可选, 本地覆盖)
  +-- application-security-tls_local.yml (可选, TLS profile, 缺失则走 HTTP)
```

---

## 启动方式

### 编译

```bash
cd agent-runtime-java/service
mvn -pl agent-service-demo/example/security -am package -DskipTests -q
```

### 启动

```bash
java -jar agent-service-demo/example/security/target/agent-service-demo-security-0.1.2.jar
```

或使用 Maven:

```bash
mvn -pl agent-service-demo/example/security -am spring-boot:run
```

### 启动日志

```
Starting SecurityDemoApplication v0.1.2 using Java 21.0.10
No active profile set, falling back to 1 default profile: "default"
Tomcat initialized with port 8095 (http)
Started SecurityDemoApplication in 7.019 seconds
Starting AgentCore Runner
Begin to initializing checkpointer with type: in_memory
Succeed to initializing checkpointer with type: in_memory
agent_loaded=true
```

---

## 鉴权架构

### 配置

```yaml
# application-security.yml
server:
  port: 8095

openjiuwen:
  service:
    security:
      enabled: true        # 启用安全模块
      auth:
        enabled: true      # 启用细粒度鉴权 AOP
      tls:
        enabled: false     # 关闭 TLS (HTTP 模式)
```

### DemoFineGrainedAuthorizer

```java
// 文件: DemoFineGrainedAuthorizer.java
public final class DemoFineGrainedAuthorizer implements FineGrainedAuthorizer {
    @Override
    public AuthorizationResult authorize(AuthorizationRequest request) {
        if (request.userId() == null || request.userId().isBlank()) {
            return AuthorizationResult.deny("X-User-ID header is required");
        }
        return AuthorizationResult.allow();
    }
}
```

> 鉴权逻辑: 检查 `X-User-ID` 请求头，为空或缺失时拒绝，否则允许。
> 生产环境请替换为机构 IAM 实现。

### Bean 装配

```
SecurityDemoApplication.java
|
+-- @Bean FineGrainedAuthorizer fineGrainedAuthorizer()
|   -> new DemoFineGrainedAuthorizer()
|   -> Spring 注入到 AuthorizationRequestBuilder
|
+-- @Bean AgentHandler agentHandler(llmConfigResolver, ...)
    +-- llmConfigResolver.resolveRequired() -> ResolvedLlmConfig (glm-5.2)
    +-- ExampleReActAgentFactory.build("demo-security-agent", ...)
    +-- new JiuwenCoreAgentHandler(agent, noop)
```

### 鉴权链路

```
POST /v1/query
|
+-- QueryMvcController.queryV1()
    |  @AuthorizedResource(resource = "query", action = "execute")
    |
    +-- AuthorizationRequestBuilder.build(authorizedResource, headers)
    |   文件: AuthorizationRequestBuilder.java
    |   |
    |   +-- 从 headers 提取:
    |   |   X-User-ID   -> request.userId()
    |   |   X-Space-ID  -> request.spaceId()
    |   |   X-Tenant-ID -> request.tenantId()
    |   |
    |   +-- new AuthorizationRequest("query", "execute", userId, spaceId, tenantId, extensions)
    |
    +-- FineGrainedAuthorizer.authorize(request)
    |   文件: DemoFineGrainedAuthorizer.java
    |   |
    |   +-- if userId == null || userId.isBlank()
    |   |   -> return AuthorizationResult.deny("X-User-ID header is required")
    |   |   -> AOP 拦截 -> HTTP 403
    |   |       Body: {"type":"error","code":"ACCESS_DENIED","message":"X-User-ID header is required"}
    |   |
    |   +-- else
    |       -> return AuthorizationResult.allow()
    |       -> 继续执行 Controller 方法
    |
    +-- [鉴权通过] -> handleQuery() -> orchestrator.query() -> JiuwenCoreAgentHandler -> LLM
    +-- [鉴权拒绝] -> HTTP 403 ACCESS_DENIED
```

---

## 验证结果

### 测试1: 无 X-User-ID -> 403

| 项 | 值 |
|---|---|
| 请求 | POST /v1/query, 无 X-User-ID 头 |
| 预期 | HTTP 403, body 含 "ACCESS_DENIED" |
| 实际 | HTTP 403 |
| 结论 | **PASS** |

请求:
```
POST http://127.0.0.1:8095/v1/query
Content-Type: application/json
(无 X-User-ID 头)
Body: {"conversation_id":"manual-sec-1","message":"hello","stream":false}
```

响应:
```
HTTP 403
```

### 测试2: 带 X-User-ID -> 200

| 项 | 值 |
|---|---|
| 请求 | POST /v1/query, X-User-ID: demo-user-1 |
| 预期 | HTTP 200, LLM 回答 |
| 实际 | "Hello! How can I help you today?" |
| 结论 | **PASS** |

请求:
```
POST http://127.0.0.1:8095/v1/query
Content-Type: application/json
X-User-ID: demo-user-1
Body: {"conversation_id":"manual-sec-2","message":"hello","stream":false}
```

响应:
```json
{
    "result": {
        "role": "assistant",
        "content": "Hello! How can I help you today?"
    },
    "conversation_id": "manual-sec-2"
}
```

### 验证汇总

| 验证项 | 预期 | 实际 | 状态 |
|---|---|---|---|
| 服务启动 | 8095 端口监听 | Tomcat started on port 8095 | PASS |
| Agent 就绪 | agent_loaded=true | agent_loaded=true | PASS |
| 无 X-User-ID -> 403 | HTTP 403 ACCESS_DENIED | HTTP 403 | PASS |
| 带 X-User-ID -> 200 | HTTP 200 + LLM 回答 | "Hello! How can I help you today?" | PASS |
| /health 不受鉴权影响 | 可直接访问 | 不受 AOP 拦截 | PASS |

---

## 关键源码文件索引

| 文件 | 角色 | 关键方法 |
|---|---|---|
| [SecurityDemoApplication.java](src/main/java/com/openjiuwen/service/demo/example/security/SecurityDemoApplication.java) | 启动入口, @Bean | fineGrainedAuthorizer(), agentHandler() |
| [DemoFineGrainedAuthorizer.java](src/main/java/com/openjiuwen/service/demo/example/security/DemoFineGrainedAuthorizer.java) | 鉴权 SPI 实现 | authorize() |
| [application-security.yml](application-security.yml) | 默认配置 | port=8095, auth.enabled=true, tls.enabled=false |
| [application-security.example.yml](application-security.example.yml) | 完整配置参考 | 每项含说明 + 必选性 |
| [application-security-tls.example.yml](application-security-tls.example.yml) | TLS/mTLS 模板 | 需生成证书后启用 |
| [README.md](README.md) | 使用说明 | 启动方法, TLS 配置, smoke 脚本 |

### 相关框架源码

| 文件 | 角色 | 关键方法 |
|---|---|---|
| QueryMvcController.java (agent-service-app) | HTTP 入口 + @AuthorizedResource | queryV1() |
| AuthorizationRequestBuilder.java (agent-service-app) | 构建鉴权请求 | build() |
| FineGrainedAuthorizer.java (agent-service-spec) | 鉴权 SPI 接口 | authorize() |
| AuthorizationResult.java (agent-service-spec) | 鉴权结果 | allow(), deny() |
| JiuwenCoreAgentHandler.java (agent-service-adapters) | 适配器 | query(), buildInputs(), runnerSession() |

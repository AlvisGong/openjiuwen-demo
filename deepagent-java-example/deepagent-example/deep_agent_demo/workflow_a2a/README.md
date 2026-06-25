# WorkflowA2A — A2A (Agent-to-Agent) 协议工作流示例

本示例是 `examples/workflow_new/` 的 A2A 协议改造版。核心业务逻辑相同（三个金融工作流：转账、理财、余额查询），但交互接口改为 **A2A (Agent-to-Agent)** 协议（基于 JSON-RPC 2.0），支持**非流式**和**流式（SSE）**两种模式。

## 文件说明

| 文件 | 说明 |
|------|------|
| `WorkflowA2ASupport.java` | 共享支持类，创建 WorkflowAgent 并注册三个金融工作流，提供 A2A Task 执行方法 |
| `WorkflowA2AServer.java` | A2A 协议服务器，实现 JSON-RPC 2.0 端点 |
| `WorkflowA2ACli.java` | CLI 命令行工具，通过 A2A 协议调用服务器 |
| `skills/financial_workflow_a2a/SKILL.md` | Skill 描述文件，供其他智能体集成调用此服务 |
| `README.md` | 本文档 |

## A2A 协议实现

### JSON-RPC 2.0 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `POST /tasks/send` | `tasks/send` | 非流式任务执行 |
| `POST /tasks/sendSubscribe` | `tasks/sendSubscribe` | 流式任务执行（SSE） |
| `POST /tasks/get` | `tasks/get` | 查询任务状态 |
| `GET /.well-known/agent-card` | — | Agent 能力发现 |

### API 数据模型

#### 请求通用结构（tasks/send / tasks/sendSubscribe）

| 字段 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `jsonrpc` | 根 | `string` | 是 | 固定为 `"2.0"` |
| `id` | 根 | `string` / `number` | 是 | 请求标识，响应中会原样返回 |
| `method` | 根 | `string` | 是 | 方法名：`tasks/send` 或 `tasks/sendSubscribe` |
| `params.id` | `params` | `string` | 是 | 任务 ID，由调用方生成，用于后续查询 |
| `params.message.role` | `params.message` | `string` | 是 | 角色：`"user"` |
| `params.message.parts` | `params.message` | `array` | 是 | 消息内容片段，每项 `{"type": "text", "text": "..."}` |
| `params.metadata.conversation_id` | `params.metadata` | `string` | 多轮必填 | 会话 ID。**首轮不传或传 null**，首轮响应中由服务端生成并返回；第二轮起必须传入首轮返回的值 |
| `params.metadata.node_id` | `params.metadata` | `string` | 多轮必填 | 上一轮返回的节点 ID。首轮不传；第二轮起必须传入上一轮响应中的 `node_id` 值（如 `"questioner"`） |

#### 响应通用结构（tasks/send）

| 字段 | 类型 | 说明 |
|------|------|------|
| `jsonrpc` | `string` | 固定为 `"2.0"` |
| `id` | `string`/`number` | 与请求 `id` 一致 |
| `result.id` | `string` | 任务 ID |
| `result.status` | `string` | 任务状态：`"working"` / `"input-required"` / `"completed"` / `"failed"` |
| `result.messages` | `array` | Agent 回复消息数组，格式同请求的 `message` |
| `result.messages[].role` | `string` | 固定为 `"agent"` |
| `result.messages[].parts` | `array` | 回复内容片段 |
| `result.metadata.conversation_id` | `string` | 会话 ID，多轮交互时在后续请求中回传 |
| `result.metadata.node_id` | `string` | 节点 ID。当 `status` 为 `"input-required"` 时存在，表示下一步应回复的节点 |
| `result.artifacts` | `array` | 产物列表（当前示例为空数组） |

#### 状态流转

```
首轮请求 → "input-required"（需要用户补充信息）
    ↓   ↻ 第二轮请求（携带 conversation_id + node_id）
"completed" | "input-required"（继续多轮） | "failed"
```

### tasks/send

**请求：**
```json
{
  "jsonrpc": "2.0",
  "method": "tasks/send",
  "params": {
    "id": "task-123",
    "message": {
      "role": "user",
      "parts": [{"type": "text", "text": "我要转账"}]
    },
    "metadata": {
      "conversation_id": "conv-abc",
      "node_id": null
    }
  },
  "id": 1
}
```

**等待用户输入的响应（input-required）：**
```json
{
  "jsonrpc": "2.0",
  "result": {
    "id": "task-123",
    "status": "input-required",
    "messages": [
      {
        "role": "agent",
        "parts": [{"type": "text", "text": "请补充转账金额，必须是数字或带货币单位的金额描述。"}]
      }
    ],
    "artifacts": [],
    "metadata": {"conversation_id": "conv-abc"}
  },
  "id": 1
}
```

**完成的响应（completed）：**
```json
{
  "jsonrpc": "2.0",
  "result": {
    "id": "task-123",
    "status": "completed",
    "messages": [
      {
        "role": "agent",
        "parts": [{"type": "text", "text": "转账服务完成，记录的转账金额为 2000元。"}]
      }
    ],
    "artifacts": [],
    "metadata": {"conversation_id": "conv-abc"}
  },
  "id": 1
}
```

### tasks/sendSubscribe（SSE 流式）

请求体与 `tasks/send` 相同。SSE 事件流：

```
event: task
data: {"jsonrpc":"2.0","method":"tasks/sendSubscribe","params":{"id":"task-123","status":"working",...}}

event: status_update
data: {"jsonrpc":"2.0","method":"tasks/sendSubscribe","params":{"id":"task-123","status":"input-required","message":{"role":"agent","parts":[{"type":"text","text":"请补充转账金额..."}]},"node_id":"questioner"}}

event: completed
data: {"jsonrpc":"2.0","method":"tasks/sendSubscribe","params":{"id":"task-123","status":"completed","messages":[...]}}
```

### tasks/get

**请求：**
```json
{
  "jsonrpc": "2.0",
  "method": "tasks/get",
  "params": {"id": "task-123"},
  "id": 1
}
```

**响应：** 与 `tasks/send` 返回的 Task 结构相同。

### Agent Card（GET /.well-known/agent-card）

返回 Agent 能力声明，用于 A2A 发现：

```json
{
  "name": "金融助手 (Financial Assistant)",
  "description": "支持转账、理财和余额查询的金融工作流 Agent",
  "version": "1.0.0",
  "capabilities": {
    "streaming": true,
    "pushNotifications": false,
    "statefulTasks": true
  },
  "skills": [...]
}
```

## 配置

1. 在 `examples/apiconfig.json` 中填入真实模型配置。
2. 本示例使用 Java 内置 `com.sun.net.httpserver.HttpServer`，**无需额外依赖**。

## 运行方式

以下命令假设当前目录是 Java 仓库根目录（包含 `pom.xml` 的目录）。

### 编译

```bash
mvn -DskipTests compile

# 生成 classpath 文件（供启动脚本使用）
mvn dependency:build-classpath -Dmdep.outputFile=target/workflow_examples.classpath
```

### 启动服务器

```bash
# 默认端口 8080
bash examples/workflow_a2a/run_server.sh

# 指定端口
bash examples/workflow_a2a/run_server.sh 9090
```

### 运行 CLI

```bash
# 交互模式
bash examples/workflow_a2a/run_cli.sh

# 单次查询（非流式）
bash examples/workflow_a2a/run_cli.sh --query "我要转账"

# 流式查询
bash examples/workflow_a2a/run_cli.sh --query "我要转账" --stream
```

### 直接使用 curl

#### 单次查询（无多轮交互）

```bash
# tasks/send 非流式
curl -s --max-time 30 -X POST http://localhost:8080/tasks/send \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "1",
    "method": "tasks/send",
    "params": {
      "id": "task-001",
      "message": {
        "role": "user",
        "parts": [{"type": "text", "text": "查一下余额"}]
      }
    }
  }'

# tasks/sendSubscribe 流式
curl -N --max-time 30 -X POST http://localhost:8080/tasks/sendSubscribe \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "1",
    "method": "tasks/sendSubscribe",
    "params": {
      "id": "task-002",
      "message": {
        "role": "user",
        "parts": [{"type": "text", "text": "查一下余额"}]
      }
    }
  }'
```

#### 完整转账流程（两轮 tasks/send）

```bash
# ===== 第1轮：发起转账意图 =====
RESP1=$(curl -s --max-time 30 -X POST http://localhost:8080/tasks/send \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "1",
    "method": "tasks/send",
    "params": {
      "id": "transfer-001",
      "message": {
        "role": "user",
        "parts": [{"type": "text", "text": "我要转账"}]
      }
    }
  }')
echo "$RESP1"

# 提取 conversation_id
CONV_ID=$(echo "$RESP1" | python3 -c "
import sys, json
r = json.load(sys.stdin)
print(r['result']['metadata']['conversation_id'])
")

# ===== 第2轮：补充金额（携带 conversation_id + node_id）=====
curl -s --max-time 60 -X POST http://localhost:8080/tasks/send \
  -H "Content-Type: application/json" \
  -d "{
    \"jsonrpc\": \"2.0\",
    \"id\": \"2\",
    \"method\": \"tasks/send\",
    \"params\": {
      \"id\": \"transfer-002\",
      \"message\": {
        \"role\": \"user\",
        \"parts\": [{\"type\": \"text\", \"text\": \"2000元\"}]
      },
      \"metadata\": {
        \"conversation_id\": \"$CONV_ID\",
        \"node_id\": \"questioner\"
      }
    }
  }"
```

## 完整对话流程示例

### 转账场景（两轮）

```
请求 1 (tasks/send):
  POST /tasks/send
  {
    "jsonrpc": "2.0",
    "id": "1",
    "method": "tasks/send",
    "params": {
      "id": "transfer-001",
      "message": {"role": "user", "parts": [{"type": "text", "text": "我要转账"}]}
    }
  }

响应 1:
  status: "input-required"
  messages: [{"role": "agent", "parts": [{"type": "text", "text": "请补充转账金额..."}]}]
  metadata: {"conversation_id": "conv-xxx"}   ← 提取 conversation_id

请求 2 (tasks/send):
  POST /tasks/send
  {
    "jsonrpc": "2.0",
    "id": "2",
    "method": "tasks/send",
    "params": {
      "id": "transfer-002",
      "message": {"role": "user", "parts": [{"type": "text", "text": "2000元"}]},
      "metadata": {
        "conversation_id": "conv-xxx",     ← 第一轮返回的值
        "node_id": "questioner"             ← 第一轮响应中 indicator 或 node_id
      }
    }
  }

响应 2:
  status: "completed"
  messages: [{"role": "agent", "parts": [{"type": "text", "text": "转账服务完成，记录的转账金额为 2000元。"}]}]
```

## 与 workflow_new 的区别

| 特性 | workflow_new | workflow_a2a |
|------|-------------|--------------|
| 协议 | RESTful（自定义 JSON） | A2A（JSON-RPC 2.0） |
| 端点 | `/api/workflow/chat` | `/tasks/send`, `/tasks/sendSubscribe`, `/tasks/get` |
| 消息格式 | 自定义 JSON 字段 | 标准 A2A Task + Message + Parts |
| 状态码 | 自定义 done/type | 标准 TaskState（working, input-required, completed, failed） |
| Agent 发现 | 无 | `/.well-known/agent-card` |
| 互操作性 | 本示例专用 | 遵循 A2A 协议，可与其他 A2A Agent 互操作 |

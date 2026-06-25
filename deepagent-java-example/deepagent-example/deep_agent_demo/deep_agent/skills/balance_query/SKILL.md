---
name: balance_query
description: 查询账户余额
---

# 余额查询 (Balance Query)

查询账户余额。

## 调用命令

```bash
python examples/deep_agent/skills/balance_query/cli.py --query "<查询内容>"
```

查询内容示例：`查余额`、`123456`（指定账号）

## 输出格式

CLI 输出 JSON，格式如下：

```json
{"status": "completed", "conversation_id": "xxx", "result": "您的余额为1000元"}
```

### 字段说明

| 字段 | 说明 |
|------|------|
| `status` | `"completed"`=最终结果 \| `"input-required"`=需要续对话 \| `"failed"`=失败 |
| `conversation_id` | 会话 ID（续对话时必须传入） |
| `node_id` | 节点 ID（`input-required` 时存在，续对话时必须传入） |
| `result` | Agent 回复文本 |

### 续对话

当 `status` 为 `"input-required"` 时，根据 `result` 的提示构造新的 `--query`，
并带上 `--conversation_id` 和 `--node_id` 再次调用 CLI：

```bash
python examples/deep_agent/skills/balance_query/cli.py --query "<下一轮输入>" --conversation_id "<id>" --node_id "<id>"
```

直到 `status` 为 `"completed"`，`result` 即为最终答案。

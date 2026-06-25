---
name: transfer
description: 执行转账汇款
---

# 转账服务 (Transfer Service)

处理转账、汇款、打款请求。CLI 自动处理 A2A 多轮交互流程，直接返回结果文本。

## 调用命令

```bash
python examples/deep_agent/skills/transfer/cli.py --to "<收款人>" --amount "<金额>"
```

金额示例：`100` 或 `100元`

## 输出格式

CLI 输出 JSON，格式如下：

```json
{"status": "completed", "result": "转账成功！已向张三转账100元"}
```

| 字段 | 说明 |
|------|------|
| `status` | `"completed"`=成功 \| `"failed"`=失败 |
| `result` | 转账结果文本 |

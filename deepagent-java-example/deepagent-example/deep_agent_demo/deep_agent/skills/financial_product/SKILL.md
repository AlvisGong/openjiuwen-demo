---
name: financial_product
description: 购买理财产品
---

# 理财产品 (Financial Product)

购买理财产品。CLI 自动处理交互流程，直接返回结果文本。

## 调用命令

```bash
python examples/deep_agent/skills/financial_product/cli.py --product "<产品名称>"
```

产品名称示例：`稳健理财`、`现金管理类产品`、`货币基金`、`定期存款理财`

## 输出格式

CLI 输出 JSON，格式如下：

```json
{"status": "completed", "result": "购买成功！恭喜您成功购入稳健理财产品，金额10000元"}
```

| 字段 | 说明 |
|------|------|
| `status` | `"completed"`=成功 \| `"failed"`=失败 |
| `result` | 购买结果文本 |

---
name: fund_cashflow_review_skill_java
description: 经营流水下滑场景评估（Java tool 版）
---

# fund_cashflow_review_skill_java

## 触发条件

一期 COT/结论含：经营流水下滑、负现金流、收入持续下降、长期无收入。

## 工具调用

本 Skill 不再通过 call_mcp 调用 Python 脚本，改为直接调用 DeepAgent 框架注册的
Java 工具 `cashflow_review`（通过 GaussDbClient 直连高斯数据库取数）。

```json
{
  "party_id": "...",
  "company_id": "..."
}
```

- `party_id`: 必填，客户号（关联 EMC 流水表的主键 MAIN_CIS）
- `company_id`: 企业ID（可选，原样透传到返回结果）

`party_id` 必填。

## 逻辑

1. 用 `party_id` 关联 `{GAUSS_SCHEMA}.emc_llm_flow_analysis.MAIN_CIS`（默认 schema 为 `emc`）
2. 按 `PT_DT` 降序取最新一行
3. 返回经营性四字段（字符串原样）：`INCM_AMT_OP` / `INCM_TXS_OP` / `EXPNDTR_AMT_OP` / `EXPNDTR_TXS_OP`，以及 `MAIN_CIS`、`PT_DT`
4. 无数据时 `operating_cashflow` 为 null，摘要提示未取到

## 返回字段

- `operating_cashflow`: MAIN_CIS, PT_DT, INCM_AMT_OP, INCM_TXS_OP, EXPNDTR_AMT_OP, EXPNDTR_TXS_OP
- `assessment_summary`: 事实摘要（仅陈述事实，不做是否下滑的布尔判断，交主复核）
- 与 Python 版 `fund_cashflow_review_skill` 输出结构完全一致（success / total / company_id /
  operating_cashflow / assessment_summary / history_info / history_params）

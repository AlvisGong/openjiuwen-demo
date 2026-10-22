/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package examples.risk_review_cashflow.tools;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;

import java.sql.SQLException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经营流水下滑场景评估工具（Java 实现，替代 Python 脚本
 * {@code fund_cashflow_review_skill/scripts/run_cashflow_review.py}）。
 *
 * <p>
 * 供 DeepAgent 通过 harness tool 机制直接调用：按 party_id 查询
 * GaussDB 中 EMC 经营性流水分析最新批次，返回与原 Python 脚本一致的
 * JSON 结构（success / total / company_id / operating_cashflow /
 * assessment_summary / history_info / history_params）。
 *
 * @since 0.1.14
 */
public final class CashflowReviewTool extends Tool {

    private static final String TOOL_ID = "cashflow_review";

    private final GaussDbClient client;

    /**
     * Construct with a GaussDB client.
     *
     * @param client the gaussdb client
     * @since 0.1.14
     */
    public CashflowReviewTool(GaussDbClient client) {
        super(buildCard());
        this.client = client;
    }

    private static ToolCard buildCard() {
        Map<String, Object> partyId = new LinkedHashMap<>();
        partyId.put("type", "string");
        partyId.put("description", "必填，客户号（关联 EMC 流水表主键 MAIN_CIS）");

        Map<String, Object> companyId = new LinkedHashMap<>();
        companyId.put("type", "string");
        companyId.put("description", "企业ID");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("party_id", partyId);
        properties.put("company_id", companyId);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("party_id"));

        return ToolCard.builder()
                .id(TOOL_ID)
                .name(TOOL_ID)
                .description("经营流水下滑场景评估：按 party_id 查询 GaussDB 中 EMC 经营性流水分析"
                        + "（最新 PT_DT 批次），返回经营性收支金额/笔数及事实摘要。")
                .inputParams(schema)
                .build();
    }

    @Override
    public Object invoke(Map<String, Object> inputs, Map<String, Object> kwargs) {
        String partyId = asText(inputs, "party_id");
        String companyId = asText(inputs, "company_id");

        if (partyId.isBlank()) {
            return errorPayload("party_id required");
        }

        Map<String, String> flow;
        try {
            flow = client.getEmcFlowAnalysis(partyId);
        } catch (SQLException e) {
            return errorPayload("GAUSSDB_QUERY_FAILED: " + e.getMessage());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("total", flow == null ? 0 : 1);
        payload.put("company_id", companyId);
        payload.put("operating_cashflow", flow);
        payload.put("assessment_summary", buildSummary(flow));
        payload.put("history_info", Collections.emptyList());
        payload.put("history_params", Collections.emptyMap());
        return payload;
    }

    @Override
    public Iterator<Object> stream(Map<String, Object> inputs, Map<String, Object> kwargs) {
        return List.of((Object) invoke(inputs, kwargs)).iterator();
    }

    private static String buildSummary(Map<String, String> flow) {
        if (flow == null || flow.isEmpty()) {
            return "未取到 EMC 经营流水分析数据";
        }
        return "已取到 EMC 经营性流水分析（批量日 " + orDash(flow.get("PT_DT")) + "）："
                + "收入金额=" + orDash(flow.get("INCM_AMT_OP")) + "，"
                + "收入笔数=" + orDash(flow.get("INCM_TXS_OP")) + "，"
                + "支出金额=" + orDash(flow.get("EXPNDTR_AMT_OP")) + "，"
                + "支出笔数=" + orDash(flow.get("EXPNDTR_TXS_OP"));
    }

    private static Map<String, Object> errorPayload(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("total", 0);
        payload.put("mcp_error", message);
        return payload;
    }

    private static String asText(Map<String, Object> inputs, String key) {
        Object value = inputs == null ? null : inputs.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String orDash(String value) {
        return (value == null || value.isBlank()) ? "-" : value;
    }
}

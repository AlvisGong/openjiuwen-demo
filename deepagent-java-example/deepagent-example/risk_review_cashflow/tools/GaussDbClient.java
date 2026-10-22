/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package examples.risk_review_cashflow.tools;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GaussDB（openGauss）只读查询客户端。
 *
 * <p>
 * 使用 openGauss JDBC 驱动（org.opengauss.Driver，URL 前缀
 * {@code jdbc:opengauss://}），兼容 GaussDB 的 sha256 认证。
 *
 * @since 0.1.14
 */
public final class GaussDbClient implements AutoCloseable {

    private static final String EMC_FLOW_TABLE = "emc_llm_flow_analysis";

    private static final String[] EMC_FLOW_FIELDS = {
            "MAIN_CIS", "PT_DT", "INCM_AMT_OP", "INCM_TXS_OP", "EXPNDTR_AMT_OP", "EXPNDTR_TXS_OP"
    };

    private static final String[] TRACE_FIELDS = {
            "review_id", "company_id", "assess_dt", "review_route", "cot_chains",
            "skills_called", "tool_calls", "review_conclusion", "created_at"
    };

    private static final String[] STAGE1_FIELDS = {
            "company_id", "assess_dt", "party_id", "ent_name",
            "llm_input", "llm_cot", "llm_output"
    };

    private static final String[] LOAN_FIELDS = {
            "PARTY_ID", "PARTY_GR_ID", "LOAN_BAL", "HQ_BRANCH_LABEL",
            "MODEL", "MAIN_CRDT_LABEL", "LOAN_TYPE", "PT_DT"
    };

    private static final String[] CORP_COLL_FIELDS = {
            "LOAN_CONTR_NUM", "CONTR_BAL_YUAN", "SYS_MTG_RATE", "NEGOT_ENCODE",
            "NEGOT_NAME", "ATTRIBUTER_CIS_NUM", "TENTH_YEAR", "HOUSE_AREA",
            "ESTIM_VAL", "PRE_ESTIM_VAL", "INIT_ESTIM_VAL", "COMM_UPRC_CNT", "PT_DT"
    };

    private static final String[] PERSON_COLL_FIELDS = {
            "LOAN_CONTR_NUM", "CONTR_BAL_YUAN", "SYS_MTG_RATE", "NEGOT_ENCODE",
            "NEGOT_NAME", "ATTRIBUTER_CIS_NUM", "TENTH_YEAR", "HOUSE_AREA",
            "ESTIM_VAL", "PRE_ESTIM_VAL", "INIT_ESTIM_VAL", "COMM_UPRC_CNT",
            "CITY_LINE", "PT_DT"
    };

    private final GaussDbConfig config;

    private Connection connection;

    /**
     * Construct with config.
     *
     * @param config the connection config
     * @since 0.1.14
     */
    public GaussDbClient(GaussDbConfig config) {
        this.config = config;
    }

    /**
     * 按 party_id（MAIN_CIS 主键）查询 EMC 经营性流水分析，取 PT_DT 最新一行。
     *
     * <p>
     * 与 Python 版 {@code gaussdb_repo.get_emc_flow_analysis} 行为一致：
     * 仅返回经营性四字段及 MAIN_CIS、PT_DT，字段值转字符串原样返回。
     *
     * @param partyId the customer id (MAIN_CIS)
     * @return 最新一行字段映射；无数据时返回 null
     * @throws SQLException if db access fails
     * @since 0.1.14
     */
    public Map<String, String> getEmcFlowAnalysis(String partyId) throws SQLException {
        String table = config.getSchema() + "." + EMC_FLOW_TABLE;
        String columns = String.join(", ", EMC_FLOW_FIELDS);
        String sql = "SELECT " + columns + " FROM " + table
                + " WHERE MAIN_CIS = ? ORDER BY PT_DT DESC LIMIT 1";
        try (PreparedStatement stmt = obtainConnection().prepareStatement(sql)) {
            stmt.setString(1, partyId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (String field : EMC_FLOW_FIELDS) {
                    row.put(field, rs.getString(field));
                }
                return row;
            }
        }
    }

    /**
     * 按企业 ID 加载一期初评记录（LIMIT 1），与 Python 版
     * {@code gaussdb_repo.get_stage1_assessment} 行为一致。
     *
     * @param companyId the company id
     * @return stage1 字段映射（company_id/assess_dt/party_id/ent_name/
     *         llm_input/llm_cot/llm_output）；无数据时返回 null
     * @throws SQLException if db access fails
     * @since 0.1.14
     */
    public Map<String, String> getStage1Assessment(String companyId) throws SQLException {
        String columns = String.join(", ", STAGE1_FIELDS);
        String sql = "SELECT " + columns + " FROM s1_risk_assess_tbl"
                + " WHERE company_id = ? LIMIT 1";
        try (PreparedStatement stmt = obtainConnection().prepareStatement(sql)) {
            stmt.setString(1, companyId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (String field : STAGE1_FIELDS) {
                    row.put(field, rs.getString(field));
                }
                return row;
            }
        }
    }

    /**
     * 查 party_id 全部贷款行（emc.emc_llm_loan_type_info），过滤到最新
     * PT_DT 批次且 LOAN_BAL &gt; 0，与 Python 版
     * {@code gaussdb_repo.get_loan_survival_basic} 行为一致。
     *
     * @param partyId the customer id
     * @return 贷款行列表（字段名小写映射的字符串值）
     * @throws SQLException if db access fails
     * @since 0.1.14
     */
    public java.util.List<Map<String, String>> getLoanSurvivalBasic(String partyId)
            throws SQLException {
        return queryLatestPtDt("emc.emc_llm_loan_type_info", LOAN_FIELDS, "PARTY_ID", partyId)
                .stream()
                .filter(row -> parseDouble(row.get("loan_bal")) > 0)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 查企业押品（emc.emc_llm_coll_detail_corp，ATTRIBUTER_CIS_NUM=party_id），
     * 最新 PT_DT 批次全部行。
     *
     * @param partyId the customer id
     * @return 押品行列表
     * @throws SQLException if db access fails
     * @since 0.1.14
     */
    public java.util.List<Map<String, String>> getCorpCollateral(String partyId)
            throws SQLException {
        return queryLatestPtDt("emc.emc_llm_coll_detail_corp", CORP_COLL_FIELDS,
                "ATTRIBUTER_CIS_NUM", partyId);
    }

    /**
     * 查个人押品（emc.emc_llm_coll_detail_person，ATTRIBUTER_CIS_NUM=party_gr_id），
     * 最新 PT_DT 批次全部行。
     *
     * @param partyGrId the personal group id
     * @return 押品行列表
     * @throws SQLException if db access fails
     * @since 0.1.14
     */
    public java.util.List<Map<String, String>> getPersonalCollateral(String partyGrId)
            throws SQLException {
        return queryLatestPtDt("emc.emc_llm_coll_detail_person", PERSON_COLL_FIELDS,
                "ATTRIBUTER_CIS_NUM", partyGrId);
    }

    private java.util.List<Map<String, String>> queryLatestPtDt(String table,
            String[] fields, String keyColumn, String keyValue) throws SQLException {
        String columns = String.join(", ", fields);
        String sql = "SELECT " + columns + " FROM " + table
                + " WHERE " + keyColumn + " = ?";
        java.util.List<Map<String, String>> rows = new java.util.ArrayList<>();
        try (PreparedStatement stmt = obtainConnection().prepareStatement(sql)) {
            stmt.setString(1, keyValue);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (String field : fields) {
                        row.put(field.toLowerCase(java.util.Locale.ROOT),
                                rs.getString(field));
                    }
                    rows.add(row);
                }
            }
        }
        if (rows.isEmpty()) {
            return rows;
        }
        String maxPt = rows.stream().map(r -> orEmpty(r.get("pt_dt")))
                .max(String::compareTo).orElse("");
        return rows.stream()
                .filter(r -> orEmpty(r.get("pt_dt")).equals(maxPt))
                .collect(java.util.stream.Collectors.toList());
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * 保存复核追溯记录（INSERT），与 Python 版
     * {@code gaussdb_repo.save_review_trace} 行为一致。
     *
     * @param record the trace record（大字段为已序列化 JSON 字符串）
     * @throws SQLException if db access fails
     * @since 0.1.14
     */
    public void saveReviewTrace(ReviewTraceRecord record) throws SQLException {
        String sql = "INSERT INTO " + config.getTraceTable()
                + " (review_id, company_id, assess_dt, review_route, cot_chains,"
                + " skills_called, tool_calls, review_conclusion, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = obtainConnection().prepareStatement(sql)) {
            stmt.setString(1, record.reviewId());
            stmt.setString(2, record.companyId());
            stmt.setString(3, record.assessDt());
            stmt.setString(4, record.reviewRoute());
            stmt.setString(5, record.cotChainsJson());
            stmt.setString(6, record.skillsCalledJson());
            stmt.setString(7, record.toolCallsJson());
            stmt.setString(8, record.reviewConclusionJson());
            stmt.setString(9, record.createdAt());
            stmt.executeUpdate();
        }
    }

    /**
     * 查询指定企业的最新一条追溯记录（按 created_at 降序）。
     *
     * @param companyId the company id
     * @return 字段映射；无数据时返回 null
     * @throws SQLException if db access fails
     * @since 0.1.14
     */
    public Map<String, String> findLatestTrace(String companyId) throws SQLException {
        String columns = String.join(", ", TRACE_FIELDS);
        String sql = "SELECT " + columns + " FROM " + config.getTraceTable()
                + " WHERE company_id = ? ORDER BY created_at DESC LIMIT 1";
        try (PreparedStatement stmt = obtainConnection().prepareStatement(sql)) {
            stmt.setString(1, companyId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (String field : TRACE_FIELDS) {
                    row.put(field, rs.getString(field));
                }
                return row;
            }
        }
    }

    private synchronized Connection obtainConnection() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return connection;
        }
        try {
            Class.forName("org.opengauss.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "openGauss JDBC driver not found on classpath. "
                            + "Add opengauss-jdbc jar (e.g. examples/risk_review_cashflow/lib).",
                    e);
        }
        connection = DriverManager.getConnection(
                config.jdbcUrl(), config.getUser(), config.getPassword());
        return connection;
    }

    @Override
    public synchronized void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    /**
     * 复核追溯落库记录；大字段为已序列化 JSON 字符串。
     *
     * @since 0.1.14
     */
    public record ReviewTraceRecord(String reviewId, String companyId, String assessDt,
            String reviewRoute, String cotChainsJson, String skillsCalledJson,
            String toolCallsJson, String reviewConclusionJson, String createdAt) {
    }
}

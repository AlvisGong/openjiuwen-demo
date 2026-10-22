/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package examples.risk_review_cashflow.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GaussDB（openGauss）连接配置。
 *
 * <p>优先读取环境变量 GAUSS_HOST / GAUSS_PORT / GAUSS_USER / GAUSS_PASSWORD /
 * GAUSS_DATABASE / GAUSS_SCHEMA，未设置时使用本机 desktop 数据库默认值。
 *
 * @since 0.1.14
 */
public final class GaussDbConfig {

    private final String host;

    private final int port;

    private final String user;

    private final String password;

    private final String database;

    private final String schema;

    private final String traceTable;

    private GaussDbConfig(String host, int port, String user, String password,
            String database, String schema, String traceTable) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.database = database;
        this.schema = schema;
        this.traceTable = traceTable;
    }

    /**
     * 从环境变量加载配置，未设置的项使用默认值。
     *
     * @return the config
     * @since 0.1.14
     */
    public static GaussDbConfig load() {
        return new GaussDbConfig(
                envOrDefault("GAUSS_HOST", "127.0.0.1"),
                Integer.parseInt(envOrDefault("GAUSS_PORT", "5432")),
                envOrDefault("GAUSS_USER", "gaussdb"),
                envOrDefault("GAUSS_PASSWORD", "Gauss@2026"),
                envOrDefault("GAUSS_DATABASE", "postgres"),
                envOrDefault("GAUSS_SCHEMA", "emc"),
                envOrDefault("GAUSS_TRACE_TABLE", "s2_risk_review_trace_tbl"));
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = System.getProperty(key);
        }
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    /**
     * Build JDBC url（openGauss 驱动前缀 jdbc:opengauss://）。
     *
     * @return the jdbc url
     * @since 0.1.14
     */
    public String jdbcUrl() {
        return "jdbc:opengauss://" + host + ":" + port + "/" + database;
    }

    /**
     * Get host.
     *
     * @return the host
     * @since 0.1.14
     */
    public String getHost() {
        return host;
    }

    /**
     * Get port.
     *
     * @return the port
     * @since 0.1.14
     */
    public int getPort() {
        return port;
    }

    /**
     * Get user.
     *
     * @return the user
     * @since 0.1.14
     */
    public String getUser() {
        return user;
    }

    /**
     * Get password.
     *
     * @return the password
     * @since 0.1.14
     */
    public String getPassword() {
        return password;
    }

    /**
     * Get database.
     *
     * @return the database
     * @since 0.1.14
     */
    public String getDatabase() {
        return database;
    }

    /**
     * Get schema.
     *
     * @return the schema
     * @since 0.1.14
     */
    public String getSchema() {
        return schema;
    }

    /**
     * Get 复核追溯表名（可带 schema 前缀）。
     *
     * @return the trace table name
     * @since 0.1.14
     */
    public String getTraceTable() {
        return traceTable;
    }

    /**
     * 转为脱敏描述（不含密码），用于日志输出。
     *
     * @return the safe description
     * @since 0.1.14
     */
    public String toSafeString() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("url", jdbcUrl());
        info.put("user", user);
        info.put("schema", schema);
        return info.toString();
    }
}

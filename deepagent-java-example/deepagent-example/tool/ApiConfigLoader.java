/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 */

package com.example.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 与 {@code agent-core-java/example-project} 的 ApiConfigLoader 保持一致。
 */
public final class ApiConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile Map<String, String> configCache;

    private ApiConfigLoader() {
    }

    public static Map<String, String> load() {
        if (configCache == null) {
            synchronized (ApiConfigLoader.class) {
                if (configCache == null) {
                    configCache = loadConfig();
                }
            }
        }
        return configCache;
    }

    public static String describeConfig() {
        String key = getApiKey();
        String maskedKey = key.length() <= 8
                ? "***"
                : key.substring(0, 4) + "..." + key.substring(key.length() - 4);
        return "provider=" + getModelProvider()
                + ", model=" + getModelName()
                + ", apiBase=" + getApiBase()
                + ", apiKey=" + maskedKey
                + ", sslVerify=" + getSslVerify();
    }

    public static String getApiBase() {
        return getRequired("API_BASE");
    }

    public static String getApiKey() {
        return getRequired("API_KEY");
    }

    public static String getModelProvider() {
        return getRequired("MODEL_PROVIDER");
    }

    public static String getModelName() {
        return getRequired("MODEL_NAME");
    }

    public static boolean getSslVerify() {
        return Boolean.parseBoolean(load().getOrDefault("LLM_SSL_VERIFY", "true"));
    }

    private static String getRequired(String key) {
        String value = load().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required key in apiconfig.json: " + key);
        }
        return value;
    }

    private static Map<String, String> loadConfig() {
        for (Path candidate : resolveConfigPathCandidates()) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) {
                System.out.println("[ApiConfigLoader] Using config file: " + normalized);
                return readConfig(normalized);
            }
        }

        try (InputStream inputStream = ApiConfigLoader.class.getClassLoader()
                .getResourceAsStream("apiconfig.json")) {
            if (inputStream != null) {
                System.out.println("[ApiConfigLoader] Using classpath:apiconfig.json");
                return MAPPER.readValue(inputStream, new TypeReference<Map<String, String>>() {
                });
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read config from classpath resource", e);
        }

        throw new IllegalStateException(
                "Cannot find apiconfig.json. Tried: " + resolveConfigPathCandidates().stream()
                        .map(path -> path.toAbsolutePath().normalize().toString())
                        .toList() + ", and classpath:apiconfig.json"
        );
    }

    private static List<Path> resolveConfigPathCandidates() {
        List<Path> candidates = new ArrayList<>();

        String configPathProperty = System.getProperty("agent.config.path");
        if (configPathProperty != null && !configPathProperty.isBlank()) {
            candidates.add(Path.of(configPathProperty));
        }

        String configPathEnv = System.getenv("AGENT_API_CONFIG");
        if (configPathEnv != null && !configPathEnv.isBlank()) {
            candidates.add(Path.of(configPathEnv));
        }

        candidates.add(Path.of("apiconfig.json"));
        candidates.add(Path.of("src/main/resources/apiconfig.json"));
        candidates.add(Path.of("D:/cursor/agent-core-java/example-project/src/main/resources/apiconfig.json"));
        return candidates;
    }

    private static Map<String, String> readConfig(Path configPath) {
        try (InputStream inputStream = Files.newInputStream(configPath)) {
            return MAPPER.readValue(inputStream, new TypeReference<Map<String, String>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read config file: " + configPath, e);
        }
    }
}

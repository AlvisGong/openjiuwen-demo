/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package myexample.rails.external_memory_rail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.memory.external.MemoryProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LocalFileMemoryProvider — 基于本地内存的简易 MemoryProvider 实现。
 *
 * <p>无需外部服务，可直接运行。提供两个工具：
 * <ul>
 *   <li>{@code ltm_search} — 搜索记忆（关键词匹配）</li>
 *   <li>{@code ltm_save} — 保存记忆事实</li>
 * </ul>
 *
 * <p>支持 prefetch（自动召回相关记忆）和 syncTurn（自动同步对话内容）。
 */
public class LocalFileMemoryProvider implements MemoryProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, Object> SEARCH_SCHEMA = Map.of(
            "name", "ltm_search",
            "description", "搜索长期记忆中的相关信息，返回匹配的记忆条目。",
            "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "query", Map.of("type", "string", "description", "搜索查询内容"),
                            "top_k", Map.of("type", "integer", "description", "最大返回结果数", "default", 5)
                    ),
                    "required", List.of("query")
            )
    );

    private static final Map<String, Object> SAVE_SCHEMA = Map.of(
            "name", "ltm_save",
            "description", "将一条事实保存到长期记忆中，供后续搜索召回。",
            "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "content", Map.of("type", "string", "description", "要保存的事实内容")
                    ),
                    "required", List.of("content")
            )
    );

    private final ConcurrentHashMap<String, List<String>> memoryStore = new ConcurrentHashMap<>();
    private boolean isInitialized;

    @Override
    public String getName() {
        return "local_file";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void initialize(Map<String, Object> kwargs) {
        isInitialized = true;
    }

    @Override
    public List<Map<String, Object>> getToolSchemas() {
        return List.of(SEARCH_SCHEMA, SAVE_SCHEMA);
    }

    @Override
    public String handleToolCall(String toolName, Map<String, Object> args) throws Exception {
        if ("ltm_search".equals(toolName)) {
            return handleSearch(args);
        }
        if ("ltm_save".equals(toolName)) {
            return handleSave(args);
        }
        return MAPPER.writeValueAsString(Map.of("error", "Unknown tool: " + toolName));
    }

    @Override
    public String prefetch(String query, Map<String, Object> kwargs) {
        if (query == null || query.isBlank()) {
            return "";
        }
        List<String> results = searchMemory(query, 5);
        if (results.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## Recalled Memories\n");
        for (String result : results) {
            sb.append("- ").append(result).append("\n");
        }
        return sb.toString().trim();
    }

    @Override
    public void syncTurn(String userMsg, String assistantMsg, Map<String, Object> kwargs) {
        // 自动将对话内容保存到记忆中（去掉前缀，保留可搜索的纯文本）
        String scopeKey = resolveScopeKey(kwargs);
        List<String> memories = memoryStore.computeIfAbsent(scopeKey, k -> new ArrayList<>());
        if (userMsg != null && !userMsg.isBlank()) {
            memories.add(userMsg);
        }
        if (assistantMsg != null && !assistantMsg.isBlank()) {
            memories.add(assistantMsg);
        }
    }

    /**
     * 直接保存一条事实到全局记忆中（用于预置记忆内容）。
     */
    public void saveFact(String fact) {
        List<String> memories = memoryStore.computeIfAbsent("global", k -> new ArrayList<>());
        memories.add(fact);
    }

    @Override
    public String systemPromptBlock() {
        return "# Long-Term Memory\n"
                + "你拥有长期记忆能力。使用 ltm_search 搜索相关记忆，使用 ltm_save 保存重要事实。\n"
                + "在回答问题前，先搜索记忆中是否有相关信息可以利用。";
    }

    @Override
    public boolean isInitialized() {
        return isInitialized;
    }

    // =========================================================================
    // 内部实现
    // =========================================================================

    private String handleSearch(Map<String, Object> args) throws Exception {
        String query = String.valueOf(args.getOrDefault("query", ""));
        int topK = args.get("top_k") != null
                ? Integer.parseInt(String.valueOf(args.get("top_k")))
                : 5;


        List<String> matched = searchMemory(query, topK);

        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < matched.size(); i++) {
            results.add(Map.of("id", String.valueOf(i), "content", matched.get(i)));
        }
        return MAPPER.writeValueAsString(Map.of("results", results, "count", results.size()));
    }

    private String handleSave(Map<String, Object> args) throws Exception {
        String content = String.valueOf(args.getOrDefault("content", ""));
        if (content.isBlank()) {
            return MAPPER.writeValueAsString(Map.of("error", "content cannot be empty"));
        }

        String scopeKey = "global";
        List<String> memories = memoryStore.computeIfAbsent(scopeKey, k -> new ArrayList<>());
        memories.add(content);

        return MAPPER.writeValueAsString(Map.of("result", "Fact saved successfully.", "total_count", memories.size()));
    }

    private List<String> searchMemory(String query, int topK) {
        // 将查询分词为关键词，任意关键词命中即算匹配
        List<String> keywords = tokenize(query);
        if (keywords.isEmpty()) {
            return List.of();
        }

        List<String> all = getAllMemories();
        List<ScoredMemory> scored = new ArrayList<>();

        for (String memory : all) {
            String lowerMemory = memory.toLowerCase();
            int hits = 0;
            for (String keyword : keywords) {
                if (lowerMemory.contains(keyword)) {
                    hits++;
                }
            }
            if (hits > 0) {
                scored.add(new ScoredMemory(memory, hits));
            }
        }

        // 按命中关键词数量排序，命中越多越靠前
        scored.sort((a, b) -> Integer.compare(b.hits, a.hits));

        List<String> matched = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            matched.add(scored.get(i).memory);
        }
        return matched;
    }

    /**
     * 简易中文+英文分词：
     * - 英文按空格和标点拆分
     * - 中文使用 bigram/trigram 提取关键词，过滤含停用词字符的组合
     * - 过滤停用词和过短片段
     */
    private static List<String> tokenize(String query) {
        String cleaned = query.toLowerCase()
                .replaceAll("[\\s，。！？、；：\"'（）\\[\\]{}【】,.!?;:()]+", " ")
                .trim();

        // 多词停用词（整词匹配）
        Set<String> wordStopwords = Set.of(
                "记得", "记住", "知道", "告诉", "什么", "怎么", "哪些", "用户",
                "the", "a", "an", "is", "are", "was", "were", "be", "been",
                "have", "has", "had", "do", "did", "will", "would", "can",
                "could", "should", "may", "might", "shall", "not", "no",
                "and", "or", "but", "if", "of", "in", "to", "for", "with",
                "on", "at", "by", "from", "up", "about", "into", "through",
                "remember", "know", "what", "who", "where", "when", "how"
        );

        // 单字停用词：bigram/trigram 中任一字符为停用词则过滤该组合
        Set<String> charStopwords = Set.of(
                "你", "我", "的", "了", "吗", "是", "在", "有", "还", "和", "与",
                "这", "那", "就", "都", "也", "要", "能", "会", "可", "以", "请",
                "不", "很", "最", "更", "比", "被", "把", "让", "给", "向",
                "为", "而", "却", "又", "且", "则", "所", "从", "至", "于"
        );

        List<String> tokens = new ArrayList<>();

        for (String segment : cleaned.split("\\s+")) {
            if (segment.isBlank()) {
                continue;
            }
            boolean hasChinese = segment.chars().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF);
            if (hasChinese) {
                // 中文 bigram：过滤含停用词字符的组合
                for (int i = 0; i < segment.length() - 1; i++) {
                    String bigram = segment.substring(i, i + 2);
                    if (wordStopwords.contains(bigram)) {
                        continue;
                    }
                    if (containsStopwordChar(bigram, charStopwords)) {
                        continue;
                    }
                    tokens.add(bigram);
                }
                // 中文 trigram：同样过滤含停用词字符的组合
                for (int i = 0; i < segment.length() - 2; i++) {
                    String trigram = segment.substring(i, i + 3);
                    if (wordStopwords.contains(trigram)) {
                        continue;
                    }
                    if (containsStopwordChar(trigram, charStopwords)) {
                        continue;
                    }
                    tokens.add(trigram);
                }
            } else {
                // 英文：直接用整词
                if (segment.length() >= 2 && !wordStopwords.contains(segment)) {
                    tokens.add(segment);
                }
            }
        }
        return tokens;
    }

    /** 检查字符串中是否包含任何单字停用词字符。 */
    private static boolean containsStopwordChar(String token, Set<String> charStopwords) {
        for (int i = 0; i < token.length(); i++) {
            String ch = String.valueOf(token.charAt(i));
            if (charStopwords.contains(ch)) {
                return true;
            }
        }
        return false;
    }

    private static final class ScoredMemory {
        final String memory;
        final int hits;

        ScoredMemory(String memory, int hits) {
            this.memory = memory;
            this.hits = hits;
        }
    }

    private List<String> getAllMemories() {
        List<String> all = new ArrayList<>();
        for (List<String> memories : memoryStore.values()) {
            all.addAll(memories);
        }
        return all;
    }

    private String resolveScopeKey(Map<String, Object> kwargs) {
        String userId = kwargs != null && kwargs.get("user_id") != null
                ? String.valueOf(kwargs.get("user_id")) : "__default__";
        String scopeId = kwargs != null && kwargs.get("scope_id") != null
                ? String.valueOf(kwargs.get("scope_id")) : "__default__";
        return userId + ":" + scopeId;
    }
}

/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package examples.workflow_a2a;

import com.fasterxml.jackson.databind.ObjectMapper;

import examples.utils.SharedExampleApiConfigLoader;

import com.openjiuwen.core.application.schema.DefaultResponse;
import com.openjiuwen.core.application.schema.WorkflowAgentConfig;
import com.openjiuwen.core.application.workflow.WorkflowAgent;
import com.openjiuwen.core.common.constants.Constant;
import com.openjiuwen.core.foundation.llm.schema.BaseModelInfo;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.session.interaction.InteractionOutput;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.WorkflowCard;
import com.openjiuwen.core.workflow.component.End;
import com.openjiuwen.core.workflow.component.Start;
import com.openjiuwen.core.workflow.component.llm.FieldInfo;
import com.openjiuwen.core.workflow.component.llm.QuestionerComponent;
import com.openjiuwen.core.workflow.component.llm.QuestionerConfig;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared support class for the A2A workflow agent example.
 * <p>
 * Creates a {@link WorkflowAgent} with three financial workflows
 * (transfer, investment, balance inquiry). Provides helper methods
 * for A2A-style task execution.
 */
public final class WorkflowA2ASupport {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private static final String AGENT_ID = "workflow_a2a_example";
        private static final String WORKFLOW_VERSION = "1.0";
        private static final String DEFAULT_RESPONSE = "我目前只支持转账、理财和余额查询三类金融流程，请明确说明你的需求。";
        private static final String SYSTEM_PROMPT = "你是一个金融业务助手。"
                        + "当用户提出转账、理财或余额查询需求时，必须选择最合适的工作流处理。"
                        + "如果信息不完整，就通过工作流里的提问节点补齐缺失字段。"
                        + "如果用户需求不属于这三类业务，就直接返回默认回复。";

        private WorkflowA2ASupport() {
        }

        /**
         * A2A Task representation.
         */
        public record A2ATask(
                        String id,
                        String status,
                        List<A2AMessage> messages,
                        List<Map<String, Object>> artifacts,
                        String conversationId) {
        }

        /**
         * A2A Message representation.
         */
        public record A2AMessage(
                        String role,
                        List<Map<String, Object>> parts) {
        }

        /**
         * Create a fully configured WorkflowAgent with three financial workflows.
         */
        public static WorkflowAgent createAgent() {
                WorkflowAgentConfig config = WorkflowAgentConfig.builder()
                                .id(AGENT_ID)
                                .description("Java A2A 多工作流金融助手示例")
                                .model(createSharedModelConfig())
                                .promptTemplate(List.of(Map.of("role", "system", "content", SYSTEM_PROMPT)))
                                .defaultResponse(DefaultResponse.builder().text(DEFAULT_RESPONSE).build())
                                .build();

                WorkflowAgent agent = new WorkflowAgent(config);
                agent.addWorkflows(List.of(
                                buildFinancialWorkflow(
                                                "transfer_flow",
                                                "转账服务",
                                                "处理用户转账、汇款、打款、transfer、remittance 请求。"
                                                                + "用户提到转账、汇款、打钱给别人或 transfer money 时应选择这个流程。",
                                                "amount",
                                                "转账金额，必须是数字或带货币单位的金额描述。",
                                                "转账服务完成，记录的转账金额为 {{amount}}。"),
                                buildFinancialWorkflow(
                                                "invest_flow",
                                                "理财服务",
                                                "处理理财、投资、购买理财产品、investment、wealth management 请求。"
                                                                + "用户提到理财、投资、基金、稳健产品或 investment 时应选择这个流程。",
                                                "product",
                                                "理财产品名称，例如稳健理财、现金管理类产品。",
                                                "理财服务完成，选择的理财产品为 {{product}}。"),
                                buildFinancialWorkflow(
                                                "balance_flow",
                                                "余额查询",
                                                "处理账户余额、银行卡余额、账户剩余金额、balance inquiry 请求。"
                                                                + "用户提到查余额、账户余额或 check balance 时应选择这个流程。",
                                                "account",
                                                "需要查询余额的账户号码。",
                                                "余额查询完成，登记的账户号码为 {{account}}，余额为1000元。")));
                return agent;
        }

        /**
         * Execute a single chat query and return the A2A Task result.
         *
         * @param agent          the WorkflowAgent
         * @param taskId         unique task identifier
         * @param query          user input text
         * @param nodeId         node ID from a previous interaction, or null
         * @param conversationId conversation ID
         * @return the A2A Task
         */
        public static A2ATask executeTask(
                        WorkflowAgent agent, String taskId, String query,
                        String nodeId, String conversationId) {
                Object queryParam = (nodeId == null || nodeId.isBlank())
                                ? query
                                : toInteractionInput(nodeId, query);

                Iterator<Object> stream = Runner.runAgentStreaming(
                                agent,
                                Map.of("query", queryParam, "conversation_id", conversationId),
                                null,
                                null,
                                List.of(StreamMode.OUTPUT));

                return consumeTaskStream(stream, taskId, conversationId);
        }

        /**
         * Execute a chat query and return the raw stream for the caller to process.
         */
        public static Iterator<Object> executeTaskStreaming(
                        WorkflowAgent agent, String taskId, String query,
                        String nodeId, String conversationId) {
                Object queryParam = (nodeId == null || nodeId.isBlank())
                                ? query
                                : toInteractionInput(nodeId, query);

                return Runner.runAgentStreaming(
                                agent,
                                Map.of("query", queryParam, "conversation_id", conversationId),
                                null,
                                null,
                                List.of(StreamMode.OUTPUT));
        }

        // ======================== Private helpers ========================

        private static A2ATask consumeTaskStream(
                        Iterator<Object> stream, String taskId, String conversationId) {
                String lastText = "[没有返回可显示的输出]";
                String lastInteractionText = null;
                String lastInteractionNodeId = null;
                boolean hasInteraction = false;
                boolean hasResult = false;

                while (stream.hasNext()) {
                        Object item = stream.next();
                        if (!(item instanceof OutputSchema output)) {
                                continue;
                        }

                        String type = output.getType();
                        Object payload = output.getPayload();

                        if (Constant.INTERACTION.equals(type)
                                        || "interaction".equals(type)) {
                                InteractionOutput interaction = toInteraction(payload);
                                if (interaction != null) {
                                        lastInteractionText = stringify(interaction.getValue());
                                        lastInteractionNodeId = interaction.getId();
                                } else {
                                        lastInteractionText = stringify(payload);
                                        lastInteractionNodeId = "questioner";
                                }
                                hasInteraction = true;
                        } else {
                                String text = extractDisplayText(payload);
                                if (!text.isBlank()) {
                                        lastText = text;
                                        hasResult = true;
                                }
                        }
                }

                // Build the task
                String status;
                List<A2AMessage> messages;

                if (lastInteractionText != null && !hasResult) {
                        // Task needs more input
                        status = "input-required";
                        messages = List.of(
                                        new A2AMessage("agent", List.of(
                                                        Map.of("type", "text", "text", lastInteractionText))));
                } else {
                        status = "completed";
                        messages = List.of(
                                        new A2AMessage("agent", List.of(
                                                        Map.of("type", "text", "text", lastText))));
                }

                return new A2ATask(taskId, status, messages, List.of(), conversationId);
        }

        // ======================== Static helpers ========================

        /**
         * Safely stringify an arbitrary object to a display string.
         */
        public static String stringify(Object obj) {
                if (obj == null) {
                        return "";
                }
                if (obj instanceof String s) {
                        return s;
                }
                try {
                        String json = MAPPER.writeValueAsString(obj);
                        return !json.equals("null") ? json : String.valueOf(obj);
                } catch (Exception e) {
                        return String.valueOf(obj);
                }
        }

        /**
         * Extract human-readable display text from an output payload.
         */
        public static String extractDisplayText(Object payload) {
                if (payload == null) {
                        return "";
                }
                if (payload instanceof String s) {
                        return s;
                }
                if (payload instanceof Map<?, ?> map) {
                        // Try common keys
                        for (String key : List.of("text", "content", "message", "value")) {
                                Object v = map.get(key);
                                if (v instanceof String s && !s.isBlank()) {
                                        return s;
                                }
                        }
                }
                String raw = stringify(payload);
                return !raw.isBlank() ? raw : "";
        }

        private static InteractionOutput toInteraction(Object payload) {
                if (payload instanceof InteractionOutput io) {
                        return io;
                }
                if (payload instanceof Map<?, ?> map) {
                        Object nodeId = map.get("id");
                        Object value = map.get("value");
                        return new InteractionOutput(
                                        nodeId == null ? "questioner" : String.valueOf(nodeId), value);
                }
                return null;
        }

        private static Map<String, Object> toInteractionInput(String nodeId, String text) {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("id", nodeId);
                input.put("value", text);
                return input;
        }

        // ======================== Workflow builder ========================

        private static Workflow buildFinancialWorkflow(
                        String workflowId,
                        String workflowName,
                        String workflowDescription,
                        String fieldName,
                        String fieldDescription,
                        String responseTemplate) {
                WorkflowCard card = WorkflowCard.builder()
                                .id(workflowId)
                                .name(workflowName)
                                .version(WORKFLOW_VERSION)
                                .description(workflowDescription)
                                .inputParams(defaultInputSchema())
                                .build();

                QuestionerConfig questionerConfig = new QuestionerConfig();
                questionerConfig.setModelClientConfig(createQuestionerClientConfig());
                questionerConfig.setModelConfig(createQuestionerRequestConfig());
                questionerConfig.setQuestionContent("请补充" + fieldDescription);
                questionerConfig.setExtractFieldsFromResponse(true);
                questionerConfig.setFieldNames(List.of(FieldInfo.builder()
                                .fieldName(fieldName)
                                .description(fieldDescription)
                                .required(true)
                                .build()));
                questionerConfig.setWithChatHistory(false);
                questionerConfig.setMaxResponse(10);

                Workflow workflow = new Workflow(card);
                workflow.setStartComp("start", new Start(), Map.of("query", "${query}"), null);
                workflow.addWorkflowComp(
                                "questioner",
                                new QuestionerComponent(questionerConfig),
                                Map.of("query", "${start.query}"),
                                null);
                workflow.setEndComp(
                                "end",
                                new End(Map.of("responseTemplate", responseTemplate)),
                                Map.of(fieldName, "${questioner." + fieldName + "}"),
                                null);
                workflow.addConnection("start", "questioner");
                workflow.addConnection("questioner", "end");
                return workflow;
        }

        // ======================== Config helpers ========================

        private static ModelConfig createSharedModelConfig() {
                BaseModelInfo modelInfo = BaseModelInfo.builder()
                                .apiKey(SharedExampleApiConfigLoader.getApiKey())
                                .apiBase(SharedExampleApiConfigLoader.getApiBase())
                                .modelName(SharedExampleApiConfigLoader.getModelName())
                                .temperature(0.2)
                                .topP(0.8)
                                .timeout(120)
                                .build();
                return new ModelConfig(
                                SharedExampleApiConfigLoader.getModelProvider(), modelInfo);
        }

        private static ModelClientConfig createQuestionerClientConfig() {
                return ModelClientConfig.builder()
                                .clientProvider(SharedExampleApiConfigLoader.getModelProvider())
                                .apiKey(SharedExampleApiConfigLoader.getApiKey())
                                .apiBase(SharedExampleApiConfigLoader.getApiBase())
                                .verifySsl(SharedExampleApiConfigLoader.getSslVerify())
                                .timeout(120.0)
                                .build();
        }

        private static ModelRequestConfig createQuestionerRequestConfig() {
                return ModelRequestConfig.builder()
                                .modelName(SharedExampleApiConfigLoader.getModelName())
                                .temperature(0.2)
                                .topP(0.8)
                                .maxTokens(256)
                                .build();
        }

        private static Map<String, Object> defaultInputSchema() {
                return Map.of(
                                "type", "object",
                                "properties", Map.of(
                                                "query", Map.of(
                                                                "type", "string",
                                                                "description", "用户输入")),
                                "required", List.of("query"));
        }
}

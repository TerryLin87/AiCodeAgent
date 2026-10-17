package com.example.aiagentdemo.model;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek Chat Model 实现
 * - 支持 thinking 模式、reasoning_content 解析
 * - 支持 tool calling（function calling）
 */
@Slf4j
public class DeepSeekModel implements ChatModel {
    private static final String DEFAULT_API_URL = "https://api.deepseek.com/chat/completions";

    private final String apiUrl;
    @Value("${deepseek.apikey}")
    private String apiKey = "";
    private final String modelName;
    private final boolean enableThinking;
    private final String reasoningEffort;
    private final OkHttpClient httpClient;

    private DeepSeekModel(Builder builder) {
        this.apiUrl = StringUtils.hasText(builder.apiUrl) ? builder.apiUrl : DEFAULT_API_URL;
        this.apiKey = StringUtils.hasText(builder.apiKey)?builder.apiKey:this.apiKey;
        this.modelName = StringUtils.hasText(builder.modelName) ? builder.modelName : "deepseek-flash";
        this.enableThinking = builder.enableThinking;
        this.reasoningEffort = StringUtils.hasText(builder.reasoningEffort)  ? builder.reasoningEffort : "medium";

        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("DeepSeek API Key is required");
        }

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120))
                .writeTimeout(Duration.ofSeconds(30))
                .retryOnConnectionFailure(true)
                .build();

        log.info("DeepSeekModel initialized: model={}, thinking={}", modelName, enableThinking);
    }

    // ================================================================
    // 核心：doChat
    // ================================================================

    @Override
    public ChatResponse doChat(ChatRequest request) {
        try {
            JSONObject root = new JSONObject();
            root.put("model", modelName);

            // ============ 1. 序列化 messages ============
            JSONArray messages = new JSONArray();
            for (ChatMessage msg : request.messages()) {
                JSONObject serialized = serializeMessage(msg);
                if (serialized != null) {
                    messages.add(serialized);
                }
            }
            root.put("messages", messages);

            // ============ 2. 序列化 tools（★ 关键新增） ============
            if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
                JSONArray tools = new JSONArray();
                for (ToolSpecification spec : request.toolSpecifications()) {
                    tools.add(serializeToolSpecification(spec));
                }
                root.put("tools", tools);
                root.put("tool_choice", "auto");
                log.debug("已注册 {} 个工具到请求", tools.size());
            }

            // ============ 3. thinking 模式 ============
            if (enableThinking) {
                JSONObject thinking = new JSONObject();
                thinking.put("type", "enabled");
                root.put("thinking", thinking);
                root.put("reasoning_effort", reasoningEffort);
            }

            root.put("stream", false);
            root.put("max_tokens", 8192);

            if (log.isDebugEnabled()) {
                log.debug("DeepSeek request: {}", root.toJSONString());
            }

            // ============ 4. 发请求 ============
            Request httpRequest = new Request.Builder()
                    .url(apiUrl)
                    .post(RequestBody.create(
                            root.toJSONString(),
                            MediaType.parse("application/json; charset=utf-8")
                    ))
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code() + ": " + responseBody);
                }

                if (log.isDebugEnabled()) {
                    log.debug("DeepSeek response: {}", responseBody);
                }

                JSONObject json = JSONObject.parseObject(responseBody);

                if (json.containsKey("error")) {
                    JSONObject error = json.getJSONObject("error");
                    throw new IOException("API Error: " + error.getString("message"));
                }

                JSONArray choices = json.getJSONArray("choices");
                if (choices == null || choices.isEmpty()) {
                    throw new IOException("Empty choices in response");
                }

                JSONObject choice = choices.getJSONObject(0);
                JSONObject message = choice.getJSONObject("message");

                // ============ 5. 解析响应（★ 关键改动） ============
                return parseResponse(message, choice);
            }

        } catch (Exception e) {
            log.error("DeepSeek chat error", e);
            return ChatResponse.builder()
                    .aiMessage(new AiMessage("[系统提示] 模型暂时不可用: " + e.getMessage()))
                    .metadata(ChatResponseMetadata.builder()
                            .modelName(modelName)
                            .finishReason(FinishReason.STOP)
                            .build())
                    .build();
        }
    }

    // ================================================================
    // 消息序列化
    // ================================================================

    /**
     * 把 LangChain4j 的 ChatMessage 序列化成 OpenAI 格式的 JSON
     * 关键：不同类型的 message 有不同格式，尤其是 AiMessage 带 tool_calls、
     * ToolExecutionResultMessage 带 tool_call_id
     */
    private JSONObject serializeMessage(ChatMessage msg) {
        JSONObject m = new JSONObject();

        if (msg instanceof SystemMessage systemMsg) {
            m.put("role", "system");
            m.put("content", systemMsg.text());
            return m;
        }

        if (msg instanceof UserMessage userMsg) {
            m.put("role", "user");
            m.put("content", extractUserText(userMsg));
            return m;
        }

        if (msg instanceof AiMessage aiMsg) {
            m.put("role", "assistant");

            // ★ 关键：如果 AiMessage 有 toolExecutionRequests，必须带上 tool_calls
            List<ToolExecutionRequest> toolRequests = aiMsg.toolExecutionRequests();
            if (toolRequests != null && !toolRequests.isEmpty()) {
                JSONArray toolCalls = new JSONArray();
                for (ToolExecutionRequest req : toolRequests) {
                    JSONObject tc = new JSONObject();
                    tc.put("id", req.id());
                    tc.put("type", "function");
                    JSONObject func = new JSONObject();
                    func.put("name", req.name());
                    func.put("arguments", req.arguments());
                    tc.put("function", func);
                    toolCalls.add(tc);
                }
                m.put("tool_calls", toolCalls);
                // content 可以为 null
                if (StringUtils.hasText(aiMsg.text())) {
                    m.put("content", aiMsg.text());
                } else {
                    m.put("content", null);
                }
            } else {
                m.put("content", aiMsg.text() != null ? aiMsg.text() : "");
            }
            return m;
        }

        if (msg instanceof ToolExecutionResultMessage toolMsg) {
            m.put("role", "tool");
            m.put("tool_call_id", toolMsg.id());   // ★ 必须带 tool_call_id
            m.put("content", toolMsg.text());
            return m;
        }

        // 其他类型（如 CustomMessage），跳过
        log.warn("未处理的消息类型: {}", msg.getClass().getName());
        return null;
    }

    private String extractUserText(UserMessage userMsg) {
        if (userMsg.hasSingleText()) {
            return userMsg.singleText();
        }
        StringBuilder sb = new StringBuilder();
        for (Content content : userMsg.contents()) {
            if (content instanceof TextContent textContent) {
                sb.append(textContent.text());
            }
        }
        return sb.toString();
    }

    // ================================================================
    // 工具定义序列化（★ 关键新增）
    // ================================================================

    private JSONObject serializeToolSpecification(ToolSpecification spec) {
        JSONObject tool = new JSONObject();
        tool.put("type", "function");

        JSONObject function = new JSONObject();
        function.put("name", spec.name());
        function.put("description", spec.description());

        // 序列化参数 schema
        JsonObjectSchema params = spec.parameters();
        if (params != null) {
            function.put("parameters", serializeSchema(params));
        } else {
            // 没有参数的工具，给个空的 object schema
            JSONObject emptySchema = new JSONObject();
            emptySchema.put("type", "object");
            emptySchema.put("properties", new JSONObject());
            function.put("parameters", emptySchema);
        }

        tool.put("function", function);
        return tool;
    }

    /**
     * 把 LangChain4j 的 JsonObjectSchema 转成 JSON Schema
     */
    private JSONObject serializeSchema(JsonObjectSchema schema) {
        JSONObject result = new JSONObject();
        result.put("type", "object");

        // properties
        JSONObject properties = new JSONObject();
        if (schema.properties() != null) {
            for (var entry : schema.properties().entrySet()) {
                properties.put(entry.getKey(), serializeSchemaElement(entry.getValue()));
            }
        }
        result.put("properties", properties);

        // required
        if (schema.required() != null && !schema.required().isEmpty()) {
            JSONArray required = new JSONArray();
            required.addAll(schema.required());
            result.put("required", required);
        }

        return result;
    }

    /**
     * 递归序列化 schema 元素（支持嵌套对象、数组、枚举等）
     */
    private JSONObject serializeSchemaElement(JsonSchemaElement element) {
        JSONObject obj = new JSONObject();

        if (element instanceof JsonStringSchema s) {
            obj.put("type", "string");
            if (s.description() != null) obj.put("description", s.description());
        } else if (element instanceof JsonIntegerSchema s) {
            obj.put("type", "integer");
            if (s.description() != null) obj.put("description", s.description());
        } else if (element instanceof JsonNumberSchema s) {
            obj.put("type", "number");
            if (s.description() != null) obj.put("description", s.description());
        } else if (element instanceof JsonBooleanSchema s) {
            obj.put("type", "boolean");
            if (s.description() != null) obj.put("description", s.description());
        } else if (element instanceof JsonArraySchema s) {
            obj.put("type", "array");
            if (s.description() != null) obj.put("description", s.description());
            if (s.items() != null) {
                obj.put("items", serializeSchemaElement(s.items()));
            }
        } else if (element instanceof JsonObjectSchema s) {
            return serializeSchema(s);   // 递归
        } else if (element instanceof JsonEnumSchema s) {
            obj.put("type", "string");
            JSONArray enumValues = new JSONArray();
            enumValues.addAll(s.enumValues());
            obj.put("enum", enumValues);
            if (s.description() != null) obj.put("description", s.description());
        } else {
            // 兜底：当字符串处理
            obj.put("type", "string");
        }

        return obj;
    }

    // ================================================================
    // 响应解析（★ 关键改动）
    // ================================================================

    private ChatResponse parseResponse(JSONObject message, JSONObject choice) {
        String content = message.getString("content");
        if (content == null) content = "";

        // 1. 检查是否有 tool_calls
        JSONArray toolCalls = message.getJSONArray("tool_calls");
        if (toolCalls != null && !toolCalls.isEmpty()) {
            List<ToolExecutionRequest> requests = new ArrayList<>();
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject tc = toolCalls.getJSONObject(i);
                String id = tc.getString("id");
                JSONObject func = tc.getJSONObject("function");
                String name = func.getString("name");
                String arguments = func.getString("arguments");

                requests.add(ToolExecutionRequest.builder()
                        .id(id)
                        .name(name)
                        .arguments(arguments)
                        .build());
            }

            AiMessage aiMessage = AiMessage.from(requests);

            return ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .metadata(ChatResponseMetadata.builder()
                            .modelName(modelName)
                            .finishReason(FinishReason.TOOL_EXECUTION)
                            .build())
                    .build();
        }

        // 2. 普通文本响应
        String reasoning = message.getString("reasoning_content");
        if (reasoning != null && !reasoning.isEmpty() && log.isDebugEnabled()) {
            log.debug("DeepSeek reasoning: {}", reasoning);
        }

        AiMessage aiMessage = new AiMessage(content);
        return ChatResponse.builder()
                .aiMessage(aiMessage)
                .metadata(ChatResponseMetadata.builder()
                        .modelName(modelName)
                        .finishReason(FinishReason.STOP)
                        .build())
                .build();
    }

    // ================================================================
    // Builder
    // ================================================================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String apiUrl;
        private String apiKey;
        private String modelName;
        private boolean enableThinking = false;
        private String reasoningEffort = "medium";

        public Builder apiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder enableThinking(boolean enableThinking) {
            this.enableThinking = enableThinking;
            return this;
        }

        public Builder reasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
            return this;
        }

        public DeepSeekModel build() {
            return new DeepSeekModel(this);
        }
    }
}
package com.example.aiagentdemo.model;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.aiagentdemo.data.execeptions.ModelCircuitOpenException;
import com.example.aiagentdemo.data.execeptions.ModelUnavailableException;
import com.example.aiagentdemo.enums.CallType;
import com.example.aiagentdemo.recorder.TokenUsageRecorder;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
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
    private static final int MAX_RETRIES = 2;

    private final String apiUrl;
    private final String apiKey;
    private final String modelName;
    private final boolean enableThinking;
    private final String reasoningEffort;
    private final OkHttpClient httpClient;
    private final CircuitBreaker circuitBreaker;
    private final TokenUsageRecorder tokenUsageRecorder;

    private DeepSeekModel(Builder builder) {
        this.apiUrl = StringUtils.hasText(builder.apiUrl) ? builder.apiUrl : DEFAULT_API_URL;
        this.apiKey = builder.apiKey;
        this.modelName = StringUtils.hasText(builder.modelName) ? builder.modelName : "deepseek-flash";
        this.enableThinking = builder.enableThinking;
        this.reasoningEffort = StringUtils.hasText(builder.reasoningEffort) ? builder.reasoningEffort : "medium";
        this.tokenUsageRecorder = builder.tokenUsageRecorder;

        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("DeepSeek API Key is required");
        }

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120))
                .writeTimeout(Duration.ofSeconds(30))
                .retryOnConnectionFailure(true)
                .build();

        // 熔断器：按 modelName 隔离
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(cbConfig);
        this.circuitBreaker = registry.circuitBreaker("deepseek-" + modelName);

        log.info("DeepSeekModel initialized: model={}, thinking={}", modelName, enableThinking);
    }

    // ================================================================
    // 核心：doChat
    // ================================================================

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return doChat(request, CallType.MAIN_CHAT, null);
    }

    /**
     * 带调用类型和会话 ID 的入口。sessionId 用于 token 统计维度，可为 null。
     */
    public ChatResponse doChat(ChatRequest request, CallType callType, String sessionId) {
        if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            throw new ModelCircuitOpenException(
                    "DeepSeek circuit breaker is OPEN for model " + modelName);
        }

        return circuitBreaker.executeSupplier(() -> doChatWithRetry(request, callType, sessionId));
    }


    public ChatResponse doChatWithRetry(ChatRequest request, CallType callType, String sessionId) {
        IOException lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long backoffMs = 1000L * attempt;
                log.warn("DeepSeek retry {}/{}, backoff {}ms", attempt, MAX_RETRIES, backoffMs);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ModelUnavailableException("Interrupted during retry backoff", e);
                }
            }

            try {
                return doChatOnce(request, callType, sessionId);
            } catch (RetryableException e) {
                lastException = e;
            }

        }

        throw new ModelUnavailableException(
                "DeepSeek call failed after " + MAX_RETRIES + " retries", lastException);
    }

    /**
     * 可重试异常：网络错误、HTTP 5xx、超时。
     */
    private static class RetryableException extends IOException {
        RetryableException(String message) { super(message); }
        RetryableException(String message, Throwable cause) { super(message, cause); }
    }


    // ================================================================
    // 单次调用
    // ================================================================

    private ChatResponse doChatOnce(ChatRequest request, CallType callType, String sessionId) throws RetryableException {

        try {
            JSONObject root = new JSONObject();
            root.put("model", modelName);

            // 1. messages
            JSONArray messages = new JSONArray();
            for (ChatMessage msg : request.messages()) {
                JSONObject serialized = serializeMessage(msg);
                if (serialized != null) {
                    messages.add(serialized);
                }
            }
            root.put("messages", messages);

            // 2. tools
            if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
                JSONArray tools = new JSONArray();
                for (ToolSpecification spec : request.toolSpecifications()) {
                    tools.add(serializeToolSpecification(spec));
                }
                root.put("tools", tools);
                root.put("tool_choice", "auto");
            }

            // 3. thinking
            if (enableThinking) {
                JSONObject thinking = new JSONObject();
                thinking.put("type", "enabled");
                root.put("thinking", thinking);
                root.put("reasoning_effort", reasoningEffort);
            }

            root.put("stream", false);
            root.put("max_tokens", callType.getMaxTokens());

            if (log.isDebugEnabled()) {
                log.debug("DeepSeek request: model={}, messages={}, tools={}, maxTokens={}, callType={}",
                        modelName, messages.size(),
                        request.toolSpecifications() != null ? request.toolSpecifications().size() : 0,
                        callType.getMaxTokens(), callType);
            }

            // 4. HTTP 请求，按 CallType 设置 callTimeout
            OkHttpClient clientWithTimeout = httpClient.newBuilder()
                    .callTimeout(Duration.ofSeconds(callType.getTimeoutSeconds()))
                    .readTimeout(Duration.ofSeconds(callType.getTimeoutSeconds()))
                    .build();

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

                if (response.code() >= 500) {
                    throw new RetryableException("HTTP " + response.code() + " (server error)");
                }
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
                    throw new ModelUnavailableException("Empty choices in response");
                }

                // 5. token 统计
                recordTokenUsage(json, callType, sessionId);

                JSONObject choice = choices.getJSONObject(0);
                JSONObject message = choice.getJSONObject("message");

                return parseResponse(message, choice);
            }catch (java.net.SocketTimeoutException e) {
                throw new RetryableException("Request timeout after " + callType.getTimeoutSeconds() + "s", e);
            } catch (IOException e) {
                throw new RetryableException("Network error: " + e.getMessage(), e);
            }

        } catch (ModelUnavailableException e) {
            throw e;   // 不可重试的业务异常，直接抛
        } catch (RetryableException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelUnavailableException("Unexpected error during DeepSeek call", e);
        }
    }

    // ================================================================
    // Token 统计
    // ================================================================

    private void recordTokenUsage(JSONObject json, CallType callType, String sessionId) {
        if (tokenUsageRecorder == null) return;
        JSONObject usage = json.getJSONObject("usage");
        if (usage == null) return;
        int input = usage.getIntValue("prompt_tokens", 0);
        int output = usage.getIntValue("completion_tokens", 0);
        tokenUsageRecorder.record(sessionId, modelName, callType, input, output);
    }

    // ================================================================
    // 消息序列化
    // ================================================================

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
            m.put("tool_call_id", toolMsg.id());
            m.put("content", toolMsg.text());
            return m;
        }

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
    // 工具定义序列化
    // ================================================================

    private JSONObject serializeToolSpecification(ToolSpecification spec) {
        JSONObject tool = new JSONObject();
        tool.put("type", "function");

        JSONObject function = new JSONObject();
        function.put("name", spec.name());
        function.put("description", spec.description());

        JsonObjectSchema params = spec.parameters();
        if (params != null) {
            function.put("parameters", serializeSchema(params));
        } else {
            JSONObject emptySchema = new JSONObject();
            emptySchema.put("type", "object");
            emptySchema.put("properties", new JSONObject());
            function.put("parameters", emptySchema);
        }

        tool.put("function", function);
        return tool;
    }

    private JSONObject serializeSchema(JsonObjectSchema schema) {
        JSONObject result = new JSONObject();
        result.put("type", "object");

        JSONObject properties = new JSONObject();
        if (schema.properties() != null) {
            for (var entry : schema.properties().entrySet()) {
                properties.put(entry.getKey(), serializeSchemaElement(entry.getValue()));
            }
        }
        result.put("properties", properties);

        if (schema.required() != null && !schema.required().isEmpty()) {
            JSONArray required = new JSONArray();
            required.addAll(schema.required());
            result.put("required", required);
        }

        return result;
    }

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
            return serializeSchema(s);
        } else if (element instanceof JsonEnumSchema s) {
            obj.put("type", "string");
            JSONArray enumValues = new JSONArray();
            enumValues.addAll(s.enumValues());
            obj.put("enum", enumValues);
            if (s.description() != null) obj.put("description", s.description());
        } else {
            obj.put("type", "string");
        }

        return obj;
    }

    // ================================================================
    // 响应解析
    // ================================================================

    private ChatResponse parseResponse(JSONObject message, JSONObject choice) {
        String content = message.getString("content");
        if (content == null) content = "";

        JSONArray toolCalls = message.getJSONArray("tool_calls");
        if (toolCalls != null && !toolCalls.isEmpty()) {
            List<ToolExecutionRequest> requests = new ArrayList<>();
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject tc = toolCalls.getJSONObject(i);
                JSONObject func = tc.getJSONObject("function");
                requests.add(ToolExecutionRequest.builder()
                        .id(tc.getString("id"))
                        .name(func.getString("name"))
                        .arguments(func.getString("arguments"))
                        .build());
            }

            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(requests))
                    .metadata(ChatResponseMetadata.builder()
                            .modelName(modelName)
                            .finishReason(FinishReason.TOOL_EXECUTION)
                            .build())
                    .build();
        }

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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
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
        private TokenUsageRecorder tokenUsageRecorder;

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

        public Builder tokenUsageRecorder(TokenUsageRecorder recorder) {
            this.tokenUsageRecorder = recorder;
            return this;
        }

        public DeepSeekModel build() {
            return new DeepSeekModel(this);
        }
    }
}
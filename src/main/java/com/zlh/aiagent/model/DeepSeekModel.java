package com.zlh.aiagent.model;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zlh.aiagent.data.common.execeptions.ModelCircuitOpenException;
import com.zlh.aiagent.data.common.execeptions.ModelUnavailableException;
import com.zlh.aiagent.enums.CallType;
import com.zlh.aiagent.instances.IStreamChatCallback;
import com.zlh.aiagent.recorder.TokenUsageRecorder;
import com.zlh.aiagent.utils.CancellationToken;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DeepSeek Chat Model 实现
 * - 支持 thinking 模式、reasoning_content 解析
 * - 支持 tool calling（function calling）
 * - 按 CallType 路由到 main / reasoning / cheap 三档模型
 * - 熔断器按模型名隔离，推理档熔断时自动降级到主力档
 * - 支持 SSE 流式输出（streamChat，阻塞到本轮流结束）
 * - 支持协作式请求取消（CancellationToken）
 */
@Slf4j
public class DeepSeekModel implements ChatModel {
    private static final String DEFAULT_API_URL = "https://api.deepseek.com/chat/completions";
    private static final int MAX_RETRIES = 2;

    private final String apiUrl;
    private final String apiKey;
    /** 兼容字段：即 mainModelName */
    private final String modelName;
    private final String mainModelName;
    private final String reasoningModelName;
    private final String cheapModelName;
    private final boolean enableThinking;
    private final String reasoningEffort;
    private final OkHttpClient httpClient;
    private final TokenUsageRecorder tokenUsageRecorder;

    /** 熔断器配置（所有模型共享滑动窗口参数） */
    private final CircuitBreakerRegistry breakerRegistry;
    /** modelName -> 独立熔断器 */
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    /** 由 RedisConfig 提供的 ObjectMapper（注册了 JavaTimeModule），供 JSON 扩展用 */
    @Autowired
    @Qualifier("agentSessionObjectMapper")
    private ObjectMapper objectMapper;

    private DeepSeekModel(Builder builder) {
        this.apiUrl = StringUtils.hasText(builder.apiUrl) ? builder.apiUrl : DEFAULT_API_URL;
        this.apiKey = builder.apiKey;

        // 三档模型名：builder.modelName() 向后兼容（老配置 deepseek.model-name 作为主力档）
        this.mainModelName = firstNonBlank(builder.mainModelName, builder.modelName, "deepseek-chat");
        this.reasoningModelName = firstNonBlank(builder.reasoningModelName, null);
        this.cheapModelName = firstNonBlank(builder.cheapModelName, null);
        this.modelName = this.mainModelName;

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

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        this.breakerRegistry = CircuitBreakerRegistry.of(cbConfig);

        breakerFor(mainModelName);
        if (StringUtils.hasText(reasoningModelName)) breakerFor(reasoningModelName);
        if (StringUtils.hasText(cheapModelName)) breakerFor(cheapModelName);

        log.info("DeepSeekModel initialized: main={}, reasoning={}, cheap={}, thinking={}",
                mainModelName, reasoningModelName, cheapModelName, enableThinking);
    }

    // ================================================================
    // 模型档位选择 + 熔断 + 降级
    // ================================================================

    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (StringUtils.hasText(s)) return s;
        }
        return null;
    }

    private String resolveModelName(CallType callType) {
        if (callType == null) return mainModelName;
        return switch (callType) {
            case EVALUATION, PLAN -> reasoningModelName != null ? reasoningModelName : mainModelName;
            case ROUTING, SUMMARY -> cheapModelName != null ? cheapModelName : mainModelName;
            default -> mainModelName;
        };
    }

    private CircuitBreaker breakerFor(String model) {
        return breakers.computeIfAbsent(model, m -> breakerRegistry.circuitBreaker("deepseek-" + m));
    }

    private String selectModelWithFallback(CallType callType) {
        String model = resolveModelName(callType);
        CircuitBreaker cb = breakerFor(model);
        if (cb.getState() == CircuitBreaker.State.OPEN && !model.equals(mainModelName)) {
            CircuitBreaker mainCb = breakerFor(mainModelName);
            if (mainCb.getState() != CircuitBreaker.State.OPEN) {
                log.warn("模型 {} 熔断中，自动降级到 {}", model, mainModelName);
                return mainModelName;
            }
        }
        if (cb.getState() == CircuitBreaker.State.OPEN) {
            throw new ModelCircuitOpenException(
                    "DeepSeek circuit breaker is OPEN for model " + model);
        }
        return model;
    }

    // ================================================================
    // ChatModel 接口：同步
    // ================================================================

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return doChat(request, CallType.MAIN_CHAT, null, null);
    }

    public ChatResponse doChat(ChatRequest request, CallType callType, String sessionId) {
        return doChat(request, callType, sessionId, null);
    }

    public ChatResponse doChat(ChatRequest request, CallType callType, String sessionId,
                               CancellationToken cancelToken) {
        String model = selectModelWithFallback(callType);
        CircuitBreaker cb = breakerFor(model);
        final String usedModel = model;
        return cb.executeSupplier(
                () -> doChatWithRetry(request, callType, sessionId, usedModel, cancelToken));
    }

    public ChatResponse doChatWithRetry(ChatRequest request, CallType callType, String sessionId,
                                        String model, CancellationToken cancelToken) {
        IOException lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (cancelToken != null) cancelToken.throwIfCancelled();
            if (attempt > 0) {
                long backoffMs = 1000L * attempt;
                log.warn("DeepSeek retry {}/{} for model {}, backoff {}ms", attempt, MAX_RETRIES, model, backoffMs);
                try {
                    // 退避期间也要响应取消
                    long slept = 0;
                    while (slept < backoffMs) {
                        if (cancelToken != null) cancelToken.throwIfCancelled();
                        Thread.sleep(Math.min(200, backoffMs - slept));
                        slept += 200;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ModelUnavailableException("Interrupted during retry backoff", e);
                }
            }
            try {
                return doChatOnce(request, callType, sessionId, model, cancelToken);
            } catch (RetryableException e) {
                lastException = e;
            }
        }
        throw new ModelUnavailableException(
                "DeepSeek call failed after " + MAX_RETRIES + " retries, model=" + model, lastException);
    }

    private ChatResponse doChatOnce(ChatRequest chatRequest, CallType callType, String sessionId, String model,
                                    CancellationToken cancelToken) throws RetryableException {
        if (cancelToken != null) cancelToken.throwIfCancelled();
        try {
            JSONObject requestBody = buildChatRequestJson(chatRequest, callType, model, false);
            Request httpRequest = new Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody.toJSONString(),
                            MediaType.parse("application/json; charset=utf-8")))
                    .build();

            // 用带超时的专用 client，并用 Call 便于取消时真正断开底层 socket
            OkHttpClient client = httpClient.newBuilder()
                    .readTimeout(Duration.ofSeconds(callType.getTimeoutSeconds()))
                    .build();
            Call call = client.newCall(httpRequest);
            if (cancelToken != null) {
                // 注册取消钩子：一旦令牌被取消，立即 cancel OkHttp Call（关 socket）
                cancelToken.throwIfCancelled();
                Thread cancelHook = new Thread(() -> {
                    if (cancelToken.isCancelled()) {
                        call.cancel();
                    }
                }, "deepseek-cancel-" + sessionId);
                cancelToken.addCancelListener(cancelHook);
            }

            try (Response response = call.execute()) {
                if (cancelToken != null) cancelToken.throwIfCancelled();

                if (response.code() == 429 || response.code() >= 500) {
                    String body = response.body() != null ? response.body().string() : "";
                    log.warn("DeepSeek HTTP {} for model {}, retryable: {}", response.code(), model, body);
                    throw new RetryableException("HTTP " + response.code() + ": " + body);
                }
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "";
                    log.error("DeepSeek API error: code={}, body={}", response.code(), body);
                    if (response.code() == 401 || response.code() == 403) {
                        throw new ModelUnavailableException("DeepSeek API auth error: " + body);
                    }
                    throw new ModelUnavailableException("DeepSeek API error " + response.code() + ": " + body);
                }
                String responseBody = response.body() != null ? response.body().string() : "{}";
                if (log.isDebugEnabled()) {
                    log.debug("DeepSeek raw response(model={}): {}", model, responseBody);
                }
                return parseResponse(responseBody, callType, sessionId, model);
            }
        } catch (IOException e) {
            // 取消时的 IOException 统一翻译成 CancelledException，外层能识别
            if (cancelToken != null && cancelToken.isCancelled()) {
                throw new CancellationToken.CancelledException("请求已取消");
            }
            throw new RetryableException("Network error calling DeepSeek model " + model, e);
        }
    }

    // ================================================================
    // 流式：streamChat
    // ================================================================

    public void streamChat(ChatRequest chatRequest, CallType callType, IStreamChatCallback callback) {
        streamChat(chatRequest, callType, callback, null);
    }

    /**
     * 流式调用：阻塞直到本轮 SSE 结束（或超时/出错/被取消）。
     * 传入非 null 的 cancelToken 时：
     * <ul>
     *   <li>每个 SSE 事件前都会检查取消，取消后立即 cancel EventSource 断开连接；</li>
     *   <li>等待超时阶段每 200ms 检查一次取消，避免 await 时僵住；</li>
     *   <li>错误回调用 {@link CancellationToken.CancelledException}，调用方可识别。</li>
     * </ul>
     */
    public void streamChat(ChatRequest chatRequest, CallType callType,
                           IStreamChatCallback callback, CancellationToken cancelToken) {
        String model = selectModelWithFallback(callType);
        if (cancelToken != null) cancelToken.throwIfCancelled();

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean finished = new AtomicBoolean(false);
        final List<ToolExecutionRequest.Builder> toolBuilders = new ArrayList<>();
        final List<StringBuilder> argBuffers = new ArrayList<>();
        final long timeoutSec = callType.getTimeoutSeconds();
        final String sessionId = null;

        final AtomicReference<EventSource> sourceRef = new AtomicReference<>();
        final AtomicReference<OkHttpClient> clientRef = new AtomicReference<>();

        try {
            JSONObject reqBody = buildChatRequestJson(chatRequest, callType, model, true);
            Request httpReq = new Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(reqBody.toJSONString(),
                            MediaType.parse("application/json; charset=utf-8")))
                    .build();

            OkHttpClient streamClient = httpClient.newBuilder()
                    .readTimeout(Duration.ofSeconds(timeoutSec))
                    .callTimeout(Duration.ZERO)
                    .build();
            clientRef.set(streamClient);

            EventSource source = EventSources.createFactory(streamClient).newEventSource(
                    httpReq, new EventSourceListener() {

                        @Override
                        public void onOpen(EventSource es, Response response) {
                            if (cancelToken != null && cancelToken.isCancelled()) {
                                es.cancel();
                                if (finished.compareAndSet(false, true)) {
                                    callback.onError(new CancellationToken.CancelledException("请求已取消"));
                                    done.countDown();
                                }
                            }
                        }

                        @Override
                        public void onEvent(EventSource es, String id, String type, String data) {
                            // 每个 chunk 都先检查取消
                            if (cancelToken != null && cancelToken.isCancelled()) {
                                es.cancel();
                                if (finished.compareAndSet(false, true)) {
                                    callback.onError(new CancellationToken.CancelledException("请求已取消"));
                                    done.countDown();
                                }
                                return;
                            }
                            if ("[DONE]".equals(data)) {
                                if (finished.compareAndSet(false, true)) {
                                    finishStream(done, callback, toolBuilders, argBuffers, model, sessionId, callType);
                                }
                                return;
                            }
                            try {
                                JSONObject chunk = JSON.parseObject(data);
                                if (chunk.containsKey("usage") && tokenUsageRecorder != null) {
                                    recordTokenUsage(chunk, callType, sessionId);
                                }
                                JSONArray choices = chunk.getJSONArray("choices");
                                if (choices == null || choices.isEmpty()) return;
                                JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                                if (delta == null) return;

                                Object reasoning = delta.get("reasoning_content");
                                if (reasoning != null && !reasoning.toString().isBlank()) {
                                    callback.onToken(reasoning.toString());
                                }
                                Object contentVal = delta.get("content");
                                if (contentVal != null && !contentVal.toString().isBlank()) {
                                    callback.onToken(contentVal.toString());
                                }
                                JSONArray arr = delta.getJSONArray("tool_calls");
                                if (arr == null) return;
                                for (int i = 0; i < arr.size(); i++) {
                                    JSONObject item = arr.getJSONObject(i);
                                    int index = item.containsKey("index") ? item.getIntValue("index") : 0;
                                    while (index >= toolBuilders.size()) {
                                        toolBuilders.add(ToolExecutionRequest.builder());
                                        argBuffers.add(new StringBuilder());
                                    }
                                    ToolExecutionRequest.Builder tb = toolBuilders.get(index);
                                    StringBuilder args = argBuffers.get(index);
                                    Object idVal = item.get("id");
                                    if (idVal != null) tb.id(idVal.toString());
                                    JSONObject func = item.getJSONObject("function");
                                    if (func != null) {
                                        Object nameVal = func.get("name");
                                        if (nameVal != null) tb.name(nameVal.toString());
                                        Object argVal = func.get("arguments");
                                        if (argVal != null) args.append(argVal);
                                    }
                                }
                            } catch (Exception e) {
                                log.warn("解析 SSE chunk 失败: {}", data, e);
                            }
                        }

                        @Override
                        public void onClosed(EventSource es) {
                            if (finished.compareAndSet(false, true)) {
                                finishStream(done, callback, toolBuilders, argBuffers, model, sessionId, callType);
                            }
                        }

                        @Override
                        public void onFailure(EventSource es, Throwable t, Response response) {
                            if (!finished.compareAndSet(false, true)) return;
                            // 如果是被我们主动 cancel 的，且令牌已取消，回调取消而非错误
                            if (cancelToken != null && cancelToken.isCancelled()) {
                                callback.onError(new CancellationToken.CancelledException("请求已取消"));
                            } else {
                                breakerFor(model).onError(0, TimeUnit.NANOSECONDS, t != null ? t : new IOException("SSE 连接中断"));
                                callback.onError(t != null ? t : new IOException("SSE 连接中断"));
                            }
                            done.countDown();
                        }
                    });
            sourceRef.set(source);

            // 等待结束，期间每 200ms 检查一次取消
            long deadline = System.currentTimeMillis() + (timeoutSec + 5L) * 1000L;
            while (System.currentTimeMillis() < deadline) {
                if (done.await(200, TimeUnit.MILLISECONDS)) return;
                if (cancelToken != null && cancelToken.isCancelled()) {
                    EventSource es = sourceRef.get();
                    if (es != null) es.cancel();
                    if (finished.compareAndSet(false, true)) {
                        callback.onError(new CancellationToken.CancelledException("请求已取消"));
                        done.countDown();
                    }
                    return;
                }
            }
            // 超时
            EventSource es = sourceRef.get();
            if (es != null) es.cancel();
            if (finished.compareAndSet(false, true)) {
                callback.onError(new ModelUnavailableException(
                        "流式响应超时（" + (timeoutSec + 5) + " 秒未结束），model=" + model));
                done.countDown();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (finished.compareAndSet(false, true)) {
                callback.onError(e);
                done.countDown();
            }
        } catch (Exception e) {
            if (finished.compareAndSet(false, true)) {
                callback.onError(e);
                done.countDown();
            }
        }
    }

    private void finishStream(CountDownLatch done, IStreamChatCallback callback,
                              List<ToolExecutionRequest.Builder> builders, List<StringBuilder> argBuffers,
                              String model, String sessionId, CallType callType) {
        List<ToolExecutionRequest> toolCalls = new ArrayList<>();
        for (int i = 0; i < builders.size(); i++) {
            builders.get(i).arguments(argBuffers.get(i).toString());
            ToolExecutionRequest req = builders.get(i).build();
            if (req.name() != null && !req.name().isBlank()) {
                toolCalls.add(req);
            }
        }
        breakerFor(model).onSuccess(0, TimeUnit.NANOSECONDS);
        if (!toolCalls.isEmpty()) {
            callback.onCompleteToolCalls(toolCalls);
        }
        callback.onComplete();
        done.countDown();
    }

    // ================================================================
    // 请求体构造
    // ================================================================

    private JSONObject buildChatRequestJson(ChatRequest request, CallType callType, String model, boolean stream) {
        JSONObject root = new JSONObject();
        root.put("model", model);
        root.put("stream", stream);
        if (stream) {
            root.put("stream_options", new JSONObject().fluentPut("include_usage", true));
        }
        root.put("max_tokens", callType.getMaxTokens());

        boolean cheap = callType == CallType.ROUTING || callType == CallType.SUMMARY;
        if (enableThinking && !cheap) {
            JSONObject thinking = new JSONObject();
            thinking.put("type", "enabled");
            thinking.put("budget_tokens", Math.max(1024, Math.min(callType.getMaxTokens() * 2 / 3, 8192)));
            root.put("thinking", thinking);
            if (StringUtils.hasText(reasoningEffort)) {
                root.put("reasoning_effort", reasoningEffort);
            }
        }

        JSONArray messages = new JSONArray();
        for (ChatMessage msg : request.messages()) {
            messages.add(messageToJson(msg));
        }
        root.put("messages", messages);

        if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            JSONArray tools = new JSONArray();
            for (ToolSpecification spec : request.toolSpecifications()) {
                JSONObject tool = new JSONObject();
                tool.put("type", "function");
                JSONObject func = new JSONObject();
                func.put("name", spec.name());
                if (StringUtils.hasText(spec.description())) func.put("description", spec.description());
                if (spec.parameters() != null) func.put("parameters", serializeSchema(spec.parameters()));
                tool.put("function", func);
                tools.add(tool);
            }
            root.put("tools", tools);
            root.put("tool_choice", "auto");
        }
        return root;
    }

    private JSONObject messageToJson(ChatMessage msg) {
        JSONObject json = new JSONObject();
        if (msg instanceof SystemMessage sys) {
            json.put("role", "system");
            json.put("content", sys.text());
        } else if (msg instanceof UserMessage user) {
            json.put("role", "user");
            json.put("content", user.singleText());
        } else if (msg instanceof AiMessage ai) {
            json.put("role", "assistant");
            if (ai.hasToolExecutionRequests()) {
                JSONArray toolCalls = new JSONArray();
                for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                    JSONObject tc = new JSONObject();
                    tc.put("id", req.id());
                    tc.put("type", "function");
                    JSONObject func = new JSONObject();
                    func.put("name", req.name());
                    func.put("arguments", req.arguments());
                    tc.put("function", func);
                    toolCalls.add(tc);
                }
                json.put("content", ai.text() == null ? "" : ai.text());
                json.put("tool_calls", toolCalls);
            } else {
                json.put("content", ai.text() == null ? "" : ai.text());
            }
        } else if (msg instanceof ToolExecutionResultMessage tool) {
            json.put("role", "tool");
            json.put("tool_call_id", tool.id());
            json.put("content", tool.text());
        } else {
            json.put("role", "user");
            json.put("content", msg.type().name());
        }
        return json;
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
            if (s.items() != null) obj.put("items", serializeSchemaElement(s.items()));
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

    private ChatResponse parseResponse(String body, CallType callType, String sessionId, String model) {
        JSONObject resp = JSON.parseObject(body);
        if (resp.containsKey("error")) {
            JSONObject err = resp.getJSONObject("error");
            String msg = err != null ? err.getString("message") : "unknown error";
            throw new ModelUnavailableException("DeepSeek API error: " + msg);
        }
        if (tokenUsageRecorder != null) recordTokenUsage(resp, callType, sessionId);

        JSONArray choices = resp.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ModelUnavailableException("Empty choices from DeepSeek model " + model);
        }
        JSONObject first = choices.getJSONObject(0);
        JSONObject message = first.getJSONObject("message");
        if (message == null) {
            throw new ModelUnavailableException("No message in DeepSeek response, model=" + model);
        }

        String reasoning = message.getString("reasoning_content");
        if (StringUtils.hasText(reasoning) && log.isDebugEnabled()) {
            log.debug("DeepSeek reasoning(model={}): {}...", model, reasoning.substring(0, Math.min(100, reasoning.length())));
        }

        String content = message.getString("content");
        if (!StringUtils.hasText(content)) content = "";

        List<ToolExecutionRequest> toolCalls = new ArrayList<>();
        JSONArray toolCallsArr = message.getJSONArray("tool_calls");
        if (toolCallsArr != null && !toolCallsArr.isEmpty()) {
            for (int i = 0; i < toolCallsArr.size(); i++) {
                JSONObject tc = toolCallsArr.getJSONObject(i);
                JSONObject func = tc.getJSONObject("function");
                if (func == null) continue;
                toolCalls.add(ToolExecutionRequest.builder()
                        .id(tc.getString("id"))
                        .name(func.getString("name"))
                        .arguments(func.getString("arguments"))
                        .build());
            }
        }

        AiMessage aiMessage = toolCalls.isEmpty()
                ? AiMessage.aiMessage(content)
                : AiMessage.aiMessage(content, toolCalls);

        String finishStr = first.getString("finish_reason");
        FinishReason finish = "tool_calls".equals(finishStr) ? FinishReason.TOOL_EXECUTION
                : "length".equals(finishStr) ? FinishReason.LENGTH
                : FinishReason.STOP;

        return ChatResponse.builder()
                .aiMessage(aiMessage)
                .metadata(ChatResponseMetadata.builder()
                        .modelName(model)
                        .finishReason(finish)
                        .build())
                .build();
    }

    private void recordTokenUsage(JSONObject resp, CallType callType, String sessionId) {
        try {
            JSONObject usage = resp.getJSONObject("usage");
            if (usage == null) return;
            int prompt = usage.getIntValue("prompt_tokens", 0);
            int completion = usage.getIntValue("completion_tokens", 0);
            if (prompt + completion <= 0) return;
            tokenUsageRecorder.record(sessionId, modelName, callType, prompt, completion);
        } catch (Exception e) {
            log.warn("Failed to record token usage", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }

    private static class RetryableException extends IOException {
        RetryableException(String msg) { super(msg); }
        RetryableException(String msg, Throwable cause) { super(msg, cause); }
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String apiUrl;
        private String apiKey;
        private String modelName;
        private String mainModelName;
        private String reasoningModelName;
        private String cheapModelName;
        private boolean enableThinking;
        private String reasoningEffort;
        private TokenUsageRecorder tokenUsageRecorder;

        public Builder apiUrl(String apiUrl) { this.apiUrl = apiUrl; return this; }
        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder modelName(String modelName) { this.modelName = modelName; return this; }
        public Builder mainModelName(String n) { this.mainModelName = n; return this; }
        public Builder reasoningModelName(String n) { this.reasoningModelName = n; return this; }
        public Builder cheapModelName(String n) { this.cheapModelName = n; return this; }
        public Builder enableThinking(boolean v) { this.enableThinking = v; return this; }
        public Builder reasoningEffort(String v) { this.reasoningEffort = v; return this; }
        public Builder tokenUsageRecorder(TokenUsageRecorder r) { this.tokenUsageRecorder = r; return this; }

        public DeepSeekModel build() { return new DeepSeekModel(this); }
    }
}

package com.example.aiagentdemo.recorder;

import com.example.aiagentdemo.enums.CallType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token 用量记录器。按 sessionId + modelName + callType 维度聚合，
 */
@Slf4j
@Component
public class TokenUsageRecorder {
    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();

    public TokenUsageRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(String sessionId, String modelName, CallType callType,
                       int inputTokens, int outputTokens) {
        String safeSession = sessionId != null ? sessionId : "unknown";
        String key = safeSession + "|" + modelName + "|" + callType.name();

        Counter inputCounter = counterCache.computeIfAbsent(key + "|input", k ->
                Counter.builder("agent_llm_token_total")
                        .tag("session", safeSession)
                        .tag("model", modelName)
                        .tag("call_type", callType.name())
                        .tag("type", "input")
                        .register(meterRegistry));

        Counter outputCounter = counterCache.computeIfAbsent(key + "|output", k ->
                Counter.builder("agent_llm_token_total")
                        .tag("session", safeSession)
                        .tag("model", modelName)
                        .tag("call_type", callType.name())
                        .tag("type", "output")
                        .register(meterRegistry));

        inputCounter.increment(inputTokens);
        outputCounter.increment(outputTokens);

        log.debug("Token usage: session={}, model={}, callType={}, input={}, output={}",
                safeSession, modelName, callType, inputTokens, outputTokens);
    }
}

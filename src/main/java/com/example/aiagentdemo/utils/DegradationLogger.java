package com.example.aiagentdemo.utils;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 降级事件统一记录器。
 * 所有降级点调 log()，便于 Prometheus 统计和日志检索。
 */
@Slf4j
@Component
public class DegradationLogger {

    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();

    public DegradationLogger(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void log(String component, String reason) {
        String traceId = MDC.get("traceId");
        log.warn("[DEGRADED] component={}, reason={}, traceId={}", component, reason, traceId);

        String key = component + "|" + reason;
        Counter counter = counterCache.computeIfAbsent(key, k ->
                Counter.builder("agent_degradation_total")
                        .tag("component", component)
                        .tag("reason", reason)
                        .register(meterRegistry));
        counter.increment();
    }
}
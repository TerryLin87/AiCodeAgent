package com.example.aiagentdemo.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.Timer;

@Configuration
public class MetricsConfig {

    @Bean
    public Timer agentRequestTimer(MeterRegistry registry) {
        return Timer.builder("agent_request_duration_seconds")
                .description("Agent 请求耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }
}

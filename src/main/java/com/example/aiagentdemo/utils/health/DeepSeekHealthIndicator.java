package com.example.aiagentdemo.utils.health;

import com.example.aiagentdemo.enums.CallType;
import com.example.aiagentdemo.model.DeepSeekModel;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class DeepSeekHealthIndicator implements HealthIndicator {
    private final DeepSeekModel deepSeekModel;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "deepseek-health-check");
        t.setDaemon(true);
        return t;
    });

    public DeepSeekHealthIndicator(@Qualifier("DeepSeekModel") DeepSeekModel deepSeekModel) {
        this.deepSeekModel = deepSeekModel;
    }

    @Override
    public Health health() {
        try {
            Future<?> future = executor.submit(() -> {
                ChatRequest req = ChatRequest.builder()
                        .messages(List.of(UserMessage.from("ping")))
                        .build();
                // 使用 3 秒超时的 ROUTING 类型（token 消耗极小）
                deepSeekModel.doChat(req, CallType.ROUTING, "health-check");
            });
            future.get(5, TimeUnit.SECONDS);
            return Health.up().build();
        } catch (Exception e) {
            return Health.down().withDetail("reason", e.getMessage()).build();
        }
    }
}

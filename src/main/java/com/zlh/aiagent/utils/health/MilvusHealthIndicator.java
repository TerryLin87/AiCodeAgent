package com.zlh.aiagent.utils.health;

import com.zlh.aiagent.service.CodeRetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

@Slf4j
@Component
public class MilvusHealthIndicator implements HealthIndicator {
    private final CodeRetrievalService retrievalService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "milvus-health-check");
        t.setDaemon(true);
        return t;
    });

    public MilvusHealthIndicator(CodeRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Override
    public Health health() {
        try {
            Future<Long> future = executor.submit(retrievalService::getCollectionCount);
            long count = future.get(2, TimeUnit.SECONDS);
            if (count >= 0) {
                return Health.up().withDetail("collectionCount", count).build();
            }
            return Health.down().withDetail("reason", "getCollectionCount returned negative").build();
        } catch (TimeoutException e) {
            return Health.down().withDetail("reason", "Milvus query timeout (2s)").build();
        } catch (Exception e) {
            return Health.down().withDetail("reason", e.getMessage()).build();
        }
    }
}

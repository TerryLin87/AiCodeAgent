package com.zlh.aiagent.utils.health;

import com.zlh.aiagent.service.KeywordSearchService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public class LuceneHealthIndicator implements HealthIndicator {
    private final KeywordSearchService keywordSearchService;

    public LuceneHealthIndicator(KeywordSearchService keywordSearchService) {
        this.keywordSearchService = keywordSearchService;
    }

    @Override
    public Health health() {
        // KeywordSearchService 需要暴露一个 isIndexReady() 方法
        if (keywordSearchService.isIndexReady()) {
            return Health.up().withDetail("index", "ready").build();
        }
        return Health.down().withDetail("index", "not built").build();
    }
}

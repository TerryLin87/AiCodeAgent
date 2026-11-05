package com.zlh.aiagent.utils.health;

import com.zlh.aiagent.service.CallRelationService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.Map;

/**
 * 调用关系健康检查。
 *
 * <p>调用关系为 0 时 DOWN——说明索引流程没把 CallRelation 喂进来，
 * {@code findCallers} / {@code analyzeImpact} 全部失效，但系统表面"正常"。
 * 这是静默故障，必须用 health 暴露。
 */
public class CallRelationHealthIndicator implements HealthIndicator {

    private final CallRelationService callRelationService;

    public CallRelationHealthIndicator(CallRelationService callRelationService) {
        this.callRelationService = callRelationService;
    }

    @Override
    public Health health() {
        try {
            Map<String, Object> stats = callRelationService.getStats();
            Object raw = stats.get("totalRelations");
            long total = raw instanceof Number n ? n.longValue() : 0;

            if (total <= 0) {
                return Health.down()
                        .withDetail("reason", "调用关系为空，索引流程可能未加载 CallRelation")
                        .withDetail("totalRelations", 0)
                        .build();
            }
            return Health.up()
                    .withDetail("totalRelations", total)
                    .withDetail("calleeKeys", stats.get("calleeKeys"))
                    .withDetail("callerKeys", stats.get("callerKeys"))
                    .build();
        } catch (Exception e) {
            return Health.down().withDetail("reason", e.getMessage()).build();
        }
    }
}
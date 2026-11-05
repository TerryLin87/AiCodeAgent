package com.zlh.aiagent.utils.health;

import com.zlh.aiagent.service.SessionManager;
import com.zlh.aiagent.service.WorkingMemoryService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * 会话健康检查。
 *
 * <p>报告活跃会话数、工作记忆数，方便运维判断系统当前负载。
 * 不设阈值——多会话不是故障。
 */
public class AgentSessionHealthIndicator implements HealthIndicator {

    private final SessionManager sessionManager;
    private final WorkingMemoryService workingMemoryService;

    public AgentSessionHealthIndicator(SessionManager sessionManager,
                                       WorkingMemoryService workingMemoryService) {
        this.sessionManager = sessionManager;
        this.workingMemoryService = workingMemoryService;
    }

    @Override
    public Health health() {
        try {
            int activeSessions = sessionManager.getActiveSessionCount();
            int workingMemories = workingMemoryService.getAllSessionIds().size();

            return Health.up()
                    .withDetail("activeSessions", activeSessions)
                    .withDetail("workingMemories", workingMemories)
                    .build();
        } catch (Exception e) {
            return Health.down().withDetail("reason", e.getMessage()).build();
        }
    }
}
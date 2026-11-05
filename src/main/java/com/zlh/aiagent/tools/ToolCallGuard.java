package com.zlh.aiagent.tools;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具调用守卫。
 */
@Slf4j
@Component
public class ToolCallGuard {

    /** 默认单工具调用上限（可按工具名覆盖） */
    @Value("${agent.tool.max-calls-per-session:15}")
    private int defaultMaxCalls;

    /** 按工具名覆盖的限额，例如 readFile=5, searchCode=5, delegateToSubAgent=3 */
    @Value("#{${agent.tool.per-tool-limits:{'readFile':5,'searchCode':5,'delegateToSubAgent':3}}}")
    private Map<String, Integer> perToolLimits;

    /** key = sessionId + ":" + toolName */
    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build();

    private static final ThreadLocal<String> CURRENT_SESSION = new ThreadLocal<>();

    // ================================================================
    // 上下文传递
    // ================================================================

    public static void setCurrentSession(String sessionId) {
        CURRENT_SESSION.set(sessionId);
    }

    public static String getCurrentSession() {
        return CURRENT_SESSION.get();
    }

    public static void clearCurrentSession() {
        CURRENT_SESSION.remove();
    }

    // ================================================================
    // 计数
    // ================================================================

    /**
     * 递增计数，返回是否允许继续调用。
     *
     * @param sessionId 会话 ID
     * @return true 允许；false 已达上限
     */
    public boolean tryAcquire(String sessionId,String toolName) {
        if (sessionId == null) {
            return true;
        }

        int limit = perToolLimits.getOrDefault(toolName, defaultMaxCalls);
        String key = sessionId + ":" + toolName;

        AtomicInteger counter = counters.get(key, k -> new AtomicInteger(0));
        int current = counter.incrementAndGet();

        if (current > limit) {
            log.warn("会话 {} 工具 {} 调用已达上限 {}，拒绝继续调用", sessionId, toolName, limit);
            counter.decrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * 重置指定会话的计数（每轮新请求开始前调用）。
     */
    public void reset(String sessionId) {
        if (sessionId == null) return;
        counters.asMap().keySet().removeIf(k -> k.startsWith(sessionId + ":"));
    }

    /**
     * 清理已结束会话的计数。Caffeine 已自动过期，这里保留作为显式清理入口。
     */
    public void evict(Set<String> aliveSessionIds) {
        counters.asMap().keySet().removeIf(key -> {
            int idx = key.indexOf(':');
            if (idx < 0) return true;
            String sessionId = key.substring(0, idx);
            return !aliveSessionIds.contains(sessionId);
        });
    }

    /**
     * 清空所有计数（reset 应用时调用）。
     */
    public void clearAll() {
        counters.invalidateAll();
    }

    /**
     * 当前计数（调试用）。
     */
    public int currentCount(String sessionId,String toolName) {
        AtomicInteger c = counters.getIfPresent(sessionId + ":" + toolName);
        return c == null ? 0 : c.get();
    }

    // ================================================================
    // 统计（新增）
    // ================================================================

    /**
     * 当前被跟踪的会话数（调试用）。
     */
    public int getTrackedSessionCount() {
        return (int) counters.asMap().keySet().stream()
                .map(k -> k.substring(0, Math.max(0, k.indexOf(':'))))
                .distinct()
                .count();
    }
}
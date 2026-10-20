package com.example.aiagentdemo.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具调用守卫。
 */
@Slf4j
@Component
public class ToolCallGuard {

    /** 单次会话最多允许的工具调用次数 */
    @Value("${agent.tool.max-calls-per-session:15}")
    private int maxCallsPerSession;

    /** 每个会话的工具调用计数 */
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /** 当前线程正在处理的会话 ID（线程内共享，工具方法里读取） */
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
    public boolean tryAcquire(String sessionId) {
        if (sessionId == null) {
            return true;   // 拿不到会话 ID 时不阻塞
        }
        AtomicInteger counter = counters.computeIfAbsent(
                sessionId, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();
        if (count > maxCallsPerSession) {
            log.warn("会话 {} 工具调用已达上限 {}，拒绝继续调用", sessionId, maxCallsPerSession);
            return false;
        }
        return true;
    }

    /**
     * 重置指定会话的计数（每轮新请求开始前调用）。
     */
    public void reset(String sessionId) {
        if (sessionId != null) {
            counters.put(sessionId, new AtomicInteger(0));
        }
    }

    /**
     * 清理已结束会话的计数（定时任务）。
     */
    public void evict(Set<String> aliveSessionIds) {
        counters.keySet().removeIf(id -> !aliveSessionIds.contains(id));
    }

    /**
     * 清空所有计数（reset 应用时调用）。
     */
    public void clearAll() {
        counters.clear();
    }

    /**
     * 当前计数（调试用）。
     */
    public int currentCount(String sessionId) {
        AtomicInteger c = counters.get(sessionId);
        return c == null ? 0 : c.get();
    }

    // ================================================================
    // 统计（新增）
    // ================================================================

    /**
     * 当前被跟踪的会话数（调试用）。
     * 用于 evictStaleResources 日志，替代无意义的 currentCount("__dummy__")。
     */
    public int getTrackedSessionCount() {
        return counters.size();
    }
}
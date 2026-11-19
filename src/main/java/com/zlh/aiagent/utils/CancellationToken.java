package com.zlh.aiagent.utils;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 协作式请求取消令牌。
 *
 * <p>设计要点：
 * <ul>
 *   <li>不依赖 {@link Thread#interrupt()}，避免打断 OkHttp 连接池等第三方线程；
 *       由被调用方在 I/O 间隙主动 {@link #throwIfCancelled()}。</li>
 *   <li>每个 {@code requestId} 对应一个令牌，取消操作通过
 *       {@link #cancel(String)} 触发；令牌本身不持有强引用，方便 GC。</li>
 *   <li>支持超时自动过期，避免前端忘记调用 /cancel 导致令牌永久驻留。</li>
 * </ul>
 *
 * <p>使用方：HTTP/SSE 入口创建 → 透传到服务层、模型层、工具层；
 * 每段耗时循环（SSE 事件接收、工具执行循环、反思轮次）里调用一次
 * {@link #throwIfCancelled()} 即可。
 */
@Slf4j
public class CancellationToken {

    private final List<Runnable> cancelListeners = new ArrayList<>();

    private static final long DEFAULT_EXPIRE_MS = TimeUnit.MINUTES.toMillis(10);

    /** requestId -> 令牌（全局注册表，供 /cancel 接口用） */
    private static final Map<String, CancellationToken> REGISTRY = new ConcurrentHashMap<>();

    @Getter
    private final String requestId;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final long createdAt = System.currentTimeMillis();
    private final long expireAtMs;

    public CancellationToken(String requestId, long expireAfterMs) {
        this.requestId = requestId;
        this.expireAtMs = System.currentTimeMillis() + expireAfterMs;
    }

    // ============ 工厂 / 注册表 ============

    /** 创建一个默认 10 分钟过期的令牌并注册。 */
    public static CancellationToken create(String requestId) {
        return create(requestId, DEFAULT_EXPIRE_MS);
    }

    /** 创建一个自定义过期时间的令牌并注册。 */
    public static CancellationToken create(String requestId, long expireAfterMs) {
        cleanExpired();
        CancellationToken token = new CancellationToken(requestId, expireAfterMs);
        REGISTRY.put(requestId, token);
        return token;
    }

    /** 外部触发取消（由 /cancel 接口调用）。 */
    public static boolean cancel(String requestId) {
        CancellationToken token = REGISTRY.get(requestId);
        if (token == null) return false;
        token.cancel();
        return true;
    }

    /** 拿到令牌（不在注册表中的 id 返回 null）。 */
    public static CancellationToken get(String requestId) {
        return REGISTRY.get(requestId);
    }

    /** 用完（请求正常结束/异常结束）必须调用 {@link #dispose()} 释放注册表引用。 */
    public void dispose() {
        REGISTRY.remove(requestId);
    }

    // ============ 实例方法 ============

    public boolean isCancelled() {
        if (cancelled.get()) return true;
        if (System.currentTimeMillis() > expireAtMs) {
            cancelled.set(true);
            dispose();
            return true;
        }
        return false;
    }

    /** 在耗时点调用；已取消时抛 {@link CancelledException}，调用方在最外层统一捕获。 */
    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancelledException("请求已被取消: " + requestId);
        }
    }

    private static void cleanExpired() {
        if (REGISTRY.size() < 256) return;
        long now = System.currentTimeMillis();
        REGISTRY.entrySet().removeIf(e -> now > e.getValue().expireAtMs);
    }

    /** 标记请求被用户主动取消。 */
    public static final class CancelledException extends RuntimeException {
        public CancelledException(String msg) { super(msg); }
    }

    public void addCancelListener(Runnable listener) {
        if (isCancelled()) {
            listener.run();
            return;
        }
        cancelListeners.add(listener);
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable listener : cancelListeners) {
                try { listener.run(); } catch (Exception e) { log.warn("取消监听器执行失败", e); }
            }
        }
    }
}

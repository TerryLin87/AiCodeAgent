package com.zlh.aiagent.service;

import com.zlh.aiagent.data.AgentSession;
import com.zlh.aiagent.instances.ISessionStore;
import dev.langchain4j.data.message.ChatMessage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话管理器。
 * 职责：
 * 1. 创建、获取、更新、删除会话
 * 2. 自动清理过期会话
 * 3. 会话级别的记忆隔离
 * 4. 支持会话上下文持久化
 *
 * <p>并发控制：按 sessionId 分段加 {@link ReentrantLock}，只锁同一会话，避免全局锁。
 *
 * <p>功能分区：
 * <ul>
 *   <li>【A】依赖注入与字段</li>
 *   <li>【B】会话分段锁</li>
 *   <li>【C】会话创建与获取</li>
 *   <li>【D】消息读写</li>
 *   <li>【E】会话上下文管理</li>
 *   <li>【F】会话删除与批量清理</li>
 *   <li>【G】统计查询</li>
 *   <li>【H】过期会话定时清理</li>
 *   <li>【I】记忆替换与工具方法</li>
 * </ul>
 */
@Slf4j
@Component
public class SessionManager {

    //region 【A】依赖注入与字段

    private final ISessionStore sessionStore;

    /** 按 sessionId 分段锁，避免同一会话并发写冲突 */
    private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    @Getter
    private String systemPrompt = """
            你是一个智能的 Java 代码助手，具有调用工具和委托子 Agent 的能力。
            请根据用户需求，合理使用工具完成任务。
            """;

    public SessionManager(ISessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    //endregion

    //region 【B】会话分段锁

    /** 取（必要时创建）指定会话的锁，保证同一会话的操作串行。 */
    private ReentrantLock lockOf(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, k -> new ReentrantLock());
    }

    //endregion

    //region 【C】会话创建与获取

    /** 获取会话，不存在则创建（无 userId）。 */
    public AgentSession getOrCreateSession(String sessionId) {
        return getOrCreateSession(sessionId, null);
    }

    public AgentSession getOrCreateSession(String sessionId, String userId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        ReentrantLock lock = lockOf(sessionId);
        lock.lock();
        try {
            AgentSession session = sessionStore.get(sessionId);
            if (session == null) {
                session = createSession(sessionId, userId);
            }
            session.setLastAccessAt(LocalDateTime.now());
            if (session.getStatus() == AgentSession.SessionStatus.EXPIRED) {
                session.setStatus(AgentSession.SessionStatus.ACTIVE);
            }
            sessionStore.put(session);
            return session;
        } finally {
            lock.unlock();
        }
    }

    /** 新建会话对象（仅构造，不落库）。 */
    private AgentSession createSession(String sessionId, String userId) {
        AgentSession session = AgentSession.createNew(sessionId);
        session.setUserId(userId);
        log.info("创建会话: sessionId={}, userId={}", sessionId, userId);
        return session;
    }

    /** 新建会话并自动生成 sessionId（无 userId）。 */
    public AgentSession createNewSession() {
        return createNewSession(null);
    }

    public AgentSession createNewSession(String userId) {
        String sessionId = generateSessionId();
        AgentSession session = createSession(sessionId, userId);
        sessionStore.put(session);
        return session;
    }

    /** 查询会话，不存在返回 null。 */
    public AgentSession getSession(String sessionId) {
        return sessionStore.get(sessionId);
    }

    //endregion

    //region 【D】消息读写

    /** 追加一条用户消息（加锁）。 */
    public void addUserMessage(String sessionId, String content) {
        AgentSession session = getOrCreateSession(sessionId);
        ReentrantLock lock = lockOf(sessionId);
        lock.lock();
        try {
            session.addMessage(dev.langchain4j.data.message.UserMessage.from(content));
            sessionStore.put(session);
        } finally {
            lock.unlock();
        }
    }

    /** 追加一条助手消息（加锁；会话不存在则忽略）。 */
    public void addAssistantMessage(String sessionId, String content) {
        AgentSession session = sessionStore.get(sessionId);
        if (session == null) return;
        ReentrantLock lock = lockOf(sessionId);
        lock.lock();
        try {
            session.addMessage(dev.langchain4j.data.message.AiMessage.from(content));
            sessionStore.put(session);
        } finally {
            lock.unlock();
        }
    }

    /** 获取会话的全部消息。 */
    public List<ChatMessage> getMessages(String sessionId) {
        AgentSession session = sessionStore.get(sessionId);
        return session == null ? List.of() : session.getMessages();
    }

    /** 获取最近 n 条消息。 */
    public List<ChatMessage> getRecentMessages(String sessionId, int n) {
        AgentSession session = sessionStore.get(sessionId);
        return session == null ? List.of() : session.getRecentMessages(n);
    }

    //endregion

    //region 【E】会话上下文管理

    /** 写入一个上下文键值对并落库。 */
    public void updateContext(String sessionId, String key, Object value) {
        AgentSession session = sessionStore.get(sessionId);
        if (session != null) {
            session.updateContext(key, value);
            sessionStore.put(session);
        }
    }

    /** 获取会话上下文，不存在返回空 Map。 */
    public Map<String, Object> getContext(String sessionId) {
        AgentSession session = sessionStore.get(sessionId);
        return session == null ? Map.of() : session.getContext();
    }

    //endregion

    //region 【F】会话删除与批量清理

    /** 删除会话，同时移除其分段锁。 */
    public void deleteSession(String sessionId) {
        ReentrantLock lock = lockOf(sessionId);
        lock.lock();
        try {
            sessionStore.remove(sessionId);
            sessionLocks.remove(sessionId);
            log.info("删除会话: sessionId={}", sessionId);
        } finally {
            lock.unlock();
        }
    }

    /** 清空所有会话与锁。 */
    public void clearAllSessions() {
        int count = sessionStore.size();
        sessionStore.clearAll();
        sessionLocks.clear();
        log.info("清空所有会话，共 {}", count);
    }

    //endregion

    //region 【G】统计查询

    /** 当前会话数。 */
    public int getActiveSessionCount() {
        return sessionStore.size();
    }

    /** 会话总数。 */
    public int getTotalSessionCount() {
        return sessionStore.size();
    }

    /** 所有会话 ID 快照。 */
    public List<String> getAllSessionIds() {
        return List.copyOf(sessionStore.getAllSessionIds());
    }

    //endregion

    //region 【H】过期会话定时清理

    /** 定时（每 5 分钟）清理过期会话。 */
    @Scheduled(fixedDelay = 300000)
    public void cleanExpiredSessions() {
        List<String> expired = sessionStore.getAllSessionIds().stream()
                .filter(id -> {
                    AgentSession s = sessionStore.get(id);
                    return s != null && s.isExpired();
                })
                .toList();
        if (!expired.isEmpty()) {
            expired.forEach(this::deleteSession);
            log.info("清理过期会话: {} 个", expired.size());
        }
    }

    //endregion

    //region 【I】记忆替换与工具方法

    /**
     * 替换指定会话的 ChatMemory（用于压缩后更新上下文）。
     */
    public void replaceSessionMemory(String sessionId, List<ChatMessage> messages) {
        AgentSession session = sessionStore.get(sessionId);
        if (session != null) {
            ReentrantLock lock = lockOf(sessionId);
            lock.lock();
            try {
                session.replaceChatMemory(messages);
                sessionStore.put(session);
                log.info("会话 {} 的 ChatMemory 已被压缩替换，新消息数: {}", sessionId, messages != null ? messages.size() : 0);
            } finally {
                lock.unlock();
            }
        }
    }

    /** 生成 sessionId：session- + UUID 前 8 位。 */
    private String generateSessionId() {
        return "session-" + UUID.randomUUID().toString().substring(0, 8);
    }

    //endregion
}

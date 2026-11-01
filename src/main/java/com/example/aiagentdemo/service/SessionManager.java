package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.AgentSession;
import com.example.aiagentdemo.instances.ISessionStore;
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
 */
@Slf4j
@Component
public class SessionManager {

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

    private ReentrantLock lockOf(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, k -> new ReentrantLock());
    }

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

    private AgentSession createSession(String sessionId, String userId) {
        AgentSession session = AgentSession.createNew(sessionId);
        session.setUserId(userId);
        log.info("创建会话: sessionId={}, userId={}", sessionId, userId);
        return session;
    }

    public AgentSession createNewSession() {
        return createNewSession(null);
    }

    public AgentSession createNewSession(String userId) {
        String sessionId = generateSessionId();
        AgentSession session = createSession(sessionId, userId);
        sessionStore.put(session);
        return session;
    }

    public AgentSession getSession(String sessionId) {
        return sessionStore.get(sessionId);
    }

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

    public List<ChatMessage> getMessages(String sessionId) {
        AgentSession session = sessionStore.get(sessionId);
        return session == null ? List.of() : session.getMessages();
    }

    public List<ChatMessage> getRecentMessages(String sessionId, int n) {
        AgentSession session = sessionStore.get(sessionId);
        return session == null ? List.of() : session.getRecentMessages(n);
    }

    public void updateContext(String sessionId, String key, Object value) {
        AgentSession session = sessionStore.get(sessionId);
        if (session != null) {
            session.updateContext(key, value);
            sessionStore.put(session);
        }
    }

    public Map<String, Object> getContext(String sessionId) {
        AgentSession session = sessionStore.get(sessionId);
        return session == null ? Map.of() : session.getContext();
    }

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

    public void clearAllSessions() {
        int count = sessionStore.size();
        sessionStore.clearAll();
        sessionLocks.clear();
        log.info("清空所有会话，共 {}", count);
    }

    public int getActiveSessionCount() {
        return sessionStore.size();
    }

    public int getTotalSessionCount() {
        return sessionStore.size();
    }

    public List<String> getAllSessionIds() {
        return List.copyOf(sessionStore.getAllSessionIds());
    }

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

    private String generateSessionId() {
        return "session-" + UUID.randomUUID().toString().substring(0, 8);
    }

}

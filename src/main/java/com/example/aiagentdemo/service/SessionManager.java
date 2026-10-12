package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.AgentSession;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器
 * 职责：
 * 1. 创建、获取、更新、删除会话
 * 2. 自动清理过期会话
 * 3. 会话级别的记忆隔离
 * 4. 支持会话上下文持久化
 */
@Slf4j
@Component
public class SessionManager {

    /**
     * 内存存储（生产环境可替换为 Redis）
     */
    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    /**
     * 系统提示词（所有会话共用）
     */
    private String systemPrompt = """
            你是一个智能的 Java 代码助手，具有调用工具和委托子 Agent 的能力。
            
            请根据用户需求，合理使用工具完成任务。
            如果任务复杂，可以分解为多个步骤逐步完成。
            如果信息不足，先通过工具获取信息再回答。
            """;

    //region 核心方法

    /**
     * 获取或创建会话（推荐入口）
     */
    public AgentSession getOrCreateSession(String sessionId) {
        return getOrCreateSession(sessionId, null);
    }

    /**
     * 获取或创建会话（带用户ID）
     */
    public AgentSession getOrCreateSession(String sessionId, String userId) {
        AgentSession session = sessions.get(sessionId);

        if (session == null) {
            log.info("会话不存在，创建新会话: sessionId={}", sessionId);
            session = createSession(sessionId, userId);
        }

        session.setLastAccessAt(LocalDateTime.now());

        if (session.getStatus() == AgentSession.SessionStatus.EXPIRED) {
            log.warn("会话已过期，重新激活：sessionId={}", sessionId);
            session.setStatus(AgentSession.SessionStatus.ACTIVE);
        }

        return session;
    }

    /**
     * 创建新会话（内部使用）
     */
    private AgentSession createSession(String sessionId, String userId) {
        AgentSession session = AgentSession.createNew(sessionId);
        session.setUserId(userId);
//        session.addMessage(SystemMessage.from(systemPrompt));
        sessions.put(sessionId, session);
        log.info("创建会话: sessionId={}, userId={}", sessionId, userId);
        return session;
    }

    /**
     * 创建新会话（外部入口，自动生成ID）
     */
    public AgentSession createNewSession() {
        return createNewSession(null);
    }

    /**
     * 创建新会话（外部入口，自动生成ID，带用户ID）
     */
    public AgentSession createNewSession(String userId) {
        String sessionId = generateSessionId();
        return createSession(sessionId, userId);
    }

    /**
     * 获取会话（只读，不更新访问时间）
     */
    public AgentSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    // ==================== 消息操作 ====================

    public void addUserMessage(String sessionId, String content) {
        AgentSession session = getOrCreateSession(sessionId);
        session.addMessage(dev.langchain4j.data.message.UserMessage.from(content));
    }

    public void addAssistantMessage(String sessionId, String content) {
        AgentSession session = getSession(sessionId);
        if (session != null) {
            session.addMessage(dev.langchain4j.data.message.AiMessage.from(content));
        }
    }

    /**
     * 获取会话的所有消息
     */
    public List<ChatMessage> getMessages(String sessionId) {
        AgentSession session = getSession(sessionId);
        if (session == null) {
            return List.of();
        }
        return session.getMessages();
    }

    /**
     * 获取最近的 N 条消息
     */
    public List<ChatMessage> getRecentMessages(String sessionId, int n) {
        AgentSession session = getSession(sessionId);
        if (session == null) {
            return List.of();
        }
        return session.getRecentMessages(n);
    }

    /**
     * 更新会话上下文
     */
    public void updateContext(String sessionId, String key, Object value) {
        AgentSession session = getSession(sessionId);
        if (session != null) {
            session.updateContext(key, value);
        }
    }

    /**
     * 获取会话上下文
     */
    public Map<String, Object> getContext(String sessionId) {
        AgentSession session = getSession(sessionId);
        if (session == null) {
            return Map.of();
        }
        return session.getContext();
    }

    /**
     * 删除会话
     */
    public void deleteSession(String sessionId) {
        AgentSession removed = sessions.remove(sessionId);
        if (removed != null) {
            log.info("🗑️ 删除会话: sessionId={}", sessionId);
        }
    }

    /**
     * 清理所有会话（清空）
     */
    public void clearAllSessions() {
        int count = sessions.size();
        sessions.clear();
        log.info("🗑️ 清空所有会话，共 {}", count);
    }

    //endregion

    //region 统计方法

    /**
     * 获取活跃会话数
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * 获取会话数
     */
    public int getTotalSessionCount() {
        return sessions.size();
    }

    /**
     * 获取所有会话 ID
     */
    public List<String> getAllSessionIds() {
        return List.copyOf(sessions.keySet());
    }

    //endregion

    //region 后台清理
    /**
     * 定时清理过期会话（每 5 分钟执行一次）
     */
    @Scheduled(fixedDelay = 300000)
    public void cleanExpiredSessions() {
        List<String> expiredIds = sessions.entrySet().stream()
                .filter(entry -> entry.getValue().isExpired())
                .map(Map.Entry::getKey)
                .toList();

        if (!expiredIds.isEmpty()) {
            expiredIds.forEach(sessions::remove);
            log.info("🧹 清理过期会话: {} 个", expiredIds.size());
        }
    }
    //endregion

    //region 辅助方法

    private String generateSessionId() {
        return "session-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 设置系统提示词
     */
    public void setSystemPrompt(String prompt) {
        this.systemPrompt = prompt;
        log.info("更新系统提示词");
    }

    //endregion
}

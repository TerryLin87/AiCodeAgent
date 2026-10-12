package com.example.aiagentdemo.data;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 会话
 * 包含一次对话会话的所有状态
 */
@Data
@Builder
public class AgentSession {
    /**
     * 会话唯一标识
     */
    private String sessionId;

    /**
     * 用户 ID（可选，用于用户隔离）
     */
    private String userId;

    /**
     * 会话创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 最后访问时间（用于过期清理）
     */
    private LocalDateTime lastAccessAt;

    /**
     * 会话状态
     */
    private SessionStatus status;

    /**
     * 直接持有ChatMemory
     */
    private ChatMemory chatMemory;

    /**
     * 工作记忆（当前任务的中间状态）
     */
    private WorkingMemory workingMemory;

    /**
     * 会话上下文（项目名、业务偏好等）
     */
    private Map<String, Object> context;

    /**
     * 当前执行状态
     */
    private ExecutionState executionState;

    public enum SessionStatus {
        ACTIVE,      // 活跃
        EXPIRED,     // 已过期
        COMPLETED,   // 已完成
        ERROR        // 异常
    }

    /**
     * 执行状态
     */
    public static class ExecutionState {
        private String currentPhase;        // planning/executing/observing/reflecting
        private int iteration;              // 当前迭代次数
        private String lastToolCall;        // 上次调用的工具
        private String lastToolResult;      // 上次工具调用的结果
        private List<String> errorHistory;  // 错误历史
    }

    /**
     * 添加消息
     */
    public void addMessage(ChatMessage message) {
        if (this.chatMemory == null) {
            this.chatMemory = MessageWindowChatMemory.builder()
                    .maxMessages(50)
                    .build();
        }
        this.chatMemory.add(message);
        this.lastAccessAt = LocalDateTime.now();
    }

    /**
     * 获取所有消息
     */
    public List<ChatMessage> getMessages(){
        if(this.chatMemory == null){
            return new ArrayList<>();
        }
        return this.chatMemory.messages();
    }

    /**
     * 获取最近 N 条消息
     */
    public List<ChatMessage> getRecentMessages(int n) {
        if (this.chatMemory == null || this.chatMemory.messages().isEmpty()) {
            return new ArrayList<>();
        }
        List<ChatMessage> all = this.chatMemory.messages();
        int start = Math.max(0, all.size() - n);
        return new ArrayList<>(all.subList(start, all.size()));
    }

    /**
     * 获取 ChatMemory（懒加载）
     */
    public ChatMemory getChatMemory() {
        if (this.chatMemory == null) {
            this.chatMemory = MessageWindowChatMemory.builder()
                    .maxMessages(50)
                    .build();
        }
        return this.chatMemory;
    }

    /**
     * 更新上下文
     */
    public void updateContext(String key, Object value) {
        if (this.context == null) {
            this.context = new ConcurrentHashMap<>();
        }
        this.context.put(key, value);
        this.lastAccessAt = LocalDateTime.now();
    }

    /**
     * 判断会话是否过期（超过30分钟无操作）
     */
    public boolean isExpired() {
        if (lastAccessAt == null) {
            return false;
        }
        return LocalDateTime.now().minusMinutes(30).isAfter(lastAccessAt);
    }

    /**
     * 判断会话是否达到最大消息数（防止内存溢出）
     */
    public boolean isFull() {
        if (this.chatMemory == null) {
            return false;
        }
        return this.chatMemory.messages().size() > 200;
    }

    /**
     * 工厂方法
     * @param sessionId
     */
    public static AgentSession createNew(String sessionId) {
        return AgentSession.builder()
                .sessionId(sessionId)
                .createdAt(LocalDateTime.now())
                .lastAccessAt(LocalDateTime.now())
                .status(SessionStatus.ACTIVE)
                .chatMemory(MessageWindowChatMemory.builder()
                        .maxMessages(50)
                        .build())
                .context(new ConcurrentHashMap<>())
                .build();
    }
}

package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.*;
import com.example.aiagentdemo.enums.AgentStatus;
import com.example.aiagentdemo.model.DeepSeekModel;
import com.example.aiagentdemo.service.subagent.ContextCompressor;
import com.example.aiagentdemo.tools.AgentTools;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CodeAgentLoopService {
    // 全局并发限制：最多 10 个请求同时执行
    private final Semaphore globalSemaphore = new Semaphore(10);

    // 单会话并发限制：同一会话同时只能 1 个请求
    private final Map<String, Semaphore> sessionSemaphores = new ConcurrentHashMap<>();

    @Autowired
    @Qualifier("DeepSeekModel")
    private DeepSeekModel deepSeekModel;

    @Autowired
    private LongTermMemoryService longTermMemoryService;

    @Autowired
    private AgentTools agentTools;

    @Autowired
    private ContextCompressor contextCompressor;

    @Autowired
    private SessionManager sessionManager;

    // ================================================================
    // Assistant 接口（System Prompt 精简，去掉 SUB_AGENT 格式）
    // ================================================================

    public interface CodingAssistant {
        @SystemMessage("""
                你是一个智能的 Java 代码助手，可以调用工具完成代码检索、分析和验证任务。

                ================================================================
                你的能力：
                1. 调用工具直接完成简单任务（搜索代码、读文件、查调用关系）
                2. 委托子 Agent 完成复杂任务（深度探索、影响分析、质量验证）
                ================================================================

                可用工具：
                - searchCode(query)                     搜索代码
                - readFile(path)                        读取文件
                - getProjectStructure(projectName)      查看项目结构
                - getClassFields(className)             查看类字段
                - getCallers(className, methodName)     查询调用者
                - delegateToSubAgent(agentName, task)   委托子 Agent

                可用的子 Agent：
                - explore: 代码探索专家（搜索、读文件、看结构、分析调用）
                - verify:  验证专家（检查代码质量、验证正确性）

                ================================================================
                工作原则：
                1. 先判断任务类型：
                   - 简单查询（"找 XX 类"、"XX 有哪些方法"）→ 直接调工具
                   - 复杂任务（"分析影响范围"、"探索整个模块"、"验证代码质量"）→ 委托子 Agent
                2. 调用工具后，根据返回结果决定下一步
                3. 收集到足够信息后，给出最终答案
                4. 不要编造代码信息，一切以工具返回为准
                ================================================================
                """)
        String chat(@UserMessage String message);
    }

    // ================================================================
    // 主入口
    // ================================================================

    public String execute(String userMessage) throws InterruptedException {
        String sessionId = UUID.randomUUID().toString();
        return execute(sessionId, userMessage);
    }

    public String execute(String sessionId, String userMessage) throws InterruptedException {
        // 全局限流
        if (!globalSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
            return "系统繁忙，请稍后重试";
        }
        try {
            // 会话级限流
            Semaphore sessionSem = sessionSemaphores.computeIfAbsent(
                    sessionId, k -> new Semaphore(1));
            if (!sessionSem.tryAcquire(3, TimeUnit.SECONDS)) {
                return "当前会话有请求正在处理，请稍后";
            }
            try {
                return doExecute(sessionId, userMessage);
            } finally {
                sessionSem.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "请求被中断";
        } finally {
            globalSemaphore.release();
        }
    }

    public String doExecute(String sessionId, String userMessage) throws InterruptedException {
        log.info("Agent 开始执行: session={}, message={}", sessionId, userMessage);
        long startTime = System.currentTimeMillis();

        try {
            // ===== 1. 获取或创建会话 =====
            AgentSession session = sessionManager.getOrCreateSession(sessionId);
            log.info("会话状态: 消息数={}", session.getMessages().size());

            // ===== 2. 构建 ChatMemory（从会话恢复历史） =====
            ChatMemory sessionMemory = buildChatMemory(session);

            // ===== 3. 检查是否需要上下文压缩 =====
            if (contextCompressor.needsCompression(sessionMemory.messages())) {
                log.info("触发上下文压缩");
                List<ChatMessage> compressed = contextCompressor.compress(sessionMemory.messages());
                sessionMemory.clear();
                for (ChatMessage msg : compressed) {
                    sessionMemory.add(msg);
                }
                log.info("上下文压缩完成，压缩后消息数: {}", compressed.size());
            }

            // ===== 4. 检索长期记忆（跨会话） =====
            List<MemoryDocument> relevantMemories = longTermMemoryService
                    .retrieveRelevantMemories(userMessage, 3);
            String memoryContext = formatMemoryContext(relevantMemories);

            // ===== 5. 构建最终消息（融合记忆） =====
            String finalMessage = memoryContext.isEmpty()
                    ? userMessage
                    : buildEnhancedMessage(userMessage, memoryContext);

            // ===== 6. 构建会话独立的 Assistant =====
            CodingAssistant sessionAssistant = AiServices.builder(CodingAssistant.class)
                    .chatModel(deepSeekModel)
                    .chatMemory(sessionMemory)
                    .tools(agentTools)   // ← 工具注册
                    .build();

            // ===== 7. 调用 Agent（LLM 自主决定调什么工具） =====
            String response = sessionAssistant.chat(finalMessage);

            // ===== 8. 存储到会话和长期记忆 =====
            sessionManager.addUserMessage(sessionId, userMessage);
            sessionManager.addAssistantMessage(sessionId, response);
            longTermMemoryService.storeMemory(sessionId, "user", userMessage);
            longTermMemoryService.storeMemory(sessionId, "assistant", response);

            long endTime = System.currentTimeMillis();
            log.info("Agent 完成, 耗时: {}ms, 响应长度: {} 字符",
                    endTime - startTime, response.length());

            return response;

        } catch (Exception e) {
            log.error("Agent 执行异常", e);
            return "Agent 执行失败: " + e.getMessage() + "\n\n" +
                    "建议: " + getRecoverySuggestion(e);
        }
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    /**
     * 从会话构建 ChatMemory
     */
    private ChatMemory buildChatMemory(AgentSession session) {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(50)
                .build();

        for (ChatMessage msg : session.getMessages()) {
            memory.add(msg);
        }
        return memory;
    }

    /**
     * 构建增强消息（融合长期记忆）
     */
    private String buildEnhancedMessage(String userMessage, String memoryContext) {
        return String.format("""
                【相关历史记录】
                %s

                【当前问题】
                %s
                """, memoryContext, userMessage);
    }

    /**
     * 格式化长期记忆上下文
     */
    private String formatMemoryContext(List<MemoryDocument> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }

        return memories.stream()
                .map(m -> String.format("[%s] %s: %s",
                        m.getTimestamp() != null ? m.getTimestamp().toLocalTime().toString() : "未知",
                        "user".equals(m.getRole()) ? "用户" : "助手",
                        m.getContent().length() > 300
                                ? m.getContent().substring(0, 300) + "..."
                                : m.getContent()))
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 恢复建议
     */
    private String getRecoverySuggestion(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return "请检查系统状态后重试";
        }
        if (message.contains("timeout") || message.contains("超时")) {
            return "请求超时，建议简化任务或稍后重试";
        }
        if (message.contains("permission") || message.contains("权限")) {
            return "权限不足，请检查文件或服务权限";
        }
        if (message.contains("not found") || message.contains("未找到")) {
            return "未找到相关信息，请检查关键词是否准确";
        }
        return "请检查日志后重试";
    }

    // ================================================================
    // 状态查询（保留）
    // ================================================================

    public AgentStatus getStatus() {
        return AgentStatus.IDLE;
    }

    public void reset() {
        sessionManager.clearAllSessions();
        log.info("Agent 已重置");
    }

    public int getActiveSessionCount() {
        return sessionManager.getActiveSessionCount();
    }
}
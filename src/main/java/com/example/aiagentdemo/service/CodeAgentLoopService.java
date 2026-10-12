package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.AgentSession;
import com.example.aiagentdemo.data.MemoryDocument;
import com.example.aiagentdemo.enums.AgentStatus;
import com.example.aiagentdemo.model.DeepSeekModel;
import com.example.aiagentdemo.service.subagent.ContextCompressor;
import com.example.aiagentdemo.tools.AgentTools;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Agent 主循环。
 */
@Slf4j
@Service
public class CodeAgentLoopService {

    // 全局并发限制：最多 10 个请求同时执行
    private final Semaphore globalSemaphore = new Semaphore(10);

    // 单会话并发限制：同一会话同时只能 1 个请求
    private final Map<String, Semaphore> sessionSemaphores = new ConcurrentHashMap<>();

    // 会话级 ChatMemory 缓存（唯一真相源：所有会话消息都存在这里）
    private final Map<String, ChatMemory> memoryCache = new ConcurrentHashMap<>();

    // 本轮会话的长期记忆上下文，供 systemMessageProvider 读取后即删
    private final Map<String, String> pendingMemoryContext = new ConcurrentHashMap<>();

    // 每个会话保留的最大消息数
    private static final int MEMORY_WINDOW = 50;

    // 记忆缓存硬上限，超过则触发一次空闲回收
    private static final int MAX_CACHED_MEMORIES = 1000;

    private static final String BASE_SYSTEM_PROMPT = """
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
            """;

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

    // 全程只构建一次的 Assistant 代理
    private CodingAssistant assistant;

    // ================================================================
    // Assistant 接口
    // 注意：不再使用 @SystemMessage 注解，系统提示词改由 systemMessageProvider 动态提供
    // （这样才能把"长期记忆"注入 System Prompt，而不是塞进用户消息）。
    // ================================================================

    public interface CodingAssistant {
        String chat(@MemoryId String sessionId, @UserMessage String message);
    }

    @PostConstruct
    public void init() {
        this.assistant = AiServices.builder(CodingAssistant.class)
                .chatModel(deepSeekModel)
                .chatMemoryProvider(memoryId -> memoryOf((String) memoryId))
                .systemMessageProvider(memoryId -> buildSystemPrompt((String) memoryId))
                .tools(agentTools)
                .build();
        log.info("CodingAssistant 初始化完成");
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
            Semaphore sessionSem = sessionSemaphores.computeIfAbsent(sessionId, k -> new Semaphore(1));
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

    public String doExecute(String sessionId, String userMessage) {
        log.info("Agent 开始执行: session={}, message={}", sessionId, userMessage);
        long startTime = System.currentTimeMillis();

        try {
            // ===== 1. 确保会话存在（作为审计镜像） =====
            AgentSession session = sessionManager.getOrCreateSession(sessionId);
            log.info("会话状态: 消息数={}", session.getMessages().size());

            // ===== 2. 取会话级 ChatMemory（唯一真相源，跨轮复用） =====
            ChatMemory memory = memoryOf(sessionId);

            // ===== 3. 压缩：原地生效，结果不再丢失 =====
            if (contextCompressor.needsCompression(memory.messages())) {
                log.info("触发上下文压缩，压缩前消息数: {}", memory.messages().size());
                List<ChatMessage> compressed = contextCompressor.compress(memory.messages());
                replaceMemory(memory, compressed);
                log.info("上下文压缩完成，压缩后消息数: {}", compressed.size());
            }

            // ===== 4. 检索长期记忆（跨会话），暂存供 System Prompt 使用 =====
            List<MemoryDocument> relevantMemories =
                    longTermMemoryService.retrieveRelevantMemories(userMessage, 3);
            pendingMemoryContext.put(sessionId, formatMemoryContext(relevantMemories));

            // ===== 5. 调用 Agent（用户消息保持原样，不拼记忆） =====
            String response = assistant.chat(sessionId, userMessage);

            // ===== 6. 审计镜像 + 长期记忆 =====
            sessionManager.addUserMessage(sessionId, userMessage);
            sessionManager.addAssistantMessage(sessionId, response);
            longTermMemoryService.storeMemory(sessionId, "user", userMessage);
            longTermMemoryService.storeMemory(sessionId, "assistant", response);

            log.info("Agent 完成, 耗时: {}ms, 响应长度: {} 字符",
                    System.currentTimeMillis() - startTime, response.length());
            return response;

        } catch (Exception e) {
            log.error("Agent 执行异常", e);
            return "Agent 执行失败: " + e.getMessage() + "\n\n建议: " + getRecoverySuggestion(e);
        } finally {
            pendingMemoryContext.remove(sessionId);
        }
    }

    // ================================================================
    // 记忆 / 提示词
    // ================================================================

    /**
     * 获取（或创建）会话级 ChatMemory。
     */
    private ChatMemory memoryOf(String sessionId) {
        ChatMemory cached = memoryCache.get(sessionId);
        if (cached != null) {
            return cached;
        }
        if (memoryCache.size() >= MAX_CACHED_MEMORIES) {
            evictStaleResources();
        }
        return memoryCache.computeIfAbsent(sessionId, id -> MessageWindowChatMemory.builder()
                .id(id)
                .maxMessages(MEMORY_WINDOW)
                .build());
    }

    /**
     * 用给定消息整体替换记忆内容。
     */
    private void replaceMemory(ChatMemory memory, List<ChatMessage> messages) {
        memory.clear();
        if (messages != null) {
            for (ChatMessage msg : messages) {
                memory.add(msg);
            }
        }
    }

    /**
     * 构建系统提示词：基础提示 + 本轮召回的长期记忆。
     */
    private String buildSystemPrompt(String sessionId) {
        String memoryContext = pendingMemoryContext.get(sessionId);
        if (memoryContext == null || memoryContext.isEmpty()) {
            return BASE_SYSTEM_PROMPT;
        }
        return BASE_SYSTEM_PROMPT
                + "\n\n【相关历史记录（供参考，非本轮用户输入）】\n"
                + memoryContext;
    }

    /**
     * 回收已过期/已删除会话占用的信号量与记忆缓存，防止无界增长。
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void evictStaleResources() {
        Set<String> alive = new HashSet<>(sessionManager.getAllSessionIds());

        // 记忆缓存：直接移除已不存在的会话
        memoryCache.keySet().removeIf(id -> !alive.contains(id));
        pendingMemoryContext.keySet().removeIf(id -> !alive.contains(id));

        // 信号量：仅当"当前无人持锁"时才移除，避免并发下出现两个信号量实例
        sessionSemaphores.entrySet().removeIf(entry -> {
            if (alive.contains(entry.getKey())) {
                return false;
            }
            Semaphore sem = entry.getValue();
            if (sem.tryAcquire()) {
                sem.release();
                return true;
            }
            return false;
        });

        log.debug("资源回收完成: memories={}, sessionSemaphores={}",
                memoryCache.size(), sessionSemaphores.size());
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    /**
     * 格式化长期记忆上下文。
     */
    private String formatMemoryContext(List<MemoryDocument> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }
        return memories.stream()
                .map(m -> String.format("[%s] %s: %s",
                        m.getTimestamp() != null ? m.getTimestamp().toLocalTime().toString() : "未知",
                        "user".equals(m.getRole()) ? "用户" : "助手",
                        m.getContent() != null && m.getContent().length() > 300
                                ? m.getContent().substring(0, 300) + "..."
                                : m.getContent()))
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 恢复建议。
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
    // 状态查询
    // ================================================================

    public AgentStatus getStatus() {
        return AgentStatus.IDLE;
    }

    public void reset() {
        sessionManager.clearAllSessions();
        memoryCache.clear();
        pendingMemoryContext.clear();
        sessionSemaphores.clear();
        log.info("Agent 已重置");
    }

    public int getActiveSessionCount() {
        return sessionManager.getActiveSessionCount();
    }
}

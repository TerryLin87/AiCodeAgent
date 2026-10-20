package com.example.aiagentdemo.service;

import com.example.aiagentdemo.config.DomainConfig;
import com.example.aiagentdemo.data.AgentSession;
import com.example.aiagentdemo.data.EvaluationResult;
import com.example.aiagentdemo.data.MemoryDocument;
import com.example.aiagentdemo.enums.AgentStatus;
import com.example.aiagentdemo.instances.IToolPlugin;
import com.example.aiagentdemo.model.DeepSeekModel;
import com.example.aiagentdemo.service.subagent.ContextCompressor;
import com.example.aiagentdemo.service.subagent.PlanSubAgent;
import com.example.aiagentdemo.tools.ToolCallGuard;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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

@Slf4j
@Service
public class CodeAgentLoopService {

    private final Semaphore globalSemaphore = new Semaphore(10);
    private final Map<String, Semaphore> sessionSemaphores = new ConcurrentHashMap<>();
    private final Map<String, ChatMemory> memoryCache = new ConcurrentHashMap<>();
    private final Map<String, String> pendingMemoryContext = new ConcurrentHashMap<>();
    private final Map<String, String> pendingPlanContext = new ConcurrentHashMap<>();

    private static final int MEMORY_WINDOW = 50;
    private static final int MAX_CACHED_MEMORIES = 1000;

    // ================================================================
    // 依赖注入
    // ================================================================

    @Autowired
    @Qualifier("DeepSeekModel")
    private DeepSeekModel deepSeekModel;

    @Autowired
    private LongTermMemoryService longTermMemoryService;

    @Autowired
    private ContextCompressor contextCompressor;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private PlanSubAgent planSubAgent;

    @Autowired
    private SubAgentRoutingService subAgentRoutingService;

    @Autowired
    private SelfEvaluationService evaluationService;

    @Autowired
    private AutoFixerService autoFixerService;

    @Autowired
    private ToolCallGuard toolCallGuard;

    @Autowired
    private List<IToolPlugin> toolPlugins;

    @Autowired
    private DomainConfig domainConfig;

    /** MCP 工具提供者（可选：MCP Server 没启动时为 null） */
    @Autowired(required = false)
    private McpToolProvider mcpToolProvider;

    // ================================================================
    // 配置项
    // ================================================================

    @Value("${agent.reflexion.enabled:true}")
    private boolean reflexionEnabled;

    @Value("${agent.reflexion.score-threshold:7.0}")
    private double scoreThreshold;

    @Value("${agent.reflexion.max-duration-ms:30000}")
    private long reflexionMaxDurationMs;

    // ================================================================
    // Assistant
    // ================================================================

    public interface CodingAssistant {
        String chat(@MemoryId String sessionId, @UserMessage String message);
    }

    private CodingAssistant assistant;

    @PostConstruct
    public void init() {
        // 按当前领域筛选本地工具
        List<Object> activeTools = toolPlugins.stream()
                .filter(p -> p.getDomainName().equals(domainConfig.getName()))
                .flatMap(p -> p.getToolBeans().stream())
                .collect(Collectors.toList());

        if (activeTools.isEmpty()) {
            throw new IllegalStateException(
                    "领域 " + domainConfig.getName() + " 没有注册任何工具，请检查 ToolPlugin 实现");
        }

        var builder = AiServices.builder(CodingAssistant.class)
                .chatModel(deepSeekModel)
                .chatMemoryProvider(memoryId -> memoryOf((String) memoryId))
                .systemMessageProvider(memoryId -> buildSystemPrompt((String) memoryId));

        // 装配本地工具
        builder.tools(activeTools.toArray(new Object[0]));

        // ★ 装配 MCP 工具（如果 MCP Server 可用）
        if (mcpToolProvider != null) {
            builder.toolProvider(mcpToolProvider);
            log.info("已接入 MCP 工具提供者");
        } else {
            log.warn("MCP 工具提供者未注入，仅使用本地工具");
        }

        this.assistant = builder.build();

        log.info("CodingAssistant 初始化完成，领域={}, 本地工具数={}, 工具=[{}]",
                domainConfig.getName(),
                activeTools.size(),
                activeTools.stream()
                        .map(t -> t.getClass().getSimpleName())
                        .collect(Collectors.joining(", ")));
    }

    // ================================================================
    // 主入口
    // ================================================================

    public String execute(String userMessage) throws InterruptedException {
        return execute(UUID.randomUUID().toString(), userMessage);
    }

    public String execute(String sessionId, String userMessage) throws InterruptedException {
        if (!globalSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
            log.warn("全局限流触发，拒绝请求");
            return "系统繁忙，请稍后重试";
        }
        try {
            Semaphore sessionSem = sessionSemaphores.computeIfAbsent(sessionId, k -> new Semaphore(1));
            if (!sessionSem.tryAcquire(3, TimeUnit.SECONDS)) {
                log.warn("会话 {} 限流触发，拒绝请求", sessionId);
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

        toolCallGuard.reset(sessionId);
        ToolCallGuard.setCurrentSession(sessionId);

        try {
            AgentSession session = sessionManager.getOrCreateSession(sessionId);
            log.info("会话状态: 消息数={}", session.getMessages().size());

            ChatMemory memory = memoryOf(sessionId);

            if (contextCompressor.needsCompression(memory.messages())) {
                log.info("触发上下文压缩，压缩前消息数: {}", memory.messages().size());
                List<ChatMessage> compressed = contextCompressor.compress(memory.messages());
                replaceMemory(memory, compressed);
                log.info("上下文压缩完成，压缩后消息数: {}", compressed.size());
            }

            if (planSubAgent != null && isComplex(userMessage)) {
                long planStart = System.currentTimeMillis();
                try {
                    AiMessage plan = planSubAgent.plan(userMessage);
                    String planText = plan != null ? plan.text() : null;
                    if (planText != null && !planText.isBlank()) {
                        pendingPlanContext.put(sessionId, planText);
                        log.info("复杂任务已生成执行计划，耗时 {}ms，计划长度 {}",
                                System.currentTimeMillis() - planStart, planText.length());
                    }
                } catch (Exception e) {
                    log.warn("生成执行计划失败，按普通任务处理: {}", e.getMessage());
                }
            }

            List<MemoryDocument> relevantMemories =
                    longTermMemoryService.retrieveRelevantMemories(userMessage, 3);
            pendingMemoryContext.put(sessionId, formatMemoryContext(relevantMemories));

            String response = assistant.chat(sessionId, userMessage);

            response = applyReflexion(sessionId, userMessage, response);

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
            pendingPlanContext.remove(sessionId);
            // ★ 必须清理 ThreadLocal，避免线程池复用时下一个请求读到上一个会话的 sessionId
            ToolCallGuard.clearCurrentSession();
        }
    }

    // ================================================================
    // 反思闭环
    // ================================================================

    private String applyReflexion(String sessionId, String userMessage, String response) {
        if (!reflexionEnabled) {
            log.debug("会话 {} 反思已关闭，跳过", sessionId);
            return response;
        }
        if (response == null || response.isBlank()) {
            return response;
        }
        if (!shouldReflect(userMessage)) {
            log.debug("会话 {} 任务类型不需要反思，跳过", sessionId);
            return response;
        }

        long reflectStart = System.currentTimeMillis();
        try {
            EvaluationResult eval = evaluationService.evaluate(response, userMessage);
            log.info("会话 {} 反思评估: 总分={}, 通过={}",
                    sessionId, eval.getOverallScore(), eval.isPassed());

            if (System.currentTimeMillis() - reflectStart > reflexionMaxDurationMs) {
                log.warn("会话 {} 评估耗时超过上限 {}ms，跳过修正",
                        sessionId, reflexionMaxDurationMs);
                return response;
            }

            if (eval.isPassed() || eval.getOverallScore() >= scoreThreshold) {
                log.debug("会话 {} 质量达标（{}），无需修正",
                        sessionId, eval.getOverallScore());
                return response;
            }

            log.info("会话 {} 质量未达标（{} < {}），触发自动修正",
                    sessionId, eval.getOverallScore(), scoreThreshold);

            var fixResult = autoFixerService.fix(response, userMessage, eval);

            if (System.currentTimeMillis() - reflectStart > reflexionMaxDurationMs) {
                log.warn("会话 {} 修正耗时超过上限 {}ms，保留原答案",
                        sessionId, reflexionMaxDurationMs);
                return response;
            }

            if (fixResult.isPassed()
                    && fixResult.getFinalContent() != null
                    && !fixResult.getFinalContent().isBlank()) {
                log.info("会话 {} 修正成功: {} → {}，尝试 {} 轮",
                        sessionId,
                        eval.getOverallScore(),
                        fixResult.getFinalEvaluation().getOverallScore(),
                        fixResult.getAttempts());
                return fixResult.getFinalContent();
            }

            log.warn("会话 {} 修正未达标或内容为空，保留原答案", sessionId);
            return response;

        } catch (Exception e) {
            log.warn("会话 {} 反思环节异常，保留原答案: {}", sessionId, e.getMessage());
            return response;
        } finally {
            log.debug("会话 {} 反思环节耗时 {}ms",
                    sessionId, System.currentTimeMillis() - reflectStart);
        }
    }

    private boolean shouldReflect(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String lower = userMessage.toLowerCase();
        return lower.contains("实现") || lower.contains("生成")
                || lower.contains("重构") || lower.contains("方案")
                || lower.contains("分析") || lower.contains("设计")
                || lower.contains("优化") || lower.contains("评审")
                || lower.contains("review") || lower.contains("检查");
    }

    // ================================================================
    // 记忆 / 提示词
    // ================================================================

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

    private void replaceMemory(ChatMemory memory, List<ChatMessage> messages) {
        memory.clear();
        if (messages != null) {
            for (ChatMessage msg : messages) {
                memory.add(msg);
            }
        }
    }

    private String buildSystemPrompt(String sessionId) {
        String basePrompt = domainConfig.getSystemPrompt();
        if (basePrompt == null || basePrompt.isBlank()) {
            basePrompt = "你是一个智能助手。";
        }

        StringBuilder sb = new StringBuilder(basePrompt);

        String planContext = pendingPlanContext.get(sessionId);
        if (planContext != null && !planContext.isEmpty()) {
            sb.append("\n\n【本轮任务执行计划（请按此计划逐步执行，可调用工具/子 Agent 完成每一步）】\n")
                    .append(planContext);
        }

        String memoryContext = pendingMemoryContext.get(sessionId);
        if (memoryContext != null && !memoryContext.isEmpty()) {
            sb.append("\n\n【相关历史记录（供参考，非本轮用户输入）】\n")
                    .append(memoryContext);
        }

        return sb.toString();
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void evictStaleResources() {
        Set<String> alive = new HashSet<>(sessionManager.getAllSessionIds());

        memoryCache.keySet().removeIf(id -> !alive.contains(id));
        pendingMemoryContext.keySet().removeIf(id -> !alive.contains(id));
        pendingPlanContext.keySet().removeIf(id -> !alive.contains(id));

        toolCallGuard.evict(alive);

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

        log.debug("资源回收完成: memories={}, sessionSemaphores={}, toolTrackedSessions={}",
                memoryCache.size(), sessionSemaphores.size(), toolCallGuard.getTrackedSessionCount());
    }

    // ================================================================
    // 辅助
    // ================================================================

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

    private boolean isComplex(String userMessage) {
        try {
            return subAgentRoutingService.isComplexTask(userMessage);
        } catch (Exception e) {
            log.warn("复杂度判断失败，按简单任务处理: {}", e.getMessage());
            return false;
        }
    }

    public AgentStatus getStatus() {
        return AgentStatus.IDLE;
    }

    public void reset() {
        sessionManager.clearAllSessions();
        memoryCache.clear();
        pendingMemoryContext.clear();
        pendingPlanContext.clear();
        sessionSemaphores.clear();
        toolCallGuard.clearAll();
        log.info("Agent 已重置");
    }

    public int getActiveSessionCount() {
        return sessionManager.getActiveSessionCount();
    }
}
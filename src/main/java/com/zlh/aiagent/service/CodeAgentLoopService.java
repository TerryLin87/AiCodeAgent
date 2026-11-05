package com.zlh.aiagent.service;

import com.zlh.aiagent.config.DomainConfig;
import com.zlh.aiagent.data.*;
import com.zlh.aiagent.data.http.AgentResponse;
import com.zlh.aiagent.enums.AgentStatus;
import com.zlh.aiagent.instances.IToolPlugin;
import com.zlh.aiagent.model.DeepSeekModel;
import com.zlh.aiagent.service.subagent.ContextCompressor;
import com.zlh.aiagent.service.subagent.PlanSubAgent;
import com.zlh.aiagent.tools.AgentTools;
import com.zlh.aiagent.tools.ToolCallGuard;
import com.zlh.aiagent.tools.ToolCallTrace;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CodeAgentLoopService {

    private final Semaphore globalSemaphore = new Semaphore(10);
    private final Map<String, Semaphore> sessionSemaphores = new ConcurrentHashMap<>();
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

    @Autowired
    private UserStateTracker userStateTracker;

    @Autowired
    private AgentTools agentTools;

    @Autowired
    private WorkingMemoryService workingMemoryService;

    @Autowired
    private ToolCallTrace toolCallTrace;

    // ================================================================
    // 配置项
    // ================================================================

    @Value("${agent.reflexion.enabled:true}")
    private boolean reflexionEnabled;

    @Value("${agent.reflexion.score-threshold:7.0}")
    private double scoreThreshold;

    @Value("${agent.reflexion.max-duration-ms:30000}")
    private long reflexionMaxDurationMs;

    private volatile boolean shuttingDown = false;

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
                .toList();

        if (activeTools.isEmpty()) {
            throw new IllegalStateException(
                    "领域 " + domainConfig.getName() + " 没有注册任何工具，请检查 ToolPlugin 实现");
        }

        var builder = AiServices.builder(CodingAssistant.class)
                .chatModel(deepSeekModel)
                // 改用 SessionManager 提供的 memory
                .chatMemoryProvider(memoryId -> {
                    String sessionId = (String) memoryId;
                    return sessionManager.getOrCreateSession(sessionId).getChatMemory();
                })
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
        toolCallTrace.clear(sessionId);

        try {
            AgentSession session = sessionManager.getOrCreateSession(sessionId);
            log.info("会话状态: 消息数={}", session.getMessages().size());

            // 1. 上下文压缩检查
            ChatMemory memory = memoryOf(sessionId);
            if (contextCompressor.needsCompression(sessionId, memory.messages())) {
                log.info("触发上下文压缩，压缩前消息数: {}", memory.messages().size());
                List<ChatMessage> compressed = contextCompressor.compress(sessionId, memory.messages());

                // 交给 SessionManager 进行原子替换
                sessionManager.replaceSessionMemory(sessionId, compressed);
                log.info("上下文压缩完成，压缩后消息数: {}", compressed.size());
            }

            // 2. 状态追踪与指代消解
            userStateTracker.recordState(sessionId, userMessage);
            String resolvedRef = userStateTracker.resolveReference(sessionId, userMessage);
            String finalUserMessage = userMessage;
            if (resolvedRef != null) {
                finalUserMessage = userMessage + "\n\n[系统提示：用户提到的指代实际是指：'" + resolvedRef + "']";
                log.info("指代消解成功: {} -> {}", userMessage, resolvedRef);
            }

            // 3. 复杂任务规划 (Plan)
            if (planSubAgent != null && isComplex(userMessage)) {
                long planStart = System.currentTimeMillis();
                try {
                    AiMessage plan = planSubAgent.plan(userMessage);
                    String planText = plan != null ? plan.text() : null;
                    if (planText != null && !planText.isBlank()) {
                        pendingPlanContext.put(sessionId, planText);
                        log.info("复杂任务已生成执行计划，耗时 {}ms，计划长度 {}",
                                System.currentTimeMillis() - planStart, planText.length());

                        // 把计划步骤写入工作记忆
                        List<WorkingMemory.StepRecord> steps = toStepRecords(plan);
                        if (!steps.isEmpty()) {
                            workingMemoryService.initSteps(sessionId, userMessage, steps);
                        }
                    }
                } catch (Exception e) {
                    log.warn("生成执行计划失败，按普通任务处理: {}", e.getMessage());
                }
            }

            // 4. 长期记忆检索 (RAG)
            List<MemoryDocument> relevantMemories =
                    longTermMemoryService.retrieveRelevantMemories(userMessage, 3);
            pendingMemoryContext.put(sessionId, formatMemoryContext(relevantMemories));

            // 5. 正式调用模型 (带工具执行)
            String response = assistant.chat(sessionId, finalUserMessage);

            //工具轨迹 → 工作记忆步骤自动标记
            syncWorkingMemoryWithTrace(sessionId);

            // 6. 反思与自动修正
            response = applyReflexion(sessionId, userMessage, response);

            // 7. 持久化记忆
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
            ToolCallGuard.clearCurrentSession();
        }
    }

    /**
     * 把 Plan 的 ToolExecutionRequest 列表转成 WorkingMemory.StepRecord。
     *
     * <p>Plan 生成的每一步 action 对应一个 StepRecord，初始状态为 PENDING。
     */
    private List<WorkingMemory.StepRecord> toStepRecords(AiMessage plan) {
        if (plan == null || plan.toolExecutionRequests() == null
                || plan.toolExecutionRequests().isEmpty()) {
            return List.of();
        }
        List<WorkingMemory.StepRecord> records = new ArrayList<>();
        int order = 1;
        for (var req : plan.toolExecutionRequests()) {
            records.add(WorkingMemory.StepRecord.builder()
                    .order(order++)
                    .action(req.name())
                    .params(req.arguments())
                    .description(req.name() + "(" + req.arguments() + ")")
                    .status(0)   // PENDING，按你 PlanStatus 枚举的 PENDING 值调整
                    .timestamp(java.time.LocalDateTime.now())
                    .build());
        }
        return records;
    }

    // ================================================================
    // 带引用的执行入口（T5）
    // ================================================================

    /**
     * 带引用的执行入口：返回 {@link AgentResponse}（正文 + citations）。
     *
     * <p>与 {@link #execute(String, String)} 的区别：本方法在返回前会解析工具输出里的
     * {@code [ref:...]} 标记，转换成结构化的 Citation 列表，并从正文中移除标记。
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     * @return 含 citations 的响应
     */
    public AgentResponse executeWithCitations(String sessionId, String userMessage)
            throws InterruptedException {
        long start = System.currentTimeMillis();

        if (!globalSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
            return AgentResponse.builder()
                    .sessionId(sessionId)
                    .content("系统繁忙，请稍后重试")
                    .citations(List.of())
                    .executionTimeMs(System.currentTimeMillis() - start)
                    .build();
        }
        try {
            Semaphore sessionSem = sessionSemaphores.computeIfAbsent(sessionId, k -> new Semaphore(1));
            if (!sessionSem.tryAcquire(3, TimeUnit.SECONDS)) {
                return AgentResponse.builder()
                        .sessionId(sessionId)
                        .content("当前会话有请求正在处理，请稍后")
                        .citations(List.of())
                        .executionTimeMs(System.currentTimeMillis() - start)
                        .build();
            }
            try {
                // 每轮开始前清空引用缓存，避免上一轮结果混入
                agentTools.clearCitationCache();

                String rawContent = doExecute(sessionId, userMessage);

                // 解析 [ref:...] 并移除标记
                List<Citation> citations = extractCitations(rawContent);
                String cleanContent = rawContent == null ? ""
                        : rawContent.replaceAll("\\n?\\[ref:[^\\]]+\\]", "").trim();

                //填充工具轨迹 + 元数据
                List<String> toolCalls = toolCallTrace.getToolNames(sessionId);
                Map<String, Object> metadata = new HashMap<>();
                WorkingMemory wm = workingMemoryService.get(sessionId);
                if (wm != null) {
                    metadata.put("goal", wm.getGoal());
                    metadata.put("completedSteps", wm.getCompletedSteps());
                    metadata.put("totalSteps", wm.getTotalSteps());
                    metadata.put("currentPhase", wm.getCurrentPhase());
                }

                return AgentResponse.builder()
                        .sessionId(sessionId)
                        .content(cleanContent)
                        .citations(citations)
                        .toolCalls(toolCalls)
                        .metadata(metadata)
                        .executionTimeMs(System.currentTimeMillis() - start)
                        .build();
            } finally {
                sessionSem.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AgentResponse.builder()
                    .sessionId(sessionId)
                    .content("请求被中断")
                    .citations(List.of())
                    .executionTimeMs(System.currentTimeMillis() - start)
                    .build();
        } finally {
            globalSemaphore.release();
        }
    }

    /**
     * 从响应正文里提取 {@code [ref:id1,id2]} 标记，转成 Citation 列表。
     *
     * @param content Agent 原始响应（含标记）
     * @return 去重后的 Citation 列表，无标记时返回空列表
     */
    private List<Citation> extractCitations(String content) {
        if (content == null || content.isBlank()) return List.of();

        Set<String> ids = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\[ref:([^\\]]+)\\]").matcher(content);
        while (m.find()) {
            for (String id : m.group(1).split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) ids.add(trimmed);
            }
        }

        Map<String, RetrievalResult> cache = agentTools.getCitationCache();
        List<Citation> citations = new ArrayList<>();
        for (String id : ids) {
            RetrievalResult r = cache.get(id);
            if (r != null) {
                Citation c = Citation.from(r);
                if (c != null) citations.add(c);
            }
        }
        return citations;
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
        // 直接从 SessionManager 拿会话里绑定的 ChatMemory
        AgentSession session = sessionManager.getOrCreateSession(sessionId);
        return session.getChatMemory();
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

        //注入工作记忆（让模型知道做到哪了）
        String workingMemoryPrompt = workingMemoryService.renderForPrompt(sessionId);
        if (!workingMemoryPrompt.isEmpty()) {
            sb.append(workingMemoryPrompt);
        }

        return sb.toString();
    }

    @PreDestroy
    public void shutdown() {
        this.shuttingDown = true;
        log.info("CodeAgentLoopService marked as shutting down");
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void evictStaleResources() {
        Set<String> alive = new HashSet<>(sessionManager.getAllSessionIds());
        pendingMemoryContext.keySet().removeIf(id -> !alive.contains(id));
        pendingPlanContext.keySet().removeIf(id -> !alive.contains(id));
        //清理死会话的工作记忆
        workingMemoryService.getAllSessionIds().stream()
                .filter(id -> !alive.contains(id))
                .forEach(workingMemoryService::clear);

        toolCallGuard.evict(alive);
        toolCallTrace.evict(alive);

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
        pendingMemoryContext.clear();
        pendingPlanContext.clear();
        sessionSemaphores.clear();
        toolCallGuard.clearAll();
        toolCallTrace.clearAll();
        //清空所有工作记忆
        workingMemoryService.getAllSessionIds()
                .forEach(workingMemoryService::clear);
        log.info("Agent 已重置");
    }

    public int getActiveSessionCount() {
        return sessionManager.getActiveSessionCount();
    }

    /**
     * 把本轮工具调用轨迹同步到工作记忆：每个成功的工具调用尝试标记对应步骤完成。
     */
    private void syncWorkingMemoryWithTrace(String sessionId) {
        List<ToolCallTrace.Record> records = toolCallTrace.get(sessionId);
        if (records.isEmpty()) {
            return;
        }
        for (ToolCallTrace.Record r : records) {
            if (r.isSuccess()) {
                workingMemoryService.markStepCompletedByAction(
                        sessionId, r.getToolName(), r.getResultSummary());
            }
        }
    }
}
package com.zlh.aiagent.service.common;

import com.zlh.aiagent.config.DomainConfig;
import com.zlh.aiagent.data.common.*;
import com.zlh.aiagent.data.common.http.AgentResponse;
import com.zlh.aiagent.enums.AgentStatus;
import com.zlh.aiagent.enums.CallType;
import com.zlh.aiagent.instances.IAgentStreamCallback;
import com.zlh.aiagent.instances.IStreamChatCallback;
import com.zlh.aiagent.instances.IToolPlugin;
import com.zlh.aiagent.model.DeepSeekModel;
import com.zlh.aiagent.service.code.SelfEvaluationService;
import com.zlh.aiagent.service.code.subagent.PlanSubAgent;
import com.zlh.aiagent.tools.AgentTools;
import com.zlh.aiagent.tools.ToolCallGuard;
import com.zlh.aiagent.tools.ToolCallTrace;
import com.zlh.aiagent.utils.CancellationToken;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Agent 主循环服务：串起"限流 → 上下文压缩 → 指代消解 → 任务规划 → 长期记忆检索 →
 * 调用模型 → 工具轨迹同步 → 反思修正 → 记忆持久化"的完整闭环。
 *
 * <p>功能分区：
 * <ul>
 *   <li>【A】并发控制与上下文缓存</li>
 *   <li>【B】依赖注入</li>
 *   <li>【C】配置项</li>
 *   <li>【D】Assistant 定义与初始化</li>
 *   <li>【E】主执行入口（全局/会话双层限流 + CancellationToken）</li>
 *   <li>【F】复杂任务计划 → 工作记忆步骤</li>
 *   <li>【G】带引用的执行入口（返回正文 + citations）</li>
 *   <li>【G2】流式执行入口（SSE 用）</li>
 *   <li>【H】反思闭环</li>
 *   <li>【I】记忆与系统提示词构建</li>
 *   <li>【J】生命周期与资源回收</li>
 *   <li>【K】辅助与状态管理</li>
 *   <li>【L】请求取消</li>
 * </ul>
 */
@Slf4j
@Service
public class CodeAgentLoopService {

    //region 【A】并发控制与上下文缓存

    private final Semaphore globalSemaphore = new Semaphore(10);
    private final Map<String, Semaphore> sessionSemaphores = new ConcurrentHashMap<>();
    private final Map<String, String> pendingMemoryContext = new ConcurrentHashMap<>();
    private final Map<String, String> pendingPlanContext = new ConcurrentHashMap<>();
    /** sessionId -> 当前正在执行的 CancellationToken（供取消接口按 session 取消） */
    private final Map<String, CancellationToken> activeTokens = new ConcurrentHashMap<>();
    /** requestId -> sessionId（反向索引，/cancel by requestId 用） */
    private final Map<String, String> requestToSession = new ConcurrentHashMap<>();

    private static final int MEMORY_WINDOW = 50;
    private static final int MAX_CACHED_MEMORIES = 1000;

    //endregion

    //region 【B】依赖注入

    @Autowired
    @Qualifier("DeepSeekModel")
    private DeepSeekModel deepSeekModel;

    @Autowired
    private LongTermMemoryService longTermMemoryService;

    @Autowired
    private ContextCompressor contextCompressor;

    @Autowired
    private SessionManager sessionManager;

    @Autowired(required = false)
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

    //endregion

    //region 【C】配置项

    @Value("${agent.reflexion.enabled:true}")
    private boolean reflexionEnabled;

    @Value("${agent.reflexion.score-threshold:7.0}")
    private double scoreThreshold;

    @Value("${agent.reflexion.max-duration-ms:30000}")
    private long reflexionMaxDurationMs;

    private volatile boolean shuttingDown = false;

    //endregion

    //region 【D】Assistant 定义与初始化

    public interface CodingAssistant {
        String chat(@MemoryId String sessionId, @dev.langchain4j.service.UserMessage String message);
    }

    private CodingAssistant assistant;

    @PostConstruct
    public void init() {
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
                .chatMemoryProvider(memoryId -> {
                    String sessionId = (String) memoryId;
                    return sessionManager.getOrCreateSession(sessionId).getChatMemory();
                })
                .systemMessageProvider(memoryId -> buildSystemPrompt((String) memoryId));

        builder.tools(activeTools.toArray(new Object[0]));

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

    //endregion

    //region 【E】主执行入口（全局/会话双层限流 + 取消支持）

    public String execute(String userMessage) {
        return execute(UUID.randomUUID().toString(), userMessage);
    }

    public String execute(String sessionId, String userMessage) {
        return execute(sessionId, userMessage, null);
    }

    public String execute(String sessionId, String userMessage, CancellationToken cancelToken) {
        boolean globalAcquired = false;
        try {
            try {
                if (!globalSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                    log.warn("全局限流触发，拒绝请求");
                    return "系统繁忙，请稍后重试";
                }
                globalAcquired = true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "请求已被取消";
            }

            CancellationToken effectiveToken = bindToken(sessionId, cancelToken);
            try {
                Semaphore sessionSem = sessionSemaphores.computeIfAbsent(sessionId, k -> new Semaphore(1));
                boolean sessionAcquired = false;
                try {
                    try {
                        if (!sessionSem.tryAcquire(3, TimeUnit.SECONDS)) {
                            log.warn("会话 {} 限流触发，拒绝请求", sessionId);
                            return "当前会话有请求正在处理，请稍后";
                        }
                        sessionAcquired = true;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return "请求已被取消";
                    }
                    try {
                        checkCancelled(effectiveToken);
                        return doExecute(sessionId, userMessage, effectiveToken);
                    } catch (CancellationToken.CancelledException e) {
                        log.info("会话 {} 请求被取消: {}", sessionId, e.getMessage());
                        return "请求已被取消";
                    }
                } finally {
                    if (sessionAcquired) sessionSem.release();
                }
            } finally {
                globalSemaphore.release();
                unbindToken(sessionId, effectiveToken);
            }
        } finally {
            // no-op
        }
    }

    public String doExecute(String sessionId, String userMessage) {
        return doExecute(sessionId, userMessage, null);
    }

    public String doExecute(String sessionId, String userMessage, CancellationToken cancelToken) {
        log.info("Agent 开始执行: session={}, message={}", sessionId, userMessage);
        long startTime = System.currentTimeMillis();

        toolCallGuard.reset(sessionId);
        ToolCallGuard.setCurrentSession(sessionId);
        toolCallTrace.clear(sessionId);

        try {
            checkCancelled(cancelToken);

            AgentSession session = sessionManager.getOrCreateSession(sessionId);
            log.info("会话状态: 消息数={}", session.getMessages().size());

            // 1. 上下文压缩检查
            ChatMemory memory = memoryOf(sessionId);
            if (contextCompressor.needsCompression(sessionId, memory.messages())) {
                checkCancelled(cancelToken);
                log.info("触发上下文压缩，压缩前消息数: {}", memory.messages().size());
                List<ChatMessage> compressed = contextCompressor.compress(sessionId, memory.messages());
                sessionManager.replaceSessionMemory(sessionId, compressed);
                log.info("上下文压缩完成，压缩后消息数: {}", compressed.size());
            }

            checkCancelled(cancelToken);

            // 2. 状态追踪与指代消解
            userStateTracker.recordState(sessionId, userMessage);
            String resolvedRef = userStateTracker.resolveReference(sessionId, userMessage);
            String finalUserMessage = userMessage;
            if (resolvedRef != null) {
                finalUserMessage = userMessage + "\n\n[系统提示：用户提到的指代实际是指：'" + resolvedRef + "']";
                log.info("指代消解成功: {} -> {}", userMessage, resolvedRef);
            }

            checkCancelled(cancelToken);

            // 3. 复杂任务规划 (Plan)
            if (planSubAgent != null && isComplex(userMessage)) {
                checkCancelled(cancelToken);
                long planStart = System.currentTimeMillis();
                try {
                    // PlanSubAgent 内部最终也走 deepSeekModel.doChat；由于它注入的是同一个
                    // DeepSeekModel Bean，我们在此线程把 cancelToken 透不过去（它是独立调用）。
                    // 为了真正取消规划，需要把 token 传进 plan()；下面走可取消版本。
                    AiMessage plan = planSubAgent.plan(userMessage, cancelToken);
                    String planText = plan != null ? plan.text() : null;
                    if (planText != null && !planText.isBlank()) {
                        pendingPlanContext.put(sessionId, planText);
                        log.info("复杂任务已生成执行计划，耗时 {}ms，计划长度 {}",
                                System.currentTimeMillis() - planStart, planText.length());
                        List<WorkingMemory.StepRecord> steps = toStepRecords(plan);
                        if (!steps.isEmpty()) {
                            workingMemoryService.initSteps(sessionId, userMessage, steps);
                        }
                    }
                } catch (CancellationToken.CancelledException ce) {
                    throw ce;
                } catch (Exception e) {
                    log.warn("生成执行计划失败，按普通任务处理: {}", e.getMessage());
                }
            }

            checkCancelled(cancelToken);

            // 4. 长期记忆检索 (RAG)
            List<MemoryDocument> relevantMemories =
                    longTermMemoryService.retrieveRelevantMemories(userMessage, 3);
            pendingMemoryContext.put(sessionId, formatMemoryContext(relevantMemories));

            checkCancelled(cancelToken);

            // 5. 正式调用模型 (带工具执行)
            //    assistant.chat 走 AiServices 内部链，不会直接走我们的 doChat(token) 重载——
            //    因此同步路径的取消是"轮询式"的：规划前、压缩前、反思前等节点检查。
            //    如果需要细粒度中断同步工具循环，必须改走流式工具循环（C-3 下一阶段）。
            String response = assistant.chat(sessionId, finalUserMessage);

            checkCancelled(cancelToken);

            syncWorkingMemoryWithTrace(sessionId);

            // 6. 反思与自动修正
            response = applyReflexion(sessionId, userMessage, response, cancelToken);

            checkCancelled(cancelToken);

            // 7. 持久化记忆
            sessionManager.addUserMessage(sessionId, userMessage);
            sessionManager.addAssistantMessage(sessionId, response);
            longTermMemoryService.storeMemory(sessionId, "user", userMessage);
            longTermMemoryService.storeMemory(sessionId, "assistant", response);

            log.info("Agent 完成, 耗时: {}ms, 响应长度: {} 字符",
                    System.currentTimeMillis() - startTime, response.length());
            return response;

        } catch (CancellationToken.CancelledException ce) {
            log.info("会话 {} 执行被取消，不持久化本轮结果", sessionId);
            throw ce;
        } catch (Exception e) {
            if (e instanceof CancellationToken.CancelledException) throw e;
            log.error("Agent 执行异常", e);
            return "Agent 执行失败: " + e.getMessage() + "\n\n建议: " + getRecoverySuggestion(e);
        } finally {
            pendingMemoryContext.remove(sessionId);
            pendingPlanContext.remove(sessionId);
            ToolCallGuard.clearCurrentSession();
        }
    }

    //endregion

    //region 【F】复杂任务计划 → 工作记忆步骤

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
                    .status(0)
                    .timestamp(java.time.LocalDateTime.now())
                    .build());
        }
        return records;
    }

    //endregion

    //region 【G】带引用的执行入口（返回正文 + citations）

    public AgentResponse executeWithCitations(String sessionId, String userMessage) {
        return executeWithCitations(sessionId, userMessage, null);
    }

    public AgentResponse executeWithCitations(String sessionId, String userMessage,
                                              CancellationToken cancelToken) {
        long start = System.currentTimeMillis();
        boolean globalAcquired = false;

        try {
            try {
                if (!globalSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                    return AgentResponse.builder()
                            .sessionId(sessionId)
                            .content("系统繁忙，请稍后重试")
                            .citations(List.of())
                            .cancelled(false)
                            .executionTimeMs(System.currentTimeMillis() - start)
                            .build();
                }
                globalAcquired = true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return AgentResponse.builder()
                        .sessionId(sessionId)
                        .content("请求已被取消")
                        .citations(List.of())
                        .cancelled(true)
                        .executionTimeMs(System.currentTimeMillis() - start)
                        .build();
            }

            CancellationToken effectiveToken = bindToken(sessionId, cancelToken);
            try {
                Semaphore sessionSem = sessionSemaphores.computeIfAbsent(sessionId, k -> new Semaphore(1));
                boolean sessionAcquired = false;
                try {
                    try {
                        if (!sessionSem.tryAcquire(3, TimeUnit.SECONDS)) {
                            return AgentResponse.builder()
                                    .sessionId(sessionId)
                                    .content("当前会话有请求正在处理，请稍后")
                                    .citations(List.of())
                                    .cancelled(false)
                                    .executionTimeMs(System.currentTimeMillis() - start)
                                    .build();
                        }
                        sessionAcquired = true;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return AgentResponse.builder()
                                .sessionId(sessionId)
                                .content("请求已被取消")
                                .citations(List.of())
                                .cancelled(true)
                                .executionTimeMs(System.currentTimeMillis() - start)
                                .build();
                    }

                    try {
                        checkCancelled(effectiveToken);
                        agentTools.clearCitationCache();

                        String rawContent = doExecute(sessionId, userMessage, effectiveToken);

                        List<Citation> citations = extractCitations(rawContent);
                        String cleanContent = rawContent == null ? ""
                                : rawContent.replaceAll("\\n?\\[ref:[^\\]]+\\]", "").trim();

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
                                .cancelled(false)
                                .executionTimeMs(System.currentTimeMillis() - start)
                                .build();
                    } catch (CancellationToken.CancelledException ce) {
                        return AgentResponse.builder()
                                .sessionId(sessionId)
                                .content("请求已被取消")
                                .citations(List.of())
                                .cancelled(true)
                                .executionTimeMs(System.currentTimeMillis() - start)
                                .build();
                    }
                } finally {
                    if (sessionAcquired) sessionSem.release();
                }
            } finally {
                if (globalAcquired) globalSemaphore.release();
                unbindToken(sessionId, effectiveToken);
            }
        } finally {
            // no-op
        }
    }

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

    //endregion

    //region 【G2】流式执行入口（SSE 用，支持取消）

    public void streamExecute(String sessionId, String userMessage, IAgentStreamCallback callback) {
        streamExecute(sessionId, userMessage, null, callback);
    }

    public void streamExecute(String sessionId, String userMessage,
                              String requestId, IAgentStreamCallback callback) {
        Objects.requireNonNull(callback, "callback cannot be null");

        // 为流式请求创建全局可查的 CancellationToken，按 requestId 注册
        CancellationToken token = requestId != null
                ? CancellationToken.create(requestId)
                : CancellationToken.create("req-" + sessionId + "-" + System.currentTimeMillis());

        long startAcquire = System.currentTimeMillis();
        CancellationToken effectiveToken = null;
        boolean globalAcquired = false;

        try {
            try {
                if (!globalSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                    log.warn("全局限流触发，拒绝流式请求");
                    callback.onError(new RuntimeException("系统繁忙，请稍后重试"));
                    return;
                }
                globalAcquired = true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.info("流式请求在等待全局限流时被中断, sessionId={}", sessionId);
                token.cancel();
                callback.onError(new CancellationToken.CancelledException("请求已被取消"));
                return;
            }

            effectiveToken = bindToken(sessionId, token);
            if (requestId != null) requestToSession.put(requestId, sessionId);

            Semaphore sessionSem = sessionSemaphores.computeIfAbsent(sessionId, k -> new Semaphore(1));
            boolean sessionAcquired = false;
            try {
                try {
                    if (!sessionSem.tryAcquire(3, TimeUnit.SECONDS)) {
                        log.warn("会话 {} 限流触发，拒绝流式请求", sessionId);
                        callback.onError(new RuntimeException("当前会话有请求正在处理，请稍后"));
                        return;
                    }
                    sessionAcquired = true;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.info("流式请求在等待会话限流时被中断, sessionId={}", sessionId);
                    token.cancel();
                    callback.onError(new CancellationToken.CancelledException("请求已被取消"));
                    return;
                }
                checkCancelled(effectiveToken);
                doStreamExecute(sessionId, userMessage, callback, effectiveToken);
            } finally {
                if (sessionAcquired) sessionSem.release();
            }
        } finally {
            if (globalAcquired) globalSemaphore.release();
            if (requestId != null) requestToSession.remove(requestId);
            if (effectiveToken != null) unbindToken(sessionId, effectiveToken);
            token.dispose();
            log.info("流式请求结束, sessionId={}, cost={}ms",
                    sessionId, System.currentTimeMillis() - startAcquire);
        }
    }

    private void doStreamExecute(String sessionId, String userMessage,
                                 IAgentStreamCallback callback,
                                 CancellationToken cancelToken) {
        log.info("Agent 开始流式执行: session={}, message={}", sessionId, userMessage);

        toolCallGuard.reset(sessionId);
        ToolCallGuard.setCurrentSession(sessionId);
        toolCallTrace.clear(sessionId);
        agentTools.clearCitationCache();

        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final AtomicReference<String> doneText = new AtomicReference<>();
        final AtomicReference<List<ToolExecutionRequest>> toolCallsRef = new AtomicReference<>();
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        try {
            checkCancelled(cancelToken);

            AgentSession session = sessionManager.getOrCreateSession(sessionId);
            log.info("会话状态: 消息数={}", session.getMessages().size());

            // 1. 上下文压缩
            ChatMemory memory = memoryOf(sessionId);
            if (contextCompressor.needsCompression(sessionId, memory.messages())) {
                checkCancelled(cancelToken);
                log.info("触发上下文压缩，压缩前消息数: {}", memory.messages().size());
                callback.onEvent("event", "开始上下文压缩");
                List<ChatMessage> compressed = contextCompressor.compress(sessionId, memory.messages());
                sessionManager.replaceSessionMemory(sessionId, compressed);
                log.info("上下文压缩完成，压缩后消息数: {}", compressed.size());
            }

            checkCancelled(cancelToken);

            // 2. 指代消解
            userStateTracker.recordState(sessionId, userMessage);
            String resolvedRef = userStateTracker.resolveReference(sessionId, userMessage);
            String finalUserMessage = userMessage;
            if (resolvedRef != null) {
                finalUserMessage = userMessage + "\n\n[系统提示：用户提到的指代实际是指：'" + resolvedRef + "']";
                callback.onEvent("event", "已做指代消解");
                log.info("指代消解成功: {} -> {}", userMessage, resolvedRef);
            }

            checkCancelled(cancelToken);

            // 3. 复杂任务规划
            if (planSubAgent != null && isComplex(userMessage)) {
                checkCancelled(cancelToken);
                callback.onEvent("plan", "开始生成执行计划");
                try {
                    AiMessage plan = planSubAgent.plan(userMessage, cancelToken);
                    String planText = plan != null ? plan.text() : null;
                    if (planText != null && !planText.isBlank()) {
                        pendingPlanContext.put(sessionId, planText);
                        log.info("复杂任务已生成执行计划");
                        List<WorkingMemory.StepRecord> steps = toStepRecords(plan);
                        if (!steps.isEmpty()) {
                            workingMemoryService.initSteps(sessionId, userMessage, steps);
                        }
                        callback.onEvent("plan", "生成计划完成，步骤数:" + steps.size());
                    }
                } catch (CancellationToken.CancelledException ce) {
                    throw ce;
                } catch (Exception e) {
                    log.warn("生成执行计划失败，按普通任务处理: {}", e.getMessage());
                }
            }

            checkCancelled(cancelToken);

            // 4. 长期记忆检索
            List<MemoryDocument> relevantMemories =
                    longTermMemoryService.retrieveRelevantMemories(userMessage, 3);
            pendingMemoryContext.put(sessionId, formatMemoryContext(relevantMemories));

            checkCancelled(cancelToken);

            // 5. 构造 ChatRequest + 流式调用
            ChatRequest chatRequest = buildChatRequest(session, finalUserMessage);

            StringBuilder tokenBuf = new StringBuilder();
            IStreamChatCallback modelCb = new IStreamChatCallback() {
                @Override public void onToken(String token) {
                    tokenBuf.append(token);
                    callback.onToken(token);
                }
                @Override public void onCompleteToolCalls(List<ToolExecutionRequest> toolCalls) {
                    toolCallsRef.set(toolCalls);
                }
                @Override public void onComplete() {
                    doneText.set(tokenBuf.toString());
                }
                @Override public void onError(Throwable t) {
                    errorRef.set(t);
                }
            };
            deepSeekModel.streamChat(chatRequest, CallType.MAIN_CHAT, modelCb, cancelToken);

            // 6. 返回后统一判断
            if (errorRef.get() instanceof CancellationToken.CancelledException) {
                cancelled.set(true);
                return;
            }
            if (errorRef.get() != null) return;

            List<ToolExecutionRequest> toolCalls = toolCallsRef.get();
            String finalText = doneText.get();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                callback.onToolCalls(toolCalls);
                callback.onEvent("tool", "模型请求调用 " + toolCalls.size() + " 个工具");
            } else if (finalText != null) {
                callback.onEvent("event", "模型流式输出结束");
            }

            // 取消则不持久化
            if (cancelToken != null && cancelToken.isCancelled()) {
                cancelled.set(true);
                return;
            }

            sessionManager.addUserMessage(sessionId, userMessage);
            sessionManager.addAssistantMessage(sessionId, finalText == null ? "" : finalText);
            syncWorkingMemoryWithTrace(sessionId);

        } catch (CancellationToken.CancelledException ce) {
            cancelled.set(true);
            log.info("流式会话 {} 执行被取消", sessionId);
        } catch (Throwable t) {
            errorRef.compareAndSet(null, t);
            log.error("流式执行异常, sessionId={}", sessionId, t);
        } finally {
            pendingMemoryContext.remove(sessionId);
            pendingPlanContext.remove(sessionId);
            ToolCallGuard.clearCurrentSession();
        }

        if (cancelled.get()) {
            callback.onError(new CancellationToken.CancelledException("请求已取消"));
        } else if (errorRef.get() != null) {
            callback.onError(errorRef.get());
        } else {
            callback.onDone();
        }
    }

    private ChatRequest buildChatRequest(AgentSession session, String userMessage) {
        List<ChatMessage> msgs = new ArrayList<>(session.getChatMemory().messages());
        msgs.add(new UserMessage(userMessage));
        return ChatRequest.builder().messages(msgs).build();
    }

    //endregion

    //region 【H】反思闭环

    private String applyReflexion(String sessionId, String userMessage, String response) {
        return applyReflexion(sessionId, userMessage, response, null);
    }

    private String applyReflexion(String sessionId, String userMessage, String response,
                                  CancellationToken cancelToken) {
        if (!reflexionEnabled) {
            log.debug("会话 {} 反思已关闭，跳过", sessionId);
            return response;
        }
        if (response == null || response.isBlank()) return response;
        if (!shouldReflect(userMessage)) {
            log.debug("会话 {} 任务类型不需要反思，跳过", sessionId);
            return response;
        }

        long reflectStart = System.currentTimeMillis();
        try {
            checkCancelled(cancelToken);
            EvaluationResult eval = evaluationService.evaluate(response, userMessage);
            log.info("会话 {} 反思评估: 总分={}, 通过={}",
                    sessionId, eval.getOverallScore(), eval.isPassed());

            if (System.currentTimeMillis() - reflectStart > reflexionMaxDurationMs) {
                log.warn("会话 {} 评估耗时超过上限 {}ms，跳过修正",
                        sessionId, reflexionMaxDurationMs);
                return response;
            }
            checkCancelled(cancelToken);

            if (eval.isPassed() || eval.getOverallScore() >= scoreThreshold) {
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
            checkCancelled(cancelToken);

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

        } catch (CancellationToken.CancelledException ce) {
            log.info("会话 {} 反思阶段被取消", sessionId);
            throw ce;
        } catch (Exception e) {
            log.warn("会话 {} 反思环节异常，保留原答案: {}", sessionId, e.getMessage());
            return response;
        }
    }

    private boolean shouldReflect(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        List<String> keywords = domainConfig != null && domainConfig.getReflectKeywords() != null
                ? domainConfig.getReflectKeywords()
                : Arrays.asList("实现", "生成", "重构", "方案", "分析", "设计",
                "优化", "评审", "review", "检查");
        String lower = userMessage.toLowerCase();
        return keywords.stream().anyMatch(lower::contains);
    }

    //endregion

    //region 【I】记忆与系统提示词构建

    private ChatMemory memoryOf(String sessionId) {
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

        String workingMemoryPrompt = workingMemoryService.renderForPrompt(sessionId);
        if (!workingMemoryPrompt.isEmpty()) {
            sb.append(workingMemoryPrompt);
        }

        return sb.toString();
    }

    //endregion

    //region 【J】生命周期与资源回收

    @PreDestroy
    public void shutdown() {
        this.shuttingDown = true;
        // 关停时把所有活跃请求都标记为取消，释放 OkHttp 连接
        activeTokens.values().forEach(CancellationToken::cancel);
        log.info("CodeAgentLoopService marked as shutting down, {} active requests cancelled",
                activeTokens.size());
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void evictStaleResources() {
        Set<String> alive = new HashSet<>(sessionManager.getAllSessionIds());
        pendingMemoryContext.keySet().removeIf(id -> !alive.contains(id));
        pendingPlanContext.keySet().removeIf(id -> !alive.contains(id));
        workingMemoryService.getAllSessionIds().stream()
                .filter(id -> !alive.contains(id))
                .forEach(workingMemoryService::clear);

        toolCallGuard.evict(alive);
        toolCallTrace.evict(alive);

        sessionSemaphores.entrySet().removeIf(entry -> {
            if (alive.contains(entry.getKey())) return false;
            Semaphore sem = entry.getValue();
            if (sem.tryAcquire()) {
                sem.release();
                return true;
            }
            return false;
        });

        // 清理已结束的取消令牌
        activeTokens.entrySet().removeIf(e -> {
            CancellationToken t = e.getValue();
            return t == null || t.isCancelled();
        });
        requestToSession.entrySet().removeIf(e -> !activeTokens.containsKey(e.getValue()));
    }

    //endregion

    //region 【K】辅助与状态管理

    private String formatMemoryContext(List<MemoryDocument> memories) {
        if (memories == null || memories.isEmpty()) return "";
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
        if (message == null) return "请检查系统状态后重试";
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

    public AgentStatus getStatus() { return AgentStatus.IDLE; }

    public void reset() {
        sessionManager.clearAllSessions();
        pendingMemoryContext.clear();
        pendingPlanContext.clear();
        sessionSemaphores.clear();
        toolCallGuard.clearAll();
        toolCallTrace.clearAll();
        workingMemoryService.getAllSessionIds()
                .forEach(workingMemoryService::clear);
        // 重置时也清掉所有活动令牌
        activeTokens.values().forEach(CancellationToken::cancel);
        activeTokens.clear();
        requestToSession.clear();
        log.info("Agent 已重置");
    }

    public int getActiveSessionCount() { return sessionManager.getActiveSessionCount(); }

    private void syncWorkingMemoryWithTrace(String sessionId) {
        List<ToolCallTrace.Record> records = toolCallTrace.get(sessionId);
        if (records.isEmpty()) return;
        for (ToolCallTrace.Record r : records) {
            if (r.isSuccess()) {
                workingMemoryService.markStepCompletedByAction(
                        sessionId, r.getToolName(), r.getResultSummary());
            }
        }
    }

    //endregion

    //region 【L】请求取消

    private static void checkCancelled(CancellationToken t) {
        if (t != null) t.throwIfCancelled();
    }

    /** 把令牌绑定到 session：调用方传了就放入 active 表，没传就不绑定（无法按 sessionId 取消，但不影响正常执行）。 */
    private CancellationToken bindToken(String sessionId, CancellationToken incoming) {
        if (incoming == null) {
            return null;
        }
        CancellationToken prev = activeTokens.put(sessionId, incoming);
        // 如果上一个令牌还在跑，说明前一个请求应该已结束（双层信号量会挡住并发）；
        // 保险起见，把它标记为取消，防止极端情况下的悬挂。
        if (prev != null && prev != incoming) {
            prev.cancel();
        }
        return incoming;
    }

    private void unbindToken(String sessionId, CancellationToken t) {
        if (t != null) {
            activeTokens.remove(sessionId, t);
        }
    }

    /**
     * 按 sessionId 取消当前正在执行的请求（同步/流式都行）。
     * @return true 表示找到并触发了取消；false 表示该 session 当前没有活动请求。
     */
    public boolean cancelBySession(String sessionId) {
        CancellationToken t = activeTokens.get(sessionId);
        if (t == null) return false;
        log.info("按 sessionId {} 取消请求", sessionId);
        t.cancel();
        return true;
    }

    /**
     * 按 requestId 取消（流式请求专用，requestId 由前端在 SSE 连接建立时生成）。
     */
    public boolean cancelByRequestId(String requestId) {
        return CancellationToken.cancel(requestId);
    }

    /**
     * 反向查询：给定 requestId 返回其所属 sessionId，无则 null。
     */
    public String sessionOfRequest(String requestId) {
        return requestToSession.get(requestId);
    }

    //endregion
}

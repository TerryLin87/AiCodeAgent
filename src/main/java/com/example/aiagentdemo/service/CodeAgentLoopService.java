package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.AgentSession;
import com.example.aiagentdemo.data.EvaluationResult;
import com.example.aiagentdemo.data.MemoryDocument;
import com.example.aiagentdemo.enums.AgentStatus;
import com.example.aiagentdemo.model.DeepSeekModel;
import com.example.aiagentdemo.service.subagent.ContextCompressor;
import com.example.aiagentdemo.service.subagent.PlanSubAgent;
import com.example.aiagentdemo.tools.AgentTools;
import com.example.aiagentdemo.tools.ToolCallGuard;
import dev.langchain4j.data.message.AiMessage;
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

/**
 * Agent 主循环。
 *
 * <p>核心能力：
 * <ol>
 *   <li><b>双层限流</b>：全局 10 并发 + 单会话 1 并发</li>
 *   <li><b>会话级 ChatMemory 缓存</b>：跨轮复用，压缩原地生效</li>
 *   <li><b>上下文压缩</b>：五级降级，超阈值触发</li>
 *   <li><b>复杂任务规划</b>：isComplexTask 判定，计划注入 System Prompt</li>
 *   <li><b>长期记忆召回</b>：跨会话，注入 System Prompt 不污染用户消息</li>
 *   <li><b>反思闭环</b>：评估 + 修正，选择性触发，有时间上限</li>
 *   <li><b>终止条件</b>：工具调用次数上限由 {@link ToolCallGuard} 统一管理，
 *       避免与 AgentTools 形成循环依赖</li>
 * </ol>
 *
 * <p><b>循环依赖说明</b>：工具调用计数职责已抽到 {@code ToolCallGuard}，
 * 本类与 {@code AgentTools} 都只依赖它，不再互相依赖。
 * 本类负责重置/清理计数、设置 ThreadLocal；AgentTools 负责在调用时检查。
 */
@Slf4j
@Service
public class CodeAgentLoopService {

    /** 全局并发限制：最多 10 个请求同时执行 */
    private final Semaphore globalSemaphore = new Semaphore(10);

    /** 单会话并发限制：同一会话同时只能 1 个请求 */
    private final Map<String, Semaphore> sessionSemaphores = new ConcurrentHashMap<>();

    /** 会话级 ChatMemory 缓存（唯一真相源：所有会话消息都存在这里） */
    private final Map<String, ChatMemory> memoryCache = new ConcurrentHashMap<>();

    /** 本轮会话的长期记忆上下文，供 systemMessageProvider 读取后即删 */
    private final Map<String, String> pendingMemoryContext = new ConcurrentHashMap<>();

    /** 本轮会话的"任务计划"上下文，供 systemMessageProvider 读取后即删 */
    private final Map<String, String> pendingPlanContext = new ConcurrentHashMap<>();

    /** 每个会话保留的最大消息数 */
    private static final int MEMORY_WINDOW = 50;

    /** 记忆缓存硬上限，超过则触发一次空闲回收 */
    private static final int MAX_CACHED_MEMORIES = 1000;

    private static final String BASE_SYSTEM_PROMPT = """
            你是一个智能的 Java 代码助手，可以调用工具完成代码检索、分析和验证任务。

            ================================================================
            你的能力：
            1. 调用工具直接完成简单任务（搜索代码、读文件、查调用关系）
            2. 委托子 Agent 完成复杂任务（任务规划、深度探索、影响分析、质量验证）
            ================================================================

            可用工具：
            - searchCode(query)                     搜索代码
            - readFile(path)                        读取文件
            - getProjectStructure(projectName)      查看项目结构
            - getClassFields(className)             查看类字段
            - getCallers(className, methodName)     查询调用者
            - delegateToSubAgent(agentName, task)   委托子 Agent

            可用的子 Agent（能力与使用场景）：
            - explore: 代码探索专家（搜索、读文件、看结构、分析调用）——简单查询、定位代码时使用
            - verify:  验证专家（检查代码质量、验证正确性、代码评审）
            - plan:    任务规划专家（拆解复杂任务、给出执行方案、重构/实现的分步计划）
                      ——任务复杂、多步骤、需要方案时优先使用 plan，再按计划逐步执行

            ================================================================
            工作原则：
            1. 先判断任务复杂度：
               - 简单查询（"找 XX 类"、"XX 有哪些方法"）→ 直接调工具或 delegate explore
               - 验证/评审/检查问题 → delegate verify
               - 复杂任务（"实现某功能"、"重构某模块"、"分析整个流程"、"为什么出问题"）→
                 先 delegate plan 拿到执行计划，再按计划逐步 delegate 其他子 Agent
            2. 调用工具/子 Agent 后，根据返回结果决定下一步
            3. 收集到足够信息后，给出最终答案
            4. 不要编造代码信息，一切以工具返回为准
            ================================================================
            """;

    // ================================================================
    // 依赖注入
    // ================================================================

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

    @Autowired
    private PlanSubAgent planSubAgent;

    @Autowired
    private SubAgentRoutingService subAgentRoutingService;

    @Autowired
    private SelfEvaluationService evaluationService;

    @Autowired
    private AutoFixerService autoFixerService;

    /** 工具调用守卫（抽离出的计数职责，避免与 AgentTools 循环依赖） */
    @Autowired
    private ToolCallGuard toolCallGuard;

    // ================================================================
    // 配置项
    // ================================================================

    /** 是否启用反思闭环 */
    @Value("${agent.reflexion.enabled:true}")
    private boolean reflexionEnabled;

    /** 评分阈值，低于这个分数触发修正 */
    @Value("${agent.reflexion.score-threshold:7.0}")
    private double scoreThreshold;

    /** 反思环节最大耗时（毫秒），超过就放弃修正，避免拖慢响应 */
    @Value("${agent.reflexion.max-duration-ms:30000}")
    private long reflexionMaxDurationMs;

    // ================================================================
    // Assistant 接口
    // 注意：不再使用 @SystemMessage 注解，系统提示词改由 systemMessageProvider 动态提供
    // （这样才能把"长期记忆/计划"注入 System Prompt，而不是塞进用户消息）。
    // ================================================================

    public interface CodingAssistant {
        String chat(@MemoryId String sessionId, @UserMessage String message);
    }

    /** 全程只构建一次的 Assistant 代理 */
    private CodingAssistant assistant;

    @PostConstruct
    public void init() {
        this.assistant = AiServices.builder(CodingAssistant.class)
                .chatModel(deepSeekModel)
                .chatMemoryProvider(memoryId -> memoryOf((String) memoryId))
                .systemMessageProvider(memoryId -> buildSystemPrompt((String) memoryId))
                .tools(agentTools)
                .build();
        log.info("CodingAssistant 初始化完成（单实例 + 动态记忆/系统提示）");
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

        // ★ 重置本会话的工具调用计数
        toolCallGuard.reset(sessionId);
        // ★ 设置当前会话到 ThreadLocal，供 AgentTools 读取
        ToolCallGuard.setCurrentSession(sessionId);

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

            // ===== 4. 复杂任务先规划 =====
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
                    // 规划失败不阻断主流程，降级为不注入计划
                    log.warn("生成执行计划失败，按普通任务处理: {}", e.getMessage());
                }
            }

            // ===== 5. 检索长期记忆（跨会话），暂存供 System Prompt 使用 =====
            List<MemoryDocument> relevantMemories =
                    longTermMemoryService.retrieveRelevantMemories(userMessage, 3);
            pendingMemoryContext.put(sessionId, formatMemoryContext(relevantMemories));

            // ===== 6. 调用 Agent（用户消息保持原样，不拼记忆/计划） =====
            String response = assistant.chat(sessionId, userMessage);

            // ===== 7. 反思闭环：评估 + 修正（可选） =====
            response = applyReflexion(sessionId, userMessage, response);

            // ===== 8. 审计镜像 + 长期记忆 =====
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
            // ★ 必须清理 ThreadLocal，避免线程池复用时串会话
            ToolCallGuard.clearCurrentSession();
        }
    }

    // ================================================================
    // 反思闭环
    // ================================================================

    /**
     * 反思闭环：对 LLM 输出做质量评估，不达标则触发自动修正。
     *
     * <p>设计要点：
     * <ul>
     *   <li><b>选择性触发</b>：只有生成/方案/分析类任务才反思，简单查询跳过</li>
     *   <li><b>时间上限</b>：反思总耗时超过 {@link #reflexionMaxDurationMs} 就放弃，
     *       保留原答案，避免拖慢响应</li>
     *   <li><b>空值保护</b>：修正结果为空时保留原答案</li>
     *   <li><b>失败不影响主流程</b>：反思任何环节异常都捕获，返回原答案</li>
     * </ul>
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户原始消息（作为评估的 context）
     * @param response    LLM 生成的回答
     * @return 可能被修正后的回答，或原回答
     */
    private String applyReflexion(String sessionId, String userMessage, String response) {
        // 前置检查
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
            // 1. 评估
            EvaluationResult eval = evaluationService.evaluate(response, userMessage);
            log.info("会话 {} 反思评估: 总分={}, 通过={}",
                    sessionId, eval.getOverallScore(), eval.isPassed());

            // 2. 评估超时保护
            if (System.currentTimeMillis() - reflectStart > reflexionMaxDurationMs) {
                log.warn("会话 {} 评估耗时超过上限 {}ms，跳过修正",
                        sessionId, reflexionMaxDurationMs);
                return response;
            }

            // 3. 达标直接返回
            if (eval.isPassed() || eval.getOverallScore() >= scoreThreshold) {
                log.debug("会话 {} 质量达标（{}），无需修正",
                        sessionId, eval.getOverallScore());
                return response;
            }

            // 4. 不达标，触发修正
            log.info("会话 {} 质量未达标（{} < {}），触发自动修正",
                    sessionId, eval.getOverallScore(), scoreThreshold);

            var fixResult = autoFixerService.fix(response, userMessage, eval);

            // 5. 修正超时保护
            if (System.currentTimeMillis() - reflectStart > reflexionMaxDurationMs) {
                log.warn("会话 {} 修正耗时超过上限 {}ms，保留原答案",
                        sessionId, reflexionMaxDurationMs);
                return response;
            }

            // 6. 修正结果处理（含空值保护）
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
            // 反思失败不影响主流程，返回原答案
            log.warn("会话 {} 反思环节异常，保留原答案: {}", sessionId, e.getMessage());
            return response;
        } finally {
            log.debug("会话 {} 反思环节耗时 {}ms",
                    sessionId, System.currentTimeMillis() - reflectStart);
        }
    }

    /**
     * 判断任务是否值得触发反思。
     *
     * <p>反思会多调 1-2 次 LLM，所以只对"生成类/方案类/分析类"任务触发。
     * 简单查询（"找 XX 类"、"XX 有哪些方法"）不反思。
     */
    private boolean shouldReflect(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String lower = userMessage.toLowerCase();
        // 生成、方案、分析、设计、优化、评审类任务才反思
        return lower.contains("实现") || lower.contains("生成")
                || lower.contains("重构") || lower.contains("方案")
                || lower.contains("分析") || lower.contains("设计")
                || lower.contains("优化") || lower.contains("评审")
                || lower.contains("review") || lower.contains("检查");
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
     * 用 clear()+add() 而不是 set()，兼容 LangChain4j 1.11.0 以下版本。
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
     * 构建系统提示词：基础提示 + 本轮计划 + 长期记忆（放在 System 侧，不污染用户消息）。
     */
    private String buildSystemPrompt(String sessionId) {
        StringBuilder sb = new StringBuilder(BASE_SYSTEM_PROMPT);

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

    /**
     * 回收已过期/已删除会话占用的资源，防止无界增长。
     * 每 5 分钟执行一次；也作为内存缓存的溢出兜底。
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void evictStaleResources() {
        Set<String> alive = new HashSet<>(sessionManager.getAllSessionIds());

        // 记忆/上下文缓存：直接移除已不存在的会话
        memoryCache.keySet().removeIf(id -> !alive.contains(id));
        pendingMemoryContext.keySet().removeIf(id -> !alive.contains(id));
        pendingPlanContext.keySet().removeIf(id -> !alive.contains(id));

        // 工具调用计数：委托给 ToolCallGuard 清理
        toolCallGuard.evict(alive);

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

        log.debug("资源回收完成: memories={}, sessionSemaphores={}, toolCounters={}",
                memoryCache.size(), sessionSemaphores.size(), toolCallGuard.currentCount("__dummy__"));
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

    /**
     * 判断任务是否"复杂"到需要先走 Plan 子 Agent。
     * 委托给 SubAgentRoutingService，统一判定逻辑。
     */
    private boolean isComplex(String userMessage) {
        try {
            return subAgentRoutingService.isComplexTask(userMessage);
        } catch (Exception e) {
            log.warn("复杂度判断失败，按简单任务处理: {}", e.getMessage());
            return false;
        }
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
        pendingPlanContext.clear();
        sessionSemaphores.clear();
        toolCallGuard.clearAll();
        log.info("Agent 已重置");
    }

    public int getActiveSessionCount() {
        return sessionManager.getActiveSessionCount();
    }
}
package com.example.aiagentdemo.controller;

import com.example.aiagentdemo.data.AgentSession;
import com.example.aiagentdemo.data.CallRelation;
import com.example.aiagentdemo.data.EvaluationResult;
import com.example.aiagentdemo.data.RetrievedCode;
import com.example.aiagentdemo.service.*;
import com.example.aiagentdemo.service.subagent.SubAgentScheduler;
import com.example.aiagentdemo.tools.ProjectStructureTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/test")
public class TestController {

    @Autowired
    private CodeAgentLoopService agentLoopService;

    @Autowired
    private CodeRetrievalService retrievalService;

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private LongTermMemoryService longTermMemoryService;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private WorkingMemoryService workingMemoryService;

    @Autowired
    private SelfEvaluationService evaluationService;

    @Autowired
    private AutoFixerService autoFixerService;

    @Autowired
    private SubAgentScheduler subAgentScheduler;

    @Autowired
    private CallRelationService callRelationService;

    @Autowired
    private ProjectStructureTool projectStructureTool;

    // ============================================================
    // 1. Agent 主入口测试（最核心）
    // ============================================================

    /**
     * 基础对话（自动生成 sessionId）
     * POST /test/chat
     * body: { "message": "帮我搜索用户登录相关的代码" }
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        long start = System.currentTimeMillis();

        try {
            String response = agentLoopService.execute(message);

            return Map.of(
                    "success", true,
                    "response", response,
                    "costMs", System.currentTimeMillis() - start,
                    "activeSessions", agentLoopService.getActiveSessionCount()
            );
        } catch (Exception e) {
            log.error("chat 失败", e);
            return Map.of(
                    "success", false,
                    "error", e.getMessage(),
                    "costMs", System.currentTimeMillis() - start
            );
        }
    }

    /**
     * 带 sessionId 的对话（支持多轮）
     * POST /test/chat/session
     * body: { "sessionId": "session-abc123", "message": "继续分析" }
     */
    @PostMapping("/chat/session")
    public Map<String, Object> chatWithSession(@RequestBody Map<String, String> request) {
        String sessionId = request.get("sessionId");
        String message = request.get("message");

        if (sessionId == null || sessionId.isBlank()) {
            return Map.of("success", false, "error", "sessionId 不能为空");
        }

        long start = System.currentTimeMillis();
        try {
            String response = agentLoopService.execute(sessionId, message);

            // 返回会话状态
            AgentSession session = sessionManager.getSession(sessionId);
            int messageCount = session != null ? session.getMessages().size() : 0;

            return Map.of(
                    "success", true,
                    "sessionId", sessionId,
                    "response", response,
                    "messageCount", messageCount,
                    "costMs", System.currentTimeMillis() - start
            );
        } catch (Exception e) {
            log.error("chatWithSession 失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    // ============================================================
    // 2. 会话管理测试
    // ============================================================

    /**
     * 创建新会话
     * POST /test/session/create
     */
    @PostMapping("/session/create")
    public Map<String, Object> createSession() {
        AgentSession session = sessionManager.createNewSession();
        return Map.of(
                "success", true,
                "sessionId", session.getSessionId(),
                "messageCount", session.getMessages().size()
        );
    }

    /**
     * 获取会话详情
     * GET /test/session/{sessionId}
     */
    @GetMapping("/session/{sessionId}")
    public Map<String, Object> getSession(@PathVariable String sessionId) {
        AgentSession session = sessionManager.getSession(sessionId);
        if (session == null) {
            return Map.of("success", false, "error", "会话不存在");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("sessionId", sessionId);
        result.put("status", session.getStatus());
        result.put("messageCount", session.getMessages().size());
        result.put("messages", session.getMessages().stream()
                .map(m -> Map.of(
                        "type", m.type().name(),
                        "content", m.toString()
                ))
                .toList());
        result.put("context", session.getContext());
        return result;
    }

    /**
     * 获取所有活跃会话 ID
     * GET /test/session/list
     */
    @GetMapping("/session/list")
    public Map<String, Object> listSessions() {
        return Map.of(
                "success", true,
                "count", sessionManager.getActiveSessionCount(),
                "sessionIds", sessionManager.getAllSessionIds()
        );
    }

    /**
     * 删除指定会话
     * DELETE /test/session/{sessionId}
     */
    @DeleteMapping("/session/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId) {
        sessionManager.deleteSession(sessionId);
        return Map.of("success", true, "message", "会话已删除: " + sessionId);
    }

    /**
     * 清空所有会话
     * POST /test/session/clear
     */
    @PostMapping("/session/clear")
    public Map<String, Object> clearAllSessions() {
        int count = sessionManager.getActiveSessionCount();
        sessionManager.clearAllSessions();
        return Map.of("success", true, "message", "已清空 " + count + " 个会话");
    }

    // ============================================================
    // 3. 代码检索测试
    // ============================================================

    /**
     * 纯语义检索
     * POST /test/retrieval/semantic
     * body: { "query": "用户登录", "maxResults": 5, "projectName": "framework" }
     */
    @PostMapping("/retrieval/semantic")
    public Map<String, Object> semanticSearch(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        int maxResults = request.get("maxResults") != null
                ? ((Number) request.get("maxResults")).intValue() : 5;
        String projectName = (String) request.get("projectName");

        long start = System.currentTimeMillis();
        List<RetrievedCode> results = retrievalService.search(query, maxResults, projectName, 0.7);

        return Map.of(
                "success", true,
                "query", query,
                "count", results.size(),
                "costMs", System.currentTimeMillis() - start,
                "results", results.stream().map(this::formatCode).toList()
        );
    }

    /**
     * 混合检索（语义 + 关键词，RRF 融合）
     * POST /test/retrieval/hybrid
     * body: { "query": "用户登录", "maxResults": 5 }
     */
    @PostMapping("/retrieval/hybrid")
    public Map<String, Object> hybridSearch(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        int maxResults = request.get("maxResults") != null
                ? ((Number) request.get("maxResults")).intValue() : 5;
        String projectName = (String) request.get("projectName");

        long start = System.currentTimeMillis();
        List<RetrievedCode> results = hybridSearchService.hybridSearch(query, maxResults, projectName, 0.7);

        return Map.of(
                "success", true,
                "query", query,
                "count", results.size(),
                "costMs", System.currentTimeMillis() - start,
                "results", results.stream().map(this::formatCode).toList()
        );
    }

    /**
     * 按类名精确查找
     * GET /test/retrieval/class/{className}
     */
    @GetMapping("/retrieval/class/{className}")
    public Map<String, Object> findByClassName(
            @PathVariable String className,
            @RequestParam(required = false) String projectName) {
        List<RetrievedCode> results = retrievalService.findByClassName(className, projectName);
        return Map.of(
                "success", true,
                "className", className,
                "count", results.size(),
                "results", results.stream().map(this::formatCode).toList()
        );
    }

    /**
     * 查询 Milvus 集合记录数
     * GET /test/retrieval/count
     */
    @GetMapping("/retrieval/count")
    public Map<String, Object> getCollectionCount() {
        long count = retrievalService.getCollectionCount();
        return Map.of("success", true, "count", count);
    }

    /**
     * 列出某个项目的所有代码片段（调试用）
     * GET /test/retrieval/segments?projectName=framework&limit=20
     */
    @GetMapping("/retrieval/segments")
    public Map<String, Object> getAllSegments(
            @RequestParam(required = false) String projectName,
            @RequestParam(defaultValue = "20") int limit) {
        List<RetrievedCode> results = retrievalService.getAllSegments(projectName, limit);
        return Map.of(
                "success", true,
                "count", results.size(),
                "results", results.stream().map(this::formatCode).toList()
        );
    }

    // ============================================================
    // 4. 长期记忆测试
    // ============================================================

    /**
     * 手动存入一条记忆
     * POST /test/memory/store
     * body: { "sessionId": "s1", "role": "user", "content": "记住我喜欢用 Lombok" }
     */
    @PostMapping("/memory/store")
    public Map<String, Object> storeMemory(@RequestBody Map<String, String> request) {
        String sessionId = request.getOrDefault("sessionId", "manual-test");
        String role = request.getOrDefault("role", "user");
        String content = request.get("content");

        longTermMemoryService.storeMemory(sessionId, role, content);
        return Map.of("success", true, "message", "已存储");
    }

    /**
     * 检索相关记忆
     * POST /test/memory/retrieve
     * body: { "query": "用户偏好", "maxResults": 3 }
     */
    @PostMapping("/memory/retrieve")
    public Map<String, Object> retrieveMemory(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        int maxResults = request.get("maxResults") != null
                ? ((Number) request.get("maxResults")).intValue() : 3;

        var memories = longTermMemoryService.retrieveRelevantMemories(query, maxResults);

        return Map.of(
                "success", true,
                "query", query,
                "count", memories.size(),
                "memories", memories.stream().map(m -> Map.of(
                        "sessionId", m.getSessionId() != null ? m.getSessionId() : "",
                        "role", m.getRole() != null ? m.getRole() : "",
                        "content", m.getContent() != null ? m.getContent() : "",
                        "timestamp", m.getTimestamp() != null ? m.getTimestamp().toString() : ""
                )).toList()
        );
    }

    // ============================================================
    // 5. 工作记忆测试
    // ============================================================

    /**
     * 查看工作记忆
     * GET /test/working-memory/{sessionId}
     */
    @GetMapping("/working-memory/{sessionId}")
    public Map<String, Object> getWorkingMemory(@PathVariable String sessionId) {
        var memory = workingMemoryService.get(sessionId);
        if (memory == null) {
            return Map.of("success", false, "error", "工作记忆不存在");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("sessionId", memory.getSessionId());
        result.put("goal", memory.getGoal());
        result.put("currentPhase", memory.getCurrentPhase());
        result.put("totalSteps", memory.getTotalSteps());
        result.put("completedSteps", memory.getCompletedSteps());
        result.put("context", memory.getContext());
        return result;
    }

    // ============================================================
    // 6. 子 Agent 测试
    // ============================================================

    /**
     * 列出所有子 Agent 能力
     * GET /test/subagent/list
     */
    @GetMapping("/subagent/list")
    public Map<String, Object> listSubAgents() {
        return Map.of(
                "success", true,
                "agents", subAgentScheduler.getAgentNames(),
                "capabilities", subAgentScheduler.getAllCapabilities()
        );
    }

    /**
     * 直接委托给指定子 Agent
     * POST /test/subagent/delegate
     * body: { "agentName": "explore", "task": "搜索用户登录相关代码" }
     */
    @PostMapping("/subagent/delegate")
    public Map<String, Object> delegateSubAgent(@RequestBody Map<String, Object> request) {
        String agentName = (String) request.get("agentName");
        String task = (String) request.get("task");
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) request.getOrDefault("context", new HashMap<>());

        long start = System.currentTimeMillis();
        var result = subAgentScheduler.delegate(agentName, task, context);

        return Map.of(
                "success", result.isSuccess(),
                "agentName", agentName,
                "content", result.getContent() != null ? result.getContent() : "",
                "error", result.getErrorMessage() != null ? result.getErrorMessage() : "",
                "costMs", System.currentTimeMillis() - start
        );
    }

    // ============================================================
    // 7. 自我评估 & 自动修正测试
    // ============================================================

    /**
     * 评估一段代码/回答的质量
     * POST /test/evaluate
     * body: { "content": "public class A {}", "context": "要求实现用户管理" }
     */
    @PostMapping("/evaluate")
    public Map<String, Object> evaluate(@RequestBody Map<String, String> request) {
        String content = request.get("content");
        String context = request.getOrDefault("context", "");

        long start = System.currentTimeMillis();
        EvaluationResult result = evaluationService.evaluate(content, context);

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", true);
        resultMap.put("overallScore", result.getOverallScore());
        resultMap.put("passed", result.isPassed());
        resultMap.put("correctness", result.getCorrectness());
        resultMap.put("completeness", result.getCompleteness());
        resultMap.put("clarity", result.getClarity());
        resultMap.put("normativity", result.getNormativity());
        resultMap.put("usability", result.getUsability());
        resultMap.put("strengths", result.getStrengths());
        resultMap.put("weaknesses", result.getWeaknesses());
        resultMap.put("suggestions", result.getSuggestions());
        resultMap.put("report", evaluationService.formatReport(result));
        resultMap.put("costMs", System.currentTimeMillis() - start);
        return resultMap;
    }

    /**
     * 自动修正
     * POST /test/autofix
     * body: { "content": "有问题的代码", "context": "需求描述" }
     */
    @PostMapping("/autofix")
    public Map<String, Object> autofix(@RequestBody Map<String, String> request) {
        String content = request.get("content");
        String context = request.getOrDefault("context", "");

        // 先评估
        EvaluationResult eval = evaluationService.evaluate(content, context);
        // 再修正
        var fixResult = autoFixerService.fix(content, context, eval);

        return Map.of(
                "success", true,
                "passed", fixResult.isPassed(),
                "attempts", fixResult.getAttempts(),
                "message", fixResult.getMessage(),
                "finalContent", fixResult.getFinalContent(),
                "finalScore", fixResult.getFinalEvaluation().getOverallScore()
        );
    }

    // ============================================================
    // 8. 项目结构 & 调用关系测试
    // ============================================================

    /**
     * 查看所有项目关系
     * GET /test/project/relations
     */
    @GetMapping("/project/relations")
    public Map<String, Object> getProjectRelations() {
        String result = projectStructureTool.getProjectRelations();
        return Map.of("success", true, "content", result);
    }

    /**
     * 查看指定项目结构
     * GET /test/project/structure/{projectName}
     */
    @GetMapping("/project/structure/{projectName}")
    public Map<String, Object> getProjectStructure(@PathVariable String projectName) {
        String result = projectStructureTool.getProjectStructure(projectName);
        return Map.of("success", true, "projectName", projectName, "content", result);
    }

    /**
     * 查询谁调用了某个方法
     * GET /test/call/callers?className=XxxService&methodName=findById
     */
    @GetMapping("/call/callers")
    public Map<String, Object> getCallers(
            @RequestParam String className,
            @RequestParam String methodName) {
        List<CallRelation> relations = callRelationService.findCallers(className, methodName);
        return Map.of(
                "success", true,
                "className", className,
                "methodName", methodName,
                "count", relations.size(),
                "relations", relations.stream().map(r -> Map.of(
                        "caller", r.getCallerClass() + "." + r.getCallerMethod(),
                        "callerPackage", r.getCallerPackage() != null ? r.getCallerPackage() : "",
                        "filePath", r.getFilePath() != null ? r.getFilePath() : "",
                        "line", r.getCallerLine()
                )).toList()
        );
    }

    /**
     * 分析变更影响范围
     * GET /test/call/impact?className=XxxService&methodName=findById&depth=3
     */
    @GetMapping("/call/impact")
    public Map<String, Object> analyzeImpact(
            @RequestParam String className,
            @RequestParam String methodName,
            @RequestParam(defaultValue = "3") int depth) {
        String report = callRelationService.generateImpactReport(className, methodName, depth);
        var affected = callRelationService.analyzeImpact(className, methodName, depth);
        return Map.of(
                "success", true,
                "className", className,
                "methodName", methodName,
                "affectedCount", affected.size(),
                "affected", affected,
                "report", report
        );
    }

    // ============================================================
    // 9. 健康检查
    // ============================================================

    /**
     * 系统状态总览
     * GET /test/health
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("timestamp", System.currentTimeMillis());
        try {
            result.put("milvusCount", retrievalService.getCollectionCount());
        } catch (Exception e) {
            result.put("milvusCount", "ERROR: " + e.getMessage());
        }
        result.put("activeSessions", sessionManager.getActiveSessionCount());
        result.put("subAgents", subAgentScheduler.getAgentNames());
        return result;
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private Map<String, Object> formatCode(RetrievedCode code) {
        Map<String, Object> m = new HashMap<>();
        m.put("className", code.getClassName() != null ? code.getClassName() : "");
        m.put("methodName", code.getMethodName() != null ? code.getMethodName() : "");
        m.put("packageName", code.getPackageName() != null ? code.getPackageName() : "");
        m.put("projectName", code.getProjectName() != null ? code.getProjectName() : "");
        m.put("filePath", code.getFilePath() != null ? code.getFilePath() : "");
        m.put("segmentType", code.getSegmentType() != null ? code.getSegmentType() : "");
        m.put("similarity", code.getSimilarity());
        String content = code.getContent();
        if (content != null && content.length() > 500) {
            content = content.substring(0, 500) + "...(截断)";
        }
        m.put("content", content != null ? content : "");
        return m;
    }
}
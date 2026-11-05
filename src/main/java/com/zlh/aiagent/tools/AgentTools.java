package com.zlh.aiagent.tools;

import com.zlh.aiagent.data.RetrievalResult;
import com.zlh.aiagent.data.subagent.SubAgentResult;
import com.zlh.aiagent.service.CallRelationService;
import com.zlh.aiagent.service.CodeRetrievalService;
import com.zlh.aiagent.service.subagent.SubAgentScheduler;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class AgentTools {

    @Autowired
    private CodeRetrievalService retrievalService;

    @Autowired
    private ProjectStructureTool projectStructureTool;

    @Autowired
    private SubAgentScheduler subAgentScheduler;

    @Autowired
    private CallRelationService callRelationService;

    @Autowired
    private ToolCallGuard toolCallGuard;

    @Autowired
    private ToolCallTrace toolCallTrace;

    /** 本会话检索结果缓存：sourceId -> RetrievalResult，供 CodeAgentLoopService 提取 Citation
     * -- GETTER --
     * 获取引用缓存（供 CodeAgentLoopService 读取）
     */
    @Getter
    private final java.util.Map<String, com.zlh.aiagent.data.RetrievalResult> citationCache
            = new ConcurrentHashMap<>();

    /** 工具调用上限的统一提示语 */
    private static final String TOOL_LIMIT_MESSAGE =
            "[系统提示] 工具调用次数已达上限，请基于已有信息给出最终答案。";

    /**
     * 统一的前置检查：是否允许继续调用工具。
     * 超限返回提示语，调用方应直接返回该提示。
     */
    private String checkToolCall(String toolName) {
        String sessionId = ToolCallGuard.getCurrentSession();
        if (sessionId != null && !toolCallGuard.tryAcquire(sessionId,toolName)) {
            log.warn("工具 {} 被 ToolCallGuard 拦截（会话 {} 已达上限）", toolName, sessionId);
            return TOOL_LIMIT_MESSAGE;
        }
        return null;   // null 表示允许继续
    }

    /**
     * 工具1: 搜索代码
     */
    @Tool("在代码库中搜索相关代码片段。当需要找到某个类或方法时使用。")
    public String searchCode(
            @P("搜索关键词，如类名、方法名") String query) {
        String limit = checkToolCall("searchCode");
        if (limit != null) return limit;

        return traced("searchCode", "{\"query\":\"" + query + "\"}", () -> {
            log.info("Tool: searchCode, query={}", query);
            var results = retrievalService.search(query, 5, null, 0.5);
            if (results.isEmpty()) {
                return "未找到与 '" + query + "' 相关的代码。";
            }
            return retrievalService.formatAsPromptContext(results) + buildRefTag(results);
        });
    }

    /**
     * 工具2: 读取文件
     */
//    @Tool("读取指定路径的完整文件内容。当需要查看完整代码时使用。")
//    public String readFile(
//            @P("文件路径，如: framework/src/main/java/com/bf/.../SysUser.java") String path) {
//        String limit = checkToolCall("readFile");
//        if (limit != null) return limit;
//
//        log.info("Tool: readFile, path={}", path);
//        return readFileTool.readFile(path);
//    }

    /**
     * 工具3: 查看项目结构
     */
    @Tool("查看指定项目的包结构和类列表。当需要了解项目整体结构时使用。")
    public String getProjectStructure(
            @P("项目名称，如: framework, app, editor_manager等") String projectName) {
        String limit = checkToolCall("getProjectStructure");
        if (limit != null) return limit;

        return traced("getProjectStructure", "{\"projectName\":\"" + projectName + "\"}", () -> {
            log.info("Tool: getProjectStructure, project={}", projectName);
            if (projectName == null || projectName.isEmpty()) {
                return projectStructureTool.getProjectRelations();
            }
            return projectStructureTool.getProjectStructure(projectName);
        });
    }

    /**
     * 工具4: 查看类字段
     */
    @Tool("查看指定类的所有字段列表。当用户问'XX类有哪些字段'时使用。")
    public String getClassFields(
            @P("类名，如: SysUser") String className) {
        String limit = checkToolCall("getClassFields");
        if (limit != null) return limit;

        return traced("getClassFields", "{\"className\":\"" + className + "\"}", () -> {
            log.info("Tool: getClassFields, className={}", className);
            List<? extends RetrievalResult> results = retrievalService.search(className, 3, null, 0.5);
            if (results.isEmpty()) {
                return "未找到类: " + className;
            }

            for (RetrievalResult r : results) {
                String title = r.getTitle();
                if (title != null && (title.equals(className)
                        || title.toLowerCase().contains(className.toLowerCase()))) {
                    String content = r.getContent();
                    StringBuilder sb = new StringBuilder();
                    sb.append("类 ").append(className).append(" 的字段:\n");
                    sb.append("=".repeat(50)).append("\n");

                    Pattern fieldPattern = Pattern.compile(
                            "(?:private|protected|public)?\\s*(?:static)?\\s*(?:final)?\\s*(\\w+)\\s+(\\w+)\\s*[=;]"
                    );
                    Matcher matcher = fieldPattern.matcher(content);
                    int count = 0;
                    while (matcher.find() && count < 30) {
                        String type = matcher.group(1);
                        String name = matcher.group(2);
                        if (!name.startsWith("get") && !name.startsWith("set") && !name.contains("(")) {
                            sb.append("  ├─ ").append(type).append(" ").append(name).append("\n");
                            count++;
                        }
                    }
                    if (count == 0) {
                        sb.append("  └─ (未检测到字段)\n");
                    }
                    return sb + buildRefTag(results);
                }
            }
            return "未找到类: " + className;
        });
    }

    /**
     * 工具5: 查询调用者
     */
    @Tool("查询谁调用了指定的方法。当需要了解方法被哪些地方使用时使用。")
    public String getCallers(
            @P("类名") String className,
            @P("方法名") String methodName) {
        String limit = checkToolCall("getCallers");
        if (limit != null) return limit;

        String args = "{\"className\":\"" + className + "\",\"methodName\":\"" + methodName + "\"}";
        return traced("getCallers", args, () -> {
            log.info("Tool: getCallers, className={}, methodName={}", className, methodName);
            return callRelationService.generateImpactReport(null, className, methodName, 3);
        });
    }

    /**
     * 工具6: 委托子 Agent
     */
    @Tool("委托任务给子 Agent。当任务复杂、需要专门能力时使用。" +
            "可用子 Agent：explore（代码探索）、verify（质量验证）、plan（任务规划）")
    public String delegateToSubAgent(
            @P("子 Agent 名称：explore / verify / plan") String agentName,
            @P("任务描述，越具体越好") String task) {
        String limit = checkToolCall("delegateToSubAgent");
        if (limit != null) return limit;

        String args = "{\"agentName\":\"" + agentName + "\",\"task\":\"" + task + "\"}";
        return traced("delegateToSubAgent", args, () -> {
            log.info("Tool: delegateToSubAgent, agentName={}, task={}", agentName, task);
            if (!subAgentScheduler.hasAgent(agentName)) {
                return "子 Agent 不存在: " + agentName;
            }
            Map<String, Object> context = new HashMap<>();
            SubAgentResult result = subAgentScheduler.delegate(agentName, task, context);
            return result.isSuccess() ? result.getContent() : "执行失败: " + result.getErrorMessage();
        });
    }

    /** 清空引用缓存（每轮对话开始前调用） */
    public void clearCitationCache() {
        citationCache.clear();
    }

    /**
     * 从检索结果列表收集引用 ID，同时填充缓存，返回 {@code [ref:id1,id2]} 标记。
     *
     * @param results 检索结果列表，可为空
     * @return 形如 "\n[ref:abc,def]" 的标记；无结果时返回空串
     */
    private String buildRefTag(java.util.List<? extends com.zlh.aiagent.data.RetrievalResult> results) {
        if (results == null || results.isEmpty()) return "";
        Set<String> ids = new LinkedHashSet<>();
        for (com.zlh.aiagent.data.RetrievalResult r : results) {
            String id = r.getSourceId();
            if (id != null && !id.isBlank()) {
                citationCache.putIfAbsent(id, r);
                ids.add(id);
            }
        }
        return ids.isEmpty() ? "" : "\n[ref:" + String.join(",", ids) + "]";
    }

    /**
     * 统一包装：执行 action，记录 trace，返回结果。
     *
     * <p>所有 {@code @Tool} 方法都应该通过它执行，确保 trace 完整。
     *
     * @param toolName 工具名
     * @param arguments 参数（JSON 字符串）
     * @param action 实际执行逻辑
     * @return action 的返回值
     */
    private String traced(String toolName, String arguments, java.util.function.Supplier<String> action) {
        String sessionId = ToolCallGuard.getCurrentSession();
        long start = System.currentTimeMillis();
        boolean success = true;
        String result = null;
        try {
            result = action.get();
        } catch (Exception e) {
            success = false;
            result = "[工具异常] " + e.getMessage();
            throw e;
        } finally {
            long cost = System.currentTimeMillis() - start;
            if (sessionId != null) {
                String summary = result == null ? ""
                        : (result.length() > 200 ? result.substring(0, 200) + "..." : result);
                toolCallTrace.record(sessionId, ToolCallTrace.Record.builder()
                        .toolName(toolName)
                        .arguments(arguments)
                        .resultSummary(summary)
                        .success(success)
                        .costMs(cost)
                        .timestamp(java.time.LocalDateTime.now())
                        .build());
            }
            log.info("工具调用: session={}, tool={}, cost={}ms, success={}",
                    sessionId, toolName, cost, success);
        }
        return result;
    }

    /** 清空本会话的工具轨迹（每轮开始前调用） */
    public void clearTrace(String sessionId) {
        toolCallTrace.clear(sessionId);
    }
}
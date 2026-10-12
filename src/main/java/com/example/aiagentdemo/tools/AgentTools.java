package com.example.aiagentdemo.tools;

import com.example.aiagentdemo.data.subagent.SubAgentResult;
import com.example.aiagentdemo.service.CodeRetrievalService;
import com.example.aiagentdemo.service.subagent.SubAgentScheduler;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
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
    private ReadFileTool readFileTool;

    /**
     * 工具1: 搜索代码
     */
    @Tool("在代码库中搜索相关代码片段。当需要找到某个类或方法时使用。")
    public String searchCode(
            @P("搜索关键词，如类名、方法名") String query) {
        log.info("Tool: searchCode, query={}", query);
        var results = retrievalService.search(query, 5, null, 0.5);
        if (results.isEmpty()) {
            return "未找到与 '" + query + "' 相关的代码。";
        }
        return retrievalService.formatAsPromptContext(results);
    }

    /**
     * 工具2: 读取文件
     */
    @Tool("读取指定路径的完整文件内容。当需要查看完整代码时使用。")
    public String readFile(
            @P("文件路径，如: framework/src/main/java/com/bf/.../SysUser.java") String path) {
        log.info("Tool: readFile, path={}", path);
        return readFileTool.readFile(path);
    }

    /**
     * 工具3: 查看项目结构
     */
    @Tool("查看指定项目的包结构和类列表。当需要了解项目整体结构时使用。")
    public String getProjectStructure(
            @P("项目名称，如: framework, app, editor_manager等") String projectName) {
        log.info("Tool: getProjectStructure, project={}", projectName);
        if (projectName == null || projectName.isEmpty()) {
            return projectStructureTool.getProjectRelations();
        }
        return projectStructureTool.getProjectStructure(projectName);
    }

    /**
     * 工具4: 查看类字段
     */
    @Tool("查看指定类的所有字段列表。当用户问'XX类有哪些字段'时使用。")
    public String getClassFields(
            @P("类名，如: SysUser") String className) {
        log.info("Tool: getClassFields, className={}", className);
        // 先搜索类位置
        var results = retrievalService.search(className, 3, null, 0.5);
        if (results.isEmpty()) {
            return "未找到类: " + className;
        }

        // 找到匹配的类
        for (var code : results) {
            if (code.getClassName().equals(className) ||
                    code.getClassName().toLowerCase().contains(className.toLowerCase())) {
                // 提取字段信息
                String content = code.getContent();
                StringBuilder sb = new StringBuilder();
                sb.append("📋 类 ").append(className).append(" 的字段:\n");
                sb.append("=".repeat(50)).append("\n");

                // 从 content 中提取字段（简单实现）
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
                return sb.toString();
            }
        }
        return "未找到类: " + className;
    }

    /**
     * 工具5: 查询调用者
     */
    @Tool("查询谁调用了指定的方法。当需要了解方法被哪些地方使用时使用。")
    public String getCallers(
            @P("类名") String className,
            @P("方法名") String methodName) {
        log.info("Tool: getCallers, className={}, methodName={}", className, methodName);
        // 这里需要注入 CallRelationService
        // 暂时返回提示
        return "调用链查询功能: " + className + "." + methodName + "()\n" +
                "(需要 CallRelationService 集成)";
    }

    @Tool("委托任务给子 Agent。当任务复杂、需要专门能力时使用。可用子 Agent：explore（代码探索）、verify（质量验证）")
    public String delegateToSubAgent(
            @P("子 Agent 名称：explore / verify") String agentName,
            @P("任务描述，越具体越好") String task) {
        log.info("Tool: delegateToSubAgent, agentName={}, task={}", agentName, task);
        if (!subAgentScheduler.hasAgent(agentName)) {
            return "子 Agent 不存在: " + agentName;
        }
        Map<String, Object> context = new HashMap<>();
        SubAgentResult result = subAgentScheduler.delegate(agentName, task, context);
        return result.isSuccess() ? result.getContent() : "执行失败: " + result.getErrorMessage();
    }
}

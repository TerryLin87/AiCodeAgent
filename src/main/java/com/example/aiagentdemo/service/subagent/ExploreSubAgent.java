package com.example.aiagentdemo.service.subagent;

import com.example.aiagentdemo.data.CallRelation;
import com.example.aiagentdemo.data.RetrievedCode;
import com.example.aiagentdemo.data.subagent.SubAgentResult;
import com.example.aiagentdemo.instances.subagent.ISubAgent;
import com.example.aiagentdemo.service.CallRelationService;
import com.example.aiagentdemo.service.CodeRetrievalService;
import com.example.aiagentdemo.tools.ProjectStructureTool;
import com.example.aiagentdemo.tools.newtools.FileSystemTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 探索子 Agent
 * 负责：搜索代码、读取文件、查看项目结构、分析调用关系
 */
@Slf4j
@Component
public class ExploreSubAgent implements ISubAgent {

    @Autowired
    private CodeRetrievalService retrievalService;

    @Autowired
    private FileSystemTools fileSystemTools;

    @Autowired
    private ProjectStructureTool projectStructureTool;

    @Autowired
    private CallRelationService callRelationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "explore";
    }

    @Override
    public String getCapability() {
        return """
                代码探索专家 - 当需要探索代码库时使用我：
                - 搜索代码片段（支持语义搜索 + 关键词搜索）
                - 读取文件内容
                - 查看项目目录结构
                - 查询方法调用关系
                - 查看类的字段和方法签名
                - 分析变更影响范围
                """;
    }

    @Override
    public boolean canHandle(String task){
        // 判断是否应该由 ExploreSubAgent 处理
        String lower = task.toLowerCase();
        return lower.contains("搜索") || lower.contains("查找") ||
                lower.contains("读取") || lower.contains("查看") ||
                lower.contains("结构") || lower.contains("目录") ||
                lower.contains("调用") || lower.contains("依赖") ||
                lower.contains("影响") || lower.contains("字段") ||
                lower.contains("文件") || lower.contains("代码") ||
                lower.contains("项目");
    }

    @Override
    public SubAgentResult execute(String task,Map<String,Object> context){
        log.info("ExploreSubAgent 执行任务:{}",task);
        long startTime = System.currentTimeMillis();

        try{
            // 1.先用LLM 或规则识别任务类型和参数
            TaskIntent intent = parseTaskIntent(task,context);

            // 2.执行具体任务
            String result = switch (intent.getType()){
                case "search" -> doSearch(intent);
                case "read_file" -> doReadFile(intent);
                case "structure" -> doGetStructure(intent);
                case "callers" -> doGetCallers(intent);
                case "class_info" -> doGetClassInfo(intent);
                case "impact" -> doImpactAnalysis(intent);
                default -> "未识别的任务类型: " + intent.getType();
            };

            return SubAgentResult.success(result,System.currentTimeMillis() - startTime);

        }catch (Exception e){
            log.error("ExoloreSubAgent 执行失败",e);
            return SubAgentResult.failure("执行失败："+e.getMessage(),System.currentTimeMillis() - startTime);
        }
    }

    //region 任务识别

    /**
     * 解析任务意图（这里用简单规则，实际可以用LLM做更精准的解析）
     */
    private TaskIntent parseTaskIntent(String task,Map<String,Object> context){
        String lower = task.toLowerCase();
        TaskIntent intent = new TaskIntent();
        intent.setContext(context);

        if(lower.contains("搜索") || lower.contains("查找")){
            intent.setType("search");
            intent.setQuery(extraQuery(task));
        } else if(lower.contains("读取") || lower.contains("查看文件")){
            intent.setType("read_file");
            intent.setPath((String)context.getOrDefault("path",extractPath(task)));
        } else if (lower.contains("结构") || lower.contains("目录")){
            intent.setType("structure");
            intent.setProject((String)context.getOrDefault("projectName",extractPath(task)));
        } else if (lower.contains("调用") || lower.contains("依赖")) {
            intent.setType("callers");
            String[] parts = extractClassAndMethod(task);
            intent.setClassName(parts[0]);
            intent.setMethodName(parts[1]);
        } else if (lower.contains("字段") || lower.contains("类信息")) {
            intent.setType("class_info");
            intent.setClassName(extractClassName(task));
        } else if (lower.contains("影响")) {
            intent.setType("impact");
            String[] parts = extractClassAndMethod(task);
            intent.setClassName(parts[0]);
            intent.setMethodName(parts[1]);
        } else {
            // 默认搜索
            intent.setType("search");
            intent.setQuery(task);
        }

        return intent;
    }

    //endregion

    //region 执行任务

    private String doSearch(TaskIntent intent){
        String query = intent.getQuery();
        log.info("探索子agent搜索：{}",query);
        List<RetrievedCode> results = retrievalService.hybridSearch(query,10);
        if(CollectionUtils.isEmpty(results)){
            return "未找到匹配的代码";
        }
        return formatSearchResults(results);
    }

    private String doReadFile(TaskIntent intent){
        String path = intent.getPath();
        log.info("探索子agent读取文件：{}",path);
        return fileSystemTools.readFile(path);
    }

    private String doGetStructure(TaskIntent intent) {
        String project = intent.getProject();
        log.info("探索子 Agent 查看结构: {}", project != null ? project : "全部");
        if (project != null && !project.isEmpty()) {
            return projectStructureTool.getProjectStructure(project);
        }
        return projectStructureTool.getProjectRelations();
    }

    private String doGetCallers(TaskIntent intent) {
        String className = intent.getClassName();
        String methodName = intent.getMethodName();
        log.info("探索子 Agent 查询调用者: {}.{}", className, methodName);
        // 这里可以复用 CodeSearchTools 的逻辑，或直接调用 CodeRetrievalService
        return queryCallers(className, methodName);
    }

    private String doGetClassInfo(TaskIntent intent) {
        String className = intent.getClassName();
        log.info("探索子 Agent 查询类信息: {}", className);
        return queryClassInfo(className);
    }

    private String doImpactAnalysis(TaskIntent intent) {
        String className = intent.getClassName();
        String methodName = intent.getMethodName();
        log.info("探索子 Agent 影响分析: {}.{}", className, methodName);
        return analyzeImpact(className, methodName);
    }

    //endregion

    //region 简单解析工具

    private String extraQuery(String task){
        // 从任务描述中提取关键词
        String[] stopWords = {"搜索", "查找", "帮我", "看看", "代码", "相关"};
        String result = task;
        for (String word : stopWords) {
            result = result.replace(word, "");
        }
        return result.trim();
    }

    private String extractPath(String task) {
        // 简单提取文件路径
        return "";
    }

    private String extractProject(String task) {
        // 提取项目名
        return null;
    }

    private String extractClassName(String task) {
        // 提取类名
        return "";
    }

    private String[] extractClassAndMethod(String task) {
        // 提取类名和方法名
        return new String[]{"", ""};
    }

    //endregion

    //region 辅助工具
    private String formatSearchResults(List<RetrievedCode> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("搜索结果（共 ").append(results.size()).append(" 条）:\n");
        sb.append("=".repeat(50)).append("\n");
        for (int i = 0; i < Math.min(results.size(), 10); i++) {
            RetrievedCode code = results.get(i);
            sb.append(i + 1).append(". ")
                    .append(code.getClassName());
            if (code.getMethodName() != null && !code.getMethodName().isEmpty()) {
                sb.append(".").append(code.getMethodName()).append("()");
            }
            sb.append(" (相似度: ").append(String.format("%.2f", code.getSimilarity())).append(")\n");
            sb.append("   项目: ").append(code.getProjectName()).append("\n");
            // 只显示前 300 字符的代码片段
            String content = code.getContent();
            if (content.length() > 300) {
                content = content.substring(0, 300) + "...";
            }
            sb.append("   ").append(content.replace("\n", "\n   ")).append("\n\n");
        }
        return sb.toString();
    }

    private String queryCallers(String className, String methodName) {
        List<CallRelation> callers = callRelationService.findCallers(className, methodName);
        if (callers.isEmpty()) {
            return "未发现 " + className + "." + methodName + "() 被调用，修改安全。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("发现 ").append(callers.size()).append(" 处调用:\n");
        for (CallRelation r : callers) {
            sb.append("  - ").append(r.getCallerProject()).append(":")
                    .append(r.getCallerClass()).append(".").append(r.getCallerMethod())
                    .append("()  [").append(r.getFilePath()).append(":").append(r.getCallerLine()).append("]\n");
        }
        return sb.toString();
    }

    private String queryClassInfo(String className) {
        List<RetrievedCode> results = retrievalService.hybridSearch(className, 5, null, 0.4);
        if (CollectionUtils.isEmpty(results)) {
            return "未找到类: " + className;
        }
        // 格式化输出类信息...
        return "类信息: " + className + "（共 " + results.size() + " 条相关记录）";
    }

    private String analyzeImpact(String className, String methodName) {
        // 复用 CodeRetrievalService 或 CallRelationService 的分析逻辑
        return "影响范围分析: " + className + "." + methodName + "()\n" +
                "（需要调用 CallRelationService 或 CodeRetrievalService）";
    }
    //endregion

    private static class TaskIntent {
        private String type;
        private String query;
        private String path;
        private String project;
        private String className;
        private String methodName;
        private Map<String, Object> context;

        // Getters and Setters...
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getProject() { return project; }
        public void setProject(String project) { this.project = project; }
        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }
        public Map<String, Object> getContext() { return context; }
        public void setContext(Map<String, Object> context) { this.context = context; }
    }
}

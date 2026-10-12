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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 探索子 Agent。
 * 负责：搜索代码、读取文件、查看项目结构、分析调用关系。
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

    // ==================== 正则 ====================

    // 文件路径：可带盘符 / 目录前缀，必须带已知扩展名
    private static final Pattern PATH_PATTERN = Pattern.compile(
            "((?:[A-Za-z]:)?[\\w\\-./\\\\]*[\\\\/])?[\\w\\-]+\\.(?:java|xml|ya?ml|properties|json|md|txt|sql|gradle|kt)");

    // 全限定类名：com.example.demo.UserService
    private static final Pattern FQCN_PATTERN = Pattern.compile(
            "((?:[a-z][a-zA-Z0-9_]*\\.)+[A-Z][A-Za-z0-9_]*)");

    // 常见类名：大驼峰
    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "\\b([A-Z][A-Za-z0-9_]{2,})\\b");

    // 类名.方法名 / 类名#方法名
    private static final Pattern CLASS_METHOD_PATTERN = Pattern.compile(
            "([A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*)\\s*[.#．]\\s*([a-z][A-Za-z0-9_]*)");

    // "XxxService 的 yyy 方法" 形式
    private static final Pattern CLASS_METHOD_CN_PATTERN = Pattern.compile(
            "([A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*)\\s*(?:的|中的|中)?\\s*([a-z][A-Za-z0-9_]*)\\s*(?:方法|函数)");

    // 项目名：xxx项目 / xxx project
    private static final Pattern PROJECT_PATTERN = Pattern.compile(
            "([A-Za-z0-9_\\-]{2,})\\s*(?:项目|project)", Pattern.CASE_INSENSITIVE);

    // 前导意图词（只剥离开头的，不动正文）
    private static final List<String> LEADING_INTENT_WORDS = Arrays.asList(
            "帮我", "请", "麻烦", "帮忙", "搜索一下", "搜索", "查找一下", "查找", "检索一下", "检索",
            "找一下", "找", "查一下", "查", "看看", "看一下", "查看");

    // 不是类名的大驼峰词
    private static final Set<String> CLASS_STOP_WORDS = new HashSet<>(Arrays.asList(
            "Java", "Spring", "SpringBoot", "Maven", "Gradle", "JSON", "XML", "SQL", "HTTP", "HTTPS",
            "API", "URL", "UUID", "DTO", "VO", "DAO", "IOC", "AOP", "JVM", "GC", "IDE", "Redis",
            "MySQL", "Milvus", "Lucene", "Token", "Agent", "RAG", "MCP", "LLM", "OK", "ID"));

    // ==================== ISubAgent ====================

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
    public boolean canHandle(String task) {
        // 判断是否应该由 ExploreSubAgent 处理
        String lower = task.toLowerCase();
        return lower.contains("搜索") || lower.contains("查找")
                || lower.contains("读取") || lower.contains("查看")
                || lower.contains("结构") || lower.contains("目录")
                || lower.contains("调用") || lower.contains("依赖")
                || lower.contains("影响") || lower.contains("字段")
                || lower.contains("文件") || lower.contains("代码")
                || lower.contains("项目");
    }

    @Override
    public SubAgentResult execute(String task, Map<String, Object> context) {
        log.info("ExploreSubAgent 执行任务: {}", task);
        long startTime = System.currentTimeMillis();
        try {
            TaskIntent intent = parseTaskIntent(task, context);
            String result = switch (intent.getType()) {
                case "search" -> doSearch(intent);
                case "read_file" -> doReadFile(intent);
                case "structure" -> doGetStructure(intent);
                case "callers" -> doGetCallers(intent);
                case "class_info" -> doGetClassInfo(intent);
                case "impact" -> doImpactAnalysis(intent);
                default -> "未识别的任务类型: " + intent.getType();
            };
            return SubAgentResult.success(result, System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            // 修正原日志拼写错误 Exolore -> Explore
            log.error("ExploreSubAgent 执行失败", e);
            return SubAgentResult.failure("执行失败：" + e.getMessage(),
                    System.currentTimeMillis() - startTime);
        }
    }

    //region 任务识别

    /**
     * 解析任务意图（规则实现，阶段 0 Day3-4 可升级为 LLM 结构化解析）。
     */
    private TaskIntent parseTaskIntent(String task, Map<String, Object> context) {
        String lower = task.toLowerCase();
        TaskIntent intent = new TaskIntent();
        intent.setContext(context);

        String pathInTask = extractPath(task);

        if (lower.contains("搜索") || lower.contains("查找")) {
            intent.setType("search");
            intent.setQuery(extraQuery(task));
        } else if (lower.contains("读取") || lower.contains("查看文件") || lower.contains("打开")
                || !pathInTask.isEmpty()) {
            intent.setType("read_file");
            intent.setPath((String) context.getOrDefault("path", pathInTask));
        } else if (lower.contains("结构") || lower.contains("目录")) {
            intent.setType("structure");
            // 修复：原来误用 extractPath(task) 取项目名
            intent.setProject((String) context.getOrDefault("projectName", extractProject(task)));
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
            intent.setType("search");
            intent.setQuery(task);
        }
        return intent;
    }

    //endregion

    //region 执行任务

    private String doSearch(TaskIntent intent) {
        String query = intent.getQuery();
        log.info("探索子agent搜索：{}", query);
        List<RetrievedCode> results = retrievalService.hybridSearch(query, 10);
        if (CollectionUtils.isEmpty(results)) {
            return "未找到匹配的代码";
        }
        return formatSearchResults(results);
    }

    private String doReadFile(TaskIntent intent) {
        String path = intent.getPath();
        log.info("探索子agent读取文件：{}", path);
        if (path == null || path.isBlank()) {
            return "未能从任务描述中识别出文件路径。请在任务中给出明确路径（如 src/main/java/.../UserService.java），"
                    + "或在 context 中传入 path。";
        }
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
        if (className == null || className.isBlank()) {
            return "未能识别类名，请提供形如 \"UserService.login\" 或 \"UserService#login\" 的目标。";
        }
        if (methodName == null || methodName.isBlank()) {
            return "已识别类名 " + className + "，但未识别方法名，请补充（例：UserService.login）。";
        }
        return queryCallers(className, methodName);
    }

    private String doGetClassInfo(TaskIntent intent) {
        String className = intent.getClassName();
        log.info("探索子 Agent 查询类信息: {}", className);
        if (className == null || className.isBlank()) {
            return "未能识别类名。";
        }
        return queryClassInfo(className);
    }

    private String doImpactAnalysis(TaskIntent intent) {
        String className = intent.getClassName();
        String methodName = intent.getMethodName();
        log.info("探索子 Agent 影响分析: {}.{}", className, methodName);
        if (className == null || className.isBlank()) {
            return "未能识别类名，无法做影响分析。请提供形如 \"UserService.login\" 的目标。";
        }
        if (methodName == null || methodName.isBlank()) {
            return "已识别类名 " + className + "，但未识别方法名，无法做影响分析。";
        }
        // 修复：原来只返回占位文案
        return callRelationService.generateImpactReport(null, className, methodName, 3);
    }

    //endregion

    //region 参数解析（Day1-2 补全）

    /**
     * 剥离前导意图词，保留真正的检索关键词。
     * <p>修复：原实现对 "搜索/查找/帮我/看看/代码/相关" 做全文 replace，
     * 会把"代码生成器"误伤成"生成器"，甚至把查询删空。
     */
    private String extraQuery(String task) {
        if (task == null) {
            return "";
        }
        String q = task.trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String word : LEADING_INTENT_WORDS) {
                if (q.startsWith(word)) {
                    q = q.substring(word.length()).trim();
                    changed = true;
                }
            }
        }
        // 去掉尾部语气词/标点
        q = q.replaceAll("[，。！？!?,;；\\s]+$", "").trim();
        return q.isEmpty() ? task.trim() : q;
    }

    /**
     * 提取文件路径。
     */
    private String extractPath(String task) {
        if (task == null || task.isBlank()) {
            return "";
        }
        Matcher matcher = PATH_PATTERN.matcher(task);
        if (matcher.find()) {
            return matcher.group();
        }
        return "";
    }

    /**
     * 提取项目名（"xxx项目" / "xxx project"）。
     */
    private String extractProject(String task) {
        if (task == null || task.isBlank()) {
            return null;
        }
        Matcher matcher = PROJECT_PATTERN.matcher(task);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 提取类名：优先全限定名，其次大驼峰（过滤常见非类名词）。
     */
    private String extractClassName(String task) {
        if (task == null || task.isBlank()) {
            return "";
        }
        Matcher fqcn = FQCN_PATTERN.matcher(task);
        if (fqcn.find()) {
            return fqcn.group(1);
        }
        Matcher cls = CLASS_PATTERN.matcher(task);
        while (cls.find()) {
            String candidate = cls.group(1);
            if (!CLASS_STOP_WORDS.contains(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    /**
     * 提取类名与方法名，返回长度为 2 的数组（取不到时为空串）。
     * 支持：UserService.login / UserService#login / UserService 的 login 方法
     */
    private String[] extractClassAndMethod(String task) {
        if (task == null || task.isBlank()) {
            return new String[]{"", ""};
        }
        Matcher m = CLASS_METHOD_PATTERN.matcher(task);
        if (m.find()) {
            return new String[]{m.group(1), m.group(2)};
        }
        Matcher cn = CLASS_METHOD_CN_PATTERN.matcher(task);
        if (cn.find()) {
            return new String[]{cn.group(1), cn.group(2)};
        }
        return new String[]{extractClassName(task), ""};
    }

    //endregion

    //region 辅助工具

    private String formatSearchResults(List<RetrievedCode> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("搜索结果（共 ").append(results.size()).append(" 条）:\n");
        sb.append("=".repeat(50)).append("\n");
        for (int i = 0; i < Math.min(results.size(), 10); i++) {
            RetrievedCode code = results.get(i);
            sb.append(i + 1).append(". ").append(code.getClassName());
            if (code.getMethodName() != null && !code.getMethodName().isEmpty()) {
                sb.append(".").append(code.getMethodName()).append("()");
            }
            sb.append(" (相似度: ").append(String.format("%.2f", code.getSimilarity())).append(")\n");
            sb.append("   项目: ").append(code.getProjectName()).append("\n");
            String content = code.getContent();
            if (content != null && content.length() > 300) {
                content = content.substring(0, 300) + "...";
            }
            sb.append("   ").append(content == null ? "" : content.replace("\n", "\n   ")).append("\n\n");
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
        return "类信息: " + className + "（共 " + results.size() + " 条相关记录）";
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

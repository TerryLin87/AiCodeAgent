package com.example.aiagentdemo.tools.newtools;

import com.example.aiagentdemo.data.RetrievedCode;
import com.example.aiagentdemo.instances.AgentTool;
import com.example.aiagentdemo.instances.ToolParam;
import com.example.aiagentdemo.service.CodeRetrievalService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CodeSearchTools {
    @Autowired
    private CodeRetrievalService codeRetrievalService;

    // ==================== 基础搜索工具 ====================

    @AgentTool(
            name = "searchCode",
            description = "在代码库中搜索相关代码片段，支持自然语言描述和关键词搜索",
            readOnly = true,
            timeout = 20           
    )
    public String searchCode(
            @ToolParam(
                    name = "query",
                    description =  "搜索关键词或需求描述",
                    required = true,
                    example = "用户登录 Service"
            ) String query) {
        return searchCode(query, null, null);
    }

    @AgentTool(
            name = "searchCode",
            description = "在指定项目中搜索代码，可限制返回数量",
            readOnly = true,
            timeout = 20
    )
    public String searchCode(
            @ToolParam(
                    name = "query",
                    description =  "搜索关键词或需求描述",
                    example = "用户登录 Service"
            ) String query,
            @ToolParam(
                    name = "projectName",
                    description =  "项目名称",
                    example = "framework"
            )String projectName,
            @ToolParam(
                    name = "maxResults",
                    description =  "返回结果的最大数量，默认为5",
                    required = false,
                    defaultValue = "5"
            ) Integer maxResults) {

        int limit = maxResults != null && maxResults > 0 ? maxResults : 5;

        try {
            List<RetrievedCode> results = codeRetrievalService.hybridSearch(query, limit, projectName, 0.3);

            if (results.isEmpty()) {
                return """
                        未找到匹配的代码
                        
                        建议:
                          1. 尝试使用更宽泛的关键词
                          2. 检查项目名称是否正确（可用 getProjectRelations 查看所有项目）
                          3. 如果确认存在，可能需要先执行代码索引""";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("搜索结果: ").append(query).append("\n");
            if (projectName != null && !projectName.isEmpty()) {
                sb.append("限定项目: ").append(projectName).append("\n");
            }
            sb.append("找到 ").append(results.size()).append(" 条结果\n");
            sb.append("=".repeat(60)).append("\n\n");

            for (int i = 0; i < results.size(); i++) {
                RetrievedCode code = results.get(i);
                appendCodeResult(sb, i + 1, code);
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("搜索失败", e);
            return "搜索失败: " + e.getMessage();
        }
    }

    // ==================== 类相关查询 ====================

    @Tool("获取指定类的完整信息，包括包名、字段和方法签名")
    public String getClassInfo(
            @P("完整类名，如 com.example.UserService 或 SysUser") String className) {

        try {
            // 按类名精确搜索
            List<RetrievedCode> results = codeRetrievalService.search(className, 20, null, 0.5);

            if (results.isEmpty()) {
                return "未找到类: " + className + "\n\n" +
                        "建议:\n" +
                        "  1. 检查类名是否正确\n" +
                        "  2. 使用 searchCode 先搜索确认存在性";
            }

            // 过滤出类级别的结果
            List<RetrievedCode> classResults = results.stream()
                    .filter(c -> c.getMethodName() == null || c.getMethodName().isEmpty())
                    .limit(5)
                    .toList();

            if (classResults.isEmpty()) {
                return "未找到类的定义，但找到了方法级别的相关代码，可尝试搜索具体方法名";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("类信息: ").append(className).append("\n");
            sb.append("=".repeat(60)).append("\n\n");

            for (RetrievedCode code : classResults) {
                sb.append("项目: ").append(code.getProjectName()).append("\n");
                sb.append("包: ").append(code.getPackageName()).append("\n");
                sb.append("文件: ").append(code.getFilePath()).append("\n");
                sb.append("相似度: ").append(String.format("%.2f", code.getSimilarity())).append("\n\n");

                // 显示代码内容（截断）
                String content = code.getContent();
                if (content.length() > 1500) {
                    content = content.substring(0, 1500) + "\n... (内容过长，已截断)";
                }
                sb.append("```java\n").append(content).append("\n```\n");
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("获取类信息失败", e);
            return "获取类信息失败: " + e.getMessage();
        }
    }

    @AgentTool(
            name = "getClassFields",
            description = "查看指定类的所有字段和成员变量，可指定项目名称",
            readOnly = true,
            timeout = 30
    )
    public String getClassFields(
            @ToolParam(
                    name = "className",
                    description = "类名，如 SysUserService 或 com.example.UserService",
                    required = true,
                    example = "SysUserService"
            ) String className,
            @ToolParam(
                    name = "projectName",
                    description = "项目名称，可选。如 framework、app、editor_manager。不填则搜索所有项目",
                    required = false,
                    defaultValue = ""
            ) String projectName
    ){

        try {
            List<RetrievedCode> results = codeRetrievalService.search(className, 10, projectName, 0.4);

            if (results.isEmpty()) {
                return "未找到类: " + className;
            }

            // 找类定义（优先）
            RetrievedCode classDef = results.stream()
                    .filter(c -> c.getMethodName() == null)
                    .findFirst()
                    .orElse(results.get(0));

            String content = classDef.getContent();

            // 尝试从内容中提取字段信息（简单解析）
            StringBuilder sb = new StringBuilder();
            sb.append("类字段信息: ").append(className).append("\n");
            sb.append("=".repeat(60)).append("\n");
            sb.append("项目: ").append(classDef.getProjectName()).append("\n");
            sb.append("包: ").append(classDef.getPackageName()).append("\n\n");

            // 简单提取字段（实际可以用 JavaParser 做更精确的解析）
            String[] lines = content.split("\n");
            boolean inClass = false;
            int fieldCount = 0;

            sb.append("字段列表:\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.contains("class " + className) || trimmed.contains("class " + className + "<")) {
                    inClass = true;
                    continue;
                }
                if (inClass && trimmed.contains("}")) {
                    break;
                }
                if (inClass) {
                    // 简单的字段匹配：包含 private/protected/public + 类型 + 变量名
                    if (trimmed.matches(".*(private|protected|public)\\s+\\w+\\s+\\w+.*;.*")) {
                        sb.append("  - ").append(trimmed.replaceAll(";.*", ";")).append("\n");
                        fieldCount++;
                    }
                }
            }

            if (fieldCount == 0) {
                sb.append("  (未解析到字段，可能使用了 Lombok 或字段定义在父类中)\n");
                sb.append("\n可尝试:\n");
                sb.append("  1. 查看类的父类: 使用 getClassHierarchy\n");
                sb.append("  2. 搜索包含 @Data 或 @Getter 的代码\n");
            } else {
                sb.append("\n共 ").append(fieldCount).append(" 个字段\n");
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("获取类字段失败", e);
            return "获取类字段失败: " + e.getMessage();
        }
    }

    // ==================== 方法调用链分析 ====================

    @AgentTool(
            name = "getCallers",
            description = "查询谁调用了指定的方法，用于分析修改影响范围",
            readOnly = true,
            timeout = 30,
            visible = true,
            requireConfirm = false
    )
    public String getCallers(
            @ToolParam(
                    name = "className",
                    description = "类名，如 SysUserService 或 com.example.SysUserService",
                    required = true,
                    example = "SysUserService"
            ) String className,
            @ToolParam(
                    name = "methodName",
                    description = "方法名，如 findById、saveUser",
                    required = true,
                    example = "findById"
            ) String methodName
    ) {

        try {
            // 从检索服务获取调用关系
            List<RetrievedCode> results = codeRetrievalService.search(
                    "调用 " + className + "." + methodName, 20, null, 0.2
            );

            StringBuilder sb = new StringBuilder();
            sb.append("调用关系分析\n");
            sb.append("=".repeat(60)).append("\n");
            sb.append("被调用方法: ").append(className).append(".").append(methodName).append("()\n\n");

            if (results.isEmpty()) {
                sb.append("未发现调用此方法的地方，修改安全。\n");
                return sb.toString();
            }

            sb.append("发现 ").append(results.size()).append(" 处相关引用:\n\n");

            for (int i = 0; i < Math.min(results.size(), 15); i++) {
                RetrievedCode code = results.get(i);
                sb.append("  ").append(i + 1).append(". ");
                sb.append(code.getProjectName());
                sb.append(" → ").append(code.getClassName());
                if (code.getMethodName() != null && !code.getMethodName().isEmpty()) {
                    sb.append(".").append(code.getMethodName());
                }
                sb.append("()\n");
                sb.append("      ").append(code.getFilePath()).append("\n");
                sb.append("      相似度: ").append(String.format("%.2f", code.getSimilarity())).append("\n");
            }

            if (results.size() > 15) {
                sb.append("\n  ... 还有 ").append(results.size() - 15).append(" 处引用\n");
            }

            sb.append("\n建议:\n");
            sb.append("  1. 修改此方法前，确认以上调用方是否兼容\n");
            sb.append("  2. 建议先修复所有调用方，再删除旧方法\n");

            return sb.toString();

        } catch (Exception e) {
            log.error("获取调用关系失败", e);
            return "获取调用关系失败: " + e.getMessage();
        }
    }

    // ==================== 辅助方法 ====================

    private void appendCodeResult(StringBuilder sb, int index, RetrievedCode code) {
        sb.append("【").append(index).append("】");
        sb.append(code.getClassName());
        if (code.getMethodName() != null && !code.getMethodName().isEmpty()) {
            sb.append(".").append(code.getMethodName()).append("()");
        }
        sb.append("\n");

        sb.append("   项目: ").append(code.getProjectName()).append("\n");
        sb.append("   包: ").append(code.getPackageName()).append("\n");
        sb.append("   文件: ").append(code.getFilePath()).append("\n");
        sb.append("   相似度: ").append(String.format("%.2f", code.getSimilarity())).append("\n\n");

        String content = code.getContent();
        if (content.length() > 800) {
            content = content.substring(0, 800) + "\n   ... (内容过长，已截断)";
        }

        // 缩进代码
        String indentedContent = content.lines()
                .map(line -> "   " + line)
                .collect(Collectors.joining("\n"));

        sb.append("   ```java\n").append(indentedContent).append("\n   ```\n\n");
    }
}

package com.example.aiagentdemo.tools;

import com.example.aiagentdemo.config.CodeIndexConfig;
import com.example.aiagentdemo.data.ProjectInfo;
import com.example.aiagentdemo.data.RetrievedCode;
import com.example.aiagentdemo.service.CodeRetrievalService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProjectStructureTool {
    @Autowired
    private CodeIndexConfig indexConfig;

    @Autowired
    private CodeRetrievalService retrievalService;

    // ==================== 第一层：项目间关系 ====================

    @Tool("获取所有项目的列表和它们之间的依赖关系")
    public String getProjectRelations() {
        StringBuilder sb = new StringBuilder();
        sb.append("📦 项目依赖关系图\n");
        sb.append("=".repeat(60)).append("\n\n");

        // 1. 获取所有项目名称和路径
        List<ProjectInfo> projects = collectAllProjects();

        // 2. 识别 framework 作为基础项目
        String frameworkName = "framework";

        sb.append("🔷 基础项目: ").append(frameworkName).append("\n");
        sb.append("   └── 被以下项目依赖:\n");

        for (ProjectInfo p : projects) {
            if (!p.getName().equals(frameworkName)) {
                sb.append("       ├── ").append(p.getName()).append("\n");
            }
        }
        sb.append("\n");

        // 3. 列出每个项目的简要信息
        for (ProjectInfo p : projects) {
            sb.append("📁 ").append(p.getName()).append("\n");
            sb.append("   📂 路径: ").append(p.getPath()).append("\n");

            // 从 Milvus 查询该项目有哪些包
            List<RetrievedCode> classes = retrievalService.search(
                    "project:" + p.getName(), 20
            );
            Set<String> packages = classes.stream()
                    .map(RetrievedCode::getPackageName)
                    .filter(pkg -> pkg != null && !pkg.isEmpty())
                    .limit(5)
                    .collect(Collectors.toSet());

            if (!packages.isEmpty()) {
                sb.append("   📦 主要包: ").append(String.join(", ", packages));
                if (classes.size() > 20) {
                    sb.append(" ...等");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    // ==================== 第二层：单项目内部结构 ====================

    @Tool("查看指定项目的完整代码结构，包括包、类、接口等")
    public String getProjectStructure(
            @P("项目名称，如 framework、app、editor_manager、ruoyi") String projectName) {

        StringBuilder sb = new StringBuilder();
        sb.append("📂 项目结构: ").append(projectName).append("\n");
        sb.append("=".repeat(60)).append("\n\n");

        // 1. 从 Milvus 检索该项目所有类级别记录
        List<RetrievedCode> classes = retrievalService.search(
                "project:" + projectName, 500
        );

        if (classes.isEmpty()) {
            return "⚠️ 未找到项目: " + projectName + "\n\n" +
                    "可用的项目: " + getAvailableProjects();
        }

        // 2. 按包名分组
        Map<String, List<RetrievedCode>> byPackage = classes.stream()
                .filter(c -> c.getPackageName() != null && !c.getPackageName().isEmpty())
                .collect(Collectors.groupingBy(RetrievedCode::getPackageName));

        // 3. 输出包结构
        sb.append("📦 包结构:\n");
        for (var entry : byPackage.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {

            String packageName = entry.getKey();
            List<RetrievedCode> classList = entry.getValue();

            // 分类统计
            long interfaces = classList.stream()
                    .filter(c -> c.getContent().contains("interface"))
                    .count();
            long entities = classList.stream()
                    .filter(c -> c.getClassName().startsWith("I") ||
                            c.getClassName().contains("Entity"))
                    .count();

            sb.append("\n  📁 ").append(packageName).append("\n");
            sb.append("     类数: ").append(classList.size());
            if (interfaces > 0) {
                sb.append(" | 🔌 接口: ").append(interfaces);
            }
            if (entities > 0) {
                sb.append(" | 📊 实体: ").append(entities);
            }
            sb.append("\n");

            // 列出前 10 个类
            sb.append("     类列表: ");
            String classNames = classList.stream()
                    .limit(10)
                    .map(RetrievedCode::getClassName)
                    .collect(Collectors.joining(", "));
            sb.append(classNames);
            if (classList.size() > 10) {
                sb.append(" ...等 ").append(classList.size() - 10).append(" 个");
            }
            sb.append("\n");
        }

        // 4. 统计信息
        sb.append("\n📊 统计:\n");
        sb.append("  总类数: ").append(classes.size()).append("\n");
        sb.append("  包数: ").append(byPackage.size()).append("\n");

        return sb.toString();
    }

    // ==================== 辅助方法 ====================

    private List<ProjectInfo> collectAllProjects() {
        List<ProjectInfo> projects = new ArrayList<>();

        // 从 CodeIndexConfig 获取所有项目
//        String frameworkPath = indexConfig.getProjectAPath();
//        if (frameworkPath != null && !frameworkPath.isEmpty()) {
//            projects.add(new ProjectInfo("framework", frameworkPath));
//        }

        String subProjectsPath = indexConfig.getSubProjectsPath();
        if (subProjectsPath != null && !subProjectsPath.isEmpty()) {
            Path subProjectsDir = Paths.get(subProjectsPath);
            if (Files.exists(subProjectsDir)) {
                try {
                    Files.list(subProjectsDir)
                            .filter(Files::isDirectory)
                            .forEach(projectDir -> {
                                String projectName = projectDir.getFileName().toString();
                                projects.add(new ProjectInfo(projectName, projectDir.toString()));
                            });
                } catch (IOException e) {
                    log.error("扫描子项目目录失败", e);
                }
            }
        }

        List<Map<String, String>> subProjects = indexConfig.getSubProjectPaths();
        if (subProjects != null) {
            for (Map<String, String> sub : subProjects) {
                String name = sub.get("pname");
                String path = sub.get("path");
                if (name != null && path != null) {
                    projects.add(new ProjectInfo(name, path));
                }
            }
        }

        return projects;
    }

    private String getAvailableProjects() {
        return collectAllProjects().stream()
                .map(ProjectInfo::getName)
                .collect(Collectors.joining(", "));
    }
}

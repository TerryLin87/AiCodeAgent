package com.example.aiagentdemo.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
public class ReadFileTool {
    @Value("${project.base-path:E:/project}")
    private String projectBasePath;

    private Path basePath;

    @PostConstruct
    public void init() {
        this.basePath = Paths.get(projectBasePath).toAbsolutePath().normalize();
    }

    @Tool("读取项目中的完整源代码文件。当需要查看某个类的完整实现时调用。")
    public String readFile(@P("相对于项目根目录的文件路径，例如: framework/src/main/java/com/bf/novel/framework/dal/system/Sysusers.java") String relativePath) {
        try {
            Path fullPath = Paths.get(projectBasePath, relativePath);
            // ★ 安全检查：必须仍在 basePath 下
            if (!fullPath.startsWith(basePath)) {
                log.warn("路径越界: {}", relativePath);
                return "路径越界，禁止访问: " + relativePath;
            }
            if (!Files.exists(fullPath)) {
                return "文件不存在: " + relativePath;
            }
            String content = Files.readString(fullPath);
            // 限制行数，防止上下文溢出
            String[] lines = content.split("\n");
            if (lines.length > 500) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 500; i++) {
                    sb.append(lines[i]).append("\n");
                }
                sb.append("\n... (文件过长，仅显示前 500 行)");
                return sb.toString();
            }
            return content;
        } catch (IOException e) {
            log.error("读取文件失败: {}", relativePath, e);
            return "读取文件失败: " + e.getMessage();
        }
    }
}

package com.example.aiagentdemo.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
public class ReadFileTool {
    private static final long MAX_FILE_SIZE_BYTES = 1_048_576L;   // 1MB
    private static final int MAX_LINES = 500;

    @Value("${project.base-path:E:/project}")
    private String projectBasePath;

    private Path basePath;

    @PostConstruct
    public void init() {
        this.basePath = Paths.get(projectBasePath).toAbsolutePath().normalize();
    }

    @Tool("读取项目中的完整源代码文件。当需要查看某个类的完整实现时调用。")
    public String readFile(@P("相对于项目根目录的文件路径，例如: framework/src/main/java/com/bf/novel/framework/dal/system/Sysusers.java") String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "文件路径为空";
        }
        if (relativePath.contains("..")) {
            log.warn("路径包含 '..'，拒绝: {}", relativePath);
            return "路径非法，禁止访问: " + relativePath;
        }

        Path requested = Paths.get(relativePath);
        if (requested.isAbsolute()) {
            log.warn("绝对路径，拒绝: {}", relativePath);
            return "不允许绝对路径: " + relativePath;
        }

        try {
            Path fullPath = Paths.get(projectBasePath, relativePath);
            //安全检查：必须仍在 basePath 下
            if (!fullPath.startsWith(basePath)) {
                log.warn("路径越界: {}", relativePath);
                return "路径越界，禁止访问: " + relativePath;
            }
            // 解析符号链接
            Path realPath;
            try {
                realPath = fullPath.toRealPath();
            } catch (IOException e) {
                return "文件不存在: " + relativePath;
            }
            if (!realPath.startsWith(basePath)) {
                log.warn("符号链接越界: {}", relativePath);
                return "路径越界（符号链接），禁止访问: " + relativePath;
            }

            if (!Files.isRegularFile(realPath)) {
                return "不是普通文件，禁止访问: " + relativePath;
            }

            long size = Files.size(realPath);
            if (size > MAX_FILE_SIZE_BYTES) {
                log.info("文件 {} 大小 {} 超过 1MB，只读前 {} 行", relativePath, size, MAX_LINES);
                return readFirstLines(realPath, MAX_LINES);
            }

            return readWithEncodingFallback(realPath);
        } catch (IOException e) {
            log.error("读取文件失败: {}", relativePath, e);
            return "读取文件失败: " + e.getMessage();
        }
    }

    private String readWithEncodingFallback(Path path) throws IOException {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            log.warn("UTF-8 解码失败，尝试 GBK: {}", path);
            byte[] bytes = Files.readAllBytes(path);
            return new String(bytes, java.nio.charset.Charset.forName("GBK"));
        }
    }

    private String readFirstLines(Path path, int maxLines) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < maxLines) {
                sb.append(line).append('\n');
                count++;
            }
        }
        sb.append("\n... (文件过大，仅显示前 ").append(maxLines).append(" 行)");
        return sb.toString();
    }
}

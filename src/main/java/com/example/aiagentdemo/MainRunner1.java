package com.example.aiagentdemo;

import com.example.aiagentdemo.config.CodeIndexConfig;
import com.example.aiagentdemo.data.CodeSegment;
import com.example.aiagentdemo.data.FilePath;
import com.example.aiagentdemo.service.JavaCodeParser;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class MainRunner1 {

    @Autowired
    private CodeIndexConfig indexConfig;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    @Qualifier("milvusEmbeddingStore")
    private MilvusEmbeddingStore embeddingStore;

    @Autowired
    private JavaCodeParser javaCodeParser;

    public void run(String... args) throws Exception {
//        log.info("========== 开始构建代码知识库 ==========");
//        log.info("Embedding 模型: {}", embeddingModel.getClass().getSimpleName());
//        log.info("向量存储: Milvus (collection: bf_novel_code)");
//
//        // 测试 embedding 是否正常
//        testEmbedding();
//
//        // 检查是否需要跳过索引
//        if (args.length > 0 && "--skip-index".equals(args[0])) {
//            log.info("跳过索引构建");
//            return;
//        }
//
//        IndexStats stats = new IndexStats();
//        List<CodeSegment> allSegments = new ArrayList<>();
//
//        // 1. 索引项目A（基础项目）
////        String projectAPath = indexConfig.getProjectAPath();
////        if (projectAPath != null && !projectAPath.isEmpty()) {
////            log.info("1. 索引项目A: {}", projectAPath);
////            indexProject(projectAPath, "framework", allSegments, stats);
////        } else {
////            log.warn("项目A路径未配置，跳过");
////        }
//
//        // 2. 索引所有子项目
//        String subProjectsPath = indexConfig.getSubProjectsPath();
//        if (subProjectsPath != null && !subProjectsPath.isEmpty()) {
//            Path subProjectsDir = Paths.get(subProjectsPath);
//            if (Files.exists(subProjectsDir)) {
//                log.info("2. 扫描子项目目录: {}", subProjectsPath);
//                Files.list(subProjectsDir)
//                        .filter(Files::isDirectory)
//                        .forEach(projectDir -> {
//                            String projectName = projectDir.getFileName().toString();
//                            log.info("   索引子项目: {} ({})", projectName, projectDir);
//                            try {
//                                indexProject(projectDir.toString(), projectName, allSegments, stats);
//                            } catch (IOException e) {
//                                log.error("   索引子项目失败: {}", projectName, e);
//                                stats.errors.add(projectName + ": " + e.getMessage());
//                            }
//                        });
//            } else {
//                log.warn("子项目目录不存在: {}", subProjectsPath);
//            }
//        } else {
//            log.warn("子项目路径未配置，跳过");
//        }
//
//        // 3. 索引单独配置的子项目列表
//
//        List<Map<String,String>> paths = new ArrayList<>();
//        Map<String,String> editor = new HashMap<>();
//        Map<String,String> app = new HashMap<>();
//        Map<String,String> ruoyi = new HashMap<>();
//
//        editor.put("pname","editor_manager");
//        editor.put("path","E:\\project\\editor_manager");
//
//        app.put("pname","app");
//        app.put("path","E:\\project\\app");
//
//        ruoyi.put("pname","ruoyi");
//        ruoyi.put("path","E:\\project\\ruoyi");
//
//        paths.add(editor);
//        paths.add(app);
//        paths.add(ruoyi);
//
//        indexConfig.setSubProjectPaths(paths);
//
//        List<Map<String,String>> subProjectPaths = indexConfig.getSubProjectPaths();
//        if (subProjectPaths != null && !subProjectPaths.isEmpty()) {
//            log.info("3. 索引单独配置的子项目:");
//            for (Map<String,String> subProjectPath : subProjectPaths) {
////                String projectName = Paths.get(subProjectPath).getFileName().toString();
//                String projectName = subProjectPath.get("pname");
//                log.info("   索引子项目: {} ({})", projectName, subProjectPath);
//                try {
//                    indexProject(subProjectPath.get("path"), projectName, allSegments, stats);
//                } catch (IOException e) {
//                    log.error("   索引子项目失败: {}", projectName, e);
//                    stats.errors.add(projectName + ": " + e.getMessage());
//                }
//            }
//        }
//
//        log.info("========== 代码扫描完成 ==========");
//        log.info("总文件数: {}", stats.totalFiles);
//        log.info("总代码片段数: {}", stats.totalSegments);
//        log.info("错误数: {}", stats.errors.size());
//        for (String error : stats.errors) {
//            log.error("  错误: {}", error);
//        }
//
//        if (!allSegments.isEmpty()) {
//            // 4. 向量化并存入 Milvus
//            log.info("========== 开始向量化存储 ==========");
//            indexToMilvus(allSegments);
//            log.info("========== 索引构建完成 ==========");
//        } else {
//            log.warn("没有找到任何代码文件，请检查配置路径");
//        }
//
//        log.info("遍历完成");
    }

    private static class IndexStats {
        int totalFiles = 0;
        int totalSegments = 0;
        List<String> errors = new ArrayList<>();
    }

    /**
     * 索引单个项目
     */
    private void indexProject(String projectPath, String projectName,
                              List<CodeSegment> allSegments, IndexStats stats) throws IOException {
        Path startPath = Paths.get(projectPath);

        if (!Files.exists(startPath)) {
            log.warn("项目路径不存在: {}", projectPath);
            return;
        }

        Files.walkFileTree(startPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    stats.totalFiles++;
                    try {
                        String content = Files.readString(file);
                        String relativePath = startPath.relativize(file).toString();

                        // 检查是否需要排除
                        if (shouldExclude(relativePath)) {
                            return FileVisitResult.CONTINUE;
                        }

                        // 解析Java文件
                        FilePath filePath = new FilePath(
                                file.toString(), relativePath, projectName
                        );
                        List<CodeSegment> segments = javaCodeParser.parse(filePath, content);

                        synchronized (allSegments) {
                            allSegments.addAll(segments);
                            stats.totalSegments += segments.size();
                        }

                        if (stats.totalFiles % 100 == 0) {
                            log.info("已扫描 {} 个文件，提取 {} 个代码片段",
                                    stats.totalFiles, stats.totalSegments);
                        }

                    } catch (Exception e) {
                        log.error("处理文件失败: {}", file, e);
                        stats.errors.add(file.toString() + ": " + e.getMessage());
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                List<String> excludeDirs = indexConfig.getExcludeDirs();
                if (excludeDirs != null && excludeDirs.contains(dirName)) {
                    log.debug("跳过目录: {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 判断文件是否应该排除
     */
    private boolean shouldExclude(String relativePath) {
        // 排除测试文件
        if (relativePath.contains("/test/") || relativePath.contains("\\test\\")) {
            return true;
        }

        // 排除特定文件
        List<String> excludeFiles = indexConfig.getExcludeFiles();
        if (excludeFiles != null) {
            for (String excludeFile : excludeFiles) {
                if (relativePath.endsWith(excludeFile)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 将代码片段向量化并存入 Milvus
     */
    private void indexToMilvus(List<CodeSegment> segments) {
        log.info("准备索引 {} 个代码片段", segments.size());

        if (segments.isEmpty()) {
            log.warn("没有代码片段需要索引");
            return;
        }

        // 转换为 LangChain4j 的 Document
        List<Document> documents = new ArrayList<>();
        for (CodeSegment segment : segments) {
            Metadata metadata = new Metadata();
            metadata.put("projectName", segment.getProjectName());
            metadata.put("className", segment.getClassName());
            metadata.put("packageName", segment.getPackageName());
            metadata.put("segmentType", segment.getSegmentType());
            metadata.put("filePath", segment.getFilePath());

            if (segment.getMethodName() != null) {
                metadata.put("methodName", segment.getMethodName());
            }
            if (segment.getTypeKind() != null) {
                metadata.put("typeKind", segment.getTypeKind());
            }

            Document document = Document.from(segment.getContent(), metadata);
            documents.add(document);
        }

        // 使用 Ingestor 批量入库
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .build();

        // 分批处理，每批 50 条（避免 Ollama 压力过大）
        int batchSize = 50;
        int totalBatches = (documents.size() + batchSize - 1) / batchSize;
        int processed = 0;

        for (int i = 0; i < documents.size(); i += batchSize) {
            int end = Math.min(i + batchSize, documents.size());
            List<Document> batch = documents.subList(i, end);

            try {
                ingestor.ingest(batch);
                processed += batch.size();
                int percent = processed * 100 / documents.size();
                log.info("进度: {}/{} ({}%)", processed, documents.size(), percent);
            } catch (Exception e) {
                log.error("批量索引失败，批次: {}-{}", i, end, e);
                // 如果整批失败，尝试单条索引
                for (Document doc : batch) {
                    try {
                        ingestor.ingest(List.of(doc));
                        processed++;
                    } catch (Exception singleError) {
                        log.error("单条索引失败: {}", doc.metadata().getString("className"), singleError);
                    }
                }
                log.info("进度: {}/{}", processed, documents.size());
            }
        }

        log.info("向量化存储完成，共 {} 个文档", documents.size());
    }

    /**
     * 测试 embedding 模型连接
     */
    private void testEmbedding() {
        try {
            var result = embeddingModel.embed("测试中文代码片段");
            log.info("Embedding 测试成功，向量维度: {}", result.content().dimension());
        } catch (Exception e) {
            log.error("Embedding 模型连接失败，请检查 Ollama 服务: http://10.246.104.20:11434", e);
            throw new RuntimeException("Embedding 模型连接失败", e);
        }
    }
}

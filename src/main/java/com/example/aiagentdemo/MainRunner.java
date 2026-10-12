package com.example.aiagentdemo;

import com.example.aiagentdemo.config.CodeIndexConfig;
import com.example.aiagentdemo.data.*;
import com.example.aiagentdemo.service.CallRelationService;
import com.example.aiagentdemo.service.JavaCodeParser;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.collection.FlushParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class MainRunner implements CommandLineRunner {

    @Autowired
    private CodeIndexConfig indexConfig;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    @Qualifier("milvusEmbeddingStore")
    private MilvusEmbeddingStore embeddingStore;

    @Autowired
    private JavaCodeParser javaCodeParser;

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private CallRelationService callRelationService;

    @Override
    public void run(String... args) {

//        deleteProjectData("com.bf.novel.editor_manage");
//        try {
//            log.info("正在执行 flush，将数据刷入磁盘...");
//            FlushParam flushParam = FlushParam.newBuilder()
//                    .addCollectionName("bf_novel_code")
//                    .build();
//            milvusClient.flush(flushParam);
//            log.info("flush 完成，数据现在应该已可见。");
//        } catch (Exception e) {
//            log.error("flush 操作失败: {}", e.getMessage());
//        }
//
//
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
//        // 收集所有需要更新的项目
//        List<ProjectInfo> projectsToUpdate = collectProjects();
//
//        if (projectsToUpdate.isEmpty()) {
//            log.warn("没有找到任何项目配置");
//            return;
//        }
//
//        //对每个项目单独处理
//        for (ProjectInfo project : projectsToUpdate) {
//            updateProject(project);
//        }
//
//        log.info("========== 所有项目处理完成 ==========");
    }

    /**
     * 收集所有需要更新的项目
     */
    private List<ProjectInfo> collectProjects() {
        List<ProjectInfo> projects = new ArrayList<>();

        // 1. 项目A（基础项目）
//        String projectAPath = indexConfig.getProjectAPath();
//        if (projectAPath != null && !projectAPath.isEmpty()) {
//            projects.add(new ProjectInfo("framework", "I:\\bfcode\\code"));
//        }

        // 2. 子项目目录下的所有子项目
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

        // 3. 单独配置的子项目列表
        List<Map<String, String>> subProjectPaths = indexConfig.getSubProjectPaths();
        if (subProjectPaths != null && !subProjectPaths.isEmpty()) {
            for (Map<String, String> subProject : subProjectPaths) {
                String projectName = subProject.get("pname");
                String projectPath = subProject.get("path");
                if (projectName != null && projectPath != null) {
                    projects.add(new ProjectInfo(projectName, projectPath));
                }
            }
        }

        return projects;
    }

    /**
     * 更新单个项目
     */
    private void updateProject(ProjectInfo project) {
        log.info("========================================");
        log.info("开始处理项目: {} ({})", project.getName(), project.getPath());
        log.info("========================================");

        try {
            // 1. 扫描项目，获取当前所有代码片段
            ParseResult scanResult = scanProjectWithRelations(project.getPath(), project.getName());

            if (CollectionUtils.isEmpty(scanResult.getSegments())) {
                log.warn("项目 {} 没有找到任何代码片段", project.getName());
                return;
            }

            // 2. 检查是否需要更新
//            boolean needUpdate = checkIfNeedUpdate(project.name, currentSegments);
//
//            if (!needUpdate) {
//                log.info("项目 {} 没有变更，跳过更新", project.name);
//                return;
//            }

            // 3. 删除该项目的所有旧数据
            deleteProjectData(project.getName());

            // 4. 插入新数据
            insertProjectData(scanResult.getSegments());

            // 5. 插入调用关系到调用关系服务
            if (!scanResult.getCallRelations().isEmpty()) {
                callRelationService.addRelations(scanResult.getCallRelations());
                log.info("项目 {} 的 {} 条调用关系已加载",
                        project.getName(), scanResult.getCallRelations().size());
            }

            // 6. 保存新的文件哈希记录
//            saveFileHashes(project.getName(), scanResult.getSegments());

            log.info("项目 {} 更新完成，共 {} 个代码片段", project.getName(), scanResult.getSegments().size());

        } catch (Exception e) {
            log.error("更新项目 {} 失败", project.getName(), e);
        }
    }

    /**
     * 扫描项目，获取所有代码片段 + 调用关系
     */
    private ParseResult scanProjectWithRelations(String projectPath, String projectName) throws IOException {
        Path startPath = Paths.get(projectPath);

        if (!Files.exists(startPath)) {
            log.warn("项目路径不存在: {}", projectPath);
            return new ParseResult(new ArrayList<>(), new ArrayList<>());
        }

        List<CodeSegment> segments = new ArrayList<>();
        List<CallRelation> allRelations = new ArrayList<>();
        IndexStats stats = new IndexStats();

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

                        // ✅ 使用 parseWithRelations 同时获取片段和调用关系
                        ParseResult parseResult = javaCodeParser.parseWithRelations(filePath, content);

                        segments.addAll(parseResult.getSegments());
                        allRelations.addAll(parseResult.getCallRelations());

                        if (stats.totalFiles % 100 == 0) {
                            log.info("已扫描 {} 个文件，提取 {} 个代码片段，{} 条调用关系",
                                    stats.totalFiles, segments.size(), allRelations.size());
                        }

                    } catch (Exception e) {
                        log.error("处理文件失败: {}", file, e);
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

        log.info("项目 {} 扫描完成: {} 个文件, {} 个代码片段, {} 条调用关系",
                projectName, stats.totalFiles, segments.size(), allRelations.size());

        return new ParseResult(segments, allRelations);
    }

//    /**
//     * 检查项目是否需要更新（通过对比文件哈希）
//     */
//    private boolean checkIfNeedUpdate(String projectName, List<CodeSegment> currentSegments) {
//        // 获取之前保存的哈希记录
//        Map<String, String> oldHashes = projectFileHashes.getOrDefault(projectName, new HashMap<>());
//
//        // 计算当前的文件哈希
//        Map<String, String> newHashes = new HashMap<>();
//        for (CodeSegment segment : currentSegments) {
//            String fileKey = segment.getFilePath();
//            // 简单起见，用文件路径 + 内容的简单哈希
//            String hash = String.valueOf(segment.getContent().hashCode());
//            newHashes.put(fileKey, hash);
//        }
//
//        // 如果之前的记录为空，说明是第一次索引，需要更新
//        if (oldHashes.isEmpty()) {
//            log.info("项目 {} 首次索引", projectName);
//            return true;
//        }
//
//        // 检查是否有变更
//        if (!oldHashes.equals(newHashes)) {
//            log.info("项目 {} 检测到变更，需要更新", projectName);
//            return true;
//        }
//
//        log.info("项目 {} 无变更", projectName);
//        return false;
//    }

    /**
     * 删除指定项目的所有数据
     */
    private void deleteProjectData(String projectName) {
        try {
            Filter filter = MetadataFilterBuilder.metadataKey("projectName").isEqualTo(projectName);
            embeddingStore.removeAll(filter);
            log.info("已删除项目 {} 的旧数据", projectName);
        } catch (Exception e) {
            log.error("删除项目 {} 数据失败", projectName, e);
        }
    }

    /**
     * 插入项目的所有数据
     */
    private void insertProjectData(List<CodeSegment> segments) {
        log.info("当前 embeddingStore 实际类型: {}", embeddingStore.getClass().getName());
        log.info("开始插入 {} 个代码片段", segments.size());

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

        // 分批处理，每批 50 条
        int batchSize = 50;
        int processed = 0;

        for (int i = 0; i < documents.size(); i += batchSize) {
            int end = Math.min(i + batchSize, documents.size());
            List<Document> batch = documents.subList(i, end);

            try {
                ingestor.ingest(batch);
                processed += batch.size();
                int percent = processed * 100 / documents.size();
                log.info("插入进度: {}/{} ({}%)", processed, documents.size(), percent);
                // 关键：每批次后强制刷新
                if (embeddingStore instanceof MilvusEmbeddingStore) {
                    // 尝试触发 flush - 不同的版本方法名可能不同
                    try {
                        java.lang.reflect.Method flushMethod = embeddingStore.getClass().getMethod("flush");
                        flushMethod.invoke(embeddingStore);
                    } catch (NoSuchMethodException e) {
                        // 没有 flush 方法，跳过
                    }
                }
            } catch (Exception e) {
                log.error("批量插入失败，批次: {}-{}", i, end, e);
                // 单条重试
                for (Document doc : batch) {
                    try {
                        ingestor.ingest(List.of(doc));
                        processed++;
                    } catch (Exception singleError) {
                        log.error("单条插入失败: {}", doc.metadata().getString("className"), singleError);
                    }
                }
            }
        }

        log.info("项目插入完成，共 {} 个文档", documents.size());
    }

//    /**
//     * 保存文件哈希记录
//     */
//    private void saveFileHashes(String projectName, List<CodeSegment> segments) {
//        Map<String, String> hashes = new HashMap<>();
//        for (CodeSegment segment : segments) {
//            String fileKey = segment.getFilePath();
//            String hash = String.valueOf(segment.getContent().hashCode());
//            hashes.put(fileKey, hash);
//        }
//        projectFileHashes.put(projectName, hashes);
//    }

//    private void insertProjectData(List<CodeSegment> segments) {
//        log.info("开始插入 {} 个代码片段", segments.size());
//
//        int batchSize = 100;
//        int successCount = 0;
//
//        for (int i = 0; i < segments.size(); i += batchSize) {
//            int end = Math.min(i + batchSize, segments.size());
//            List<CodeSegment> batch = segments.subList(i, end);
//
//            // 1. 准备这一批的 TextSegment
//            List<dev.langchain4j.data.segment.TextSegment> textSegments = new ArrayList<>();
//            for (CodeSegment segment : batch) {
//                String content = segment.getContent();
//                if (content.length() > 60000) {
//                    content = content.substring(0, 60000) + "\n... [截断]";
//                }
//                textSegments.add(
//                        dev.langchain4j.data.segment.TextSegment.from(content, buildMetadata(segment))
//                );
//            }
//
//            // 2. 向量化（你原有逻辑不变）
//            var embeddingResponse = embeddingModel.embedAll(textSegments);
//            List<Embedding> embeddings = embeddingResponse.content();
//
//            // 3. 入库逻辑改造：不再依赖addAll，统一循环单条add
//            try {
//                // 放弃 embeddingStore.addAll，直接循环单条add写入
//                for (int j = 0; j < embeddings.size(); j++) {
//                    embeddingStore.add(embeddings.get(j), textSegments.get(j));
//                }
//                successCount += batch.size();
//                log.info("进度: {}/{}", successCount, segments.size());
//            } catch (Exception e) {
//                log.error("批次单条循环插入失败", e);
//                // 单条写入依然异常时，逐条捕获隔离失败数据
//                for (int j = 0; j < embeddings.size(); j++) {
//                    try {
//                        embeddingStore.add(embeddings.get(j), textSegments.get(j));
//                        successCount++;
//                    } catch (Exception se) {
//                        log.error("单条add入库失败，跳过本条", se);
//                    }
//                }
//            }
//
//            // 批次间休息（避免过载）
//            try {
//                Thread.sleep(100);
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//            }
//        }
//
//        log.info("插入完成: 成功 {} / 总共 {}", successCount, segments.size());
//
//        // ✅ 所有批次完成后，执行一次 flush
//        try {
//            log.info("正在执行 flush，将数据刷入磁盘...");
//            FlushParam flushParam = FlushParam.newBuilder()
//                    .addCollectionName("bf_novel_code")
//                    .build();
//            milvusClient.flush(flushParam);
//            log.info("flush 完成，数据现在应该已可见。");
//        } catch (Exception e) {
//            log.error("flush 操作失败: {}", e.getMessage());
//        }
//    }

//    private void insertProjectData(List<CodeSegment> segments) {
//        log.info("开始插入 {} 个代码片段", segments.size());
//
//        int batchSize = 100;
//        int successCount = 0;
//
//        for (int i = 0; i < segments.size(); i += batchSize) {
//            int end = Math.min(i + batchSize, segments.size());
//            List<CodeSegment> batch = segments.subList(i, end);
//
//            // 1. 构建文本片段（原有逻辑不变）
//            List<dev.langchain4j.data.segment.TextSegment> textSegments = new ArrayList<>();
//            for (CodeSegment segment : batch) {
//                String content = segment.getContent();
//                if (content.length() > 60000) {
//                    content = content.substring(0, 60000) + "\n... [截断]";
//                }
//                textSegments.add(
//                        dev.langchain4j.data.segment.TextSegment.from(content, buildMetadata(segment))
//                );
//            }
//
//            // 2. 批量向量化，不牺牲速度
//            var embeddingResponse = embeddingModel.embedAll(textSegments);
//            List<Embedding> embeddings = embeddingResponse.content();
//
//            // 3. 优先批量入库，失败降级单条add
//            try {
//                embeddingStore.addAll(embeddings, textSegments);
//                successCount += batch.size();
//                log.info("进度: {}/{}", successCount, segments.size());
//            } catch (Exception e) {
//                log.error("批次addAll失败，降级单条插入", e);
//                for (int j = 0; j < embeddings.size(); j++) {
//                    try {
//                        embeddingStore.add(embeddings.get(j), textSegments.get(j));
//                        successCount++;
//                    } catch (Exception se) {
//                        log.error("单条add插入失败", se);
//                    }
//                }
//            }
//
//            // 普通批次短休眠
//            try {
//                Thread.sleep(100);
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//            }
//
//            // 核心：每满1000条，休眠3秒回收系统socket/端口资源
//            if (successCount > 0 && successCount % 1000 == 0) {
//                log.info("已处理{}条，休眠3秒释放TCP缓冲区与端口", successCount);
//                try {
//                    Thread.sleep(3000);
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                }
//            }
//        }
//
//        log.info("插入完成: 成功 {} / 总共 {}", successCount, segments.size());
//
//        // Milvus flush 原有逻辑不变
//        try {
//            log.info("正在执行 flush，将数据刷入磁盘...");
//            FlushParam flushParam = FlushParam.newBuilder()
//                    .addCollectionName("bf_novel_code")
//                    .build();
//            milvusClient.flush(flushParam);
//            log.info("flush 完成，数据现在应该已可见。");
//        } catch (Exception e) {
//            log.error("flush 操作失败: {}", e.getMessage());
//        }
//    }

    /**
     * 构建元数据
     */
    private dev.langchain4j.data.document.Metadata buildMetadata(CodeSegment segment) {
        dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata();
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

        return metadata;
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

    private static class IndexStats {
        int totalFiles = 0;
        int totalSegments = 0;
    }
}

package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.RetrievedCode;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.param.R;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 代码向量检索服务（Milvus）。
 */
@Slf4j
@Service
public class CodeRetrievalService {

    @Autowired
    @Qualifier("milvusEmbeddingStore")
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private HybridSearchService hybridSearchService;

    // Milvus 集合名
    @Value("${agent.milvus.collection:bf_novel_code}")
    private String collectionName = "bf_novel_code";

    // 维度探测失败时的回退值
    @Value("${agent.embedding.dimension:1024}")
    private int fallbackDimension = 1024;

    // Milvus 单次 topK 上限
    @Value("${agent.milvus.max-top-k:16384}")
    private int maxTopK = 16384;

    // 运行时探测到的向量维度，-1 表示尚未探测
    private volatile int dimension = -1;

    /**
     * 根据需求检索相似代码。
     */
    public List<RetrievedCode> search(String query, int maxResults) {
        return search(query, maxResults, null, null);
    }

    /**
     * 根据需求检索相似代码，可按项目过滤。
     */
    public List<RetrievedCode> search(String query, int maxResults, String projectName, Double minScore) {
        log.info("检索: query={}, project={}, maxResults={}", query, projectName, maxResults);

        try {
            // 将查询文本向量化
            var queryEmbedding = embeddingModel.embed(query).content();

            // 构建过滤器
            Filter filter = null;
            if (projectName != null && !projectName.isEmpty()) {
                filter = MetadataFilterBuilder.metadataKey("projectName").isEqualTo(projectName);
            }

            // 构建搜索请求
            EmbeddingSearchRequest.EmbeddingSearchRequestBuilder requestBuilder = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults);

            if (minScore != null) {
                requestBuilder.minScore(minScore);
            }
            if (filter != null) {
                requestBuilder.filter(filter);
            }

            EmbeddingSearchRequest searchRequest = requestBuilder.build();

            // 执行检索（使用 search 方法，不是 findRelevant）
            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

            return searchResult.matches().stream()
                    .map(match -> {
                        Metadata metadata = match.embedded().metadata();
                        return new RetrievedCode(
                                match.embedded().text(),
                                metadata.getString("className"),
                                metadata.getString("packageName"),
                                metadata.getString("projectName"),
                                match.score(),
                                metadata.getString("filePath"),
                                metadata.getString("segmentType"),
                                metadata.getString("methodName")
                        );
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("检索失败", e);
            return List.of();
        }
    }

    /**
     * 混合搜索入口。
     */
    public List<RetrievedCode> hybridSearch(String query, int maxResults) {
        return hybridSearch(query, maxResults, null, 0.3);
    }

    public List<RetrievedCode> hybridSearch(String query, int maxResults, String projectName, Double minScore) {
        return hybridSearchService.hybridSearch(query, maxResults, projectName, minScore);
    }

    /**
     * 按类名精确检索。
     */
    public List<RetrievedCode> findByClassName(String className, String projectName) {
        return search(className, 10, projectName, null);
    }

    /**
     * 检索项目 A 的基础接口（供 Agent 参考代码规范）。
     */
    public List<RetrievedCode> getBaseProjectApis() {
        return search("public interface abstract class base service", 20, "framework", null);
    }

    /**
     * 检索项目 A 中指定类型的代码。
     */
    public List<RetrievedCode> getBaseProjectCodeByKeyword(String keyword) {
        return search(keyword, 10, "framework", 0.7);
    }

    /**
     * 格式化检索结果为 Prompt 上下文。
     */
    public String formatAsPromptContext(List<RetrievedCode> results) {
        if (results == null || results.isEmpty()) {
            return "（未找到相似代码参考）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是项目中相似的代码示例，请参考其风格和规范：\n\n");

        for (int i = 0; i < results.size(); i++) {
            RetrievedCode code = results.get(i);
            String content = code.getContent();
            if (content == null) {
                content = "";
            }
            if (content.length() > 2000) {
                content = content.substring(0, 2000) + "\n... (代码过长已截断)";
            }

            sb.append("【参考 ").append(i + 1).append("】\n");
            sb.append("项目: ").append(code.getProjectName()).append("\n");
            sb.append("类型: ").append(code.getSegmentType() != null ? code.getSegmentType() : "class").append("\n");
            sb.append("类名: ").append(code.getClassName()).append("\n");
            if (code.getMethodName() != null) {
                sb.append("方法: ").append(code.getMethodName()).append("\n");
            }
            sb.append("相似度: ").append(String.format("%.2f", code.getSimilarity())).append("\n");
            sb.append("```java\n");
            sb.append(content).append("\n");
            sb.append("```\n\n");
        }

        return sb.toString();
    }

    /**
     * 精简格式化（只保留代码，用于节省 token）。
     */
    public String formatAsCompactContext(List<RetrievedCode> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(results.size(), 3); i++) {
            RetrievedCode code = results.get(i);
            sb.append("参考 ").append(i + 1).append(":\n");
            sb.append(code.getContent()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 查询指定项目的所有代码片段（用于构建关键词索引、调试和验证）。
     *
     * @param projectName 项目名称（可选，为 null 则查询全部）
     * @param limit       返回数量限制
     * @return 所有代码片段列表
     */
    public List<RetrievedCode> getAllSegments(String projectName, int limit) {
        log.info("查询全部片段: project={}, limit={}", projectName, limit);

        try {
            Filter filter = null;
            if (projectName != null && !projectName.isEmpty()) {
                filter = MetadataFilterBuilder.metadataKey("projectName").isEqualTo(projectName);
            }

            // Milvus 要求必须提供查询向量，这里使用中性向量做「全量拉取」的折中方案。
            // 关键修复：维度改为运行时探测，避免与 embedding 模型不匹配导致静默失败。
            int dim = resolveDimension();
            if (dim <= 0) {
                log.error("无法确定 embedding 维度，放弃全量查询");
                return List.of();
            }
            float[] neutralVector = new float[dim];
            Arrays.fill(neutralVector, 1.0f);

            int effectiveLimit = Math.max(1, Math.min(limit, maxTopK));
            if (effectiveLimit != limit) {
                log.warn("limit {} 超出 Milvus 上限，已钳位为 {}", limit, effectiveLimit);
            }

            Embedding queryEmbedding = Embedding.from(neutralVector);

            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(effectiveLimit)
                    .filter(filter)
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

            return searchResult.matches().stream()
                    .map(match -> new RetrievedCode(
                            match.embedded().text(),
                            match.embedded().metadata().getString("className"),
                            match.embedded().metadata().getString("packageName"),
                            match.embedded().metadata().getString("projectName"),
                            match.score(),
                            match.embedded().metadata().getString("filePath"),
                            match.embedded().metadata().getString("segmentType"),
                            match.embedded().metadata().getString("methodName")
                    ))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("查询全部片段失败", e);
            return List.of();
        }
    }

    /**
     * 运行时探测 embedding 维度（双重检查锁，只探测一次）。
     */
    private int resolveDimension() {
        int cached = dimension;
        if (cached > 0) {
            return cached;
        }
        synchronized (this) {
            if (dimension > 0) {
                return dimension;
            }
            try {
                int probed = embeddingModel.embed("dimension-probe").content().vector().length;
                if (probed > 0) {
                    dimension = probed;
                    log.info("探测到 embedding 维度: {}", probed);
                    return probed;
                }
            } catch (Exception e) {
                log.warn("embedding 维度探测失败，回退到配置值 {}", fallbackDimension, e);
            }
            dimension = fallbackDimension;
            return dimension;
        }
    }

    /**
     * 获取集合中的记录总数。
     */
    public long getCollectionCount() {
        try {
            FlushParam flushParam = FlushParam.newBuilder()
                    .addCollectionName(collectionName)
                    .build();
            milvusClient.flush(flushParam);

            GetCollectionStatisticsParam param = GetCollectionStatisticsParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build();
            R<GetCollectionStatisticsResponse> response = milvusClient.getCollectionStatistics(param);

            if (response.getStatus() == 0) {
                return response.getData().getStatsCount();
            }
        } catch (Exception e) {
            log.error("获取记录总数失败", e);
        }
        return -1;
    }

    /**
     * 按文件路径查询（用于验证特定文件是否入库）。
     */
    public List<RetrievedCode> findByFilePath(String filePath, String projectName) {
        log.info("按文件路径查询: filePath={}, project={}", filePath, projectName);

        try {
            Filter filter = MetadataFilterBuilder.metadataKey("filePath").isEqualTo(filePath);
            if (projectName != null && !projectName.isEmpty()) {
                filter = filter.and(MetadataFilterBuilder.metadataKey("projectName").isEqualTo(projectName));
            }

            // 用文件名作为查询向量
            var queryEmbedding = embeddingModel.embed(filePath).content();

            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(100)
                    .filter(filter)
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

            return searchResult.matches().stream()
                    .map(match -> new RetrievedCode(
                            match.embedded().text(),
                            match.embedded().metadata().getString("className"),
                            match.embedded().metadata().getString("packageName"),
                            match.embedded().metadata().getString("projectName"),
                            match.score(),
                            match.embedded().metadata().getString("filePath"),
                            match.embedded().metadata().getString("segmentType"),
                            match.embedded().metadata().getString("methodName")
                    ))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("按文件路径查询失败", e);
            return List.of();
        }
    }

    /**
     * 精确查找类定义（通过类名 + segmentType 过滤）。
     */
    public List<RetrievedCode> findByClassSegment(String className) {
        log.info("精确查找类定义: className={}", className);

        try {
            var queryEmbedding = embeddingModel.embed(className).content();

            Filter filter = MetadataFilterBuilder.metadataKey("className").isEqualTo(className)
                    .and(MetadataFilterBuilder.metadataKey("segmentType").isIn("class", "interface", "enum"));

            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(10)
                    .filter(filter)
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

            return searchResult.matches().stream()
                    .map(match -> {
                        Metadata metadata = match.embedded().metadata();
                        return new RetrievedCode(
                                match.embedded().text(),
                                metadata.getString("className"),
                                metadata.getString("packageName"),
                                metadata.getString("projectName"),
                                match.score(),
                                metadata.getString("filePath"),
                                metadata.getString("segmentType"),
                                metadata.getString("methodName")
                        );
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("精确查找类定义失败", e);
            return List.of();
        }
    }
}

package com.zlh.aiagent.service;

import com.zlh.aiagent.config.DomainConfig;
import com.zlh.aiagent.data.RetrievalResult;
import com.zlh.aiagent.data.RetrievedCode;
import com.zlh.aiagent.utils.DegradationLogger;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.param.R;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 代码向量检索服务（Milvus）。
 *
 * <p>职责分区：
 * <ul>
 *   <li>【依赖注入与配置】</li>
 *   <li>【语义检索】query 文本 → 向量 → Milvus 相似度匹配（带熔断）</li>
 *   <li>【混合检索入口】语义 + Lucene 关键词，RRF 融合（委托 HybridSearchService）</li>
 *   <li>【精确查询】按项目 / 文件路径 / 类名 + 类型做 metadata filter，不做语义</li>
 *   <li>【格式化输出】把检索结果拼成 Prompt 上下文</li>
 *   <li>【元信息与工具方法】健康检查、维度探测、公共转换</li>
 * </ul>
 */
@Slf4j
@Service
public class CodeRetrievalService {

    //region 【A】依赖注入与配置

    @Autowired
    @Qualifier("milvusEmbeddingStore")
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private DegradationLogger degradationLogger;

    @Autowired
    private DomainConfig domainConfig;

    /** Milvus 集合名（需与 ChromaDbConfig 里 milvusEmbeddingStore 的 collectionName 一致） */
    @Value("${agent.milvus.collection:bf_novel_code}")
    private String collectionName = "bf_novel_code";

    /** 维度探测失败时的回退值，需与 embedding 模型实际维度一致 */
    @Value("${agent.embedding.dimension:1024}")
    private int fallbackDimension = 1024;

    /** Milvus 单次 topK 上限，超过会被钳位 */
    @Value("${agent.milvus.max-top-k:1024}")
    private int maxTopK = 1024;

    /** 运行时探测到的向量维度，-1 表示尚未探测 */
    private volatile int dimension = -1;

    //endregion

    //region 【B】语义检索（带熔断）

    /**
     * 语义检索（简化入口，使用默认 minScore 和不限项目）。
     */
    public List<RetrievedCode> search(String query, int maxResults) {
        return search(query, maxResults, null, defaultMinScore());
    }

    /**
     * 语义检索主入口：query 文本向量化后与 Milvus 做相似度匹配。
     *
     * <p>熔断策略：如果 Milvus 连续失败触发熔断（Open 状态），
     * 自动走 {@link #searchFallback} 返回空列表，不阻塞主链路。
     *
     * @param query       查询文本（自然语言或关键词）
     * @param maxResults  返回数量上限
     * @param projectName 项目名过滤，null 表示不限项目
     * @param minScore    最低相似度阈值，null 表示不设阈值
     * @return 匹配的代码片段列表，失败时为空列表
     */
    @CircuitBreaker(name = "milvus", fallbackMethod = "searchFallback")
    public List<RetrievedCode> search(String query, int maxResults, String projectName, Double minScore) {
        log.info("检索: query={}, project={}, maxResults={}", query, projectName, maxResults);
        return doSearch(query, maxResults, projectName, minScore);
    }

    /**
     * 语义检索的实际执行逻辑（熔断器内部调用）。
     */
    private List<RetrievedCode> doSearch(String query, int maxResults, String projectName, Double minScore) {
        try {
            // 1. query 文本向量化
            var queryEmbedding = embeddingModel.embed(query).content();

            // 2. 项目过滤（可选）
            Filter filter = null;
            if (projectName != null && !projectName.isEmpty()) {
                filter = MetadataFilterBuilder.metadataKey("projectName").isEqualTo(projectName);
            }

            // 3. 构建搜索请求
            EmbeddingSearchRequest.EmbeddingSearchRequestBuilder requestBuilder = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults);

            if (minScore != null) {
                requestBuilder.minScore(minScore);
            }
            if (filter != null) {
                requestBuilder.filter(filter);
            }

            // 4. 执行检索
            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(requestBuilder.build());

            return searchResult.matches().stream()
                    .map(this::toRetrievedCode)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("检索失败", e);
            return List.of();
        }
    }

    /**
     * search 方法的熔断降级：返回空列表，并记录降级事件。
     * <p>方法签名必须与原方法一致，最后多加一个 Throwable 参数。
     */
    public List<RetrievedCode> searchFallback(String query, int maxResults, String projectName,
                                              Double minScore, Throwable t) {
        degradationLogger.log("Milvus", "search degraded, reason: " + t.getMessage());
        return List.of();
    }

    //endregion

    //region 【C】混合检索入口（委托 HybridSearchService）

    /**
     * 混合检索（语义 + 关键词，RRF 融合），简化入口。
     */
    public List<RetrievedCode> hybridSearch(String query, int maxResults) {
        return hybridSearch(query, maxResults, null, defaultMinScore());
    }

    /**
     * 混合检索（语义 + 关键词，RRF 融合）。
     * <p>实际逻辑在 {@link HybridSearchService#hybridSearch}，这里只是转发。
     */
    public List<RetrievedCode> hybridSearch(String query, int maxResults, String projectName, Double minScore) {
        return hybridSearchService.hybridSearch(query, maxResults, projectName, minScore);
    }

    //endregion

    //region 【D】精确查询（走 metadata filter，不做语义）

    /**
     * 按项目名精确拉取代码片段（走 metadata filter，不做语义检索）。
     *
     * <p>适用场景："拉某项目全部片段"——不需要语义相关性，只需要项目过滤。
     * 用中性向量满足 Milvus 对 query vector 的硬性要求，排序无意义。
     *
     * @param projectName 项目名，非空
     * @param limit       返回数量上限（会被钳位到 maxTopK）
     * @return 该项目的片段列表，失败时返回空列表
     */
    public List<RetrievedCode> getByProject(String projectName, int limit) {
        if (projectName == null || projectName.isBlank()) {
            return List.of();
        }
        try {
            Filter filter = MetadataFilterBuilder.metadataKey("projectName").isEqualTo(projectName);
            return searchWithNeutralVector(filter, limit, "project=" + projectName);
        } catch (Exception e) {
            log.error("按项目查询失败: project={}", projectName, e);
            return List.of();
        }
    }

    /**
     * 按文件路径精确查询（走 metadata filter）。
     *
     * <p>用途：验证某个文件是否已入库，或拉取该文件的全部片段。
     * 与旧实现区别：不再把路径文本向量化，直接用 filePath filter，减少一次 embedding 调用。
     *
     * @param filePath    文件路径（项目内相对路径）
     * @param projectName 项目名，可选。非空时同时限定项目
     * @return 匹配的片段列表
     */
    public List<RetrievedCode> findByFilePath(String filePath, String projectName) {
        log.info("按文件路径查询: filePath={}, project={}", filePath, projectName);

        if (filePath == null || filePath.isBlank()) {
            return List.of();
        }
        try {
            Filter filter = MetadataFilterBuilder.metadataKey("filePath").isEqualTo(filePath);
            if (projectName != null && !projectName.isEmpty()) {
                filter = filter.and(MetadataFilterBuilder.metadataKey("projectName").isEqualTo(projectName));
            }
            return searchWithNeutralVector(filter, 100, "filePath=" + filePath);
        } catch (Exception e) {
            log.error("按文件路径查询失败", e);
            return List.of();
        }
    }

    /**
     * 按类名精确检索（语义版，不限定 segmentType）。
     * <p>注意：语义检索可能返回方法级片段（方法 content 里也含类名），
     * 想精确拿类定义请用 {@link #findByClassSegment}。
     */
    public List<RetrievedCode> findByClassName(String className, String projectName) {
        return search(className, 10, projectName, null);
    }

    /**
     * 精确查找类定义（className + segmentType 过滤，走语义 + filter）。
     *
     * <p>比 {@link #findByClassName} 更准：只返回 class/interface/enum 级片段，
     * 不会混入方法级片段。
     *
     * @param className 类名（简单名或全限定名）
     * @return 类定义片段列表
     */
    public List<RetrievedCode> findByClassSegment(String className) {
        return findByClassSegment(className, null);
    }

    /**
     * 精确查找类定义（带项目过滤）。
     *
     * @param className   类名
     * @param projectName 项目名，可选
     * @return 类定义片段列表
     */
    public List<RetrievedCode> findByClassSegment(String className, String projectName) {
        log.info("精确查找类定义: className={}, project={}", className, projectName);
        if (className == null || className.isBlank()) {
            return List.of();
        }
        try {
            var queryEmbedding = embeddingModel.embed(className).content();

            Filter filter = MetadataFilterBuilder.metadataKey("className").isEqualTo(className)
                    .and(MetadataFilterBuilder.metadataKey("segmentType").isIn("class", "interface", "enum"));
            if (projectName != null && !projectName.isEmpty()) {
                filter = filter.and(MetadataFilterBuilder.metadataKey("projectName").isEqualTo(projectName));
            }

            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(10)
                    .filter(filter)
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
            return searchResult.matches().stream()
                    .map(this::toRetrievedCode)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("精确查找类定义失败", e);
            return List.of();
        }
    }

    /**
     * 查询指定项目的所有代码片段（用于构建关键词索引、调试和验证）。
     *
     * @param projectName 项目名称（可选，为 null 则查询全部）
     * @param limit       返回数量限制（会被钳位到 maxTopK）
     * @return 所有代码片段列表
     */
    public List<RetrievedCode> getAllSegments(String projectName, int limit) {
        log.info("查询全部片段: project={}, limit={}", projectName, limit);

        try {
            Filter filter = null;
            if (projectName != null && !projectName.isEmpty()) {
                filter = MetadataFilterBuilder.metadataKey("projectName").isEqualTo(projectName);
            }
            return searchWithNeutralVector(filter, limit, "all");
        } catch (Exception e) {
            log.error("查询全部片段失败", e);
            return List.of();
        }
    }

    //endregion

    //region 【E】格式化输出（供 Prompt 使用）

    /**
     * 格式化检索结果为 Prompt 上下文（详细版，展示全部元数据）。
     * <p>通用实现，不依赖具体领域的 metadata key。
     */
    public String formatAsPromptContext(List<? extends RetrievalResult> results) {
        if (results == null || results.isEmpty()) {
            return "（未找到相似内容参考）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是项目中相似的示例，请参考其风格和规范：\n\n");

        for (int i = 0; i < results.size(); i++) {
            RetrievalResult r = results.get(i);
            String content = r.getContent();
            if (content == null) {
                content = "";
            }
            if (content.length() > 2000) {
                content = content.substring(0, 2000) + "\n... (内容过长已截断)";
            }

            sb.append("【参考 ").append(i + 1).append("】\n");
            sb.append("来源: ").append(r.getSource() != null ? r.getSource() : "").append("\n");
            sb.append("类型: ").append(r.getCategory() != null ? r.getCategory() : "unknown").append("\n");
            sb.append("标题: ").append(r.getTitle() != null ? r.getTitle() : "").append("\n");

            Map<String, String> meta = r.getAllMetadata();
            if (!meta.isEmpty()) {
                meta.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
            }

            sb.append("相似度: ").append(String.format("%.2f", r.getScore())).append("\n");
            sb.append("```\n").append(content).append("\n```\n\n");
        }

        return sb.toString();
    }

    /**
     * 精简格式化（只保留前 3 条内容，用于节省 token）。
     * <p>目前无调用方，保留作为 token 紧张时的备选。
     */
    public String formatAsCompactContext(List<? extends RetrievalResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(results.size(), 3); i++) {
            RetrievalResult r = results.get(i);
            sb.append("参考 ").append(i + 1).append(":\n");
            sb.append(r.getContent()).append("\n\n");
        }

        return sb.toString();
    }

    //endregion

    //region 【F】元信息与工具方法

    /**
     * 获取集合中的记录总数（供健康检查用）。
     * <p>注意：不再调用 flush——flush 是重操作，health check 每 2 秒调一次会把 Milvus 打爆。
     * flush 只在索引写入完成后由 MainRunner 调一次即可。
     */
    public long getCollectionCount() {
        try {
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
     * 用中性向量 + filter 做"全量拉取"查询。
     * <p>Milvus 要求必须提供 query vector，这里用全 1 向量满足要求，排序无意义。
     *
     * @param filter 元数据过滤器，可为 null
     * @param limit  返回数量上限（会被钳位到 maxTopK）
     * @param tag    日志标识
     * @return 匹配的片段列表
     */
    private List<RetrievedCode> searchWithNeutralVector(Filter filter, int limit, String tag) {
        int dim = resolveDimension();
        if (dim <= 0) {
            log.error("无法确定 embedding 维度，放弃查询: {}", tag);
            return List.of();
        }
        float[] neutralVector = new float[dim];
        Arrays.fill(neutralVector, 1.0f);

        int effectiveLimit = Math.max(1, Math.min(limit, maxTopK));
        if (effectiveLimit != limit) {
            log.warn("limit {} 超出 Milvus 上限，已钳位为 {} (tag={})", limit, effectiveLimit, tag);
        }

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(neutralVector))
                .maxResults(effectiveLimit)
                .filter(filter)
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        return searchResult.matches().stream()
                .map(this::toRetrievedCode)
                .collect(Collectors.toList());
    }

    /**
     * 把 Milvus 的 EmbeddingMatch 转换成 RetrievedCode（统一转换入口，避免重复代码）。
     */
    private RetrievedCode toRetrievedCode(dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment> match) {
        Metadata md = match.embedded().metadata();
        return new RetrievedCode(
                match.embedded().text(),
                md.getString("className"),
                md.getString("packageName"),
                md.getString("projectName"),
                match.score(),
                md.getString("filePath"),
                md.getString("segmentType"),
                md.getString("methodName")
        );
    }

    /**
     * 从 DomainConfig 读默认 minScore，兜底 0.3。
     */
    private double defaultMinScore() {
        if (domainConfig != null && domainConfig.getRetrievalMinScore() > 0) {
            return domainConfig.getRetrievalMinScore();
        }
        return 0.3;
    }

    //endregion
}
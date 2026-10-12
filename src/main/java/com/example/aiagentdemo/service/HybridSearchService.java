package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.RetrievedCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

//混合检索服务
@Slf4j
@Service
public class HybridSearchService {
    @Autowired
    @Lazy
    private CodeRetrievalService semanticSearchService;

    @Autowired
    private KeywordSearchService keywordSearchService;

    @Value("${search.hybrid.semantic-weight:0.6}")
    private double semanticWeight;

    @Value("${search.hybrid.keyword-weight:0.4}")
    private double keywordWeight;

    /**
     * 混合检索（语义 + 关键词）
     */
    public List<RetrievedCode> hybridSearch(String query, int topK) {
        return hybridSearch(query, topK, null, 0.3);
    }

    /**
     * 混合检索（带过滤）
     */
    public List<RetrievedCode> hybridSearch(String query, int topK, String projectName, Double minScore) {
        log.info("混合检索: query={}, project={}, topK={}", query, projectName, topK);

        int recallSize = topK * 3;  // 召回更多候选

        // 1. 语义检索
        List<RetrievedCode> semanticResults = semanticSearchService.search(query, recallSize, projectName, minScore);

        // 2. 关键词检索
        List<RetrievedCode> keywordResults = keywordSearchService.search(query, recallSize);

        // 3. 按项目过滤（如果指定）
        if (projectName != null && !projectName.isEmpty()) {
            keywordResults = keywordResults.stream()
                    .filter(c -> projectName.equals(c.getProjectName()))
                    .collect(Collectors.toList());
        }

        // 4. RRF 融合
        List<RetrievedCode> merged = rrfMerge(semanticResults, keywordResults, topK);

        log.info("混合检索完成: 结果数={}", merged.size());
        return merged;
    }

    /**
     * RRF (Reciprocal Rank Fusion) 融合（以后还需要加上Cross-Encoder模型）
     */
    private List<RetrievedCode> rrfMerge(List<RetrievedCode> results1,
                                         List<RetrievedCode> results2,
                                         int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, RetrievedCode> codeMap = new HashMap<>();
        double k = 60.0;

        // 语义检索结果打分
        for (int i = 0; i < results1.size(); i++) {
            RetrievedCode code = results1.get(i);
            String key = buildKey(code);
            double score = semanticWeight / (k + i + 1);
            scores.merge(key, score, Double::sum);
            codeMap.putIfAbsent(key, code);
        }

        // 关键词检索结果打分
        for (int i = 0; i < results2.size(); i++) {
            RetrievedCode code = results2.get(i);
            String key = buildKey(code);
            double score = keywordWeight / (k + i + 1);
            scores.merge(key, score, Double::sum);
            codeMap.putIfAbsent(key, code);
        }

        // 排序并截断
        return scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> codeMap.get(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private String buildKey(RetrievedCode code) {
        return code.getProjectName() + ":" + code.getPackageName() + ":" + code.getClassName();
    }

    /**
     * 在项目启动时构建关键词索引
     */
    public void buildKeywordIndex() {
        // 从 Milvus 加载所有代码
        List<RetrievedCode> allCode = semanticSearchService.getAllSegments(null, 10000);
        if (!allCode.isEmpty()) {
            keywordSearchService.buildIndex(allCode);
            log.info("关键词索引构建完成，共 {} 条记录", allCode.size());
        }
    }
}

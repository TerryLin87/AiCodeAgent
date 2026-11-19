package com.zlh.aiagent.service.law;

import com.zlh.aiagent.data.law.RetrievedClause;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 法务检索服务（从 Milvus 读 law_contract_kb）。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "agent.domain.name", havingValue = "law")
public class LawRetrievalService {

    @Autowired
    @Qualifier("lawContractEmbeddingStore")
    private MilvusEmbeddingStore contractStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    /**
     * 根据问题检索相关法条
     */
    public List<RetrievedClause> searchClauses(String query, int topK, Double minScore) {
        log.info("法务检索: query={}, topK={}", query, topK);

        Embedding emb = embeddingModel.embed(query).content();
        EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                .queryEmbedding(emb)
                .maxResults(topK)
                .minScore(minScore != null ? minScore : 0.4)
                .build();

        EmbeddingSearchResult<TextSegment> result = contractStore.search(req);

        return result.matches().stream()
                .map(m -> {
                    Metadata meta = m.embedded().metadata();
                    return new RetrievedClause(
                            m.embedded().text(),
                            meta.getString("lawName"),
                            meta.getString("articleNo"),
                            meta.getString("chapter"),
                            meta.getString("sourceUrl"),
                            m.score(),
                            meta.getString("docType")
                    );
                })
                .toList();
    }
}
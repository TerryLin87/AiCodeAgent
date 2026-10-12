package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.MemoryDocument;
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
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LongTermMemoryService {
    @Autowired
    @Qualifier("chatMemoryStore")
    private MilvusEmbeddingStore embeddingStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    private static final String COLLECTION_NAME = "chat_memory";
    private final Semaphore embeddingSemaphore = new Semaphore(2);   // 最多 2 个并发 embedding
    /**
     * 将一段对话存入长期记忆（自动生成ID，存储文本+向量）
     */
    public void storeMemory(String sessionId, String role, String content) {
        try {
            embeddingSemaphore.acquire();
            // 1. 构建元数据
            Metadata metadata = new Metadata();
            metadata.put("sessionId", sessionId);
            metadata.put("role", role);
            metadata.put("timestamp", LocalDateTime.now().toString());
            metadata.put("type", "conversation");

            // 2. 构建 TextSegment
            TextSegment segment = TextSegment.from(content, metadata);

            // 3. 生成向量
            Embedding embedding = embeddingModel.embed(content).content();

            // 4. ✅ 正确调用：add(Embedding, TextSegment) → 自动生成ID，存储向量+文本
            String id = embeddingStore.add(embedding, segment);
            log.debug("已存储长期记忆: id={}, session={}, role={}", id, sessionId, role);

        } catch (Exception e) {
            log.error("存储长期记忆失败", e);
        }
    }

    /**
     * 检索相关的历史记忆
     */
    public List<MemoryDocument> retrieveRelevantMemories(String query, int maxResults) {
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();

            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)
                    .minScore(0.65)
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

            return searchResult.matches().stream()
                    .map(match -> {
                        TextSegment segment = match.embedded();
                        Metadata meta = segment.metadata();
                        return MemoryDocument.builder()
                                .content(segment.text())
                                .sessionId(meta.getString("sessionId"))
                                .role(meta.getString("role"))
                                .timestamp(LocalDateTime.parse(Objects.requireNonNull(meta.getString("timestamp"))))
                                .build();
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("检索长期记忆失败", e);
            return List.of();
        }
    }

    /**
     * 清空指定会话的记忆（用于测试）
     */
    public void clearSessionMemories(String sessionId) {
        // 可通过 metadata 过滤删除，此处暂略
        log.warn("清空会话 {} 的长期记忆功能待实现", sessionId);
    }
}

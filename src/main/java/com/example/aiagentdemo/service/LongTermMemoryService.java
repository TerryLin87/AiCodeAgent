package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.MemoryDocument;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * 长期记忆服务（向量持久化到 Milvus {@code chat_memory} 集合）。
 *
 * <p>本次改造（Day3-4）：
 * <ol>
 *   <li><b>写入前去重</b>：{@code storeMemory} 前先在 Milvus 里做一次相似度检索，
 *       若已存在相似度 ≥ {@link #dedupeThreshold}（默认 0.85）的同会话同角色内容则跳过，
 *       彻底堵死回声室的"写回"源头——配合 CodeAgentLoopService 里"长期记忆注入
 *       System Prompt 而不是用户消息"的改造，记忆库只存干净的用户/助手原话。</li>
 *   <li>去重检索<b>复用同一 Semaphore</b>，和写入共享 2 个 embedding 并发上限，
 *       避免去重检查打破原有的流控。</li>
 *   <li>空内容/过短内容（&lt; 4 字符）直接跳过，不写无意义条目。</li>
 * </ol>
 *
 * <p><b>阈值取舍（面试要点）</b>：
 * 0.85 是经验值——太高（如 0.95）会让改写过的同义句重复入库；太低（如 0.7）会误杀
 * 真正不同的内容。代码里把阈值做成配置项 {@code agent.memory.dedupe-threshold}，
 * 可根据实际语料微调。去重<b>仅在同会话 + 同角色内</b>做，避免跨会话误判（两个不同用户
 * 说同一句话是合法的）。
 */
@Slf4j
@Service
public class LongTermMemoryService {

    @Autowired
    @Qualifier("chatMemoryStore")
    private MilvusEmbeddingStore embeddingStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    /** 写入前去重的相似度阈值，可通过配置覆盖 */
    @Value("${agent.memory.dedupe-threshold:0.85}")
    private double dedupeThreshold;

    /** 去重检索的候选数（取最相似的 1 条足以判断是否重复） */
    private static final int DEDUPE_TOP_K = 3;

    /** 过短内容直接不入库，避免"嗯""好"等无意义碎片占用向量库 */
    private static final int MIN_CONTENT_LENGTH = 4;

    private static final String COLLECTION_NAME = "chat_memory";
    private final Semaphore embeddingSemaphore = new Semaphore(2);

    /**
     * 将一段对话存入长期记忆（带写入前去重）。
     *
     * @param sessionId 会话ID，去重仅在同会话内生效
     * @param role      user / assistant
     * @param content   文本内容
     */
    public void storeMemory(String sessionId, String role, String content) {
        if (content == null || content.isBlank() || content.length() < MIN_CONTENT_LENGTH) {
            log.debug("跳过过短/空内容的长期记忆写入, role={}, len={}", role,
                    content == null ? 0 : content.length());
            return;
        }

        try {
            embeddingSemaphore.acquire();
            try {
                // 1) 先做去重检查：同会话 + 同角色 下，是否已有高度相似的条目
                Embedding emb = embeddingModel.embed(content).content();

                EmbeddingSearchRequest dedupReq = EmbeddingSearchRequest.builder()
                        .queryEmbedding(emb)
                        .maxResults(DEDUPE_TOP_K)
                        .minScore(dedupeThreshold)
                        .build();
                EmbeddingSearchResult<TextSegment> dup = embeddingStore.search(dedupReq);
                boolean isDuplicate = false;
                if (dup.matches() != null) {
                    for (EmbeddingMatch<TextSegment> m : dup.matches()) {
                        Metadata meta = m.embedded().metadata();
                        String mSession = meta.getString("sessionId");
                        String mRole = meta.getString("role");
                        // 仅在同会话 + 同角色内判重
                        if (Objects.equals(sessionId, mSession) && Objects.equals(role, mRole)) {
                            log.debug("长期记忆写入被去重跳过, session={}, role={}, score={}, content={}",
                                    sessionId, role, String.format("%.3f", m.score()),
                                    truncate(content, 60));
                            isDuplicate = true;
                            break;
                        }
                    }
                }
                if (isDuplicate) {
                    return;
                }

                // 2) 真正写入
                Metadata metadata = new Metadata();
                metadata.put("sessionId", sessionId);
                metadata.put("role", role);
                metadata.put("timestamp", LocalDateTime.now().toString());
                metadata.put("type", "conversation");

                TextSegment segment = TextSegment.from(content, metadata);
                String id = embeddingStore.add(emb, segment);
                log.debug("已存储长期记忆: id={}, session={}, role={}, len={}",
                        id, sessionId, role, content.length());

            } finally {
                embeddingSemaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("长期记忆写入被中断");
        } catch (Exception e) {
            log.error("存储长期记忆失败", e);
        }
    }

    /**
     * 检索相关的历史记忆（跨会话）。
     */
    public List<MemoryDocument> retrieveRelevantMemories(String query, int maxResults) {
        try {
            embeddingSemaphore.acquire();
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
                                    .score(match.score())
                                    .timestamp(LocalDateTime.parse(
                                            Objects.requireNonNull(meta.getString("timestamp"))))
                                    .build();
                        })
                        .collect(Collectors.toList());
            } finally {
                embeddingSemaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.error("检索长期记忆失败", e);
            return List.of();
        }
    }

    /**
     * 清空指定会话的记忆（可通过 metadata filter 实现，这里留作扩展点）。
     */
    public void clearSessionMemories(String sessionId) {
        log.warn("清空会话 {} 的长期记忆功能待实现（需 Milvus metadata filter 支持）", sessionId);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

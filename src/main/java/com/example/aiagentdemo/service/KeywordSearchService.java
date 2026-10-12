package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.RetrievedCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 关键词检索服务（Lucene BM25）。
 */
@Slf4j
@Service
public class KeywordSearchService {

    // Milvus / Lucene 单次返回上限保护
    private static final int MAX_TOP_K = 1000;

    private Directory directory;
    private volatile IndexReader reader;
    private volatile IndexSearcher searcher;
    private Analyzer analyzer;

    // 代码片段缓存
    private final List<RetrievedCode> codeCache = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        try {
            this.directory = new ByteBuffersDirectory();
            this.analyzer = new StandardAnalyzer();
            log.info("关键词检索服务初始化完成");
        } catch (Exception e) {
            log.error("关键词检索服务初始化失败", e);
        }
    }

    /**
     * 构建关键词索引（幂等：重复调用会重建而非叠加）。
     */
    public synchronized void buildIndex(List<RetrievedCode> allCode) {
        if (allCode == null) {
            log.warn("构建关键词索引入参为空，跳过");
            return;
        }
        try {
            // 1. 关闭旧 reader，避免资源泄漏
            closeReaderQuietly();

            // 2. 重建缓存
            codeCache.clear();
            codeCache.addAll(allCode);

            // 3. 重建索引
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            try (IndexWriter writer = new IndexWriter(directory, config)) {
                writer.deleteAll();
                for (int i = 0; i < allCode.size(); i++) {
                    RetrievedCode code = allCode.get(i);
                    Document doc = new Document();
                    doc.add(new StringField("id", String.valueOf(i), Field.Store.YES));
                    // 其余字段仅用于检索，不落存储
                    addIfNotNull(doc, "content", code.getContent());
                    addIfNotNull(doc, "className", code.getClassName());
                    addIfNotNull(doc, "packageName", code.getPackageName());
                    addIfNotNull(doc, "projectName", code.getProjectName());
                    writer.addDocument(doc);
                }
                writer.commit();
            }

            this.reader = DirectoryReader.open(directory);
            this.searcher = new IndexSearcher(reader);

            log.info("关键词索引构建完成，共 {} 条记录", allCode.size());
        } catch (Exception e) {
            log.error("构建关键词索引失败", e);
        }
    }

    /**
     * 关键词检索（BM25）。
     */
    public List<RetrievedCode> search(String query, int topK) {
        try {
            IndexSearcher localSearcher = this.searcher;
            if (localSearcher == null) {
                log.warn("关键词索引未构建，返回空结果");
                return new ArrayList<>();
            }
            if (query == null || query.isBlank()) {
                return new ArrayList<>();
            }

            int effectiveTopK = Math.max(1, Math.min(topK, MAX_TOP_K));
            QueryParser parser = new QueryParser("content", analyzer);
            Query queryObj = parser.parse(QueryParser.escape(query));

            TopDocs topDocs = localSearcher.search(queryObj, effectiveTopK);
            List<RetrievedCode> results = new ArrayList<>();

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = localSearcher.storedFields().document(scoreDoc.doc);
                int id = Integer.parseInt(doc.get("id"));
                if (id >= 0 && id < codeCache.size()) {
                    RetrievedCode code = codeCache.get(id);
                    // 归一化 BM25 分数到 0-1（启发式，仅供 RRF 排序参考）
                    results.add(new RetrievedCode(
                            code.getContent(),
                            code.getClassName(),
                            code.getPackageName(),
                            code.getProjectName(),
                            scoreDoc.score / 10.0,
                            code.getFilePath(),
                            code.getSegmentType(),
                            code.getMethodName()
                    ));
                }
            }

            log.info("关键词检索完成: query={}, 结果数={}", query, results.size());
            return results;
        } catch (Exception e) {
            log.error("关键词检索失败: query={}", query, e);
            return new ArrayList<>();
        }
    }

    /**
     * 关闭 reader（字节缓冲目录，仍需显式释放）。
     */
    private void closeReaderQuietly() {
        IndexReader old = this.reader;
        this.reader = null;
        this.searcher = null;
        if (old != null) {
            try {
                old.close();
            } catch (IOException e) {
                log.warn("关闭旧索引 reader 失败", e);
            }
        }
    }

    private void addIfNotNull(Document doc, String field, String value) {
        if (value != null && !value.isEmpty()) {
            doc.add(new TextField(field, value, Field.Store.NO));
        }
    }
}

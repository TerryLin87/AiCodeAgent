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

import java.util.ArrayList;
import java.util.List;

//关键词检索服务
@Slf4j
@Service
public class KeywordSearchService {
    private Directory directory;
    private IndexReader reader;
    private IndexSearcher searcher;
    private Analyzer analyzer;

    // 代码片段缓存（用于从 Lucene 文档 ID 还原 RetrievedCode）
    private final List<RetrievedCode> codeCache = new ArrayList<>();

    @PostConstruct
    public void init() {
        try {
            this.directory = new ByteBuffersDirectory();
            this.analyzer = new StandardAnalyzer();
            // 索引在首次调用 buildIndex 时构建
            log.info("关键词检索服务初始化完成");
        } catch (Exception e) {
            log.error("关键词检索服务初始化失败", e);
        }
    }

    /**
     * 构建关键词索引
     */
    public void buildIndex(List<RetrievedCode> allCode) {
        try {
            codeCache.clear();
            codeCache.addAll(allCode);

            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            IndexWriter writer = new IndexWriter(directory, config);

            for (int i = 0; i < allCode.size(); i++) {
                RetrievedCode code = allCode.get(i);
                Document doc = new Document();
                doc.add(new StringField("id", String.valueOf(i), Field.Store.YES));
                doc.add(new TextField("content", code.getContent(), Field.Store.YES));
                doc.add(new TextField("className", code.getClassName(), Field.Store.YES));
                doc.add(new TextField("packageName", code.getPackageName(), Field.Store.YES));
                doc.add(new TextField("projectName", code.getProjectName(), Field.Store.YES));
                writer.addDocument(doc);
            }

            writer.commit();
            writer.close();

            this.reader = DirectoryReader.open(directory);
            this.searcher = new IndexSearcher(reader);

            log.info("关键词索引构建完成，共 {} 条记录", allCode.size());

        } catch (Exception e) {
            log.error("构建关键词索引失败", e);
        }
    }

    /**
     * 关键词检索（BM25）
     */
    public List<RetrievedCode> search(String query, int topK) {
        try {
            if (searcher == null) {
                log.warn("关键词索引未构建，返回空结果");
                return new ArrayList<>();
            }

            QueryParser parser = new QueryParser("content", analyzer);
            Query queryObj = parser.parse(query);

            TopDocs topDocs = searcher.search(queryObj, topK);
            List<RetrievedCode> results = new ArrayList<>();

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                int id = Integer.parseInt(doc.get("id"));
                if (id < codeCache.size()) {
                    RetrievedCode code = codeCache.get(id);
                    // 创建一个新的对象，不修改原对象
                    results.add(new RetrievedCode(
                            code.getContent(),
                            code.getClassName(),
                            code.getPackageName(),
                            code.getProjectName(),
                            scoreDoc.score / 10.0,  // 归一化到 0-1
                            code.getFilePath(),
                            code.getSegmentType(),
                            code.getMethodName()
                    ));
                }
            }

            log.info("关键词检索完成: query={}, 结果数={}", query, results.size());
            return results;

        } catch (Exception e) {
            log.error("关键词检索失败", e);
            return new ArrayList<>();
        }
    }
}

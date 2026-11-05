package com.zlh.aiagent.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 回答引用来源。
 *
 * <p>每条 citation 对应一个被 Agent 实际使用的检索结果，
 * 前端可据此渲染"引用来源"面板。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Citation {

    /** 稳定 ID：project:filePath:className:methodName 的 hash，用于去重 */
    private String id;

    /** 展示标题：如 "UserService.login()" */
    private String title;

    /** 来源类型：code / document / clause / faq */
    private String type;

    /** 文件路径或文档路径 */
    private String filePath;

    /** 项目名（代码领域）或文档来源（文档领域） */
    private String source;

    /** 代码片段/文档片段（截断后） */
    private String snippet;

    /** 相似度分数 */
    private Double score;

    /**
     * 从 RetrievalResult 构造 Citation。
     * 通用方法，不依赖具体领域的 key。
     */
    public static Citation from(RetrievalResult r) {
        if (r == null) return null;

        String title = buildTitle(r);
        String snippet = r.getContent();
        if (snippet != null && snippet.length() > 300) {
            snippet = snippet.substring(0, 300) + "...";
        }

        return Citation.builder()
                .id(r.getSourceId())
                .title(title)
                .type(r.getCategory() != null ? r.getCategory() : "unknown")
                .filePath(r.getMetadata("filePath"))
                .source(r.getSource())
                .snippet(snippet)
                .score(r.getScore())
                .build();
    }

    private static String buildTitle(RetrievalResult r) {
        String className = r.getMetadata("className");
        String methodName = r.getMetadata("methodName");
        if (className != null && methodName != null) {
            return className + "." + methodName + "()";
        }
        if (className != null) {
            return className;
        }
        return r.getTitle() != null ? r.getTitle() : "unknown";
    }
}
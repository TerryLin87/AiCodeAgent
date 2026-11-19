package com.zlh.aiagent.data.law;

import com.zlh.aiagent.data.common.RetrievalResult;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class RetrievedClause implements RetrievalResult {
    private final String content;
    private final String lawName;
    private final String articleNo;
    private final String chapter;
    private final String sourceUrl;
    private final double score;
    private final String docType;

    @Override
    public String getTitle() {
        if (lawName != null && articleNo != null) {
            return lawName + " " + articleNo;
        }
        return lawName != null ? lawName : "未知法条";
    }

    @Override
    public String getSource() {
        return sourceUrl != null ? sourceUrl : "";
    }

    @Override
    public String getCategory() {
        return docType != null ? docType : "LAW";
    }

    @Override
    public double getScore() {
        return score;
    }

    @Override
    public String getMetadata(String key) {
        return switch (key) {
            case "lawName" -> lawName;
            case "articleNo" -> articleNo;
            case "chapter" -> chapter;
            case "sourceUrl" -> sourceUrl;
            case "docType" -> docType;
            default -> null;
        };
    }

    @Override
    public Map<String, String> getAllMetadata() {
        Map<String, String> meta = new HashMap<>();
        if (lawName != null) meta.put("lawName", lawName);
        if (articleNo != null) meta.put("articleNo", articleNo);
        if (chapter != null) meta.put("chapter", chapter);
        if (sourceUrl != null) meta.put("sourceUrl", sourceUrl);
        if (docType != null) meta.put("docType", docType);
        return meta;
    }
}

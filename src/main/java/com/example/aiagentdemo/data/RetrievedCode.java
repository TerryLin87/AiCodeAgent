package com.example.aiagentdemo.data;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class RetrievedCode implements RetrievalResult {
    private final String content;
    private final String className;
    private final String packageName;
    private final String projectName;
    private final double similarity;
    private final String filePath;      // 新增
    private final String segmentType;   // 新增：class / method / interface / enum
    private final String methodName;    // 新增：如果是方法级别，存方法名

    @Override
    public String getTitle() {
        return "";
    }

    @Override
    public String getSource() {
        return "";
    }

    @Override
    public String getCategory() {
        return "";
    }

    @Override
    public double getScore() {
        return 0;
    }

    @Override
    public String getMetadata(String key) {
        return switch (key) {
            case "packageName" -> packageName;
            case "projectName" -> projectName;
            case "methodName" -> methodName;
            default -> null;
        };
    }

    @Override
    public Map<String, String> getAllMetadata() {
        Map<String, String> meta = new HashMap<>();
        if (packageName != null) meta.put("packageName", packageName);
        if (projectName != null) meta.put("projectName", projectName);
        if (methodName != null) meta.put("methodName", methodName);
        return meta;
    }
}

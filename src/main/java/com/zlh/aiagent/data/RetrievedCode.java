package com.zlh.aiagent.data;

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
        // 优先 "类名.方法名()"，其次类名，最后空
        if (className != null && methodName != null && !methodName.isBlank()) {
            return className + "." + methodName + "()";
        }
        return className != null ? className : "";
    }

    @Override
    public String getSource() {
        // 项目名作为 source（文档领域可换成 docSource）
        return projectName != null ? projectName : "";
    }

    @Override
    public String getCategory() {
        // segmentType 作为分类：class / method / interface / enum
        return segmentType != null ? segmentType : "unknown";
    }

    @Override
    public double getScore() {
        return similarity;
    }

    @Override
    public String getMetadata(String key) {
        return switch (key) {
            case "packageName" -> packageName;
            case "projectName" -> projectName;
            case "methodName" -> methodName;
            case "className" -> className;
            case "filePath" -> filePath;          // ★ 补上，Citation 要用
            case "segmentType" -> segmentType;    // ★ 补上
            default -> null;
        };
    }

    @Override
    public Map<String, String> getAllMetadata() {
        Map<String, String> meta = new HashMap<>();
        if (packageName != null) meta.put("packageName", packageName);
        if (projectName != null) meta.put("projectName", projectName);
        if (methodName != null) meta.put("methodName", methodName);
        if (className != null) meta.put("className", className);
        if (filePath != null) meta.put("filePath", filePath);
        if (segmentType != null) meta.put("segmentType", segmentType);
        return meta;
    }
}

package com.example.aiagentdemo.data;

import lombok.Data;

@Data
public class RetrievedCode {
    private final String content;
    private final String className;
    private final String packageName;
    private final String projectName;
    private final double similarity;
    private final String filePath;      // 新增
    private final String segmentType;   // 新增：class / method / interface / enum
    private final String methodName;    // 新增：如果是方法级别，存方法名
}

package com.example.aiagentdemo.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MethodInfo {
    private String projectName;
    private String packageName;
    private String className;
    private String methodName;
    private String returnType;
    private List<String> parameters = new ArrayList<>();
    private List<String> annotations = new ArrayList<>();
    private boolean isStatic;
    private boolean isPublic;
    private boolean isPrivate;
    private boolean isProtected;
    private int lineStart;
    private int lineEnd;
    private String filePath;
}

package com.example.aiagentdemo.data;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CodeSegment {
    private String content;        // 用于向量化的文本，通常为代码的语义表示或摘要
    private String rawCode;        // 原始代码，完整的源代码文本
    private String projectName;    // 所属项目名称
    private String filePath;       // 文件在项目中的相对路径
    private String packageName;    // Java包名，如 com.example.service
    private String className;      // 类名（不含包名）
    private String methodName;     // 方法名（如果是方法级片段）
    private String methodSignature; // 方法签名，包含参数类型和返回类型
    private String typeKind;       // 类型种类：class, interface, enum, annotation 等
    private String segmentType;    // 代码段类型：class, method, interface, enum, constructor, field 等
    private List<String> imports;  // 该文件中的导入语句列表
    private String javadoc;        // Javadoc文档注释内容
}

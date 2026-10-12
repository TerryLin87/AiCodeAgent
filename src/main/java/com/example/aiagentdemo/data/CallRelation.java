package com.example.aiagentdemo.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallRelation {
    // 调用方
    private String callerProject;      // 调用方所在项目
    private String callerPackage;      // 调用方包名
    private String callerClass;        // 调用方类名
    private String callerMethod;       // 调用方方法名
    private int callerLine;            // 调用行号

    // 被调用方
    private String calleeProject;      // 被调用方所在项目
    private String calleePackage;      // 被调用方包名
    private String calleeClass;        // 被调用方类名
    private String calleeMethod;       // 被调用方方法名

    // 调用类型
    private String callType;           // method_call, constructor_call, super_call, interface_call

    // 文件信息
    private String filePath;           // 调用方文件路径
}

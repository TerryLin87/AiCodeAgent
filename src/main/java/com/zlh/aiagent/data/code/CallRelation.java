package com.zlh.aiagent.data.code;

import com.zlh.aiagent.data.common.Relation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallRelation implements Relation {
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

    //region Relation 接口实现

    @Override
    public String getFromId() {
        return (callerProject != null ? callerProject : "unknown")
                + ":" + (callerClass != null ? callerClass : "unknown")
                + "." + (callerMethod != null ? callerMethod : "unknown");
    }

    @Override
    public String getToId() {
        return (calleeProject != null ? calleeProject : "unknown")
                + ":" + (calleeClass != null ? calleeClass : "unknown")
                + "." + (calleeMethod != null ? calleeMethod : "unknown");
    }

    @Override
    public String getRelationType() {
        return callType != null ? callType : "unknown";
    }

    @Override
    public String getFromType() {
        return "method";
    }

    @Override
    public String getToType() {
        return "method";
    }

    @Override
    public Map<String, String> getMetadata() {
        Map<String, String> meta = new HashMap<>();
        meta.put("callerLine", String.valueOf(callerLine));
        if (filePath != null) meta.put("filePath", filePath);
        if (callerPackage != null) meta.put("callerPackage", callerPackage);
        if (calleePackage != null) meta.put("calleePackage", calleePackage);
        return meta;
    }

    //endregion
}

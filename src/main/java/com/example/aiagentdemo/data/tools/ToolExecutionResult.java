package com.example.aiagentdemo.data.tools;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToolExecutionResult {
    /**
     * 是否执行成功
     */
    private boolean success;

    /**
     * 执行结果（成功时的输出）
     */
    private String result;

    /**
     * 错误信息（失败时）
     */
    private String errorMessage;

    /**
     * 错误类型
     */
    private String errorType;

    /**
     * 执行耗时（毫秒）
     */
    private long executionTimeMs;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 使用的参数
     */
    private java.util.Map<String, Object> usedParams;

    /**
     * 是否超时
     */
    private boolean timeout;

    public static ToolExecutionResult success(String toolName, String result, long executionTimeMs) {
        return ToolExecutionResult.builder()
                .success(true)
                .toolName(toolName)
                .result(result)
                .executionTimeMs(executionTimeMs)
                .build();
    }

    public static ToolExecutionResult failure(String toolName, String errorMessage) {
        return ToolExecutionResult.builder()
                .success(false)
                .toolName(toolName)
                .errorMessage(errorMessage)
                .errorType("EXECUTION_ERROR")
                .build();
    }

    public static ToolExecutionResult timeout(String toolName) {
        return ToolExecutionResult.builder()
                .success(false)
                .toolName(toolName)
                .errorMessage("工具执行超时")
                .errorType("TIMEOUT")
                .timeout(true)
                .build();
    }

    public static ToolExecutionResult notFound(String toolName) {
        return ToolExecutionResult.builder()
                .success(false)
                .toolName(toolName)
                .errorMessage("工具不存在: " + toolName)
                .errorType("NOT_FOUND")
                .build();
    }
}

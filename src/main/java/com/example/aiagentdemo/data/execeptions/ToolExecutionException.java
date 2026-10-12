package com.example.aiagentdemo.data.execeptions;

import lombok.Getter;

/**
 * 工具执行异常
 */
@Getter
public class ToolExecutionException extends Exception {
    private final String toolName;
    private final String errorType;

    public ToolExecutionException(String toolName, String message) {
        super(message);
        this.toolName = toolName;
        this.errorType = "UNKNOWN";
    }

    public ToolExecutionException(String toolName, String message, Throwable cause) {
        super(message, cause);
        this.toolName = toolName;
        this.errorType = "UNKNOWN";
    }

    public ToolExecutionException(String toolName, String errorType, String message) {
        super(message);
        this.toolName = toolName;
        this.errorType = errorType;
    }

}

package com.example.aiagentdemo.enums;

//异常枚举
public enum ErrorType {
    NETWORK_ERROR,      // 网络异常
    TIMEOUT,            // 超时
    EMPTY_RESULT,       // 空结果
    PARSE_ERROR,        // 解析异常
    PERMISSION_DENIED,  // 权限不足
    TOOL_NOT_FOUND,     // 工具不存在
    RATE_LIMITED,       // 限流
    UNKNOWN             // 未知异常
}

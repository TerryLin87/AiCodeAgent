package com.example.aiagentdemo.data;

import com.example.aiagentdemo.enums.ErrorType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResult {
    private ErrorType errorType;
    private String message;
    private String details;
    private String recoveryAction;     // 恢复动作
    private boolean isRecoverable;     // 是否可恢复
    private String userMessage;        // 用户友好消息
    private int retryCount;            // 已重试次数
    private int maxRetries;            // 最大重试次数
}

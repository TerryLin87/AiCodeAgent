package com.example.aiagentdemo.data.subagent;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class SubAgentResult {
    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 执行结果内容
     */
    private String content;

    /**
     * 错误信息（失败时）
     */
    private String errorMessage;

    /**
     * 执行耗时（毫秒）
     */
    private long executionTimeMs;

    /**
     * 元数据（可携带额外信息）
     */
    private Map<String, Object> metadata;

    public static SubAgentResult success(String content, long executionTimeMs) {
        return SubAgentResult.builder()
                .success(true)
                .content(content)
                .executionTimeMs(executionTimeMs)
                .build();
    }

    public static SubAgentResult success(String content, long executionTimeMs, Map<String, Object> metadata) {
        return SubAgentResult.builder()
                .success(true)
                .content(content)
                .executionTimeMs(executionTimeMs)
                .metadata(metadata)
                .build();
    }

    public static SubAgentResult failure(String errorMessage, long executionTimeMs) {
        return SubAgentResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .executionTimeMs(executionTimeMs)
                .build();
    }
}

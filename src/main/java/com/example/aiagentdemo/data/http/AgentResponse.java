package com.example.aiagentdemo.data.http;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class AgentResponse {
    private String content;
    private String sessionId;
    private List<String> toolCalls;
    private long executionTimeMs;
    private Map<String, Object> metadata;  // 扩展字段
}

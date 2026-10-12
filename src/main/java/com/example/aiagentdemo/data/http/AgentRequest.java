package com.example.aiagentdemo.data.http;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class AgentRequest {
    private String sessionId;
    private String userMessage;
    private Map<String, Object> context;  // 扩展字段，可放入场景特定信息
    private AgentConfig config;            // 场景配置
    private List<String> toolNames;
}

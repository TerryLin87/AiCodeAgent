package com.example.aiagentdemo.data.http;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AgentConfig {
    private String scenarioId;             // 场景ID: "coding", "legal", "enterprise"
    private String systemPrompt;
    private List<String> toolNames;        // 该场景启用的工具
    private String knowledgeBaseId;        // 对应的知识库
    private String modelName;              // 可用不同的模型
    private int maxIterations;             // 最大循环次数
    private double temperature;            // 模型温度参数
}

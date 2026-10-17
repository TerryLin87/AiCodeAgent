package com.example.aiagentdemo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "agent.domain")
public class DomainConfig {

    // 领域标识：code / law / medical
    private String name = "code";

    // 领域显示名
    private String displayName = "Java 代码助手";

    // System Prompt（从配置加载）
    private String systemPrompt;

    // 子 Agent 名称列表
    private List<String> subAgents;

    // 评估维度
    private List<EvaluationDimension> evaluationDimensions;

    @Data
    public static class EvaluationDimension {
        private String name;
        private String label;
        private String description;
    }
}
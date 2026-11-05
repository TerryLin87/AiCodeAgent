package com.zlh.aiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "agent.domain")
public class DomainConfig {
    /** 领域标识：code / law / medical */
    private String name = "code";

    /** 领域显示名 */
    private String displayName = "Java 代码助手";

    /** System Prompt（从配置加载） */
    private String systemPrompt;

    /** 子 Agent 名称列表 */
    private List<String> subAgents;

    /** 评估维度 */
    private List<EvaluationDimension> evaluationDimensions;

    /** 默认路由 Agent（所有子 Agent 都不匹配时选它） */
    private String defaultAgent = "explore";

    /** 该领域可用的工具名清单（供 PlanSubAgent 生成计划时参考） */
    private List<String> supportedActions;

    /** 额外路由关键词 → agent 名（与各 ISubAgent.getRoutingKeywords() 合并） */
    private Map<String, String> routingKeywords;

    /** 各评估维度权重（key = dimension.name），缺省为 1.0 */
    private Map<String, Double> dimensionWeights;

    /** 通过阈值（0~10） */
    private double passThreshold = 7.0;

    /** 摘要生成 Prompt 模板，{content} 会被替换为对话内容 */
    private String summaryPromptTemplate;

    /** 检索默认 minScore */
    private double retrievalMinScore = 0.3;

    /** 是否支持代码解析（决定 JavaCodeParser 是否启用） */
    private boolean supportsCodeParsing = true;

    /** 领域单会话最大上下文 token */
    private int maxContextTokens = 8000;

    /** 需要从 PlanSubAgent 动作清单中排除的工具名（可选黑名单） */
    private List<String> excludedActions;

    // ================================================================
    // 评估维度（原有内部类，扩展权重字段）
    // ================================================================

    @Data
    public static class EvaluationDimension {
        private String name;
        private String label;
        private String description;

        /** 权重，缺省 1.0；显式配置优先于 dimensionWeights */
        private Double weight;
    }
}
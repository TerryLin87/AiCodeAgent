package com.zlh.aiagent.service;

import com.zlh.aiagent.config.DomainConfig;
import com.zlh.aiagent.data.EvaluationResult;
import com.zlh.aiagent.data.execeptions.ModelCircuitOpenException;
import com.zlh.aiagent.data.execeptions.ModelUnavailableException;
import com.zlh.aiagent.enums.CallType;
import com.zlh.aiagent.model.DeepSeekModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 自我评估服务。
 *
 * <p>T3 改造：评估维度、权重、通过阈值从 {@link DomainConfig} 读取，不再硬编码。
 *
 * <p>维度定义来自 {@code agent.domain.evaluation-dimensions}：
 * <ul>
 *   <li>{@code name}   —— 维度字段名（correctness / compliance / ...）</li>
 *   <li>{@code label}  —— 中文标签，用于 Prompt 和报告</li>
 *   <li>{@code description} —— 给 LLM 的评估标准</li>
 *   <li>{@code weight} —— 权重，缺省 1.0</li>
 * </ul>
 *
 * <p>通过阈值来自 {@code agent.domain.pass-threshold}（默认 7.0）。
 */
@Slf4j
@Service
public class SelfEvaluationService {

    @Autowired
    @Qualifier("DeepSeekModel")
    private DeepSeekModel deepSeekModel;

    @Autowired
    private DomainConfig domainConfig;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 降级时的保守分值 */
    private static final double FALLBACK_SCORE = 5.0;

    /**
     * 自我评估：按当前领域的维度定义评估内容质量。
     */
    public EvaluationResult evaluate(String content, String context) {
        List<DomainConfig.EvaluationDimension> dimensions = dimensions();
        log.info("开始自我评估，维度数={}, 维度={}", dimensions.size(),
                dimensions.stream().map(DomainConfig.EvaluationDimension::getName).toList());

        String prompt = buildEvaluationPrompt(content, context, dimensions);

        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(UserMessage.from(prompt)))
                    .build();
            ChatResponse response = deepSeekModel.doChat(request, CallType.EVALUATION, null);
            String text = response.aiMessage() != null ? response.aiMessage().text() : null;
            log.debug("评估响应长度: {}", text != null ? text.length() : 0);
            return parseEvaluation(text, dimensions);
        } catch (ModelCircuitOpenException e) {
            log.warn("评估熔断打开，返回保守默认结果: {}", e.getMessage());
            return fallbackResult("评估服务熔断，返回保守默认结果", dimensions);
        } catch (ModelUnavailableException e) {
            log.warn("评估模型不可用，返回保守默认结果: {}", e.getMessage());
            return fallbackResult("评估模型不可用：" + e.getMessage(), dimensions);
        } catch (Exception e) {
            log.error("评估失败", e);
            return fallbackResult("评估服务异常：" + e.getMessage(), dimensions);
        }
    }

    // ================================================================
    // Prompt 构建（动态维度）
    // ================================================================

    private String buildEvaluationPrompt(String content, String context,
                                         List<DomainConfig.EvaluationDimension> dimensions) {
        StringBuilder dimsPrompt = new StringBuilder();
        int idx = 1;
        for (DomainConfig.EvaluationDimension d : dimensions) {
            dimsPrompt.append(idx++).append(". ").append(d.getLabel())
                    .append("：").append(d.getDescription()).append("\n");
        }

        // 动态 JSON 示例
        StringBuilder jsonExample = new StringBuilder("{\n");
        for (int i = 0; i < dimensions.size(); i++) {
            DomainConfig.EvaluationDimension d = dimensions.get(i);
            jsonExample.append("    \"").append(d.getName()).append("\": 8.5");
            if (i < dimensions.size() - 1) jsonExample.append(",");
            jsonExample.append("\n");
        }
        jsonExample.append("    \"strengths\": [\"优点1\", \"优点2\"],\n");
        jsonExample.append("    \"weaknesses\": [\"缺点1\", \"缺点2\"],\n");
        jsonExample.append("    \"suggestions\": [\"建议1\", \"建议2\"]\n");
        jsonExample.append("}");

        return String.format("""
            你是一个质量评估专家。请评估以下代码/回答的质量。

            【上下文/需求】
            %s

            【待评估内容】
            %s

            请从以下维度评分（1-10分）：
            %s
            同时请列出：
            - 优点（2-3个）
            - 缺点（2-3个）
            - 改进建议（2-3个）

            输出 JSON 格式：
            %s

            要求：只输出 JSON 本身，不要输出任何解释文字或 Markdown 代码块标记。
            """, context, content, dimsPrompt, jsonExample);
    }

    // ================================================================
    // 解析
    // ================================================================

    private EvaluationResult parseEvaluation(String raw,
                                             List<DomainConfig.EvaluationDimension> dimensions) throws Exception {
        String json = extractJsonObject(raw);
        if (json == null) {
            throw new IllegalArgumentException("响应中未找到 JSON 对象");
        }

        JsonNode jsonNode = objectMapper.readTree(json);

        // 动态维度：Map<String, Double>
        Map<String, Double> dimensionScores = new LinkedHashMap<>();
        for (DomainConfig.EvaluationDimension d : dimensions) {
            double score = safeScore(jsonNode, d.getName(), FALLBACK_SCORE);
            dimensionScores.put(d.getName(), score);
        }

        EvaluationResult result = EvaluationResult.builder()
                .dimensionScores(dimensionScores)
                .strengths(new ArrayList<>(safeList(jsonNode, "strengths")))
                .weaknesses(new ArrayList<>(safeList(jsonNode, "weaknesses")))
                .suggestions(new ArrayList<>(safeList(jsonNode, "suggestions")))
                .build();

        calculateOverall(result, dimensions);
        return result;
    }

    /**
     * 按权重计算总分，并按 passThreshold 判定是否通过。
     */
    private void calculateOverall(EvaluationResult result,
                                  List<DomainConfig.EvaluationDimension> dimensions) {
        double weightedSum = 0.0;
        double weightSum = 0.0;

        for (DomainConfig.EvaluationDimension d : dimensions) {
            double score = result.getDimensionScores()
                    .getOrDefault(d.getName(), FALLBACK_SCORE);
            double weight = d.getWeight() != null ? d.getWeight() : 1.0;
            weightedSum += score * weight;
            weightSum += weight;
        }

        double overall = weightSum > 0 ? weightedSum / weightSum : FALLBACK_SCORE;
        double passThreshold = domainConfig != null ? domainConfig.getPassThreshold() : 7.0;

        result.setOverallScore(overall);
        result.setPassed(overall >= passThreshold);
    }

    // ================================================================
    // 工具方法
    // ================================================================

    /** 从 DomainConfig 读维度列表，兜底为默认 5 维度（防止配置缺失） */
    private List<DomainConfig.EvaluationDimension> dimensions() {
        if (domainConfig != null && domainConfig.getEvaluationDimensions() != null
                && !domainConfig.getEvaluationDimensions().isEmpty()) {
            return domainConfig.getEvaluationDimensions();
        }
        // 兜底：与 T0 默认一致
        return List.of(
                dim("correctness", "正确性", "逻辑是否正确，是否有明显错误", 1.0),
                dim("completeness", "完整性", "是否完整回答了问题，是否遗漏重要内容", 1.0),
                dim("clarity", "清晰度", "是否清晰易懂，结构是否合理", 1.0),
                dim("normativity", "规范性", "是否符合编码规范，命名是否合理", 1.0),
                dim("usability", "可用性", "是否可以直接使用，是否需要额外修改", 1.0)
        );
    }

    private DomainConfig.EvaluationDimension dim(String name, String label, String desc, Double weight) {
        DomainConfig.EvaluationDimension d = new DomainConfig.EvaluationDimension();
        d.setName(name);
        d.setLabel(label);
        d.setDescription(desc);
        d.setWeight(weight);
        return d;
    }

    private String extractJsonObject(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.contains("```")) {
            text = text.replaceAll("```(?:json|JSON)?", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return text.substring(start, end + 1);
    }

    private double safeScore(JsonNode node, String field, double defaultValue) {
        if (node == null) return defaultValue;
        JsonNode valueNode = node.get(field);
        if (valueNode == null || valueNode.isNull()) return defaultValue;

        double value;
        if (valueNode.isNumber()) {
            value = valueNode.asDouble();
        } else {
            String text = valueNode.asText("").replaceAll("[^0-9.\\-]", "");
            if (text.isEmpty()) return defaultValue;
            try {
                value = Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        if (Double.isNaN(value)) return defaultValue;
        return Math.max(0.0, Math.min(10.0, value));
    }

    private List<String> safeList(JsonNode node, String field) {
        List<String> results = new ArrayList<>();
        if (node == null) return results;
        JsonNode valueNode = node.get(field);
        if (valueNode == null || valueNode.isNull()) return results;
        if (valueNode.isArray()) {
            valueNode.forEach(item -> {
                String text = item.asText("").trim();
                if (!text.isEmpty()) results.add(text);
            });
        } else if (valueNode.isTextual()) {
            String text = valueNode.asText("").trim();
            if (!text.isEmpty()) results.add(text);
        }
        return results;
    }

    private EvaluationResult fallbackResult(String reason,
                                            List<DomainConfig.EvaluationDimension> dimensions) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (DomainConfig.EvaluationDimension d : dimensions) {
            scores.put(d.getName(), FALLBACK_SCORE);
        }
        EvaluationResult result = EvaluationResult.builder()
                .dimensionScores(scores)
                .overallScore(FALLBACK_SCORE)
                .passed(false)
                .strengths(new ArrayList<>())
                .weaknesses(new ArrayList<>(List.of(reason)))
                .suggestions(new ArrayList<>())
                .build();
        return result;
    }

    public boolean isPassed(EvaluationResult result) {
        return result.isPassed();
    }

    /**
     * 生成评估报告（按当前领域维度动态输出）。
     */
    public String formatReport(EvaluationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("质量评估报告\n");
        sb.append("=".repeat(50)).append("\n\n");

        List<DomainConfig.EvaluationDimension> dims = dimensions();
        sb.append("各维度评分:\n");
        for (DomainConfig.EvaluationDimension d : dims) {
            double score = result.getDimensionScores() != null
                    ? result.getDimensionScores().getOrDefault(d.getName(), FALLBACK_SCORE)
                    : FALLBACK_SCORE;
            sb.append("  ").append(d.getLabel()).append(": ").append(score).append("/10\n");
        }

        sb.append("\n总分: ").append(String.format("%.1f", result.getOverallScore())).append("/10\n");
        sb.append("状态: ").append(result.isPassed() ? "通过" : "未通过").append("\n\n");

        if (result.getStrengths() != null && !result.getStrengths().isEmpty()) {
            sb.append("优点:\n");
            for (String s : result.getStrengths()) sb.append(" ── ").append(s).append("\n");
            sb.append("\n");
        }
        if (result.getWeaknesses() != null && !result.getWeaknesses().isEmpty()) {
            sb.append("缺点:\n");
            for (String s : result.getWeaknesses()) sb.append("  ── ").append(s).append("\n");
            sb.append("\n");
        }
        if (result.getSuggestions() != null && !result.getSuggestions().isEmpty()) {
            sb.append("改进建议:\n");
            for (String s : result.getSuggestions()) sb.append("  ── ").append(s).append("\n");
        }
        return sb.toString();
    }
}
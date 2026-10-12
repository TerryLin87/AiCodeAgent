package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.EvaluationResult;
import com.example.aiagentdemo.model.DeepSeekModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 自我评估服务。
 */
@Slf4j
@Service
public class SelfEvaluationService {

    @Autowired
    @Qualifier("DeepSeekModel")
    private DeepSeekModel deepSeekModel;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 评估阈值
    private static final double PASS_THRESHOLD = 7.0;

    // 降级时的保守分值
    private static final double FALLBACK_SCORE = 5.0;

    /**
     * 自我评估：评估代码/回答的质量。
     */
    public EvaluationResult evaluate(String content, String context) {
        log.info("开始自我评估");

        String prompt = buildEvaluationPrompt(content, context);

        try {
            String response = deepSeekModel.chat(prompt);
            log.debug("评估响应: {}", response);
            return parseEvaluation(response);
        } catch (Exception e) {
            log.error("评估失败", e);
            return fallbackResult("评估服务异常或返回内容无法解析：" + e.getMessage());
        }
    }

    /**
     * 构建评估 Prompt。
     */
    private String buildEvaluationPrompt(String content, String context) {
        return String.format("""
            你是一个代码质量评估专家。请评估以下代码/回答的质量。

            【上下文/需求】
            %s

            【待评估内容】
            %s

            请从以下维度评分（1-10分）：
            1. 正确性：逻辑是否正确，是否有明显错误
            2. 完整性：是否完整回答了问题，是否遗漏重要内容
            3. 清晰度：是否清晰易懂，结构是否合理
            4. 规范性：是否符合编码规范，命名是否合理
            5. 可用性：是否可以直接使用，是否需要额外修改

            同时请列出：
            - 优点（2-3个）
            - 缺点（2-3个）
            - 改进建议（2-3个）

            输出 JSON 格式：
            {
                "correctness": 8.5,
                "completeness": 7.0,
                "clarity": 9.0,
                "normativity": 7.5,
                "usability": 6.5,
                "strengths": ["优点1", "优点2"],
                "weaknesses": ["缺点1", "缺点2"],
                "suggestions": ["建议1", "建议2"]
            }

            要求：只输出 JSON 本身，不要输出任何解释文字或 Markdown 代码块标记。
            """, context, content);
    }

    /**
     * 解析评估结果。
     */
    private EvaluationResult parseEvaluation(String raw) throws Exception {
        String json = extractJsonObject(raw);
        if (json == null) {
            throw new IllegalArgumentException("响应中未找到 JSON 对象");
        }

        JsonNode jsonNode = objectMapper.readTree(json);

        EvaluationResult result = EvaluationResult.builder()
                .correctness(safeScore(jsonNode, "correctness", FALLBACK_SCORE))
                .completeness(safeScore(jsonNode, "completeness", FALLBACK_SCORE))
                .clarity(safeScore(jsonNode, "clarity", FALLBACK_SCORE))
                .normativity(safeScore(jsonNode, "normativity", FALLBACK_SCORE))
                .usability(safeScore(jsonNode, "usability", FALLBACK_SCORE))
                .strengths(new ArrayList<>())
                .weaknesses(new ArrayList<>())
                .suggestions(new ArrayList<>())
                .build();

        result.getStrengths().addAll(safeList(jsonNode, "strengths"));
        result.getWeaknesses().addAll(safeList(jsonNode, "weaknesses"));
        result.getSuggestions().addAll(safeList(jsonNode, "suggestions"));

        result.calculateOverall();

        // 兜底：LLM 没给总分时，用各维度均值，避免总分恒为 0 被判不通过
        if (result.getOverallScore() <= 0) {
            double avg = (result.getCorrectness() + result.getCompleteness() + result.getClarity()
                    + result.getNormativity() + result.getUsability()) / 5.0;
            result.setOverallScore(avg);
            result.setPassed(avg >= PASS_THRESHOLD);
        }

        return result;
    }

    /**
     * 从任意文本中抠出最外层的 JSON 对象。
     * 兼容 ```json 围栏、前后夹带的自然语言说明。
     */
    private String extractJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        // 去掉代码块围栏
        if (text.contains("```")) {
            text = text.replaceAll("```(?:json|JSON)?", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /**
     * 宽容解析分值：兼容数字、字符串数字、带单位文本（"8.5分"），并钳位到 0~10。
     */
    private double safeScore(JsonNode node, String field, double defaultValue) {
        if (node == null) {
            return defaultValue;
        }
        JsonNode valueNode = node.get(field);
        if (valueNode == null || valueNode.isNull()) {
            return defaultValue;
        }

        double value;
        if (valueNode.isNumber()) {
            value = valueNode.asDouble();
        } else {
            String text = valueNode.asText("").replaceAll("[^0-9.\\-]", "");
            if (text.isEmpty()) {
                return defaultValue;
            }
            try {
                value = Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        if (Double.isNaN(value)) {
            return defaultValue;
        }
        return Math.max(0.0, Math.min(10.0, value));
    }

    /**
     * 宽容解析字符串数组：兼容 JSON 数组与单个字符串。
     */
    private List<String> safeList(JsonNode node, String field) {
        List<String> results = new ArrayList<>();
        if (node == null) {
            return results;
        }
        JsonNode valueNode = node.get(field);
        if (valueNode == null || valueNode.isNull()) {
            return results;
        }
        if (valueNode.isArray()) {
            valueNode.forEach(item -> {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    results.add(text);
                }
            });
        } else if (valueNode.isTextual()) {
            String text = valueNode.asText("").trim();
            if (!text.isEmpty()) {
                results.add(text);
            }
        }
        return results;
    }

    /**
     * 降级结果：保守判为未通过。
     */
    private EvaluationResult fallbackResult(String reason) {
        EvaluationResult result = EvaluationResult.createDefault();
        result.setOverallScore(FALLBACK_SCORE);
        result.setPassed(false);
        if (result.getWeaknesses() != null) {
            result.getWeaknesses().add(reason);
        }
        return result;
    }

    /**
     * 判断是否通过评估。
     */
    public boolean isPassed(EvaluationResult result) {
        return result.isPassed();
    }

    /**
     * 生成评估报告（可读格式）。
     */
    public String formatReport(EvaluationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("质量评估报告\n");
        sb.append("=".repeat(50)).append("\n\n");

        sb.append("各维度评分:\n");
        sb.append("  正确性: ").append(result.getCorrectness()).append("/10\n");
        sb.append("  完整性: ").append(result.getCompleteness()).append("/10\n");
        sb.append("  清晰度: ").append(result.getClarity()).append("/10\n");
        sb.append("  规范性: ").append(result.getNormativity()).append("/10\n");
        sb.append("  可用性: ").append(result.getUsability()).append("/10\n\n");

        sb.append("总分: ").append(String.format("%.1f", result.getOverallScore())).append("/10\n");
        sb.append("状态: ").append(result.isPassed() ? "通过" : "未通过").append("\n\n");

        if (!result.getStrengths().isEmpty()) {
            sb.append("优点:\n");
            for (String s : result.getStrengths()) {
                sb.append(" ── ").append(s).append("\n");
            }
            sb.append("\n");
        }

        if (!result.getWeaknesses().isEmpty()) {
            sb.append("缺点:\n");
            for (String s : result.getWeaknesses()) {
                sb.append("  ── ").append(s).append("\n");
            }
            sb.append("\n");
        }

        if (!result.getSuggestions().isEmpty()) {
            sb.append("改进建议:\n");
            for (String s : result.getSuggestions()) {
                sb.append("  ── ").append(s).append("\n");
            }
        }

        return sb.toString();
    }
}

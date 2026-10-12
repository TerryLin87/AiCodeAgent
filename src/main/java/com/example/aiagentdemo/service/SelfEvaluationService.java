package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.EvaluationResult;
import com.example.aiagentdemo.model.DeepSeekModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SelfEvaluationService {

    @Autowired
    @Qualifier("DeepSeekModel")
    private DeepSeekModel deepSeekModel;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 评估阈值
    private static final double PASS_THRESHOLD = 7.0;
    private static final int MAX_FIX_ATTEMPTS = 3;

    /**
     * 自我评估：评估代码/回答的质量
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
            // 返回默认评估（保守）
            EvaluationResult result = EvaluationResult.createDefault();
            result.setOverallScore(6.0);
            result.setPassed(false);
            result.getWeaknesses().add("评估服务异常，无法完成质量检查");
            return result;
        }
    }

    /**
     * 构建评估 Prompt
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
            """, context, content);
    }

    /**
     * 解析评估结果
     */
    private EvaluationResult parseEvaluation(String json) throws Exception {
        // 清理 JSON
        String cleanJson = json;
        if (cleanJson.contains("```json")) {
            cleanJson = cleanJson.replaceAll("```json\\s*", "").replaceAll("\\s*```", "");
        }
        if (cleanJson.contains("```")) {
            cleanJson = cleanJson.replaceAll("```\\s*", "");
        }

        var jsonNode = objectMapper.readTree(cleanJson);

        EvaluationResult result = EvaluationResult.builder()
                .correctness(jsonNode.has("correctness") ? jsonNode.get("correctness").asDouble() : 5.0)
                .completeness(jsonNode.has("completeness") ? jsonNode.get("completeness").asDouble() : 5.0)
                .clarity(jsonNode.has("clarity") ? jsonNode.get("clarity").asDouble() : 5.0)
                .normativity(jsonNode.has("normativity") ? jsonNode.get("normativity").asDouble() : 5.0)
                .usability(jsonNode.has("usability") ? jsonNode.get("usability").asDouble() : 5.0)
                .strengths(new java.util.ArrayList<>())
                .weaknesses(new java.util.ArrayList<>())
                .suggestions(new java.util.ArrayList<>())
                .build();

        // 提取优点
        if (jsonNode.has("strengths")) {
            jsonNode.get("strengths").forEach(s -> result.getStrengths().add(s.asText()));
        }

        // 提取缺点
        if (jsonNode.has("weaknesses")) {
            jsonNode.get("weaknesses").forEach(s -> result.getWeaknesses().add(s.asText()));
        }

        // 提取建议
        if (jsonNode.has("suggestions")) {
            jsonNode.get("suggestions").forEach(s -> result.getSuggestions().add(s.asText()));
        }

        // 计算总分和通过状态
        result.calculateOverall();

        return result;
    }

    /**
     * 判断是否通过评估
     */
    public boolean isPassed(EvaluationResult result) {
        return result.isPassed();
    }

    /**
     * 生成评估报告（可读格式）
     */
    public String formatReport(EvaluationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 质量评估报告\n");
        sb.append("=".repeat(50)).append("\n\n");

        sb.append("📈 各维度评分:\n");
        sb.append("  ├─ 正确性: ").append(result.getCorrectness()).append("/10\n");
        sb.append("  ├─ 完整性: ").append(result.getCompleteness()).append("/10\n");
        sb.append("  ├─ 清晰度: ").append(result.getClarity()).append("/10\n");
        sb.append("  ├─ 规范性: ").append(result.getNormativity()).append("/10\n");
        sb.append("  └─ 可用性: ").append(result.getUsability()).append("/10\n\n");

        sb.append("📊 总分: ").append(String.format("%.1f", result.getOverallScore())).append("/10\n");
        sb.append("📌 状态: ").append(result.isPassed() ? "✅ 通过" : "❌ 未通过").append("\n\n");

        if (!result.getStrengths().isEmpty()) {
            sb.append("✅ 优点:\n");
            for (String s : result.getStrengths()) {
                sb.append("  ├─ ").append(s).append("\n");
            }
            sb.append("\n");
        }

        if (!result.getWeaknesses().isEmpty()) {
            sb.append("⚠️ 缺点:\n");
            for (String s : result.getWeaknesses()) {
                sb.append("  ├─ ").append(s).append("\n");
            }
            sb.append("\n");
        }

        if (!result.getSuggestions().isEmpty()) {
            sb.append("💡 改进建议:\n");
            for (String s : result.getSuggestions()) {
                sb.append("  ├─ ").append(s).append("\n");
            }
        }

        return sb.toString();
    }
}

package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.AutoFixResult;
import com.example.aiagentdemo.data.EvaluationResult;
import com.example.aiagentdemo.model.DeepSeekModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AutoFixerService {

    @Autowired
    @Qualifier("DeepSeekModel")
    private DeepSeekModel deepSeekModel;

    @Autowired
    private SelfEvaluationService evaluationService;

    private static final int MAX_FIX_ATTEMPTS = 3;

    /**
     * 自动修正：根据评估结果修正代码/回答
     */
    public AutoFixResult fix(String content, String context, EvaluationResult evaluation) {
        log.info("开始自动修正");

        String currentContent = content;
        EvaluationResult currentEvaluation = evaluation;
        int attempt = 0;

        while (attempt < MAX_FIX_ATTEMPTS && !currentEvaluation.isPassed()) {
            attempt++;
            log.info("修正尝试 {}/{}", attempt, MAX_FIX_ATTEMPTS);

            // 1. 生成修正方案
            String fixPrompt = buildFixPrompt(currentContent, context, currentEvaluation);
            String fixedContent = deepSeekModel.chat(fixPrompt);

            // 2. 重新评估
            currentEvaluation = evaluationService.evaluate(fixedContent, context);
            currentContent = fixedContent;

            log.info("修正后评分: {}/10", currentEvaluation.getOverallScore());
        }

        // 生成修正报告
        return AutoFixResult.builder()
                .finalContent(currentContent)
                .finalEvaluation(currentEvaluation)
                .attempts(attempt)
                .passed(currentEvaluation.isPassed())
                .message(currentEvaluation.isPassed() ?
                        "✅ 经过 " + attempt + " 轮修正，质量已达标" :
                        "⚠️ 经过 " + attempt + " 轮修正，质量仍未达标，建议人工审核")
                .build();
    }

    /**
     * 构建修正 Prompt
     */
    private String buildFixPrompt(String content, String context, EvaluationResult evaluation) {
        StringBuilder sb = new StringBuilder();

        sb.append("请根据以下评估结果修正代码/回答。\n\n");

        sb.append("【原始内容】\n");
        sb.append(content).append("\n\n");

        sb.append("【评估结果】\n");
        sb.append("总分: ").append(evaluation.getOverallScore()).append("/10\n");
        sb.append("正确性: ").append(evaluation.getCorrectness()).append("/10\n");
        sb.append("完整性: ").append(evaluation.getCompleteness()).append("/10\n");
        sb.append("清晰度: ").append(evaluation.getClarity()).append("/10\n");
        sb.append("规范性: ").append(evaluation.getNormativity()).append("/10\n");
        sb.append("可用性: ").append(evaluation.getUsability()).append("/10\n\n");

        if (!evaluation.getWeaknesses().isEmpty()) {
            sb.append("【需要改进的问题】\n");
            for (String w : evaluation.getWeaknesses()) {
                sb.append("- ").append(w).append("\n");
            }
            sb.append("\n");
        }

        if (!evaluation.getSuggestions().isEmpty()) {
            sb.append("【改进建议】\n");
            for (String s : evaluation.getSuggestions()) {
                sb.append("- ").append(s).append("\n");
            }
            sb.append("\n");
        }

        sb.append("请输出修正后的完整内容，确保所有问题都已解决。");

        return sb.toString();
    }
}

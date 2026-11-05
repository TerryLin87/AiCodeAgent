package com.zlh.aiagent.service;

import com.zlh.aiagent.data.AutoFixResult;
import com.zlh.aiagent.data.EvaluationResult;
import com.zlh.aiagent.data.execeptions.ModelCircuitOpenException;
import com.zlh.aiagent.data.execeptions.ModelUnavailableException;
import com.zlh.aiagent.enums.CallType;
import com.zlh.aiagent.model.DeepSeekModel;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

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

            String fixedContent;
            try {
                String fixPrompt = buildFixPrompt(currentContent, context, currentEvaluation);
                ChatRequest request = ChatRequest.builder()
                        .messages(List.of(UserMessage.from(fixPrompt)))
                        .build();
                ChatResponse response = deepSeekModel.doChat(request, CallType.MAIN_CHAT, null);
                fixedContent = response.aiMessage() != null ? response.aiMessage().text() : null;
            } catch (ModelCircuitOpenException e) {
                log.warn("修正熔断打开，放弃本轮修正: {}", e.getMessage());
                break;
            } catch (ModelUnavailableException e) {
                log.warn("修正模型不可用，放弃本轮修正: {}", e.getMessage());
                break;
            } catch (Exception e) {
                log.warn("修正调用异常，放弃本轮修正: {}", e.getMessage());
                break;
            }

            if (fixedContent == null || fixedContent.isBlank()) {
                log.warn("修正返回为空，放弃本轮修正");
                break;
            }

            try {
                currentEvaluation = evaluationService.evaluate(fixedContent, context);
                currentContent = fixedContent;
                log.info("修正后评分: {}/10", currentEvaluation.getOverallScore());
            } catch (Exception e) {
                // evaluate 内部已经降级为 fallbackResult，不会抛到这里；这里只做兜底防御
                log.warn("重新评估异常，保留当前内容: {}", e.getMessage());
                break;
            }
        }

        // 生成修正报告
        return AutoFixResult.builder()
                .finalContent(currentContent)
                .finalEvaluation(currentEvaluation)
                .attempts(attempt)
                .passed(currentEvaluation.isPassed())
                .message(currentEvaluation.isPassed() ?
                        "经过 " + attempt + " 轮修正，质量已达标" :
                        "经过 " + attempt + " 轮修正，质量仍未达标，建议人工审核")
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

        // 动态维度
        if (evaluation.getDimensionScores() != null) {
            evaluation.getDimensionScores().forEach((name, score) ->
                    sb.append(name).append(": ").append(score).append("/10\n"));
        }
        sb.append("\n");

        if (evaluation.getWeaknesses() != null && !evaluation.getWeaknesses().isEmpty()) {
            sb.append("【需要改进的问题】\n");
            for (String w : evaluation.getWeaknesses()) {
                sb.append("- ").append(w).append("\n");
            }
            sb.append("\n");
        }

        if (evaluation.getSuggestions() != null && !evaluation.getSuggestions().isEmpty()) {
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

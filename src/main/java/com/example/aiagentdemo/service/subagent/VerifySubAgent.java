package com.example.aiagentdemo.service.subagent;

import com.example.aiagentdemo.data.EvaluationResult;
import com.example.aiagentdemo.data.subagent.SubAgentResult;
import com.example.aiagentdemo.instances.subagent.ISubAgent;
import com.example.aiagentdemo.service.SelfEvaluationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

@Slf4j
@Component
public class VerifySubAgent implements ISubAgent {

    @Autowired
    public SelfEvaluationService selfEvaluationService;

    @Override
    public String getName() {
        return "verify";
    }

    @Override
    public String getCapability() {
        return """
                验证专家 - 当需要验证结果质量时使用我：
                - 检查代码是否正确
                - 验证修改是否满足需求
                - 评估代码质量
                - 检查是否存在潜在问题
                """;
    }

    @Override
    public SubAgentResult execute(String task, Map<String, Object> context) {
        log.info("VerifySubAgent 执行任务：{}",task);
        long startTime = System.currentTimeMillis();

        try{
            //获取要验证的内容
            String content = (String)context.getOrDefault("content", "");
            String requirement = (String)context.getOrDefault("requirement", task);

            if(!StringUtils.hasText(content)){
                return SubAgentResult.failure("没有需要验证的内容",System.currentTimeMillis()-startTime);
            }

            //如果内容包含代码 用SelfEvaluationService评估
            if(content.contains("```") || content.contains("class ") || content.contains("public")){
                EvaluationResult result = selfEvaluationService.evaluate(content,requirement);
                String report = selfEvaluationService.formatReport(result);
                return SubAgentResult.success(report,System.currentTimeMillis()-startTime);
            } else{
                //简单验证，检验是否包含关键信息
                String verification = simpleVerity(content,requirement);
                return SubAgentResult.success(verification,System.currentTimeMillis()-startTime);
            }
        }catch (Exception e){
            log.error("VeriSubAgent 执行失败",e);
            return SubAgentResult.failure("验证失败：" + e.getMessage(),System.currentTimeMillis()-startTime);
        }
    }

    /**
     * 简单验证（非代码场景)
     */
    private String simpleVerity(String content,String requirement){
        StringBuilder sb = new StringBuilder();
        sb.append("验证结果:\n");
        sb.append("=".repeat(50)).append("\n");

        // 检查内容长度
        if (content.length() < 50) {
            sb.append("内容过短（").append(content.length()).append(" 字符），可能不完整\n");
        } else {
            sb.append("内容长度合适（").append(content.length()).append(" 字符）\n");
        }

        // 检查是否包含关键词（简单判断）
        String[] keywords = extractKeywords(requirement);
        int found = 0;
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                found++;
            }
        }

        if (found > 0) {
            sb.append("包含 ").append(found).append("/")
                    .append(keywords.length).append(" 个关键词\n");
        } else {
            sb.append("未找到任何关键词\n");
        }

        return sb.toString();
    }

    private String[] extractKeywords(String requirement) {
        // 简单提取，实际可以用 NLP
        return requirement.split(" ");
    }
}

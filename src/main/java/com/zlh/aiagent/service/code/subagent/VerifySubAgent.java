package com.zlh.aiagent.service.code.subagent;

import com.hankcs.hanlp.HanLP;
import com.zlh.aiagent.data.common.EvaluationResult;
import com.zlh.aiagent.data.common.subagent.SubAgentResult;
import com.zlh.aiagent.instances.subagent.ISubAgent;
import com.zlh.aiagent.service.code.SelfEvaluationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
//只有当配置文件里面配置的环境是code时才会加载该子agent
@ConditionalOnProperty(name = "agent.domain.name", havingValue = "code", matchIfMissing = true)
public class VerifySubAgent implements ISubAgent {

    private static final Pattern EN_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*|\\d+[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern ZH_SEG   = Pattern.compile("[\\u4e00-\\u9fa5\\u3400-\\u4dbf]{2,}");
    private static final int MAX_KEYWORDS = 20;

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
    public String getDomain() {
        return "code";
    }

    @Override
    public List<String> getRoutingKeywords() {
        return List.of("验证", "检查", "评审", "review", "质量", "bug", "问题");
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
        List<String> keywords = extractKeywords(requirement);
        int found = 0;
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                found++;
            }
        }

        if (found > 0) {
            sb.append("包含 ").append(found).append("/")
                    .append(keywords.size()).append(" 个关键词\n");
        } else {
            sb.append("未找到任何关键词\n");
        }

        return sb.toString();
    }

    //后续可以改成经过llm的版本
    private List<String> extractKeywords(String requirement) {
        if (requirement == null || requirement.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // 第一层：分词 + 停用词过滤（HanLP 内部处理了常见停用词）
        // 第二层：TextRank 打分，直接出结果
        // HanLP 的 extractKeyword 底层就是 TextRank[citation:2][citation:7]
        List<String> keywords = HanLP.extractKeyword(requirement, 20);

        // 后处理：去重（HanLP 已做）、过滤纯数字、限制长度
        return keywords.stream()
                .filter(k -> k.length() >= 2)          // 过滤单字
                .filter(k -> !k.matches("\\d+"))       // 过滤纯数字
                .limit(20)
                .collect(Collectors.toList());
    }

    /**
     * 增强版：先用 TextRank 出候选，再按需交给 LLM 重排。
     * 这个方法可以做成异步，或者只在特定业务场景调用。
     */
    private List<String> extractWithLlmEnhancement(String requirement) {
        // 先拿一个较大的候选集
        List<String> candidates = HanLP.extractKeyword(requirement, 50);
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 如果候选词质量已经够好（比如前几个词长度都 >= 3 且非纯通用词），直接返回
        // 这里省略业务判断逻辑，核心是：不是每次都需要 LLM

        try {
            // 调用 LLM：把候选词和原文一起给模型，让它选出最相关的 Top 20
            // 必须设超时（比如 2 秒），失败抛异常
            List<String> llmResult = callLlmForRerank(requirement, candidates);
            if (llmResult != null && !llmResult.isEmpty()) {
                return llmResult;
            }
        } catch (Exception e) {
            // 熔断/降级：LLM 挂了就用 TextRank 结果，保证可用性
            // 这里可以打日志、上报监控
        }

        // 降级返回 TextRank 候选的前 20 个
        return candidates.stream().limit(20).collect(Collectors.toList());
    }

    // 示意方法，实际实现取决于你用的 LLM 客户端
    private List<String> callLlmForRerank(String text, List<String> candidates) {
        // 构造 prompt：要求从候选词中选出最相关的，并以 JSON 数组返回
        // 注意：prompt 里强调 "keyphrase" 比 "keyword" 效果通常更好[citation:4]
        // 并且要求 "exact phrases that occur in the input text"
        // 实现略
        return null;
    }
}

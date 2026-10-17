package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.subagent.SubAgentResult;
import com.example.aiagentdemo.instances.subagent.ISubAgent;
import com.example.aiagentdemo.model.DeepSeekModel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 子 Agent 路由器。
 *
 * <p>取代原来 SubAgentScheduler.delegateAuto() 里的 HashMap 遍历 + 关键词 contains 匹配，
 * 采用"能力声明 + LLM 结构化输出 + 确定性兜底"三级路由：
 */
@Slf4j
@Service
public class SubAgentRoutingService {

    @Autowired
    @Qualifier("DeepSeekModel")
    private DeepSeekModel chatModel;

    @Autowired
    private List<ISubAgent> allAgents;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 路由结果：选中的 Agent + 重写后的子任务 + 选型理由 */
    public record RouteDecision(String agentName, String rewrittenTask, String reason, boolean byLlm) {}

    /** LLM 输出的缓存（不需要每次都重建） */
    private String routingPrompt;
    /** 强信号关键词 → 对应的 agent 名（从各子 Agent 动态收集） */
    private final Map<String, String> strongKeywords = new LinkedHashMap<>();
    private Map<String, ISubAgent> agentIndex;

    @PostConstruct
    public void init() {
        agentIndex = allAgents.stream()
                .collect(Collectors.toMap(ISubAgent::getName, a -> a, (a, b) -> a, LinkedHashMap::new));
        //从各子 Agent 动态收集路由关键词
        for (ISubAgent agent : allAgents) {
            for (String keyword : agent.getRoutingKeywords()) {
                strongKeywords.put(keyword, agent.getName());
            }
        }

        routingPrompt = buildRoutingPrompt();
        log.info("SubAgentRoutingService 初始化完成，注册 Agent: {}，路由关键词数: {}",
                agentIndex.keySet(), strongKeywords.size());
    }

    /**
     * 主入口：给定任务，返回路由决策。
     * 优先 LLM，失败/非法则降级到关键词兜底。
     */
    public RouteDecision route(String task) {
        if (task == null || task.isBlank()) {
            return new RouteDecision("explore", task, "空任务默认 explore", false);
        }

        // 1. 尝试 LLM 路由
        try {
            RouteDecision decision = routeByLlm(task);
            if (decision != null && agentIndex.containsKey(decision.agentName())) {
                log.info("🧭 LLM 路由 → {}（reason: {}）", decision.agentName(), decision.reason());
                return decision;
            }
            if (decision != null) {
                log.warn("LLM 路由到不存在的 Agent: {}，降级到关键词匹配", decision.agentName());
            }
        } catch (Exception e) {
            log.warn("LLM 路由失败，降级到关键词匹配: {}", e.getMessage());
        }

        // 2. 关键词加权兜底
        RouteDecision fallback = routeByKeywords(task);
        log.info("🧭 关键词兜底路由 → {}", fallback.agentName());
        return fallback;
    }

    /**
     * 便捷方法：直接执行路由后的 Agent（供 SubAgentScheduler.delegateAuto 调用）。
     */
    public SubAgentResult routeAndExecute(String task, Map<String, Object> context) {
        RouteDecision decision = route(task);
        ISubAgent target = agentIndex.get(decision.agentName());
        if (target == null) {
            target = agentIndex.get("explore");
            decision = new RouteDecision("explore", task, "兜底", false);
        }
        String finalTask = decision.rewrittenTask() != null ? decision.rewrittenTask() : task;
        log.info("开始执行子 Agent [{}]，任务: {}", decision.agentName(), truncate(finalTask, 120));
        long start = System.currentTimeMillis();
        try {
            SubAgentResult result = target.execute(finalTask, context != null ? context : new HashMap<>());
            log.info("子 Agent [{}] 执行完成，耗时 {}ms，成功: {}",
                    decision.agentName(), System.currentTimeMillis() - start, result.isSuccess());
            return result;
        } catch (Exception e) {
            log.error("子 Agent [{}] 执行异常", decision.agentName(), e);
            return SubAgentResult.failure("子 Agent 执行异常: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    // ================================================================
    // LLM 路由
    // ================================================================

    private RouteDecision routeByLlm(String task) throws JsonProcessingException {
        String prompt = routingPrompt + "\n\n【用户任务】\n" + task +
                "\n\n请严格按上述 JSON 格式输出，不要输出其他内容，不要加 markdown 围栏。";

        List<ChatMessage> messages = List.of(
                SystemMessage.from(prompt),
                UserMessage.from("请为下面这个任务选择最合适的子 Agent：")
        );

        ChatResponse resp = chatModel.chat(messages);
        String content = resp.aiMessage().text();
        if (content == null) {
            return null;
        }

        String json = extractJson(content);
        if (json == null) {
            log.debug("LLM 路由返回中无 JSON 片段，原文: {}", truncate(content, 200));
            return null;
        }

        JsonNode node = objectMapper.readTree(json);
        String agent = text(node, "agent");
        String rewritten = text(node, "task");
        String reason = text(node, "reason");

        if (agent == null || agent.isBlank()) {
            return null;
        }
        return new RouteDecision(agent.trim().toLowerCase(),
                rewritten != null ? rewritten : task,
                reason != null ? reason : "",
                true);
    }

    private String buildRoutingPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个子 Agent 路由器。根据用户任务，从下列子 Agent 中选择最合适的一个来处理，");
        sb.append("并可以把用户原始任务重写为一段更聚焦、更适合目标子 Agent 执行的子任务描述。\n\n");
        sb.append("可用子 Agent（name: 能力描述）:\n");
        for (ISubAgent a : agentIndex.values()) {
            sb.append("- name=\"").append(a.getName()).append("\": ")
                    .append(a.getCapability().replace("\n", " "))
                    .append("\n");
        }
        sb.append("\n选择规则:\n");
        sb.append("1. 验证/检查/评审类任务选 verify；\n");
        sb.append("2. 复杂任务拆解/重构方案/多步骤实现选 plan；\n");
        sb.append("3. 搜索代码/读文件/查调用关系/看结构选 explore；\n");
        sb.append("4. 如果都不明确，选 explore。\n");
        sb.append("\n必须严格输出下面格式的 JSON（不要 markdown、不要额外说明）:\n");
        sb.append("{\n");
        sb.append("  \"agent\": \"子 Agent 名字\",\n");
        sb.append("  \"task\": \"重写后的子任务描述（中文，具体可执行）\",\n");
        sb.append("  \"reason\": \"一句话说明为什么选它\"\n");
        sb.append("}\n");
        return sb.toString();
    }

    // ================================================================
    // 关键词兜底路由
    // ================================================================

    private RouteDecision routeByKeywords(String task) {
        String lower = task.toLowerCase(Locale.ROOT);

        // 1. 先扫强信号词（从子 Agent 动态收集）
        for (Map.Entry<String, String> e : strongKeywords.entrySet()) {
            if (lower.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                if (agentIndex.containsKey(e.getValue())) {
                    return new RouteDecision(e.getValue(), task, "关键词命中: " + e.getKey(), false);
                }
            }
        }

        // 2. 默认兜底：explore（如果有）
        if (agentIndex.containsKey("explore")) {
            return new RouteDecision("explore", task, "默认路由", false);
        }

        // 3. 连 explore 都没有，取第一个注册的
        if (!agentIndex.isEmpty()) {
            String first = agentIndex.keySet().iterator().next();
            return new RouteDecision(first, task, "无匹配，取第一个", false);
        }

        // 4. 一个都没有（异常情况）
        return new RouteDecision("", task, "无可用子 Agent", false);
    }

    /**
     * 判断任务是否"复杂"到需要先走 Plan 子 Agent。
     * 供主循环决策使用，不是路由器本身的路由规则（路由交给 LLM）。
     */
    public boolean isComplexTask(String task) {
        if (task == null) return false;
        if (task.length() > 60) return true;
        String lower = task.toLowerCase(Locale.ROOT);
        int hit = 0;
        for (String w : Arrays.asList("分析", "重构", "方案", "实现", "修改", "影响", "怎么", "如何",
                "完整", "对比", "优化", "调试", "排查", "设计", "为什么", "原因")) {
            if (lower.contains(w)) hit++;
        }
        return hit >= 2;
    }

    // ================================================================
    // 工具方法
    // ================================================================

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** 从可能带 markdown/前后说明文本的内容里抠出最外层 JSON 对象 */
    private static String extractJson(String text) {
        if (text == null) return null;
        // 先去除 ```json ... ``` 围栏
        String cleaned = text.replaceAll("(?s)```(?:json)?\\s*(.*?)\\s*```", "$1").trim();
        // 找最外层 { ... }
        Matcher m = Pattern.compile("\\{[\\s\\S]*}").matcher(cleaned);
        return m.find() ? m.group() : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

package com.zlh.aiagent.service.code.subagent;

import com.zlh.aiagent.config.DomainConfig;
import com.zlh.aiagent.data.common.subagent.SubAgentResult;
import com.zlh.aiagent.instances.IToolPlugin;
import com.zlh.aiagent.instances.subagent.ISubAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 计划子 Agent —— Plan & Execute 里的 "Plan" 环节。
 * <p>新增工具时无需改本类——只要工具方法带 {@code @Tool} 注解，就能被自动收集。
 */
@Component
@ConditionalOnProperty(name = "agent.domain.name", havingValue = "code", matchIfMissing = true)
public class PlanSubAgent implements ISubAgent {

    private static final Logger log = LoggerFactory.getLogger(PlanSubAgent.class);

    public static final String AGENT_NAME = "plan";

    private static final int MIN_STEPS = 2;
    private static final int MAX_STEPS = 6;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private List<IToolPlugin> toolPlugins;

    @Autowired
    private DomainConfig domainConfig;

    /** 可用动作清单，启动时自动收集 */
    private volatile Set<String> availableActions = Set.of();

    public PlanSubAgent(@Qualifier("DeepSeekModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    // ================================================================
    // 启动时自动收集可用动作
    // ================================================================

    @PostConstruct
    public void collectAvailableActions() {
        Set<String> actions = new LinkedHashSet<>();

        // 1. 从所有 IToolPlugin 的 @Tool 方法收集
        if (toolPlugins != null) {
            for (IToolPlugin plugin : toolPlugins) {
                if (plugin.getToolBeans() == null) continue;
                for (Object bean : plugin.getToolBeans()) {
                    collectFromBean(bean, actions);
                }
            }
        }

        // 2. 黑名单排除（DomainConfig.excludedActions）
        Set<String> excluded = domainConfig == null || domainConfig.getExcludedActions() == null
                ? Set.of()
                : new HashSet<>(domainConfig.getExcludedActions());
        if (!excluded.isEmpty()) {
            int before = actions.size();
            actions.removeAll(excluded);
            log.info("PlanSubAgent 排除黑名单动作: {}（{} → {}）", excluded, before, actions.size());
        }

        this.availableActions = Set.copyOf(actions);
        log.info("PlanSubAgent 自动收集 {} 个可用动作: {}", actions.size(), actions);
    }

    /**
     * 从单个 Bean 的方法里收集 {@code @Tool} 注解的工具名。
     * 处理 Spring CGLIB 代理：注解在父类/接口方法上，需向上遍历。
     */
    private void collectFromBean(Object bean, Set<String> out) {
        if (bean == null) return;
        // 剥掉 CGLIB 代理拿原始类
        Class<?> targetClass = org.springframework.aop.support.AopUtils.getTargetClass(bean);
        for (Method m : targetClass.getMethods()) {
            if (m.isAnnotationPresent(Tool.class)) {
                out.add(m.getName());
            }
        }
    }

    // ================================================================
    // ISubAgent 接口实现
    // ================================================================

    @Override
    public String getName() {
        return AGENT_NAME;
    }

    @Override
    public String getCapability() {
        return """
                任务规划专家 - 当需要拆解复杂任务、制定执行方案时使用我：
                - 把复杂需求拆解成 2-6 个可顺序执行的步骤
                - 给出每步要做什么、依赖前序哪一步的产出
                - 推荐每步使用的动作（检索/读文件/查调用关系等）
                - 适合重构、新功能实现、多步骤分析类任务
                """;
    }

    @Override
    public String getDomain() {
        return "code";
    }

    @Override
    public List<String> getRoutingKeywords() {
        return List.of("规划", "方案", "拆解", "分步", "怎么实现", "如何实现", "重构", "步骤");
    }

    @Override
    public boolean canHandle(String task) {
        if (task == null || task.isBlank()) return false;
        String lower = task.toLowerCase();
        return lower.contains("规划") || lower.contains("方案")
                || lower.contains("拆解") || lower.contains("分步")
                || lower.contains("怎么实现") || lower.contains("如何实现")
                || lower.contains("重构") || lower.contains("步骤");
    }

    @Override
    public SubAgentResult execute(String task, Map<String, Object> context) {
        log.info("PlanSubAgent 执行任务: {}", task);
        long startTime = System.currentTimeMillis();

        try {
            AiMessage plan = plan(task);
            String text = plan != null ? plan.text() : "";
            if (text == null || text.isBlank()) {
                return SubAgentResult.failure("规划结果为空", System.currentTimeMillis() - startTime);
            }
            String stepsBlock = formatSteps(plan.toolExecutionRequests());
            String fullContent = stepsBlock.isEmpty() ? text : text + "\n\n" + stepsBlock;
            log.info("PlanSubAgent 规划完成，耗时 {}ms", System.currentTimeMillis() - startTime);
            return SubAgentResult.success(fullContent, System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("PlanSubAgent 执行失败", e);
            return SubAgentResult.failure("规划失败: " + e.getMessage(),
                    System.currentTimeMillis() - startTime);
        }
    }

    // ================================================================
    // 核心规划方法
    // ================================================================

    public AiMessage plan(String task) {
        if (task == null || task.isBlank()) {
            return AiMessage.from("任务为空，未生成计划。", List.of());
        }

        // 如果动作清单为空（比如工具没注册），先尝试重收集一次
        if (availableActions.isEmpty()) {
            collectAvailableActions();
        }

        try {
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(plannerSystemPrompt()),
                            UserMessage.from(task)))
                    .responseFormat(planResponseFormat())
                    .build());
            logUsage("json-schema", response);
            return parsePlan(response.aiMessage().text());
        } catch (Exception e) {
            log.warn("【PlanSubAgent】JSON Schema 结构化输出失败，降级为提示词约束模式：{}", e.getMessage());
        }

        try {
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(plannerSystemPrompt() + "\n再次强调：只输出 JSON，不要任何多余文字。"),
                            UserMessage.from(task)))
                    .build());
            logUsage("prompt-only", response);
            return parsePlan(response.aiMessage().text());
        } catch (Exception e) {
            log.error("【PlanSubAgent】生成结构化计划失败，返回单步兜底计划", e);
            return fallbackPlan(task);
        }
    }

    public String describe(AiMessage plan) {
        if (plan == null) return "";
        String text = plan.text();
        return text == null ? "" : text;
    }

    public List<ToolExecutionRequest> stepsOf(AiMessage plan) {
        if (plan == null) return List.of();
        return Collections.unmodifiableList(plan.toolExecutionRequests());
    }

    // ================================================================
    // 提示词
    // ================================================================

    private String formatSteps(List<ToolExecutionRequest> steps) {
        if (steps == null || steps.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("【可执行步骤清单】\n");
        int i = 1;
        for (ToolExecutionRequest step : steps) {
            sb.append(i++).append(". action=").append(step.name())
                    .append("  arguments=").append(step.arguments()).append('\n');
        }
        return sb.toString();
    }

    private String plannerSystemPrompt() {
        return """
                你是一个代码任务规划器（Planner）。把用户的任务拆解成 %d-%d 个可顺序执行的步骤。

                硬性要求：
                1. 只输出 JSON，不要任何解释、不要 markdown 代码块标记。
                2. 顶层字段：taskSummary（一句话复述任务目标）、steps（数组，按执行顺序排列）。
                3. 每个 step 含四个字段：order（从 1 开始）、action、description、arguments。
                4. action 必须从下面的可用动作清单里选，不要自造：
                %s
                5. arguments 必须是一个 JSON 字符串（注意：是字符串，不是 JSON 对象），没有入参时填 "{}"。
                6. description 用中文，说明这一步做什么，以及它依赖前序哪一步的产出。
                7. 步骤之间要有合理依赖：先定位/检索，再读细节，再分析调用关系，最后汇总结论。
                """.formatted(MIN_STEPS, MAX_STEPS, String.join(", ", availableActions));
    }

    private ResponseFormat planResponseFormat() {
        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder()
                        .name("ExecutionPlan")
                        .rootElement(JsonObjectSchema.builder()
                                .addStringProperty("taskSummary", "对用户任务的简要复述，一句话")
                                .addProperty("steps", JsonArraySchema.builder()
                                        .description("按执行顺序排列的步骤列表，2-6 步")
                                        .items(JsonObjectSchema.builder()
                                                .addIntegerProperty("order", "步骤序号，从 1 开始")
                                                .addStringProperty("action", "本步骤的动作/工具名")
                                                .addStringProperty("description", "这一步做什么、依赖前序哪一步的产出")
                                                .addStringProperty("arguments", "该动作的入参，必须是 JSON 字符串")
                                                .required("order", "action", "description", "arguments")
                                                .build())
                                        .build())
                                .required("taskSummary", "steps")
                                .build())
                        .build())
                .build();
    }

    // ================================================================
    // 解析
    // ================================================================

    private AiMessage parsePlan(String raw) throws Exception {
        JsonNode root = objectMapper.readTree(extractJsonObject(raw));

        String summary = root.path("taskSummary").asText("").trim();
        JsonNode stepsNode = root.path("steps");

        List<ToolExecutionRequest> steps = new ArrayList<>();
        StringBuilder readable = new StringBuilder();
        if (!summary.isEmpty()) {
            readable.append("任务概述：").append(summary).append('\n');
        }
        readable.append("执行步骤：\n");

        int order = 1;
        if (stepsNode.isArray()) {
            for (JsonNode stepNode : stepsNode) {
                String action = stepNode.path("action").asText("").trim();
                if (action.isEmpty()) continue;

                // 校验 action 是否在可用清单里
                if (!availableActions.isEmpty() && !availableActions.contains(action)) {
                    log.warn("【PlanSubAgent】模型给出的 action '{}' 不在可用清单中，已跳过", action);
                    continue;
                }

                String description = stepNode.path("description").asText("").trim();
                String arguments = normalizeArguments(stepNode.path("arguments"));

                steps.add(ToolExecutionRequest.builder()
                        .id("step-" + order)
                        .name(action)
                        .arguments(arguments)
                        .build());

                readable.append(order).append(". [").append(action).append("] ")
                        .append(description.isEmpty() ? "（无描述）" : description).append('\n')
                        .append("   参数：").append(arguments).append('\n');

                order++;
                if (steps.size() >= MAX_STEPS) {
                    log.warn("【PlanSubAgent】模型给出的步骤数超过上限 {}，已截断", MAX_STEPS);
                    break;
                }
            }
        }

        if (steps.size() < MIN_STEPS) {
            throw new IllegalStateException("有效步骤数不足 " + MIN_STEPS + " 个，视为规划失败");
        }

        AiMessage plan = AiMessage.from(readable.toString(), steps);
        log.info("【PlanSubAgent】规划完成：{} 步，动作=[{}]", steps.size(),
                steps.stream().map(ToolExecutionRequest::name).collect(Collectors.joining(", ")));
        return plan;
    }

    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("模型输出为空");
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstLineBreak = text.indexOf('\n');
            if (firstLineBreak > 0) text = text.substring(firstLineBreak + 1);
            if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
            text = text.trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("模型输出中未找到 JSON 对象");
        }
        return text.substring(start, end + 1);
    }

    private String normalizeArguments(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "{}";
        if (node.isObject() || node.isArray()) return node.toString();
        String candidate = node.asText("").trim();
        if (candidate.isEmpty()) return "{}";
        try {
            objectMapper.readTree(candidate);
            return candidate;
        } catch (Exception e) {
            return wrapAsRaw(candidate);
        }
    }

    private String wrapAsRaw(String raw) {
        try {
            return objectMapper.writeValueAsString(Map.of("raw", raw));
        } catch (Exception e) {
            return "{}";
        }
    }

    private AiMessage fallbackPlan(String task) {
        // 兜底计划优先用 hybridSearch；若不在可用清单，用第一个可用动作
        String fallbackAction = availableActions.contains("hybridSearch")
                ? "hybridSearch"
                : (availableActions.isEmpty() ? "hybridSearch" : availableActions.iterator().next());
        String arguments = wrapAsRaw(task);
        ToolExecutionRequest step = ToolExecutionRequest.builder()
                .id("step-1")
                .name(fallbackAction)
                .arguments(arguments)
                .build();
        String text = "任务概述：" + task
                + "\n执行步骤：\n"
                + "1. [" + fallbackAction + "] 降级方案：未能生成结构化计划，先做一次检索定位相关代码\n"
                + "   参数：" + arguments + "\n";
        return AiMessage.from(text, List.of(step));
    }

    private void logUsage(String mode, ChatResponse response) {
        if (response == null || response.tokenUsage() == null) return;
        log.info("【PlanSubAgent】模式={}，输入token={}，输出token={}", mode,
                response.tokenUsage().inputTokenCount(),
                response.tokenUsage().outputTokenCount());
    }
}
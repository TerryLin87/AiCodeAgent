package com.example.aiagentdemo.service.subagent;

import com.example.aiagentdemo.data.subagent.SubAgentResult;
import com.example.aiagentdemo.instances.subagent.ISubAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 计划子 Agent —— Plan & Execute 里的 "Plan" 环节。
 */
@Component
public class PlanSubAgent implements ISubAgent {

    private static final Logger log = LoggerFactory.getLogger(PlanSubAgent.class);

    /** 与 SubAgentRoutingService 约定的 agent 名，路由时用 */
    public static final String AGENT_NAME = "plan";

    private static final int MIN_STEPS = 2;
    private static final int MAX_STEPS = 6;

    /** 规划器可用的动作/工具清单（接入 MCP 后可以从 tools/list 动态拉取，不必硬编码） */
    private static final Set<String> DEFAULT_ACTIONS = Set.of(
            "hybrid_search", "semantic_search", "keyword_search",
            "list_projects", "read_file", "get_class_info",
            "find_callers", "find_callees", "impact_analysis");

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile Set<String> availableActions = DEFAULT_ACTIONS;

    public PlanSubAgent(@Qualifier("DeepSeekModel") ChatModel chatModel) {
        this.chatModel = chatModel;
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
    public boolean canHandle(String task) {
        if (task == null || task.isBlank()) {
            return false;
        }
        String lower = task.toLowerCase();
        return lower.contains("规划") || lower.contains("方案")
                || lower.contains("拆解") || lower.contains("分步")
                || lower.contains("怎么实现") || lower.contains("如何实现")
                || lower.contains("重构") || lower.contains("步骤");
    }

    /**
     * ISubAgent 入口：把任务交给 {@link #plan(String)}，把结果包装成 SubAgentResult。
     *
     * <p>返回内容的组织形式：
     * <ul>
     *   <li>人类可读的编号步骤文本（来自 plan.text()）</li>
     *   <li>机器可执行的步骤清单（来自 plan.toolExecutionRequests()）</li>
     * </ul>
     * 这里把两者拼在一起输出，主 Agent 可以据此继续执行。
     */
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

            // 额外把机器可执行步骤附在后面，方便主 Agent 决策
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
    // 原有核心方法（保留，供内部或旧调用方使用）
    // ================================================================

    /**
     * 把用户任务拆解成有序步骤。
     */
    public AiMessage plan(String task) {
        if (task == null || task.isBlank()) {
            return AiMessage.from("任务为空，未生成计划。", List.of());
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

    /** 取出可读计划文本（注入上下文用） */
    public String describe(AiMessage plan) {
        if (plan == null) {
            return "";
        }
        String text = plan.text();
        return text == null ? "" : text;
    }

    /** 取出可执行步骤（原生 ToolExecutionRequest 列表，只读） */
    public List<ToolExecutionRequest> stepsOf(AiMessage plan) {
        if (plan == null) {
            return List.of();
        }
        return Collections.unmodifiableList(plan.toolExecutionRequests());
    }

    /**
     * 可选：接入 MCP 后，把 tools/list 拉到的工具名灌进来，规划器就能自动认识新工具。
     */
    public void setAvailableActions(Collection<String> actions) {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        this.availableActions = Set.copyOf(actions);
        log.info("【PlanSubAgent】可用动作清单已更新，共 {} 项：{}", actions.size(), this.availableActions);
    }

    // ================================================================
    // 格式化与提示词
    // ================================================================

    /** 把可执行步骤格式化成人类可读的块，附在 plan 文本后 */
    private String formatSteps(List<ToolExecutionRequest> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
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

    /** 用 LangChain4j 原生 JSON Schema 描述"计划"的结构，不需要任何自定义 POJO */
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
                if (action.isEmpty()) {
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

    /**
     * 抠出最外层的 JSON 对象：LLM 常见的 ```json 围栏、前后寒暄都在这里兜掉。
     */
    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("模型输出为空");
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstLineBreak = text.indexOf('\n');
            if (firstLineBreak > 0) {
                text = text.substring(firstLineBreak + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("模型输出中未找到 JSON 对象");
        }
        return text.substring(start, end + 1);
    }

    /**
     * 归一化 arguments：必须是合法 JSON 字符串。
     */
    private String normalizeArguments(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "{}";
        }
        if (node.isObject() || node.isArray()) {
            return node.toString();
        }
        String candidate = node.asText("").trim();
        if (candidate.isEmpty()) {
            return "{}";
        }
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

    /** 兜底：连 LLM 都调不通时，给一个"先检索定位"的最小计划 */
    private AiMessage fallbackPlan(String task) {
        String arguments = wrapAsRaw(task);
        ToolExecutionRequest step = ToolExecutionRequest.builder()
                .id("step-1")
                .name("hybrid_search")
                .arguments(arguments)
                .build();
        String text = "任务概述：" + task
                + "\n执行步骤：\n"
                + "1. [hybrid_search] 降级方案：未能生成结构化计划，先做一次混合检索定位相关代码\n"
                + "   参数：" + arguments + "\n";
        return AiMessage.from(text, List.of(step));
    }

    private void logUsage(String mode, ChatResponse response) {
        if (response == null || response.tokenUsage() == null) {
            return;
        }
        log.info("【PlanSubAgent】模式={}，输入token={}，输出token={}", mode,
                response.tokenUsage().inputTokenCount(),
                response.tokenUsage().outputTokenCount());
    }
}
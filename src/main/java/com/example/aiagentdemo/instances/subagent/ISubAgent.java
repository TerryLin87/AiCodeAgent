package com.example.aiagentdemo.instances.subagent;

import com.example.aiagentdemo.data.subagent.SubAgentResult;

import java.util.List;
import java.util.Map;

public interface ISubAgent {
    /**
     * 执行子任务
     * @param task 子任务描述（自然语言）
     * @param context 上下文参数（如项目名、文件路径等）
     * @return 子任务执行结果
     */
    SubAgentResult execute(String task, Map<String, Object> context);

    /**
     * 子 Agent 唯一名称
     */
    String getName();

    /**
     * 子 Agent 能力描述（供主 Agent 决策时参考，会放入 System Prompt）
     */
    String getCapability();

    /**
     * 判断是否能够处理该任务（可选，用于路由）
     */
    default boolean canHandle(String task) {
        return true;  // 默认都可以，子类可重写
    }

    /**
     * 适用领域。
     * <b>null 表示全领域通用</b>（如任务规划器可能跨领域）。
     * 非 null 时，只在 agent.domain.name 匹配时注册。
     */
    default String getDomain() {
        return null;
    }

    /**
     * 路由关键词。
     * 路由器用这些词做关键词兜底匹配。
     * 空列表表示该子 Agent 不参与关键词路由（只能靠 LLM 路由选中）。
     */
    default List<String> getRoutingKeywords() {
        return List.of();
    }
}

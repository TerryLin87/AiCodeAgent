package com.example.aiagentdemo.instances.subagent;

import com.example.aiagentdemo.data.subagent.SubAgentResult;

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
}

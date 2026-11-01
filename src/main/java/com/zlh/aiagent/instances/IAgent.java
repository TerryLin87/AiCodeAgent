package com.zlh.aiagent.instances;

import com.zlh.aiagent.data.http.AgentRequest;
import com.zlh.aiagent.data.http.AgentResponse;
import com.zlh.aiagent.enums.AgentStatus;

//agent抽象接口
public interface IAgent {
    /**
     * 执行 Agent 循环，处理用户输入
     * @param request 包含用户消息和会话上下文
     * @return Agent 响应结果
     */
    AgentResponse execute(AgentRequest request);

    /**
     * 获取 Agent 当前状态
     */
    AgentStatus getStatus();

    /**
     * 重置 Agent 状态（清空会话记忆）
     */
    void reset();
}

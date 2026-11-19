package com.zlh.aiagent.instances;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;

public interface IAgentStreamCallback {
    /** LLM文本token */
    void onToken(String token);
    /** 下发状态事件：plan / tool_call / tool_result */
    void onEvent(String eventName, String data);
    /** 完整工具调用，模型侧流式分片拼接完成 */
    void onToolCalls(List<ToolExecutionRequest> toolExecutionRequests);
    /** 全部完成 */
    void onDone();
    /** 异常 */
    void onError(Throwable e);
}

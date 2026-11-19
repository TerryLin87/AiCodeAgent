package com.zlh.aiagent.instances;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;

public interface IStreamChatCallback {
    /** 收到一块token */
    void onToken(String token);
    /** 收到完整的工具调用请求（全部分片拼接完成后） */
    void onCompleteToolCalls(List<ToolExecutionRequest> toolExecutionRequests);
    /** 流式正常结束 */
    void onComplete();
    /** 异常 */
    void onError(Throwable throwable);
}

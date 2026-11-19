package com.zlh.aiagent.data.common.http;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatMessageRequest {
    private String sessionId;

    /**
     * 客户端幂等请求 ID：前端每次发起新对话时生成一个 UUID，重试/断网重连时复用同一个。
     * 未传则服务端自动生成，无法提供幂等保证（仍会执行，但不会去重）。
     */
    @Size(max = 64, message = "requestId 长度不能超过 64")
    private String requestId;

    @NotBlank(message = "message 不能为空")
    @Size(max = 10000, message = "message 长度不能超过 10000")
    private String message;
}

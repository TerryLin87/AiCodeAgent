package com.zlh.aiagent.data.common.http;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatMessageRequest {
    private String sessionId;

    @NotBlank(message = "message 不能为空")
    @Size(max = 10000, message = "message 长度不能超过 10000")
    private String message;
}

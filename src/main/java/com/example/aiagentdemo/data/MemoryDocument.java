package com.example.aiagentdemo.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryDocument {
    private String id;
    private String sessionId;        // 所属会话ID
    private String role;              // "user" 或 "assistant"
    private String content;           // 原始对话内容
    private String summary;           // 可选：对长内容的摘要
    private LocalDateTime timestamp;
    private Map<String, Object> metadata; // 可扩展的元数据
    private Double score;//分数
}

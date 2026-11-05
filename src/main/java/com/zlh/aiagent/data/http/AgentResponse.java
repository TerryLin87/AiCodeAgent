package com.zlh.aiagent.data.http;

import com.zlh.aiagent.data.Citation;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Agent 统一响应体（HTTP 层 DTO）。
 *
 * <p>包含：回答正文、引用来源、工具调用轨迹、耗时、扩展元数据。
 */
@Data
@Builder
public class AgentResponse {

    /** 回答正文（已去除内部 [ref:...] 标记，可直接展示给用户） */
    private String content;

    /** 会话 ID，便于前端关联多轮对话 */
    private String sessionId;

    /** 本轮调用过的工具名列表（按调用顺序），可为空 */
    private List<String> toolCalls;

    /** 本轮执行总耗时（毫秒） */
    private long executionTimeMs;

    /** 扩展元数据：评估分、token 用量、passed 等，key 由调用方约定 */
    private Map<String, Object> metadata;

    /** 引用来源列表：回答中实际使用的代码片段/文档片段，可为空 */
    private List<Citation> citations;

    /** 便捷方法：正文非空 */
    public boolean hasContent() {
        return content != null && !content.isBlank();
    }

    /** 便捷方法：有引用来源 */
    public boolean hasCitations() {
        return citations != null && !citations.isEmpty();
    }
}
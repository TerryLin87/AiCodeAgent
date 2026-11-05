package com.zlh.aiagent.tools;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工具调用轨迹：记录本会话本轮所有工具调用。
 *
 * <p>用途：
 * <ol>
 *   <li>填充 {@code AgentResponse.toolCalls}，让前端看到"调了什么工具"；</li>
 *   <li>映射到 {@code WorkingMemory.steps}，自动标记步骤完成；</li>
 *   <li>排障：出问题时能精确复现"模型在什么时候调了什么、返回什么"。</li>
 * </ol>
 *
 * <p>按 sessionId 隔离，每轮开始前清空（避免跨轮污染）。
 */
@Component
public class ToolCallTrace {

    /**
     * 单次工具调用记录。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Record {
        /** 工具名，对应 {@code @Tool} 方法名 */
        private String toolName;

        /** 参数（JSON 字符串） */
        private String arguments;

        /** 结果摘要（截断到 200 字符） */
        private String resultSummary;

        /** 是否成功 */
        private boolean success;

        /** 耗时（毫秒） */
        private long costMs;

        /** 调用时间 */
        private LocalDateTime timestamp;
    }

    /** sessionId -> 本轮工具调用记录 */
    private final Map<String, List<Record>> traces = new ConcurrentHashMap<>();

    /** 记录一次工具调用 */
    public void record(String sessionId, Record record) {
        if (sessionId == null || record == null) return;
        traces.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(record);
    }

    /** 获取本会话本轮的调用记录 */
    public List<Record> get(String sessionId) {
        if (sessionId == null) return List.of();
        return List.copyOf(traces.getOrDefault(sessionId, List.of()));
    }

    /** 获取本会话本轮调用过的工具名（去重、保序） */
    public List<String> getToolNames(String sessionId) {
        return get(sessionId).stream()
                .map(Record::getToolName)
                .distinct()
                .toList();
    }

    /** 清空本会话的记录（每轮开始前调用） */
    public void clear(String sessionId) {
        if (sessionId != null) {
            traces.remove(sessionId);
        }
    }

    /** 清理非活跃会话（定时任务调用） */
    public void evict(java.util.Set<String> aliveSessionIds) {
        traces.keySet().removeIf(id -> !aliveSessionIds.contains(id));
    }

    /** 清空所有（reset 调用） */
    public void clearAll() {
        traces.clear();
    }
}
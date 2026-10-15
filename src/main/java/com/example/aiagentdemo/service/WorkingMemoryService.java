package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.ExecutionPlan;
import com.example.aiagentdemo.data.ExecutionPlanStep;
import com.example.aiagentdemo.data.WorkingMemory;
import com.example.aiagentdemo.enums.PlanStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class WorkingMemoryService {
    // 存储所有会话的工作记忆
    private final Map<String, WorkingMemory> memoryStore = new ConcurrentHashMap<>();

    /**
     * 创建工作记忆
     */
    public WorkingMemory create(String sessionId, String goal) {
        WorkingMemory memory = WorkingMemory.builder()
                .sessionId(sessionId)
                .goal(goal)
                .steps(new java.util.ArrayList<>())
                .context(new java.util.HashMap<>())
                .currentPhase("planning")
                .startTime(LocalDateTime.now())
                .lastUpdate(LocalDateTime.now())
                .totalSteps(0)
                .completedSteps(0)
                .build();

        memoryStore.put(sessionId, memory);
        log.info("创建工作记忆: sessionId={}, goal={}", sessionId, goal);
        return memory;
    }

    /**
     * 获取工作记忆
     */
    public WorkingMemory get(String sessionId) {
        return memoryStore.get(sessionId);
    }

    /**
     * 更新工作记忆
     */
    public WorkingMemory update(WorkingMemory memory) {
        memory.setLastUpdate(LocalDateTime.now());
        memoryStore.put(memory.getSessionId(), memory);
        return memory;
    }

    /**
     * 更新上下文
     */
    public WorkingMemory updateContext(String sessionId, String key, Object value) {
        WorkingMemory memory = memoryStore.get(sessionId);
        if (memory != null) {
            memory.getContext().put(key, value);
            memory.setLastUpdate(LocalDateTime.now());
            memoryStore.put(sessionId, memory);
        }
        return memory;
    }

    /**
     * 获取上下文
     */
    public Object getContext(String sessionId, String key) {
        WorkingMemory memory = memoryStore.get(sessionId);
        if (memory != null && memory.getContext() != null) {
            return memory.getContext().get(key);
        }
        return null;
    }

    /**
     * 清除工作记忆
     */
    public void clear(String sessionId) {
        memoryStore.remove(sessionId);
        log.info("清除工作记忆: sessionId={}", sessionId);
    }

    /**
     * 获取所有会话ID
     */
    public java.util.Set<String> getAllSessionIds() {
        return memoryStore.keySet();
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("totalSessions", memoryStore.size());
        stats.put("activeSessions", memoryStore.size());
        return stats;
    }
}

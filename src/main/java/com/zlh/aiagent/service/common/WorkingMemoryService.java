package com.zlh.aiagent.service.common;

import com.zlh.aiagent.data.common.WorkingMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作记忆服务。
 *
 * <p>职责：为每个会话维护"本轮任务目标 + 步骤清单 + 执行进度"的工作记忆，供主链路在每轮
 * 开始时读取、执行中更新、构建 SystemPrompt 时渲染，避免重复已完成步骤。
 *
 * <p>功能分区：
 * <ul>
 *   <li>【A】存储结构</li>
 *   <li>【B】工作记忆的创建与获取</li>
 *   <li>【C】工作记忆更新</li>
 *   <li>【D】清理与统计</li>
 *   <li>【E】获取或创建</li>
 *   <li>【F】步骤列表初始化</li>
 *   <li>【G】渲染到 SystemPrompt</li>
 *   <li>【H】按工具调用更新进度</li>
 * </ul>
 */
@Slf4j
@Service
public class WorkingMemoryService {

    //region 【A】存储结构

    // 存储所有会话的工作记忆
    private final Map<String, WorkingMemory> memoryStore = new ConcurrentHashMap<>();

    //endregion

    //region 【B】工作记忆的创建与获取

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

    //endregion

    //region 【C】工作记忆更新

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

    //endregion

    //region 【D】清理与统计

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

    //endregion

    //region 【E】获取或创建

    /**
     * 获取或创建：会话已有工作记忆则返回，否则用 goal 新建。
     *
     * <p>主链路每轮开始时调用，避免"重复 create 覆盖上一轮进度"。
     *
     * @param sessionId 会话 ID
     * @param goal      本轮目标（仅新建时使用）
     * @return 工作记忆实例
     */
    public WorkingMemory getOrCreate(String sessionId, String goal) {
        WorkingMemory existing = memoryStore.get(sessionId);
        if (existing != null) {
            return existing;
        }
        return create(sessionId, goal);
    }

    //endregion

    //region 【F】步骤列表初始化

    /**
     * 用 Plan 结果初始化步骤列表。
     *
     * <p>只在"本轮刚生成计划"时调用，会把已有 steps 清空重建。
     *
     * @param sessionId 会话 ID
     * @param goal      本轮目标
     * @param planSteps 计划步骤（顺序即执行顺序）
     */
    public void initSteps(String sessionId, String goal,
                          List<WorkingMemory.StepRecord> planSteps) {
        WorkingMemory memory = getOrCreate(sessionId, goal);
        memory.setGoal(goal);
        memory.setSteps(new java.util.ArrayList<>(planSteps));
        memory.setTotalSteps(planSteps.size());
        memory.setCompletedSteps(0);
        memory.setCurrentPhase("executing");
        memory.setLastUpdate(LocalDateTime.now());
        memoryStore.put(sessionId, memory);
        log.info("初始化工作记忆步骤: session={}, total={}", sessionId, planSteps.size());
    }

    //endregion

    //region 【G】渲染到 SystemPrompt

    /**
     * 把工作记忆渲染成 SystemPrompt 片段。
     *
     * <p>主链路在 buildSystemPrompt 里调用，让模型知道"目标是什么、做到哪了、还剩什么"。
     *
     * @param sessionId 会话 ID
     * @return 可直接拼接到 SystemPrompt 的文本；无工作记忆时返回空串
     */
    public String renderForPrompt(String sessionId) {
        WorkingMemory memory = memoryStore.get(sessionId);
        if (memory == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【当前任务工作记忆】\n");
        sb.append("目标: ").append(memory.getGoal() != null ? memory.getGoal() : "未设置").append("\n");
        sb.append("阶段: ").append(memory.getCurrentPhase() != null ? memory.getCurrentPhase() : "unknown").append("\n");
        sb.append("进度: ").append(memory.getCompletedSteps()).append("/").append(memory.getTotalSteps()).append("\n");

        if (memory.getSteps() != null && !memory.getSteps().isEmpty()) {
            sb.append("步骤:\n");
            for (WorkingMemory.StepRecord step : memory.getSteps()) {
                String mark = switch (step.getStatus()) {
                    case 2 -> "✅";   // COMPLETED（对应 PlanStatus.COMPLETED.getCode()，按你枚举实际值调整）
                    case 3 -> "❌";   // FAILED
                    default -> "⏳";  // PENDING / RUNNING
                };
                sb.append("  ").append(mark).append(" ").append(step.getOrder())
                        .append(". ").append(step.getDescription() != null ? step.getDescription() : "").append("\n");
                if (step.getResult() != null && !step.getResult().isBlank()) {
                    String r = step.getResult().length() > 100
                            ? step.getResult().substring(0, 100) + "..."
                            : step.getResult();
                    sb.append("     结果: ").append(r).append("\n");
                }
            }
        }

        sb.append("请基于以上进度继续执行，不要重复已完成的步骤。\n");
        return sb.toString();
    }

    //endregion

    //region 【H】按工具调用更新进度

    /**
     * 按 action 名匹配步骤，标记为 COMPLETED。
     *
     * <p>用于 T7：工具调用后自动更新进度。匹配规则：step.action == toolName。
     * 多个步骤同名 action 时，标记第一个未完成的。
     *
     * @param sessionId 会话 ID
     * @param toolName  实际调用的工具名
     * @param result    工具返回摘要
     * @return 是否成功标记（无匹配步骤时返回 false）
     */
    public boolean markStepCompletedByAction(String sessionId, String toolName, String result) {
        WorkingMemory memory = memoryStore.get(sessionId);
        if (memory == null || memory.getSteps() == null) {
            return false;
        }
        for (WorkingMemory.StepRecord step : memory.getSteps()) {
            if (toolName != null && toolName.equals(step.getAction())
                    && step.getStatus() != 2) {   // 2 = COMPLETED，按你枚举实际值改
                step.setStatus(2);
                step.setResult(result);
                step.setTimestamp(LocalDateTime.now());
                memory.setCompletedSteps(memory.getCompletedSteps() + 1);
                memory.setLastUpdate(LocalDateTime.now());
                memoryStore.put(sessionId, memory);
                log.info("工作记忆步骤完成: session={}, order={}, action={}",
                        sessionId, step.getOrder(), toolName);
                return true;
            }
        }
        return false;
    }

    //endregion
}

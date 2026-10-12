package com.example.aiagentdemo.data;

import com.example.aiagentdemo.enums.PlanStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkingMemory {
    private String sessionId;                    // 会话ID
    private String goal;                         // 当前目标
    private List<StepRecord> steps;              // 步骤记录
    private Map<String, Object> context;         // 上下文数据
    private String currentPhase;                 // 当前阶段
    private LocalDateTime startTime;             // 开始时间
    private LocalDateTime lastUpdate;            // 最后更新时间
    private int totalSteps;                      // 总步骤数
    private int completedSteps;                  // 已完成步骤数

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepRecord {
        private int order;                       // 顺序
        private String action;                   // 动作
        private String params;                   // 参数
        private String description;              // 描述
        private String result;                   // 结果
        private int status;                   // 对应planStatus枚举
        private LocalDateTime timestamp;         // 时间戳
        private String error;                    // 错误信息
        private long durationMs;                 // 耗时
    }

    // 辅助方法
    public void addStep(StepRecord step) {
        if (this.steps == null) {
            this.steps = new ArrayList<>();
        }
        this.steps.add(step);
        this.totalSteps = this.steps.size();
        this.lastUpdate = LocalDateTime.now();
    }

    public void markStepCompleted(int order, String result) {
        if (this.steps != null && order < this.steps.size()) {
            StepRecord step = this.steps.get(order);
            step.setStatus(PlanStatus.COMPLETED.getCode());
            step.setResult(result);
            step.setTimestamp(LocalDateTime.now());
            this.completedSteps++;
            this.lastUpdate = LocalDateTime.now();
        }
    }

    public void markStepFailed(int order, String error) {
        if (this.steps != null && order < this.steps.size()) {
            StepRecord step = this.steps.get(order);
            step.setStatus(PlanStatus.FAILED.getCode());
            step.setError(error);
            step.setTimestamp(LocalDateTime.now());
            this.lastUpdate = LocalDateTime.now();
        }
    }

    public String getCurrentStepDescription() {
        if (this.steps == null || this.steps.isEmpty()) {
            return "无";
        }
        int current = this.completedSteps;
        if (current < this.steps.size()) {
            return this.steps.get(current).getDescription();
        }
        return "所有步骤已完成";
    }

    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("目标: ").append(goal).append("\n");
        sb.append("进度: ").append(completedSteps).append("/").append(totalSteps).append("\n");
        sb.append("当前: ").append(getCurrentStepDescription()).append("\n");
        if (context != null && !context.isEmpty()) {
            sb.append("上下文: ").append(context).append("\n");
        }
        return sb.toString();
    }
}

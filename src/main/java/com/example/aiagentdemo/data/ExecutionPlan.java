package com.example.aiagentdemo.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionPlan {
    private String goal;                    // 总体目标
    private List<ExecutionPlanStep> steps;           // 步骤列表
    private String estimatedComplexity;     // 复杂度: simple/medium/complex
    private int currentStepIndex;           // 当前执行到的步骤索引
    private int status;                  // 匹配PlanStatus枚举
}

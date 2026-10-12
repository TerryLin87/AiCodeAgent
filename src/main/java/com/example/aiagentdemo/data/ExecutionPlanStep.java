package com.example.aiagentdemo.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionPlanStep {
    private int order;                  // 步骤序号
    private String action;              // 动作: searchCode/readFile/getClassFields/analyze
    private String params;              // 参数
    private String description;         // 步骤描述
    private int status;              // 匹配PlanStatus枚举
    private String result;              // 执行结果
    private String error;               // 错误信息（如果有）
}

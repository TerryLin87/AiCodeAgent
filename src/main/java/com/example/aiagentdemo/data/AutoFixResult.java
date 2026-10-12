package com.example.aiagentdemo.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutoFixResult {
    private String finalContent;          // 最终内容
    private EvaluationResult finalEvaluation; // 最终评估
    private int attempts;                 // 尝试次数
    private boolean passed;               // 是否通过
    private String message;               // 结果消息
}

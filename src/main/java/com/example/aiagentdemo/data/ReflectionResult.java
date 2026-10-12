package com.example.aiagentdemo.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReflectionResult {
    private boolean shouldRetry;          // 是否重试
    private String modifiedAction;        // 修改后的动作
    private String modifiedParams;        // 修改后的参数
    private String strategy;              // 使用的策略
    private String explanation;           // 解释
    private double confidence;            // 置信度
}

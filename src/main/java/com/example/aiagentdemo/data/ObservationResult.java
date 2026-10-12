package com.example.aiagentdemo.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

//观察结果类
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObservationResult {
    private boolean success;              // 是否成功
    private String summary;               // 结果摘要
    private String details;               // 详细结果
    private String errorType;             // 错误类型: not_found/empty/exception/incomplete
    private String errorMessage;          // 错误信息
    private String suggestion;            // 建议的修正方向
    private double confidence;            // 置信度 0-1
    private String timestamp;
}

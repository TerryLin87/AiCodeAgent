package com.example.aiagentdemo.data;

import com.example.aiagentdemo.enums.Intent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class IntentResult {
    private Intent intent;
    private String projectName;   // 项目名（PROJECT_STRUCTURE 时使用）
    private String className;
    private String filePath;      // 文件路径（READ_FILE、CODE_MODIFY 时使用）
    private String methodName;
    private String requirement;   // 用户原始需求
    private double confidence;    // 置信度

    public IntentResult(Intent intent, String projectName, String className,
                        String filePath,String methodName, String requirement, double confidence) {
        this.intent = intent;
        this.projectName = projectName;
        this.className = className;
        this.filePath = filePath;
        this.methodName = methodName;
        this.requirement = requirement;
        this.confidence = confidence;
    }
}

package com.example.aiagentdemo.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationResult {
    // 各维度评分 (1-10)
    private double correctness;      // 正确性
    private double completeness;     // 完整性
    private double clarity;          // 清晰度
    private double normativity;      // 规范性
    private double usability;        // 可用性

    private double overallScore;     // 总分

    private List<String> strengths;  // 优点
    private List<String> weaknesses; // 缺点
    private List<String> suggestions; // 改进建议

    private boolean passed;          // 是否通过

    public static EvaluationResult createDefault() {
        return EvaluationResult.builder()
                .correctness(5.0)
                .completeness(5.0)
                .clarity(5.0)
                .normativity(5.0)
                .usability(5.0)
                .overallScore(5.0)
                .strengths(new ArrayList<>())
                .weaknesses(new ArrayList<>())
                .suggestions(new ArrayList<>())
                .passed(false)
                .build();
    }

    public void calculateOverall() {
        this.overallScore = (correctness + completeness + clarity + normativity + usability) / 5.0;
        this.passed = this.overallScore >= 7.0;
    }
}

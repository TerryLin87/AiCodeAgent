package com.zlh.aiagent.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationResult {

    /** 动态维度分数：key = 维度名（correctness / compliance / ...） */
    @Builder.Default
    private Map<String, Double> dimensionScores = new LinkedHashMap<>();

    private double overallScore;
    private boolean passed;

    @Builder.Default
    private List<String> strengths = new ArrayList<>();
    @Builder.Default
    private List<String> weaknesses = new ArrayList<>();
    @Builder.Default
    private List<String> suggestions = new ArrayList<>();
}
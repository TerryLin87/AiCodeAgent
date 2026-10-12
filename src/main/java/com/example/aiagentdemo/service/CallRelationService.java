package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.CallRelation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CallRelationService {
    // 存储所有调用关系
    private final List<CallRelation> allRelations = new ArrayList<>();

    // 索引: 被调用方 -> 调用方列表
    private final Map<String, List<CallRelation>> calleeIndex = new ConcurrentHashMap<>();

    // 索引: 调用方 -> 被调用方列表
    private final Map<String, List<CallRelation>> callerIndex = new ConcurrentHashMap<>();

    /**
     * 批量添加调用关系
     */
    public void addRelations(List<CallRelation> relations) {
        synchronized (allRelations) {
            allRelations.addAll(relations);

            for (CallRelation relation : relations) {
                String calleeKey = buildMethodKey(
                        relation.getCalleeProject(),
                        relation.getCalleeClass(),
                        relation.getCalleeMethod()
                );

                String callerKey = buildMethodKey(
                        relation.getCallerProject(),
                        relation.getCallerClass(),
                        relation.getCallerMethod()
                );

                calleeIndex.computeIfAbsent(calleeKey, k -> new ArrayList<>()).add(relation);
                callerIndex.computeIfAbsent(callerKey, k -> new ArrayList<>()).add(relation);
            }
        }
        log.info("已加载 {} 条调用关系", relations.size());
    }

    // ================================================================
    // 查询方法
    // ================================================================

    /**
     * 查找谁调用了指定的方法（无 project 限定）
     */
    public List<CallRelation> findCallers(String className, String methodName) {
        return findCallers(null, className, methodName);
    }

    /**
     * 查找谁调用了指定的方法（带 project 限定）
     * ★ 关键：projectName 非空时，先精确匹配，匹配不到再模糊匹配
     */
    public List<CallRelation> findCallers(String projectName, String className, String methodName) {
        // 1. 先精确匹配（带 project）
        String key = buildMethodKey(projectName, className, methodName);
        List<CallRelation> results = calleeIndex.getOrDefault(key, new ArrayList<>());

        if (!results.isEmpty()) {
            return results;
        }

        // 2. 精确匹配失败，尝试模糊匹配
        // 匹配条件：key 里包含 className.methodName
        String classMethodKey = className + "." + methodName;
        results = calleeIndex.entrySet().stream()
                .filter(entry -> entry.getKey().contains(classMethodKey))
                .flatMap(entry -> entry.getValue().stream())
                // 如果指定了 project，只返回该 project 的关系
                .filter(r -> projectName == null || projectName.isEmpty()
                        || projectName.equals(r.getCalleeProject()))
                .collect(Collectors.toList());

        return results;
    }

    /**
     * 查找某个方法调用了哪些方法（向下追踪）
     */
    public List<CallRelation> findCallees(String className, String methodName) {
        return findCallees(null, className, methodName);
    }

    public List<CallRelation> findCallees(String projectName, String className, String methodName) {
        String key = buildMethodKey(projectName, className, methodName);
        List<CallRelation> results = callerIndex.getOrDefault(key, new ArrayList<>());

        if (results.isEmpty()) {
            String classMethodKey = className + "." + methodName;
            results = callerIndex.entrySet().stream()
                    .filter(entry -> entry.getKey().contains(classMethodKey))
                    .flatMap(entry -> entry.getValue().stream())
                    .filter(r -> projectName == null || projectName.isEmpty()
                            || projectName.equals(r.getCallerProject()))
                    .collect(Collectors.toList());
        }

        return results;
    }

    // ================================================================
    // 影响分析（★ 跨项目递归）
    // ================================================================

    /**
     * 分析变更影响范围（递归向上查找所有调用者）
     * ★ 支持跨项目追踪
     */
    public Set<String> analyzeImpact(String className, String methodName, int depth) {
        return analyzeImpact(null, className, methodName, depth);
    }

    public Set<String> analyzeImpact(String projectName, String className, String methodName, int depth) {
        Set<String> affected = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        analyzeImpactRecursive(projectName, className, methodName, depth, 0, affected, visited);
        return affected;
    }

    private void analyzeImpactRecursive(String projectName, String className, String methodName,
                                        int maxDepth, int currentDepth,
                                        Set<String> affected, Set<String> visited) {
        if (currentDepth >= maxDepth) return;

        String currentKey = buildMethodKey(projectName, className, methodName);
        if (visited.contains(currentKey)) return;
        visited.add(currentKey);

        List<CallRelation> callers = findCallers(projectName, className, methodName);

        for (CallRelation relation : callers) {
            String callerProject = relation.getCallerProject();
            String callerClass = relation.getCallerClass();
            String callerMethod = relation.getCallerMethod();

            String callerKey = buildMethodKey(callerProject, callerClass, callerMethod);
            if (affected.contains(callerKey)) continue;

            affected.add(callerKey);

            // ★ 递归时带上 callerProject，实现跨项目追踪
            analyzeImpactRecursive(
                    callerProject,
                    callerClass,
                    callerMethod,
                    maxDepth,
                    currentDepth + 1,
                    affected,
                    visited
            );
        }
    }

    /**
     * 生成影响报告
     */
    public String generateImpactReport(String className, String methodName, int depth) {
        return generateImpactReport(null, className, methodName, depth);
    }

    public String generateImpactReport(String projectName, String className, String methodName, int depth) {
        Set<String> affected = analyzeImpact(projectName, className, methodName, depth);

        String targetName = (projectName != null && !projectName.isEmpty()
                ? projectName + ":" : "") + className + "." + methodName;

        if (affected.isEmpty()) {
            return "未发现 " + targetName + "() 被其他方法调用，修改安全。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📊 影响范围分析: ").append(targetName).append("()\n");
        sb.append("=".repeat(60)).append("\n");
        sb.append("🔗 共发现 ").append(affected.size()).append(" 个调用方:\n\n");

        int i = 1;
        for (String caller : affected) {
            sb.append("  ").append(i++).append(". ").append(caller).append("\n");
        }

        sb.append("\n⚠️ 建议:\n");
        sb.append("  1. 修改此方法前，确认以上调用方是否兼容\n");
        sb.append("  2. 考虑使用 @Deprecated 标记后渐进式替换\n");
        sb.append("  3. 建议先修复所有调用方，再删除旧方法\n");

        return sb.toString();
    }

    // ================================================================
    // 辅助
    // ================================================================

    private String buildMethodKey(String projectName, String className, String methodName) {
        String safeClass = className != null ? className : "unknown";
        String safeMethod = methodName != null ? methodName : "unknown";
        String key = safeClass + "." + safeMethod;
        if (projectName != null && !projectName.isEmpty()) {
            key = projectName + ":" + key;
        }
        return key;
    }

    /**
     * 统计信息（调试用）
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRelations", allRelations.size());
        stats.put("calleeKeys", calleeIndex.size());
        stats.put("callerKeys", callerIndex.size());

        // 按项目统计
        Map<String, Long> byProject = allRelations.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getCallerProject() != null ? r.getCallerProject() : "unknown",
                        Collectors.counting()
                ));
        stats.put("relationsByCallerProject", byProject);

        return stats;
    }
}
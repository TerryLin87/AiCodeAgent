package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.CallRelation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * 调用关系服务。
 */
@Slf4j
@Service
public class CallRelationService {

    // 读写锁：写少读多，读操作可并发
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // 存储所有调用关系
    private final List<CallRelation> allRelations = new ArrayList<>();

    // 索引: 被调用方 -> 调用方列表
    private final Map<String, List<CallRelation>> calleeIndex = new HashMap<>();

    // 索引: 调用方 -> 被调用方列表
    private final Map<String, List<CallRelation>> callerIndex = new HashMap<>();

    /**
     * 批量添加调用关系。
     */
    public void addRelations(List<CallRelation> relations) {
        if (relations == null || relations.isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            allRelations.addAll(relations);
            for (CallRelation relation : relations) {
                String calleeKey = buildMethodKey(
                        relation.getCalleeProject(), relation.getCalleeClass(), relation.getCalleeMethod());
                String callerKey = buildMethodKey(
                        relation.getCallerProject(), relation.getCallerClass(), relation.getCallerMethod());

                calleeIndex.computeIfAbsent(calleeKey, k -> new ArrayList<>()).add(relation);
                callerIndex.computeIfAbsent(callerKey, k -> new ArrayList<>()).add(relation);
            }
        } finally {
            lock.writeLock().unlock();
        }
        log.info("已加载 {} 条调用关系", relations.size());
    }

    // ================================================================
    // 查询方法（一律返回不可变副本，避免外部修改内部状态）
    // ================================================================

    public List<CallRelation> findCallers(String className, String methodName) {
        return findCallers(null, className, methodName);
    }

    /**
     * 查找谁调用了指定的方法（带 project 限定）。
     * projectName 非空时先精确匹配，匹配不到再模糊匹配。
     */
    public List<CallRelation> findCallers(String projectName, String className, String methodName) {
        lock.readLock().lock();
        try {
            // 1. 先精确匹配（带 project）
            String key = buildMethodKey(projectName, className, methodName);
            List<CallRelation> exact = calleeIndex.get(key);
            if (exact != null && !exact.isEmpty()) {
                return List.copyOf(exact);
            }

            // 2. 精确匹配失败，尝试模糊匹配（key 里包含 className.methodName）
            String classMethodKey = className + "." + methodName;
            return calleeIndex.entrySet().stream()
                    .filter(entry -> entry.getKey().contains(classMethodKey))
                    .flatMap(entry -> entry.getValue().stream())
                    .filter(r -> projectName == null || projectName.isEmpty()
                            || projectName.equals(r.getCalleeProject()))
                    .distinct()
                    .collect(Collectors.toUnmodifiableList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<CallRelation> findCallees(String className, String methodName) {
        return findCallees(null, className, methodName);
    }

    public List<CallRelation> findCallees(String projectName, String className, String methodName) {
        lock.readLock().lock();
        try {
            String key = buildMethodKey(projectName, className, methodName);
            List<CallRelation> exact = callerIndex.get(key);
            if (exact != null && !exact.isEmpty()) {
                return List.copyOf(exact);
            }

            String classMethodKey = className + "." + methodName;
            return callerIndex.entrySet().stream()
                    .filter(entry -> entry.getKey().contains(classMethodKey))
                    .flatMap(entry -> entry.getValue().stream())
                    .filter(r -> projectName == null || projectName.isEmpty()
                            || projectName.equals(r.getCallerProject()))
                    .distinct()
                    .collect(Collectors.toUnmodifiableList());
        } finally {
            lock.readLock().unlock();
        }
    }

    // ================================================================
    // 影响分析（跨项目递归）
    // ================================================================

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
        if (currentDepth >= maxDepth) {
            return;
        }
        String currentKey = buildMethodKey(projectName, className, methodName);
        if (!visited.add(currentKey)) {
            return;
        }

        List<CallRelation> callers = findCallers(projectName, className, methodName);
        for (CallRelation relation : callers) {
            String callerProject = relation.getCallerProject();
            String callerClass = relation.getCallerClass();
            String callerMethod = relation.getCallerMethod();

            String callerKey = buildMethodKey(callerProject, callerClass, callerMethod);
            if (!affected.add(callerKey)) {
                continue;
            }
            // 递归时带上 callerProject，实现跨项目追踪
            analyzeImpactRecursive(callerProject, callerClass, callerMethod,
                    maxDepth, currentDepth + 1, affected, visited);
        }
    }

    /**
     * 生成影响报告。
     */
    public String generateImpactReport(String className, String methodName, int depth) {
        return generateImpactReport(null, className, methodName, depth);
    }

    public String generateImpactReport(String projectName, String className, String methodName, int depth) {
        Set<String> affected = analyzeImpact(projectName, className, methodName, depth);

        String targetName = (projectName != null && !projectName.isEmpty() ? projectName + ":" : "")
                + className + "." + methodName;

        if (affected.isEmpty()) {
            return "未发现 " + targetName + "() 被其他方法调用，修改安全。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("影响范围分析: ").append(targetName).append("()\n");
        sb.append("=".repeat(60)).append("\n");
        sb.append("共发现 ").append(affected.size()).append(" 个调用方:\n\n");

        int i = 1;
        for (String caller : affected) {
            sb.append("  ").append(i++).append(". ").append(caller).append("\n");
        }

        sb.append("\n建议:\n");
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
     * 统计信息（调试用）。注意：这里同样要加读锁，原先无锁读取会与写入并发冲突。
     */
    public Map<String, Object> getStats() {
        lock.readLock().lock();
        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalRelations", allRelations.size());
            stats.put("calleeKeys", calleeIndex.size());
            stats.put("callerKeys", callerIndex.size());
            stats.put("relationsByCallerProject", allRelations.stream()
                    .collect(Collectors.groupingBy(
                            r -> r.getCallerProject() != null ? r.getCallerProject() : "unknown",
                            Collectors.counting())));
            return stats;
        } finally {
            lock.readLock().unlock();
        }
    }
}

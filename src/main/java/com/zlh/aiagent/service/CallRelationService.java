package com.zlh.aiagent.service;

import com.zlh.aiagent.data.CallRelation;
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
 *
 * <p>维护方法级调用关系的正反两个索引：
 * <ul>
 *   <li>{@code calleeIndex}：被调用方 → 调用方列表，支撑 {@link #findCallers}</li>
 *   <li>{@code callerIndex}：调用方 → 被调用方列表，支撑 {@link #findCallees}</li>
 * </ul>
 *
 * <p>读多写少，用 {@link ReentrantReadWriteLock} 保证并发安全。
 */
@Slf4j
@Service
public class CallRelationService {

    //region 【A】索引字段与并发控制

    // 读写锁：写少读多，读操作可并发
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // 存储所有调用关系
    private final List<CallRelation> allRelations = new ArrayList<>();

    // 索引: 被调用方 -> 调用方列表（谁调用了它）
    private final Map<String, List<CallRelation>> calleeIndex = new HashMap<>();

    // 索引: 调用方 -> 被调用方列表（它调用了谁）
    private final Map<String, List<CallRelation>> callerIndex = new HashMap<>();

    //endregion

    //region 【B】写入：批量添加调用关系

    /**
     * 批量添加调用关系：同时维护"被调用方索引"与"调用方索引"，写操作加写锁。
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

    //endregion

    //region 【C】入边查询：谁调用了指定方法（findCallers）

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

            // 2. 精确匹配失败，尝试模糊匹配（key 等于 "Class.method" 或以其结尾）
            String classMethodKey = className + "." + methodName;
            return calleeIndex.entrySet().stream()
                    .filter(entry -> matchesClassMethodKey(entry.getKey(), classMethodKey))
                    .flatMap(entry -> entry.getValue().stream())
                    .filter(r -> projectName == null || projectName.isEmpty()
                            || projectName.equals(r.getCalleeProject()))
                    .distinct()
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    //endregion

    //region 【D】出边查询：指定方法调用了谁（findCallees）

    public List<CallRelation> findCallees(String className, String methodName) {
        return findCallees(null, className, methodName);
    }

    /**
     * 查找指定方法调用了哪些方法（带 project 限定）。
     * projectName 非空时先精确匹配，匹配不到再模糊匹配。
     */
    public List<CallRelation> findCallees(String projectName, String className, String methodName) {
        lock.readLock().lock();
        try {
            // 1. 先精确匹配（带 project）
            String key = buildMethodKey(projectName, className, methodName);
            List<CallRelation> exact = callerIndex.get(key);
            if (exact != null && !exact.isEmpty()) {
                return List.copyOf(exact);
            }

            // 2. 精确匹配失败，尝试模糊匹配
            String classMethodKey = className + "." + methodName;
            return callerIndex.entrySet().stream()
                    .filter(entry -> matchesClassMethodKey(entry.getKey(), classMethodKey))
                    .flatMap(entry -> entry.getValue().stream())
                    .filter(r -> projectName == null || projectName.isEmpty()
                            || projectName.equals(r.getCallerProject()))
                    .distinct()
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    //endregion

    //region 【E】影响分析（跨项目递归，只用入边）

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

    //endregion

    //region 【F】辅助工具方法

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
     * 判断索引 key 是否匹配 "Class.method"。
     * <p>索引 key 有两种形态：{@code "Class.method"} 或 {@code "project:Class.method"}。
     * 用 equals 或 endsWith(":Class.method") 精确匹配，避免 {@code contains} 的误伤
     * （如 className="Service" 误命中 "UserService"）。
     */
    private boolean matchesClassMethodKey(String indexKey, String classMethodKey) {
        if (indexKey == null || classMethodKey == null) {
            return false;
        }
        return indexKey.equals(classMethodKey)
                || indexKey.endsWith(":" + classMethodKey);
    }

    /**
     * 统计信息（调试用）。读锁保护，避免与写入并发冲突。
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

    //endregion
}
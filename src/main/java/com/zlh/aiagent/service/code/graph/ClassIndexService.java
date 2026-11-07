package com.zlh.aiagent.service.code.graph;

import com.zlh.aiagent.data.code.CodeSegment;
import com.zlh.aiagent.data.common.graph.GraphNode;
import com.zlh.aiagent.service.code.CodeRetrievalService;
import com.zlh.aiagent.data.code.RetrievedCode;
import com.zlh.aiagent.service.code.JavaCodeParser;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 类级关系索引。
 *
 * <p>数据来源：{@link JavaCodeParser} 解析出的 {@link CodeSegment}。
 * 从 CodeSegment 的 {@code typeKind} / {@code rawCode} 里提取：
 * <ul>
 *   <li>继承关系：{@code extends Xxx}</li>
 *   <li>实现关系：{@code implements Yyy}</li>
 *   <li>字段依赖：{@code private UserRepository repo}</li>
 * </ul>
 *
 * <p>索引结构：
 * <ul>
 *   <li>{@code classIndex}：className → GraphNode</li>
 *   <li>{@code implementIndex}：interfaceName → List<实现类></li>
 *   <li>{@code inheritanceIndex}：parentClass → List<子类></li>
 *   <li>{@code dependencyIndex}：className → List<依赖的类></li>
 * </ul>
 */
@Slf4j
@Service
public class ClassIndexService {

    @Autowired
    private CodeRetrievalService retrievalService;

    /** className → GraphNode */
    private final Map<String, GraphNode> classIndex = new ConcurrentHashMap<>();

    /** interfaceName → 实现类列表 */
    private final Map<String, List<String>> implementIndex = new ConcurrentHashMap<>();

    /** parentClass → 子类列表 */
    private final Map<String, List<String>> inheritanceIndex = new ConcurrentHashMap<>();

    /** className → 依赖的类列表 */
    private final Map<String, List<String>> dependencyIndex = new ConcurrentHashMap<>();

    /** 是否已构建
     * -- GETTER --
     * 索引是否已构建
     */
    @Getter
    private volatile boolean built = false;

    /**
     * 启动时构建索引。
     *
     * <p>从 Milvus 拉所有 class 级 segment，从 rawCode 里提取关系。
     * 注意：这里不重新解析文件，而是复用 Milvus 里已存的 rawCode。
     */
    @PostConstruct
    public void buildIndex() {
        try {
            log.info("开始构建类级关系索引...");
            long start = System.currentTimeMillis();

            // 从 Milvus 拉所有 class 级片段（limit 10000，覆盖大部分项目）
            List<RetrievedCode> segments = retrievalService.getAllSegments(null, 10000);
            if (segments.isEmpty()) {
                log.warn("Milvus 无数据，类级索引为空");
                built = true;
                return;
            }

            int classCount = 0;
            for (RetrievedCode seg : segments) {
                if (!"class".equals(seg.getSegmentType()) && !"interface".equals(seg.getSegmentType())) {
                    continue;
                }
                String className = seg.getClassName();
                if (className == null || className.isBlank()) {
                    continue;
                }

                // 建节点
                GraphNode node = GraphNode.builder()
                        .id(seg.getProjectName() + ":" + className)
                        .type("class")
                        .label(className)
                        .project(seg.getProjectName())
                        .className(className)
                        .build();
                classIndex.put(className, node);
                classCount++;

                // 从 content（含 rawCode + 元数据）里提取关系
                String content = seg.getContent();
                if (content != null) {
                    extractRelations(className, content);
                }
            }

            built = true;
            log.info("类级关系索引构建完成: 类数={}, 实现关系={}, 继承关系={}, 依赖关系={}, 耗时={}ms",
                    classCount, implementIndex.size(), inheritanceIndex.size(),
                    dependencyIndex.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("构建类级索引失败", e);
            built = true;
        }
    }

    /**
     * 从类源码里提取 extends / implements / 字段依赖。
     */
    private void extractRelations(String className, String content) {
        // 1. implements
        var implMatcher = java.util.regex.Pattern.compile(
                "\\bimplements\\s+([A-Z][A-Za-z0-9_,\\s<>]+?)(?:\\s*\\{|$)",
                java.util.regex.Pattern.MULTILINE
        ).matcher(content);
        while (implMatcher.find()) {
            String impls = implMatcher.group(1);
            for (String impl : impls.split(",")) {
                String iface = impl.trim().replaceAll("<.*>", "").trim();
                if (!iface.isEmpty() && Character.isUpperCase(iface.charAt(0))) {
                    implementIndex.computeIfAbsent(iface, k -> new ArrayList<>()).add(className);
                }
            }
        }

        // 2. extends
        var extMatcher = java.util.regex.Pattern.compile(
                "\\bextends\\s+([A-Z][A-Za-z0-9_<>]+)",
                java.util.regex.Pattern.MULTILINE
        ).matcher(content);
        while (extMatcher.find()) {
            String parent = extMatcher.group(1).replaceAll("<.*>", "").trim();
            if (!parent.isEmpty()) {
                inheritanceIndex.computeIfAbsent(parent, k -> new ArrayList<>()).add(className);
            }
        }

        // 3. 字段依赖：private UserRepository repo; / @Autowired private Foo foo;
        var fieldMatcher = java.util.regex.Pattern.compile(
                "(?:private|protected|public)\\s+(?:final\\s+)?([A-Z][A-Za-z0-9_<>]+)\\s+[a-z][A-Za-z0-9_]*\\s*[;=]",
                java.util.regex.Pattern.MULTILINE
        ).matcher(content);
        Set<String> deps = new HashSet<>();
        while (fieldMatcher.find()) {
            String dep = fieldMatcher.group(1).replaceAll("<.*>", "").trim();
            // 排除 JDK 常见类型
            if (!isPrimitiveOrCommon(dep) && !dep.equals(className)) {
                deps.add(dep);
            }
        }
        if (!deps.isEmpty()) {
            dependencyIndex.put(className, new ArrayList<>(deps));
        }
    }

    private boolean isPrimitiveOrCommon(String type) {
        Set<String> skip = Set.of(
                "String", "Object", "Integer", "Long", "Boolean", "Double", "Float",
                "List", "Map", "Set", "Collection", "ArrayList", "HashMap", "HashSet",
                "Optional", "Date", "LocalDate", "LocalDateTime", "BigDecimal"
        );
        return skip.contains(type);
    }

    // ================================================================
    // 查询 API
    // ================================================================

    public List<GraphNode> getImplementations(String interfaceName) {
        if (!built) buildIndex();
        String key = normalizeName(interfaceName);
        List<String> impls = implementIndex.getOrDefault(key, List.of());
        return impls.stream()
                .map(classIndex::get)
                .filter(Objects::nonNull)
                .toList();
    }

    public List<GraphNode> getSubclasses(String className) {
        if (!built) buildIndex();
        String key = normalizeName(className);
        List<String> subs = inheritanceIndex.getOrDefault(key, List.of());
        return subs.stream()
                .map(classIndex::get)
                .filter(Objects::nonNull)
                .toList();
    }

    public List<GraphNode> getClassDependencies(String className) {
        if (!built) buildIndex();
        String key = normalizeName(className);
        List<String> deps = dependencyIndex.getOrDefault(key, List.of());
        return deps.stream()
                .map(classIndex::get)
                .filter(Objects::nonNull)
                .toList();
    }

    public GraphNode getClass(String className) {
        return classIndex.get(normalizeName(className));
    }

    /**
     * 归一化类名：去掉包名，只留简单名。
     * "com.bf.framework.UserService" → "UserService"
     */
    private String normalizeName(String name) {
        if (name == null) return "";
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(idx + 1) : name;
    }

    /** 统计信息（调试用） */
    public Map<String, Object> getStats() {
        return Map.of(
                "classes", classIndex.size(),
                "implementRelations", implementIndex.size(),
                "inheritanceRelations", inheritanceIndex.size(),
                "dependencyRelations", dependencyIndex.size(),
                "built", built
        );
    }
}
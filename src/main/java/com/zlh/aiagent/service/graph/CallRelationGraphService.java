package com.zlh.aiagent.service.graph;

import com.zlh.aiagent.data.CallRelation;
import com.zlh.aiagent.data.graph.GraphEdge;
import com.zlh.aiagent.data.graph.GraphNode;
import com.zlh.aiagent.data.graph.GraphPath;
import com.zlh.aiagent.instances.IKnowledgeGraphService;
import com.zlh.aiagent.service.CallRelationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CallRelationGraphService implements IKnowledgeGraphService {

    @Autowired
    private CallRelationService callRelationService;

    @Autowired
    private ProjectDependencyParser projectDependencyParser;

    @Autowired
    private com.zlh.aiagent.config.CodeIndexConfig codeIndexConfig;

    @Lazy
    private List<ProjectDependencyParser.ProjectDependency> cachedDeps;

    @Autowired
    private ClassIndexService classIndexService;

    @Override
    public String getDomain() {
        return "code";
    }

    @Override
    public GraphNode getNode(String nodeId) {
        // nodeId 格式：project:className.methodName
        if (nodeId == null || !nodeId.contains(":")) {
            return null;
        }

        String[] parts = nodeId.split(":", 2);
        String project = parts[0];
        String classMethod = parts[1];
        int dotIdx = classMethod.lastIndexOf('.');
        String className = dotIdx > 0 ? classMethod.substring(0, dotIdx) : classMethod;
        String methodName = dotIdx > 0 ? classMethod.substring(dotIdx + 1) : "";

        return GraphNode.builder()
                .id(nodeId)
                .type("method")
                .label(className + (methodName.isEmpty() ? "" : "." + methodName + "()"))
                .project(project)
                .className(className)
                .methodName(methodName)
                .build();
    }

    @Override
    public List<GraphEdge> getEdges(String nodeId, EdgeDirection direction) {
        List<GraphEdge> edges = new ArrayList<>();
        GraphNode node = getNode(nodeId);
        if (node == null) return edges;

        if (direction == EdgeDirection.OUT || direction == EdgeDirection.BOTH) {
            // 出边：我调用了谁
            List<CallRelation> callees = callRelationService.findCallees(
                    node.getProject(), node.getClassName(), node.getMethodName());
            for (CallRelation r : callees) {
                edges.add(GraphEdge.builder()
                        .from(nodeId)
                        .to(buildNodeId(r.getCalleeProject(), r.getCalleeClass(), r.getCalleeMethod()))
                        .type(r.getCallType())
                        .detail("line " + r.getCallerLine())
                        .build());
            }
        }
        if (direction == EdgeDirection.IN || direction == EdgeDirection.BOTH) {
            // 入边：谁调用了
            List<CallRelation> callers = callRelationService.findCallers(
                    node.getProject(), node.getClassName(), node.getMethodName());
            for (CallRelation r : callers) {
                edges.add(GraphEdge.builder()
                        .from(buildNodeId(r.getCallerProject(), r.getCallerClass(), r.getCallerMethod()))
                        .to(nodeId)
                        .type(r.getCallType())
                        .detail("line " + r.getCallerLine())
                        .build());
            }
        }

        return edges;
    }

    @Override
    public List<GraphPath> findPaths(String fromId, String toId, int maxDepth) {
        if (fromId == null || toId == null || maxDepth <= 0) {
            return List.of();
        }
        List<GraphPath> paths = new ArrayList<>();
        List<String> currentPath = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        currentPath.add(fromId);
        visited.add(fromId);
        dfsFindPaths(fromId, toId, maxDepth, currentPath, visited, paths);
        return paths;
    }

    @Override
    public List<GraphNode> findNodes(String type, String namePattern) {
        return List.of();
    }

    @Override
    public List<String> getProjectDependencies(String projectName) {
        return deps().stream()
                .filter(d -> projectName.equals(d.getFromProject()))
                .map(ProjectDependencyParser.ProjectDependency::getToProject)
                .distinct()
                .toList();
    }

    @Override
    public List<String> getProjectDependents(String projectName) {
        return deps().stream()
                .filter(d -> projectName.equals(d.getToProject()))
                .map(ProjectDependencyParser.ProjectDependency::getFromProject)
                .distinct()
                .toList();
    }

    @Override
    public List<GraphNode> getImplementations(String interfaceName) {
        return classIndexService.getImplementations(interfaceName);
    }

    @Override
    public List<GraphNode> getClassDependencies(String className) {
        return classIndexService.getClassDependencies(className);
    }

    @Override
    public List<GraphNode> getSubclasses(String className) {
        return classIndexService.getSubclasses(className);
    }

    private void dfsFindPaths(String current, String target, int remainingDepth,
                              List<String> currentPath, Set<String> visited,
                              List<GraphPath> result) {
        if (remainingDepth == 0) return;

        List<GraphEdge> outEdges = getEdges(current, EdgeDirection.OUT);
        for (GraphEdge edge : outEdges) {
            String next = edge.getTo();
            if (next.equals(target)) {
                // 找到一条路径
                List<String> fullPath = new ArrayList<>(currentPath);
                fullPath.add(next);
                result.add(buildPath(fullPath));
                // 不 return，继续找其它路径
            } else if (!visited.contains(next)) {
                visited.add(next);
                currentPath.add(next);
                dfsFindPaths(next, target, remainingDepth - 1, currentPath, visited, result);
                currentPath.remove(currentPath.size() - 1);
                visited.remove(next);
            }
        }
    }

    private GraphPath buildPath(List<String> nodeIds) {
        List<GraphNode> nodes = nodeIds.stream().map(this::getNode).filter(Objects::nonNull).toList();
        List<GraphEdge> edges = new ArrayList<>();
        for (int i = 0; i < nodeIds.size() - 1; i++) {
            edges.add(GraphEdge.builder()
                    .from(nodeIds.get(i))
                    .to(nodeIds.get(i + 1))
                    .type("path")
                    .build());
        }
        String summary = nodeIds.stream()
                .map(id -> {
                    GraphNode n = getNode(id);
                    return n != null ? n.getLabel() : id;
                })
                .collect(Collectors.joining(" → "));
        return GraphPath.builder()
                .nodes(nodes)
                .edges(edges)
                .depth(nodeIds.size() - 1)
                .summary(summary)
                .build();
    }

    private String buildNodeId(String project, String className, String methodName) {
        String p = project != null ? project : "unknown";
        String c = className != null ? className : "unknown";
        String m = methodName != null ? methodName : "unknown";
        return p + ":" + c + "." + m;
    }

    private List<ProjectDependencyParser.ProjectDependency> deps() {
        if (cachedDeps == null) {
            cachedDeps = projectDependencyParser.parseAll(codeIndexConfig.getSubProjectsPath());
            log.info("加载项目依赖 {} 条", cachedDeps.size());
        }
        return cachedDeps;
    }
}

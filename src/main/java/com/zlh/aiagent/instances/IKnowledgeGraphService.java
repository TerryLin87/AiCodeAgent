package com.zlh.aiagent.instances;

import com.zlh.aiagent.data.graph.GraphNode;
import com.zlh.aiagent.data.graph.GraphPath;
import com.zlh.aiagent.data.graph.GraphEdge;

import java.util.List;

/**
 * 知识图谱服务接口。
 *
 * <p>所有领域（code / law / finance）都实现这个接口，核心代码只依赖接口。
 */
public interface IKnowledgeGraphService {

    /** 领域标识：code / law / finance */
    String getDomain();

    /** 按 ID 查节点 */
    GraphNode getNode(String nodeId);

    /** 查节点的直接邻居（出边 / 入边 / 双向） */
    List<GraphEdge> getEdges(String nodeId, EdgeDirection direction);

    /** 多跳路径查询：从 fromId 到 toId 的所有路径，最多 maxDepth 跳 */
    List<GraphPath> findPaths(String fromId, String toId, int maxDepth);

    /** 按类型和名字查节点 */
    List<GraphNode> findNodes(String type, String namePattern);

    /** 查项目的直接依赖 */
    List<String> getProjectDependencies(String projectName);

    /** 查哪些项目依赖了指定项目 */
    List<String> getProjectDependents(String projectName);

    /** 查接口/类的实现类。 */
    List<GraphNode> getImplementations(String interfaceName);

    /** 查类直接依赖的类（@Autowired / @Resource / new / 字段类型）。 */
    List<GraphNode> getClassDependencies(String className);

    /** 查类的继承链（父类 → 子类）。*/
    List<GraphNode> getSubclasses(String className);

    /** 边的方向 */
    enum EdgeDirection {
        OUT,    // 出边（我指向谁）
        IN,     // 入边（谁指向我）
        BOTH
    }
}
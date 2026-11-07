package com.zlh.aiagent.data.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 图路径：从 from 到 to 的一条路径。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphPath {

    /** 路径上的节点列表（按顺序） */
    private List<GraphNode> nodes;

    /** 路径上的边列表（nodes.size() - 1 条） */
    private List<GraphEdge> edges;

    /** 路径长度（边数） */
    private int depth;

    /** 路径摘要（人类可读） */
    private String summary;
}
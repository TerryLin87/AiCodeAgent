package com.zlh.aiagent.data.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图边：两个实体之间的关系。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphEdge {

    /** 起点节点 ID */
    private String from;

    /** 终点节点 ID */
    private String to;

    /** 关系类型：method_call / implements / extends / project_depends */
    private String type;

    /** 关系属性（如调用行号） */
    private String detail;
}

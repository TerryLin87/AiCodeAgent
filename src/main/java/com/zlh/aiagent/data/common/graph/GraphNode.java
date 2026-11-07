package com.zlh.aiagent.data.common.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图节点：一个实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphNode {

    /** 节点 ID：如 "framework:UserService.login" */
    private String id;

    /** 节点类型：method / class / interface / project */
    private String type;

    /** 显示名：如 "UserService.login()" */
    private String label;

    /** 所属项目 */
    private String project;

    /** 所属类 */
    private String className;

    /** 方法名（type=method 时） */
    private String methodName;
}
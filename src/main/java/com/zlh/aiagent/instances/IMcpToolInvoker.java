package com.zlh.aiagent.instances;

import java.util.Map;

/**
 * MCP 工具调用器（通用）。
 *
 * <p>Java 代码需要调 MCP 工具时用这个，不需要每个工具写一个接口。
 *
 * <p>使用场景：
 * <ul>
 *   <li>子 Agent 内部需要调 MCP 工具（如 {@code ExploreSubAgent} 读文件）；</li>
 *   <li>任何非 LLM 路径需要调 MCP 工具。</li>
 * </ul>
 *
 * <p>LLM 路径不需要这个接口——{@code McpToolProvider} 会自动把 MCP 工具装配给 LLM。
 */
public interface IMcpToolInvoker {

    //region 核心方法

    /**
     * 调 MCP 工具。
     *
     * @param toolName  MCP 工具名（如 "readFile"）
     * @param arguments 参数（key 必须和 MCP Server 的方法参数名一致）
     * @return 工具返回的文本；失败时返回错误描述（不抛异常）
     */
    String invoke(String toolName, Map<String, Object> arguments);

    /**
     * 调 MCP 工具（无参数）。
     *
     * @param toolName MCP 工具名
     * @return 工具返回的文本
     */
    default String invoke(String toolName) {
        return invoke(toolName, Map.of());
    }

    //endregion

    //region 健康检查

    /**
     * MCP Server 是否可用。
     *
     * @return true 表示可连通
     */
    boolean isAvailable();

    //endregion
}
package com.example.aiagentdemo.instances;

import java.util.List;

/**
 * 领域工具插件
 */
public interface IToolPlugin {
    // 适用领域（对应 agent.domain.name）
    String getDomainName();

    // 返回该领域的所有工具 Bean（LangChain4j 会扫描 @Tool）
    List<Object> getToolBeans();
}

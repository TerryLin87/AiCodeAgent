package com.example.aiagentdemo.tools;

import com.example.aiagentdemo.instances.IToolPlugin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 代码领域的工具插件。
 * 包装现有的 AgentTools，仅在 agent.domain.name=code（或未配置）时加载。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.domain.name", havingValue = "code", matchIfMissing = true)
public class CodeToolPlugin implements IToolPlugin {

    @Autowired
    private AgentTools agentTools;

    @Override
    public String getDomainName() {
        return "code";
    }

    @Override
    public List<Object> getToolBeans() {
        return List.of(agentTools);
    }
}
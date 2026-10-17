package com.example.aiagentdemo.service.subagent;

import com.example.aiagentdemo.config.DomainConfig;
import com.example.aiagentdemo.data.subagent.SubAgentResult;
import com.example.aiagentdemo.instances.subagent.ISubAgent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.example.aiagentdemo.service.SubAgentRoutingService;

/**
 * 子 Agent 调度器。
 */
@Slf4j
@Component
public class SubAgentScheduler {

    private final Map<String, ISubAgent> agents = new LinkedHashMap<>();

    @Autowired
    private List<ISubAgent> agentList;

    @Autowired
    private SubAgentRoutingService routingService;

    @Autowired
    private DomainConfig domainConfig;

    @PostConstruct
    public void init() {
        String currentDomain = domainConfig.getName();
        for (ISubAgent agent : agentList) {
            // 只注册"通用"或"匹配当前领域"的子 Agent
            if (agent.getDomain() == null || agent.getDomain().equals(currentDomain)) {
                agents.put(agent.getName(), agent);
                String firstLine = agent.getCapability().split("\n")[0];
                log.info("注册子 Agent: {} - {}", agent.getName(), firstLine);
            } else {
                log.debug("跳过子 Agent: {}（领域 {} 不匹配当前 {}）",
                        agent.getName(), agent.getDomain(), currentDomain);
            }
        }
        log.info("领域={}, 共注册 {} 个子 Agent: {}",
                currentDomain, agents.size(), agents.keySet());
    }

    /**
     * 委托任务给指定的子 Agent（显式指名）。
     */
    public SubAgentResult delegate(String agentName, String task, Map<String, Object> context) {
        ISubAgent agent = agents.get(agentName);
        if (agent == null) {
            log.warn("子 Agent 不存在：{}，可用列表: {}", agentName, agents.keySet());
            return SubAgentResult.failure("子 Agent 不存在：" + agentName, 0);
        }

        log.info("显式委托任务给 {}: {}", agentName, truncate(task, 120));
        long startTime = System.currentTimeMillis();

        try {
            SubAgentResult result = agent.execute(task, context != null ? context : new java.util.HashMap<>());
            log.info("{} 执行完成, 耗时: {}ms, 成功: {}",
                    agentName, result.getExecutionTimeMs(), result.isSuccess());
            return result;
        } catch (Exception e) {
            log.error("子 Agent 执行异常", e);
            return SubAgentResult.failure("执行异常: " + e.getMessage(),
                    System.currentTimeMillis() - startTime);
        }
    }

    /**
     * 自动路由到合适的子 Agent。
     */
    public SubAgentResult delegateAuto(String task, Map<String, Object> context) {
        log.info("自动路由任务：{}", truncate(task, 120));
        return routingService.routeAndExecute(task, context);
    }

    /**
     * 获取所有子 Agent 的能力描述（用于主 Agent 的 System Prompt 或 UI 展示）。
     */
    public String getAllCapabilities() {
        return agents.values().stream()
                .map(agent -> "【" + agent.getName() + "】\n" + agent.getCapability())
                .collect(Collectors.joining("\n\n"));
    }

    /** 获取所有子 Agent 名称列表（顺序即注册顺序） */
    public List<String> getAgentNames() {
        return new ArrayList<>(agents.keySet());
    }

    public boolean hasAgent(String name) {
        return agents.containsKey(name);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

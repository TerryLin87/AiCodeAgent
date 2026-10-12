package com.example.aiagentdemo.service.subagent;

import com.example.aiagentdemo.data.subagent.SubAgentResult;
import com.example.aiagentdemo.instances.subagent.ISubAgent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SubAgentScheduler{

    private final Map<String,ISubAgent> agents = new HashMap<>();

    @Autowired
    private List<ISubAgent> agentList;

    @PostConstruct
    public void init() {
        for (ISubAgent agent : agentList) {
            agents.put(agent.getName(), agent);
            log.info("注册子 Agent: {} - {}", agent.getName(), agent.getCapability().split("\n")[0]);
        }
        log.info("共注册 {} 个子 Agent", agents.size());
    }

    /**
     * 委托任务给指定的子 Agent
     *
     * @param agentName 子 Agent 名称
     * @param task 任务描述
     * @param context 上下文参数
     * @return 执行结果
     */
    public SubAgentResult delegate(String agentName,String task,Map<String,Object> context){
        ISubAgent agent = agents.get(agentName);
        if(agent == null){
            log.warn("子Agent不存在：{}",agentName);
            return SubAgentResult.failure("子Agent不存在：" + agentName,0);
        }

        log.info("委托任务给 {}: {}", agentName, task);
        long startTime = System.currentTimeMillis();

        try {
            SubAgentResult result = agent.execute(task, context);
            log.info("{} 执行完成, 耗时: {}ms, 成功: {}",
                    agentName, result.getExecutionTimeMs(), result.isSuccess());
            return result;
        } catch (Exception e) {
            log.error("子 Agent 执行异常", e);
            return SubAgentResult.failure("执行异常: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    /**
     * 根据任务自动路由到合适的子 Agent
     */
    public SubAgentResult delegateAuto(String task,Map<String,Object> context){
        log.info("自动路由任务：{}",task);

        //找到第一个能处理该任务的子Agent
        for(ISubAgent agent : agents.values()){
            if(agent.canHandle(task)){
                log.info("自动路由到：{}",agent.getName());
                return delegate(agent.getName(),task,context);
            }
        }

        // 如果没有匹配的，默认使用 explore
        log.info("未找到匹配的 Agent，使用默认 explore");
        return delegate("explore", task, context);
    }

    /**
     * 获取所有子 Agent 的能力描述（用于主 Agent 的 System Prompt）
     */
    public String getAllCapabilities() {
        return agents.values().stream()
                .map(agent -> "【" + agent.getName() + "】\n" + agent.getCapability())
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 获取所有子 Agent 名称列表
     */
    public List<String> getAgentNames() {
        return new ArrayList<>(agents.keySet());
    }

    /**
     * 检查子 Agent 是否存在
     */
    public boolean hasAgent(String name) {
        return agents.containsKey(name);
    }

}

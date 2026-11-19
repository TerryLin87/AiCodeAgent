package com.zlh.aiagent.tools;

import com.zlh.aiagent.instances.IToolPlugin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "agent.domain.name", havingValue = "law")
public class LawToolPlugin implements IToolPlugin {

    @Autowired
    private LawTools lawTools;

    @Override
    public String getDomainName() {
        return "law";
    }

    @Override
    public List<Object> getToolBeans() {
        return List.of(lawTools);
    }
}
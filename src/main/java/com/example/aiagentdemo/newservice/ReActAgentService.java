package com.example.aiagentdemo.newservice;

import com.example.aiagentdemo.data.http.AgentRequest;
import com.example.aiagentdemo.data.http.AgentResponse;
import com.example.aiagentdemo.enums.AgentStatus;
import com.example.aiagentdemo.instances.IAgent;
import com.example.aiagentdemo.model.DeepSeekModel;
import dev.langchain4j.chain.ConversationalChain;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ReAct 模式的 Agent 实现
 * 实现统一的 Agent 接口，基于自定义 ToolRegistry
 *
 * 工作流程：思考(Reason) → 行动(Act) → 观察(Observe) → 循环
 */
@Slf4j
@Service
public class ReActAgentService implements IAgent {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    @Qualifier("DeepSeekModel")
    private DeepSeekModel deepSeekModel;

    private final ObjectMapper objectMapper = new ObjectMapper();

    //会话状态管理
    private final Map<String,AgentStatus> sessionStatus = new ConcurrentHashMap<>();
    private final Map<String, ConversationContext> sessionContext = new ConcurrentHashMap<>();

    private final static int MAX_ITERATIONS = 10;

    @Override
    public AgentResponse execute(AgentRequest request) {
        String sessionId = request.getSessionId();
        String userManager = request.getUserMessage();

        return null;
    }

    @Override
    public AgentStatus getStatus() {
        return null;
    }

    @Override
    public void reset() {

    }

    /**
     * 会话上下文
     */
    private static class ConversationContext {
        private String lastAction;
        private String lastResult;
        // 可以扩展添加更多上下文
    }

    /**
     * 模型的一次思考决策
     */
    private static class Thought {
        private final boolean finalAnswer;
        private final String finalAnswerText;
        private final String action;
        private final Map<String, Object> params;

        private Thought(boolean finalAnswer, String finalAnswerText, String action, Map<String, Object> params) {
            this.finalAnswer = finalAnswer;
            this.finalAnswerText = finalAnswerText;
            this.action = action;
            this.params = params;
        }

        public static Thought finalAnswer(String answer) {
            return new Thought(true, answer, null, null);
        }

        public static Thought toolCall(String action, Map<String, Object> params) {
            return new Thought(false, null, action, params);
        }

        public boolean isFinal() { return finalAnswer; }
        public String getFinalAnswer() { return finalAnswerText; }
        public String getAction() { return action; }
        public Map<String, Object> getParams() { return params; }
    }
}

package com.zlh.aiagent.enums;

/**
 * LLM 调用类型，用于区分不同场景的超时和 max_tokens。
 */
public enum CallType {
    ROUTING(3, 512),
    SUMMARY(10, 1024),
    EVALUATION(15, 2048),
    MAIN_CHAT(60, 8192),
    TOOL_CALL(30, 4096);

    private final int timeoutSeconds;
    private final int maxTokens;

    CallType(int timeoutSeconds, int maxTokens) {
        this.timeoutSeconds = timeoutSeconds;
        this.maxTokens = maxTokens;
    }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public int getMaxTokens() { return maxTokens; }
}
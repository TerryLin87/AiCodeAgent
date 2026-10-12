package com.example.aiagentdemo.enums;

public enum AgentStatus {
    IDLE("空闲"),
    THINKING("思考中"),
    ACTING("执行工具"),
    OBSERVING("观察结果"),
    REFINING("反思修正"),
    COMPLETED("已完成"),
    FAILED("失败"),
    WAITING("等待用户确认");

    private final String description;

    AgentStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 判断是否处于“运行中”的活跃状态
     */
    public boolean isRunning() {
        return this == THINKING || this == ACTING ||
                this == OBSERVING || this == REFINING;
    }

    /**
     * 判断是否为终态（任务结束）
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}

package com.example.aiagentdemo.enums;

import lombok.Getter;

@Getter
public enum PlanStatus {
    PENDING(0, "准备中"),
    RUNNING(1, "执行中"),
    COMPLETED(2, "完成"),
    REPLANNING(3,"重新规划"),
    FAILED(4, "失败");

    private final int code;
    private final String desc;

    PlanStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    // 根据 code 反向获取枚举（常用工具方法）
    public static PlanStatus fromCode(int code) {
        for (PlanStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知状态码: " + code);
    }
}

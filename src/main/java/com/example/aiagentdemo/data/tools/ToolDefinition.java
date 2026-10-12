package com.example.aiagentdemo.data.tools;

import lombok.Builder;
import lombok.Data;

import java.lang.reflect.Method;
import java.util.List;

@Data
@Builder
public class ToolDefinition {
    /**
     * 工具名称（唯一标识）
     */
    private String name;

    /**
     * 工具描述
     */
    private String description;

    /**
     * 工具方法（用于反射调用）
     */
    private Method method;

    /**
     * 方法所属的实例（用于反射调用）
     */
    private Object instance;

    /**
     * 参数定义列表
     */
    private List<ToolParameter> parameters;

    /**
     * 是否只读
     */
    private boolean readOnly;

    /**
     * 超时时间（秒）
     */
    private int timeout;

    /**
     * 是否对用户可见
     */
    private boolean visible;

    /**
     * 是否需要用户确认
     */
    private boolean requireConfirm;

    /**
     * 工具所属的 Bean 名称
     */
    private String beanName;

    /**
     * 生成用于 System Prompt 的工具描述
     */
    public String toPromptDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(name).append(": ").append(description).append("\n");
        sb.append("  参数:\n");
        for (ToolParameter param : parameters) {
            sb.append("    - ").append(param.toPromptDescription()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 校验参数是否满足必填要求
     */
    public boolean validateParams(java.util.Map<String, Object> params) {
        for (ToolParameter param : parameters) {
            if (param.isRequired() && !params.containsKey(param.getName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 获取缺失的必填参数列表
     */
    public List<String> getMissingRequiredParams(java.util.Map<String, Object> params) {
        return parameters.stream()
                .filter(ToolParameter::isRequired)
                .map(ToolParameter::getName)
                .filter(name -> !params.containsKey(name))
                .collect(java.util.stream.Collectors.toList());
    }
}

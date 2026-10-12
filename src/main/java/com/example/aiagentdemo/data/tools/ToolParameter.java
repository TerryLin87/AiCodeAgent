package com.example.aiagentdemo.data.tools;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToolParameter {
    /**
     * 参数名称
     */
    private String name;

    /**
     * 参数类型（String, Integer, Boolean, List<String> 等）
     */
    private String type;

    /**
     * 参数描述
     */
    private String description;

    /**
     * 是否必须
     */
    private boolean required;

    /**
     * 默认值
     */
    private Object defaultValue;

    /**
     * 约束条件
     */
    private String constraint;

    /**
     * 示例值
     */
    private String example;

    /**
     * 参数在方法中的实际索引位置
     */
    private int index;

    /**
     * 参数类型 Class
     */
    private Class<?> parameterClass;

    /**
     * 是否为集合类型
     */
    public boolean isArray() {
        return parameterClass != null &&
                (parameterClass.isArray() ||
                        java.util.List.class.isAssignableFrom(parameterClass));
    }

    /**
     * 生成用于 Prompt 的描述文本
     */
    public String toPromptDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(": ").append(type);
        if (required) {
            sb.append(" (必填)");
        } else {
            sb.append(" (可选)");
        }
        if (example != null && !example.isEmpty()) {
            sb.append(" 示例: ").append(example);
        }
        if (!description.isEmpty()) {
            sb.append(" ").append(description);
        }
        return sb.toString();
    }
}

package com.example.aiagentdemo.data.tools;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ParameterDefinition {
    /**
     * 参数名称（需与方法参数名一致）
     */
    private String name;

    /**
     * 参数类型（如 String, Integer, List<String> 等）
     */
    private String type;

    /**
     * 参数描述（放入 Prompt 让模型理解该参数的用途）
     */
    private String description;

    /**
     * 是否必须（true: 模型必须提供该参数；false: 可选，模型可不传）
     */
    private boolean required;

    /**
     * 默认值（当模型未提供该参数时使用的值）
     */
    private Object defaultValue;

    /**
     * 参数约束（如 "最小值为1", "必须符合正则表达式" 等）
     */
    private String constraint;

    /**
     * 参数示例（帮助模型理解如何填值）
     */
    private String example;

    /**
     * 是否是数组/集合类型
     */
    private boolean isArray;

    /**
     * 如果是数组类型，元素的类型
     */
    private String elementType;
}

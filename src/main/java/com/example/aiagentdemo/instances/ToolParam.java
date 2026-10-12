package com.example.aiagentdemo.instances;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具方法参数的元信息注解。
 * 配合 @AgentTool 使用，为每个参数提供描述。
 *
 * 使用示例：
 * <pre>
 * &#64;AgentTool(name = "readFile", description = "读取文件内容")
 * public String readFile(
 *     &#64;ToolParam(name = "path", description = "文件路径", required = true) String path,
 *     &#64;ToolParam(name = "encoding", description = "字符编码", required = false, defaultValue = "UTF-8") String encoding
 * ) {
 *     // ...
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {
    /**
     * 参数名称。
     * 如果不填，默认使用方法参数名（通过反射获取）。
     */
    String name() default "";

    /**
     * 参数描述。
     * 必填，描述越清晰模型调用越准确。
     */
    String description();

    /**
     * 是否必须。
     * true: 模型必须提供此参数；
     * false: 可省略，使用 defaultValue。
     */
    boolean required() default true;

    /**
     * 默认值（当 required=false 且模型未提供时使用）。
     */
    String defaultValue() default "";

    /**
     * 参数约束，如 "min=1, max=100" 或 "regex=^[a-zA-Z]+$"
     */
    String constraint() default "";

    /**
     * 示例值，帮助模型理解格式。
     */
    String example() default "";
}

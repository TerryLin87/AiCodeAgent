package com.example.aiagentdemo.instances;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个方法为 Agent 可调用的工具。
 * 被此注解标记的方法会被 ToolRegistry 自动扫描并注册。
 *
 * 使用示例：
 * <pre>
 * &#64;Component
 * public class FileTools {
 *
 *     &#64;AgentTool(
 *         name = "readFile",
 *         description = "读取指定路径的文件内容",
 *         readOnly = true,
 *         timeout = 10
 *     )
 *     public String readFile(
 *         &#64;ToolParam(name = "path", description = "文件路径", required = true) String path
 *     ) {
 *         // ...
 *     }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentTool {
    /**
     * 工具名称，Agent 通过此名称调用工具。
     * 如果不填，默认使用方法名。
     */
    String name() default "";

    /**
     * 工具描述，会放入 System Prompt 供模型理解工具用途。
     * 必填，描述要清晰说明工具做什么、什么时候用。
     */
    String description();

    /**
     * 是否只读。true: 不会修改系统状态；false: 可能修改。
     * 用于安全控制和执行前的风险提示。
     */
    boolean readOnly() default true;

    /**
     * 超时时间（秒），超过此时间未返回视为执行失败。
     */
    int timeout() default 30;

    /**
     * 是否对用户可见。false 的工具可作内部使用，不暴露给模型。
     */
    boolean visible() default true;

    /**
     * 执行前是否需要用户确认（如删除文件等高风险操作）。
     */
    boolean requireConfirm() default false;
}

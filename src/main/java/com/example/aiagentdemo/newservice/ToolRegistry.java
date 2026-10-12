package com.example.aiagentdemo.newservice;

import com.example.aiagentdemo.data.tools.ToolDefinition;
import com.example.aiagentdemo.data.tools.ToolExecutionResult;
import com.example.aiagentdemo.data.tools.ToolParameter;
import com.example.aiagentdemo.instances.AgentTool;
import com.example.aiagentdemo.instances.ToolParam;
import jakarta.annotation.PostConstruct;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Setter
@Component
public class ToolRegistry implements BeanPostProcessor {

    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();
    private final Map<String, List<String>> beanTools = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private int defaultTimeout = 30;
    private boolean enableAutoScan = true;
    private String scanPackages;

    // ==================== 注册方法 ====================

    public void registerTool(ToolDefinition definition) {
        if (tools.containsKey(definition.getName())) {
            log.warn("工具 {} 已存在，将被覆盖", definition.getName());
        }
        tools.put(definition.getName(), definition);
        beanTools.computeIfAbsent(definition.getBeanName(), k -> new ArrayList<>())
                .add(definition.getName());
        log.info("注册工具: {} (bean: {})", definition.getName(), definition.getBeanName());
    }

    public void registerTools(List<ToolDefinition> definitions) {
        for (ToolDefinition def : definitions) {
            registerTool(def);
        }
    }

    public void unregisterTool(String toolName) {
        ToolDefinition removed = tools.remove(toolName);
        if (removed != null) {
            beanTools.values().forEach(list -> list.remove(toolName));
            log.info("注销工具: {}", toolName);
        }
    }

    public void unregisterBeanTools(String beanName) {
        List<String> toolNames = beanTools.remove(beanName);
        if (toolNames != null) {
            toolNames.forEach(tools::remove);
            log.info("注销 Bean {} 的 {} 个工具", beanName, toolNames.size());
        }
    }

    // ==================== 查询方法 ====================

    public List<ToolDefinition> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    public List<ToolDefinition> getVisibleTools() {
        return tools.values().stream()
                .filter(ToolDefinition::isVisible)
                .collect(Collectors.toList());
    }

    public List<ToolDefinition> getTools(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return getVisibleTools();
        }
        return toolNames.stream()
                .map(tools::get)
                .filter(Objects::nonNull)
                .filter(ToolDefinition::isVisible)
                .collect(Collectors.toList());
    }

    public Optional<ToolDefinition> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    public int size() {
        return tools.size();
    }

    public String generateToolsDescription(List<String> toolNames) {
        List<ToolDefinition> toolList = getTools(toolNames);
        if (toolList.isEmpty()) {
            return "（无可用工具）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("可用工具列表:\n");
        for (ToolDefinition tool : toolList) {
            sb.append(tool.toPromptDescription());
        }
        return sb.toString();
    }

    // ==================== 执行方法 ====================

    public ToolExecutionResult execute(String toolName, Map<String, Object> params) {
        ToolDefinition definition = tools.get(toolName);
        if (definition == null) {
            return ToolExecutionResult.notFound(toolName);
        }

        log.info("执行工具: {}，参数: {}", toolName, params);
        long startTime = System.currentTimeMillis();

        try {
            if (!definition.validateParams(params)) {
                List<String> missing = definition.getMissingRequiredParams(params);
                return ToolExecutionResult.failure(toolName,
                        "缺少必填参数: " + String.join(", ", missing));
            }

            int timeout = definition.getTimeout() > 0 ? definition.getTimeout() : defaultTimeout;
            if (timeout > 0) {
                return executeWithTimeout(definition, params, startTime, timeout);
            } else {
                return executeDirect(definition, params, startTime);
            }

        } catch (Exception e) {
            log.error("工具执行异常: {}", e.getMessage(), e);
            return ToolExecutionResult.failure(toolName, e.getMessage());
        }
    }

    private ToolExecutionResult executeDirect(ToolDefinition definition,
                                              Map<String, Object> params,
                                              long startTime) throws Exception {
        Object[] args = buildMethodArgs(definition, params);
        Object result = definition.getMethod().invoke(definition.getInstance(), args);
        long executionTime = System.currentTimeMillis() - startTime;
        return ToolExecutionResult.success(
                definition.getName(),
                result != null ? result.toString() : "执行成功（无返回）",
                executionTime
        );
    }

    private ToolExecutionResult executeWithTimeout(ToolDefinition definition,
                                                   Map<String, Object> params,
                                                   long startTime,
                                                   int timeout) {
        try {
            Future<Object> future = executor.submit(() -> {
                Object[] args = buildMethodArgs(definition, params);
                return definition.getMethod().invoke(definition.getInstance(), args);
            });

            Object result = future.get(timeout, TimeUnit.SECONDS);
            long executionTime = System.currentTimeMillis() - startTime;
            return ToolExecutionResult.success(
                    definition.getName(),
                    result != null ? result.toString() : "执行成功（无返回）",
                    executionTime
            );

        } catch (java.util.concurrent.TimeoutException e) {
            return ToolExecutionResult.timeout(definition.getName());
        } catch (Exception e) {
            return ToolExecutionResult.failure(definition.getName(), e.getMessage());
        }
    }

    private Object[] buildMethodArgs(ToolDefinition definition, Map<String, Object> params) {
        List<ToolParameter> paramDefs = definition.getParameters();
        Object[] args = new Object[paramDefs.size()];

        for (int i = 0; i < paramDefs.size(); i++) {
            ToolParameter paramDef = paramDefs.get(i);
            String paramName = paramDef.getName();
            Object value = params.get(paramName);

            if (value == null && paramDef.getDefaultValue() != null) {
                value = paramDef.getDefaultValue();
            }

            if (value != null) {
                value = convertValue(value, paramDef.getParameterClass());
            }

            args[i] = value;
        }

        return args;
    }

    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null || targetType.isInstance(value)) {
            return value;
        }

        String str = value.toString();
        try {
            if (targetType == Integer.class || targetType == int.class) {
                return Integer.parseInt(str);
            }
            if (targetType == Long.class || targetType == long.class) {
                return Long.parseLong(str);
            }
            if (targetType == Boolean.class || targetType == boolean.class) {
                return Boolean.parseBoolean(str);
            }
            if (targetType == Double.class || targetType == double.class) {
                return Double.parseDouble(str);
            }
        } catch (NumberFormatException e) {
            log.warn("类型转换失败: {} -> {}, 使用原值", str, targetType.getSimpleName());
            return value;
        }
        return value;
    }

    // ==================== BeanPostProcessor 实现 ====================

    @Override
    public Object postProcessAfterInitialization(@NotNull Object bean, @NotNull String beanName) throws BeansException {
        if (!enableAutoScan) {
            return bean;
        }

        if (scanPackages != null && !scanPackages.isEmpty()) {
            String beanPackage = bean.getClass().getPackage().getName();
            if (!beanPackage.startsWith(scanPackages)) {
                return bean;
            }
        }

        Method[] methods = bean.getClass().getDeclaredMethods();
        boolean hasTools = false;

        for (Method method : methods) {
            AgentTool annotation = method.getAnnotation(AgentTool.class);
            if (annotation != null) {
                ToolDefinition definition = buildToolDefinition(method, bean, beanName, annotation);
                registerTool(definition);
                hasTools = true;
            }
        }

        if (hasTools) {
            log.info("从 Bean {} 中发现并注册工具", beanName);
        }

        return bean;
    }

    private ToolDefinition buildToolDefinition(Method method, Object bean,
                                               String beanName, AgentTool annotation) {
        String toolName = annotation.name().isEmpty() ? method.getName() : annotation.name();

        List<ToolParameter> parameters = new ArrayList<>();
        Parameter[] methodParams = method.getParameters();

        for (int i = 0; i < methodParams.length; i++) {
            Parameter param = methodParams[i];
            ToolParam paramAnnotation = param.getAnnotation(ToolParam.class);

            ToolParameter.ToolParameterBuilder builder = ToolParameter.builder()
                    .index(i)
                    .parameterClass(param.getType())
                    .type(param.getType().getSimpleName());

            if (paramAnnotation != null) {
                builder.name(paramAnnotation.name().isEmpty() ? param.getName() : paramAnnotation.name())
                        .description(paramAnnotation.description())
                        .required(paramAnnotation.required())
                        .constraint(paramAnnotation.constraint())
                        .example(paramAnnotation.example());
                if (!paramAnnotation.defaultValue().isEmpty()) {
                    builder.defaultValue(paramAnnotation.defaultValue());
                }
            } else {
                builder.name(param.getName())
                        .description("")
                        .required(true);
            }

            parameters.add(builder.build());
        }

        method.setAccessible(true);

        return ToolDefinition.builder()
                .name(toolName)
                .description(annotation.description())
                .method(method)
                .instance(bean)
                .parameters(parameters)
                .readOnly(annotation.readOnly())
                .timeout(annotation.timeout())
                .visible(annotation.visible())
                .requireConfirm(annotation.requireConfirm())
                .beanName(beanName)
                .build();
    }
}

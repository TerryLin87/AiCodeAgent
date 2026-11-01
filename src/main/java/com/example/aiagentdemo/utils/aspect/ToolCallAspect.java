package com.example.aiagentdemo.utils.aspect;

import com.example.aiagentdemo.tools.ToolCallGuard;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.*;

@Slf4j
@Aspect
@Component
public class ToolCallAspect {
    private final ToolCallGuard toolCallGuard;
    private final ExecutorService toolExecutor;

    @Value("${agent.tool.max-return-chars:8000}")
    private int maxReturnChars;

    @Value("${agent.tool.timeout.readFile:10}")
    private long readFileTimeout;

    @Value("${agent.tool.timeout.searchCode:15}")
    private long searchCodeTimeout;

    @Value("${agent.tool.timeout.delegateToSubAgent:30}")
    private long delegateTimeout;

    @Value("${agent.tool.timeout.default:15}")
    private long defaultTimeout;

    public ToolCallAspect(ToolCallGuard toolCallGuard) {
        this.toolCallGuard = toolCallGuard;
        this.toolExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "tool-executor");
            t.setDaemon(true);
            return t;
        });
    }

    @Around("@annotation(dev.langchain4j.agent.tool.Tool)")
    public Object guard(ProceedingJoinPoint pjp) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        String toolName = method.getName();

        String sessionId = ToolCallGuard.getCurrentSession();

        // 1. 前置：工具调用计数
        if (sessionId != null && !toolCallGuard.tryAcquire(sessionId, toolName)) {
            return "[系统提示] 工具调用次数已达上限，请基于已有信息给出最终答案。";
        }

        // 2. 超时控制
        long timeoutSec = timeoutOf(toolName);
        long start = System.currentTimeMillis();
        Object result;
        try {
            Future<Object> future = toolExecutor.submit(() -> {
                try {
                    return pjp.proceed();
                } catch (Throwable t) {
                    throw new ExecutionException(t);
                }
            });
            try {
                result = future.get(timeoutSec, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                log.warn("工具 {} 执行超时（{}s），session={}", toolName, timeoutSec, sessionId);
                return "[工具执行超时] " + toolName + " 超过 " + timeoutSec + " 秒未返回，请简化查询或重试。";
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.error("工具 {} 执行异常", toolName, cause);
                return "[工具执行失败] " + toolName + ": " + safeMessage(cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "[工具执行中断] " + toolName;
            }
        } finally {
            log.info("工具调用: session={}, tool={}, cost={}ms",
                    sessionId, toolName, System.currentTimeMillis() - start);
        }

        // 3. 后置：返回内容截断
        if (result instanceof String s) {
            if (s.length() > maxReturnChars) {
                return s.substring(0, maxReturnChars) + "\n... (内容已截断，共 " + s.length() + " 字符)";
            }
        }
        return result;
    }

    private long timeoutOf(String toolName) {
        return switch (toolName) {
            case "readFile" -> readFileTimeout;
            case "searchCode" -> searchCodeTimeout;
            case "delegateToSubAgent" -> delegateTimeout;
            default -> defaultTimeout;
        };
    }

    private String safeMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) return t.getClass().getSimpleName();
        return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
    }

    @PreDestroy
    public void destroy() {
        log.info("Shutting down ToolCallAspect executor");
        toolExecutor.shutdown();
        try {
            if (!toolExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                toolExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            toolExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

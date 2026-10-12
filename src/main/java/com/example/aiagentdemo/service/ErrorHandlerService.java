package com.example.aiagentdemo.service;

import com.example.aiagentdemo.data.ErrorResult;
import com.example.aiagentdemo.enums.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Slf4j
@Service
public class ErrorHandlerService {
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;

    /**
     * 分析和分类异常
     */
    public ErrorResult analyzeException(Exception e) {
        log.error("异常分析: {}", e.getClass().getName());

        ErrorResult.ErrorResultBuilder builder = ErrorResult.builder()
                .retryCount(0)
                .maxRetries(MAX_RETRIES);

        // 分类异常
        if (isNetworkException(e)) {
            return builder
                    .errorType(ErrorType.NETWORK_ERROR)
                    .message("网络连接异常")
                    .details(e.getMessage())
                    .recoveryAction("等待后重试")
                    .isRecoverable(true)
                    .userMessage("网络不稳定，系统将自动重试...")
                    .build();
        }

        if (isTimeoutException(e)) {
            return builder
                    .errorType(ErrorType.TIMEOUT)
                    .message("请求超时")
                    .details(e.getMessage())
                    .recoveryAction("增加超时时间后重试")
                    .isRecoverable(true)
                    .userMessage("响应超时，系统将自动重试...")
                    .build();
        }

        if (isEmptyResultException(e)) {
            return builder
                    .errorType(ErrorType.EMPTY_RESULT)
                    .message("结果为空")
                    .details(e.getMessage())
                    .recoveryAction("尝试换一种方式查询")
                    .isRecoverable(true)
                    .userMessage("未找到相关内容，尝试换一种查询方式...")
                    .build();
        }

        if (isParseException(e)) {
            return builder
                    .errorType(ErrorType.PARSE_ERROR)
                    .message("解析异常")
                    .details(e.getMessage())
                    .recoveryAction("使用备用解析器")
                    .isRecoverable(true)
                    .userMessage("解析时遇到问题，正在尝试备用方案...")
                    .build();
        }

        if (isPermissionException(e)) {
            return builder
                    .errorType(ErrorType.PERMISSION_DENIED)
                    .message("权限不足")
                    .details(e.getMessage())
                    .recoveryAction("提示用户检查权限")
                    .isRecoverable(false)
                    .userMessage("权限不足，请检查您的访问权限")
                    .build();
        }

        // 未知异常
        return builder
                .errorType(ErrorType.UNKNOWN)
                .message("未知异常")
                .details(e.getMessage())
                .recoveryAction("降级处理")
                .isRecoverable(false)
                .userMessage("遇到了意外问题，建议重试或联系管理员")
                .build();
    }

    /**
     * 执行带重试的操作
     */
    public <T> T executeWithRetry(Supplier<T> action, String actionName) throws Exception {
        ErrorResult lastError = null;
        int attempt = 0;

        while (attempt < MAX_RETRIES) {
            attempt++;
            try {
                log.info("执行操作: {}, 尝试 {}/{}", actionName, attempt, MAX_RETRIES);
                return action.get();

            } catch (Exception e) {
                lastError = analyzeException(e);
                log.warn("操作失败: {}, 错误类型: {}", actionName, lastError.getErrorType());

                if (!lastError.isRecoverable()) {
                    throw new Exception("不可恢复错误: " + lastError.getUserMessage());
                }

                // 如果是最后一次尝试，不再等待
                if (attempt >= MAX_RETRIES) {
                    break;
                }

                // 等待后重试
                long delay = RETRY_DELAY_MS * attempt; // 递增延迟
                log.info("等待 {}ms 后重试...", delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new Exception("被中断");
                }
            }
        }

        throw new Exception("重试 " + MAX_RETRIES + " 次后仍然失败: " +
                (lastError != null ? lastError.getUserMessage() : "未知错误"));
    }

    /**
     * 获取降级结果
     */
    public String getFallbackResult(String actionName, ErrorResult error) {
        log.info("返回降级结果: action={}, error={}", actionName, error.getErrorType());

        return switch (error.getErrorType()) {
            case EMPTY_RESULT -> "未找到相关结果。建议：\n" +
                    "1. 检查关键词是否正确\n" +
                    "2. 尝试使用更宽泛的关键词\n" +
                    "3. 确认该内容是否存在于代码库中";
            case TIMEOUT -> "请求超时，但部分结果可能已经生成。建议：\n" +
                    "1. 稍后重试\n" +
                    "2. 缩小搜索范围";
            case NETWORK_ERROR -> "网络连接异常，请检查网络后重试。";
            case PARSE_ERROR -> "解析结果时遇到问题，但原始结果已保存，建议手动查看。";
            case PERMISSION_DENIED -> error.getUserMessage();
            default -> "遇到了意外问题，建议重试。如果问题持续，请联系技术支持。\n" +
                    "错误信息: " + error.getMessage();
        };
    }

    // ==================== 异常类型判断 ====================

    private boolean isNetworkException(Exception e) {
        return e instanceof ConnectException ||
                e instanceof java.net.ConnectException ||
                (e.getMessage() != null && e.getMessage().contains("connection"));
    }

    private boolean isTimeoutException(Exception e) {
        return e instanceof TimeoutException ||
                e instanceof SocketTimeoutException ||
                (e.getMessage() != null && e.getMessage().contains("timeout"));
    }

    private boolean isEmptyResultException(Exception e) {
        return e.getMessage() != null && (
                e.getMessage().contains("empty") ||
                        e.getMessage().contains("no result") ||
                        e.getMessage().contains("未找到"));
    }

    private boolean isParseException(Exception e) {
        return e instanceof com.fasterxml.jackson.core.JsonParseException ||
                e instanceof java.text.ParseException ||
                (e.getMessage() != null && e.getMessage().contains("parse"));
    }

    private boolean isPermissionException(Exception e) {
        return e instanceof java.nio.file.AccessDeniedException ||
                (e.getMessage() != null && (e.getMessage().contains("permission") ||
                        e.getMessage().contains("access denied")));
    }
}

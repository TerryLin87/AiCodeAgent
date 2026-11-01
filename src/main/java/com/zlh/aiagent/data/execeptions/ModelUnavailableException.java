package com.zlh.aiagent.data.execeptions;

/**
 * 模型不可用异常（网络、HTTP 5xx、超时、解析失败等）。
 * 调用方应捕获此异常并走降级路径，不要把异常文本当作正常回答。
 */
public class ModelUnavailableException extends RuntimeException {
    public ModelUnavailableException(String message) {
        super(message);
    }

    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

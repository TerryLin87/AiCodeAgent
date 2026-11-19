package com.zlh.aiagent.controller;

import com.zlh.aiagent.data.common.http.AgentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 参数校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("参数校验失败");
        return build(HttpStatus.BAD_REQUEST, "invalid_param", msg);
    }

    /** 兜底：不向前端暴露内部堆栈 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity handleOther(Exception e) {
        log.error("请求处理失败", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "服务内部错误，请稍后重试");
    }

    private ResponseEntity build(HttpStatus status, String code, String message) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("code", code);
        meta.put("message", message);
        AgentResponse body = AgentResponse.builder()
                .content(message)
                .executionTimeMs(0L)
                .metadata(meta)
                .build();
        return ResponseEntity.status(status).body(body);
    }
}

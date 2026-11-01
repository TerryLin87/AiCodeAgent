package com.zlh.aiagent.controller;

import com.zlh.aiagent.data.AgentSession;
import com.zlh.aiagent.data.http.ChatMessageRequest;
import com.zlh.aiagent.service.CodeAgentLoopService;
import com.zlh.aiagent.service.SessionManager;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final CodeAgentLoopService agentLoopService;
    private final SessionManager sessionManager;
    private final RateLimiter rateLimiter;

    public ChatController(CodeAgentLoopService agentLoopService,
                          SessionManager sessionManager,
                          RateLimiterRegistry rateLimiterRegistry) {
        this.agentLoopService = agentLoopService;
        this.sessionManager = sessionManager;
        this.rateLimiter = rateLimiterRegistry.rateLimiter("chat");
    }

    /**
     * 统一对话入口。
     * 如果请求中不带 sessionId，则自动生成一个新的；
     * 如果带 sessionId，则在原会话基础上继续多轮对话。
     */
    @PostMapping
    public ResponseEntity<?> chat(@Valid @RequestBody ChatMessageRequest request) {
        long start = System.currentTimeMillis();

        try {
            Map<String, Object> response = RateLimiter.decorateSupplier(rateLimiter, () -> {
                try {
                    String sessionId = request.getSessionId();
                    boolean isNewSession = (sessionId == null || sessionId.isBlank());

                    // 如果是新会话，提前生成一个，方便最后返回给前端
                    if (isNewSession) {
                        sessionId = "session-" + UUID.randomUUID().toString().substring(0, 8);
                    }

                    // 执行 Agent 逻辑
                    String aiResponse = agentLoopService.execute(sessionId, request.getMessage());

                    // 获取最新会话状态
                    AgentSession session = sessionManager.getSession(sessionId);
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    result.put("sessionId", sessionId); // 返回 sessionId，前端下次请求时带上
                    result.put("response", aiResponse);
                    result.put("costMs", System.currentTimeMillis() - start);
                    if (session != null) {
                        result.put("messageCount", session.getMessages().size());
                    }
                    return result;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("请求被中断", e);
                }
            }).get();

            return ResponseEntity.ok(response);

        } catch (RequestNotPermitted e) {
            log.warn("触发限流保护");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1")
                    .body(Map.of("success", false, "error", "请求过于频繁，请稍后再试"));
        } catch (Exception e) {
            log.error("对话执行失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
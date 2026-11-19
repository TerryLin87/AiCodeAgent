package com.zlh.aiagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlh.aiagent.data.common.AgentSession;
import com.zlh.aiagent.data.common.http.AgentResponse;
import com.zlh.aiagent.data.common.http.ChatMessageRequest;
import com.zlh.aiagent.instances.IAgentStreamCallback;
import com.zlh.aiagent.service.common.CodeAgentLoopService;
import com.zlh.aiagent.service.common.SessionManager;
import com.zlh.aiagent.utils.CancellationToken;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
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
            }).get();

            return ResponseEntity.ok(response);

        } catch (RequestNotPermitted e) {
            log.warn("触发限流保护");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1")
                    .body(Map.of("success", false, "error", "请求过于频繁，请稍后再试"));
        } catch (Exception e) {
            log.error("对话执行失败", e);
            boolean isCancelled = e instanceof CancellationToken.CancelledException;
            return ResponseEntity.status(isCancelled ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", !isCancelled, "cancelled", isCancelled, "error", e.getMessage()));
        }
    }

    /**
     * 带引用的对话接口。
     */
    @PostMapping("/citations")
    public AgentResponse chatWithCitations(@Valid @RequestBody ChatMessageRequest req) {
        String sessionId = req.getSessionId() != null && !req.getSessionId().isBlank()
                ? req.getSessionId()
                : UUID.randomUUID().toString();
        try {
            return agentLoopService.executeWithCitations(sessionId, req.getMessage());
        } catch (CancellationToken.CancelledException ce) {
            return AgentResponse.builder()
                    .content("请求已被取消")
                    .sessionId(sessionId)
                    .cancelled(true)
                    .executionTimeMs(0)
                    .build();
        } catch (Exception e) {
            log.error("带引用对话执行失败", e);
            return AgentResponse.builder()
                    .content("处理失败：" + e.getMessage())
                    .sessionId(sessionId)
                    .cancelled(false)
                    .executionTimeMs(0)
                    .build();
        }
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter streamChat(@RequestParam String message,
                                 @RequestParam(required = false) String sessionId) {
        SseEmitter emitter = new SseEmitter(300_000L);
        if(sessionId == null || sessionId.isBlank()){
            sessionId = "session-" + UUID.randomUUID().toString().substring(0,8);
        }
        String finalSessionId = sessionId;

        // 客户端断开/超时/出错时，自动取消后端正在执行的请求
        Runnable cancelTask = () -> agentLoopService.cancelBySession(finalSessionId);
        emitter.onTimeout(cancelTask);
        emitter.onError(t -> cancelTask.run());
        emitter.onCompletion(cancelTask);

        // 首帧返回sessionId给前端，方便后续取消
        try {
            emitter.send(SseEmitter.event().name("meta")
                    .data("{\"sessionId\":\"" + escapeJson(finalSessionId) + "\"}", MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitter.complete();
            return emitter;
        }

        new Thread(() -> {
            try {
                agentLoopService.streamExecute(finalSessionId, message, new IAgentStreamCallback() {
                    private final ObjectMapper mapper = new ObjectMapper();
                    @Override
                    public void onToken(String token) {
                        try {
                            emitter.send(SseEmitter.event().name("token").data(token, MediaType.TEXT_PLAIN));
                        } catch (Exception e) {
                            log.debug("SSE token发送失败，客户端可能已断开: {}", e.getMessage());
                            emitter.complete();
                        }
                    }
                    @Override
                    public void onEvent(String eventName, String data) {
                        try {
                            emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.TEXT_PLAIN));
                        } catch (Exception e) {
                            log.warn("sse send event error", e);
                        }
                    }
                    @Override
                    public void onToolCalls(List<ToolExecutionRequest> toolExecutionRequests) {
                        try {
                            String json = mapper.writeValueAsString(toolExecutionRequests);
                            emitter.send(SseEmitter.event().name("tool_calls").data(json, MediaType.APPLICATION_JSON));
                        } catch (Exception e) {
                            log.warn("sse send tool_calls error", e);
                        }
                    }
                    @Override
                    public void onDone() {
                        try {
                            emitter.send(SseEmitter.event().name("done").data("{\"ok\":true}", MediaType.APPLICATION_JSON));
                            emitter.complete();
                        }catch (Exception ignore){}
                    }
                    @Override
                    public void onError(Throwable e) {
                        log.error("agent stream error", e);
                        boolean isCancelled = e instanceof CancellationToken.CancelledException;
                        String eventName = isCancelled ? "cancelled" : "error";
                        try {
                            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                            emitter.send(SseEmitter.event().name(eventName)
                                    .data("{\"msg\":\"" + escapeJson(msg) + "\"}", MediaType.APPLICATION_JSON));
                        } catch (Exception ignore) {}
                        emitter.complete();
                    }
                });
            }catch (Exception ex){
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data("{\"msg\":\"" + escapeJson(ex.getMessage()) + "\"}", MediaType.APPLICATION_JSON));
                } catch (Exception ignore) {}
                emitter.complete();
            }
        }, "sse-chat-" + finalSessionId).start();

        return emitter;
    }

    /**
     * 取消正在执行的请求
     */
    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestBody Map<String, String> body) {
        String sessionId = body == null ? null : body.get("sessionId");
        String requestId = body == null ? null : body.get("requestId");

        boolean cancelled = false;
        String by = "";
        if (requestId != null && !requestId.isBlank()) {
            cancelled = agentLoopService.cancelByRequestId(requestId.trim());
            by = "requestId";
        } else if (sessionId != null && !sessionId.isBlank()) {
            cancelled = agentLoopService.cancelBySession(sessionId.trim());
            by = "sessionId";
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("cancelled", cancelled);
        result.put("by", by);
        if (sessionId != null) result.put("sessionId", sessionId);
        return result;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    /**
     * JSON字符串转义，防止SSE返回的JSON语法错误
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
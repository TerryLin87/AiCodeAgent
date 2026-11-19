package com.zlh.aiagent.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {
    @Value("${agent.api.keys:}")
    private String configuredKeys;

    /** 未配置 API Key 时是否放行；仅本地/测试环境建议置 true */
    @Value("${agent.api.allow-when-not-configured:false}")
    private boolean allowWhenNotConfigured;


    private static final Set<String> WHITELIST = Set.of(
            "/actuator/health", "/actuator/prometheus"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (WHITELIST.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        Set<String> validKeys = parseKeys();
        if (validKeys.isEmpty()) {
            if (!allowWhenNotConfigured) {
                log.error("agent.api.keys 未配置，拒绝请求: {}", path);
                writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "auth_not_configured", "服务端未配置 API Key，已拒绝访问");
                return;
            }
            // 未配置 key 时，开发环境放行；生产环境应强制配置
            log.warn("agent.api.keys 未配置，放行请求: {}", path);
            chain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader("X-API-Key");
        if (!StringUtils.hasText(provided) || !validKeys.contains(provided)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "unauthorized", "X-API-Key 无效或缺失");
            return;
        }

        chain.doFilter(request, response);
    }

    private Set<String> parseKeys() {
        if (!StringUtils.hasText(configuredKeys)) return Set.of();
        return Arrays.stream(configuredKeys.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    private void writeError(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }
}

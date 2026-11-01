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

    private static final Set<String> WHITELIST = Set.of(
            "/actuator/health", "/actuator/prometheus"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (WHITELIST.stream().anyMatch(path::startsWith)) {
            chain.doFilter(request, response);
            return;
        }

        Set<String> validKeys = parseKeys();
        if (validKeys.isEmpty()) {
            // 未配置 key 时，开发环境放行；生产环境应强制配置
            log.warn("agent.api.keys 未配置，放行请求: {}", path);
            chain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader("X-API-Key");
        if (!StringUtils.hasText(provided) || !validKeys.contains(provided)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"X-API-Key 无效或缺失\"}");
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
}

package com.zlh.aiagent.config;

import com.zlh.aiagent.controller.interceptor.DebugLocalOnlyInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final DebugLocalOnlyInterceptor debugLocalOnlyInterceptor;

    public WebMvcConfig(DebugLocalOnlyInterceptor debugLocalOnlyInterceptor) {
        this.debugLocalOnlyInterceptor = debugLocalOnlyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(debugLocalOnlyInterceptor)
                .addPathPatterns("/debug/**");
    }
}

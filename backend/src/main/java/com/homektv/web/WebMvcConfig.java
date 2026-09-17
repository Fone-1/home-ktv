package com.homektv.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：
 * 注册管理员安全拦截器，保护管理端与敏感操作。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ObjectProvider<AdminSecurityInterceptor> adminSecurityInterceptorProvider;

    public WebMvcConfig(ObjectProvider<AdminSecurityInterceptor> adminSecurityInterceptorProvider) {
        this.adminSecurityInterceptorProvider = adminSecurityInterceptorProvider;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        AdminSecurityInterceptor interceptor = adminSecurityInterceptorProvider.getIfAvailable();
        if (interceptor != null) {
            registry.addInterceptor(interceptor)
                .addPathPatterns(
                        "/api/admin/**",
                        "/api/songs/*/convert-dual-track",
                        "/api/songs/batch-convert-dual-track",
                        "/api/songs/*/rollback-dual-track"
                );
        }
    }
}

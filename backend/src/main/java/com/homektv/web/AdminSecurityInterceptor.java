package com.homektv.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.library.AdminAuthService;
import com.homektv.library.SettingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;

/**
 * 管理后台安全拦截器：
 * 拦截 /api/admin/** 及敏感写操作（曲库删除、全量转码、AI 配置保存等），
 * 当系统启用管理员 PIN 且请求未授权时返回 401（ADMIN_AUTH_REQUIRED）。
 */
@Component
public class AdminSecurityInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminSecurityInterceptor.class);

    private final AdminAuthService authService;
    private final SettingService settingService;
    private final ObjectMapper mapper;

    @Autowired
    public AdminSecurityInterceptor(
            ObjectProvider<AdminAuthService> authServiceProvider,
            ObjectProvider<SettingService> settingServiceProvider,
            ObjectMapper mapper
    ) {
        this.authService = authServiceProvider.getIfAvailable();
        this.settingService = settingServiceProvider.getIfAvailable();
        this.mapper = mapper;
    }

    public AdminSecurityInterceptor(AdminAuthService authService, SettingService settingService, ObjectMapper mapper) {
        this.authService = authService;
        this.settingService = settingService;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        // 放行自身鉴权接口：/api/admin/auth/**
        if (uri.startsWith("/api/admin/auth/")) {
            return true;
        }

        // 若依赖未装配（如切片测试环境）或系统未开启管理员 PIN，完全放行（家庭局域网零摩擦默认体验）
        if (settingService == null || authService == null || !settingService.isAdminPinEnabled()) {
            return true;
        }

        // 提取 Token
        String token = extractToken(request);
        boolean isUnlocked = authService.isValidToken(token);
        if (isUnlocked) {
            return true;
        }

        String method = request.getMethod();
        boolean isSafeMethod = HttpMethod.GET.matches(method)
                || HttpMethod.HEAD.matches(method)
                || HttpMethod.OPTIONS.matches(method);

        // 如果是只读请求，且系统配置了“读取免登录”，则放行
        if (isSafeMethod && !settingService.isAdminReadRequireAuth()) {
            return true;
        }

        // 拦截写操作或强制鉴权的读操作
        log.debug("拦截未授权管理请求: {} {}", method, uri);
        rejectUnauthorized(response);
        return false;
    }

    private String extractToken(HttpServletRequest request) {
        String token = request.getHeader("X-Admin-Token");
        if (token == null || token.isBlank()) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                token = auth.substring(7).trim();
            }
        }
        return token;
    }

    private void rejectUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> error = Map.of(
                "code", "ADMIN_AUTH_REQUIRED",
                "message", "需要管理员权限，请输入 PIN 码解锁"
        );
        response.getWriter().write(mapper.writeValueAsString(error));
    }
}

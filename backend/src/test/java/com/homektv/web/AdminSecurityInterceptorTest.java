package com.homektv.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.library.AdminAuthService;
import com.homektv.library.SettingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdminSecurityInterceptorTest {

    private AdminAuthService authService;
    private SettingService settingService;
    private ObjectMapper mapper;
    private AdminSecurityInterceptor interceptor;

    @BeforeEach
    void setUp() {
        authService = mock(AdminAuthService.class);
        settingService = mock(SettingService.class);
        mapper = new ObjectMapper();
        interceptor = new AdminSecurityInterceptor(authService, settingService, mapper);
    }

    private HttpServletRequest mockRequest(String method, String uri, String token) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn(method);
        when(req.getRequestURI()).thenReturn(uri);
        when(req.getHeader("X-Admin-Token")).thenReturn(token);
        return req;
    }

    private HttpServletResponse mockResponse(StringWriter writer) throws Exception {
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(res.getWriter()).thenReturn(new PrintWriter(writer));
        return res;
    }

    @Test
    void authEndpointBypassed() throws Exception {
        HttpServletRequest req = mockRequest("POST", "/api/admin/auth/login", null);
        HttpServletResponse res = mock(HttpServletResponse.class);
        boolean result = interceptor.preHandle(req, res, new Object());
        assertThat(result).isTrue();
    }

    @Test
    void whenPinDisabledAllMethodsAllowed() throws Exception {
        when(settingService.isAdminPinEnabled()).thenReturn(false);

        HttpServletRequest postReq = mockRequest("POST", "/api/admin/songs", null);
        HttpServletResponse res = mock(HttpServletResponse.class);
        boolean result = interceptor.preHandle(postReq, res, new Object());
        assertThat(result).isTrue();
    }

    @Test
    void whenPinEnabledAndAuthorizedAllowed() throws Exception {
        when(settingService.isAdminPinEnabled()).thenReturn(true);
        when(authService.isValidToken("valid-token")).thenReturn(true);

        HttpServletRequest postReq = mockRequest("POST", "/api/admin/songs", "valid-token");
        HttpServletResponse res = mock(HttpServletResponse.class);
        boolean result = interceptor.preHandle(postReq, res, new Object());
        assertThat(result).isTrue();
    }

    @Test
    void whenPinEnabledAndUnauthorizedPostRejected() throws Exception {
        when(settingService.isAdminPinEnabled()).thenReturn(true);
        when(authService.isValidToken(null)).thenReturn(false);

        StringWriter sw = new StringWriter();
        HttpServletRequest postReq = mockRequest("POST", "/api/admin/songs", null);
        HttpServletResponse res = mockResponse(sw);

        boolean result = interceptor.preHandle(postReq, res, new Object());
        assertThat(result).isFalse();
        verify(res).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(sw.toString()).contains("ADMIN_AUTH_REQUIRED");
    }

    @Test
    void whenPinEnabledGetAllowedIfReadRequireAuthFalse() throws Exception {
        when(settingService.isAdminPinEnabled()).thenReturn(true);
        when(settingService.isAdminReadRequireAuth()).thenReturn(false);
        when(authService.isValidToken(null)).thenReturn(false);

        HttpServletRequest getReq = mockRequest("GET", "/api/admin/songs", null);
        HttpServletResponse res = mock(HttpServletResponse.class);

        boolean result = interceptor.preHandle(getReq, res, new Object());
        assertThat(result).isTrue();
    }

    @Test
    void whenPinEnabledGetRejectedIfReadRequireAuthTrue() throws Exception {
        when(settingService.isAdminPinEnabled()).thenReturn(true);
        when(settingService.isAdminReadRequireAuth()).thenReturn(true);
        when(authService.isValidToken(null)).thenReturn(false);

        StringWriter sw = new StringWriter();
        HttpServletRequest getReq = mockRequest("GET", "/api/admin/songs", null);
        HttpServletResponse res = mockResponse(sw);

        boolean result = interceptor.preHandle(getReq, res, new Object());
        assertThat(result).isFalse();
        verify(res).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(sw.toString()).contains("ADMIN_AUTH_REQUIRED");
    }
}

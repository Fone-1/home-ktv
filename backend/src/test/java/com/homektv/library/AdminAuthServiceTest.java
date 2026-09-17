package com.homektv.library;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdminAuthServiceTest {

    private SettingService settingService;
    private AdminAuthService authService;
    private final Map<String, Object> settingsStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        settingService = mock(SettingService.class);
        settingsStore.clear();

        doAnswer(inv -> {
            Map<String, Object> map = inv.getArgument(0);
            settingsStore.putAll(map);
            return null;
        }).when(settingService).putAll(anyMap());

        when(settingService.isAdminPinEnabled()).thenAnswer(inv -> Boolean.TRUE.equals(settingsStore.get(SettingService.ADMIN_PIN_ENABLED)));
        when(settingService.getAdminPinHash()).thenAnswer(inv -> (String) settingsStore.getOrDefault(SettingService.ADMIN_PIN_HASH, ""));
        when(settingService.isAdminReadRequireAuth()).thenAnswer(inv -> Boolean.TRUE.equals(settingsStore.get(SettingService.ADMIN_READ_REQUIRE_AUTH)));

        authService = new AdminAuthService(settingService);
    }

    @Test
    void whenPinDisabledLoginReturnsOpenToken() {
        settingsStore.put(SettingService.ADMIN_PIN_ENABLED, false);
        String token = authService.login("anything");
        assertThat(token).isEqualTo("open-admin-token");
    }

    @Test
    void setPinAndVerifyLoginFlow() {
        authService.updatePinSettings("123456", true, false);
        assertThat(settingService.isAdminPinEnabled()).isTrue();
        assertThat(settingService.getAdminPinHash()).contains("$");

        // 错误 PIN
        String wrongToken = authService.login("654321");
        assertThat(wrongToken).isNull();

        // 正确 PIN
        String validToken = authService.login("123456");
        assertThat(validToken).isNotNull().startsWith("admin-");
        assertThat(authService.isValidToken(validToken)).isTrue();

        // 注销
        authService.logout(validToken);
        assertThat(authService.isValidToken(validToken)).isFalse();
    }

    @Test
    void disablingPinClearsTokens() {
        authService.updatePinSettings("8888", true, false);
        String token = authService.login("8888");
        assertThat(authService.isValidToken(token)).isTrue();

        // 停用 PIN
        authService.updatePinSettings(null, false, false);
        assertThat(settingService.isAdminPinEnabled()).isFalse();
        assertThat(authService.isValidToken(token)).isFalse();
    }
}

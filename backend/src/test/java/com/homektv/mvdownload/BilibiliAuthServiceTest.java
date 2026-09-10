package com.homektv.mvdownload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.Setting;
import com.homektv.repo.SettingRepository;
import com.homektv.web.dto.BilibiliAccountDto;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BilibiliAuthServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private SettingRepository createMockRepo(Map<String, Setting> db) {
        return (SettingRepository) Proxy.newProxyInstance(
                SettingRepository.class.getClassLoader(),
                new Class<?>[]{SettingRepository.class},
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) {
                        return Optional.ofNullable(db.get((String) args[0]));
                    }
                    if ("save".equals(method.getName())) {
                        Setting s = (Setting) args[0];
                        db.put(s.getKey(), s);
                        return s;
                    }
                    if ("deleteById".equals(method.getName())) {
                        db.remove((String) args[0]);
                        return null;
                    }
                    return null;
                }
        );
    }

    @Test
    void unauthenticatedWhenNoSettingsStored() {
        Map<String, Setting> db = new HashMap<>();
        SettingRepository repo = createMockRepo(db);
        BilibiliAuthService authService = new BilibiliAuthService(repo, mapper);
        authService.init();

        BilibiliAccountDto account = authService.getAccountStatus();
        assertThat(account.isLoggedIn()).isFalse();
        assertThat(authService.getAuthCookie()).isNull();

        // 接入 Wbi 测试未登录时仅输出设备追踪指纹
        BilibiliWbi wbi = new BilibiliWbi(mapper, authService);
        String cookie = wbi.getCookieHeader();
        assertThat(cookie).contains("buvid3=");
        assertThat(cookie).doesNotContain("SESSDATA");
    }

    @Test
    void loadsAndExposesStoredAccountCookie() throws Exception {
        Map<String, Setting> db = new HashMap<>();
        String storedJson = mapper.writeValueAsString(new BilibiliAuthService.AuthRecord(
                "SESSDATA=mock_sessdata; bili_jct=mock_jct; DedeUserID=12345",
                "mock_refresh_token",
                12345L,
                "测试K歌官",
                "https://example.com/face.jpg",
                1,
                2,
                "年度大会员",
                System.currentTimeMillis()
        ));
        db.put(BilibiliAuthService.SETTING_KEY_AUTH, new Setting(BilibiliAuthService.SETTING_KEY_AUTH, storedJson));

        SettingRepository repo = createMockRepo(db);
        BilibiliAuthService authService = new BilibiliAuthService(repo, mapper);
        authService.init();

        BilibiliAccountDto account = authService.getAccountStatus();
        assertThat(account.isLoggedIn()).isTrue();
        assertThat(account.mid()).isEqualTo(12345L);
        assertThat(account.uname()).isEqualTo("测试K歌官");
        assertThat(account.vipLabel()).isEqualTo("年度大会员");
        assertThat(account.vipStatus()).isEqualTo(1);
        assertThat(authService.getAuthCookie()).contains("SESSDATA=mock_sessdata");

        // 接入 Wbi 测试已登录时 Cookie 自动前置合并
        BilibiliWbi wbi = new BilibiliWbi(mapper, authService);
        String cookie = wbi.getCookieHeader();
        assertThat(cookie).startsWith("SESSDATA=mock_sessdata; bili_jct=mock_jct; DedeUserID=12345; buvid3=");

        // 退出登录后状态清空
        authService.logout();
        assertThat(authService.getAccountStatus().isLoggedIn()).isFalse();
        assertThat(authService.getAuthCookie()).isNull();
        assertThat(db).doesNotContainKey(BilibiliAuthService.SETTING_KEY_AUTH);
        assertThat(wbi.getCookieHeader()).doesNotContain("SESSDATA");
    }
}

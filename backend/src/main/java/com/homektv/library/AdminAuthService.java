package com.homektv.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理员身份鉴权服务：
 * 提供可选 PIN 码加盐哈希校验、短期内存 Token 管理与生命周期控制。
 */
@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);
    public static final long TOKEN_TTL_MS = 2 * 3600 * 1000L; // 2 小时

    private final SettingService settingService;
    private final Map<String, Long> activeTokens = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminAuthService(SettingService settingService) {
        this.settingService = settingService;
    }

    /**
     * 校验传入的 Token 是否处于有效期内。
     */
    public boolean isValidToken(String token) {
        if (token == null || token.isBlank()) return false;
        Long expireAt = activeTokens.get(token);
        if (expireAt == null) return false;
        if (System.currentTimeMillis() > expireAt) {
            activeTokens.remove(token);
            return false;
        }
        return true;
    }

    /**
     * 校验输入的 PIN 是否正确。
     */
    public boolean verifyPin(String rawPin) {
        if (rawPin == null || rawPin.isBlank()) return false;
        String stored = settingService.getAdminPinHash();
        if (stored == null || !stored.contains("$")) {
            return false;
        }
        String[] parts = stored.split("\\$", 2);
        String salt = parts[0];
        String expectedHash = parts[1];
        String actualHash = hashWithSalt(salt, rawPin);
        return expectedHash.equals(actualHash);
    }

    /**
     * 登录校验通过后颁发短期 Token。
     */
    public String login(String rawPin) {
        if (!settingService.isAdminPinEnabled()) {
            return "open-admin-token";
        }
        if (!verifyPin(rawPin)) {
            return null;
        }
        String token = "admin-" + UUID.randomUUID().toString().replace("-", "");
        activeTokens.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
        return token;
    }

    /**
     * 注销指定 Token。
     */
    public void logout(String token) {
        if (token != null) {
            activeTokens.remove(token);
        }
    }

    /**
     * 设置或修改 PIN 码。
     * @param newPin 新 PIN 码（若为空或 null 且 enabled=false 则表示停用 PIN）
     * @param enabled 是否启用 PIN 保护
     * @param readRequireAuth 读取操作是否强制需要鉴权
     */
    public synchronized void updatePinSettings(String newPin, boolean enabled, boolean readRequireAuth) {
        if (!enabled) {
            settingService.putAll(Map.of(
                    SettingService.ADMIN_PIN_ENABLED, false,
                    SettingService.ADMIN_READ_REQUIRE_AUTH, readRequireAuth
            ));
            activeTokens.clear();
            return;
        }

        if (newPin != null && !newPin.isBlank()) {
            byte[] saltBytes = new byte[16];
            secureRandom.nextBytes(saltBytes);
            String salt = HexFormat.of().formatHex(saltBytes);
            String hash = hashWithSalt(salt, newPin);
            String stored = salt + "$" + hash;

            settingService.putAll(Map.of(
                    SettingService.ADMIN_PIN_ENABLED, true,
                    SettingService.ADMIN_PIN_HASH, stored,
                    SettingService.ADMIN_READ_REQUIRE_AUTH, readRequireAuth
            ));
            activeTokens.clear();
        } else {
            // 仅修改开关配置
            settingService.putAll(Map.of(
                    SettingService.ADMIN_PIN_ENABLED, enabled,
                    SettingService.ADMIN_READ_REQUIRE_AUTH, readRequireAuth
            ));
        }
    }

    private String hashWithSalt(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest(pin.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not supported", e);
        }
    }
}

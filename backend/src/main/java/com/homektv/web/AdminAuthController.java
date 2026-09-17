package com.homektv.web;

import com.homektv.library.AdminAuthService;
import com.homektv.library.SettingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理员安全鉴权接口：
 * 提供鉴权状态查询、PIN 码解锁换取短期 Token、注销与 PIN 码设置维护。
 */
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final AdminAuthService authService;
    private final SettingService settingService;

    public AdminAuthController(AdminAuthService authService, SettingService settingService) {
        this.authService = authService;
        this.settingService = settingService;
    }

    /**
     * 查询当前管理员安全鉴权状态与客户端解锁状态。
     */
    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        boolean pinEnabled = settingService.isAdminPinEnabled();
        boolean pinSet = !settingService.getAdminPinHash().isBlank();
        boolean readRequireAuth = settingService.isAdminReadRequireAuth();
        String token = extractToken(request);
        boolean unlocked = !pinEnabled || authService.isValidToken(token);

        return Map.of(
                "pin_enabled", pinEnabled,
                "pin_set", pinSet,
                "read_require_auth", readRequireAuth,
                "unlocked", unlocked
        );
    }

    /**
     * 输入 PIN 码进行解锁，换取短期管理员 Token（2 小时有效期）。
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String pin = body != null ? body.get("pin") : "";
        String token = authService.login(pin);
        if (token == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "code", "INVALID_PIN",
                    "message", "PIN 码不正确，请重新输入"
            ));
        }
        return ResponseEntity.ok(Map.of(
                "token", token,
                "expires_in", AdminAuthService.TOKEN_TTL_MS / 1000
        ));
    }

    /**
     * 主动注销 Token 重新锁定。
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        String token = extractToken(request);
        authService.logout(token);
        return Map.of("success", true);
    }

    /**
     * 配置或修改管理员 PIN 码与免登录规则。
     */
    @PostMapping("/pin")
    public ResponseEntity<?> updatePin(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        boolean currentlyEnabled = settingService.isAdminPinEnabled();
        String token = extractToken(request);
        boolean isUnlocked = authService.isValidToken(token);

        String oldPin = body != null && body.get("old_pin") != null ? body.get("old_pin").toString() : "";
        String newPin = body != null && body.get("new_pin") != null ? body.get("new_pin").toString() : "";
        boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
        boolean readRequireAuth = body != null && Boolean.TRUE.equals(body.get("read_require_auth"));

        // 若当前已启用 PIN，则必须满足以下条件之一方可修改：
        // 1. 请求携带有效短期 Token（已解锁状态）；
        // 2. 提供了正确的原 PIN 码。
        if (currentlyEnabled && !isUnlocked) {
            if (!authService.verifyPin(oldPin)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "code", "ADMIN_AUTH_REQUIRED",
                        "message", "修改安全设置需要输入正确的原 PIN 码"
                ));
            }
        }

        authService.updatePinSettings(newPin, enabled, readRequireAuth);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "enabled", enabled,
                "read_require_auth", readRequireAuth
        ));
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
}

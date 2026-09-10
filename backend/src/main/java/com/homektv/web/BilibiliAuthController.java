package com.homektv.web;

import com.homektv.mvdownload.BilibiliAuthService;
import com.homektv.web.dto.BilibiliAccountDto;
import com.homektv.web.dto.BilibiliPollResultDto;
import com.homektv.web.dto.BilibiliQrCodeDto;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 哔哩哔哩扫码登录与账号鉴权控制器。
 * 为前端管理台提供二维码生成、轮询扫码状态、读取登录详情与登出操作。
 */
@RestController
@RequestMapping("/api/mv/bilibili/auth")
public class BilibiliAuthController {

    private final BilibiliAuthService authService;

    public BilibiliAuthController(BilibiliAuthService authService) {
        this.authService = authService;
    }

    /**
     * 申请 B 站登录二维码
     */
    @GetMapping("/qrcode")
    public BilibiliQrCodeDto getQrCode() {
        return authService.generateQrCode();
    }

    /**
     * 轮询扫码状态
     *
     * @param key 申请二维码时返回的 qrcodeKey
     */
    @GetMapping("/poll")
    public BilibiliPollResultDto poll(@RequestParam("key") String key) {
        return authService.pollQrCode(key);
    }

    /**
     * 查询当前持久化的 B 站账号登录状态与会员标识
     */
    @GetMapping("/status")
    public BilibiliAccountDto status() {
        return authService.getAccountStatus();
    }

    /**
     * 主动刷新当前账号的最新资料与 Cookie 有效性
     */
    @PostMapping("/refresh")
    public BilibiliAccountDto refresh() {
        return authService.refreshAccountProfile();
    }

    /**
     * 退出 B 站登录并销毁持久化凭据
     */
    @PostMapping("/logout")
    public Map<String, Object> logout() {
        authService.logout();
        return Map.of("code", "OK", "message", "已退出 B 站登录并恢复未登录态");
    }
}

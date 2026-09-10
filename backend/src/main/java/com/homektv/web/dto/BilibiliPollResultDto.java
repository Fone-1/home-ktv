package com.homektv.web.dto;

/**
 * B 站二维码扫码轮询结果。
 * code 取值：
 * 0 - 登录成功
 * 86101 - 未扫码
 * 86090 - 二维码已扫码未确认
 * 86038 - 二维码已失效
 */
public record BilibiliPollResultDto(
        /** 轮询状态码 */
        int code,

        /** 用户可读的状态提示描述 */
        String message,

        /** 登录成功时携带的脱敏账号详情 (未登录成功时为 null) */
        BilibiliAccountDto account
) {}

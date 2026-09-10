package com.homektv.web.dto;

/**
 * B 站扫码登录二维码数据传输对象。
 * 包含二维码原始密钥、跳转 URL 以及经后端预渲染的 Base64 图片直链。
 */
public record BilibiliQrCodeDto(
        /** B 站二维码唯一轮询标识 key */
        String qrcodeKey,

        /** 扫码目标 URL (供手机客户端深度链接或备用渲染) */
        String url,

        /** Base64 Data URL 格式的 PNG 二维码图片 (data:image/png;base64,...) */
        String qrImgBase64,

        /** 二维码有效时间 (秒，通常为 180 秒) */
        int expireSeconds
) {}

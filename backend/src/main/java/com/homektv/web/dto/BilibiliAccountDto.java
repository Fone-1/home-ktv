package com.homektv.web.dto;

/**
 * B 站当前登录账号信息（面向前端安全脱敏视图，不暴露真实 SESSDATA 等敏感凭证）。
 */
public record BilibiliAccountDto(
        /** 是否已成功登录且凭据有效 */
        boolean isLoggedIn,

        /** B 站用户唯一数字 ID (mid) */
        Long mid,

        /** B 站用户昵称 */
        String uname,

        /** 用户头像图片地址 */
        String face,

        /** 大会员状态：0-非大会员，1-大会员 */
        int vipStatus,

        /** 大会员类型：0-无，1-月度大会员，2-年度及以上大会员 */
        int vipType,

        /** 大会员徽章显示文本 (如“年度大会员”) */
        String vipLabel,

        /** 最近一次同步或登录的时间戳 (毫秒) */
        Long updatedAt
) {
    public static BilibiliAccountDto unauthenticated() {
        return new BilibiliAccountDto(false, null, null, null, 0, 0, null, null);
    }
}

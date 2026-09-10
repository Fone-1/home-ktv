package com.homektv.mvdownload;

import com.homektv.web.ApiException;

import java.util.Locale;

public enum MvProvider {
    NETEASE("网易云音乐"),
    BILIBILI("哔哩哔哩");

    private final String displayName;

    MvProvider(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static MvProvider parse(String value) {
        try {
            return valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException("MV_PROVIDER_INVALID", "不支持的视频来源平台：" + value);
        }
    }
}

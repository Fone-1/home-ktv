package com.homektv.mvdownload;

import java.util.Map;

/**
 * MV 媒体流信息。
 * 单流（网易云）：包含 videoUrl（即完整 MP4 地址）；
 * DASH 分离流（B站）：包含 videoUrl 与 audioUrl，下载后需要使用 FFmpeg 进行无损合流。
 */
public record MvStreamInfo(
        String videoUrl,
        String audioUrl,
        boolean isDash,
        Map<String, String> httpHeaders
) {
    public static MvStreamInfo single(String videoUrl, Map<String, String> httpHeaders) {
        return new MvStreamInfo(videoUrl, null, false, httpHeaders);
    }

    public static MvStreamInfo dash(String videoUrl, String audioUrl, Map<String, String> httpHeaders) {
        return new MvStreamInfo(videoUrl, audioUrl, true, httpHeaders);
    }
}

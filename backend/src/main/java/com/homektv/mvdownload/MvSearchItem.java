package com.homektv.mvdownload;

/**
 * 在线 MV 搜索结果项。
 */
public record MvSearchItem(
        String provider,
        String externalId,
        String title,
        String artist,
        Integer durationMs,
        String coverUrl,
        String resolution,
        String sourceUrl
) {}

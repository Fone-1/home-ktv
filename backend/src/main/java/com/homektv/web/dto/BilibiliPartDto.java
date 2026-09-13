package com.homektv.web.dto;

/**
 * 哔哩哔哩视频分集/分P详细信息数据传输对象。
 *
 * @param page       分集序号，从 1 开始
 * @param cid        分集独立内容标识符（换取流媒体播放地址的关键凭证）
 * @param part       分集标题（通常为分P的具体歌曲名称或章节名）
 * @param durationMs 分集时长（毫秒）
 * @param coverUrl   分集专属预览帧/封面图（若无则可回退使用主视频封面）
 */
public record BilibiliPartDto(
        int page,
        long cid,
        String part,
        int durationMs,
        String coverUrl
) {}

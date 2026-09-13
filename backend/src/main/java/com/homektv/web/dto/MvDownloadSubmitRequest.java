package com.homektv.web.dto;

/**
 * MV 在线下载提交请求。
 *
 * @param provider             数据源平台（NETEASE, BILIBILI）
 * @param externalId           外部平台标识符（对于 B 站多 P 支持形如 BVxxx?p=2&cid=yyy）
 * @param title                歌曲/MV 标题（分集下载时为具体分P曲目名）
 * @param artist               歌手/UP主名称
 * @param coverUrl             封面图片 URL
 * @param resolution           期望清晰度（默认 1080p）
 * @param autoEnqueue          下载入库后是否自动加入点歌队列
 * @param autoConvertDualTrack 是否自动执行单音轨转双轨伴奏流水线
 * @param durationMs           曲目/视频时长（毫秒，支持单集准确时长）
 */
public record MvDownloadSubmitRequest(
        String provider,
        String externalId,
        String title,
        String artist,
        String coverUrl,
        String resolution,
        Boolean autoEnqueue,
        Boolean autoConvertDualTrack,
        Integer durationMs
) {
    public MvDownloadSubmitRequest(
            String provider,
            String externalId,
            String title,
            String artist,
            String coverUrl,
            String resolution,
            Boolean autoEnqueue,
            Boolean autoConvertDualTrack
    ) {
        this(provider, externalId, title, artist, coverUrl, resolution, autoEnqueue, autoConvertDualTrack, null);
    }
}

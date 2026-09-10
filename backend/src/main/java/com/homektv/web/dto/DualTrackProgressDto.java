package com.homektv.web.dto;

/**
 * 双轨伴奏转换进度 DTO。
 *
 * @param currentSongId  当前正在处理的歌曲 ID（空闲时为 null 或 0）
 * @param title          当前正在处理的歌曲标题
 * @param progress       当前总体或单曲进度百分比 (0~100)
 * @param pendingCount   排队待处理歌曲数量
 * @param processedCount 已处理完成数量
 * @param totalCount     批次总数量
 * @param running        是否有任务正在执行中
 * @param lastMessage    最新状态或错误提示
 */
public record DualTrackProgressDto(
        Long currentSongId,
        String title,
        int progress,
        int pendingCount,
        int processedCount,
        int totalCount,
        boolean running,
        String lastMessage
) {
    public static DualTrackProgressDto idle() {
        return new DualTrackProgressDto(0L, "", 0, 0, 0, 0, false, "空闲中");
    }
}

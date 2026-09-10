package com.homektv.web.dto;

/**
 * 单曲转双轨伴奏执行结果 DTO。
 *
 * @param songId   歌曲 ID
 * @param status   状态: "SUCCESS", "PROCESSING", "FAILED", "RESTORED"
 * @param message  结果说明或提示信息
 * @param filePath 输出文件路径
 */
public record DualTrackConvertResultDto(
        Long songId,
        String status,
        String message,
        String filePath
) {}

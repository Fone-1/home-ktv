package com.homektv.web.dto;

/**
 * 单曲转双轨伴奏请求体。
 *
 * @param mode           转换模式: "DSP" (极速消音) 或 "AI" / "REMOTE_AI" (深度学习分离)
 * @param backupOriginal 是否保留原始单轨文件为 .original.bak
 * @param outputFormat   输出视频容器格式 ("mkv" 推荐, 或 "mp4")
 */
public record DualTrackConvertRequest(
        String mode,
        Boolean backupOriginal,
        String outputFormat
) {}

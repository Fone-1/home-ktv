package com.homektv.dualtrack;

import java.nio.file.Path;

/**
 * 人声伴奏分离引擎接口（SPI）。
 * 负责从输入的单音轨视频或音频中提取出伴奏音频流。
 */
public interface VocalSeparationEngine {

    /**
     * 返回该引擎支持的分离模式。
     */
    VocalSeparationMode getMode();

    /**
     * 检查当前引擎在宿主或网络环境中是否就绪可用。
     */
    boolean isAvailable();

    /**
     * 执行分离并生成纯伴奏音频文件。
     *
     * @param inputMedia  原始视频或音频文件路径
     * @param outputAudio 输出伴奏音频目标文件路径（如 .aac 或 .wav）
     * @param bitrate     伴奏音频比特率（如 "192k"）
     * @return 实际生成的伴奏音频路径
     * @throws Exception 分离过程中的异常
     */
    Path separateAccompaniment(Path inputMedia, Path outputAudio, String bitrate) throws Exception;
}

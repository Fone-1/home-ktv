package com.homektv.dualtrack;

import com.homektv.media.ExternalProcessRunner;
import com.homektv.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 双音轨无损合流与重构封装引擎。
 * 将原始单轨视频无损合流生成带 Track 0 (原唱) 与 Track 1 (伴奏, disposition:karaoke) 的标准双音轨 KTV 视频。
 */
@Component
public class DualTrackRemuxer {

    private static final Logger log = LoggerFactory.getLogger(DualTrackRemuxer.class);

    private final String ffmpegPath;

    public DualTrackRemuxer(@Value("${app.transcode.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    /**
     * 执行极速单阶段 DSP 分频带声学消音并直接无损合流。
     * 视频轨执行 stream copy（耗时仅2-5秒，完全无损），音频轨 0 保留原唱，音频轨 1 实时滤镜消音为伴奏。
     *
     * @param inputVideo  原始单轨视频
     * @param outputVideo 目标输出视频（临时文件）
     * @param bitrate     音频比特率（如 192k）
     */
    public void remuxDsp(Path inputVideo, Path outputVideo, String bitrate) throws Exception {
        String effectiveBitrate = (bitrate != null && !bitrate.isBlank()) ? bitrate : "192k";
        boolean isMp4 = outputVideo.toString().toLowerCase().endsWith(".mp4");

        List<String> command = new ArrayList<>(List.of(
                ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                "-i", inputVideo.toAbsolutePath().toString(),
                "-filter_complex", DspVocalSeparationEngine.DSP_FILTER_GRAPH,
                "-map", "0:v:0",
                "-map", "0:a:0",
                "-map", "[accomp]",
                "-c:v", "copy",
                "-c:a:0", "aac",
                "-b:a:0", effectiveBitrate,
                "-metadata:s:a:0", "title=原唱",
                "-metadata:s:a:0", "handler_name=Original Vocal Track",
                "-c:a:1", "aac",
                "-b:a:1", effectiveBitrate,
                "-metadata:s:a:1", "title=伴奏",
                "-metadata:s:a:1", "handler_name=Accompaniment Karaoke Track",
                "-disposition:a:1", "+karaoke",
                "-avoid_negative_ts", "make_zero",
                "-shortest"
        ));
        if (isMp4) {
            command.addAll(List.of("-movflags", "+faststart"));
        } else {
            command.addAll(List.of("-cues_to_front", "true"));
        }
        command.add(outputVideo.toAbsolutePath().toString());

        runFfmpegCommand("DSP 单阶段双轨合流", command, outputVideo, 180);
    }

    /**
     * 将原始视频与外部提取的纯伴奏音频合流为双音轨视频。
     *
     * @param inputVideo         原始视频源
     * @param accompanimentAudio 伴奏音频源
     * @param outputVideo        目标合流视频路径
     * @param bitrate            伴奏音轨编码码率
     */
    public void remuxWithAccompanimentAudio(Path inputVideo, Path accompanimentAudio, Path outputVideo, String bitrate) throws Exception {
        String effectiveBitrate = (bitrate != null && !bitrate.isBlank()) ? bitrate : "192k";
        boolean isMp4 = outputVideo.toString().toLowerCase().endsWith(".mp4");

        List<String> command = new ArrayList<>(List.of(
                ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                "-i", inputVideo.toAbsolutePath().toString(),
                "-i", accompanimentAudio.toAbsolutePath().toString(),
                "-map", "0:v:0",
                "-map", "0:a:0",
                "-map", "1:a:0",
                "-c:v", "copy",
                "-c:a:0", "aac",
                "-b:a:0", effectiveBitrate,
                "-metadata:s:a:0", "title=原唱",
                "-metadata:s:a:0", "handler_name=Original Vocal Track",
                "-c:a:1", "aac",
                "-b:a:1", effectiveBitrate,
                "-metadata:s:a:1", "title=伴奏",
                "-metadata:s:a:1", "handler_name=Accompaniment Karaoke Track",
                "-disposition:a:1", "+karaoke",
                "-avoid_negative_ts", "make_zero",
                "-shortest"
        ));
        if (isMp4) {
            command.addAll(List.of("-movflags", "+faststart"));
        } else {
            command.addAll(List.of("-cues_to_front", "true"));
        }
        command.add(outputVideo.toAbsolutePath().toString());

        runFfmpegCommand("多音频流双轨重构合流", command, outputVideo, 180);
    }

    private void runFfmpegCommand(String actionName, List<String> command, Path outputVideo, int timeoutSeconds) throws Exception {
        log.info("开始执行 FFmpeg {}: output={}", actionName, outputVideo.getFileName());
        ExternalProcessRunner.Result result = ExternalProcessRunner.run(
                "FFmpeg " + actionName, command, outputVideo, Duration.ofSeconds(timeoutSeconds));

        if (result.timedOut()) {
            throw new ApiException("REMUX_TIMEOUT", actionName + "处理超时（" + timeoutSeconds + "秒）");
        }
        if (result.cancelled()) {
            throw new ApiException("REMUX_CANCELLED", actionName + "已取消");
        }
        if (result.exitCode() != 0 || !Files.exists(outputVideo) || Files.size(outputVideo) == 0) {
            throw new ApiException("REMUX_FAILED",
                    actionName + "失败 (exit=" + result.exitCode() + "): " + result.diagnostic());
        }
        log.info("FFmpeg {}完成: size={} bytes", actionName, Files.size(outputVideo));
    }

    public String getFfmpegPath() {
        return ffmpegPath;
    }
}

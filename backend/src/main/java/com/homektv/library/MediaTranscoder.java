package com.homektv.library;

import com.homektv.media.ExternalProcessRunner;
import com.homektv.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class MediaTranscoder {

    /** 全片转码可能耗时较长，但仍需有硬上限，避免卡死的 FFmpeg 永久占用转码队列。 */
    static final Duration TRANSCODE_TIMEOUT = Duration.ofMinutes(120);

    private final TranscodeHardwareService hardwareService;
    private final String ffmpegPath;

    public MediaTranscoder(TranscodeHardwareService hardwareService,
                           @Value("${app.transcode.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.hardwareService = hardwareService;
        this.ffmpegPath = ffmpegPath;
    }

    public Path transcode(Path source, Path output, SettingService.TranscodePolicy policy, boolean hasVideo) {
        List<String> command = new ArrayList<>(List.of(ffmpegPath, "-hide_banner", "-loglevel", "error", "-y"));
        boolean hardware = policy.hardwareAcceleration() && hasVideo;
        TranscodeHardwareService.HardwareStatus hardwareStatus = null;
        if (hardware) {
            hardwareStatus = hardwareService.requireAvailable(policy.videoCodec());
            if ("vaapi".equals(hardwareStatus.acceleration())) {
                command.addAll(List.of("-vaapi_device", hardwareStatus.device()));
            }
        }
        command.addAll(List.of("-i", source.toString(), "-map", "0:v:0?", "-map", "0:a?"));
        if (hasVideo) {
            if (hardware) {
                if ("rkmpp".equals(hardwareStatus.acceleration())) {
                    command.addAll(List.of("-pix_fmt", "nv12", "-c:v", policy.videoCodec() + "_rkmpp"));
                } else {
                    command.addAll(List.of("-vf", "format=nv12,hwupload", "-c:v", policy.videoCodec() + "_vaapi"));
                }
            } else {
                command.addAll(List.of("-c:v", "hevc".equals(policy.videoCodec()) ? "libx265" : "libx264",
                        "-pix_fmt", "yuv420p"));
            }
        }
        command.addAll(List.of("-c:a", audioEncoder(policy.audioCodec()), "-b:a", "192k", "-ar", "48000",
                "-c:s", "copy", output.toString()));

        boolean completed = false;
        try {
            ExternalProcessRunner.Result result = ExternalProcessRunner.run(
                    "转码", command, source, TRANSCODE_TIMEOUT);
            if (result.timedOut()) {
                throw new ApiException(hardware ? "HARDWARE_TRANSCODE_FAILED" : "TRANSCODE_FAILED",
                        "转码超时（" + TRANSCODE_TIMEOUT.toMinutes() + "分钟），已终止 FFmpeg 进程");
            }
            if (result.cancelled()) {
                throw new ApiException("TRANSCODE_INTERRUPTED", "转码已取消");
            }
            if (result.exitCode() != 0 || !Files.isReadable(output) || Files.size(output) == 0) {
                throw new ApiException(hardware ? "HARDWARE_TRANSCODE_FAILED" : "TRANSCODE_FAILED",
                        result.diagnostic().isBlank() ? "ffmpeg 转码失败" : result.diagnostic());
            }
            completed = true;
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("TRANSCODE_INTERRUPTED", "转码被中断");
        } catch (IOException e) {
            throw new ApiException(hardware ? "HARDWARE_TRANSCODE_FAILED" : "TRANSCODE_FAILED", e.getMessage());
        } finally {
            if (!completed) {
                try { Files.deleteIfExists(output); } catch (IOException ignored) { }
            }
        }
    }

    private static String audioEncoder(String codec) {
        return switch (codec) {
            case "mp3" -> "libmp3lame";
            case "opus" -> "libopus";
            default -> "aac";
        };
    }
}

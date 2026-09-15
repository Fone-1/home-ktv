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
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 FFmpeg 分频带声学反相中置消音的极速 DSP 伴奏分离引擎。
 *
 * <p>核心算法原理：
 * 绝大部分流行歌曲人声录制于立体声正中（L 与 R 同相且等幅）。单纯 L-R 差分消音会导致中置低频（底鼓与贝斯）一同丢失。
 * 本引擎通过 asplit 将音频分为低于 160Hz 的低频带和高于 160Hz 的中高频带，
 * 低频带完整保留左右立体声，中高频带执行反相中置人声消除，最后通过 amix 动态混音合成出高丰满度的立体声伴奏。
 */
@Component
public class DspVocalSeparationEngine implements VocalSeparationEngine {

    private static final Logger log = LoggerFactory.getLogger(DspVocalSeparationEngine.class);

    /** DSP 消音为纯音频处理，长曲目也应在 3 分钟内完成。 */
    private static final Duration DSP_TIMEOUT = Duration.ofMinutes(3);

    /**
     * 标准分频带声学消音滤镜链定义。
     * 保留低于 160Hz 贝斯底鼓，对中高频执行反相消除，混音后通过 aresample 锁定时间戳。
     */
    public static final String DSP_FILTER_GRAPH =
            "[0:a]asplit=2[low][high];" +
            "[low]lowpass=f=160,pan=stereo|c0=c0|c1=c1[bass];" +
            "[high]highpass=f=160,pan=stereo|c0=c0-c1|c1=c1-c0[vocals_cut];" +
            "[bass][vocals_cut]amix=inputs=2:weights=1.1 1.0,aresample=async=1:first_pts=0[accomp]";

    private final String ffmpegPath;

    public DspVocalSeparationEngine(@Value("${app.transcode.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    @Override
    public VocalSeparationMode getMode() {
        return VocalSeparationMode.DSP;
    }

    @Override
    public boolean isAvailable() {
        try {
            Process p = new ProcessBuilder(ffmpegPath, "-version").redirectErrorStream(true).start();
            boolean ok = p.waitFor(3, TimeUnit.SECONDS);
            if (!ok) p.destroyForcibly();
            return ok && p.exitValue() == 0;
        } catch (Exception e) {
            log.warn("FFmpeg 未检测到或不可用: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public Path separateAccompaniment(Path inputMedia, Path outputAudio, String bitrate) throws Exception {
        if (!Files.isReadable(inputMedia)) {
            throw new ApiException("FILE_NOT_FOUND", "输入媒体文件不可读: " + inputMedia);
        }

        String effectiveBitrate = (bitrate != null && !bitrate.isBlank()) ? bitrate : "192k";
        List<String> command = List.of(
                ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                "-i", inputMedia.toAbsolutePath().toString(),
                "-vn",
                "-filter_complex", DSP_FILTER_GRAPH,
                "-map", "[accomp]",
                "-c:a", "aac",
                "-b:a", effectiveBitrate,
                outputAudio.toAbsolutePath().toString()
        );

        log.info("执行 DSP 人声消除滤镜提取伴奏: input={}, output={}", inputMedia.getFileName(), outputAudio.getFileName());
        ExternalProcessRunner.Result result = ExternalProcessRunner.run(
                "DSP 人声分离", command, inputMedia, DSP_TIMEOUT);

        if (result.timedOut()) {
            throw new ApiException("DSP_SEPARATION_TIMEOUT",
                    "DSP 人声分离处理超时（" + DSP_TIMEOUT.toSeconds() + "秒）");
        }
        if (result.cancelled()) {
            throw new ApiException("DSP_SEPARATION_CANCELLED", "DSP 人声分离已取消");
        }
        if (result.exitCode() != 0 || !Files.exists(outputAudio) || Files.size(outputAudio) == 0) {
            throw new ApiException("DSP_SEPARATION_FAILED",
                    "DSP 人声分离失败 (exit=" + result.exitCode() + "): " + result.diagnostic());
        }
        return outputAudio;
    }

    public String getFfmpegPath() {
        return ffmpegPath;
    }
}

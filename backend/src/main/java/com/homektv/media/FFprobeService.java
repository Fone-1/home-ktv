package com.homektv.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.homektv.config.AppProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 通过 ProcessBuilder 调用 ffprobe 探测媒体文件（P0.7）。
 * 解析音轨数、字幕流、时长、分辨率，供曲库扫描（P1.1）判定类型使用。
 *
 * Probes media files by invoking ffprobe through ProcessBuilder (P0.7).
 * Extracts audio tracks, subtitle streams, duration, and resolution for media
 * classification during library scanning (P1.1).
 */
@Service
public class FFprobeService {

    private static final Logger log = LoggerFactory.getLogger(FFprobeService.class);

    /** 单文件探测超时；网络盘/大文件也应在该时间内返回。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    private final String ffprobePath;
    private final ObjectMapper mapper = new ObjectMapper();

    public FFprobeService(AppProperties props) {
        this.ffprobePath = props.getFfprobePath();
    }

    /**
     * 探测媒体文件。
     *
     * @throws MediaProbeException 探测失败（进程异常、超时、非媒体文件等）
     */
    public MediaProbe probe(Path file) {
        List<String> command = List.of(
                ffprobePath,
                "-v", "error",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                file.toString()
        );

        ExternalProcessRunner.Result result;
        try {
            // stdout/stderr 分离：stdout 必须是干净 JSON，不能被 ffprobe 的错误输出污染
            result = ExternalProcessRunner.runSplit("ffprobe 探测", command, file, PROBE_TIMEOUT);
        } catch (IOException e) {
            throw new MediaProbeException("无法启动 ffprobe（请确认已安装并在 PATH 中）：" + ffprobePath, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MediaProbeException("ffprobe 探测被中断：" + file, e);
        }

        if (result.timedOut()) {
            throw new MediaProbeException("ffprobe 探测超时（" + PROBE_TIMEOUT.toSeconds() + "秒）：" + file);
        }
        if (result.cancelled()) {
            throw new MediaProbeException("ffprobe 探测已取消：" + file);
        }
        if (result.exitCode() != 0) {
            throw new MediaProbeException("ffprobe 探测失败（exit=" + result.exitCode() + "）："
                    + result.diagnostic());
        }
        return parse(result.stdout());
    }

    MediaProbe parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);

            long durationMs = 0;
            JsonNode format = root.path("format");
            if (format.hasNonNull("duration")) {
                durationMs = Math.round(format.get("duration").asDouble() * 1000);
            }

            int audioTracks = 0;
            int subtitleTracks = 0;
            boolean hasVideo = false;
            String resolution = null;
            java.util.List<AudioStreamInfo> audioStreams = new java.util.ArrayList<>();
            String videoCodec = null;
            String audioCodec = null;
            String title = null, artist = null, language = null;
            JsonNode formatTags = format.path("tags");
            title = textOrNull(formatTags, "title");
            artist = textOrNull(formatTags, "artist");
            if (artist == null) artist = textOrNull(formatTags, "album_artist");
            language = textOrNull(formatTags, "language");

            for (JsonNode stream : root.path("streams")) {
                String type = stream.path("codec_type").asText("");
                switch (type) {
                    case "audio" -> {
                        if (audioCodec == null && stream.hasNonNull("codec_name")) {
                            audioCodec = stream.get("codec_name").asText();
                        }
                        JsonNode disp = stream.path("disposition");
                        JsonNode tags = stream.path("tags");
                        // 音轨标题/语言标签大小写不敏感，取时统一去空白
                        String streamTitle = textOrNull(tags, "title");
                        // MP4 stores FFmpeg's stream title metadata as `name`.
                        if (streamTitle == null) {
                            streamTitle = textOrNull(tags, "name");
                        }
                        String streamLanguage = textOrNull(tags, "language");
                        int channels = stream.path("channels").asInt(0);
                        boolean karaoke = disp.path("karaoke").asInt(0) == 1;
                        boolean isDefault = disp.path("default").asInt(0) == 1;
                        audioStreams.add(new AudioStreamInfo(
                                audioTracks, streamTitle, streamLanguage, channels, karaoke, isDefault));
                        audioTracks++;
                    }
                    case "subtitle" -> subtitleTracks++;
                    case "video" -> {
                        // 排除封面图等附着的 MJPEG 图片流：有帧率或非附件的才算真视频
                        boolean isCover = stream.path("disposition").path("attached_pic").asInt(0) == 1;
                        if (!isCover) {
                            if (videoCodec == null && stream.hasNonNull("codec_name")) {
                                videoCodec = stream.get("codec_name").asText();
                            }
                            hasVideo = true;
                            int w = stream.path("width").asInt(0);
                            int h = stream.path("height").asInt(0);
                            if (w > 0 && h > 0) {
                                resolution = w + "x" + h;
                            }
                        }
                    }
                    default -> { /* data / attachment 忽略 */ }
                }
            }

            return new MediaProbe(durationMs, audioTracks, subtitleTracks, hasVideo, resolution,
                    java.util.List.copyOf(audioStreams), videoCodec, audioCodec, title, artist, language);
        } catch (IOException e) {
            throw new MediaProbeException("解析 ffprobe JSON 失败", e);
        }
    }

    /**
     * 取 JSON 文本字段，缺失/空白返回 null。
     *
     * Read a JSON text field and return null when it is missing or blank.
     */
    private static String textOrNull(JsonNode node, String field) {
        if (!node.hasNonNull(field)) return null;
        String v = node.get(field).asText().trim();
        return v.isEmpty() ? null : v;
    }
}

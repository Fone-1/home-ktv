package com.homektv.dualtrack;

import com.homektv.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 远程深度学习 AI 人声分离引擎客户端。
 * 支持向远程 Demucs / UVR5 / Spleeter HTTP 服务推送待分离音频，并接收提取的高品质伴奏音频。
 */
@Component
public class RemoteAiVocalSeparationEngine implements VocalSeparationEngine {

    private static final Logger log = LoggerFactory.getLogger(RemoteAiVocalSeparationEngine.class);

    private final String ffmpegPath;

    public RemoteAiVocalSeparationEngine(@Value("${app.transcode.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    @Override
    public VocalSeparationMode getMode() {
        return VocalSeparationMode.REMOTE_AI;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Path separateAccompaniment(Path inputMedia, Path outputAudio, String bitrate) throws Exception {
        throw new UnsupportedOperationException("请调用携带远程 URL 与 Token 的重载方法 separateAccompanimentWithConfig");
    }

    /**
     * 调用远程 AI 分离服务提取伴奏。
     *
     * @param inputMedia  原始媒体文件
     * @param outputAudio 输出伴奏音频文件
     * @param bitrate     音频码率
     * @param remoteUrl   远程 AI 服务地址 (如 http://ai-service:8000/api/separate)
     * @param token       访问鉴权 Token (可选)
     * @return 输出音频文件路径
     */
    public Path separateAccompanimentWithConfig(Path inputMedia, Path outputAudio, String bitrate,
                                                String remoteUrl, String token) throws Exception {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new ApiException("AI_SERVICE_UNCONFIGURED", "未配置远程 AI 人声分离服务地址，请在管理后台完成配置");
        }
        if (!Files.isReadable(inputMedia)) {
            throw new ApiException("FILE_NOT_FOUND", "输入媒体文件不可读: " + inputMedia);
        }

        // 1. 抽取音频至临时 WAV
        Path tempWav = Files.createTempFile("ai-vocal-input-", ".wav");
        try {
            extractAudioToWav(inputMedia, tempWav);

            // 2. 将临时 WAV 推送给远程 AI 服务
            log.info("向远程 AI 服务提交伴奏分离任务: url={}, file={}", remoteUrl, inputMedia.getFileName());
            postWavToRemoteAi(tempWav, outputAudio, remoteUrl, token);

            if (!Files.exists(outputAudio) || Files.size(outputAudio) == 0) {
                throw new ApiException("AI_SERVICE_EMPTY_RESULT", "远程 AI 分离服务未返回有效的伴奏音频数据");
            }
            return outputAudio;
        } finally {
            try { Files.deleteIfExists(tempWav); } catch (Exception ignored) {}
        }
    }

    private void extractAudioToWav(Path inputMedia, Path targetWav) throws Exception {
        List<String> command = List.of(
                ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                "-i", inputMedia.toAbsolutePath().toString(),
                "-vn",
                "-ar", "44100",
                "-ac", "2",
                targetWav.toAbsolutePath().toString()
        );
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0 || !Files.exists(targetWav) || Files.size(targetWav) == 0) {
            throw new ApiException("EXTRACT_AUDIO_FAILED", "抽取原始音频流失败，无法提交 AI 分离");
        }
    }

    private void postWavToRemoteAi(Path wavFile, Path outputAudio, String remoteUrl, String token) throws Exception {
        String boundary = "----DualTrackBoundary" + UUID.randomUUID().toString().replace("-", "");
        URL url = URI.create(remoteUrl.trim()).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000); // 15s 连接超时
        conn.setReadTimeout(180000);   // 3分钟读取超时（神经网络推理可能需要一定时间）
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setUseCaches(false);

        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (token != null && !token.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
        }

        try (OutputStream out = conn.getOutputStream()) {
            String header = "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"audio\"; filename=\"input.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n";
            out.write(header.getBytes());
            Files.copy(wavFile, out);
            out.write("\r\n".getBytes());
            String footer = "--" + boundary + "--\r\n";
            out.write(footer.getBytes());
            out.flush();
        }

        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) {
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(outputAudio)) {
                in.transferTo(out);
            }
        } else {
            String errorMsg = "";
            try (InputStream err = conn.getErrorStream()) {
                if (err != null) {
                    errorMsg = new String(err.readAllBytes());
                }
            } catch (Exception ignored) {}
            throw new ApiException("AI_SERVICE_HTTP_ERROR", "远程 AI 服务响应异常 (HTTP " + code + "): " + errorMsg.trim());
        }
    }
}

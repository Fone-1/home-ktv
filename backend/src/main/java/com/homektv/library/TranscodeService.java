package com.homektv.library;

import com.homektv.domain.SongFile;
import com.homektv.media.ExternalProcessRunner;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.ApiException;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Creates a TV-compatible H.264/AAC derivative without touching the source file. */
@Service
public class TranscodeService {

    /** TV 兼容副本转码的硬超时，避免卡死进程占用后台线程。 */
    static final java.time.Duration TRANSCODE_TIMEOUT = java.time.Duration.ofMinutes(120);

    private final SongFileRepository files;
    private final String ffmpegPath;

    public TranscodeService(SongFileRepository files,
                            @org.springframework.beans.factory.annotation.Value("${app.transcode.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.files = files;
        this.ffmpegPath = ffmpegPath;
    }

    public Result transcodeSong(Long songId) {
        List<SongFile> sources = files.findBySongIdOrderByPriorityDesc(songId);
        SongFile source = sources.stream().findFirst()
                .orElseThrow(() -> new ApiException("FILE_NOT_FOUND", "歌曲没有可转码文件"));
        Path input = Path.of(source.getFilePath());
        if (!Files.isReadable(input)) throw new ApiException("FILE_NOT_FOUND", "源文件不可读：" + input);
        Path output = uniqueOutput(input, "mkv");
        SongFile existing = files.findByFilePath(output.toString()).orElse(null);
        try {
            if (existing != null && Files.isReadable(output) && Files.size(output) > 0) {
                return new Result(existing.getId(), output.toString(), Files.size(output));
            }
        } catch (java.io.IOException e) {
            throw new ApiException("TRANSCODE_FAILED", e.getMessage());
        }
        try {
            java.util.List<String> command = java.util.List.of(
                    ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                    "-i", input.toString(), "-map", "0:v:0?", "-map", "0:a?",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-profile:v", "high",
                    "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-c:s", "copy", output.toString());
            ExternalProcessRunner.Result result = ExternalProcessRunner.run(
                    "TV 兼容转码", command, input, TRANSCODE_TIMEOUT);
            if (result.timedOut()) {
                throw new ApiException("TRANSCODE_FAILED",
                        "转码超时（" + TRANSCODE_TIMEOUT.toMinutes() + "分钟），已终止 FFmpeg 进程");
            }
            if (result.cancelled()) {
                throw new ApiException("TRANSCODE_INTERRUPTED", "转码已取消");
            }
            if (result.exitCode() != 0 || !Files.isReadable(output)) {
                throw new ApiException("TRANSCODE_FAILED", result.diagnostic());
            }
            SongFile derivative = existing != null ? existing : new SongFile();
            derivative.setSongId(source.getSongId());
            derivative.setFilePath(output.toString());
            derivative.setFormat("matroska");
            derivative.setAudioTracks(source.getAudioTracks());
            derivative.setVocalTrackIndex(source.getVocalTrackIndex());
            derivative.setVocalConfidence(source.getVocalConfidence());
            derivative.setResolution(source.getResolution());
            derivative.setFileSize(Files.size(output));
            derivative.setFileMtime(java.time.OffsetDateTime.now());
            derivative.setPriority(source.getPriority() + 100);
            derivative.setValid(true);
            derivative = files.save(derivative);
            return new Result(derivative.getId(), output.toString(), Files.size(output));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("TRANSCODE_INTERRUPTED", "转码被中断");
        } catch (java.io.IOException e) {
            throw new ApiException("TRANSCODE_FAILED", e.getMessage());
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Path uniqueOutput(Path input, String extension) {
        Path desired = input.resolveSibling(stripExtension(input.getFileName().toString()) + "." + extension);
        if (!desired.equals(input) && !Files.exists(desired)) return desired;
        String baseName = stripExtension(input.getFileName().toString());
        for (int index = 2; index < 10000; index++) {
            Path candidate = input.resolveSibling(baseName + "-" + index + "." + extension);
            if (!Files.exists(candidate)) return candidate;
        }
        throw new ApiException("TARGET_NAME_EXHAUSTED", "无法为输出文件分配唯一文件名：" + desired);
    }

    public record Result(Long sourceFileId, String outputPath, long outputBytes) {}
}

package com.homektv.dualtrack;

import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.library.MediaClassifier;
import com.homektv.library.SettingService;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import com.homektv.web.dto.BatchDualTrackConvertRequest;
import com.homektv.web.dto.DualTrackConvertRequest;
import com.homektv.web.dto.DualTrackConvertResultDto;
import com.homektv.web.dto.DualTrackProgressDto;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单轨转双轨伴奏核心业务服务。
 * 负责单曲/批量伴奏分离重构、原子替换、状态持久化、进度追踪及 WebSocket 实时通知。
 */
@Service
public class DualTrackConvertService {

    private static final Logger log = LoggerFactory.getLogger(DualTrackConvertService.class);

    private final SongRepository songRepo;
    private final SongFileRepository fileRepo;
    private final SettingService settingService;
    private final FFprobeService ffprobeService;
    private final DspVocalSeparationEngine dspEngine;
    private final RemoteAiVocalSeparationEngine remoteAiEngine;
    private final DualTrackRemuxer remuxer;
    private final WsBroadcaster wsBroadcaster;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Set<Long> activeSongIds = ConcurrentHashMap.newKeySet();

    // 进度追踪状态
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Long currentSongId = 0L;
    private volatile String currentTitle = "";
    private final AtomicInteger currentProgress = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger pendingCount = new AtomicInteger(0);
    private volatile String lastMessage = "空闲中";

    public DualTrackConvertService(SongRepository songRepo,
                                   SongFileRepository fileRepo,
                                   SettingService settingService,
                                   FFprobeService ffprobeService,
                                   DspVocalSeparationEngine dspEngine,
                                   RemoteAiVocalSeparationEngine remoteAiEngine,
                                   DualTrackRemuxer remuxer,
                                   WsBroadcaster wsBroadcaster) {
        this.songRepo = songRepo;
        this.fileRepo = fileRepo;
        this.settingService = settingService;
        this.ffprobeService = ffprobeService;
        this.dspEngine = dspEngine;
        this.remoteAiEngine = remoteAiEngine;
        this.remuxer = remuxer;
        this.wsBroadcaster = wsBroadcaster;
    }

    /**
     * 异步提交单曲转双轨伴奏任务。
     */
    public DualTrackConvertResultDto submitSingleConversion(Long songId, DualTrackConvertRequest request) {
        Song song = songRepo.findById(songId)
                .orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在: " + songId));

        if (!activeSongIds.add(songId)) {
            throw new ApiException("CONVERSION_IN_PROGRESS", "该歌曲正在转换双轨伴奏中，请稍候");
        }

        String mode = (request != null && request.mode() != null) ? request.mode() : null;
        Boolean backup = (request != null) ? request.backupOriginal() : null;
        String format = (request != null && request.outputFormat() != null && !request.outputFormat().isBlank())
                ? request.outputFormat() : null;

        executor.submit(() -> {
            try {
                updateProgress(songId, song.getTitle(), 10, 0, 1, 1, true, "正在执行人声伴奏分离...");
                doConvert(songId, mode, backup, format);
                updateProgress(songId, song.getTitle(), 100, 1, 0, 1, false, "双轨转换完成");
            } catch (Exception e) {
                log.error("单曲转双轨失败: songId={}, error={}", songId, e.getMessage(), e);
                updateProgress(songId, song.getTitle(), 0, 1, 0, 1, false, "转换失败: " + e.getMessage());
            } finally {
                activeSongIds.remove(songId);
            }
        });

        return new DualTrackConvertResultDto(songId, "PROCESSING", "已提交双轨转换任务", "");
    }

    /**
     * 提交批量转换任务。
     */
    public Map<String, Object> submitBatchConversion(BatchDualTrackConvertRequest request) {
        List<Long> songIds = (request != null && request.songIds() != null) ? request.songIds() : List.of();
        if (songIds.isEmpty()) {
            throw new ApiException("PARAM_INVALID", "待转换歌曲列表为空");
        }

        SettingService.DualTrackPolicy policy = settingService.dualTrackPolicy();
        int concurrency = (request.concurrency() != null && request.concurrency() >= 1 && request.concurrency() <= 3)
                ? request.concurrency() : policy.concurrency();
        String mode = (request.mode() != null && !request.mode().isBlank()) ? request.mode() : policy.engine();

        totalCount.set(songIds.size());
        processedCount.set(0);
        pendingCount.set(songIds.size());
        running.set(true);
        lastMessage = "批量任务排队中";

        executor.submit(() -> {
            Semaphore semaphore = new Semaphore(concurrency);
            List<Future<?>> futures = new ArrayList<>();

            for (Long sId : songIds) {
                futures.add(executor.submit(() -> {
                    try {
                        semaphore.acquire();
                        if (activeSongIds.add(sId)) {
                            try {
                                Song song = songRepo.findById(sId).orElse(null);
                                String title = song != null ? song.getTitle() : ("歌曲#" + sId);
                                currentSongId = sId;
                                currentTitle = title;
                                lastMessage = "正在处理: " + title;
                                broadcastCurrentProgress();

                                doConvert(sId, mode, policy.backupOriginal(), null);
                            } finally {
                                activeSongIds.remove(sId);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("批量处理单曲失败: songId={}, msg={}", sId, e.getMessage());
                    } finally {
                        semaphore.release();
                        processedCount.incrementAndGet();
                        pendingCount.decrementAndGet();
                        int pct = Math.min(100, Math.round((float) processedCount.get() / totalCount.get() * 100));
                        currentProgress.set(pct);
                        broadcastCurrentProgress();
                    }
                }));
            }

            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
            running.set(false);
            lastMessage = "批量转换完成，已处理 " + processedCount.get() + " 首歌曲";
            broadcastCurrentProgress();
        });

        return Map.of("total", songIds.size(), "queued", songIds.size(), "message", "批量转换任务已在后台排队");
    }

    /**
     * 执行单曲转换核心逻辑（同步阻塞）。
     */
    public DualTrackConvertResultDto doConvert(Long songId, String requestedMode, Boolean requestedBackup, String outputFormat) throws Exception {
        Song song = songRepo.findById(songId)
                .orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在: " + songId));

        List<SongFile> files = fileRepo.findBySongIdAndValidTrueOrderByPriorityDesc(songId);
        if (files.isEmpty()) {
            throw new ApiException("FILE_NOT_FOUND", "歌曲没有有效媒体文件");
        }
        SongFile songFile = files.get(0);
        Path inputPath = Path.of(songFile.getFilePath());
        if (!Files.isReadable(inputPath)) {
            throw new ApiException("FILE_NOT_FOUND", "媒体文件不可读: " + inputPath);
        }

        // 若已经是双音轨伴奏，则直接返回
        if (songFile.getAudioTracks() >= 2 && song.isHasVocalTrack()) {
            return new DualTrackConvertResultDto(songId, "SUCCESS", "歌曲已具备双音轨伴奏", songFile.getFilePath());
        }

        SettingService.DualTrackPolicy policy = settingService.dualTrackPolicy();
        String effectiveMode = (requestedMode != null && !requestedMode.isBlank()) ? requestedMode.toUpperCase() : policy.engine();
        boolean backupOriginal = (requestedBackup != null) ? requestedBackup : policy.backupOriginal();
        String effectiveFormat;
        if (outputFormat != null && !outputFormat.isBlank()) {
            effectiveFormat = outputFormat.equalsIgnoreCase("mkv") ? "mkv" : "mp4";
        } else {
            String srcName = inputPath.getFileName().toString().toLowerCase();
            effectiveFormat = srcName.endsWith(".mkv") ? "mkv" : "mp4";
        }
        String bitrate = policy.audioBitrate();

        // 探测原始音频声道数
        MediaProbe srcProbe = ffprobeService.probe(inputPath);
        if (srcProbe.audioStreams().isEmpty()) {
            throw new ApiException("NO_AUDIO_STREAM", "源文件未检测到音频流");
        }
        if (srcProbe.audioStreams().get(0).channels() <= 1 && "DSP".equalsIgnoreCase(effectiveMode)) {
            throw new ApiException("MONO_AUDIO_UNSUPPORTED", "单声道音频无法通过 DSP 声学反相消除人声，请使用 AI 分离模式");
        }

        // 构建临时输出路径
        String baseName = stripExtension(inputPath.getFileName().toString());
        Path tempOutput = inputPath.resolveSibling(baseName + ".dual.tmp." + effectiveFormat);
        Files.deleteIfExists(tempOutput);

        try {
            if ("REMOTE_AI".equalsIgnoreCase(effectiveMode) || "LOCAL_AI".equalsIgnoreCase(effectiveMode) || "AI".equalsIgnoreCase(effectiveMode)) {
                // 方案B：UVR-MDX-Net 深度学习 AI 分离模式
                Path tempAccompAudio = Files.createTempFile("accomp-", ".wav");
                try {
                    remoteAiEngine.separateAccompanimentWithConfig(
                            inputPath, tempAccompAudio, bitrate, policy.remoteUrl(), policy.remoteToken());
                    remuxer.remuxWithAccompanimentAudio(inputPath, tempAccompAudio, tempOutput, bitrate);
                } catch (Exception e) {
                    log.warn("AI 伴奏分离异常，尝试自动平滑降级至 DSP 声学消音: songId={}, error={}", songId, e.getMessage());
                    remuxer.remuxDsp(inputPath, tempOutput, bitrate);
                } finally {
                    try { Files.deleteIfExists(tempAccompAudio); } catch (Exception ignored) {}
                }
            } else {
                // 极速 DSP 模式 (默认)
                remuxer.remuxDsp(inputPath, tempOutput, bitrate);
            }

            // 验证重封装输出有效性
            MediaProbe outProbe = ffprobeService.probe(tempOutput);
            if (!outProbe.hasVideo()) {
                Files.deleteIfExists(tempOutput);
                throw new ApiException("CONVERT_PROBE_FAILED", "转换后文件缺少有效视频流");
            }
            if (outProbe.audioTracks() < 2) {
                Files.deleteIfExists(tempOutput);
                throw new ApiException("CONVERT_PROBE_FAILED", "转换后音轨数不足 2 轨");
            }

            // 备份原单轨文件（若开启）
            Path backupPath = inputPath.resolveSibling(inputPath.getFileName().toString() + ".original.bak");
            if (backupOriginal) {
                if (!Files.exists(backupPath)) {
                    Files.copy(inputPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                    log.info("已备份原始单轨文件: {}", backupPath.getFileName());
                }
            }

            // 确定最终替换文件路径
            Path finalOutputPath;
            if (inputPath.toString().toLowerCase().endsWith("." + effectiveFormat)) {
                finalOutputPath = inputPath;
            } else {
                finalOutputPath = inputPath.resolveSibling(baseName + "." + effectiveFormat);
            }

            // 原子替换
            try {
                Files.move(tempOutput, finalOutputPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tempOutput, finalOutputPath, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!finalOutputPath.equals(inputPath) && !backupOriginal) {
                try { Files.deleteIfExists(inputPath); } catch (Exception ignored) {}
            }

            // 更新数据库
            updateSongAndFileMetadata(song, songFile, finalOutputPath, effectiveFormat, outProbe);

            // 广播事件
            wsBroadcaster.broadcast(WsEvent.of("song_updated", Map.of(
                    "songId", song.getId(),
                    "hasVocalTrack", true,
                    "mediaType", MediaClassifier.KTV_VIDEO
            )));
            wsBroadcaster.broadcast(WsEvent.of("dual_track_completed", Map.of(
                    "songId", song.getId(),
                    "title", song.getTitle(),
                    "status", "SUCCESS"
            )));

            return new DualTrackConvertResultDto(songId, "SUCCESS", "已成功转换为双轨伴奏版本", finalOutputPath.toString());
        } finally {
            try { Files.deleteIfExists(tempOutput); } catch (Exception ignored) {}
        }
    }

    @Transactional
    public void updateSongAndFileMetadata(Song song, SongFile songFile, Path finalPath, String format, MediaProbe probe) throws Exception {
        songFile.setFilePath(finalPath.toAbsolutePath().toString());
        songFile.setAudioTracks(probe.audioTracks());
        songFile.setVocalTrackIndex(1); // Track 1 为伴奏轨
        songFile.setVocalConfidence("HIGH");
        songFile.setFormat("mkv".equalsIgnoreCase(format) ? "matroska" : format.toLowerCase());
        songFile.setFileSize(Files.size(finalPath));
        songFile.setFileMtime(OffsetDateTime.now());
        songFile.setPriority(100);
        songFile.setValid(true);
        fileRepo.save(songFile);

        // 确保本文件为最高优先级文件源
        List<SongFile> allFiles = fileRepo.findBySongIdOrderByPriorityDesc(song.getId());
        for (SongFile other : allFiles) {
            if (!other.getId().equals(songFile.getId()) && other.getPriority() >= 100) {
                other.setPriority(50);
                fileRepo.save(other);
            }
        }

        song.setMediaType(MediaClassifier.KTV_VIDEO);
        song.setHasVocalTrack(true);
        songRepo.save(song);
    }

    /**
     * 回滚至原始单轨文件（若存在备份）。
     */
    public DualTrackConvertResultDto rollback(Long songId) throws Exception {
        Song song = songRepo.findById(songId)
                .orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在: " + songId));
        List<SongFile> files = fileRepo.findBySongIdAndValidTrueOrderByPriorityDesc(songId);
        if (files.isEmpty()) {
            throw new ApiException("FILE_NOT_FOUND", "未找到歌曲有效文件");
        }
        SongFile songFile = files.get(0);
        Path currentPath = Path.of(songFile.getFilePath());

        // 寻找同目录下匹配的 .original.bak
        Path directBak = currentPath.resolveSibling(currentPath.getFileName().toString() + ".original.bak");
        Path baseNameBak = currentPath.resolveSibling(stripExtension(currentPath.getFileName().toString()) + ".mp4.original.bak");
        Path bakFile = Files.exists(directBak) ? directBak : Files.exists(baseNameBak) ? baseNameBak : null;

        if (bakFile == null || !Files.isReadable(bakFile)) {
            throw new ApiException("BACKUP_NOT_FOUND", "未找到原始单轨备份文件 (.original.bak)");
        }

        // 还原备份文件
        String origFileName = bakFile.getFileName().toString().replace(".original.bak", "");
        Path restoredPath = bakFile.resolveSibling(origFileName);
        Files.move(bakFile, restoredPath, StandardCopyOption.REPLACE_EXISTING);
        if (!restoredPath.equals(currentPath)) {
            try { Files.deleteIfExists(currentPath); } catch (Exception ignored) {}
        }

        MediaProbe probe = ffprobeService.probe(restoredPath);
        songFile.setFilePath(restoredPath.toAbsolutePath().toString());
        songFile.setAudioTracks(probe.audioTracks());
        songFile.setVocalTrackIndex(null);
        songFile.setVocalConfidence("NONE");
        songFile.setFileSize(Files.size(restoredPath));
        songFile.setFileMtime(OffsetDateTime.now());
        fileRepo.save(songFile);

        song.setMediaType(MediaClassifier.classify(probe));
        song.setHasVocalTrack(MediaClassifier.hasVocalTrack(probe));
        songRepo.save(song);

        wsBroadcaster.broadcast(WsEvent.of("song_updated", Map.of(
                "songId", song.getId(),
                "hasVocalTrack", song.isHasVocalTrack(),
                "mediaType", song.getMediaType()
        )));

        return new DualTrackConvertResultDto(songId, "RESTORED", "已成功还原原始单轨文件", restoredPath.toString());
    }

    /**
     * 便捷异步转换入口（供 MV 下载完成自动联动）。
     */
    public void convertSongAsync(Long songId, String mode) {
        submitSingleConversion(songId, new DualTrackConvertRequest(mode, false, null));
    }

    public DualTrackProgressDto getProgress() {
        return new DualTrackProgressDto(
                currentSongId,
                currentTitle,
                currentProgress.get(),
                pendingCount.get(),
                processedCount.get(),
                totalCount.get(),
                running.get(),
                lastMessage
        );
    }

    private void updateProgress(Long songId, String title, int pct, int processed, int pending, int total, boolean isRunning, String msg) {
        currentSongId = songId;
        currentTitle = title;
        currentProgress.set(pct);
        processedCount.set(processed);
        pendingCount.set(pending);
        totalCount.set(total);
        running.set(isRunning);
        lastMessage = msg;
        broadcastCurrentProgress();
    }

    private void broadcastCurrentProgress() {
        wsBroadcaster.broadcast(WsEvent.of("dual_track_progress", Map.of(
                "currentSongId", currentSongId != null ? currentSongId : 0L,
                "title", currentTitle != null ? currentTitle : "",
                "progress", currentProgress.get(),
                "pendingCount", pendingCount.get(),
                "processedCount", processedCount.get(),
                "totalCount", totalCount.get(),
                "running", running.get(),
                "lastMessage", lastMessage != null ? lastMessage : ""
        )));
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}

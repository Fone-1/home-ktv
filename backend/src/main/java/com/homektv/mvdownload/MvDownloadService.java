package com.homektv.mvdownload;

import com.homektv.config.AppProperties;
import com.homektv.config.SslContextHelper;
import com.homektv.dualtrack.DualTrackConvertService;
import com.homektv.domain.MediaImportRecord;
import com.homektv.domain.MvDownloadTask;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.library.MediaImportService;
import com.homektv.library.LibraryScanService;
import com.homektv.library.SettingService;
import com.homektv.queue.QueueService;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.repo.MvDownloadTaskRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import com.homektv.web.dto.MvDownloadSubmitRequest;
import com.homektv.web.dto.MvDownloadTaskDto;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * MV 在线下载与自动入库调度服务：
 * 1. 负责多平台 (网易云/B站) MV 检索与直链流解析
 * 2. 异步多线程下载、分块拉取、下载速度计算与断点续传
 * 3. DASH 独立音频/视频流调用 FFmpeg 极速无损封装
 * 4. 下载完成自动交付 LibraryScanService 入库并可选择直接加入待播队列
 */
@Service
public class MvDownloadService {

    private static final Logger log = LoggerFactory.getLogger(MvDownloadService.class);
    private static final int BUFFER_SIZE = 64 * 1024; // 64KB 缓冲区

    private final Map<MvProvider, MvSearchProvider> providerMap = new EnumMap<>(MvProvider.class);
    private final MvDownloadTaskRepository taskRepo;
    private final SongFileRepository songFileRepo;
    private final AppProperties props;
    private final LibraryScanService scanService;
    private final MediaImportService mediaImportService;
    private final QueueService queueService;
    private final WsBroadcaster wsBroadcaster;
    private final String ffmpegPath;
    private final DualTrackConvertService dualTrackConvertService;
    private final SettingService settingService;
    private final MediaImportRecordRepository importRecordRepo;
    private final SongRepository songRepo;

    private final HttpClient httpClient;
    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "mv-download-worker");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<Long, Boolean> activeTaskCancelFlags = new ConcurrentHashMap<>();

    @Autowired
    public MvDownloadService(List<MvSearchProvider> providers,
                             MvDownloadTaskRepository taskRepo,
                             SongFileRepository songFileRepo,
                             AppProperties props,
                             LibraryScanService scanService,
                             MediaImportService mediaImportService,
                             QueueService queueService,
                             WsBroadcaster wsBroadcaster,
                             @Nullable DualTrackConvertService dualTrackConvertService,
                             @Nullable SettingService settingService,
                             @Nullable MediaImportRecordRepository importRecordRepo,
                             @Nullable SongRepository songRepo,
                             @Value("${app.transcode.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        providers.forEach(p -> providerMap.put(p.provider(), p));
        this.taskRepo = taskRepo;
        this.songFileRepo = songFileRepo;
        this.props = props;
        this.scanService = scanService;
        this.mediaImportService = mediaImportService;
        this.queueService = queueService;
        this.wsBroadcaster = wsBroadcaster;
        this.dualTrackConvertService = dualTrackConvertService;
        this.settingService = settingService;
        this.importRecordRepo = importRecordRepo;
        this.songRepo = songRepo;
        this.ffmpegPath = ffmpegPath;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .sslContext(SslContextHelper.trustAllSslContext())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public MvDownloadService(List<MvSearchProvider> providers,
                             MvDownloadTaskRepository taskRepo,
                             SongFileRepository songFileRepo,
                             AppProperties props,
                             LibraryScanService scanService,
                             MediaImportService mediaImportService,
                             QueueService queueService,
                             WsBroadcaster wsBroadcaster,
                             DualTrackConvertService dualTrackConvertService,
                             SettingService settingService,
                             String ffmpegPath) {
        this(providers, taskRepo, songFileRepo, props, scanService, mediaImportService, queueService, wsBroadcaster,
                dualTrackConvertService, settingService, null, null, ffmpegPath);
    }

    @PreDestroy
    public void shutdown() {
        downloadExecutor.shutdownNow();
    }

    /**
     * 在线搜索 MV。若未指定 provider 则在网易云和 B 站并行搜索。
     */
    public List<MvSearchItem> search(String keyword, MvProvider provider, int limit) {
        String query = keyword == null ? "" : keyword.trim();
        if (query.length() < 2) {
            throw new ApiException("MV_SEARCH_QUERY_TOO_SHORT", "请输入至少 2 个字符的搜索词");
        }

        if (provider != null) {
            MvSearchProvider p = providerMap.get(provider);
            if (p == null) throw new ApiException("MV_PROVIDER_NOT_SUPPORTED", "不支持的搜索源：" + provider);
            return p.search(query, limit, Duration.ofSeconds(10));
        }

        // 多平台并行搜索聚合
        List<MvSearchItem> combined = new ArrayList<>();
        List<CompletableFuture<List<MvSearchItem>>> futures = providerMap.values().stream()
                .map(p -> CompletableFuture.supplyAsync(() -> p.search(query, limit, Duration.ofSeconds(10)), downloadExecutor))
                .toList();

        for (CompletableFuture<List<MvSearchItem>> f : futures) {
            try {
                combined.addAll(f.get(10, TimeUnit.SECONDS));
            } catch (Exception e) {
                log.debug("并行搜索部分提供商超时或失败: {}", e.getMessage());
            }
        }
        return combined;
    }

    /**
     * 提交下载任务
     */
    public MvDownloadTaskDto submitDownload(MvDownloadSubmitRequest request) {
        MvProvider provider = MvProvider.parse(request.provider());
        if (request.externalId() == null || request.externalId().isBlank()) {
            throw new ApiException("MV_PARAM_INVALID", "视频外部 ID 不能为空");
        }

        MvDownloadTask task = new MvDownloadTask();
        task.setTitle(request.title() != null && !request.title().isBlank() ? request.title().trim() : "未命名MV");
        task.setArtist(request.artist() != null && !request.artist().isBlank() ? request.artist().trim() : "未知歌手");
        task.setProvider(provider.name());
        task.setExternalId(request.externalId().trim());
        task.setCoverUrl(request.coverUrl());
        task.setResolution(request.resolution() != null ? request.resolution() : "1080p");
        task.setStatus("PENDING");
        task.setProgress(0);
        boolean autoConvert = request.autoConvertDualTrack() != null
                ? request.autoConvertDualTrack()
                : (settingService != null ? settingService.isMvAutoConvertDualTrack() : false);
        task.setAutoConvertDualTrack(autoConvert);
        task = taskRepo.save(task);

        final Long taskId = task.getId();
        final boolean autoEnqueue = request.autoEnqueue() != null
                ? request.autoEnqueue()
                : (settingService != null ? settingService.isMvAutoEnqueue() : true);

        downloadExecutor.submit(() -> executeDownloadTask(taskId, autoEnqueue));
        return MvDownloadTaskDto.from(task);
    }

    /**
     * 获取所有下载任务
     */
    public List<MvDownloadTaskDto> listTasks() {
        return taskRepo.findAllByOrderByCreatedAtDesc().stream().map(MvDownloadTaskDto::from).toList();
    }

    /**
     * 取消下载任务
     */
    public void cancelTask(Long taskId) {
        activeTaskCancelFlags.put(taskId, true);
        taskRepo.findById(taskId).ifPresent(task -> {
            if ("DOWNLOADING".equals(task.getStatus()) || "PENDING".equals(task.getStatus())) {
                task.setStatus("CANCELLED");
                task.setErrorMessage("用户取消下载");
                taskRepo.save(task);
                broadcastProgress(task);
            }
        });
    }

    /**
     * 重试下载任务
     */
    public void retryTask(Long taskId) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setStatus("PENDING");
            task.setProgress(0);
            task.setErrorMessage(null);
            if (settingService != null && settingService.isMvAutoConvertDualTrack()) {
                task.setAutoConvertDualTrack(true);
            }
            taskRepo.save(task);
            broadcastProgress(task);
            downloadExecutor.submit(() -> executeDownloadTask(taskId, false));
        });
    }

    /**
     * 删除任务记录
     */
    public void deleteTask(Long taskId) {
        cancelTask(taskId);
        taskRepo.deleteById(taskId);
    }

    /**
     * 任务实际执行流程
     */
    private void executeDownloadTask(Long taskId, boolean autoEnqueue) {
        MvDownloadTask task = taskRepo.findById(taskId).orElse(null);
        if (task == null || "CANCELLED".equals(task.getStatus())) return;

        activeTaskCancelFlags.remove(taskId);
        task.setStatus("DOWNLOADING");
        taskRepo.save(task);
        broadcastProgress(task);

        // 下载的原始素材存入 sourceLibraryPath (即 /source-music/downloads)
        Path targetDir = Path.of(props.getSourceLibraryPath(), "downloads");
        Path targetFile = null;
        Path tmpFile = null;
        Path videoTmp = null;
        Path audioTmp = null;

        try {
            Files.createDirectories(targetDir);
            String safeTitle = sanitizeFilename(task.getTitle());
            String safeArtist = sanitizeFilename(task.getArtist());
            String baseName = safeArtist + " - " + safeTitle + " - " + task.getId();
            targetFile = targetDir.resolve(baseName + ".mp4");
            task.setTargetFilePath(targetFile.toAbsolutePath().toString());

            MvProvider provider = MvProvider.parse(task.getProvider());
            MvSearchProvider searchProvider = providerMap.get(provider);
            if (searchProvider == null) throw new IllegalStateException("未找到源解析器: " + provider);

            // 1. 解析媒体流地址
            MvStreamInfo streamInfo = searchProvider.resolveStream(task.getExternalId(), task.getResolution(), Duration.ofSeconds(15));

            if (!streamInfo.isDash()) {
                // 单视频流直接拉取 (网易云模式)
                tmpFile = targetDir.resolve(baseName + ".tmp.mp4");
                downloadStreamToFile(streamInfo.videoUrl(), streamInfo.httpHeaders(), tmpFile, task, 0, 90);
                checkCancelled(taskId);
                Files.move(tmpFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            } else {
                // DASH 音视频分流拉取并合流 (B站模式)
                videoTmp = targetDir.resolve(baseName + ".video.tmp");
                audioTmp = targetDir.resolve(baseName + ".audio.tmp");

                // 0% ~ 60%: 视频流下载
                downloadStreamToFile(streamInfo.videoUrl(), streamInfo.httpHeaders(), videoTmp, task, 0, 60);
                checkCancelled(taskId);

                // 60% ~ 85%: 音频流下载
                downloadStreamToFile(streamInfo.audioUrl(), streamInfo.httpHeaders(), audioTmp, task, 60, 85);
                checkCancelled(taskId);

                // 85% ~ 90%: FFmpeg 无损合流封装
                task.setStatus("MERGING");
                task.setProgress(88);
                taskRepo.save(task);
                broadcastProgress(task);

                mergeDashStreams(videoTmp, audioTmp, targetFile);
                try { Files.deleteIfExists(videoTmp); } catch (Exception ignored) {}
                try { Files.deleteIfExists(audioTmp); } catch (Exception ignored) {}
            }

            // 90% ~ 100%: 自动入库管线
            task.setStatus("IMPORTING");
            task.setProgress(95);
            taskRepo.save(task);
            broadcastProgress(task);

            // 触发原始音乐管理扫描管线，进行文件分析、格式判定与自动直拷入库
            try {
                if (mediaImportService != null) {
                    mediaImportService.scanSourceLibrary();
                } else {
                    scanService.ingest(targetFile);
                }
            } catch (Exception se) {
                log.warn("触发曲库扫描异常: {}", se.getMessage());
            }

            // 查询入库生成的歌曲 ID：
            // 1. 优先从 media_import_records 表通过 sourcePath 查询（MediaImportService 扫描直拷入库后写入）
            Long songId = null;
           if (importRecordRepo != null) {
                Optional<MediaImportRecord> record = importRecordRepo.findBySourcePath(targetFile.toAbsolutePath().normalize().toString());
                if (record.isEmpty()) {
                    record = importRecordRepo.findBySourcePath(targetFile.toAbsolutePath().toString());
                }
                if (record.isEmpty()) {
                    record = importRecordRepo.findBySourcePath(targetFile.toString());
                }
                if (record.isPresent() && record.get().getSongId() != null) {
                    songId = record.get().getSongId();
                }
            }
            // 2. 备用从 song_files 表直接根据路径匹配（直接 ingest 原地入库时）
            if (songId == null && songFileRepo != null) {
                Optional<SongFile> ingestedFile = songFileRepo.findByFilePath(targetFile.toAbsolutePath().toString());
                if (ingestedFile.isPresent()) {
                    songId = ingestedFile.get().getSongId();
                }
            }
            // 3. 兜底从曲库最新文件按目标文件名结尾匹配
            if (songId == null && songFileRepo != null) {
                String filename = targetFile.getFileName().toString();
                List<SongFile> matches = songFileRepo.findAll().stream()
                        .filter(sf -> sf.getFilePath() != null && sf.getFilePath().endsWith(filename))
                        .toList();
                if (!matches.isEmpty()) {
                    songId = matches.getFirst().getSongId();
                }
            }

            if (songId != null) {
                task.setSongId(songId);

                // 若开启自动转双轨，检查是否为单音轨视频（非已包含伴唱的双轨视频），若是则在后台触发伴奏分离
                if (task.isAutoConvertDualTrack() && dualTrackConvertService != null) {
                    boolean isSingleTrack = true;
                    if (songRepo != null) {
                        Optional<Song> songOpt = songRepo.findById(songId);
                        if (songOpt.isPresent()) {
                            // 若歌曲已被识别为已有伴唱轨(hasVocalTrack=true)，则为双轨视频，无需重复消音
                            isSingleTrack = !songOpt.get().isHasVocalTrack();
                        }
                    }

                    if (isSingleTrack) {
                        try {
                            String engine = (settingService != null)
                                    ? settingService.dualTrackPolicy().engine()
                                    : "DSP";
                            dualTrackConvertService.convertSongAsync(songId, engine);
                            log.info("MV 下载入库完成，已自动触发单轨转双轨伴奏转换: songId={}, engine={}", songId, engine);
                        } catch (Exception ce) {
                            log.warn("自动触发双轨伴奏转换异常: songId={}, msg={}", songId, ce.getMessage());
                        }
                    } else {
                        log.info("MV 视频已包含伴唱音轨 (hasVocalTrack=true)，跳过自动转双轨: songId={}", songId);
                    }
                }

                // 若用户勾选自动加入点歌队列，则自动点歌
                if (autoEnqueue && queueService != null) {
                    try {
                        queueService.order(songId, 0L, true);
                    } catch (Exception qe) {
                        log.debug("下载完成自动点歌提示: {}", qe.getMessage());
                    }
                }
            }

            task.setStatus("COMPLETED");
            task.setProgress(100);
            task.setSpeedBps(0);
            taskRepo.save(task);
            broadcastProgress(task);
            wsBroadcaster.broadcast(WsEvent.of("mv_download_completed", Map.of(
                    "taskId", task.getId(),
                    "songId", task.getSongId() != null ? task.getSongId() : 0,
                    "title", task.getTitle()
            )));

        } catch (Exception e) {
            log.error("MV 下载任务执行失败 taskId={}: {}", taskId, e.getMessage(), e);
            task.setStatus("FAILED");
            task.setErrorMessage(e.getMessage() != null ? e.getMessage() : "下载或合流失败");
            task.setSpeedBps(0);
            taskRepo.save(task);
            broadcastProgress(task);
            // 清理遗留临时文件
            if (tmpFile != null) try { Files.deleteIfExists(tmpFile); } catch (Exception ignored) {}
            if (videoTmp != null) try { Files.deleteIfExists(videoTmp); } catch (Exception ignored) {}
            if (audioTmp != null) try { Files.deleteIfExists(audioTmp); } catch (Exception ignored) {}
        } finally {
            activeTaskCancelFlags.remove(taskId);
        }
    }

    /**
     * 流式下载到目标文件并实时统计速度与百分比
     */
    private void downloadStreamToFile(String streamUrl, Map<String, String> headers, Path destFile,
                                      MvDownloadTask task, int startPercent, int endPercent) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(streamUrl))
                .timeout(Duration.ofMinutes(10))
                .GET();
        if (headers != null) headers.forEach(builder::header);

        HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("下载请求返回 HTTP " + response.statusCode());
        }

        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(0L);
        if (contentLength > 0) {
            task.setTotalBytes(task.getTotalBytes() + contentLength);
        }

        long downloaded = 0;
        long lastTime = System.currentTimeMillis();
        long bytesSinceLastTime = 0;

        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(destFile)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                checkCancelled(task.getId());
                out.write(buffer, 0, bytesRead);
                downloaded += bytesRead;
                bytesSinceLastTime += bytesRead;

                long now = System.currentTimeMillis();
                long elapsed = now - lastTime;
                if (elapsed >= 1000) { // 每秒刷新进度与瞬时速度
                    long speed = (bytesSinceLastTime * 1000) / elapsed;
                    task.setDownloadedBytes(task.getDownloadedBytes() + bytesSinceLastTime);
                    task.setSpeedBps(speed);
                    if (contentLength > 0) {
                        int subProgress = (int) ((downloaded * (endPercent - startPercent)) / contentLength);
                        task.setProgress(Math.min(endPercent, startPercent + subProgress));
                    }
                    taskRepo.save(task);
                    broadcastProgress(task);

                    lastTime = now;
                    bytesSinceLastTime = 0;
                }
            }
        }
    }

    /**
     * 使用系统 FFmpeg 将独立的视频流和音频流无损合流（pure stream copy，耗时极短）
     */
    private void mergeDashStreams(Path videoFile, Path audioFile, Path outputFile) throws Exception {
        List<String> cmd = List.of(
                ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                "-i", videoFile.toAbsolutePath().toString(),
                "-i", audioFile.toAbsolutePath().toString(),
                "-c:v", "copy",
                "-c:a", "aac",
                "-b:a", "192k",
                "-movflags", "+faststart",
                outputFile.toAbsolutePath().toString()
        );

        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            int code = process.waitFor();
            if (code != 0 || !Files.isReadable(outputFile) || Files.size(outputFile) == 0) {
                throw new IOException("FFmpeg 音视频合流失败 (code=" + code + "): " + output);
            }
        } catch (IOException e) {
            if (e.getMessage() != null && (e.getMessage().contains("Cannot run program") || e.getMessage().contains("系统找不到指定的文件"))) {
                throw new IOException("系统未找到 FFmpeg 工具。Docker 环境下已内置；本地运行请安装 FFmpeg 并配置到系统 PATH 环境变量。");
            }
            throw e;
        }
    }

    private void checkCancelled(Long taskId) {
        if (Boolean.TRUE.equals(activeTaskCancelFlags.get(taskId))) {
            throw new RuntimeException("任务已被用户取消");
        }
    }

    private void broadcastProgress(MvDownloadTask task) {
        wsBroadcaster.broadcast(WsEvent.of("mv_download_progress", Map.of(
                "taskId", task.getId(),
                "status", task.getStatus(),
                "progress", task.getProgress(),
                "speedBps", task.getSpeedBps(),
                "downloadedBytes", task.getDownloadedBytes(),
                "totalBytes", task.getTotalBytes(),
                "title", task.getTitle()
        )));
    }

    private String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "unknown";
        return name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
    }
}

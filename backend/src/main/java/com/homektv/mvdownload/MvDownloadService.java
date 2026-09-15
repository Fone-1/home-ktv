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
import com.homektv.media.ExternalProcessRunner;
import com.homektv.queue.QueueService;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.repo.MvDownloadTaskRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import com.homektv.web.dto.BilibiliPartDto;
import com.homektv.web.dto.MvDownloadSubmitRequest;
import com.homektv.web.dto.MvDownloadTaskDto;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
import java.time.OffsetDateTime;
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
    /** 单个流的自动重试上限（断点续传使重试成本很低）。 */
    private static final int DOWNLOAD_MAX_ATTEMPTS = 3;
    /** 重试退避基数，按次数线性增长。 */
    private static final long RETRY_BACKOFF_MS = 2_000L;
    /** DASH 合流为 stream copy，5 分钟足够。 */
    private static final Duration MERGE_TIMEOUT = Duration.ofMinutes(5);

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
        if (request.durationMs() != null && request.durationMs() > 0) {
            task.setDurationMs(request.durationMs());
        }
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
    /**
     * 获取视频分集/分P列表（针对 B 站等合集资源）
     */
    public List<BilibiliPartDto> getParts(MvProvider provider, String externalId) {
        if (provider == MvProvider.BILIBILI) {
            MvSearchProvider p = providerMap.get(MvProvider.BILIBILI);
            if (p instanceof BilibiliMvProvider biliProvider) {
                return biliProvider.fetchParts(externalId, Duration.ofSeconds(10));
            }
        }
        return List.of();
    }

    public List<MvDownloadTaskDto> listTasks() {
        return listTasks(null, null, 0, 50).getContent().stream().map(MvDownloadTaskDto::from).toList();
    }

    /**
     * 分页查询下载任务。默认只返回活动任务和最近一段历史，避免无限加载全部记录。
     * status 为空时优先活动任务，再按创建时间倒序补齐最近历史。
     */
    public Page<MvDownloadTask> listTasks(String status, OffsetDateTime since, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        PageRequest pageable = PageRequest.of(safePage, safeSize);
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        OffsetDateTime from = since != null ? since : OffsetDateTime.now().minusDays(7);
        if ("ACTIVE".equals(normalized)) {
            return taskRepo.findByStatusInOrderByCreatedAtDesc(ACTIVE_STATUSES, pageable);
        }
        if (!normalized.isBlank() && !"ALL".equals(normalized)) {
            return taskRepo.findByStatusAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(normalized, from, pageable);
        }
        return taskRepo.findRecentOrActive(ACTIVE_STATUSES, from, pageable);
    }

    public Map<String, Object> listTasksPage(String status, OffsetDateTime since, int page, int size) {
        Page<MvDownloadTask> result = listTasks(status, since, page, size);
        return Map.of(
                "content", result.getContent().stream().map(MvDownloadTaskDto::from).toList(),
                "total", result.getTotalElements(),
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalPages", result.getTotalPages(),
                "activeCount", taskRepo.countByStatusIn(ACTIVE_STATUSES)
        );
    }

    private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "DOWNLOADING", "MERGING", "IMPORTING");

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
     * 删除任务记录，同时清理其断点与中间文件。
     */
    public void deleteTask(Long taskId) {
        cancelTask(taskId);
        taskRepo.findById(taskId).ifPresent(this::cleanupTempFiles);
        taskRepo.deleteById(taskId);
    }

    /** 清理该任务的 .part 断点与合流中间文件（目标成品保留）。 */
    private void cleanupTempFiles(MvDownloadTask task) {
        String target = task.getTargetFilePath();
        if (target == null || target.isBlank()) return;
        Path targetFile = Path.of(target);
        String base = targetFile.getFileName().toString();
        if (base.endsWith(".mp4")) base = base.substring(0, base.length() - 4);
        Path dir = targetFile.getParent();
        if (dir == null) return;
        for (String suffix : TEMP_FILE_SUFFIXES) {
            try { Files.deleteIfExists(dir.resolve(base + suffix)); } catch (Exception ignored) {}
        }
    }

    /** 下载过程中可能产生的中间文件后缀（成品文件不在其中）。 */
    private static final List<String> TEMP_FILE_SUFFIXES = List.of(
            ".part", ".tmp.mp4", ".tmp.mp4.part",
            ".video.tmp", ".video.tmp.part", ".audio.tmp", ".audio.tmp.part");

    /**
     * 任务实际执行流程
     */
    private void executeDownloadTask(Long taskId, boolean autoEnqueue) {
        MvDownloadTask task = taskRepo.findById(taskId).orElse(null);
        if (task == null || "CANCELLED".equals(task.getStatus())) return;

        activeTaskCancelFlags.remove(taskId);
        task.setStatus("DOWNLOADING");
        task.setErrorMessage(null);
        // 每次进入执行都重新统计本次会话的字节数；已有 .part 断点会在下载阶段被识别并续传
        task.setDownloadedBytes(0L);
        task.setTotalBytes(0L);
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
                downloadWithRetry(streamInfo.videoUrl(), streamInfo.httpHeaders(), tmpFile, task, 0, 90);
                checkCancelled(taskId);
                Files.move(tmpFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            } else {
                // DASH 音视频分流拉取并合流 (B站模式)
                videoTmp = targetDir.resolve(baseName + ".video.tmp");
                audioTmp = targetDir.resolve(baseName + ".audio.tmp");

                // 0% ~ 60%: 视频流下载
                downloadWithRetry(streamInfo.videoUrl(), streamInfo.httpHeaders(), videoTmp, task, 0, 60);
                checkCancelled(taskId);

                // 60% ~ 85%: 音频流下载
                downloadWithRetry(streamInfo.audioUrl(), streamInfo.httpHeaders(), audioTmp, task, 60, 85);
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

            // 只把当前下载文件交给单文件入库，禁止默认全量扫描 source-music
            String importStatus = "FAILED";
            Long songId = null;
            try {
                if (mediaImportService != null) {
                    MediaImportService.SourceScanResult importResult = mediaImportService.scanSourceLibrary(targetFile);
                    if (importResult.copied() > 0) importStatus = "COPIED";
                    else if (importResult.pendingTranscode() > 0) importStatus = "PENDING_TRANSCODE";
                    else if (importResult.skippedSourceDuplicate() > 0 || importResult.skippedOutputDuplicate() > 0) importStatus = "SKIPPED";
                    else if (importResult.unrecognized() > 0) importStatus = "UNRECOGNIZED";
                    else if (importResult.failed() > 0) importStatus = "FAILED";
                    else importStatus = "UNCHANGED";
                } else if (scanService != null) {
                    scanService.ingest(targetFile);
                    importStatus = "IMPORTED";
                }
            } catch (Exception se) {
                importStatus = "FAILED";
                log.warn("单文件增量入库失败: {}", se.getMessage());
            }

            songId = resolveImportedSongId(targetFile);
            log.info("MV 单文件增量入库完成 taskId={} importStatus={} songId={}", taskId, importStatus, songId);
            if ("FAILED".equals(importStatus) && songId == null) {
                throw new IOException("下载完成但单文件入库失败");
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
                    "title", task.getTitle(),
                    "status", task.getStatus(),
                    "importStatus", importStatus
            )));

        } catch (Exception e) {
            boolean cancelled = e instanceof TaskCancelledException;
            log.error("MV 下载任务{} taskId={}: {}", cancelled ? "被取消" : "执行失败", taskId, e.getMessage(), e);
            task.setStatus(cancelled ? "CANCELLED" : "FAILED");
            task.setErrorMessage(e.getMessage() != null ? e.getMessage() : "下载或合流失败");
            task.setSpeedBps(0);
            taskRepo.save(task);
            broadcastProgress(task);
            wsBroadcaster.broadcast(WsEvent.of("mv_download_completed", Map.of(
                    "taskId", task.getId(),
                    "songId", 0,
                    "title", task.getTitle(),
                    "status", task.getStatus()
            )));
            // 保留 .part 断点文件供重试续传；仅清理本身不完整、无法续传的中间产物
            if (tmpFile != null) try { Files.deleteIfExists(tmpFile); } catch (Exception ignored) {}
            if (videoTmp != null) try { Files.deleteIfExists(videoTmp); } catch (Exception ignored) {}
            if (audioTmp != null) try { Files.deleteIfExists(audioTmp); } catch (Exception ignored) {}
        } finally {
            activeTaskCancelFlags.remove(taskId);
        }
    }

    /**
     * 流式下载到目标文件，支持真实断点续传（任务 3.2）。
     *
     * <p>未完成的下载保存在同级 {@code .part} 文件中：重试/重跑时按已有长度发送
     * {@code Range: bytes=<length>-}，服务端返回 206 才继续追加；若返回 200（忽略 Range）
     * 或 ETag/Last-Modified 变化，则丢弃断点从头下载。下载成功后原子重命名，避免半成品被扫描或播放。
     *
     * @param destFile 最终目标文件（成功后才出现）
     */
    void downloadStreamToFile(String streamUrl, Map<String, String> headers, Path destFile,
                              MvDownloadTask task, int startPercent, int endPercent) throws Exception {
        Path partFile = partFileOf(destFile);
        long resumeOffset = Files.exists(partFile) ? Files.size(partFile) : 0L;
        if (resumeOffset > 0) {
            // 断点仅在远端文件未变化时可用
            long existingBytes = task.getDownloadedBytes();
            log.info("检测到未完成下载，尝试断点续传: file={} offset={} bytes", partFile.getFileName(), resumeOffset);
            task.setDownloadedBytes(Math.max(0, existingBytes - resumeOffset));
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(streamUrl))
                .timeout(Duration.ofMinutes(30))
                .GET();
        if (headers != null) headers.forEach(builder::header);
        if (resumeOffset > 0) {
            builder.header("Range", "bytes=" + resumeOffset + "-");
            if (task.getEtag() != null && !task.getEtag().isBlank()) {
                builder.header("If-Range", task.getEtag());
            } else if (task.getLastModified() != null && !task.getLastModified().isBlank()) {
                builder.header("If-Range", task.getLastModified());
            }
        }

        HttpResponse<InputStream> response =
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        String etag = response.headers().firstValue("ETag").orElse(null);
        String lastModified = response.headers().firstValue("Last-Modified").orElse(null);

        boolean resumed = false;
        long totalLength;
        if (resumeOffset > 0 && status == 206) {
            // 服务端确认断点有效：正文是剩余部分
            long remaining = response.headers().firstValueAsLong("Content-Length").orElse(0L);
            totalLength = remaining > 0 ? resumeOffset + remaining : 0L;
            resumed = true;
            task.setResumeState("RESUMED");
        } else if (resumeOffset > 0 && status == 200) {
            // 服务端忽略 Range（或 If-Range 失效）：必须丢弃断点全量重下，否则文件会损坏
            log.info("服务端未支持断点续传，改为重新完整下载: file={}", destFile.getFileName());
            resumeOffset = 0L;
            Files.deleteIfExists(partFile);
            totalLength = response.headers().firstValueAsLong("Content-Length").orElse(0L);
            task.setResumeState("RESTARTED");
            task.setDownloadedBytes(0);
        } else if (status >= 200 && status < 300) {
            totalLength = response.headers().firstValueAsLong("Content-Length").orElse(0L);
            task.setResumeState("FRESH");
        } else {
            throw new IOException("下载请求返回 HTTP " + status);
        }

        // 记录远端标识，供下次断点校验
        if (etag != null) task.setEtag(etag);
        if (lastModified != null) task.setLastModified(lastModified);
        if (totalLength > 0) {
            task.setTotalBytes(task.getTotalBytes() + totalLength);
        }

        long downloaded = resumeOffset;
        long lastTime = System.currentTimeMillis();
        long bytesSinceLastTime = 0;
        var openOptions = new java.nio.file.OpenOption[]{
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
                resumed ? java.nio.file.StandardOpenOption.APPEND
                        : java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
        };

        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(partFile, openOptions)) {
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
                    if (totalLength > 0) {
                        int subProgress = (int) ((downloaded * (endPercent - startPercent)) / totalLength);
                        task.setProgress(Math.min(endPercent, startPercent + subProgress));
                    }
                    taskRepo.save(task);
                    broadcastProgress(task);

                    lastTime = now;
                    bytesSinceLastTime = 0;
                }
            }
            out.flush();
        }

        // 完整性校验：只有拿到预期长度才认可
        if (totalLength > 0 && downloaded < totalLength) {
            throw new IOException("下载不完整（已接收 " + downloaded + "/" + totalLength + " 字节），保留断点待重试");
        }

        // 原子重命名，避免半成品被扫描或播放
        try {
            Files.move(partFile, destFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicFailure) {
            Files.move(partFile, destFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 未完成下载的临时文件路径。 */
    static Path partFileOf(Path destFile) {
        return destFile.resolveSibling(destFile.getFileName().toString() + ".part");
    }

    /**
     * 下载失败自动重试。网络抖动时保留 {@code .part} 断点，下一次尝试自动走 Range 续传；
     * 用户主动取消不重试。任务状态会在 DOWNLOADING / RETRYING 之间切换，并累计重试次数供用户查看。
     */
    private void downloadWithRetry(String streamUrl, Map<String, String> headers, Path destFile,
                                   MvDownloadTask task, int startPercent, int endPercent) throws Exception {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                downloadStreamToFile(streamUrl, headers, destFile, task, startPercent, endPercent);
                return;
            } catch (TaskCancelledException cancelled) {
                throw cancelled;
            } catch (Exception failure) {
                if (attempt >= DOWNLOAD_MAX_ATTEMPTS) throw failure;
                task.setRetryCount(task.getRetryCount() + 1);
                task.setStatus("RETRYING");
                task.setErrorMessage("第 " + attempt + " 次下载中断，正在重试：" + safeMessage(failure));
                taskRepo.save(task);
                broadcastProgress(task);
                log.warn("下载中断，保留断点准备重试 attempt={}/{} file={} msg={}",
                        attempt, DOWNLOAD_MAX_ATTEMPTS, destFile.getFileName(), failure.getMessage());
                Thread.sleep(RETRY_BACKOFF_MS * attempt);
                task.setStatus("DOWNLOADING");
                task.setErrorMessage(null);
                taskRepo.save(task);
            }
        }
    }

    /**
     * 使用系统 FFmpeg 将独立的视频流和音频流无损合流（pure stream copy，耗时极短）。
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

        ExternalProcessRunner.Result result;
        try {
            result = ExternalProcessRunner.run("DASH 音视频合流", cmd, outputFile, MERGE_TIMEOUT);
        } catch (IOException e) {
            if (e.getMessage() != null && (e.getMessage().contains("Cannot run program") || e.getMessage().contains("系统找不到指定的文件"))) {
                throw new IOException("系统未找到 FFmpeg 工具。Docker 环境下已内置；本地运行请安装 FFmpeg 并配置到系统 PATH 环境变量。");
            }
            throw e;
        }
        if (result.timedOut()) {
            throw new IOException("FFmpeg 音视频合流超时（" + MERGE_TIMEOUT.toMinutes() + "分钟）");
        }
        if (result.cancelled()) {
            throw new TaskCancelledException();
        }
        if (result.exitCode() != 0 || !Files.isReadable(outputFile) || Files.size(outputFile) == 0) {
            throw new IOException("FFmpeg 音视频合流失败 (code=" + result.exitCode() + "): " + result.diagnostic());
        }
    }

    private void checkCancelled(Long taskId) {
        if (Boolean.TRUE.equals(activeTaskCancelFlags.get(taskId))) {
            throw new TaskCancelledException();
        }
    }

    /** 用户主动取消：不参与自动重试，且保留 .part 供后续手动重试续传。 */
    private static final class TaskCancelledException extends RuntimeException {
        TaskCancelledException() {
            super("任务已被用户取消");
        }
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    /**
     * 用绝对路径精确查找入库后的 songId，不再全表按文件名后缀匹配。
     * 直拷会把文件从 downloads 移到曲库目录，因此同时查源路径和目标路径。
     */
    private Long resolveImportedSongId(Path targetFile) {
        if (targetFile == null) return null;
        List<String> pathCandidates = List.of(
                targetFile.toAbsolutePath().normalize().toString(),
                targetFile.toAbsolutePath().toString(),
                targetFile.toString()
        );
        if (importRecordRepo != null) {
            for (String path : pathCandidates) {
                Optional<MediaImportRecord> record = importRecordRepo.findBySourcePath(path);
                if (record.isPresent() && record.get().getSongId() != null) return record.get().getSongId();
            }
        }
        if (songFileRepo != null) {
            for (String path : pathCandidates) {
                Optional<SongFile> byFile = songFileRepo.findByFilePath(path);
                if (byFile.isPresent()) return byFile.get().getSongId();
                List<SongFile> bySource = songFileRepo.findBySourcePath(path);
                if (bySource != null && !bySource.isEmpty()) return bySource.getFirst().getSongId();
            }
        }
        return null;
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

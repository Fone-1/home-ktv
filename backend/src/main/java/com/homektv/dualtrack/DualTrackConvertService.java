package com.homektv.dualtrack;

import com.homektv.domain.DualTrackTask;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.library.MediaClassifier;
import com.homektv.library.SettingService;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.repo.DualTrackTaskRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import com.homektv.web.dto.BatchDualTrackConvertRequest;
import com.homektv.web.dto.DualTrackConvertRequest;
import com.homektv.web.dto.DualTrackConvertResultDto;
import com.homektv.web.dto.DualTrackProgressDto;
import com.homektv.web.dto.DualTrackTaskDto;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;
import jakarta.annotation.PostConstruct;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 单轨转双轨伴奏核心业务服务。
 *
 * <p>阶段三任务 3.3 后的并发与状态模型：
 * <ul>
 *   <li>提交进入<b>有界</b>线程池（固定线程数 + 有界队列 + 明确拒绝策略），不再使用无界 cached pool；</li>
 *   <li>本地 FFmpeg（DSP/抽音频/合流）与远程 AI 使用<b>相互独立</b>的资源闸门，避免互相挤占；</li>
 *   <li>每个任务（含批次中的每一首）持久化到 {@code dual_track_tasks}，拥有独立进度、状态与失败原因，
 *       单曲任务不再覆盖批次的全局进度；</li>
 *   <li>支持取消与重试；服务重启时把遗留的 QUEUED/RUNNING 明确标记为中断失败，而不是假装仍在运行。</li>
 * </ul>
 */
@Service
public class DualTrackConvertService {

    private static final Logger log = LoggerFactory.getLogger(DualTrackConvertService.class);

    /** 线程池固定线程数：真正的并发上限由资源闸门按配置控制，池只负责有界排队与执行。 */
    private static final int POOL_SIZE = 4;
    /** 有界队列容量：超出即明确拒绝，避免无限堆积任务吃光内存。 */
    private static final int QUEUE_CAPACITY = 200;
    /** 单次提交最多接受的歌曲数，防止一次请求塞入上万首。 */
    private static final int MAX_BATCH_SIZE = 500;
    /** 远程 AI 服务内部有模型级串行锁，这里同样串行发送，避免堆积超时。 */
    private static final int REMOTE_AI_PERMITS = 1;

    private final SongRepository songRepo;
    private final SongFileRepository fileRepo;
    private final SettingService settingService;
    private final FFprobeService ffprobeService;
    private final DspVocalSeparationEngine dspEngine;
    private final RemoteAiVocalSeparationEngine remoteAiEngine;
    private final DualTrackRemuxer remuxer;
    private final WsBroadcaster wsBroadcaster;
    private final DualTrackTaskRepository taskRepo;

    /** 有界执行队列：拒绝策略为 AbortPolicy，由提交方转换为明确的队列已满错误。 */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            POOL_SIZE, POOL_SIZE, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
                Thread thread = new Thread(r, "dual-track-worker");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private final Set<Long> activeSongIds = ConcurrentHashMap.newKeySet();
    private final Map<Long, Boolean> cancelFlags = new ConcurrentHashMap<>();
    /** 批次完成计数：批内最后一个任务结束时广播批次汇总（无需占用线程等待）。 */
    private final Map<String, BatchCounters> batches = new ConcurrentHashMap<>();

    private final Object gateLock = new Object();
    private volatile Semaphore localFfmpegGate = new Semaphore(1);
    private volatile int localFfmpegPermits = 1;
    private final Semaphore remoteAiGate = new Semaphore(REMOTE_AI_PERMITS);

    private record BatchCounters(int total, AtomicInteger finished, AtomicInteger failed) {}

    public DualTrackConvertService(SongRepository songRepo,
                                   SongFileRepository fileRepo,
                                   SettingService settingService,
                                   FFprobeService ffprobeService,
                                   DspVocalSeparationEngine dspEngine,
                                   RemoteAiVocalSeparationEngine remoteAiEngine,
                                   DualTrackRemuxer remuxer,
                                   WsBroadcaster wsBroadcaster,
                                   DualTrackTaskRepository taskRepo) {
        this.songRepo = songRepo;
        this.fileRepo = fileRepo;
        this.settingService = settingService;
        this.ffprobeService = ffprobeService;
        this.dspEngine = dspEngine;
        this.remoteAiEngine = remoteAiEngine;
        this.remuxer = remuxer;
        this.wsBroadcaster = wsBroadcaster;
        this.taskRepo = taskRepo;
    }

    /**
     * 服务启动时清理上次进程遗留的任务：内存队列已丢失，留在 QUEUED/RUNNING 的任务不可能再继续，
     * 明确标记为失败并保留原因，用户可手动重试。
     */
    @PostConstruct
    void recoverInterruptedTasks() {
        List<DualTrackTask> stale = taskRepo.findByStatusInOrderByCreatedAtAsc(
                List.of(DualTrackTask.STATUS_QUEUED, DualTrackTask.STATUS_RUNNING));
        if (stale.isEmpty()) return;
        OffsetDateTime now = OffsetDateTime.now();
        for (DualTrackTask task : stale) {
            task.setStatus(DualTrackTask.STATUS_FAILED);
            task.setErrorMessage("服务重启导致任务中断，请手动重试");
            task.setFinishedAt(now);
        }
        taskRepo.saveAll(stale);
        log.warn("已把 {} 个服务重启前遗留的双轨任务标记为中断失败", stale.size());
    }

    // ---------------------------------------------------------------- 提交

    /**
     * 异步提交单曲转双轨伴奏任务。
     */
    public DualTrackConvertResultDto submitSingleConversion(Long songId, DualTrackConvertRequest request) {
        Song song = songRepo.findById(songId)
                .orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在: " + songId));

        if (!activeSongIds.add(songId)) {
            throw new ApiException("CONVERSION_IN_PROGRESS", "该歌曲正在转换双轨伴奏中，请稍候");
        }

        String mode = resolveMode(request);
        Boolean backup = (request != null) ? request.backupOriginal() : null;
        String format = (request != null && request.outputFormat() != null && !request.outputFormat().isBlank())
                ? request.outputFormat() : null;

        DualTrackTask task;
        try {
            task = createTask(songId, song.getTitle(), mode, DualTrackTask.ORIGIN_SINGLE, null);
            enqueue(task, mode, backup, format);
        } catch (RuntimeException failure) {
            activeSongIds.remove(songId);
            throw failure;
        }
        return new DualTrackConvertResultDto(songId, "PROCESSING", "已提交双轨转换任务",
                String.valueOf(task.getId()));
    }

    /**
     * 提交批量转换任务。批次内每首歌各自持久化任务，共享 batchId。
     */
    public Map<String, Object> submitBatchConversion(BatchDualTrackConvertRequest request) {
        List<Long> songIds = (request != null && request.songIds() != null) ? request.songIds() : List.of();
        if (songIds.isEmpty()) {
            throw new ApiException("PARAM_INVALID", "待转换歌曲列表为空");
        }
        List<Long> distinct = songIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            throw new ApiException("PARAM_INVALID", "待转换歌曲列表为空");
        }
        if (distinct.size() > MAX_BATCH_SIZE) {
            throw new ApiException("BATCH_TOO_LARGE", "单次批量转换不能超过 " + MAX_BATCH_SIZE + " 首");
        }

        SettingService.DualTrackPolicy policy = settingService.dualTrackPolicy();
        String mode = (request.mode() != null && !request.mode().isBlank())
                ? request.mode().toUpperCase() : policy.engine();
        String batchId = UUID.randomUUID().toString();
        batches.put(batchId, new BatchCounters(distinct.size(), new AtomicInteger(), new AtomicInteger()));

        int queued = 0;
        int skipped = 0;
        for (Long songId : distinct) {
            Song song = songRepo.findById(songId).orElse(null);
            if (song == null) {
                skipped++;
                batches.get(batchId).finished().incrementAndGet();
                continue;
            }
            if (!activeSongIds.add(songId)) {
                // 已在转换中的歌曲跳过，避免同一首歌并发转换互相覆盖文件
                skipped++;
                batches.get(batchId).finished().incrementAndGet();
                continue;
            }
            try {
                DualTrackTask task = createTask(songId, song.getTitle(), mode, DualTrackTask.ORIGIN_BATCH, batchId);
                enqueue(task, mode, policy.backupOriginal(), null);
                queued++;
            } catch (RuntimeException failure) {
                activeSongIds.remove(songId);
                batches.get(batchId).failed().incrementAndGet();
                batches.get(batchId).finished().incrementAndGet();
                log.warn("批量提交单曲失败: songId={}, msg={}", songId, failure.getMessage());
            }
        }

        finishBatchIfDone(batchId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", distinct.size());
        result.put("queued", queued);
        result.put("skipped", skipped);
        result.put("batchId", batchId);
        result.put("message", "批量转换任务已在后台排队");
        return result;
    }

    /** 便捷异步转换入口（供 MV 下载完成自动联动）。 */
    public void convertSongAsync(Long songId, String mode) {
        submitSingleConversion(songId, new DualTrackConvertRequest(mode, false, null));
    }

    /**
     * 取消任务：排队中的立即标记取消；运行中的设置取消标记，工作线程会在下一步检查时终止并清理临时文件。
     */
    public DualTrackTaskDto cancelTask(Long taskId) {
        DualTrackTask task = taskRepo.findById(taskId)
                .orElseThrow(() -> new ApiException("TASK_NOT_FOUND", "双轨任务不存在: " + taskId));
        if (task.isTerminal()) {
            throw new ApiException("TASK_ALREADY_FINISHED", "任务已结束，无法取消");
        }
        cancelFlags.put(taskId, true);
        if (DualTrackTask.STATUS_QUEUED.equals(task.getStatus())) {
            // 还没开始执行，直接落地为已取消
            task.setStatus(DualTrackTask.STATUS_CANCELLED);
            task.setErrorMessage("用户取消");
            task.setFinishedAt(OffsetDateTime.now());
            taskRepo.save(task);
            activeSongIds.remove(task.getSongId());
            broadcastTask(task);
            countBatchFinish(task);
        } else {
            broadcastTask(task);
        }
        return DualTrackTaskDto.from(task);
    }

    /**
     * 重试任务：把失败/取消的任务重新排队（已完成的任务不允许重试）。
     */
    public DualTrackTaskDto retryTask(Long taskId) {
        DualTrackTask task = taskRepo.findById(taskId)
                .orElseThrow(() -> new ApiException("TASK_NOT_FOUND", "双轨任务不存在: " + taskId));
        if (DualTrackTask.STATUS_COMPLETED.equals(task.getStatus())) {
            throw new ApiException("TASK_ALREADY_FINISHED", "任务已完成，无需重试");
        }
        if (!activeSongIds.add(task.getSongId())) {
            throw new ApiException("CONVERSION_IN_PROGRESS", "该歌曲正在转换双轨伴奏中，请稍候");
        }
        task.setStatus(DualTrackTask.STATUS_QUEUED);
        task.setProgress(0);
        task.setErrorMessage(null);
        task.setStartedAt(null);
        task.setFinishedAt(null);
        taskRepo.save(task);
        cancelFlags.remove(taskId);
        try {
            enqueue(task, task.getEngine(), null, null);
        } catch (RuntimeException failure) {
            activeSongIds.remove(task.getSongId());
            throw failure;
        }
        return DualTrackTaskDto.from(task);
    }

    /** 最近的任务列表（默认近 50 条），供后台展示状态与失败原因。 */
    public List<DualTrackTaskDto> listTasks(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<DualTrackTask> tasks = taskRepo.findTop50ByOrderByCreatedAtDesc();
        return tasks.stream().limit(safeLimit).map(DualTrackTaskDto::from).toList();
    }

    // ---------------------------------------------------------------- 内部调度

    private String resolveMode(DualTrackConvertRequest request) {
        if (request != null && request.mode() != null && !request.mode().isBlank()) {
            return request.mode().toUpperCase(Locale.ROOT);
        }
        return settingService.dualTrackPolicy().engine();
    }

    private DualTrackTask createTask(Long songId, String title, String engine, String origin, String batchId) {
        DualTrackTask task = new DualTrackTask();
        task.setSongId(songId);
        task.setTitle(title == null ? "" : title);
        task.setEngine(engine == null ? "DSP" : engine);
        task.setOrigin(origin);
        task.setBatchId(batchId);
        task.setStatus(DualTrackTask.STATUS_QUEUED);
        task.setProgress(0);
        return taskRepo.save(task);
    }

    private void enqueue(DualTrackTask task, String mode, Boolean backup, String format) {
        try {
            executor.execute(() -> runTask(task.getId(), task.getSongId(), mode, backup, format));
        } catch (RejectedExecutionException rejected) {
            task.setStatus(DualTrackTask.STATUS_FAILED);
            task.setErrorMessage("转换队列已满（上限 " + QUEUE_CAPACITY + "），请稍后重试");
            task.setFinishedAt(OffsetDateTime.now());
            taskRepo.save(task);
            broadcastTask(task);
            throw new ApiException("DUAL_TRACK_QUEUE_FULL", "双轨转换队列已满，请稍后重试");
        }
    }

    private void runTask(Long taskId, Long songId, String mode, Boolean backup, String format) {
        DualTrackTask task = taskRepo.findById(taskId).orElse(null);
        if (task == null) {
            activeSongIds.remove(songId);
            return;
        }
        if (Boolean.TRUE.equals(cancelFlags.get(taskId))) {
            markCancelled(task);
            return;
        }
        task.setStatus(DualTrackTask.STATUS_RUNNING);
        task.setProgress(5);
        task.setStartedAt(OffsetDateTime.now());
        task.setErrorMessage(null);
        taskRepo.save(task);
        broadcastTask(task);

        BooleanSupplier cancelled = () -> Boolean.TRUE.equals(cancelFlags.get(taskId));
        try {
            updateTaskProgress(task, 20);
            doConvert(songId, mode, backup, format, cancelled);
            task.setStatus(DualTrackTask.STATUS_COMPLETED);
            task.setProgress(100);
            task.setErrorMessage(null);
            log.info("双轨转换完成: taskId={} songId={}", taskId, songId);
        } catch (ConversionCancelledException cancelledFailure) {
            task.setStatus(DualTrackTask.STATUS_CANCELLED);
            task.setErrorMessage("用户取消");
        } catch (Exception failure) {
            log.error("双轨转换失败: taskId={} songId={} error={}", taskId, songId, failure.getMessage(), failure);
            task.setStatus(DualTrackTask.STATUS_FAILED);
            task.setErrorMessage(shortMessage(failure));
        } finally {
            task.setFinishedAt(OffsetDateTime.now());
            taskRepo.save(task);
            broadcastTask(task);
            activeSongIds.remove(songId);
            cancelFlags.remove(taskId);
            countBatchFinish(task);
        }
    }

    private void markCancelled(DualTrackTask task) {
        task.setStatus(DualTrackTask.STATUS_CANCELLED);
        task.setErrorMessage("用户取消");
        task.setFinishedAt(OffsetDateTime.now());
        taskRepo.save(task);
        broadcastTask(task);
        activeSongIds.remove(task.getSongId());
        cancelFlags.remove(task.getId());
        countBatchFinish(task);
    }

    private void updateTaskProgress(DualTrackTask task, int progress) {
        task.setProgress(Math.max(0, Math.min(100, progress)));
        taskRepo.save(task);
        broadcastTask(task);
    }

    private void countBatchFinish(DualTrackTask task) {
        if (task.getBatchId() == null) return;
        BatchCounters counters = batches.get(task.getBatchId());
        if (counters == null) return;
        counters.finished().incrementAndGet();
        if (!DualTrackTask.STATUS_COMPLETED.equals(task.getStatus())) {
            counters.failed().incrementAndGet();
        }
        finishBatchIfDone(task.getBatchId());
    }

    private void finishBatchIfDone(String batchId) {
        BatchCounters counters = batches.get(batchId);
        if (counters == null) return;
        synchronized (counters) {
            if (counters.finished().get() < counters.total()) return;
            if (batches.remove(batchId) == null) return;
        }
        wsBroadcaster.broadcast(WsEvent.of("dual_track_batch_completed", Map.of(
                "batchId", batchId,
                "total", counters.total(),
                "failed", counters.failed().get()
        )));
    }

    // ---------------------------------------------------------------- 转换核心

    /**
     * 执行单曲转换核心逻辑（同步阻塞，保留原签名供既有调用方使用）。
     */
    public DualTrackConvertResultDto doConvert(Long songId, String requestedMode, Boolean requestedBackup,
                                               String outputFormat) throws Exception {
        return doConvert(songId, requestedMode, requestedBackup, outputFormat, null);
    }

    /**
     * 执行单曲转换核心逻辑，支持取消。取消时抛 {@link ConversionCancelledException}
     * 并保证不留下半成品：临时输出在 finally 中清理。
     */
    public DualTrackConvertResultDto doConvert(Long songId, String requestedMode, Boolean requestedBackup,
                                               String outputFormat, BooleanSupplier cancelled) throws Exception {
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

        // 按资源类型限流：本地 FFmpeg 与远程 AI 各自独立，避免互相挤占
        Semaphore gate = gateFor(effectiveMode);
        gate.acquire();
        try {
            checkCancelled(cancelled);
            if (isAiMode(effectiveMode)) {
                // 方案B：UVR-MDX-Net 深度学习 AI 分离模式
                Path tempAccompAudio = Files.createTempFile("accomp-", ".wav");
                try {
                    remoteAiEngine.separateAccompanimentWithConfig(
                            inputPath, tempAccompAudio, bitrate, policy.remoteUrl(), policy.remoteToken());
                    checkCancelled(cancelled);
                    remuxer.remuxWithAccompanimentAudio(inputPath, tempAccompAudio, tempOutput, bitrate);
                } catch (ConversionCancelledException cancelledFailure) {
                    throw cancelledFailure;
                } catch (Exception e) {
                    log.warn("AI 伴奏分离异常，尝试自动平滑降级至 DSP 声学消音: songId={}, error={}", songId, e.getMessage());
                    checkCancelled(cancelled);
                    remuxer.remuxDsp(inputPath, tempOutput, bitrate);
                } finally {
                    try { Files.deleteIfExists(tempAccompAudio); } catch (Exception ignored) {}
                }
            } else {
                // 极速 DSP 模式 (默认)
                remuxer.remuxDsp(inputPath, tempOutput, bitrate);
            }

            checkCancelled(cancelled);

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
            gate.release();
            try { Files.deleteIfExists(tempOutput); } catch (Exception ignored) {}
        }
    }

    /** 资源闸门：远端 AI 串行，本地 FFmpeg 按配置并发数动态调整许可。 */
    private Semaphore gateFor(String mode) {
        if (!isAiMode(mode)) {
            int desired = Math.max(1, Math.min(3, settingService.dualTrackPolicy().concurrency()));
            synchronized (gateLock) {
                if (desired != localFfmpegPermits) {
                    localFfmpegPermits = desired;
                    localFfmpegGate = new Semaphore(desired);
                }
                return localFfmpegGate;
            }
        }
        return remoteAiGate;
    }

    private static boolean isAiMode(String mode) {
        return "REMOTE_AI".equalsIgnoreCase(mode) || "LOCAL_AI".equalsIgnoreCase(mode) || "AI".equalsIgnoreCase(mode);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new ConversionCancelledException();
        }
    }

    /** 用户取消转换：与真实失败区分，任务状态记为 CANCELLED。 */
    static final class ConversionCancelledException extends RuntimeException {
        ConversionCancelledException() {
            super("转换已取消");
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

    // ---------------------------------------------------------------- 进度查询

    /**
     * 批次进度（兼容既有批量弹窗）。
     *
     * <p>只统计批次任务，因此单曲转换不会再覆盖批量进度显示；
     * 若当前没有批次，返回空闲状态。
     */
    public DualTrackProgressDto getProgress() {
        String batchId = latestBatchId();
        if (batchId == null) return DualTrackProgressDto.idle();
        List<DualTrackTask> tasks = taskRepo.findByBatchIdOrderByCreatedAtAsc(batchId);
        if (tasks.isEmpty()) return DualTrackProgressDto.idle();

        int total = tasks.size();
        int finished = 0;
        int pending = 0;
        DualTrackTask current = null;
        for (DualTrackTask task : tasks) {
            if (task.isTerminal()) {
                finished++;
            } else {
                if (DualTrackTask.STATUS_QUEUED.equals(task.getStatus())) pending++;
                if (current == null) current = task;
            }
        }
        boolean running = pending > 0 || current != null;
        int progress = total == 0 ? 0 : Math.round((float) finished / total * 100);
        String title = current != null ? current.getTitle() : "";
        Long currentSongId = current != null ? current.getSongId() : 0L;
        String message = running
                ? "正在处理: " + (title.isBlank() ? "待处理" : title)
                : "批量转换完成，已处理 " + finished + " 首歌曲";
        return new DualTrackProgressDto(currentSongId, title, progress, pending, finished, total, running, message);
    }

    /** 最近一次批次 ID；没有批次任务时返回 null。 */
    private String latestBatchId() {
        for (DualTrackTask task : taskRepo.findTop50ByOrderByCreatedAtDesc()) {
            if (task.getBatchId() != null) return task.getBatchId();
        }
        return null;
    }

    private void broadcastTask(DualTrackTask task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.getId());
        payload.put("songId", task.getSongId());
        payload.put("title", task.getTitle());
        payload.put("origin", task.getOrigin());
        payload.put("batchId", task.getBatchId());
        payload.put("engine", task.getEngine());
        payload.put("status", task.getStatus());
        payload.put("progress", task.getProgress());
        payload.put("queuedCount", executor.getQueue().size());
        payload.put("runningCount", executor.getActiveCount());
        payload.put("lastMessage", task.getErrorMessage() == null ? task.getStatus() : task.getErrorMessage());
        wsBroadcaster.broadcast(WsEvent.of("dual_track_progress", payload));
    }

    private static String shortMessage(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}

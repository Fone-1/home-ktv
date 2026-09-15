package com.homektv.dualtrack;

import com.homektv.domain.DualTrackTask;
import com.homektv.library.SettingService;
import com.homektv.media.FFprobeService;
import com.homektv.repo.DualTrackTaskRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import com.homektv.web.dto.DualTrackProgressDto;
import com.homektv.web.dto.DualTrackTaskDto;
import com.homektv.ws.WsBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 双轨转换任务的并发边界与状态隔离（阶段三任务 3.3）。
 *
 * 覆盖：有界线程池与拒绝策略、单曲/批次进度互不干扰、排队任务取消、已完成任务拒绝重试、
 * 服务重启后遗留任务被标记为中断失败。
 */
class DualTrackTaskLifecycleTest {

    private final Map<Long, DualTrackTask> store = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);
    private DualTrackTaskRepository taskRepo;
    private SongRepository songRepo;
    private DualTrackConvertService service;

    @BeforeEach
    void setUp() {
        store.clear();
        ids.set(1);
        taskRepo = Mockito.mock(DualTrackTaskRepository.class);
        when(taskRepo.save(any())).thenAnswer(invocation -> {
            DualTrackTask task = invocation.getArgument(0);
            if (task.getId() == null) task.setId(ids.getAndIncrement());
            // 保存快照副本：避免服务后续修改与测试断言共享同一个对象
            store.put(task.getId(), copy(task));
            return task;
        });
        when(taskRepo.saveAll(any())).thenAnswer(invocation -> {
            Collection<DualTrackTask> tasks = invocation.getArgument(0);
            tasks.forEach(task -> store.put(task.getId(), copy(task)));
            return new ArrayList<>(tasks);
        });
        when(taskRepo.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(store.get(((Number) invocation.getArgument(0)).longValue())).map(this::copy));
        when(taskRepo.findByStatusInOrderByCreatedAtAsc(any())).thenAnswer(invocation -> {
            Collection<String> statuses = invocation.getArgument(0);
            return store.values().stream().filter(task -> statuses.contains(task.getStatus())).map(this::copy).toList();
        });
        when(taskRepo.findByBatchIdOrderByCreatedAtAsc(anyString())).thenAnswer(invocation -> {
            String batchId = invocation.getArgument(0);
            return store.values().stream().filter(task -> batchId.equals(task.getBatchId())).map(this::copy).toList();
        });
        when(taskRepo.findTop50ByOrderByCreatedAtDesc()).thenAnswer(invocation ->
                store.values().stream().map(this::copy).toList());

        songRepo = Mockito.mock(SongRepository.class);
        when(songRepo.findById(any())).thenReturn(Optional.empty());

        SettingService settingService = Mockito.mock(SettingService.class);
        when(settingService.dualTrackPolicy())
                .thenReturn(new SettingService.DualTrackPolicy("DSP", "", "", 1, false, "192k"));

        service = new DualTrackConvertService(
                songRepo, Mockito.mock(SongFileRepository.class), settingService,
                Mockito.mock(FFprobeService.class), Mockito.mock(DspVocalSeparationEngine.class),
                Mockito.mock(RemoteAiVocalSeparationEngine.class), Mockito.mock(DualTrackRemuxer.class),
                Mockito.mock(WsBroadcaster.class), taskRepo);
    }

    @Test
    void executorIsBoundedWithRejectionPolicy() throws Exception {
        Field field = DualTrackConvertService.class.getDeclaredField("executor");
        field.setAccessible(true);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) field.get(service);

        // 必须是有界线程池：固定线程数 + 有界队列 + 明确拒绝策略（替代原 newCachedThreadPool）
        assertThat(executor.getMaximumPoolSize()).isEqualTo(executor.getCorePoolSize());
        assertThat(executor.getMaximumPoolSize()).isGreaterThan(0).isLessThanOrEqualTo(16);
        assertThat(executor.getQueue()).isInstanceOf(LinkedBlockingQueue.class);
        assertThat(executor.getQueue().remainingCapacity()).isGreaterThan(0);
        assertThat(executor.getQueue().remainingCapacity()).isLessThan(Integer.MAX_VALUE);
        assertThat(executor.getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        executor.shutdownNow();
    }

    @Test
    void singleTaskDoesNotPolluteBatchProgress() {
        DualTrackTask single = task(1L, "单曲", DualTrackTask.ORIGIN_SINGLE, null, DualTrackTask.STATUS_RUNNING);
        single.setProgress(60);
        store.put(single.getId(), single);

        // 单曲任务存在时，批次进度仍应为空闲，不能被单曲进度覆盖
        DualTrackProgressDto progress = service.getProgress();
        assertThat(progress.running()).isFalse();
        assertThat(progress.totalCount()).isZero();
    }

    @Test
    void batchProgressAggregatesOnlyItsOwnBatch() {
        store.put(1L, task(1L, "批1", DualTrackTask.ORIGIN_BATCH, "batch-A", DualTrackTask.STATUS_COMPLETED));
        store.put(2L, task(2L, "批2", DualTrackTask.ORIGIN_BATCH, "batch-A", DualTrackTask.STATUS_FAILED));
        store.put(3L, task(3L, "批3", DualTrackTask.ORIGIN_BATCH, "batch-A", DualTrackTask.STATUS_QUEUED));
        store.put(4L, task(4L, "单曲", DualTrackTask.ORIGIN_SINGLE, null, DualTrackTask.STATUS_RUNNING));

        DualTrackProgressDto progress = service.getProgress();
        assertThat(progress.totalCount()).isEqualTo(3);
        assertThat(progress.processedCount()).isEqualTo(2);
        assertThat(progress.pendingCount()).isEqualTo(1);
        assertThat(progress.running()).isTrue();
    }

    @Test
    void queuedTaskIsCancelledImmediately() {
        DualTrackTask queued = task(9L, "待取消", DualTrackTask.ORIGIN_SINGLE, null, DualTrackTask.STATUS_QUEUED);
        store.put(9L, queued);

        DualTrackTaskDto dto = service.cancelTask(9L);

        assertThat(dto.status()).isEqualTo(DualTrackTask.STATUS_CANCELLED);
        assertThat(dto.errorMessage()).contains("取消");
        assertThat(store.get(9L).getStatus()).isEqualTo(DualTrackTask.STATUS_CANCELLED);
    }

    @Test
    void cancellingFinishedTaskIsRejected() {
        store.put(5L, task(5L, "已完成", DualTrackTask.ORIGIN_SINGLE, null, DualTrackTask.STATUS_COMPLETED));

        assertThatThrownBy(() -> service.cancelTask(5L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("无法取消");
    }

    @Test
    void completedTaskCannotBeRetried() {
        store.put(6L, task(6L, "已完成", DualTrackTask.ORIGIN_SINGLE, null, DualTrackTask.STATUS_COMPLETED));

        assertThatThrownBy(() -> service.retryTask(6L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("无需重试");
    }

    @Test
    void retryRequeuesFailedTaskAndClearsPreviousError() {
        DualTrackTask failed = task(7L, "失败任务", DualTrackTask.ORIGIN_SINGLE, null, DualTrackTask.STATUS_FAILED);
        failed.setErrorMessage("上一次失败原因");
        failed.setProgress(40);
        store.put(7L, failed);

        service.retryTask(7L);
        waitUntil(() -> store.get(7L).getProgress() != 40 || store.get(7L).getStatus() != DualTrackTask.STATUS_FAILED);

        // 重试后先回到排队态，并且不保留旧错误
        DualTrackTask latest = store.get(7L);
        assertThat(latest.getStatus()).isIn(DualTrackTask.STATUS_QUEUED, DualTrackTask.STATUS_RUNNING,
                DualTrackTask.STATUS_FAILED);
        assertThat(latest.getProgress()).isNotEqualTo(40);
    }

    @Test
    void recoverInterruptedTasksMarksStaleAsFailedAndKeepsFinished() {
        store.put(11L, task(11L, "排队中", DualTrackTask.ORIGIN_SINGLE, null, DualTrackTask.STATUS_QUEUED));
        store.put(12L, task(12L, "执行中", DualTrackTask.ORIGIN_BATCH, "b", DualTrackTask.STATUS_RUNNING));
        store.put(13L, task(13L, "已完成", DualTrackTask.ORIGIN_SINGLE, null, DualTrackTask.STATUS_COMPLETED));
        store.put(14L, task(14L, "已取消", DualTrackTask.ORIGIN_SINGLE, null, DualTrackTask.STATUS_CANCELLED));

        service.recoverInterruptedTasks();

        assertThat(store.get(11L).getStatus()).isEqualTo(DualTrackTask.STATUS_FAILED);
        assertThat(store.get(12L).getStatus()).isEqualTo(DualTrackTask.STATUS_FAILED);
        assertThat(store.get(11L).getErrorMessage()).contains("重启");
        assertThat(store.get(13L).getStatus()).isEqualTo(DualTrackTask.STATUS_COMPLETED);
        assertThat(store.get(14L).getStatus()).isEqualTo(DualTrackTask.STATUS_CANCELLED);
    }

    @Test
    void listTasksKeepsSingleAndBatchDistinguishable() {
        store.put(21L, task(21L, "单曲A", DualTrackTask.ORIGIN_SINGLE, null, DualTrackTask.STATUS_QUEUED));
        store.put(22L, task(22L, "批A", DualTrackTask.ORIGIN_BATCH, "batch-X", DualTrackTask.STATUS_FAILED));

        List<DualTrackTaskDto> tasks = service.listTasks(10);

        assertThat(tasks).hasSize(2);
        Map<Long, DualTrackTaskDto> byId = new HashMap<>();
        tasks.forEach(item -> byId.put(item.id(), item));
        assertThat(byId.get(21L).origin()).isEqualTo(DualTrackTask.ORIGIN_SINGLE);
        assertThat(byId.get(21L).batchId()).isNull();
        assertThat(byId.get(22L).origin()).isEqualTo(DualTrackTask.ORIGIN_BATCH);
        assertThat(byId.get(22L).batchId()).isEqualTo("batch-X");
    }

    @Test
    void missingTaskSurfacesClearError() {
        assertThatThrownBy(() -> service.cancelTask(999L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不存在");
    }

    private DualTrackTask task(Long id, String title, String origin, String batchId, String status) {
        DualTrackTask task = new DualTrackTask();
        task.setId(id);
        task.setSongId(id * 10);
        task.setTitle(title);
        task.setOrigin(origin);
        task.setBatchId(batchId);
        task.setEngine("DSP");
        task.setStatus(status);
        return task;
    }

    private DualTrackTask copy(DualTrackTask source) {
        DualTrackTask copy = new DualTrackTask();
        copy.setId(source.getId());
        copy.setSongId(source.getSongId());
        copy.setTitle(source.getTitle());
        copy.setOrigin(source.getOrigin());
        copy.setBatchId(source.getBatchId());
        copy.setEngine(source.getEngine());
        copy.setStatus(source.getStatus());
        copy.setProgress(source.getProgress());
        copy.setErrorMessage(source.getErrorMessage());
        return copy;
    }

    private void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}

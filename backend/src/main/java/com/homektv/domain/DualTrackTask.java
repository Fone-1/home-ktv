package com.homektv.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * 双轨转换任务（阶段三任务 3.3）。
 *
 * <p>单曲与批次任务各自独立成行：批次内所有任务共享 {@code batchId}，
 * 单曲任务的 {@code batchId} 为空。这样单曲转换不会再覆盖批次进度，
 * 前端也能按任务粒度展示状态、失败原因并支持取消/重试。
 */
@Entity
@Table(name = "dual_track_tasks")
public class DualTrackTask {

    /** 任务来源：单曲提交。 */
    public static final String ORIGIN_SINGLE = "SINGLE";
    /** 任务来源：批量提交。 */
    public static final String ORIGIN_BATCH = "BATCH";

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "song_id", nullable = false)
    private Long songId;

    @Column(nullable = false)
    private String title = "";

    @Column(nullable = false)
    private String origin = ORIGIN_SINGLE;

    @Column(name = "batch_id")
    private String batchId;

    @Column(nullable = false)
    private String engine = "DSP";

    @Column(nullable = false)
    private String status = STATUS_QUEUED;

    @Column(nullable = false)
    private int progress = 0;

    @Column(name = "error_message")
    private String errorMessage;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSongId() { return songId; }
    public void setSongId(Long songId) { this.songId = songId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    /** 是否为终态（无需再执行）。 */
    public boolean isTerminal() {
        return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status) || STATUS_CANCELLED.equals(status);
    }
}

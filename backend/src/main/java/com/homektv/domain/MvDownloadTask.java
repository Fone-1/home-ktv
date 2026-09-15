package com.homektv.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * MV 在线下载任务实体，对应 mv_download_tasks 表。
 * 记录来自网易云、B站等平台的下载进度、临时文件、入库关联及状态。
 */
@Entity
@Table(name = "mv_download_tasks")
public class MvDownloadTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String artist = "未知歌手";

    @Column(nullable = false)
    private String provider; // NETEASE, BILIBILI

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(name = "cover_url")
    private String coverUrl;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs = 0;

    @Column(name = "resolution")
    private String resolution = "1080p";

    @Column(nullable = false)
    private String status = "PENDING"; // PENDING, DOWNLOADING, MERGING, IMPORTING, COMPLETED, FAILED, CANCELLED

    @Column(nullable = false)
    private int progress = 0; // 0 - 100

    @Column(name = "downloaded_bytes", nullable = false)
    private long downloadedBytes = 0;

    @Column(name = "total_bytes", nullable = false)
    private long totalBytes = 0;

    @Column(name = "speed_bps", nullable = false)
    private long speedBps = 0;

    @Column(name = "target_file_path")
    private String targetFilePath;

    @Column(name = "song_id")
    private Long songId;

    @Column(name = "auto_convert_dual_track", nullable = false)
    private boolean autoConvertDualTrack = false;

    /** 远端 ETag：断点续传时校验文件是否变化。 */
    @Column(name = "etag")
    private String etag;

    /** 远端 Last-Modified：ETag 缺失时的断点校验依据。 */
    @Column(name = "last_modified")
    private String lastModified;

    /** 下载起点：FRESH 全新 / RESUMED 断点续传 / RESTARTED 断点失效后重下。 */
    @Column(name = "resume_state")
    private String resumeState;

    /** 网络失败自动重试次数。 */
    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "error_message")
    private String errorMessage;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    // ---- Getters and Setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public int getDurationMs() { return durationMs; }
    public void setDurationMs(int durationMs) { this.durationMs = durationMs; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public long getDownloadedBytes() { return downloadedBytes; }
    public void setDownloadedBytes(long downloadedBytes) { this.downloadedBytes = downloadedBytes; }
    public long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }
    public long getSpeedBps() { return speedBps; }
    public void setSpeedBps(long speedBps) { this.speedBps = speedBps; }
    public String getTargetFilePath() { return targetFilePath; }
    public void setTargetFilePath(String targetFilePath) { this.targetFilePath = targetFilePath; }
    public Long getSongId() { return songId; }
    public void setSongId(Long songId) { this.songId = songId; }
    public boolean isAutoConvertDualTrack() { return autoConvertDualTrack; }
    public void setAutoConvertDualTrack(boolean autoConvertDualTrack) { this.autoConvertDualTrack = autoConvertDualTrack; }
    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
    public String getLastModified() { return lastModified; }
    public void setLastModified(String lastModified) { this.lastModified = lastModified; }
    public String getResumeState() { return resumeState; }
    public void setResumeState(String resumeState) { this.resumeState = resumeState; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}

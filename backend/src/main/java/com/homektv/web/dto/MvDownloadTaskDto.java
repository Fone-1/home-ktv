package com.homektv.web.dto;

import com.homektv.domain.MvDownloadTask;

import java.time.OffsetDateTime;

public record MvDownloadTaskDto(
        Long id,
        String title,
        String artist,
        String provider,
        String externalId,
        String coverUrl,
        int durationMs,
        String resolution,
        String status,
        int progress,
        long downloadedBytes,
        long totalBytes,
        long speedBps,
        String targetFilePath,
        Long songId,
        boolean autoConvertDualTrack,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static MvDownloadTaskDto from(MvDownloadTask task) {
        return new MvDownloadTaskDto(
                task.getId(),
                task.getTitle(),
                task.getArtist(),
                task.getProvider(),
                task.getExternalId(),
                task.getCoverUrl(),
                task.getDurationMs(),
                task.getResolution(),
                task.getStatus(),
                task.getProgress(),
                task.getDownloadedBytes(),
                task.getTotalBytes(),
                task.getSpeedBps(),
                task.getTargetFilePath(),
                task.getSongId(),
                task.isAutoConvertDualTrack(),
                task.getErrorMessage(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}

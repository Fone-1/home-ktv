package com.homektv.web.dto;

import com.homektv.domain.DualTrackTask;

import java.time.OffsetDateTime;

/**
 * 双轨转换任务视图（阶段三任务 3.3）。
 * 单曲与批次任务通过 {@code origin}/{@code batchId} 区分，各自带独立状态与失败原因。
 */
public record DualTrackTaskDto(
        Long id,
        Long songId,
        String title,
        String origin,
        String batchId,
        String engine,
        String status,
        int progress,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt
) {
    public static DualTrackTaskDto from(DualTrackTask task) {
        return new DualTrackTaskDto(
                task.getId(),
                task.getSongId(),
                task.getTitle(),
                task.getOrigin(),
                task.getBatchId(),
                task.getEngine(),
                task.getStatus(),
                task.getProgress(),
                task.getErrorMessage(),
                task.getCreatedAt(),
                task.getStartedAt(),
                task.getFinishedAt()
        );
    }
}

package com.homektv.web.dto;

import java.util.List;

/**
 * 队列 + 播放状态快照（详设§11.1 GET /queue，也用于 WS sync_full）。
 *
 * Queue and playback status snapshot (detailed design §11.1 GET /queue, also used for WS sync_full).
 */
public record QueueSnapshot(
        NowPlaying playing,
        List<QueueEntry> list,
        String state,      // idle/playing/paused
        int volume,
        boolean muted,
        String vocalMode,  // original/accompaniment
        boolean tvOnline,  // TV 是否在线（P2.13：H5 据此显示「电视未连接」横幅） / Whether TV is online (P2.13: H5 shows "TV not connected" banner based on this)
        long connectedPhones,
        Long queueId,
        Integer position,
        Boolean duplicated,
        Boolean playbackStarted,
        long totalCount    // 等待队列总长度（快照 list 可能被截断到上限，前端据它提示剩余数量）
) {
    public QueueSnapshot(NowPlaying playing, List<QueueEntry> list, String state,
                         int volume, boolean muted, String vocalMode,
                         boolean tvOnline, long connectedPhones) {
        this(playing, list, state, volume, muted, vocalMode, tvOnline, connectedPhones,
                null, null, null, null, list == null ? 0 : list.size());
    }

    public QueueSnapshot(NowPlaying playing, List<QueueEntry> list, String state,
                         int volume, boolean muted, String vocalMode,
                         boolean tvOnline, long connectedPhones, long totalCount) {
        this(playing, list, state, volume, muted, vocalMode, tvOnline, connectedPhones,
                null, null, null, null, totalCount);
    }

    public QueueSnapshot withOrderResult(Long queueId, Integer position, Boolean duplicated, Boolean playbackStarted) {
        return new QueueSnapshot(playing, list, state, volume, muted, vocalMode, tvOnline, connectedPhones,
                queueId, position, duplicated, playbackStarted, totalCount);
    }

    /** 正在播放 / Now playing */
    public record NowPlaying(Long queueId, SongDto song, String orderedByNick) {}

    /** 队列条目 / Queue entry */
    public record QueueEntry(Long queueId, SongDto song, Long orderedBy, String orderedByNick, String status) {}
}

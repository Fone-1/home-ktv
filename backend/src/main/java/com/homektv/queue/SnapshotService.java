package com.homektv.queue;

import com.homektv.domain.AppUser;
import com.homektv.domain.PlayerState;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.repo.AppUserRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.QueueSnapshot;
import com.homektv.web.dto.SongDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 组装队列+播放状态快照（详设§11.1 / §4.2 sync_full）。
 *
 * 快照查询数量固定（播放状态 1 + 等待队列 1 + 队列总数 1 + 当前条目 1 + 歌曲 1 + 用户 1 = 6 次），
 * 不随队列长度或用户数线性增长；大曲库/长队列下避免 N+1 拖垮 GET /queue 与 WS 广播。
 */
@Service
public class SnapshotService {

    /**
     * 快照中返回的最大等待条目数。超长队列只下发前 N 条 + totalCount，
     * 避免无限增长的完整快照占满 WS 带宽（前端可据 totalCount 提示「还有 N 首」）。
     */
    public static final int SNAPSHOT_QUEUE_LIMIT = 200;

    private final PlayerStateRepository playerRepo;
    private final QueueItemRepository queueRepo;
    private final SongRepository songRepo;
    private final AppUserRepository userRepo;
    private final com.homektv.ws.WsBroadcaster broadcaster;

    public SnapshotService(PlayerStateRepository playerRepo, QueueItemRepository queueRepo,
                           SongRepository songRepo, AppUserRepository userRepo,
                           com.homektv.ws.WsBroadcaster broadcaster) {
        this.playerRepo = playerRepo;
        this.queueRepo = queueRepo;
        this.songRepo = songRepo;
        this.userRepo = userRepo;
        this.broadcaster = broadcaster;
    }

    @Transactional(readOnly = true)
    public QueueSnapshot snapshot() {
        PlayerState ps = playerRepo.getSingleton();
        List<QueueItem> waiting = queueRepo.findByStatusOrderByOrderIndexAsc(QueueService.WAITING);
        long totalCount = waiting.size();
        // 超长队列截断下发，totalCount 保证总长度可见
        List<QueueItem> visible = totalCount > SNAPSHOT_QUEUE_LIMIT
                ? waiting.subList(0, SNAPSHOT_QUEUE_LIMIT) : waiting;

        QueueItem current = ps.getCurrentQueueId() == null
                ? null : queueRepo.findById(ps.getCurrentQueueId()).orElse(null);

        // 先收集全部歌曲 ID 与用户 ID，再一次性批量加载，禁止在 stream().map() 中逐条访问 Repository
        Set<Long> songIds = new LinkedHashSet<>();
        Set<Long> userIds = new LinkedHashSet<>();
        List<QueueItem> allItems = new ArrayList<>(visible);
        if (current != null) allItems.add(current);
        for (QueueItem item : allItems) {
            if (item.getSongId() != null) songIds.add(item.getSongId());
            if (item.getOrderedBy() != null) userIds.add(item.getOrderedBy());
        }
        Map<Long, Song> songs = songIds.isEmpty() ? Map.of()
                : songRepo.findAllById(songIds).stream()
                        .collect(Collectors.toMap(Song::getId, Function.identity(), (a, b) -> a));
        Map<Long, String> nicks = userIds.isEmpty() ? Map.of()
                : userRepo.findAllById(userIds).stream()
                        .filter(u -> u.getNickname() != null)
                        .collect(Collectors.toMap(AppUser::getId, AppUser::getNickname, (a, b) -> a));

        QueueSnapshot.NowPlaying nowPlaying = null;
        if (current != null) {
            Song song = songs.get(current.getSongId());
            nowPlaying = new QueueSnapshot.NowPlaying(
                    current.getId(),
                    song != null ? SongDto.from(song) : null,
                    nicks.get(current.getOrderedBy()));
        }

        List<QueueSnapshot.QueueEntry> list = visible.stream()
                .map(q -> new QueueSnapshot.QueueEntry(
                        q.getId(),
                        songs.get(q.getSongId()) != null ? SongDto.from(songs.get(q.getSongId())) : null,
                        q.getOrderedBy(),
                        nicks.get(q.getOrderedBy()),
                        q.getStatus()))
                .toList();

        return new QueueSnapshot(nowPlaying, list, ps.getState(), ps.getVolume(),
                ps.isMuted(), ps.getVocalMode(),
                broadcaster.isTvOnline(), broadcaster.h5Count(), totalCount);
    }
}

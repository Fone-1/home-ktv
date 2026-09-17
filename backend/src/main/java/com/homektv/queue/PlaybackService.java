package com.homektv.queue;

import com.homektv.domain.PlayHistory;
import com.homektv.domain.PlayerState;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 播放控制状态机（P1.10/P1.11，详设§4.4/§9.2/§9.4）。
 * play/pause/next/restart + 音量/静音/原伴唱；状态落 player_state。
 */
@Service
public class PlaybackService {

    private final PlayerStateRepository playerRepo;
    private final QueueItemRepository queueRepo;
    private final SongRepository songRepo;
    private final PlayHistoryRepository historyRepo;
    private final SongFileRepository fileRepo;
    private final com.homektv.library.StandbyContentCache standbyCache;

    public PlaybackService(PlayerStateRepository playerRepo, QueueItemRepository queueRepo,
                           SongRepository songRepo, PlayHistoryRepository historyRepo,
                           SongFileRepository fileRepo) {
        // 兼容旧手工构造（单元测试）：使用独立缓存实例，行为一致
        this(playerRepo, queueRepo, songRepo, historyRepo, fileRepo, new com.homektv.library.StandbyContentCache());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PlaybackService(PlayerStateRepository playerRepo, QueueItemRepository queueRepo,
                           SongRepository songRepo, PlayHistoryRepository historyRepo,
                           SongFileRepository fileRepo, com.homektv.library.StandbyContentCache standbyCache) {
        this.playerRepo = playerRepo;
        this.queueRepo = queueRepo;
        this.songRepo = songRepo;
        this.historyRepo = historyRepo;
        this.fileRepo = fileRepo;
        this.standbyCache = standbyCache;
    }

    /** 开始/恢复播放。若当前无曲目，尝试从队列取第一首。 */
    @Transactional
    public PlayerState play() {
        PlayerState ps = playerRepo.getSingleton();
        if (ps.getCurrentQueueId() == null) {
            advanceToNext(ps);
        } else {
            ps.setState("playing");
        }
        return playerRepo.save(ps);
    }

    /** 点歌后仅在播放器空闲时自动开始，不打断正在暂停的歌曲。 */
    @Transactional
    public boolean startIfIdle() {
        PlayerState ps = playerRepo.getSingletonForUpdate();
        if (ps.getCurrentQueueId() != null || !"idle".equals(ps.getState())) {
            return false;
        }
        advanceToNext(ps);
        playerRepo.save(ps);
        return ps.getCurrentQueueId() != null;
    }

    @Transactional
    public PlayerState pause() {
        PlayerState ps = playerRepo.getSingleton();
        if ("playing".equals(ps.getState())) {
            ps.setState("paused");
        }
        return playerRepo.save(ps);
    }

    /** 切歌：当前行标记 skipped，推进到下一首（详设§9.2）。 */
    @Transactional
    public PlayerState next() {
        PlayerState ps = playerRepo.getSingleton();
        markCurrent(ps, QueueService.SKIPPED, true);
        advanceToNext(ps);
        return playerRepo.save(ps);
    }

    /**
     * 播放完成（TV 上报）：当前行标记 done，写历史，推进下一首。
     * 具备严格幂等性：如果传入了 expectedQueueId，必须匹配当前播放的 queueId；
     * 若当前曲目已非 PLAYING 状态或已被处理，则忽略，避免重复推进队列或重复累计播放次数。
     *
     * @param expectedQueueId 期望完成的队列 ID，可为 null
     * @return 是否实际推进了队列（true 表示实际完成；false 表示重复上报或已切歌被忽略）
     */
    @Transactional
    public boolean onFinished(Long expectedQueueId) {
        PlayerState ps = playerRepo.getSingleton();
        Long cur = ps.getCurrentQueueId();
        if (cur == null) {
            return false;
        }
        if (expectedQueueId != null && !cur.equals(expectedQueueId)) {
            return false;
        }
        QueueItem current = queueRepo.findById(cur).orElse(null);
        if (current == null || !QueueService.PLAYING.equals(current.getStatus())) {
            return false;
        }
        markCurrent(ps, QueueService.DONE, true);
        advanceToNext(ps);
        playerRepo.save(ps);
        return true;
    }

    @Transactional
    public PlayerState onFinished() {
        onFinished(null);
        return playerRepo.getSingleton();
    }

    @Transactional
    public boolean onPlayError(Long fileId, Long expectedQueueId) {
        PlayerState ps = playerRepo.getSingleton();
        Long cur = ps.getCurrentQueueId();
        if (cur == null) {
            return false;
        }
        if (expectedQueueId != null && !cur.equals(expectedQueueId)) {
            return false;
        }
        QueueItem current = queueRepo.findById(cur).orElse(null);
        if (current == null || !QueueService.PLAYING.equals(current.getStatus())) {
            return false;
        }
        if (fileId != null) {
            fileRepo.findById(fileId).ifPresent(file -> {
                file.setValid(false);
                fileRepo.save(file);
            });
        }
        markCurrent(ps, QueueService.SKIPPED, false);
        advanceToNext(ps);
        playerRepo.save(ps);
        return true;
    }

    @Transactional
    public PlayerState onPlayError(Long fileId) {
        onPlayError(fileId, null);
        return playerRepo.getSingleton();
    }

    /** 获取当前正在播放的队列项 ID（若空闲或无歌曲则返回 null）。 */
    public Long getCurrentQueueId() {
        return playerRepo.getSingleton().getCurrentQueueId();
    }

    @Transactional
    public PlayerState recoverAfterRestart() {
        PlayerState ps = playerRepo.getSingleton();
        QueueItem current = ps.getCurrentQueueId() == null
                ? null
                : queueRepo.findById(ps.getCurrentQueueId()).orElse(null);
        if (current != null
                && (QueueService.DONE.equals(current.getStatus())
                || QueueService.SKIPPED.equals(current.getStatus()))) {
            current = null;
        }

        List<QueueItem> playingItems = queueRepo.findByStatusOrderByOrderIndexAsc(QueueService.PLAYING);
        if (current == null && !playingItems.isEmpty()) {
            current = playingItems.get(0);
        }

        if (current == null) {
            ps.setCurrentQueueId(null);
            ps.setState("idle");
            advanceToNext(ps);
            return playerRepo.save(ps);
        }

        for (QueueItem playing : playingItems) {
            if (!playing.getId().equals(current.getId())) {
                playing.setStatus(QueueService.WAITING);
                queueRepo.save(playing);
            }
        }
        current.setStatus(QueueService.PLAYING);
        queueRepo.save(current);
        ps.setCurrentQueueId(current.getId());
        ps.setState("playing");
        return playerRepo.save(ps);
    }

    /** 重唱：当前曲目回到 0，队列不变（详设§4.4.5）。 */
    @Transactional
    public PlayerState restart() {
        PlayerState ps = playerRepo.getSingleton();
        if (ps.getCurrentQueueId() == null) {
            throw new ApiException("INVALID_ACTION", "当前没有正在播放的歌曲");
        }
        ps.setState("playing");
        return playerRepo.save(ps);
    }

    @Transactional
    public PlayerState setVolume(int volume) {
        PlayerState ps = playerRepo.getSingleton();
        ps.setVolume(Math.max(0, Math.min(100, volume)));
        return playerRepo.save(ps);
    }

    @Transactional
    public PlayerState setMuted(boolean muted) {
        PlayerState ps = playerRepo.getSingleton();
        ps.setMuted(muted);
        return playerRepo.save(ps);
    }

    /** 原/伴唱切换（详设§9.4）。仅 A 类（有伴唱轨）有意义。 */
    @Transactional
    public PlayerState setVocalMode(String mode) {
        if (!"original".equals(mode) && !"accompaniment".equals(mode)) {
            throw new ApiException("INVALID_ACTION", "无效的原伴唱模式：" + mode);
        }
        PlayerState ps = playerRepo.getSingleton();
        ps.setVocalMode(mode);
        return playerRepo.save(ps);
    }

    /** 当前歌曲原/伴唱标记反转时，交换前两条音轨的语义并持久保存。 */
    @Transactional
    public PlayerState swapVocalTracks() {
        PlayerState ps = playerRepo.getSingleton();
        if (ps.getCurrentQueueId() == null) {
            throw new ApiException("INVALID_ACTION", "当前没有正在播放的歌曲");
        }
        QueueItem current = queueRepo.findById(ps.getCurrentQueueId())
                .orElseThrow(() -> new ApiException("QUEUE_ITEM_NOT_FOUND", "当前队列项不存在"));
        SongFile file = fileRepo.findBySongIdAndValidTrueOrderByPriorityDesc(current.getSongId()).stream()
                .findFirst()
                .orElseThrow(() -> new ApiException("FILE_NOT_FOUND", "当前歌曲没有可用文件源"));
        if (file.getAudioTracks() < 2) {
            throw new ApiException("INVALID_ACTION", "当前歌曲没有可交换的双音轨");
        }
        file.setVocalTrackIndex(Integer.valueOf(0).equals(file.getVocalTrackIndex()) ? 1 : 0);
        // 用户手动交换即人工确认，标 HIGH，后续复核列表不再显示
        file.setVocalConfidence("HIGH");
        fileRepo.save(file);
        return ps;
    }

    /**
     * TV 离线超时：清空等待队列并停止当前播放（不写历史），
     * 避免 TV 下次上线时快照带着未播完的歌而自动续播。返回是否有内容被清空。
     */
    @Transactional
    public boolean clearOnTvOffline() {
        PlayerState ps = playerRepo.getSingleton();
        List<QueueItem> waiting = queueRepo.findByStatusOrderByOrderIndexAsc(QueueService.WAITING);
        if (waiting.isEmpty() && ps.getCurrentQueueId() == null) {
            return false;
        }
        markCurrent(ps, QueueService.SKIPPED, false);
        queueRepo.deleteAll(waiting);
        ps.setCurrentQueueId(null);
        ps.setState("idle");
        playerRepo.save(ps);
        return true;
    }

    // ---- 内部 ----

    /** 标记当前播放行为指定终态，并写历史。 */
    private void markCurrent(PlayerState ps, String endStatus, boolean recordHistory) {
        Long cur = ps.getCurrentQueueId();
        if (cur == null) return;
        queueRepo.findById(cur).ifPresent(item -> {
            if (QueueService.PLAYING.equals(item.getStatus())) {
                item.setStatus(endStatus);
                item.setPlayedAt(OffsetDateTime.now());
                queueRepo.save(item);
                if (recordHistory) {
                    PlayHistory h = new PlayHistory();
                    h.setSongId(item.getSongId());
                    h.setPlayedBy(item.getOrderedBy());
                    historyRepo.save(h);
                    songRepo.findById(item.getSongId()).ifPresent(s -> {
                        s.setPlayCount(s.getPlayCount() + 1);
                        songRepo.save(s);
                    });
                    // 播放完成会改变热门排行与最近播放，失效待机内容短缓存
                    standbyCache.evict();
                }
            }
        });
    }

    /** 推进到下一首等待歌曲；无则进入 idle（待机）。 */
    private void advanceToNext(PlayerState ps) {
        List<QueueItem> waiting = queueRepo.findByStatusOrderByOrderIndexAsc(QueueService.WAITING);
        if (waiting.isEmpty()) {
            ps.setCurrentQueueId(null);
            ps.setState("idle");
            return;
        }
        QueueItem nextItem = waiting.get(0);
        nextItem.setStatus(QueueService.PLAYING);
        queueRepo.save(nextItem);
        ps.setCurrentQueueId(nextItem.getId());
        ps.setState("playing");
    }
}

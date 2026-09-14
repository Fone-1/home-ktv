package com.homektv.queue;

import com.homektv.domain.PlayerState;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;

/**
 * 队列状态机：点歌/顶歌/删歌（P1.9，详设§4.4）。
 * order_index 用分数插入，避免整列重排。
 *
 * Queue state machine for ordering, promoting, and cancelling songs (P1.9,
 * Detailed Design §4.4). Fractional order_index values avoid reordering the
 * entire column when inserting items.
 */
@Service
public class QueueService {

    public static final String WAITING = "waiting";
    public static final String PLAYING = "playing";
    public static final String DONE = "done";
    public static final String SKIPPED = "skipped";

    private static final double STEP = 1000.0;
    private final Object queueLock = new Object();

    private final QueueItemRepository queueRepo;
    private final SongRepository songRepo;
    private final PlayerStateRepository playerRepo;

    public QueueService(QueueItemRepository queueRepo, SongRepository songRepo,
                        PlayerStateRepository playerRepo) {
        this.queueRepo = queueRepo;
        this.songRepo = songRepo;
        this.playerRepo = playerRepo;
    }

    /**
     * 点歌结果信息。
     *
     * Result of an order operation, including the queue item, final position,
     * duplicate indicator, and whether playback was started.
     */
    public record OrderResult(
            QueueItem item,
            int position,
            boolean duplicated,
            boolean started
    ) {}

    /**
     * 点歌：追加到队尾。
     * 同一首歌已在等待队列 → 抛 SONG_IN_QUEUE（携带位次）；force=true 时允许重复插入（合唱场景）。
     */
    @Transactional
    public QueueItem order(Long songId, Long userId, boolean force) {
        return order(songId, userId, force, false).item();
    }

    /**
     * 点歌/优先插播原子操作：
     * - priority=false 时追加到队尾；
     * - priority=true 时原子创建在当前播放歌曲之后的位置（第一位等待歌曲之前），无需先追加再置顶；
     * - 校验文件与重复状态，单次事务内完成并计算最终排位。
     */
    @Transactional
    public OrderResult order(Long songId, Long userId, boolean force, boolean priority) {
        Song song = songRepo.findById(songId)
                .orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在"));
        if ("file_missing".equals(song.getStatus())) {
            throw new ApiException("FILE_MISSING", "歌曲文件丢失，无法点播");
        }

        boolean duplicated = false;
        if (!force) {
            queueRepo.lockSongForOrder(songId);
            Optional<QueueItem> dup = queueRepo.findFirstBySongIdAndStatus(songId, WAITING);
            if (dup.isPresent()) {
                int pos = positionOf(dup.get());
                throw new ApiException("SONG_IN_QUEUE",
                        "《" + song.getTitle() + "》已在队列中，第 " + pos + " 位", pos);
            }
        } else {
            duplicated = queueRepo.findFirstBySongIdAndStatus(songId, WAITING).isPresent();
        }

        synchronized (queueLock) {
            double targetIndex;
            if (priority) {
                double currentIndex = currentPlayingIndex();
                List<QueueItem> waiting = queueRepo.findByStatusOrderByOrderIndexAsc(WAITING);
                double nextIndex = waiting.stream()
                        .mapToDouble(QueueItem::getOrderIndex)
                        .filter(idx -> idx > currentIndex)
                        .min()
                        .orElse(currentIndex + 2 * STEP);

                // 若浮点步长过小，重排整列等待歌曲以重置步长
                if (nextIndex - currentIndex < 0.001) {
                    reindexWaiting(currentIndex);
                    waiting = queueRepo.findByStatusOrderByOrderIndexAsc(WAITING);
                    nextIndex = waiting.stream()
                            .mapToDouble(QueueItem::getOrderIndex)
                            .filter(idx -> idx > currentIndex)
                            .min()
                            .orElse(currentIndex + 2 * STEP);
                }
                targetIndex = (currentIndex + nextIndex) / 2.0;
            } else {
                double tail = queueRepo.findFirstByStatusOrderByOrderIndexDesc(WAITING)
                        .map(QueueItem::getOrderIndex)
                        .orElseGet(this::currentOrBaseIndex);
                targetIndex = tail + STEP;
            }

            QueueItem item = new QueueItem();
            item.setSongId(songId);
            item.setOrderedBy(userId);
            item.setOrderIndex(targetIndex);
            item.setStatus(WAITING);
            item = queueRepo.save(item);

            int pos = positionOf(item);
            return new OrderResult(item, pos, duplicated, false);
        }
    }

    /**
     * 顶歌：插入到「当前播放的下一首」位置。
     * 多人同时顶歌时，后顶者排更前（插到当前 playing 之后、第一个 waiting 之前）。
     */
    @Transactional
    public QueueItem top(Long queueId) {
        QueueItem item = queueRepo.findById(queueId)
                .orElseThrow(() -> new ApiException("QUEUE_ITEM_NOT_FOUND", "队列项不存在"));
        if (!WAITING.equals(item.getStatus())) {
            throw new ApiException("INVALID_ACTION", "只能顶起等待中的歌曲");
        }

        synchronized (queueLock) {
            double currentIndex = currentPlayingIndex();
            // 找当前播放之后的第一个等待项
            List<QueueItem> waiting = queueRepo.findByStatusOrderByOrderIndexAsc(WAITING);
            double nextIndex = waiting.stream()
                    .filter(q -> !q.getId().equals(queueId) && q.getOrderIndex() > currentIndex)
                    .mapToDouble(QueueItem::getOrderIndex)
                    .min()
                    .orElse(currentIndex + 2 * STEP);

            if (nextIndex - currentIndex < 0.001) {
                reindexWaiting(currentIndex);
                waiting = queueRepo.findByStatusOrderByOrderIndexAsc(WAITING);
                nextIndex = waiting.stream()
                        .filter(q -> !q.getId().equals(queueId) && q.getOrderIndex() > currentIndex)
                        .mapToDouble(QueueItem::getOrderIndex)
                        .min()
                        .orElse(currentIndex + 2 * STEP);
            }

            // 插到 current 与 next 的中值 → 排到最前（后顶者更前）
            item.setOrderIndex((currentIndex + nextIndex) / 2.0);
            return queueRepo.save(item);
        }
    }

    private void reindexWaiting(double currentIndex) {
        List<QueueItem> waiting = queueRepo.findByStatusOrderByOrderIndexAsc(WAITING);
        double idx = currentIndex + STEP;
        for (QueueItem q : waiting) {
            q.setOrderIndex(idx);
            idx += STEP;
        }
        queueRepo.saveAll(waiting);
    }

    /**
     * 删歌：本人可删自己点的等待歌曲；权限校验由调用方（control）处理。
     *
     * Cancel a waiting song ordered by the current user; the caller performs
     * permission validation.
     */
    @Transactional
    public void cancel(Long queueId) {
        QueueItem item = queueRepo.findById(queueId)
                .orElseThrow(() -> new ApiException("QUEUE_ITEM_NOT_FOUND", "队列项不存在"));
        if (!WAITING.equals(item.getStatus())) {
            throw new ApiException("INVALID_ACTION", "只能删除等待中的歌曲");
        }
        queueRepo.delete(item);
    }

    /** 等待队列（含歌曲信息由上层组装） */
    public List<QueueItem> waitingList() {
        return queueRepo.findByStatusOrderByOrderIndexAsc(WAITING);
    }

    public int waitingPosition(QueueItem item) {
        return positionOf(item);
    }

    /** 约束打散：尽量避免同一演唱者连续出现，当前播放项不参与重排。 */
    @Transactional
    public List<QueueItem> shuffleWaiting() {
        List<QueueItem> items = new ArrayList<>(waitingList());
        if (items.size() < 2) return items;
        Collections.shuffle(items);
        items.sort(Comparator.comparingInt(q -> 0));
        List<QueueItem> arranged = new ArrayList<>();
        Long previousUser = null;
        while (!items.isEmpty()) {
            int pick = 0;
            for (int i = 0; i < items.size(); i++) {
                Long user = items.get(i).getOrderedBy();
                if (previousUser == null || !previousUser.equals(user)) { pick = i; break; }
            }
            QueueItem item = items.remove(pick);
            arranged.add(item);
            previousUser = item.getOrderedBy();
        }
        double index = currentPlayingIndex() + STEP;
        for (QueueItem item : arranged) {
            item.setOrderIndex(index);
            index += STEP;
        }
        return queueRepo.saveAll(arranged);
    }

    // ---- 内部工具 ----

    private int positionOf(QueueItem item) {
        List<QueueItem> waiting = queueRepo.findByStatusOrderByOrderIndexAsc(WAITING);
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).getId().equals(item.getId())) return i + 1;
        }
        return waiting.size();
    }

    private double currentPlayingIndex() {
        PlayerState ps = playerRepo.getSingleton();
        if (ps.getCurrentQueueId() != null) {
            return queueRepo.findById(ps.getCurrentQueueId())
                    .map(QueueItem::getOrderIndex)
                    .orElse(0.0);
        }
        return 0.0;
    }

    private double currentOrBaseIndex() {
        return currentPlayingIndex();
    }
}

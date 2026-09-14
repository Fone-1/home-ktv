package com.homektv.queue;

import com.homektv.domain.PlayerState;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class QueueServiceTest {

    @Test
    void priorityOrderInsertsImmediatelyAfterCurrentPlaying() {
        QueueItemRepository queueRepo = Mockito.mock(QueueItemRepository.class);
        SongRepository songRepo = Mockito.mock(SongRepository.class);
        PlayerStateRepository playerRepo = Mockito.mock(PlayerStateRepository.class);

        Song song1 = new Song(); song1.setId(1L); song1.setTitle("第一首"); song1.setStatus("ok");
        Song song2 = new Song(); song2.setId(2L); song2.setTitle("第二首"); song2.setStatus("ok");
        when(songRepo.findById(1L)).thenReturn(Optional.of(song1));
        when(songRepo.findById(2L)).thenReturn(Optional.of(song2));

        PlayerState playerState = new PlayerState();
        playerState.setCurrentQueueId(null);
        when(playerRepo.getSingleton()).thenReturn(playerState);

        List<QueueItem> storedItems = new ArrayList<>();
        AtomicLong idGen = new AtomicLong(1);

        when(queueRepo.findFirstBySongIdAndStatus(any(), any())).thenReturn(Optional.empty());
        when(queueRepo.findByStatusOrderByOrderIndexAsc(QueueService.WAITING)).thenAnswer(inv ->
                storedItems.stream().sorted(Comparator.comparingDouble(QueueItem::getOrderIndex)).toList()
        );
        when(queueRepo.findFirstByStatusOrderByOrderIndexDesc(QueueService.WAITING)).thenAnswer(inv ->
                storedItems.stream().max(Comparator.comparingDouble(QueueItem::getOrderIndex))
        );
        when(queueRepo.save(any(QueueItem.class))).thenAnswer(inv -> {
            QueueItem item = inv.getArgument(0);
            if (item.getId() == null) item.setId(idGen.getAndIncrement());
            storedItems.removeIf(q -> q.getId().equals(item.getId()));
            storedItems.add(item);
            return item;
        });

        QueueService queueService = new QueueService(queueRepo, songRepo, playerRepo);

        // 先普通点播第 1 首
        QueueService.OrderResult res1 = queueService.order(1L, 100L, false, false);
        assertThat(res1.position()).isEqualTo(1);
        assertThat(res1.item().getOrderIndex()).isEqualTo(1000.0);

        // 优先插播第 2 首 -> 应插到当前播放（0.0）与第1首（1000.0）的中值 500.0，排在第 1 位
        QueueService.OrderResult res2 = queueService.order(2L, 101L, false, true);
        assertThat(res2.position()).isEqualTo(1);
        assertThat(res2.item().getOrderIndex()).isEqualTo(500.0);

        // 此时等待列表中，song2 排在第 1 位，song1 排在第 2 位
        List<QueueItem> waiting = queueService.waitingList();
        assertThat(waiting).extracting(QueueItem::getSongId).containsExactly(2L, 1L);
    }

    @Test
    void concurrentOrderingDoesNotLoseRecordsAndMaintainsOrder() throws Exception {
        QueueItemRepository queueRepo = Mockito.mock(QueueItemRepository.class);
        SongRepository songRepo = Mockito.mock(SongRepository.class);
        PlayerStateRepository playerRepo = Mockito.mock(PlayerStateRepository.class);

        PlayerState playerState = new PlayerState();
        playerState.setCurrentQueueId(null);
        when(playerRepo.getSingleton()).thenReturn(playerState);

        List<QueueItem> storedItems = Collections.synchronizedList(new ArrayList<>());
        AtomicLong idGen = new AtomicLong(1);

        when(songRepo.findById(any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            Song song = new Song();
            song.setId(id);
            song.setTitle("歌曲 " + id);
            song.setStatus("ok");
            return Optional.of(song);
        });

        when(queueRepo.findFirstBySongIdAndStatus(any(), any())).thenReturn(Optional.empty());
        when(queueRepo.findByStatusOrderByOrderIndexAsc(QueueService.WAITING)).thenAnswer(inv -> {
            synchronized (storedItems) {
                return storedItems.stream().sorted(Comparator.comparingDouble(QueueItem::getOrderIndex)).toList();
            }
        });
        when(queueRepo.findFirstByStatusOrderByOrderIndexDesc(QueueService.WAITING)).thenAnswer(inv -> {
            synchronized (storedItems) {
                return storedItems.stream().max(Comparator.comparingDouble(QueueItem::getOrderIndex));
            }
        });
        when(queueRepo.save(any(QueueItem.class))).thenAnswer(inv -> {
            QueueItem item = inv.getArgument(0);
            synchronized (storedItems) {
                if (item.getId() == null) item.setId(idGen.getAndIncrement());
                storedItems.removeIf(q -> q.getId().equals(item.getId()));
                storedItems.add(item);
            }
            return item;
        });

        QueueService queueService = new QueueService(queueRepo, songRepo, playerRepo);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Future<QueueService.OrderResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final long songId = (long) (i + 1);
            final boolean priority = (i % 2 == 1); // 奇数插播，偶数普通追加
            futures.add(executor.submit(() -> {
                latch.countDown();
                latch.await();
                return queueService.order(songId, songId, false, priority);
            }));
        }

        for (Future<QueueService.OrderResult> f : futures) {
            QueueService.OrderResult r = f.get();
            assertThat(r).isNotNull();
            assertThat(r.item().getId()).isNotNull();
        }
        executor.shutdown();

        List<QueueItem> waiting = queueService.waitingList();
        assertThat(waiting).hasSize(threadCount);

        // 验证 orderIndex 严格升序且不重复
        for (int i = 0; i < waiting.size() - 1; i++) {
            assertThat(waiting.get(i).getOrderIndex()).isLessThan(waiting.get(i + 1).getOrderIndex());
        }
    }
}

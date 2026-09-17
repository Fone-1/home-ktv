package com.homektv.queue;

import com.homektv.domain.PlayerState;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlaybackServiceTest {

    private QueueItemRepository queueRepo;
    private PlayerStateRepository playerRepo;
    private SongRepository songRepo;
    private SongFileRepository fileRepo;
    private PlayHistoryRepository historyRepo;
    private PlaybackService playbackService;

    @BeforeEach
    void setUp() {
        queueRepo = mock(QueueItemRepository.class);
        playerRepo = mock(PlayerStateRepository.class);
        songRepo = mock(SongRepository.class);
        fileRepo = mock(SongFileRepository.class);
        historyRepo = mock(PlayHistoryRepository.class);
        playbackService = new PlaybackService(playerRepo, queueRepo, songRepo, historyRepo, fileRepo);
    }

    @Test
    void onFinishedWithExpectedQueueIdSucceedsWhenMatched() {
        PlayerState ps = new PlayerState();
        ps.setCurrentQueueId(10L);
        when(playerRepo.getSingleton()).thenReturn(ps);

        QueueItem item = new QueueItem();
        item.setId(10L);
        item.setStatus(QueueService.PLAYING);
        item.setSongId(1L);
        when(queueRepo.findById(10L)).thenReturn(Optional.of(item));
        when(queueRepo.findByStatusOrderByOrderIndexAsc(QueueService.WAITING)).thenReturn(Collections.emptyList());
        when(songRepo.findById(1L)).thenReturn(Optional.of(new Song()));

        boolean result = playbackService.onFinished(10L);

        assertThat(result).isTrue();
        assertThat(item.getStatus()).isEqualTo(QueueService.DONE);
        verify(playerRepo).save(ps);
    }

    @Test
    void onFinishedFailsWhenExpectedQueueIdMismatched() {
        PlayerState ps = new PlayerState();
        ps.setCurrentQueueId(10L);
        when(playerRepo.getSingleton()).thenReturn(ps);

        boolean result = playbackService.onFinished(9L);

        assertThat(result).isFalse();
        verify(queueRepo, never()).findById(any());
        verify(playerRepo, never()).save(any());
    }

    @Test
    void onFinishedFailsWhenItemNotPlaying() {
        PlayerState ps = new PlayerState();
        ps.setCurrentQueueId(10L);
        when(playerRepo.getSingleton()).thenReturn(ps);

        QueueItem item = new QueueItem();
        item.setId(10L);
        item.setStatus(QueueService.DONE); // 已经被推进过
        when(queueRepo.findById(10L)).thenReturn(Optional.of(item));

        boolean result = playbackService.onFinished(10L);

        assertThat(result).isFalse();
        verify(playerRepo, never()).save(any());
    }
}

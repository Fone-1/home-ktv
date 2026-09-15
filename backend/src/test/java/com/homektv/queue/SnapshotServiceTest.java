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
import com.homektv.ws.WsBroadcaster;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class SnapshotServiceTest {

    @Test
    void snapshotBatchLoadsSongsAndUsersInsteadOfNPlusOne() {
        PlayerStateRepository playerRepo = Mockito.mock(PlayerStateRepository.class);
        QueueItemRepository queueRepo = Mockito.mock(QueueItemRepository.class);
        SongRepository songRepo = Mockito.mock(SongRepository.class);
        AppUserRepository userRepo = Mockito.mock(AppUserRepository.class);
        WsBroadcaster broadcaster = Mockito.mock(WsBroadcaster.class);

        PlayerState ps = new PlayerState();
        ps.setCurrentQueueId(1L);
        ps.setState("playing");
        ps.setVolume(60);
        when(playerRepo.getSingleton()).thenReturn(ps);
        when(broadcaster.isTvOnline()).thenReturn(true);
        when(broadcaster.h5Count()).thenReturn(3L);

        List<QueueItem> waiting = new ArrayList<>();
        List<Song> songs = new ArrayList<>();
        List<AppUser> users = new ArrayList<>();
        QueueItem current = item(1L, 101L, 11L, QueueService.PLAYING);
        when(queueRepo.findById(1L)).thenReturn(Optional.of(current));
        songs.add(song(101L, "当前"));
        users.add(user(11L, "房主"));
        for (int i = 0; i < 100; i++) {
            long queueId = 10L + i;
            long songId = 200L + i;
            long userId = 20L + (i % 20);
            waiting.add(item(queueId, songId, userId, QueueService.WAITING));
            songs.add(song(songId, "歌曲" + i));
            if (users.stream().noneMatch(u -> u.getId().equals(userId))) users.add(user(userId, "用户" + userId));
        }
        when(queueRepo.findByStatusOrderByOrderIndexAsc(QueueService.WAITING)).thenReturn(waiting);

        AtomicInteger songBatchCalls = new AtomicInteger();
        AtomicInteger userBatchCalls = new AtomicInteger();
        when(songRepo.findAllById(any())).thenAnswer(invocation -> {
            songBatchCalls.incrementAndGet();
            Collection<Long> ids = invocation.getArgument(0);
            return songs.stream().filter(song -> ids.contains(song.getId())).toList();
        });
        when(userRepo.findAllById(any())).thenAnswer(invocation -> {
            userBatchCalls.incrementAndGet();
            Collection<Long> ids = invocation.getArgument(0);
            return users.stream().filter(user -> ids.contains(user.getId())).toList();
        });

        SnapshotService service = new SnapshotService(playerRepo, queueRepo, songRepo, userRepo, broadcaster);
        QueueSnapshot snapshot = service.snapshot();

        assertThat(snapshot.list()).hasSize(100);
        assertThat(snapshot.totalCount()).isEqualTo(100);
        assertThat(snapshot.playing()).isNotNull();
        assertThat(snapshot.playing().song().title()).isEqualTo("当前");
        assertThat(songBatchCalls.get()).isEqualTo(1);
        assertThat(userBatchCalls.get()).isEqualTo(1);
        Mockito.verify(songRepo, Mockito.never()).findById(any());
        Mockito.verify(userRepo, Mockito.never()).findById(any());
    }

    private QueueItem item(Long id, Long songId, Long userId, String status) {
        QueueItem item = new QueueItem();
        item.setId(id);
        item.setSongId(songId);
        item.setOrderedBy(userId);
        item.setStatus(status);
        return item;
    }

    private Song song(Long id, String title) {
        Song song = new Song();
        song.setId(id);
        song.setTitle(title);
        song.setArtist("歌手");
        song.setMediaType("KTV_VIDEO");
        song.setStatus("ok");
        return song;
    }

    private AppUser user(Long id, String nickname) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setNickname(nickname);
        return user;
    }
}

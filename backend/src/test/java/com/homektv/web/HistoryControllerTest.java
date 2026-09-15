package com.homektv.web;

import com.homektv.domain.AppUser;
import com.homektv.domain.PlayHistory;
import com.homektv.domain.Song;
import com.homektv.repo.AppUserRepository;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.RecentHistoryDto;
import com.homektv.web.dto.SongDto;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class HistoryControllerTest {

    @Test
    void recentHistoryBatchLoadsSongsAndNicknames() {
        PlayHistoryRepository historyRepo = Mockito.mock(PlayHistoryRepository.class);
        SongRepository songRepo = Mockito.mock(SongRepository.class);
        AppUserRepository userRepo = Mockito.mock(AppUserRepository.class);
        HistoryController controller = new HistoryController(
                historyRepo, songRepo, userRepo, null, null, null, null, null);

        PlayHistory first = history(1L, 11L, 21L);
        PlayHistory second = history(2L, 12L, 22L);
        when(historyRepo.findTop50ByOrderByPlayedAtDesc()).thenReturn(List.of(first, second));

        AtomicInteger songCalls = new AtomicInteger();
        AtomicInteger userCalls = new AtomicInteger();
        when(songRepo.findAllById(any())).thenAnswer(invocation -> {
            songCalls.incrementAndGet();
            Collection<Long> ids = invocation.getArgument(0);
            return List.of(song(11L, "晴天"), song(12L, "七里香")).stream()
                    .filter(song -> ids.contains(song.getId())).toList();
        });
        when(userRepo.findAllById(any())).thenAnswer(invocation -> {
            userCalls.incrementAndGet();
            Collection<Long> ids = invocation.getArgument(0);
            return List.of(user(21L, "小明"), user(22L, "小红")).stream()
                    .filter(user -> ids.contains(user.getId())).toList();
        });

        List<RecentHistoryDto> result = controller.recent(null, false);
        assertThat(result).extracting(item -> item.song().title()).containsExactly("晴天", "七里香");
        assertThat(result).extracting(RecentHistoryDto::playedByNick).containsExactly("小明", "小红");
        assertThat(songCalls.get()).isEqualTo(1);
        assertThat(userCalls.get()).isEqualTo(1);
        Mockito.verify(songRepo, Mockito.never()).findById(any());
        Mockito.verify(userRepo, Mockito.never()).findById(any());
    }

    @Test
    void rankingBatchLoadsSongsInRankOrder() {
        PlayHistoryRepository historyRepo = Mockito.mock(PlayHistoryRepository.class);
        SongRepository songRepo = Mockito.mock(SongRepository.class);
        DiscoveryController controller = new DiscoveryController(historyRepo, songRepo);
        List<Object[]> rankingRows = new java.util.ArrayList<>();
        rankingRows.add(new Object[]{2L, 9L});
        rankingRows.add(new Object[]{1L, 3L});
        when(historyRepo.ranking(any(), Mockito.eq(20))).thenReturn(rankingRows);
        AtomicInteger songCalls = new AtomicInteger();
        when(songRepo.findAllById(any())).thenAnswer(invocation -> {
            songCalls.incrementAndGet();
            return List.of(okSong(1L, "后唱"), okSong(2L, "先唱"));
        });

        List<SongDto> ranking = controller.ranking(30);
        assertThat(ranking).extracting(SongDto::title).containsExactly("先唱", "后唱");
        assertThat(songCalls.get()).isEqualTo(1);
        Mockito.verify(songRepo, Mockito.never()).findById(any());
    }

    private PlayHistory history(Long id, Long songId, Long userId) {
        PlayHistory history = new PlayHistory();
        history.setId(id);
        history.setSongId(songId);
        history.setPlayedBy(userId);
        return history;
    }

    private Song song(Long id, String title) {
        Song song = new Song();
        song.setId(id);
        song.setTitle(title);
        song.setArtist("周杰伦");
        song.setMediaType("KTV_VIDEO");
        song.setStatus("ok");
        return song;
    }

    private Song okSong(Long id, String title) {
        return song(id, title);
    }

    private AppUser user(Long id, String nickname) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setNickname(nickname);
        return user;
    }
}

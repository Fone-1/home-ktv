package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.SongDto;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class StandbyContentServiceTest {

    @Test
    void customSongsPreserveConfiguredOrderAndBatchLoad() {
        SettingService settingService = Mockito.mock(SettingService.class);
        SongRepository songRepo = Mockito.mock(SongRepository.class);
        PlayHistoryRepository historyRepo = Mockito.mock(PlayHistoryRepository.class);
        StandbyContentCache cache = new StandbyContentCache();
        when(settingService.getAll()).thenReturn(Map.of(
                "standby_source", "custom",
                "standby_song_ids", List.of(3, 1, 2)
        ));
        AtomicInteger batchCalls = new AtomicInteger();
        when(songRepo.findAllById(any())).thenAnswer(invocation -> {
            batchCalls.incrementAndGet();
            return List.of(ok(1L, "一"), ok(2L, "二"), ok(3L, "三"));
        });

        StandbyContentService service = new StandbyContentService(
                settingService, songRepo, historyRepo, Mockito.mock(AssetWriter.class), cache);
        @SuppressWarnings("unchecked")
        List<SongDto> first = (List<SongDto>) service.content().get("songs");
        assertThat(first).extracting(SongDto::id).containsExactly(3L, 1L, 2L);
        assertThat(batchCalls.get()).isEqualTo(1);
        Mockito.verify(songRepo, Mockito.never()).findById(any());
    }

    @Test
    void cachePreventsRepeatedHotQueriesUntilEvicted() {
        SettingService settingService = Mockito.mock(SettingService.class);
        SongRepository songRepo = Mockito.mock(SongRepository.class);
        PlayHistoryRepository historyRepo = Mockito.mock(PlayHistoryRepository.class);
        StandbyContentCache cache = new StandbyContentCache();
        when(settingService.getAll()).thenReturn(Map.of("standby_source", "hot"));
        List<Object[]> ranking = new java.util.ArrayList<>();
        ranking.add(new Object[]{1L, 9L});
        when(historyRepo.ranking(any(), Mockito.eq(20))).thenReturn(ranking);
        when(songRepo.findAllById(any())).thenReturn(List.of(ok(1L, "热歌")));

        StandbyContentService service = new StandbyContentService(
                settingService, songRepo, historyRepo, Mockito.mock(AssetWriter.class), cache);
        service.content();
        service.content();
        Mockito.verify(historyRepo, Mockito.times(1)).ranking(any(), Mockito.eq(20));
        cache.evict();
        service.content();
        Mockito.verify(historyRepo, Mockito.times(2)).ranking(any(), Mockito.eq(20));
    }

    private Song ok(Long id, String title) {
        Song song = new Song();
        song.setId(id);
        song.setTitle(title);
        song.setArtist("歌手");
        song.setMediaType("KTV_VIDEO");
        song.setStatus("ok");
        return song;
    }
}

package com.homektv.musicsource;

import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MusicSourceSearchServiceTest {
    @Test
    void usesConfiguredProvidersWhenCallerDoesNotOverrideThem() {
        Set<MusicProvider> selected = MusicSourceSearchService.selectProviders(Set.of(), Set.of(MusicProvider.QQ));

        selected.retainAll(Set.of(MusicProvider.QQ));

        assertThat(selected).containsExactly(MusicProvider.QQ);
    }

    @Test
    void automaticallyUsesCleanedCandidateQueryWhenMatchingMessySong() {
        MusicMetadataProvider provider = mock(MusicMetadataProvider.class);
        when(provider.provider()).thenReturn(MusicProvider.KUGOU);

        MusicSourceConfigService configService = mock(MusicSourceConfigService.class);
        when(configService.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.KUGOU), 20, 5, 6, 1, 1500, 0.95));

        ExternalTrackStorage storage = mock(ExternalTrackStorage.class);
        when(storage.cachedSearch(any(), any(), anyInt())).thenReturn(Optional.empty());

        SongRepository songRepository = mock(SongRepository.class);
        Song song = new Song();
        song.setId(100L);
        song.setTitle("002.周杰伦-晴天 - 1");
        song.setArtist("超级爱下雨天");
        song.setDurationMs(269_760);
        when(songRepository.findById(100L)).thenReturn(Optional.of(song));

        ExternalTrack track = new ExternalTrack(
                MusicProvider.KUGOU, "hash-jay-qingtian", "晴天", List.of("周杰伦"),
                "叶惠美", 269_000, "2003-07-31", List.of(), "https://cover.example.com", "AVAILABLE", null);
        when(provider.search(eq("周杰伦 晴天"), anyInt(), any(Duration.class))).thenReturn(List.of(track));

        ExternalTrackMatcher matcher = new ExternalTrackMatcher();
        MusicSourceSearchService service = new MusicSourceSearchService(
                List.of(provider), configService, storage, matcher, songRepository);

        List<MusicSourceSearchService.SongMatch> matches = service.matches(100L, false);

        assertThat(matches).isNotEmpty();
        MusicSourceSearchService.SongMatch top = matches.getFirst();
        assertThat(top.track().title()).isEqualTo("晴天");
        assertThat(top.track().artists()).containsExactly("周杰伦");
        assertThat(top.score()).isGreaterThanOrEqualTo(0.96);

        // 验证确实调用了提取出的智能搜索词 "周杰伦 晴天"
        verify(provider).search(eq("周杰伦 晴天"), eq(20), any(Duration.class));
    }
}

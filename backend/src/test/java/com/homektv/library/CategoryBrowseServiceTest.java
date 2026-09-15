package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.SongDto;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class CategoryBrowseServiceTest {

    @Test
    void songsFilterByMediaTypeExcludesOtherTypes() {
        SongRepository songRepo = Mockito.mock(SongRepository.class);
        CategoryBrowseService service = new CategoryBrowseService(songRepo);

        Song ktv = createSong("晴天", "周杰伦", "KTV_VIDEO", "未知", null);
        Song mv = createSong("七里香", "周杰伦", "MV", "未知", null);
        Song audio = createSong("后来", "刘若英", "AUDIO", "未知", null);

        when(songRepo.findAll()).thenReturn(List.of(ktv, mv, audio));
        when(songRepo.findByStatus("ok")).thenReturn(List.of());
        when(songRepo.findBrowseSongs(any(), any(), any(), any())).thenReturn(List.of());

        List<SongDto> result = service.songs(null, null, null, null, null, "KTV_VIDEO", "hot", 100);
        assertThat(result).extracting(SongDto::title).containsExactly("晴天");
    }

    @Test
    void songsFilterByVocalFormPrioritizesManualLockOverAi() {
        SongRepository songRepo = Mockito.mock(SongRepository.class);
        CategoryBrowseService service = new CategoryBrowseService(songRepo);

        // 歌曲1：仅人工基础字段为对唱，无 AI 数据 -> 应命中对唱
        Song song1 = createSong("屋顶", "周杰伦", "KTV_VIDEO", "对唱", null);
        // 歌曲2：基础字段未知，AI 建议对唱 -> 应命中对唱
        Song song2 = createSong("千里之外", "周杰伦", "KTV_VIDEO", "未知", "对唱");
        // 歌曲3：人工锁定了独唱，但 AI 建议对唱 -> 人工锁定优先，不应命中对唱
        Song song3 = createSong("安静", "周杰伦", "KTV_VIDEO", "独唱", "对唱");
        song3.lockMetadata("vocalForm");

        when(songRepo.findAll()).thenReturn(List.of(song1, song2, song3));
        when(songRepo.findByStatus("ok")).thenReturn(List.of());
        when(songRepo.findBrowseSongs(any(), any(), any(), any())).thenReturn(List.of());

        List<SongDto> result = service.songs(null, null, null, null, "对唱", null, "hot", 100);
        assertThat(result).extracting(SongDto::title).containsExactlyInAnyOrder("屋顶", "千里之外");
    }

    @Test
    void songsFilterWithInvalidMediaTypeReturnsEmptyListGracefully() {
        SongRepository songRepo = Mockito.mock(SongRepository.class);
        CategoryBrowseService service = new CategoryBrowseService(songRepo);

        Song ktv = createSong("晴天", "周杰伦", "KTV_VIDEO", "未知", null);
        when(songRepo.findAll()).thenReturn(List.of(ktv));
        when(songRepo.findByStatus("ok")).thenReturn(List.of());
        when(songRepo.findBrowseSongs(any(), any(), any(), any())).thenReturn(List.of());

        List<SongDto> result = service.songs(null, null, null, null, null, "INVALID_TYPE", "hot", 100);
        assertThat(result).isEmpty();
    }

    private Song createSong(String title, String artist, String mediaType, String vocalForm, String aiVocalForm) {
        Song song = new Song();
        song.setId((long) title.hashCode());
        song.setTitle(title);
        song.setArtist(artist);
        song.setMediaType(mediaType);
        song.setStatus("ok");
        song.setVocalForm(vocalForm);
        song.setAiVocalForm(aiVocalForm);
        return song;
    }

    @Test
    void dominantArtistGenderUsesKnownMajority() {
        assertThat(CategoryBrowseService.dominantArtistGender(List.of(
                song("男歌手"), song("男歌手"), song("女歌手"), song("未知"))))
                .isEqualTo("男歌手");
    }

    @Test
    void dominantArtistGenderFallsBackToUnknown() {
        assertThat(CategoryBrowseService.dominantArtistGender(List.of(song("未知"), song(null))))
                .isEqualTo("未知");
    }

    private Song song(String gender) {
        Song song = new Song();
        song.setArtistGender(gender);
        return song;
    }
}

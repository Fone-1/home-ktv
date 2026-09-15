package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.ai.AiConfigService;
import com.homektv.ai.OpenAiCompatibleClient;
import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtistLibraryServicePagingTest {

    @Test
    void pageSplitsGroupedArtistsWithoutLoadingEverythingIntoOneResponse() {
        SongRepository songs = mock(SongRepository.class);
        ArtistLibraryService service = new ArtistLibraryService(
                songs, mock(AiConfigService.class), mock(OpenAiCompatibleClient.class), new ObjectMapper());
        List<Song> library = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            library.add(song((long) i, "歌手" + String.format("%02d", i)));
        }
        when(songs.findAll()).thenReturn(library);
        when(songs.findByStatus("ok")).thenReturn(library);

        Page<Map<String, Object>> first = service.page(null, null, null, 0, 5);
        Page<Map<String, Object>> second = service.page(null, null, null, 1, 5);

        assertThat(first.getTotalElements()).isEqualTo(12);
        assertThat(first.getContent()).hasSize(5);
        assertThat(second.getContent()).hasSize(5);
        assertThat(first.getContent().get(0).get("name")).isNotEqualTo(second.getContent().get(0).get("name"));
    }

    private Song song(Long id, String artist) {
        Song song = new Song();
        song.setId(id);
        song.setArtist(artist);
        song.setTitle("歌曲" + id);
        song.setStatus("ok");
        song.setArtistGender("未知");
        song.setMediaType("KTV_VIDEO");
        song.setPlayCount(id.intValue());
        return song;
    }
}

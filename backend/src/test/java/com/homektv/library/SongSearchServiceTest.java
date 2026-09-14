package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongSearchRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SongSearchServiceTest {

    @Test
    void emptyKeywordReturnsEmptyListWithoutQueryingRepo() {
        SongSearchRepository searchRepo = mock(SongSearchRepository.class);
        SongSearchService service = new SongSearchService(searchRepo);

        assertThat(service.search("", 0)).isEmpty();
        assertThat(service.search("   ", 0)).isEmpty();
        assertThat(service.search(null, 0)).isEmpty();

        verifyNoInteractions(searchRepo);
    }

    @Test
    void searchPassesNormalizedKeywordAndTypeToRepo() {
        SongSearchRepository searchRepo = mock(SongSearchRepository.class);
        SongSearchService service = new SongSearchService(searchRepo);

        Song song = new Song();
        song.setId(1L);
        song.setTitle("晴天");
        song.setMediaType("KTV_VIDEO");

        when(searchRepo.search(eq("zjl"), eq("KTV_VIDEO"), any(PageRequest.class)))
                .thenReturn(List.of(song));

        List<Song> results = service.search("  ZJL  ", "ktv_video", 1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("晴天");

        ArgumentCaptor<PageRequest> pageCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(searchRepo).search(eq("zjl"), eq("KTV_VIDEO"), pageCaptor.capture());
        assertThat(pageCaptor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void searchBlankTypePassesNullTypeToRepo() {
        SongSearchRepository searchRepo = mock(SongSearchRepository.class);
        SongSearchService service = new SongSearchService(searchRepo);

        service.search("晴天", "   ", 0);
        verify(searchRepo).search(eq("晴天"), isNull(), any(PageRequest.class));
    }
}

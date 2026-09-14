package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.AdminSongDto;
import com.homektv.ws.WsBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AdminServiceTest {

    private SongRepository songRepo;
    private SongFileRepository fileRepo;
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        songRepo = mock(SongRepository.class);
        fileRepo = mock(SongFileRepository.class);
        adminService = new AdminService(
                songRepo, fileRepo, mock(PlayHistoryRepository.class),
                mock(WsBroadcaster.class), mock(AssetWriter.class),
                mock(QueueItemRepository.class), mock(PlayerStateRepository.class),
                new AppProperties()
        );
    }

    @Test
    void listAdminSongsPassesNormalizedScrapeStatusAndResolvesScrapedFlag() {
        Song song1 = new Song();
        song1.setId(101L);
        song1.setTitle("已刮削歌曲");
        song1.setArtist("歌手A");

        Song song2 = new Song();
        song2.setId(102L);
        song2.setTitle("未刮削歌曲");
        song2.setArtist("歌手B");

        when(songRepo.searchAdminSongs(eq(""), eq(""), eq(""), eq("SCRAPED"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(song1, song2)));

        SongFile file1 = new SongFile();
        file1.setId(1L);
        file1.setSongId(101L);
        file1.setFilePath("/music/song1.mp4");
        file1.setValid(true);
        when(fileRepo.findBySongIdInAndValidTrueOrderByPriorityDesc(List.of(101L, 102L)))
                .thenReturn(List.of(file1));

        when(songRepo.findAppliedMatchSongIds(anyCollection())).thenReturn(List.of(101L));
        when(songRepo.findScrapedItemSongIds(anyCollection())).thenReturn(List.of());

        Page<AdminSongDto> page = adminService.listAdminSongs("", "", "", "scraped", 0, 20);

        verify(songRepo).searchAdminSongs(eq(""), eq(""), eq(""), eq("SCRAPED"), any(Pageable.class));
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).id()).isEqualTo(101L);
        assertThat(page.getContent().get(0).scraped()).isTrue();
        assertThat(page.getContent().get(1).id()).isEqualTo(102L);
        assertThat(page.getContent().get(1).scraped()).isFalse();
    }

    @Test
    void getAdminSongResolvesScrapedFlagTrueWhenScrapedItemExists() {
        Song song = new Song();
        song.setId(201L);
        song.setTitle("测试歌曲");
        song.setArtist("测试歌手");

        when(songRepo.findById(201L)).thenReturn(Optional.of(song));
        when(fileRepo.findBySongIdAndValidTrueOrderByPriorityDesc(201L)).thenReturn(List.of());
        when(songRepo.findAppliedMatchSongIds(List.of(201L))).thenReturn(List.of());
        when(songRepo.findScrapedItemSongIds(List.of(201L))).thenReturn(List.of(201L));

        AdminSongDto dto = adminService.getAdminSong(201L);
        assertThat(dto.scraped()).isTrue();
    }
}

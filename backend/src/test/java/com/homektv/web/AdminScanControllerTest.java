package com.homektv.web;

import com.homektv.library.AdminService;
import com.homektv.library.MediaImportService;
import com.homektv.library.SettingService;
import com.homektv.library.SongReparseService;
import com.homektv.library.LibraryScanService;
import com.homektv.library.LibraryWatchService;
import com.homektv.library.SongMergeService;
import com.homektv.library.TranscodeHardwareService;
import com.homektv.library.TranscodeService;
import com.homektv.web.dto.AdminSongDto;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdminScanControllerTest {

    @Test
    void listSongsDelegatesToAdminServiceWithScrapeStatus() {
        AdminService adminService = mock(AdminService.class);

        AdminScanController controller = new AdminScanController(
                mock(LibraryScanService.class), mock(LibraryWatchService.class),
                adminService, mock(MediaImportService.class), mock(SettingService.class),
                mock(TranscodeService.class), mock(TranscodeHardwareService.class),
                mock(SongReparseService.class), mock(SongMergeService.class)
        );

        AdminSongDto dto = new AdminSongDto(
                1L, "晴天", "周杰伦", "叶惠美", "2003",
                new String[]{"Sunny Day"}, "/covers/1.jpg", new String[0],
                "国语", "男", new String[]{"流行"}, "KTV_VIDEO",
                "line", 269000, 5, "/music/晴天.mp4", "COPIED", true
        );

        when(adminService.listAdminSongs("晴天", "KTV_VIDEO", "COPIED", "SCRAPED", 0, 20))
                .thenReturn(new PageImpl<>(List.of(dto)));

        Map<String, Object> result = controller.listSongs("晴天", "KTV_VIDEO", "COPIED", "SCRAPED", 0, 20);

        verify(adminService).listAdminSongs("晴天", "KTV_VIDEO", "COPIED", "SCRAPED", 0, 20);
        assertThat(result).containsEntry("total", 1L);
        assertThat(result.get("content")).isEqualTo(List.of(dto));
    }
}

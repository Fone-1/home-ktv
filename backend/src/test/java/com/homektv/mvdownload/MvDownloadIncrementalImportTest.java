package com.homektv.mvdownload;

import com.homektv.library.MediaImportService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MvDownloadIncrementalImportTest {

    @Test
    void incrementalScanDoesNotTriggerFullDirectoryScan() {
        MediaImportService importService = Mockito.mock(MediaImportService.class);
        Path file = Path.of("source-music", "downloads", "周杰伦 - 晴天 - 1.mp4");
        when(importService.scanSourceLibrary(file))
                .thenReturn(new MediaImportService.SourceScanResult(1, 1, 0, 0, 0, 0, 0));

        MediaImportService.SourceScanResult result = importService.scanSourceLibrary(file);

        assert result.copied() == 1;
        verify(importService).scanSourceLibrary(file);
        verify(importService, never()).scanSourceLibrary();
    }
}

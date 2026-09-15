package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.MediaImportRecord;
import com.homektv.domain.SongFile;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.repo.SongFileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaImportIncrementalScanTest {

    @TempDir Path temp;
    private Path sourceDir;
    private Path targetDir;
    private FFprobeService probe;
    private MediaImportRecordRepository importRepo;
    private SongFileRepository songFileRepo;
    private LibraryScanService scanService;
    private MediaImportService service;

    @BeforeEach
    void setUp() throws Exception {
        sourceDir = Files.createDirectories(temp.resolve("source"));
        targetDir = Files.createDirectories(temp.resolve("music"));
        AppProperties props = new AppProperties();
        props.setSourceLibraryPath(sourceDir.toString());
        props.setKtvLibraryPath(targetDir.toString());
        probe = mock(FFprobeService.class);
        importRepo = mock(MediaImportRecordRepository.class);
        songFileRepo = mock(SongFileRepository.class);
        scanService = mock(LibraryScanService.class);
        SettingService settingService = mock(SettingService.class);
        when(settingService.transcodePolicy()).thenReturn(new SettingService.TranscodePolicy(
                List.of("mp4", "m4v", "mkv"), List.of("h264", "hevc"), List.of("aac", "mp3"),
                false, "mkv", "h264", "aac", false));
        when(importRepo.findBySourcePath(anyString())).thenReturn(Optional.empty());
        when(importRepo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(scanService.ingestLibraryFile(any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new LibraryScanService.IngestResult(true, 1L, 2L));
        SongFile songFile = new SongFile();
        songFile.setId(2L);
        when(songFileRepo.findById(2L)).thenReturn(Optional.of(songFile));
        service = new MediaImportService(props, probe, new FileHashService(), importRepo, songFileRepo,
                scanService, settingService, mock(MediaTranscoder.class));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void scanSourceLibraryWithTargetFileOnlyImportsThatFile() throws Exception {
        Path wanted = sourceDir.resolve("周杰伦 - 晴天.mp4");
        Path other = sourceDir.resolve("林俊杰 - 江南.mp4");
        Files.writeString(wanted, "wanted-media");
        Files.writeString(other, "other-media");
        when(probe.probe(wanted)).thenReturn(new MediaProbe(1000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary(wanted);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.copied()).isEqualTo(1);
        assertThat(wanted).doesNotExist();
        assertThat(other).exists();
        verify(scanService).ingestLibraryFile(any(), eq(wanted), anyString(), anyString(), eq(false));
        verify(probe, never()).probe(other);
    }
}

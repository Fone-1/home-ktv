package com.homektv.dualtrack;

import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.library.MediaClassifier;
import com.homektv.library.SettingService;
import com.homektv.media.AudioStreamInfo;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import com.homektv.web.dto.DualTrackConvertRequest;
import com.homektv.web.dto.DualTrackConvertResultDto;
import com.homektv.web.dto.DualTrackProgressDto;
import com.homektv.ws.WsBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class DualTrackConvertServiceTest {

    private SongRepository songRepo;
    private SongFileRepository fileRepo;
    private SettingService settingService;
    private FFprobeService ffprobeService;
    private DspVocalSeparationEngine dspEngine;
    private RemoteAiVocalSeparationEngine remoteAiEngine;
    private DualTrackRemuxer remuxer;
    private WsBroadcaster wsBroadcaster;
    private com.homektv.repo.DualTrackTaskRepository taskRepo;

    private DualTrackConvertService service;

    @BeforeEach
    void setUp() {
        songRepo = mock(SongRepository.class);
        fileRepo = mock(SongFileRepository.class);
        settingService = mock(SettingService.class);
        ffprobeService = mock(FFprobeService.class);
        dspEngine = mock(DspVocalSeparationEngine.class);
        remoteAiEngine = mock(RemoteAiVocalSeparationEngine.class);
        remuxer = mock(DualTrackRemuxer.class);
        wsBroadcaster = mock(WsBroadcaster.class);
        taskRepo = mock(com.homektv.repo.DualTrackTaskRepository.class);
        when(taskRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepo.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of());

        when(settingService.dualTrackPolicy()).thenReturn(
                new SettingService.DualTrackPolicy("DSP", "", "", 1, false, "192k")
        );

        service = new DualTrackConvertService(
                songRepo, fileRepo, settingService, ffprobeService,
                dspEngine, remoteAiEngine, remuxer, wsBroadcaster, taskRepo
        );
    }

    @Test
    void testAlreadyDualTrackReturnsDirectly() throws Exception {
        Song song = new Song();
        song.setTitle("测试歌曲");
        song.setMediaType(MediaClassifier.KTV_VIDEO);
        song.setHasVocalTrack(true);

        SongFile file = new SongFile();
        file.setFilePath("test.mkv");
        file.setAudioTracks(2);
        file.setVocalTrackIndex(1);

        Path tempFile = Files.createTempFile("test-already-dual-", ".mkv");
        file.setFilePath(tempFile.toAbsolutePath().toString());

        try {
            when(songRepo.findById(101L)).thenReturn(Optional.of(song));
            when(fileRepo.findBySongIdAndValidTrueOrderByPriorityDesc(101L)).thenReturn(List.of(file));

            DualTrackConvertResultDto result = service.doConvert(101L, "DSP", false, "mkv");
            assertEquals("SUCCESS", result.status());
            assertTrue(result.message().contains("已具备双音轨伴奏"));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testMonoAudioThrowsExceptionInDspMode() throws Exception {
        Song song = new Song();
        song.setTitle("单声道老歌");
        song.setMediaType(MediaClassifier.MV);
        song.setHasVocalTrack(false);

        Path tempFile = Files.createTempFile("test-mono-", ".mp4");
        SongFile file = new SongFile();
        file.setFilePath(tempFile.toAbsolutePath().toString());
        file.setAudioTracks(1);

        try {
            when(songRepo.findById(102L)).thenReturn(Optional.of(song));
            when(fileRepo.findBySongIdAndValidTrueOrderByPriorityDesc(102L)).thenReturn(List.of(file));

            AudioStreamInfo monoStream = new AudioStreamInfo(0, "Audio", "und", 1, false, true);
            MediaProbe probe = new MediaProbe(120000L, 1, 0, true, "1920x1080", List.of(monoStream), "h264", "aac", "单声道老歌", "未知", "chi");
            when(ffprobeService.probe(tempFile)).thenReturn(probe);

            ApiException ex = assertThrows(ApiException.class, () -> service.doConvert(102L, "DSP", false, "mkv"));
            assertEquals("MONO_AUDIO_UNSUPPORTED", ex.getCode());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testInitialProgress() {
        DualTrackProgressDto progress = service.getProgress();
        assertNotNull(progress);
        assertFalse(progress.running());
        assertEquals(0, progress.progress());
    }

    @Test
    void testRollbackWithoutBackupThrowsException() {
        Song song = new Song();
        song.setTitle("无备份歌曲");

        SongFile file = new SongFile();
        file.setFilePath("non_existent_file.mkv");

        when(songRepo.findById(201L)).thenReturn(Optional.of(song));
        when(fileRepo.findBySongIdAndValidTrueOrderByPriorityDesc(201L)).thenReturn(List.of(file));

        ApiException ex = assertThrows(ApiException.class, () -> service.rollback(201L));
        assertEquals("BACKUP_NOT_FOUND", ex.getCode());
    }
}

package com.homektv.library;

import com.homektv.support.FakeExecutable;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaTranscoderTest {

    @TempDir Path temp;

    @Test
    void removesPartialOutputWhenFfmpegFails() throws Exception {
        // 假 ffmpeg 会先写出部分产物再以 1 退出，验证失败时不会留下半成品
        Path ffmpeg = FakeExecutable.failingWriter(temp, "fake-ffmpeg");
        Path source = temp.resolve("source.mpg");
        Path output = temp.resolve("output.mkv");
        Files.writeString(source, "source");
        TranscodeHardwareService hardware = new TranscodeHardwareService(
                temp.resolve("dri").toString(), temp.resolve("sys").toString(), temp.resolve("mpp").toString(), ffmpeg.toString());
        MediaTranscoder transcoder = new MediaTranscoder(hardware, ffmpeg.toString());
        SettingService.TranscodePolicy policy = new SettingService.TranscodePolicy(
                List.of("mkv"), List.of("h264"), List.of("aac"), false,
                "mkv", "h264", "aac", false);

        assertThatThrownBy(() -> transcoder.transcode(source, output, policy, true))
                .isInstanceOf(ApiException.class);
        assertThat(output).doesNotExist();
    }
}

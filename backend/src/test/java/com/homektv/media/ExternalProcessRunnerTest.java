package com.homektv.media;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 统一外部进程执行器的行为验证（阶段三任务 3.1）。
 *
 * 用真实 ffmpeg 构造慢进程与大量输出，覆盖超时、取消、线程中断、输出限长与命令脱敏。
 * 与 {@code FFprobeRealProbeTest} 一致：缺少 ffmpeg/ffprobe 时自动跳过。
 */
class ExternalProcessRunnerTest {

    @TempDir Path temp;

    private static boolean toolsAvailable;

    @BeforeAll
    static void checkTools() {
        toolsAvailable = commandExists("ffmpeg") && commandExists("ffprobe");
    }

    /** 生成一个真实可探测的音频文件。 */
    private Path sampleAudio() throws Exception {
        Path wav = temp.resolve("sample.wav");
        ExternalProcessRunner.Result result = ExternalProcessRunner.run(
                "生成测试音频",
                List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                        "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                        wav.toString()),
                wav, Duration.ofSeconds(60));
        assertThat(result.ok()).isTrue();
        return wav;
    }

    /** 以实时速度播放长音频：进程会持续运行到超时/取消，用于验证强制结束。 */
    private List<String> slowCommand() {
        return List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-re",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=300", "-f", "null", "-");
    }

    @Test
    void timeoutKillsProcessAndReportsTimedOut() throws Exception {
        assumeTrue(toolsAvailable, "ffmpeg 不可用，跳过超时测试");
        long started = System.nanoTime();
        ExternalProcessRunner.Result result = ExternalProcessRunner.run(
                "超时用例", slowCommand(), null, Duration.ofSeconds(1));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.ok()).isFalse();
        assertThat(result.exitCode()).isEqualTo(-1);
        // 命令本身要跑 300 秒，必须已强制结束而不是等到自然退出
        assertThat(elapsedMs).isLessThan(30_000L);
    }

    @Test
    void cancellationKillsProcessWithoutWaitingForTimeout() throws Exception {
        assumeTrue(toolsAvailable, "ffmpeg 不可用，跳过取消测试");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            cancelled.set(true);
        });
        canceller.start();

        long started = System.nanoTime();
        ExternalProcessRunner.Result result = ExternalProcessRunner.run(
                "取消用例", slowCommand(), null, Duration.ofSeconds(120), cancelled::get);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(result.cancelled()).isTrue();
        assertThat(result.ok()).isFalse();
        assertThat(elapsedMs).isLessThan(30_000L);
    }

    @Test
    void hugeOutputIsDrainedAndTruncatedToTail() throws Exception {
        assumeTrue(toolsAvailable, "ffprobe 不可用，跳过输出限长测试");
        Path wav = sampleAudio();
        // -v trace 会产生数十 KB 级日志；执行器必须持续消费并只保留末尾
        ExternalProcessRunner.Result result = ExternalProcessRunner.run(
                "大输出用例",
                List.of("ffprobe", "-v", "trace", "-i", wav.toString()),
                wav, Duration.ofSeconds(60));

        assertThat(result.stdout()).isNotEmpty();
        assertThat(result.stdout()).hasSizeLessThanOrEqualTo(8_000);
    }

    @Test
    void splitModeKeepsStdoutCleanForJsonParsing() throws Exception {
        assumeTrue(toolsAvailable, "ffprobe 不可用，跳过分流测试");
        Path wav = sampleAudio();
        ExternalProcessRunner.Result result = ExternalProcessRunner.runSplit(
                "分流用例",
                List.of("ffprobe", "-v", "error", "-print_format", "json",
                        "-show_format", "-show_streams", wav.toString()),
                wav, Duration.ofSeconds(30));

        assertThat(result.exitCode()).isZero();
        assertThat(result.ok()).isTrue();
        // stdout 必须是干净 JSON，否则 FFprobeService 解析会失败
        assertThat(result.stdout().trim()).startsWith("{");
        assertThat(result.stdout()).contains("\"format\"");
    }

    @Test
    void failureExitCodeAndDiagnosticsAreCaptured() throws Exception {
        assumeTrue(toolsAvailable, "ffprobe 不可用，跳过失败诊断测试");
        Path bogus = temp.resolve("not-a-media.bin");
        Files.writeString(bogus, "this is definitely not a media file");

        ExternalProcessRunner.Result result = ExternalProcessRunner.run(
                "失败诊断用例",
                List.of("ffprobe", "-v", "error", "-i", bogus.toString()),
                bogus, Duration.ofSeconds(30));

        assertThat(result.ok()).isFalse();
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.diagnostic()).isNotBlank();
    }

    @Test
    void missingExecutableSurfacesAsIOException() {
        IOException failure = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () ->
                ExternalProcessRunner.run("缺失程序", List.of("definitely-not-a-real-binary-xyz"),
                        null, Duration.ofSeconds(5)));
        assertThat(failure).isNotNull();
    }

    @Test
    void interruptedThreadDestroysProcess() throws Exception {
        assumeTrue(toolsAvailable, "ffmpeg 不可用，跳过线程中断测试");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean sawInterrupt = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            try {
                ExternalProcessRunner.run("中断用例", slowCommand(), null, Duration.ofSeconds(120));
            } catch (InterruptedException expected) {
                sawInterrupt.set(true);
            } catch (IOException ignored) {
                // 不在本用例关注范围
            } finally {
                finished.countDown();
            }
        });
        worker.start();
        Thread.sleep(600);
        worker.interrupt();

        assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(sawInterrupt.get()).isTrue();
    }

    @Test
    void maskedCommandHidesTokensAndSignedUrls() {
        List<String> command = new ArrayList<>(List.of(
                "ffmpeg", "-i", "input.mp4",
                "https://cdn.example.com/video.m4s?token=SECRET123&sign=abc&deadline=999999999999",
                "Authorization: Bearer SUPER-SECRET-TOKEN"));

        String masked = ExternalProcessRunner.maskedCommand(command);

        assertThat(masked).doesNotContain("SECRET123");
        assertThat(masked).doesNotContain("SUPER-SECRET-TOKEN");
        assertThat(masked).doesNotContain("sign=abc");
        assertThat(masked).contains("ffmpeg").contains("input.mp4").contains("[REDACTED]");
    }

    @Test
    void longQueryStringIsReducedToPathOnly() {
        String masked = ExternalProcessRunner.maskedCommand(List.of(
                "https://example.com/a.m4s?xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx=1"));
        assertThat(masked).isEqualTo("https://example.com/a.m4s?[REDACTED]");
    }

    @Test
    void shortQueryStringIsLeftIntact() {
        // 短查询串不包含凭据风险，保留原样便于排查
        String masked = ExternalProcessRunner.maskedCommand(List.of("ffmpeg", "-i", "a.m4s?p=1"));
        assertThat(masked).isEqualTo("ffmpeg -i a.m4s?p=1");
    }

    private static boolean commandExists(String command) {
        try {
            Process process = new ProcessBuilder(command, "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            return finished && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

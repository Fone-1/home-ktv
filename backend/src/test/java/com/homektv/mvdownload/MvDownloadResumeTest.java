package com.homektv.mvdownload;

import com.homektv.config.AppProperties;
import com.homektv.domain.MvDownloadTask;
import com.homektv.repo.MvDownloadTaskRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.ws.WsBroadcaster;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * MV 下载真实断点续传验证（阶段三任务 3.2）。
 *
 * 用本地 HTTP 服务器分别模拟「支持 Range（206）」「忽略 Range（200）」「响应被截断」三种服务端行为，
 * 验证：Range 请求头格式、200 时丢弃断点重下（不能拼接出损坏文件）、成功后原子重命名、失败时保留断点。
 */
class MvDownloadResumeTest {

    @TempDir Path temp;

    private HttpServer server;
    private String baseUrl;
    private final List<String> rangeHeaders = new ArrayList<>();
    private byte[] payload;
    /** 服务端是否支持 Range（false 时即使收到 Range 也返回 200 全量）。 */
    private volatile boolean supportsRange = true;
    /** 声明长度与真实长度不一致，模拟网络中断。 */
    private volatile boolean truncateResponse = false;

    @BeforeEach
    void startServer() throws IOException {
        payload = new byte[256 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 251);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/media", this::handleMedia);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/media";
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private void handleMedia(HttpExchange exchange) throws IOException {
        String range = exchange.getRequestHeaders().getFirst("Range");
        String ifRange = exchange.getRequestHeaders().getFirst("If-Range");
        rangeHeaders.add(String.valueOf(range) + "|" + String.valueOf(ifRange));
        int start = 0;
        boolean partial = false;
        if (range != null && supportsRange) {
            // "bytes=<n>-"
            String digits = range.replace("bytes=", "").replace("-", "").trim();
            start = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            partial = start > 0;
        }
        int length = payload.length - start;
        if (truncateResponse) {
            // 故意声明完整长度但只写一半，触发"下载不完整"
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(length + 4096));
        } else {
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(length));
        }
        exchange.getResponseHeaders().set("ETag", "\"etag-v1\"");
        exchange.getResponseHeaders().set("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT");
        exchange.sendResponseHeaders(partial ? 206 : 200, truncateResponse ? 0 : length);
        try (OutputStream out = exchange.getResponseBody()) {
            int writeLength = truncateResponse ? Math.max(0, length / 2) : length;
            out.write(payload, start, writeLength);
        }
    }

    private MvDownloadService newService() {
        MvDownloadTaskRepository taskRepo = Mockito.mock(MvDownloadTaskRepository.class);
        when(taskRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepo.findById(any())).thenReturn(Optional.empty());
        SongFileRepository songFileRepo = Mockito.mock(SongFileRepository.class);
        WsBroadcaster broadcaster = Mockito.mock(WsBroadcaster.class);
        AppProperties props = new AppProperties();
        props.setSourceLibraryPath(temp.toString());
        return new MvDownloadService(List.of(), taskRepo, songFileRepo, props,
                null, null, null, broadcaster, null, null,
                null, null, "ffmpeg");
    }

    private MvDownloadTask newTask() {
        MvDownloadTask task = new MvDownloadTask();
        task.setId(7L);
        task.setTitle("测试歌曲");
        task.setArtist("测试歌手");
        task.setProvider("NETEASE");
        task.setExternalId("x1");
        return task;
    }

    private String lastRange() {
        return rangeHeaders.get(rangeHeaders.size() - 1);
    }

    @Test
    void freshDownloadWritesFinalFileAtomicallyWithoutRangeHeader() throws Exception {
        MvDownloadService service = newService();
        Path target = temp.resolve("fresh.mp4");
        MvDownloadTask task = newTask();

        service.downloadStreamToFile(baseUrl, null, target, task, 0, 90);

        assertThat(Files.readAllBytes(target)).isEqualTo(payload);
        assertThat(Files.exists(MvDownloadService.partFileOf(target))).isFalse();
        assertThat(lastRange()).startsWith("null");
        assertThat(task.getResumeState()).isEqualTo("FRESH");
        assertThat(task.getEtag()).isEqualTo("\"etag-v1\"");
        assertThat(task.getLastModified()).isNotBlank();
    }

    @Test
    void resumeContinuesFromExistingPartWithRangeAnd206() throws Exception {
        MvDownloadService service = newService();
        Path target = temp.resolve("resume.mp4");
        Path part = MvDownloadService.partFileOf(target);
        int alreadyDownloaded = 100 * 1024;
        Files.write(part, java.util.Arrays.copyOf(payload, alreadyDownloaded));
        MvDownloadTask task = newTask();
        task.setDownloadedBytes(alreadyDownloaded);
        task.setEtag("\"etag-v1\"");

        service.downloadStreamToFile(baseUrl, null, target, task, 0, 90);

        // 必须带上断点位置与 If-Range 校验
        assertThat(lastRange()).startsWith("bytes=" + alreadyDownloaded + "-");
        assertThat(lastRange()).contains("\"etag-v1\"");
        // 最终文件必须是完整且正确的（前缀来自断点，后缀来自续传）
        assertThat(Files.readAllBytes(target)).isEqualTo(payload);
        assertThat(Files.exists(part)).isFalse();
        assertThat(task.getResumeState()).isEqualTo("RESUMED");
    }

    @Test
    void serverIgnoringRangeRestartsFromScratchWithoutCorruption() throws Exception {
        supportsRange = false; // 服务端忽略 Range，始终返回 200 全量
        MvDownloadService service = newService();
        Path target = temp.resolve("restart.mp4");
        Path part = MvDownloadService.partFileOf(target);
        Files.write(part, java.util.Arrays.copyOf(payload, 100 * 1024));
        MvDownloadTask task = newTask();
        task.setDownloadedBytes(100 * 1024);

        service.downloadStreamToFile(baseUrl, null, target, task, 0, 90);

        // 关键：不能把全量响应追加到旧断点后（否则文件会变成 断点+全量 的损坏内容）
        assertThat(Files.readAllBytes(target)).isEqualTo(payload);
        assertThat(service).isNotNull();
        assertThat(task.getResumeState()).isEqualTo("RESTARTED");
    }

    @Test
    void truncatedResponseKeepsPartFileForLaterResume() throws Exception {
        truncateResponse = true;
        MvDownloadService service = newService();
        Path target = temp.resolve("truncated.mp4");
        MvDownloadTask task = newTask();

        assertThatThrownBy(() -> service.downloadStreamToFile(baseUrl, null, target, task, 0, 90))
                .isInstanceOf(Exception.class);

        // 失败时不能产出"看起来完整"的成品，断点必须保留供下次续传
        assertThat(Files.exists(target)).isFalse();
        assertThat(Files.exists(MvDownloadService.partFileOf(target))).isTrue();
    }

    @Test
    void partFileNamingIsDerivedFromTargetFile() {
        Path target = Path.of("/music", "downloads", "a - b - 1.mp4");
        assertThat(MvDownloadService.partFileOf(target).getFileName().toString())
                .isEqualTo("a - b - 1.mp4.part");
    }

    @Test
    void rangeWithoutEtagUsesLastModifiedAsValidator() throws Exception {
        MvDownloadService service = newService();
        Path target = temp.resolve("ifrange.mp4");
        Path part = MvDownloadService.partFileOf(target);
        Files.write(part, java.util.Arrays.copyOf(payload, 4096));
        MvDownloadTask task = newTask();
        task.setLastModified("Wed, 21 Oct 2026 07:28:00 GMT");

        service.downloadStreamToFile(baseUrl, null, target, task, 0, 90);

        // ETag 缺失时退化为 If-Range: Last-Modified
        assertThat(lastRange()).contains("Wed, 21 Oct 2026 07:28:00 GMT");
        assertThat(Files.readAllBytes(target)).isEqualTo(payload);
    }

    @Test
    void etagIsRecordedForSubsequentValidation() throws Exception {
        MvDownloadService service = newService();
        Path target = temp.resolve("etag.mp4");
        MvDownloadTask task = newTask();

        service.downloadStreamToFile(baseUrl, null, target, task, 0, 90);

        assertThat(task.getEtag()).isEqualTo("\"etag-v1\"");
        assertThat(new String(payload, 0, 4, StandardCharsets.UTF_8)).isNotEmpty();
    }
}

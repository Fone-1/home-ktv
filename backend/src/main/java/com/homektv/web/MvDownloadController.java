package com.homektv.web;

import com.homektv.mvdownload.MvDownloadService;
import com.homektv.mvdownload.MvProvider;
import com.homektv.mvdownload.MvSearchItem;
import com.homektv.web.dto.BilibiliPartDto;
import com.homektv.web.dto.MvDownloadSubmitRequest;
import com.homektv.web.dto.MvDownloadTaskDto;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MV 在线搜索与下载控制器。
 * 提供跨平台 (网易云/B站) MV 检索、提交异步下载、进度监控及取消操作。
 */
@RestController
@RequestMapping("/api/mv")
public class MvDownloadController {

    private final MvDownloadService downloadService;

    public MvDownloadController(MvDownloadService downloadService) {
        this.downloadService = downloadService;
    }

    /**
     * 搜索在线 MV
     */
    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "provider", required = false) String providerStr,
            @RequestParam(value = "limit", defaultValue = "20") int limit
    ) {
        MvProvider provider = null;
        if (providerStr != null && !providerStr.isBlank() && !"ALL".equalsIgnoreCase(providerStr)) {
            provider = MvProvider.parse(providerStr);
        }
        List<MvSearchItem> items = downloadService.search(keyword, provider, limit);
        return Map.of(
                "keyword", keyword,
                "provider", provider != null ? provider.name() : "ALL",
                "total", items.size(),
                "items", items
        );
    }

    /**
     * 提交下载任务
     */
    /**
     * 获取视频分集/分P列表（针对 B 站等合集资源）
     */
    @GetMapping("/parts")
    public Map<String, Object> parts(
            @RequestParam("provider") String providerStr,
            @RequestParam("externalId") String externalId
    ) {
        MvProvider provider = MvProvider.parse(providerStr);
        List<BilibiliPartDto> parts = downloadService.getParts(provider, externalId);
        return Map.of(
                "provider", provider.name(),
                "externalId", externalId,
                "totalParts", parts.size(),
                "parts", parts
        );
    }

    /**
     * 批量提交下载任务（支持合集中勾选的多集同时下载）
     */
    @PostMapping("/batch-download")
    public List<MvDownloadTaskDto> batchDownload(@RequestBody List<MvDownloadSubmitRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        return requests.stream()
                .map(downloadService::submitDownload)
                .toList();
    }

    @PostMapping("/download")
    public MvDownloadTaskDto download(@RequestBody MvDownloadSubmitRequest request) {
        return downloadService.submitDownload(request);
    }

    /**
     * 分页查询下载任务，支持状态和时间范围过滤。
     * 未传参数时默认返回活动任务和最近 7 天历史。
     */
    @GetMapping("/tasks")
    public Map<String, Object> tasks(@RequestParam(required = false) String status,
                                     @RequestParam(required = false) String since,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        java.time.OffsetDateTime from = null;
        if (since != null && !since.isBlank()) {
            from = java.time.OffsetDateTime.parse(since);
        }
        return downloadService.listTasksPage(status, from, page, size);
    }

    /**
     * 取消下载任务
     */
    @PostMapping("/tasks/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable("id") Long id) {
        downloadService.cancelTask(id);
        return Map.of("code", "OK", "message", "已取消下载任务");
    }

    /**
     * 重试下载任务
     */
    @PostMapping("/tasks/{id}/retry")
    public Map<String, Object> retry(@PathVariable("id") Long id) {
        downloadService.retryTask(id);
        return Map.of("code", "OK", "message", "已重新提交下载任务");
    }

    /**
     * 删除任务记录
     */
    @DeleteMapping("/tasks/{id}")
    public Map<String, Object> delete(@PathVariable("id") Long id) {
        downloadService.deleteTask(id);
        return Map.of("code", "OK", "message", "已删除任务记录");
    }
}

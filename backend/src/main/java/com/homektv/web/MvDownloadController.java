package com.homektv.web;

import com.homektv.mvdownload.MvDownloadService;
import com.homektv.mvdownload.MvProvider;
import com.homektv.mvdownload.MvSearchItem;
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
    @PostMapping("/download")
    public MvDownloadTaskDto download(@RequestBody MvDownloadSubmitRequest request) {
        return downloadService.submitDownload(request);
    }

    /**
     * 查询所有下载任务
     */
    @GetMapping("/tasks")
    public List<MvDownloadTaskDto> tasks() {
        return downloadService.listTasks();
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

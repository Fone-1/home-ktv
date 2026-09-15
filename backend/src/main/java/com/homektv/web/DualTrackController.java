package com.homektv.web;

import com.homektv.dualtrack.DualTrackConvertService;
import com.homektv.web.dto.BatchDualTrackConvertRequest;
import com.homektv.web.dto.DualTrackConvertRequest;
import com.homektv.web.dto.DualTrackConvertResultDto;
import com.homektv.web.dto.DualTrackProgressDto;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 单轨转双轨伴奏 REST 控制器。
 * 提供单曲转换提交、批量排队、进度查询及原文件回滚接口。
 */
@RestController
@RequestMapping("/api/songs")
public class DualTrackController {

    private final DualTrackConvertService convertService;

    public DualTrackController(DualTrackConvertService convertService) {
        this.convertService = convertService;
    }

    /**
     * 触发单曲转双轨伴奏。
     */
    @PostMapping("/{id}/convert-dual-track")
    public Map<String, Object> convertSingle(
            @PathVariable("id") Long id,
            @RequestBody(required = false) DualTrackConvertRequest request
    ) {
        DualTrackConvertResultDto result = convertService.submitSingleConversion(id, request);
        return Map.of(
                "code", "OK",
                "data", Map.of(
                        "songId", result.songId(),
                        "status", result.status(),
                        "message", result.message()
                )
        );
    }

    /**
     * 批量转换单轨歌曲。
     */
    @PostMapping("/batch-convert-dual-track")
    public Map<String, Object> convertBatch(
            @RequestBody BatchDualTrackConvertRequest request
    ) {
        Map<String, Object> batchResult = convertService.submitBatchConversion(request);
        return Map.of(
                "code", "OK",
                "data", batchResult
        );
    }

    /**
     * 查询转换任务进度（批次维度，兼容原有批量弹窗）。
     */
    @GetMapping("/convert-progress")
    public Map<String, Object> progress() {
        DualTrackProgressDto progress = convertService.getProgress();
        return Map.of(
                "code", "OK",
                "data", progress
        );
    }

    /**
     * 查询双轨转换任务列表：单曲与批次任务各自独立，含状态与失败原因。
     */
    @GetMapping("/convert-tasks")
    public Map<String, Object> tasks(@RequestParam(defaultValue = "50") int limit) {
        return Map.of(
                "code", "OK",
                "data", convertService.listTasks(limit)
        );
    }

    /**
     * 取消排队中或运行中的双轨转换任务。
     */
    @PostMapping("/convert-tasks/{taskId}/cancel")
    public Map<String, Object> cancelTask(@PathVariable("taskId") Long taskId) {
        return Map.of("code", "OK", "data", convertService.cancelTask(taskId));
    }

    /**
     * 重试失败或已取消的双轨转换任务。
     */
    @PostMapping("/convert-tasks/{taskId}/retry")
    public Map<String, Object> retryTask(@PathVariable("taskId") Long taskId) {
        return Map.of("code", "OK", "data", convertService.retryTask(taskId));
    }

    /**
     * 回滚至原始单轨文件。
     */
    @PostMapping("/{id}/rollback-dual-track")
    public Map<String, Object> rollback(@PathVariable("id") Long id) throws Exception {
        DualTrackConvertResultDto result = convertService.rollback(id);
        return Map.of(
                "code", "OK",
                "data", Map.of(
                        "songId", result.songId(),
                        "status", result.status(),
                        "message", result.message()
                )
        );
    }
}

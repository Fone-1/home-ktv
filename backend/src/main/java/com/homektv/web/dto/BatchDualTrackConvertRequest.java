package com.homektv.web.dto;

import java.util.List;

/**
 * 批量转双轨伴奏请求体。
 *
 * @param songIds     待转换的歌曲 ID 列表
 * @param mode        转换模式: "DSP" 或 "AI"
 * @param concurrency 后台并发数 (1~3)
 */
public record BatchDualTrackConvertRequest(
        List<Long> songIds,
        String mode,
        Integer concurrency
) {}

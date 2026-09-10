package com.homektv.mvdownload;

import java.time.Duration;
import java.util.List;

/**
 * 在线 MV 检索与直链提取提供者 SPI 接口。
 */
public interface MvSearchProvider {

    MvProvider provider();

    List<MvSearchItem> search(String keyword, int limit, Duration timeout);

    MvStreamInfo resolveStream(String externalId, String desiredResolution, Duration timeout);
}

package com.homektv.library;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 待机轮播内容短 TTL 缓存（阶段二任务 2.2）。
 *
 * 待机内容接口会被 TV 长期高频轮询，热门/新歌/混合来源每次都要跑排行聚合查询；
 * 短 TTL（15 秒）+ 事件失效在「几乎实时」与「数据库零压力」之间取平衡：
 * - 歌曲入库（LibraryScanService 新增歌曲）→ evict
 * - 播放完成写历史（PlaybackService.markCurrent）→ evict（热门排行会变）
 * - 设置变更（SettingService.putAll，待机来源/自定义歌曲/文案）→ evict
 */
@Component
public class StandbyContentCache {

    private static final Duration TTL = Duration.ofSeconds(15);

    private record Entry(Map<String, Object> content, Instant expiresAt) {}

    private final AtomicReference<Entry> entry = new AtomicReference<>();

    /** 读取缓存；未命中或已过期返回 null，由调用方重新计算并 put。 */
    public Map<String, Object> get() {
        Entry current = entry.get();
        if (current == null || Instant.now().isAfter(current.expiresAt())) return null;
        return current.content();
    }

    public void put(Map<String, Object> content) {
        entry.set(new Entry(content, Instant.now().plus(TTL)));
    }

    /** 失效缓存。调用频率低且无副作用，任何影响待机内容的事件都可以直接调用。 */
    public void evict() {
        entry.set(null);
    }
}

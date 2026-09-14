-- 为刮削任务项表增加 (song_id, status) 复合索引，优化曲库管理分页查询刮削状态过滤性能
CREATE INDEX IF NOT EXISTS idx_metadata_scrape_items_song_status ON music_metadata_scrape_items(song_id, status);

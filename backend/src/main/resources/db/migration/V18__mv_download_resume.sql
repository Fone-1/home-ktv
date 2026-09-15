-- 阶段三任务 3.2：MV 下载支持真实断点续传。
-- etag / last_modified 用于校验远端文件是否变化（变化则放弃断点重新全量下载）；
-- resume_state 记录本次任务的下载起点（FRESH/RESUMED/RESTARTED），便于用户理解任务状态；
-- retry_count 记录网络重试次数。

ALTER TABLE mv_download_tasks
    ADD COLUMN IF NOT EXISTS etag VARCHAR(256),
    ADD COLUMN IF NOT EXISTS last_modified VARCHAR(128),
    ADD COLUMN IF NOT EXISTS resume_state VARCHAR(32),
    ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN mv_download_tasks.etag IS '远端 ETag，用于断点续传时校验文件是否变化';
COMMENT ON COLUMN mv_download_tasks.last_modified IS '远端 Last-Modified，ETag 缺失时的断点校验依据';
COMMENT ON COLUMN mv_download_tasks.resume_state IS 'FRESH 全新下载 / RESUMED 断点续传 / RESTARTED 断点失效后重下';
COMMENT ON COLUMN mv_download_tasks.retry_count IS '网络失败自动重试次数';

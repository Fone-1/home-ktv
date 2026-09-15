-- 阶段三任务 3.3：双轨转换任务持久化 + 有界并发。
-- 单曲任务（origin=SINGLE）与批次任务（origin=BATCH, batch_id 相同）各自独立记录进度与状态，
-- 不再共用一个无法区分的全局进度；服务重启后 QUEUED/RUNNING 会被标记为中断失败，可手动重试。

CREATE TABLE IF NOT EXISTS dual_track_tasks (
    id            BIGSERIAL PRIMARY KEY,
    song_id       BIGINT NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
    title         VARCHAR(255) NOT NULL DEFAULT '',
    origin        VARCHAR(16) NOT NULL DEFAULT 'SINGLE',   -- SINGLE / BATCH
    batch_id      VARCHAR(40),                             -- 批次任务共享；单曲为 NULL
    engine        VARCHAR(16) NOT NULL DEFAULT 'DSP',      -- DSP / LOCAL_AI / REMOTE_AI
    status        VARCHAR(16) NOT NULL DEFAULT 'QUEUED',   -- QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED
    progress      INT NOT NULL DEFAULT 0,                  -- 0 ~ 100
    error_message TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_dual_track_tasks_status ON dual_track_tasks(status);
CREATE INDEX IF NOT EXISTS idx_dual_track_tasks_batch ON dual_track_tasks(batch_id);
CREATE INDEX IF NOT EXISTS idx_dual_track_tasks_created ON dual_track_tasks(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_dual_track_tasks_song ON dual_track_tasks(song_id, created_at DESC);

COMMENT ON COLUMN dual_track_tasks.origin IS 'SINGLE 单曲提交 / BATCH 批量提交';
COMMENT ON COLUMN dual_track_tasks.status IS 'QUEUED 排队 / RUNNING 转换中 / COMPLETED 完成 / FAILED 失败 / CANCELLED 已取消';

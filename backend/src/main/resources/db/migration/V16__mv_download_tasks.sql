-- Persistent MV download tasks for NetEase and Bilibili online videos.

CREATE TABLE IF NOT EXISTS mv_download_tasks (
    id                      BIGSERIAL PRIMARY KEY,
    title                   VARCHAR(255) NOT NULL,
    artist                  VARCHAR(255) NOT NULL DEFAULT '未知歌手',
    provider                VARCHAR(32) NOT NULL,            -- NETEASE, BILIBILI, EXTERNAL
    external_id             VARCHAR(128) NOT NULL,
    cover_url               VARCHAR(1024),
    duration_ms             INT NOT NULL DEFAULT 0,
    resolution              VARCHAR(32) DEFAULT '1080p',
    status                  VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING, DOWNLOADING, MERGING, IMPORTING, COMPLETED, FAILED, CANCELLED
    progress                INT NOT NULL DEFAULT 0,         -- 0 ~ 100
    downloaded_bytes        BIGINT NOT NULL DEFAULT 0,
    total_bytes             BIGINT NOT NULL DEFAULT 0,
    speed_bps               BIGINT NOT NULL DEFAULT 0,
    target_file_path        VARCHAR(1024),
    song_id                 BIGINT REFERENCES songs(id) ON DELETE SET NULL,
    auto_convert_dual_track BOOLEAN NOT NULL DEFAULT FALSE,
    error_message           TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_mv_download_status ON mv_download_tasks(status);
CREATE INDEX IF NOT EXISTS idx_mv_download_provider_ext_id ON mv_download_tasks(provider, external_id);
CREATE INDEX IF NOT EXISTS idx_mv_download_created_at ON mv_download_tasks(created_at DESC);

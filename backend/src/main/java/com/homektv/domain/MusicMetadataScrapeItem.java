package com.homektv.domain;

import jakarta.persistence.*;

/**
 * 歌曲元数据刮削任务项实体，对应 music_metadata_scrape_items 表。
 *
 * Song metadata scrape task item entity, corresponding to music_metadata_scrape_items table.
 */
@Entity
@Table(name = "music_metadata_scrape_items")
public class MusicMetadataScrapeItem {

    /** 主键ID。 / Primary key ID. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属刮削批次ID。 / Batch ID. */
    @Column(name = "batch_id", nullable = false)
    private String batchId;

    /** 关联的歌曲ID。 / Associated song ID. */
    @Column(name = "song_id")
    private Long songId;

    /** 任务状态：PENDING / PROCESSING / AUTO_APPLIED / REVIEW / MANUAL_APPLIED / FAILED。 / Item status. */
    @Column(nullable = false)
    private String status = "PENDING";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public Long getSongId() { return songId; }
    public void setSongId(Long songId) { this.songId = songId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}

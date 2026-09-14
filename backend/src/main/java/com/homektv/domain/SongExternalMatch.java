package com.homektv.domain;

import jakarta.persistence.*;

/**
 * 歌曲外部元数据匹配记录实体，对应 song_external_matches 表。
 *
 * Song external metadata match entity, corresponding to song_external_matches table.
 */
@Entity
@Table(name = "song_external_matches")
public class SongExternalMatch {

    /** 主键ID。 / Primary key ID. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的歌曲ID。 / Associated song ID. */
    @Column(name = "song_id", nullable = false)
    private Long songId;

    /** 音乐平台提供方（如 NETEASE、QQ、KUGOU）。 / Music provider name. */
    @Column(nullable = false)
    private String provider;

    /** 外部音轨ID。 / External track ID. */
    @Column(name = "external_id", nullable = false)
    private String externalId;

    /** 匹配状态：SUGGESTED / APPLIED / REJECTED。 / Match status. */
    @Column(nullable = false)
    private String status = "SUGGESTED";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSongId() { return songId; }
    public void setSongId(Long songId) { this.songId = songId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}

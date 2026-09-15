package com.homektv.repo;

import com.homektv.domain.Song;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Collection;
import java.util.List;

/**
 * 歌曲数据访问层，负责 {@link Song} 实体的数据库操作。
 *
 * Song data access layer, responsible for database operations on the {@link Song} entity.
 */
public interface SongRepository extends JpaRepository<Song, Long> {

    Optional<Song> findByFingerprint(String fingerprint);

    List<Song> findTop10ByTitleIgnoreCase(String title);

    long countByMediaType(String mediaType);

    long countByStatus(String status);

    java.util.List<Song> findTop50ByOrderByCreatedAtDesc();

    org.springframework.data.domain.Page<Song> findByMediaType(String mediaType, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<Song> findByStatus(String status, org.springframework.data.domain.Pageable pageable);

    @Query("""
            SELECT song FROM Song song
            WHERE EXISTS (SELECT file.id FROM SongFile file WHERE file.songId = song.id AND file.valid = true)
              AND (:keyword = ''
                OR LOWER(song.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(song.artist) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(song.titlePy) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(song.titleInit) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(song.artistPy) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(song.artistInit) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:type = ''
                OR (:type = 'unrecognized' AND song.status = 'unrecognized')
                OR (:type <> 'unrecognized' AND song.mediaType = :type))
              AND (:source = ''
                OR (:source = 'UNKNOWN' AND EXISTS (
                    SELECT file.id FROM SongFile file
                    WHERE file.songId = song.id AND file.valid = true AND file.sourcePath IS NULL))
                OR (:source = 'COPIED' AND EXISTS (
                    SELECT file.id FROM SongFile file
                    WHERE file.songId = song.id AND file.valid = true
                      AND file.sourcePath IS NOT NULL AND file.transcodeRequired = false))
                OR (:source = 'TRANSCODED' AND EXISTS (
                    SELECT file.id FROM SongFile file
                    WHERE file.songId = song.id AND file.valid = true
                      AND file.sourcePath IS NOT NULL AND file.transcodeRequired = true)))
              AND (:scrapeStatus = ''
                OR (:scrapeStatus = 'SCRAPED' AND (
                    EXISTS (SELECT m.id FROM SongExternalMatch m WHERE m.songId = song.id AND m.status = 'APPLIED')
                    OR EXISTS (SELECT item.id FROM MusicMetadataScrapeItem item WHERE item.songId = song.id AND item.status IN ('AUTO_APPLIED', 'REVIEW', 'MANUAL_APPLIED', 'FAILED'))
                ))
                OR (:scrapeStatus = 'UNSCRAPED' AND NOT (
                    EXISTS (SELECT m.id FROM SongExternalMatch m WHERE m.songId = song.id AND m.status = 'APPLIED')
                    OR EXISTS (SELECT item.id FROM MusicMetadataScrapeItem item WHERE item.songId = song.id AND item.status IN ('AUTO_APPLIED', 'REVIEW', 'MANUAL_APPLIED', 'FAILED'))
                )))
            """)
    Page<Song> searchAdminSongs(@Param("keyword") String keyword,
                                @Param("type") String type,
                                @Param("source") String source,
                                @Param("scrapeStatus") String scrapeStatus,
                                Pageable pageable);

    @Query("SELECT DISTINCT m.songId FROM SongExternalMatch m WHERE m.songId IN :songIds AND m.status = 'APPLIED'")
    List<Long> findAppliedMatchSongIds(@Param("songIds") Collection<Long> songIds);

    @Query("SELECT DISTINCT item.songId FROM MusicMetadataScrapeItem item WHERE item.songId IN :songIds AND item.status IN ('AUTO_APPLIED', 'REVIEW', 'MANUAL_APPLIED', 'FAILED')")
    List<Long> findScrapedItemSongIds(@Param("songIds") Collection<Long> songIds);

    List<Song> findByStatus(String status);

    List<Song> findByStatusAndArtistIgnoreCase(String status, String artist);

    /**
     * 分类浏览歌曲：状态、歌手、性别、语种、媒体类型在 SQL 中过滤。
     * 演唱形式与标签仍由服务层按有效字段优先级过滤，避免 PostgreSQL text[] 的 JPQL MEMBER OF 兼容问题。
     */
    @Query("""
            SELECT song FROM Song song
            WHERE song.status = 'ok'
              AND (:artist = '' OR LOWER(song.artist) = LOWER(:artist))
              AND (:artistGender = '' OR LOWER(song.artistGender) = LOWER(:artistGender))
              AND (:language = '' OR LOWER(song.language) = LOWER(:language))
              AND (:mediaType = '' OR LOWER(song.mediaType) = LOWER(:mediaType))
            """)
    List<Song> findBrowseSongs(@Param("artist") String artist,
                               @Param("artistGender") String artistGender,
                               @Param("language") String language,
                               @Param("mediaType") String mediaType);

    @Query(value = """
            SELECT COALESCE(NULLIF(TRIM(s.artist), ''), '未知歌手') AS name,
                   COALESCE(NULLIF(UPPER(LEFT(NULLIF(TRIM(MAX(s.artist_init)), ''), 1)), ''), '#') AS initial,
                   COUNT(*) AS song_count,
                   COALESCE((
                       SELECT s2.artist_gender
                       FROM songs s2
                       WHERE s2.status = 'ok'
                         AND COALESCE(NULLIF(TRIM(s2.artist), ''), '未知歌手') = COALESCE(NULLIF(TRIM(s.artist), ''), '未知歌手')
                         AND s2.artist_gender IS NOT NULL
                         AND TRIM(s2.artist_gender) <> ''
                         AND s2.artist_gender <> '未知'
                       GROUP BY s2.artist_gender
                       ORDER BY COUNT(*) DESC, s2.artist_gender ASC
                       LIMIT 1
                   ), '未知') AS gender
            FROM songs s
            WHERE s.status = 'ok'
            GROUP BY COALESCE(NULLIF(TRIM(s.artist), ''), '未知歌手')
            ORDER BY song_count DESC, name ASC
            """, nativeQuery = true)
    List<Object[]> aggregateBrowseArtists();

    @Query(value = """
            SELECT language AS name, COUNT(*) AS song_count
            FROM songs
            WHERE status = 'ok' AND language IS NOT NULL AND TRIM(language) <> ''
            GROUP BY language
            ORDER BY song_count DESC, language ASC
            """, nativeQuery = true)
    List<Object[]> aggregateLanguages();

    @Query(value = """
            SELECT media_type AS name, COUNT(*) AS song_count
            FROM songs
            WHERE status = 'ok' AND media_type IS NOT NULL AND TRIM(media_type) <> ''
            GROUP BY media_type
            ORDER BY song_count DESC, media_type ASC
            """, nativeQuery = true)
    List<Object[]> aggregateMediaTypes();

    @Query(value = """
            SELECT tag AS name, COUNT(*) AS song_count FROM (
                SELECT UNNEST(tags) AS tag FROM songs WHERE status = 'ok'
                UNION ALL
                SELECT UNNEST(ai_genres) AS tag FROM songs WHERE status = 'ok'
                UNION ALL
                SELECT UNNEST(ai_themes) AS tag FROM songs WHERE status = 'ok'
            ) tags
            WHERE tag IS NOT NULL AND TRIM(tag) <> ''
            GROUP BY tag
            ORDER BY song_count DESC, tag ASC
            LIMIT 100
            """, nativeQuery = true)
    List<Object[]> aggregateBrowseTags();

    @Query(value = """
            SELECT COALESCE(NULLIF(TRIM(artist), ''), '未知歌手') AS name,
                   COUNT(*) AS song_count,
                   COALESCE((
                       SELECT s2.artist_gender
                       FROM songs s2
                       WHERE s2.status = 'ok'
                         AND COALESCE(NULLIF(TRIM(s2.artist), ''), '未知歌手') = COALESCE(NULLIF(TRIM(s.artist), ''), '未知歌手')
                         AND s2.artist_gender IS NOT NULL
                         AND TRIM(s2.artist_gender) <> ''
                         AND s2.artist_gender <> '未知'
                       GROUP BY s2.artist_gender
                       ORDER BY COUNT(*) DESC, s2.artist_gender ASC
                       LIMIT 1
                   ), '未知') AS gender,
                   BOOL_AND('artistGender' = ANY(COALESCE(metadata_locks, '{}')))
                       AND BOOL_AND(artist_gender IS NOT NULL AND TRIM(artist_gender) <> '' AND artist_gender <> '未知') AS reviewed
            FROM songs s
            WHERE status = 'ok'
              AND (:keyword = '' OR LOWER(COALESCE(NULLIF(TRIM(artist), ''), '未知歌手')) LIKE LOWER(CONCAT('%', :keyword, '%')))
            GROUP BY COALESCE(NULLIF(TRIM(artist), ''), '未知歌手')
            HAVING (:gender = '' OR COALESCE((
                       SELECT s2.artist_gender
                       FROM songs s2
                       WHERE s2.status = 'ok'
                         AND COALESCE(NULLIF(TRIM(s2.artist), ''), '未知歌手') = COALESCE(NULLIF(TRIM(s.artist), ''), '未知歌手')
                         AND s2.artist_gender IS NOT NULL
                         AND TRIM(s2.artist_gender) <> ''
                         AND s2.artist_gender <> '未知'
                       GROUP BY s2.artist_gender
                       ORDER BY COUNT(*) DESC, s2.artist_gender ASC
                       LIMIT 1
                   ), '未知') = :gender)
               AND (:reviewed IS NULL OR (
                       BOOL_AND('artistGender' = ANY(COALESCE(metadata_locks, '{}')))
                       AND BOOL_AND(artist_gender IS NOT NULL AND TRIM(artist_gender) <> '' AND artist_gender <> '未知')
                   ) = :reviewed)
            ORDER BY song_count DESC, name ASC
            """,
            countQuery = """
            SELECT COUNT(*) FROM (
                SELECT COALESCE(NULLIF(TRIM(artist), ''), '未知歌手') AS name
                FROM songs s
                WHERE status = 'ok'
                  AND (:keyword = '' OR LOWER(COALESCE(NULLIF(TRIM(artist), ''), '未知歌手')) LIKE LOWER(CONCAT('%', :keyword, '%')))
                GROUP BY COALESCE(NULLIF(TRIM(artist), ''), '未知歌手')
                HAVING (:gender = '' OR COALESCE((
                           SELECT s2.artist_gender
                           FROM songs s2
                           WHERE s2.status = 'ok'
                             AND COALESCE(NULLIF(TRIM(s2.artist), ''), '未知歌手') = COALESCE(NULLIF(TRIM(s.artist), ''), '未知歌手')
                             AND s2.artist_gender IS NOT NULL
                             AND TRIM(s2.artist_gender) <> ''
                             AND s2.artist_gender <> '未知'
                           GROUP BY s2.artist_gender
                           ORDER BY COUNT(*) DESC, s2.artist_gender ASC
                           LIMIT 1
                       ), '未知') = :gender)
                   AND (:reviewed IS NULL OR (
                           BOOL_AND('artistGender' = ANY(COALESCE(metadata_locks, '{}')))
                           AND BOOL_AND(artist_gender IS NOT NULL AND TRIM(artist_gender) <> '' AND artist_gender <> '未知')
                       ) = :reviewed)
            ) grouped
            """,
            nativeQuery = true)
    Page<Object[]> pageAdminArtists(@Param("keyword") String keyword,
                                    @Param("gender") String gender,
                                    @Param("reviewed") Boolean reviewed,
                                    Pageable pageable);
}

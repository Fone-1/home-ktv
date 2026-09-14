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
}

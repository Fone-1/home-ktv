package com.homektv.repo;

import com.homektv.domain.MvDownloadTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MvDownloadTaskRepository extends JpaRepository<MvDownloadTask, Long> {

    List<MvDownloadTask> findAllByOrderByCreatedAtDesc();

    List<MvDownloadTask> findByStatusOrderByCreatedAtAsc(String status);

    Optional<MvDownloadTask> findByProviderAndExternalId(String provider, String externalId);

    long countByStatus(String status);

    long countByStatusIn(Collection<String> statuses);

    Page<MvDownloadTask> findByStatusInOrderByCreatedAtDesc(Collection<String> statuses, Pageable pageable);

    Page<MvDownloadTask> findByStatusAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(String status, OffsetDateTime since, Pageable pageable);

    /**
     * 默认列表：活动任务不过期，历史任务只保留 since 之后的记录，避免无限加载全部任务。
     */
    @Query("""
            SELECT task FROM MvDownloadTask task
            WHERE task.status IN :activeStatuses OR task.createdAt >= :since
            ORDER BY task.createdAt DESC
            """)
    Page<MvDownloadTask> findRecentOrActive(@Param("activeStatuses") Collection<String> activeStatuses,
                                            @Param("since") OffsetDateTime since,
                                            Pageable pageable);
}

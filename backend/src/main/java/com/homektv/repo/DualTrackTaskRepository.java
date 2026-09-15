package com.homektv.repo;

import com.homektv.domain.DualTrackTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * 双轨转换任务数据访问（阶段三任务 3.3）。
 */
@Repository
public interface DualTrackTaskRepository extends JpaRepository<DualTrackTask, Long> {

    List<DualTrackTask> findByStatusInOrderByCreatedAtAsc(Collection<String> statuses);

    List<DualTrackTask> findByBatchIdOrderByCreatedAtAsc(String batchId);

    Page<DualTrackTask> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<DualTrackTask> findTop50ByOrderByCreatedAtDesc();

    List<DualTrackTask> findBySongIdOrderByCreatedAtDesc(Long songId);

    long countByStatusIn(Collection<String> statuses);
}

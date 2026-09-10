package com.homektv.repo;

import com.homektv.domain.MvDownloadTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MvDownloadTaskRepository extends JpaRepository<MvDownloadTask, Long> {

    List<MvDownloadTask> findAllByOrderByCreatedAtDesc();

    List<MvDownloadTask> findByStatusOrderByCreatedAtAsc(String status);

    Optional<MvDownloadTask> findByProviderAndExternalId(String provider, String externalId);

    long countByStatus(String status);
}

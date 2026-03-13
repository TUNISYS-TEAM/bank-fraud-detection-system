package com.fraud.backend.data.repository.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.ArchiveObject;
import com.fraud.backend.data.enums.Archive.ArchiveStatus;
import com.fraud.backend.data.enums.Archive.ArchiveType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ArchiveObjectRepository extends JpaRepository<ArchiveObject, Long> {

    Page<ArchiveObject> findByTypeOrderByCreatedAtDesc(ArchiveType type, Pageable pageable);

    Page<ArchiveObject> findByStatusOrderByCreatedAtDesc(ArchiveStatus status, Pageable pageable);

    Page<ArchiveObject> findByTypeAndStatusOrderByCreatedAtDesc(
            ArchiveType type,
            ArchiveStatus status,
            Pageable pageable
    );

    Page<ArchiveObject> findByStatusNotOrderByCreatedAtDesc(ArchiveStatus status, Pageable pageable);

    List<ArchiveObject> findByType(ArchiveType type);

    List<ArchiveObject> findByStatus(ArchiveStatus status);

    List<ArchiveObject> findByTypeAndStatus(ArchiveType type, ArchiveStatus status);

    boolean existsByTypeAndStartAtAndEndAt(ArchiveType type, LocalDateTime startAt, LocalDateTime endAt);

    List<ArchiveObject> findByTypeAndStartAtGreaterThanEqualAndEndAtLessThanEqual(
            ArchiveType type,
            LocalDateTime startAt,
            LocalDateTime endAt
    );

    // Dernière archive
    Optional<ArchiveObject> findTopByTypeOrderByEndAtDesc(ArchiveType type);

    Optional<ArchiveObject> findTopByTypeAndStatusOrderByEndAtDesc(ArchiveType type, ArchiveStatus status);

    // Retrouver une archive précise
    Optional<ArchiveObject> findByTypeAndStartAtAndEndAt(ArchiveType type, LocalDateTime startAt, LocalDateTime endAt);
}
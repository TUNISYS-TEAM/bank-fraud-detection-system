package com.fraud.backend.data.repository.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findBySessionId(Long sessionId, Pageable pageable);

    Page<AuditLog> findByAction(String action, Pageable pageable);

    Page<AuditLog> findByActionType(String actionType, Pageable pageable);

    Page<AuditLog> findByLevel(String level, Pageable pageable);

    Page<AuditLog> findByActionTimestampBetween(
            LocalDateTime start,
            LocalDateTime end,
            Pageable pageable);

    // Used when no filters
    Page<AuditLog> findAllByOrderByActionTimestampDesc(Pageable pageable);

    // Used when filters are present
    @Query("SELECT a FROM AuditLog a WHERE " +
            "(:actionType IS NULL OR a.actionType = :actionType) AND " +
            "(:level IS NULL OR a.level = :level) " +
            "ORDER BY a.actionTimestamp DESC")
    Page<AuditLog> findWithFilters(
            @Param("actionType") String actionType,
            @Param("level") String level,
            Pageable pageable);

    @Query("SELECT a FROM AuditLog a WHERE " +
            "(:actionType IS NULL OR a.actionType = :actionType) AND " +
            "(:level IS NULL OR a.level = :level) " +
            "ORDER BY a.actionTimestamp DESC")
    List<AuditLog> findWithFiltersNoPaging(
            @Param("actionType") String actionType,
            @Param("level") String level);
}
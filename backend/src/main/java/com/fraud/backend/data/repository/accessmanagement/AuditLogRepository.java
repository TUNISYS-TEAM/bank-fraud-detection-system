package com.fraud.backend.data.repository.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    // Logs par session
    Page<AuditLog> findBySessionId(Long sessionId, Pageable pageable);

    // Logs par action
    Page<AuditLog> findByAction(String action, Pageable pageable);

    // Logs par type d’action
    Page<AuditLog> findByActionType(String actionType, Pageable pageable);

    // Logs par niveau (INFO, WARN, ERROR…)
    Page<AuditLog> findByLevel(String level, Pageable pageable);

    // Logs entre deux dates
    Page<AuditLog> findByActionTimestampBetween(
            LocalDateTime start,
            LocalDateTime end,
            Pageable pageable
    );
}
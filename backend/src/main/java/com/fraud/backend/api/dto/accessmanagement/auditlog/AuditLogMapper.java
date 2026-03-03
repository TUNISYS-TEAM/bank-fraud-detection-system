package com.fraud.backend.api.dto.accessmanagement.auditlog;

import com.fraud.backend.data.entity.accessmanagement.AuditLog;
import org.springframework.stereotype.Component;

@Component
public class AuditLogMapper {

    public AuditLogResponse toResponse(AuditLog log) {
        return AuditLogResponse.builder()
                .id(log.getId())
                .sessionId(log.getSession() != null ? log.getSession().getSessionId() : null)
                .userEmail(log.getSession() != null && log.getSession().getUser() != null
                        ? log.getSession().getUser().getEmail()
                        : "system")
                .action(log.getAction())
                .actionType(log.getActionType())
                .actionTimestamp(log.getActionTimestamp())
                .level(log.getLevel())
                .build();
    }
}
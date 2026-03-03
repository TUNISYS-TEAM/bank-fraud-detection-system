package com.fraud.backend.api.dto.accessmanagement.auditlog;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AuditLogResponse {
    private Long id;
    private String sessionId;
    private String userEmail;
    private String action;
    private String actionType;
    private LocalDateTime actionTimestamp;
    private String level;
}

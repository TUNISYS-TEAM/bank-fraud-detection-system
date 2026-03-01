package com.fraud.backend.api.dto.accessmanagement.session.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SessionResponse {
    private Long id;
    private String sessionId;
    private String keycloakSessionId;
    private String ipAddress;
    private String userAgent;
    private LocalDateTime loginTime;
    private LocalDateTime lastActivity;
    private LocalDateTime logoutTime;
    private Boolean isActive;
    private String userEmail;
    private String userName;
}
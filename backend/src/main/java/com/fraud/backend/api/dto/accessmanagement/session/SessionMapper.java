package com.fraud.backend.api.dto.accessmanagement.session;

import com.fraud.backend.api.dto.accessmanagement.session.response.SessionResponse;
import com.fraud.backend.data.entity.accessmanagement.UserSession;
import org.springframework.stereotype.Component;

@Component
public class SessionMapper {

    public SessionResponse toResponse(UserSession session) {
        return SessionResponse.builder()
                .id(session.getId())
                .sessionId(session.getSessionId())
                .keycloakSessionId(session.getKeycloakSessionId())
                .ipAddress(session.getIpAddress())
                .userAgent(session.getUserAgent())
                .loginTime(session.getLoginTime())
                .lastActivity(session.getLastActivity())
                .logoutTime(session.getLogoutTime())
                .isActive(session.getIsActive())
                .userEmail(session.getUser().getEmail())
                .userName(session.getUser().getFirstName() + " " + session.getUser().getLastName())
                .build();
    }
}
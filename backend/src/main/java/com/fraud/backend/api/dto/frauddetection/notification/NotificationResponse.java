package com.fraud.backend.api.dto.frauddetection.notification;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class NotificationResponse {
    private Long id;
    private UUID notificationUuid;
    private String title;
    private String message;
    private String type;
    private LocalDateTime createdAt;
    private boolean isRead;
    private LocalDateTime readAt;
}

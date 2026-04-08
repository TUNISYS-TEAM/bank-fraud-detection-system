package com.fraud.backend.data.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent {
    private String eventId;          // UUID
    private String eventType;        // "NEW_MESSAGE", "VALIDATION_REQUEST", "MODEL_VALIDATED", etc.
    private Long userId;             // target user ID (who should receive the notification)
    private String userEmail;        // alternative target
    private Map<String, Object> payload; // flexible data (message details, model info, etc.)
    private Instant timestamp;
}
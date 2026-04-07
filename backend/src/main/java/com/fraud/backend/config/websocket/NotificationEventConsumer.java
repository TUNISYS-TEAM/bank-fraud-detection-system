package com.fraud.backend.config.websocket;

import com.fraud.backend.data.service.notification.NotificationService;
import com.fraud.backend.data.entity.accessmanagement.KeycloakMapping;
import com.fraud.backend.data.repository.accessmanagement.KeycloakMappingRepository;
import com.fraud.backend.kafka.NotificationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final KeycloakMappingRepository keycloakMappingRepository;
    private final NotificationService notificationService; // 🔔 ADD THIS

    @KafkaListener(topics = "notification-events", groupId = "fraud-backend-group")
    public void consume(NotificationEvent event) {
        log.info("CONSUMER RECEIVED: type={}, userId={}, userEmail={}",
            event.getEventType(), event.getUserId(), event.getUserEmail());

        // 🔔 SAVE TO DATABASE FIRST
        notificationService.saveNotification(event);

        // Then send WebSocket notification (existing code)
        String principalName = null;

        // Try to map email -> Keycloak ID
        if (event.getUserEmail() != null && !event.getUserEmail().isBlank()) {
            principalName = keycloakMappingRepository.findByEmail(event.getUserEmail())
                .map(KeycloakMapping::getKeycloakId)
                .orElse(null);
            if (principalName != null) {
                log.info("Mapped email '{}' to Keycloak ID: {}", event.getUserEmail(), principalName);
            } else {
                log.warn("No Keycloak mapping found for email: {}", event.getUserEmail());
            }
        }

        // Fallback to userId
        if (principalName == null && event.getUserId() != null) {
            principalName = keycloakMappingRepository.findByUserId(event.getUserId())
                .map(KeycloakMapping::getKeycloakId)
                .orElse(null);
            if (principalName != null) {
                log.info("Mapped userId '{}' to Keycloak ID: {}", event.getUserId(), principalName);
            }
        }

        // Final fallback
        if (principalName == null || principalName.isBlank()) {
            principalName = event.getUserEmail() != null
                ? event.getUserEmail()
                : String.valueOf(event.getUserId());
            log.warn("Using fallback principal: {}", principalName);
        }

        if (principalName == null || principalName.isBlank()) {
            log.error("No user identifier found in event: {}", event);
            return;
        }

        try {
            String payloadJson = objectMapper.writeValueAsString(event);
            messagingTemplate.convertAndSendToUser(
                principalName,
                "/queue/notifications",
                payloadJson
            );
            log.info("WebSocket notification sent to user: {}", principalName);
        } catch (Exception e) {
            log.error("Failed to send WebSocket message to user {}", principalName, e);
        }
    }
}
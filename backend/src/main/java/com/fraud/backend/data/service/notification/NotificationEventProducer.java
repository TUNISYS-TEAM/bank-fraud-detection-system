package com.fraud.backend.data.service.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.fraud.backend.data.event.NotificationEvent;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationEventProducer {

    private final KafkaTemplate<String, NotificationEvent> kafkaTemplate;

    @Value("${spring.kafka.producer.topic:notification-events}")
    private String topic;

    public void sendNotification(NotificationEvent event) {
        // Ensure eventId if not present
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID().toString());
        }
        kafkaTemplate.send(topic, event.getUserEmail(), event)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.debug("Notification sent: {} -> {}", event.getEventType(), event.getUserId());
                    } else {
                        log.error("Failed to send notification", ex);
                    }
                });
    }
}

package com.fraud.backend.data.service.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.fraud.backend.data.event.OtpEvent;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtpEventProducer {

    private final KafkaTemplate<String, OtpEvent> kafkaTemplate;

    @Value("${spring.kafka.producer.otp-topic:otp-events}")
    private String topic;

    public void sendOtpEvent(String email, String otp, String name, String eventType) {
        OtpEvent event = OtpEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .email(email)
                .otp(otp)
                .name(name)
                .eventType(eventType)
                .timestamp(Instant.now())
                .retryCount(0)
                .build();

        kafkaTemplate.send(topic, email, event)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("OTP event sent to Kafka for: {}", email);
                    } else {
                        log.error("Failed to send OTP event to Kafka for: {}", email, ex);
                    }
                });
    }
}

package com.fraud.backend.config.kafka;

import com.fraud.backend.data.event.OtpEvent;
import com.fraud.backend.data.service.accessmanagement.EmailService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OtpEmailConsumer {

    private final EmailService emailService;

    @KafkaListener(topics = "otp-events", groupId = "otp-email-group")
    public void consumeOtpEvent(OtpEvent event) {
        log.info("Consumed OTP event for: {}, type: {}", event.getEmail(), event.getEventType());
        
        try {
            emailService.sendOtpEmail(event.getEmail(), event.getOtp(), event.getName());
            log.info("OTP email sent successfully to: {}", event.getEmail());
        } catch (Exception e) {
            log.error("Failed to send OTP email to: {}", event.getEmail(), e);
            // Could implement retry logic here
            throw new RuntimeException("Failed to process OTP email", e);
        }
    }
}
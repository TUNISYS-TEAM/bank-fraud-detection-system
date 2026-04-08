package com.fraud.backend.data.service.accessmanagement.authverification;

import com.fraud.backend.data.service.notification.OtpEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private final OtpEventProducer otpEventProducer;
    private final OtpCacheService otpCacheService;

    private static final SecureRandom secureRandom = new SecureRandom();

    public void generateAndSendOtp(String email, String name, String eventType) {
        String otp = String.format("%06d", secureRandom.nextInt(1000000));
        
        // Store in cache FIRST before sending to Kafka
        otpCacheService.storeOtp(email, otp);
        log.info("OTP generated and stored in cache for: {}", email);
        
        // Send to Kafka for async email delivery
        otpEventProducer.sendOtpEvent(email, otp, name, eventType);
        log.info("OTP event queued to Kafka for: {}", email);
    }

    public boolean verifyOtp(String email, String otp) {
        return otpCacheService.verifyOtp(email, otp);
    }
    
    public boolean hasActiveOtp(String email) {
        return otpCacheService.hasActiveOtp(email);
    }
}
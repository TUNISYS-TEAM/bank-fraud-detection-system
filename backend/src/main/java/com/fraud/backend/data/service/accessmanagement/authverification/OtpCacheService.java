package com.fraud.backend.data.service.accessmanagement.authverification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class OtpCacheService {
    
    private final ConcurrentHashMap<String, OtpEntry> otpStore = new ConcurrentHashMap<>();
    
    public void storeOtp(String email, String otp) {
        otpStore.put(email, new OtpEntry(otp, System.currentTimeMillis()));
        log.debug("OTP stored in cache for email: {}", email);
    }
    
    public boolean verifyOtp(String email, String otp) {
        OtpEntry entry = otpStore.get(email);
        
        if (entry == null) {
            log.warn("No OTP found in cache for email: {}", email);
            return false;
        }
        
        // Check expiration (5 minutes)
        long currentTime = System.currentTimeMillis();
        if (currentTime - entry.timestamp > TimeUnit.MINUTES.toMillis(5)) {
            otpStore.remove(email);
            log.warn("OTP expired for email: {} (age: {} seconds)", 
                email, (currentTime - entry.timestamp) / 1000);
            return false;
        }
        
        boolean isValid = entry.otp.equals(otp);
        
        // Always remove OTP after verification attempt (prevents brute force)
        otpStore.remove(email);
        
        if (isValid) {
            log.info("OTP verified successfully for email: {}", email);
        } else {
            log.warn("Invalid OTP provided for email: {} (expected: {}, got: {})", 
                email, entry.otp, otp);
        }
        
        return isValid;
    }
    
    /**
     * Check if an OTP exists for the given email (useful for resend prevention)
     */
    public boolean hasActiveOtp(String email) {
        OtpEntry entry = otpStore.get(email);
        if (entry == null) {
            return false;
        }
        
        // Check if expired
        if (System.currentTimeMillis() - entry.timestamp > TimeUnit.MINUTES.toMillis(5)) {
            otpStore.remove(email);
            return false;
        }
        
        return true;
    }
    
    private static class OtpEntry {
        final String otp;
        final long timestamp;
        
        OtpEntry(String otp, long timestamp) {
            this.otp = otp;
            this.timestamp = timestamp;
        }
    }
}
package com.fraud.backend.data.service.accessmanagement;

import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class OtpCacheService {
    
    private final ConcurrentHashMap<String, OtpEntry> otpStore = new ConcurrentHashMap<>();
    
    public void storeOtp(String email, String otp) {
        otpStore.put(email, new OtpEntry(otp, System.currentTimeMillis()));
    }
    
    public boolean verifyOtp(String email, String otp) {
        OtpEntry entry = otpStore.get(email);
        if (entry == null) return false;
        
        if (System.currentTimeMillis() - entry.timestamp > TimeUnit.MINUTES.toMillis(5)) {
            otpStore.remove(email);
            return false;
        }
        
        boolean isValid = entry.otp.equals(otp);
        if (isValid) {
            otpStore.remove(email);
        }
        return isValid;
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

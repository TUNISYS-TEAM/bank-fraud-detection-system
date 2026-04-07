package com.fraud.backend.kafka;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OtpEvent {
    private String eventId;
    private String email;
    private String otp;
    private String name;
    private String eventType; // VERIFICATION, PASSWORD_RESET, LOGIN
    private Instant timestamp;
    private int retryCount;
}
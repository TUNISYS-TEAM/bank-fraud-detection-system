package com.fraud.backend.monitoring.api.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class TrainingTransaction {
    private String transactionId;
    private LocalDateTime timestamp;
    private BigDecimal amount;
    private String postalCode;
    private Double latitude;
    private Double longitude;
    private String customerId;
    private String channel;
    private boolean isFraud;
    private Double fraudScore;
    private String transactionStatus;
}
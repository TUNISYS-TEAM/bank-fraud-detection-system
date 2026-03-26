package com.fraud.backend.api.dto.frauddetection.fraudalert.response;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudAlertResponse {
    private Long id;
    private UUID alertUuid;
    private Long transactionId;
    private Long userId;
    private Double fraudScore;
    private Double supervisedScore;
    private Double anomalyScore;
    private Long modelUsedId;
    private String riskLevel;
    private String alertType;
    private String status;
    private Long reviewedBy;
    private LocalDateTime reviewDate;
    private String reviewComment;
    private String actionTaken;
    private Map<String, Object> featuresSnapshot;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // Transaction details for summary views
    private Double transactionAmount;
    private String transactionCurrency;
    private String transactionChannel;
    private String transactionCategory;
    private String clientName;
    private String accountNumber;
}
package com.fraud.backend.api.dto.frauddetection.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulatedTransactionResult {
    private String transactionId;
    private Double amount;
    private String channel;
    private String category;
    private String region;
    private Double fraudScore;
    private String riskLevel;
    private Boolean wasInjectedFraud; 
    private Boolean wasDetected;     
    private String alertType;
}
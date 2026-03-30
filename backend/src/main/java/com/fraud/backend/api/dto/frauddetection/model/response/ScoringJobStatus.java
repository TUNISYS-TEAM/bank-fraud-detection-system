package com.fraud.backend.api.dto.frauddetection.model.response;

import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class ScoringJobStatus {
    private UUID jobId;
    private String status; // RUNNING, COMPLETED, FAILED
    private int totalTransactions;
    private int processedCount;
    private double progress;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String errorMessage;
    private List<FraudAlert> results = new ArrayList<>();
    private Map<UUID, String> errors = new ConcurrentHashMap<>();
    
    public void addResult(FraudAlert alert) {
        results.add(alert);
    }
    
    public void addError(UUID transactionUuid, String error) {
        errors.put(transactionUuid, error);
    }
}
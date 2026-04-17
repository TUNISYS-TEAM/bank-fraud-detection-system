package com.fraud.backend.api.dto.frauddetection.model.response;

import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import lombok.Getter;

import java.util.UUID;

@Getter
public class ScoringResult {
    private final UUID transactionUuid;
    private final FraudAlert alert;
    private final boolean success;
    private final String errorMessage;

    private ScoringResult(UUID transactionUuid, FraudAlert alert, boolean success, String errorMessage) {
        this.transactionUuid = transactionUuid;
        this.alert = alert;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public static ScoringResult success(UUID transactionUuid, FraudAlert alert) {
        return new ScoringResult(transactionUuid, alert, true, null);
    }

    public static ScoringResult failure(UUID transactionUuid, String errorMessage) {
        return new ScoringResult(transactionUuid, null, false, errorMessage);
    }
}
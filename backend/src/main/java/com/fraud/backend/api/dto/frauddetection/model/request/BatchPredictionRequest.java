package com.fraud.backend.api.dto.frauddetection.model.request;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class BatchPredictionRequest {
    private List<UUID> transactionUuids;
    private List<Long> accountIds;
    private UUID modelUuid;
}
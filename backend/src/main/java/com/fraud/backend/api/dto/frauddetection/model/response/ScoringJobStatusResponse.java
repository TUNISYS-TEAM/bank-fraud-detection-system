package com.fraud.backend.api.dto.frauddetection.model.response;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class ScoringJobStatusResponse {
    private UUID jobId;
    private String status;
    private String message;
}
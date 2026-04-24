package com.fraud.backend.api.dto.frauddetection.model.response;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class ModelSummaryResponse {
    private UUID modelUuid;
    private String modelName;
    private String modelType;
    private String version;
    private String status;
    private LocalDateTime createdAt;
    private String createdByEmail;
    private String createdByName;
    private Double auc;
    private Double precision;
    private Double recall;
    private Double f1Score;
    private Double threshold;
}
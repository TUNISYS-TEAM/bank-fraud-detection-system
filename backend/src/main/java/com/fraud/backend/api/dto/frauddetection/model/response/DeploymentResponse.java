package com.fraud.backend.api.dto.frauddetection.model.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class DeploymentResponse {
    private Long id;
    private UUID modelUuid;
    private String modelName;
    private String modelType;
    private String modelVersion;
    private String deployedByEmail;
    private String deployedByName;
    private LocalDateTime deploymentDate;
    private LocalDateTime undeploymentDate;
    private String status;
    private Map<String, Object> performanceAtDeployment;
    private String notes;
}
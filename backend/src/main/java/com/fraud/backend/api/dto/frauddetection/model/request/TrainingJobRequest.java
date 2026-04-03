package com.fraud.backend.api.dto.frauddetection.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.Map;
import java.util.UUID;

@Data
public class TrainingJobRequest {
    
    @NotNull(message = "Dataset UUID is required")
    private UUID datasetUuid;
    
    private UUID modelUuid;
    
    @NotBlank(message = "Job name is required")
    private String jobName;
    
    private Map<String, Object> hyperparameters;
    
    private Double testSize = 0.2; 
    
    private Integer randomSeed = 42;
    
    private Boolean enableHyperparameterTuning = false;

    private Boolean isTest = false;
    
    private Integer tuningTrials = 10; 
}
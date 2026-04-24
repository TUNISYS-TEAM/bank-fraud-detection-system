package com.fraud.backend.api.dto.frauddetection.model.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationRequest {
    
    @NotNull(message = "Model UUID is required")
    private UUID modelUuid;

    private String scenario; // mixed, high_amount, geo_anomaly, high_frequency, sophisticated

    @Min(value = 10, message = "Transaction count must be at least 10")
    @Max(value = 10000, message = "Transaction count cannot exceed 10000")
    private Integer transactionCount;

    @Min(value = 0, message = "Fraud ratio cannot be negative")
    @Max(value = 1, message = "Fraud ratio cannot exceed 1")
    private Double fraudRatio;

    private Integer periodHours;

    private String timeDistribution; // uniform, realistic, night

    private Integer randomSeed;

    @Builder.Default
    private Boolean subtleFraud = true;

    private Integer fraudCount;
}
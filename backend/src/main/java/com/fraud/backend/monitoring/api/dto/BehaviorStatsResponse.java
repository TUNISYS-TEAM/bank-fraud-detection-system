package com.fraud.backend.monitoring.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BehaviorStatsResponse {
    private double driftScore;
    private double highRiskRatio;
}
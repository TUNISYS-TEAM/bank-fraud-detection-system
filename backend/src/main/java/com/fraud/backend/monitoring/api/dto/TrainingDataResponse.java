package com.fraud.backend.monitoring.api.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class TrainingDataResponse {
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private List<TrainingTransaction> transactions;
}
package com.fraud.backend.api.dto.accessmanagement.system.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemMetricsHistoryResponse {
    private List<MetricsPoint> requestsPerSecond;
    private List<MetricsPoint> responseTime;
    private List<MetricsPoint> cpuUsage;
    private List<MetricsPoint> memoryUsage;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricsPoint {
        private String time;
        private double value;
    }
}
package com.fraud.backend.api.dto.accessmanagement.system.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemMetricsResponse {
    private long uptime; // in seconds
    private double requestsPerSecond;
    private double cpuUsage;
    private double memoryUsage;
    private double responseTime; // in ms
    private int activeConnections;
    private double diskUsage;
    private int threadCount;
    private String jvmVersion;
    private String osName;
    private String osArch;
    private int availableProcessors;
}
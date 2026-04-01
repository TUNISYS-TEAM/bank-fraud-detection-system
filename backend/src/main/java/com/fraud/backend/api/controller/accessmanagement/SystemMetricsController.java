package com.fraud.backend.api.controller.accessmanagement;

import com.fraud.backend.api.dto.accessmanagement.system.response.SystemHealthResponse;
import com.fraud.backend.api.dto.accessmanagement.system.response.SystemMetricsHistoryResponse;
import com.fraud.backend.api.dto.accessmanagement.system.response.SystemMetricsResponse;
import com.fraud.backend.data.service.accessmanagement.SystemMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
@Tag(name = "System Metrics", description = "API for system monitoring and metrics")
public class SystemMetricsController {
    
    private final SystemMetricsService systemMetricsService;
    
    @GetMapping("/metrics")
    @Operation(summary = "Get current system metrics")
    public ResponseEntity<SystemMetricsResponse> getSystemMetrics() {
        return ResponseEntity.ok(systemMetricsService.getCurrentMetrics());
    }
    
    @GetMapping("/health")
    @Operation(summary = "Get system health status")
    public ResponseEntity<SystemHealthResponse> getSystemHealth() {
        return ResponseEntity.ok(systemMetricsService.getSystemHealth());
    }
    
    @GetMapping("/metrics/history")
    @Operation(summary = "Get historical metrics data for charts")
    public ResponseEntity<SystemMetricsHistoryResponse> getMetricsHistory(
            @RequestParam(defaultValue = "60") int minutes) {
        return ResponseEntity.ok(systemMetricsService.getMetricsHistory(minutes));
    }
    
    @GetMapping("/uptime")
    @Operation(summary = "Get system uptime in seconds")
    public ResponseEntity<Map<String, Long>> getUptime() {
        return ResponseEntity.ok(Map.of("uptime", systemMetricsService.getUptime()));
    }
    
    @GetMapping("/metrics/requests")
    @Operation(summary = "Get current requests per second")
    public ResponseEntity<Map<String, Double>> getRequestsPerSecond() {
        return ResponseEntity.ok(Map.of("requestsPerSecond", systemMetricsService.getRequestsPerSecond()));
    }
    
    @GetMapping("/metrics/cpu")
    @Operation(summary = "Get current CPU usage")
    public ResponseEntity<Map<String, Double>> getCpuUsage() {
        return ResponseEntity.ok(Map.of("cpuUsage", systemMetricsService.getCpuUsage()));
    }
    
    @GetMapping("/metrics/memory")
    @Operation(summary = "Get current memory usage")
    public ResponseEntity<Map<String, Double>> getMemoryUsage() {
        return ResponseEntity.ok(Map.of("memoryUsage", systemMetricsService.getMemoryUsage()));
    }
    
    @GetMapping("/metrics/response-time")
    @Operation(summary = "Get average response time")
    public ResponseEntity<Map<String, Double>> getResponseTime() {
        return ResponseEntity.ok(Map.of("responseTime", systemMetricsService.getResponseTime()));
    }
    
    @GetMapping("/metrics/connections")
    @Operation(summary = "Get active connections count")
    public ResponseEntity<Map<String, Integer>> getActiveConnections() {
        return ResponseEntity.ok(Map.of("activeConnections", systemMetricsService.getActiveConnections()));
    }
}
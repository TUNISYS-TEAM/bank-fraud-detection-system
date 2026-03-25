package com.fraud.backend.data.service.accessmanagement;

import com.fraud.backend.api.dto.accessmanagement.system.response.SystemHealthResponse;
import com.fraud.backend.api.dto.accessmanagement.system.response.SystemMetricsHistoryResponse;
import com.fraud.backend.api.dto.accessmanagement.system.response.SystemMetricsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


import jakarta.annotation.PostConstruct;
import java.lang.management.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class SystemMetricsService {
    
    private final OperatingSystemMXBean osBean;
    private final RuntimeMXBean runtimeBean;
    private final MemoryMXBean memoryBean;
    private final ThreadMXBean threadBean;
    
    // Store historical metrics
    private final Deque<MetricsRecord> metricsHistory = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY_SIZE = 1440; // 24 hours with 1 minute interval
    
    // Request tracking for RPS calculation
    private final AtomicLong requestCount = new AtomicLong(0);
    private final AtomicReference<Double> currentRps = new AtomicReference<>(0.0);
    private long lastRequestTimestamp = System.currentTimeMillis();
    
    public SystemMetricsService() {
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.runtimeBean = ManagementFactory.getRuntimeMXBean();
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.threadBean = ManagementFactory.getThreadMXBean();
    }
    
    @PostConstruct
    public void init() {
        // Start metrics collection thread
        Thread metricsCollector = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(60000); // Collect every minute
                    collectMetrics();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        metricsCollector.setDaemon(true);
        metricsCollector.start();
        
        // Start RPS calculation thread
        Thread rpsCalculator = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    calculateRps();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        rpsCalculator.setDaemon(true);
        rpsCalculator.start();
    }
    
    public void recordRequest() {
        requestCount.incrementAndGet();
    }
    
    private void calculateRps() {
        long currentTime = System.currentTimeMillis();
        long timeDiff = (currentTime - lastRequestTimestamp) / 1000;
        
        if (timeDiff > 0) {
            long count = requestCount.getAndSet(0);
            currentRps.set(count / (double) timeDiff);
            lastRequestTimestamp = currentTime;
        }
    }
    
    private void collectMetrics() {
        MetricsRecord record = MetricsRecord.builder()
            .timestamp(LocalDateTime.now())
            .cpuUsage(getCpuUsage())
            .memoryUsage(getMemoryUsage())
            .responseTime(getResponseTime())
            .requestsPerSecond(currentRps.get())
            .activeConnections(getActiveConnections())
            .build();
        
        metricsHistory.addFirst(record);
        
        // Keep history size limited
        while (metricsHistory.size() > MAX_HISTORY_SIZE) {
            metricsHistory.removeLast();
        }
        
        log.debug("Metrics collected: CPU={}%, Memory={}%, RPS={}", 
            record.getCpuUsage(), record.getMemoryUsage(), record.getRequestsPerSecond());
    }
    
    public SystemMetricsResponse getCurrentMetrics() {
        return SystemMetricsResponse.builder()
            .uptime(getUptime())
            .requestsPerSecond(currentRps.get())
            .cpuUsage(getCpuUsage())
            .memoryUsage(getMemoryUsage())
            .responseTime(getResponseTime())
            .activeConnections(getActiveConnections())
            .diskUsage(getDiskUsage())
            .threadCount(threadBean.getThreadCount())
            .jvmVersion(runtimeBean.getVmVersion())
            .osName(System.getProperty("os.name"))
            .osArch(System.getProperty("os.arch"))
            .availableProcessors(Runtime.getRuntime().availableProcessors())
            .build();
    }
    
    public SystemHealthResponse getSystemHealth() {
        double cpuUsage = getCpuUsage();
        double memoryUsage = getMemoryUsage();
        double responseTime = getResponseTime();
        
        // Calculate health score (0-100)
        double healthScore = 100.0;
        healthScore -= cpuUsage * 0.3; // CPU contributes 30%
        healthScore -= memoryUsage * 0.2; // Memory contributes 20%
        healthScore -= Math.min(50, responseTime / 10); // Response time contributes up to 50%
        
        healthScore = Math.max(0, Math.min(100, healthScore));
        
        String status;
        String message;
        if (healthScore >= 80) {
            status = "HEALTHY";
            message = "System is operating normally";
        } else if (healthScore >= 60) {
            status = "DEGRADED";
            message = "System is experiencing some degradation";
        } else {
            status = "CRITICAL";
            message = "System requires immediate attention";
        }
        
        return SystemHealthResponse.builder()
            .healthScore(healthScore)
            .status(status)
            .timestamp(LocalDateTime.now())
            .message(message)
            .build();
    }
    
    public SystemMetricsHistoryResponse getMetricsHistory(int minutes) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        List<SystemMetricsHistoryResponse.MetricsPoint> rpsPoints = new ArrayList<>();
        List<SystemMetricsHistoryResponse.MetricsPoint> responseTimePoints = new ArrayList<>();
        List<SystemMetricsHistoryResponse.MetricsPoint> cpuPoints = new ArrayList<>();
        List<SystemMetricsHistoryResponse.MetricsPoint> memoryPoints = new ArrayList<>();
        
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(minutes);
        
        metricsHistory.stream()
            .filter(record -> record.getTimestamp().isAfter(cutoff))
            .forEach(record -> {
                String timeStr = record.getTimestamp().format(formatter);
                
                rpsPoints.add(SystemMetricsHistoryResponse.MetricsPoint.builder()
                    .time(timeStr)
                    .value(record.getRequestsPerSecond())
                    .build());
                
                responseTimePoints.add(SystemMetricsHistoryResponse.MetricsPoint.builder()
                    .time(timeStr)
                    .value(record.getResponseTime())
                    .build());
                
                cpuPoints.add(SystemMetricsHistoryResponse.MetricsPoint.builder()
                    .time(timeStr)
                    .value(record.getCpuUsage())
                    .build());
                
                memoryPoints.add(SystemMetricsHistoryResponse.MetricsPoint.builder()
                    .time(timeStr)
                    .value(record.getMemoryUsage())
                    .build());
            });
        
        return SystemMetricsHistoryResponse.builder()
            .requestsPerSecond(rpsPoints)
            .responseTime(responseTimePoints)
            .cpuUsage(cpuPoints)
            .memoryUsage(memoryPoints)
            .build();
    }
    
    public long getUptime() {
        return runtimeBean.getUptime() / 1000;
    }
    
    public double getRequestsPerSecond() {
        return currentRps.get();
    }
    
    public double getCpuUsage() {
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean = 
                (com.sun.management.OperatingSystemMXBean) osBean;
            return sunOsBean.getProcessCpuLoad() * 100;
        }
        return 0.0;
    }
    
    public double getMemoryUsage() {
        long usedMemory = memoryBean.getHeapMemoryUsage().getUsed();
        long maxMemory = memoryBean.getHeapMemoryUsage().getMax();
        if (maxMemory > 0) {
            return (usedMemory * 100.0) / maxMemory;
        }
        return 0.0;
    }
    
    public double getResponseTime() {
        // This should be replaced with actual response time tracking from interceptors
        // For now, return a simulated value based on request rate
        double rps = currentRps.get();
        if (rps > 0) {
            return Math.min(500, 50 + (rps / 2));
        }
        return 50.0;
    }
    
    public int getActiveConnections() {
        // This should be replaced with actual connection tracking
        // For now, return a simulated value based on thread count
        return threadBean.getThreadCount() / 2;
    }
    
    public double getDiskUsage() {
        // This should be implemented with File API
        java.io.File root = new java.io.File("/");
        long totalSpace = root.getTotalSpace();
        long freeSpace = root.getFreeSpace();
        if (totalSpace > 0) {
            return ((totalSpace - freeSpace) * 100.0) / totalSpace;
        }
        return 0.0;
    }
    
    @lombok.Data
    @lombok.Builder
    private static class MetricsRecord {
        private LocalDateTime timestamp;
        private double cpuUsage;
        private double memoryUsage;
        private double responseTime;
        private double requestsPerSecond;
        private int activeConnections;
    }
}
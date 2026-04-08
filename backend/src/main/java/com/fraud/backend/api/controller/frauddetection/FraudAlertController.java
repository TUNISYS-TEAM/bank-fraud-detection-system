package com.fraud.backend.api.controller.frauddetection;

import com.fraud.backend.api.dto.frauddetection.fraudalert.request.UpdateAlertStatusRequest;
import com.fraud.backend.api.dto.frauddetection.fraudalert.response.AlertStatsResponse;
import com.fraud.backend.api.dto.frauddetection.fraudalert.response.FraudAlertResponse;
import com.fraud.backend.api.dto.frauddetection.fraudalert.response.TimeSeriesDataResponse;
import com.fraud.backend.data.service.frauddetection.alert.FraudAlertService;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/fraud-alerts")
@RequiredArgsConstructor
public class FraudAlertController {

    private final FraudAlertService fraudAlertService;

    @GetMapping("/count")
    public ResponseEntity<Long> getTotalAlerts() {
        return ResponseEntity.ok(fraudAlertService.getTotalAlerts());
    }

    @GetMapping("/count/critical")
    public ResponseEntity<Long> getCriticalAlertsCount() {
        return ResponseEntity.ok(fraudAlertService.getCriticalAlertsCount());
    }

    @GetMapping("/stats")
    public ResponseEntity<AlertStatsResponse> getAlertStats() {
        return ResponseEntity.ok(fraudAlertService.getAlertStats());
    }

    @GetMapping("/time-series")
    public ResponseEntity<List<TimeSeriesDataResponse>> getTimeSeriesData(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        return ResponseEntity.ok(fraudAlertService.getTimeSeriesData(startDate, endDate));
    }

    @GetMapping("/risk-distribution")
    public ResponseEntity<List<Map<String, Object>>> getRiskDistribution(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        return ResponseEntity.ok(fraudAlertService.getRiskDistribution(startDate, endDate));
    }

    @GetMapping("/recent")
    public ResponseEntity<List<FraudAlertResponse>> getRecentAnomalies(
            @RequestParam(defaultValue = "8") int limit) {
        return ResponseEntity.ok(fraudAlertService.getRecentAnomalies(limit));
    }

    @GetMapping("/{alertUuid}")
    public ResponseEntity<FraudAlertResponse> getAlertByUuid(@PathVariable UUID alertUuid) {
        return ResponseEntity.ok(fraudAlertService.getAlertByUuid(alertUuid));
    }

    @PutMapping("/{alertUuid}/status")
    public ResponseEntity<FraudAlertResponse> updateAlertStatus(
            @PathVariable UUID alertUuid,
            @RequestBody UpdateAlertStatusRequest request) {
        return ResponseEntity.ok(fraudAlertService.updateAlertStatus(alertUuid, request));
    }

    @GetMapping
    public ResponseEntity<Page<FraudAlertResponse>> getAlerts(
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String alertType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID datasetUuid,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection) {
        Sort sort = Sort.by(Sort.Direction.fromString(sortDirection), sortBy);
        Pageable pageable = PageRequest.of(page, size, sort);
        return ResponseEntity
                .ok(fraudAlertService.getAlerts(riskLevel, status, alertType, search, datasetUuid, startDate, endDate, pageable));
    }
}
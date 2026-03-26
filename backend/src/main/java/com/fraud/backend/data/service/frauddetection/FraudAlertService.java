package com.fraud.backend.data.service.frauddetection;

import com.fraud.backend.api.dto.frauddetection.fraudalert.FraudAlertMapper;
import com.fraud.backend.api.dto.frauddetection.fraudalert.request.UpdateAlertStatusRequest;
import com.fraud.backend.api.dto.frauddetection.fraudalert.response.AlertStatsResponse;
import com.fraud.backend.api.dto.frauddetection.fraudalert.response.FraudAlertResponse;
import com.fraud.backend.api.dto.frauddetection.fraudalert.response.TimeSeriesDataResponse;
import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import com.fraud.backend.data.repository.frauddetection.FraudAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudAlertService {

    private final FraudAlertRepository fraudAlertRepository;
    private final FraudAlertMapper fraudAlertMapper;

    public Long getTotalAlerts() {
        return fraudAlertRepository.count();
    }

    public Long getCriticalAlertsCount() {
        return fraudAlertRepository.countByRiskLevel("CRITICAL");
    }

    public AlertStatsResponse getAlertStats() {
        List<Object[]> results = fraudAlertRepository.getAlertStats();

        if (results != null && !results.isEmpty() && results.get(0) != null) {
            Object[] stats = results.get(0);
            return AlertStatsResponse.builder()
                    .total(stats[0] != null ? ((Number) stats[0]).longValue() : 0L)
                    .critical(stats[1] != null ? ((Number) stats[1]).longValue() : 0L)
                    .high(stats[2] != null ? ((Number) stats[2]).longValue() : 0L)
                    .medium(stats[3] != null ? ((Number) stats[3]).longValue() : 0L)
                    .low(stats[4] != null ? ((Number) stats[4]).longValue() : 0L)
                    .pending(stats[5] != null ? ((Number) stats[5]).longValue() : 0L)
                    .reviewed(stats[6] != null ? ((Number) stats[6]).longValue() : 0L)
                    .resolved(stats[7] != null ? ((Number) stats[7]).longValue() : 0L)
                    .falsePositive(stats[8] != null ? ((Number) stats[8]).longValue() : 0L)
                    .build();
        }

        return AlertStatsResponse.builder()
                .total(0L)
                .critical(0L)
                .high(0L)
                .medium(0L)
                .low(0L)
                .pending(0L)
                .reviewed(0L)
                .resolved(0L)
                .falsePositive(0L)
                .build();
    }

    public List<TimeSeriesDataResponse> getTimeSeriesData(LocalDateTime startDate, LocalDateTime endDate) {
        if (startDate == null) {
            startDate = LocalDateTime.now().minusDays(7).withHour(0).withMinute(0).withSecond(0);
        }
        if (endDate == null) {
            endDate = LocalDateTime.now();
        }

        List<Object[]> results = fraudAlertRepository.getTimeSeriesData(startDate, endDate);
        Map<String, TimeSeriesDataResponse> dataByDate = new HashMap<>();

        for (Object[] row : results) {
            String dateStr = row[0].toString();
            TimeSeriesDataResponse point = TimeSeriesDataResponse.builder()
                    .date(dateStr)
                    .count(row[1] != null ? ((Number) row[1]).longValue() : 0L)
                    .criticalCount(row[2] != null ? ((Number) row[2]).longValue() : 0L)
                    .highCount(row[3] != null ? ((Number) row[3]).longValue() : 0L)
                    .mediumCount(row[4] != null ? ((Number) row[4]).longValue() : 0L)
                    .lowCount(row[5] != null ? ((Number) row[5]).longValue() : 0L)
                    .build();
            dataByDate.put(dateStr, point);
        }

        // Fill gaps with zeros
        List<TimeSeriesDataResponse> timeSeries = new ArrayList<>();
        LocalDateTime current = startDate;
        while (!current.isAfter(endDate)) {
            String dateKey = current.toLocalDate().toString();
            if (dataByDate.containsKey(dateKey)) {
                timeSeries.add(dataByDate.get(dateKey));
            } else {
                timeSeries.add(TimeSeriesDataResponse.builder()
                        .date(dateKey)
                        .count(0L).criticalCount(0L).highCount(0L).mediumCount(0L).lowCount(0L)
                        .build());
            }
            current = current.plusDays(1);
        }
        return timeSeries;
    }

    public List<Map<String, Object>> getRiskDistribution(LocalDateTime startDate, LocalDateTime endDate) {
        if (startDate == null) {
            startDate = LocalDateTime.now().minusDays(6).withHour(0).withMinute(0).withSecond(0);
        }
        if (endDate == null) {
            endDate = LocalDateTime.now();
        }

        List<Object[]> results = fraudAlertRepository.getRiskDistribution(startDate, endDate);
        List<Map<String, Object>> distribution = new ArrayList<>();

        for (Object[] row : results) {
            Map<String, Object> item = new HashMap<>();
            item.put("riskLevel", row[0]);
            item.put("count", row[1] != null ? ((Number) row[1]).longValue() : 0L);
            item.put("percentage", row[2] != null ? ((Number) row[2]).doubleValue() : 0.0);
            distribution.add(item);
        }

        return distribution;
    }

    public List<FraudAlertResponse> getRecentAnomalies(int limit) {
        return fraudAlertRepository.findTop8ByOrderByCreatedAtDesc()
                .stream()
                .limit(limit)
                .map(fraudAlertMapper::toResponse)
                .toList();
    }

    public FraudAlertResponse getAlertByUuid(UUID alertUuid) {
        FraudAlert alert = fraudAlertRepository.findByAlertUuid(alertUuid)
                .orElseThrow(() -> new RuntimeException("Alert not found with UUID: " + alertUuid));
        return fraudAlertMapper.toResponse(alert);
    }

    @Transactional
    public FraudAlertResponse updateAlertStatus(UUID alertUuid, UpdateAlertStatusRequest request) {
        FraudAlert alert = fraudAlertRepository.findByAlertUuid(alertUuid)
                .orElseThrow(() -> new RuntimeException("Alert not found with UUID: " + alertUuid));

        alert.setStatus(request.getStatus());
        alert.setReviewComment(request.getReviewComment());
        alert.setActionTaken(request.getActionTaken());
        alert.setReviewDate(LocalDateTime.now());

        FraudAlert updatedAlert = fraudAlertRepository.save(alert);
        return fraudAlertMapper.toResponse(updatedAlert);
    }

    public Page<FraudAlertResponse> getAlerts(String riskLevel, String status, String alertType, String search,
            LocalDateTime startDate, LocalDateTime endDate,
            Pageable pageable) {
        Page<FraudAlert> alerts = fraudAlertRepository.findAll((root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            if (riskLevel != null && !riskLevel.isEmpty()) {
                predicates.add(cb.equal(root.get("riskLevel"), riskLevel));
            }
            if (status != null && !status.isEmpty()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (alertType != null && !alertType.isEmpty()) {
                predicates.add(cb.equal(root.get("alertType"), alertType));
            }
            // Search filtering is handled on the frontend side
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startDate));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endDate));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        }, pageable);

        return alerts.map(fraudAlertMapper::toResponse);
    }
}
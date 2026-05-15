package com.fraud.backend.data.service.frauddetection.alert;

import com.fraud.backend.api.dto.frauddetection.fraudalert.FraudAlertMapper;
import com.fraud.backend.api.dto.frauddetection.fraudalert.request.AssignAlertsRequest;
import com.fraud.backend.api.dto.frauddetection.fraudalert.request.UpdateAlertStatusRequest;
import com.fraud.backend.api.dto.frauddetection.fraudalert.response.AlertStatsResponse;
import com.fraud.backend.api.dto.frauddetection.fraudalert.response.FraudAlertResponse;
import com.fraud.backend.api.dto.frauddetection.fraudalert.response.FraudLocationResponse;
import com.fraud.backend.api.dto.frauddetection.fraudalert.response.TimeSeriesDataResponse;
import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import com.fraud.backend.data.repository.frauddetection.FraudAlertRepository;
import com.fraud.backend.data.service.accessmanagement.EmailService;
import com.fraud.backend.data.repository.accessmanagement.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.accessmanagement.KeycloakMapping;
import com.fraud.backend.data.repository.accessmanagement.KeycloakMappingRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudAlertService {

    private final FraudAlertRepository fraudAlertRepository;
    private final FraudAlertMapper fraudAlertMapper;
    private final KeycloakMappingRepository keycloakMappingRepository;
    private final AppUserRepository appUserRepository;
    private final PostalCodeCoordinatesResolver coordinatesResolver;
    private final com.fraud.backend.data.repository.corebanking.TransactionRepository transactionRepository;
    private final EmailService emailService;

    // ========== EXISTING METHODS (unchanged except for explicit typing in getAlerts) ==========

    private AppUser getCurrentUser() {
        // ... (your existing implementation, unchanged)
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            log.error("No authentication found");
            throw new IllegalStateException("Utilisateur non authentifié");
        }
        Object principal = authentication.getPrincipal();
        String email = null;
        if (principal instanceof Jwt) {
            Jwt jwt = (Jwt) principal;
            email = jwt.getClaim("preferred_username");
            if (email == null) email = jwt.getClaim("email");
            if (email == null) email = jwt.getClaim("sub");
        } else if (principal instanceof org.springframework.security.core.userdetails.UserDetails) {
            email = ((org.springframework.security.core.userdetails.UserDetails) principal).getUsername();
        } else if (principal instanceof String) {
            email = (String) principal;
        }
        if (email == null || email.isEmpty()) {
            email = authentication.getName();
        }
        final String userEmail = email;
        if (userEmail == null || userEmail.isEmpty()) {
            throw new IllegalStateException("Impossible d'extraire l'email de l'authentification");
        }
        return keycloakMappingRepository.findByEmail(userEmail)
                .map(KeycloakMapping::getUser)
                .orElseThrow(() -> new IllegalStateException("Utilisateur non trouvé avec l'email: " + userEmail));
    }

    private Long getEffectiveManagerId(AppUser user) {
        String role = user.getRole().getName().toUpperCase();
        if ("ADMIN".equals(role)) return null;
        if ("MANAGER".equals(role)) return user.getId();
        if ("ANALYST".equals(role) && user.getManager() != null) return user.getManager().getId();
        return null;
    }

    public Long getTotalAlerts() {
        AppUser currentUser = getCurrentUser();
        Long managerId = getEffectiveManagerId(currentUser);
        if (managerId != null) {
            return (long) fraudAlertRepository
                    .findAllByManagerId(managerId, org.springframework.data.domain.PageRequest.of(0, 1))
                    .getTotalElements();
        }
        return fraudAlertRepository.count();
    }

    public Long getCriticalAlertsCount() {
        AppUser currentUser = getCurrentUser();
        Long managerId = getEffectiveManagerId(currentUser);
        if (managerId != null) {
            AlertStatsResponse stats = getAlertStats();
            return stats.getCritical();
        }
        return fraudAlertRepository.countByRiskLevel("CRITICAL");
    }

    public AlertStatsResponse getAlertStats() {
        AppUser currentUser = getCurrentUser();
        Long managerId = getEffectiveManagerId(currentUser);
        List<Object[]> results;
        if (managerId != null) {
            results = fraudAlertRepository.getAlertStatsByManagerId(managerId);
        } else {
            results = fraudAlertRepository.getAlertStats();
        }
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
                .total(0L).critical(0L).high(0L).medium(0L).low(0L)
                .pending(0L).reviewed(0L).resolved(0L).falsePositive(0L)
                .build();
    }

    public Long countAlertsByAnalystId(Long analystId) {
        return fraudAlertRepository.countByAssignedAnalystId(analystId);
    }

    public List<TimeSeriesDataResponse> getTimeSeriesData(LocalDateTime startDate, LocalDateTime endDate) {
        // ... (unchanged, keep as is)
        if (startDate == null) startDate = LocalDateTime.now().minusDays(7).withHour(0).withMinute(0).withSecond(0);
        if (endDate == null) endDate = LocalDateTime.now();
        AppUser currentUser = getCurrentUser();
        Long managerId = getEffectiveManagerId(currentUser);
        List<Object[]> results;
        if (managerId != null) {
            results = fraudAlertRepository.getTimeSeriesDataByManagerId(startDate, endDate, managerId);
        } else {
            results = fraudAlertRepository.getTimeSeriesData(startDate, endDate);
        }
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
        // ... (unchanged)
        if (startDate == null) startDate = LocalDateTime.now().minusDays(7).withHour(0).withMinute(0).withSecond(0);
        if (endDate == null) endDate = LocalDateTime.now();
        AppUser currentUser = getCurrentUser();
        Long managerId = getEffectiveManagerId(currentUser);
        List<Object[]> results;
        if (managerId != null) {
            results = fraudAlertRepository.getRiskDistributionByManagerId(startDate, endDate, managerId);
        } else {
            results = fraudAlertRepository.getRiskDistribution(startDate, endDate);
        }
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
        AppUser currentUser = getCurrentUser();
        Long managerId = getEffectiveManagerId(currentUser);
        List<FraudAlert> alerts;
        org.springframework.data.domain.PageRequest pageRequest = org.springframework.data.domain.PageRequest.of(0, limit);
        if (managerId != null) {
            alerts = fraudAlertRepository.findPrioritizedAnomaliesByManagerId(managerId, pageRequest);
        } else {
            alerts = fraudAlertRepository.findPrioritizedAnomalies(pageRequest);
        }
        List<FraudAlertResponse> responseList = new ArrayList<>();
        for (FraudAlert alert : alerts) {
            responseList.add(fraudAlertMapper.toResponse(alert));
        }
        return responseList;
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
        AppUser currentUser = getCurrentUser();
        alert.setStatus(request.getStatus());
        alert.setReviewComment(request.getReviewComment());
        alert.setActionTaken(request.getActionTaken());
        alert.setReviewDate(LocalDateTime.now());
        alert.setReviewedBy(currentUser);
        com.fraud.backend.data.entity.corebanking.Transaction targetTransaction = alert.getTransaction();
        if (targetTransaction != null) {
            String status = request.getStatus();
            if ("CONFIRMED".equalsIgnoreCase(status) || "RESOLVED".equalsIgnoreCase(status)) {
                targetTransaction.setFinalDecision(true);
            } else if ("REJECTED".equalsIgnoreCase(status) || "FALSE_POSITIVE".equalsIgnoreCase(status) || "DISMISSED".equalsIgnoreCase(status)) {
                targetTransaction.setFinalDecision(false);
            }
            transactionRepository.save(targetTransaction);
        }
        FraudAlert updatedAlert = fraudAlertRepository.save(alert);
        return fraudAlertMapper.toResponse(updatedAlert);
    }

    public Page<FraudAlertResponse> getAlerts(String riskLevel, String status, String alertType, String search,
            UUID datasetUuid, LocalDateTime startDate, LocalDateTime endDate,
            Pageable pageable) {

        AppUser currentUser = getCurrentUser();
        String role = currentUser.getRole().getName().toUpperCase();

        if ("ANALYST".equals(role)) {
            Page<FraudAlert> alerts = fraudAlertRepository.findAll((root, query, cb) -> {
                List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
                predicates.add(cb.equal(root.get("assignedAnalyst").get("id"), currentUser.getId()));
                if (riskLevel != null && !riskLevel.isEmpty() && !"all".equalsIgnoreCase(riskLevel)) {
                    predicates.add(cb.equal(root.get("riskLevel"), riskLevel));
                }
                if (status != null && !status.isEmpty() && !"all".equalsIgnoreCase(status)) {
                    predicates.add(cb.equal(root.get("status"), status));
                }
                if (alertType != null && !alertType.isEmpty() && !"all".equalsIgnoreCase(alertType)) {
                    predicates.add(cb.equal(root.get("alertType"), alertType));
                }
                return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
            }, pageable);
            // FIX: Explicitly type the map operation
            return alerts.<FraudAlertResponse>map(fraudAlertMapper::toResponse);
        }

        Long managerId = getEffectiveManagerId(currentUser);
        Page<FraudAlert> alerts = fraudAlertRepository.findAll((root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (managerId != null) {
                predicates.add(cb.equal(
                        root.get("transaction").get("dataset").get("assignedManager").get("id"),
                        managerId));
            }
            if (riskLevel != null && !riskLevel.isEmpty() && !"all".equalsIgnoreCase(riskLevel)) {
                predicates.add(cb.equal(root.get("riskLevel"), riskLevel));
            }
            if (status != null && !status.isEmpty() && !"all".equalsIgnoreCase(status)) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (alertType != null && !alertType.isEmpty() && !"all".equalsIgnoreCase(alertType)) {
                predicates.add(cb.equal(root.get("alertType"), alertType));
            }
            if (datasetUuid != null) {
                predicates.add(cb.equal(
                        root.get("transaction").get("dataset").get("datasetUuid"),
                        datasetUuid));
            }
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startDate));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endDate));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        }, pageable);
        // FIX: Explicitly type the map operation
        return alerts.<FraudAlertResponse>map(fraudAlertMapper::toResponse);
    }

    @Transactional
    public void assignAlerts(AssignAlertsRequest request) {
        AppUser currentUser = getCurrentUser();
        String role = currentUser.getRole().getName().toUpperCase();
        if (!"MANAGER".equals(role) && !"ADMIN".equals(role)) {
            throw new IllegalStateException("Seul un manager peut affecter des alertes");
        }
        AppUser analyst = appUserRepository.findById(request.getAnalystId())
                .orElseThrow(() -> new RuntimeException("Analyste non trouvé: " + request.getAnalystId()));
        if ("MANAGER".equals(role) && (analyst.getManager() == null || !analyst.getManager().getId().equals(currentUser.getId()))) {
            throw new IllegalStateException("Cet analyste n'appartient pas à votre équipe");
        }
        int updated = fraudAlertRepository.bulkAssignAlerts(request.getAlertUuids(), analyst);
        log.info("Manager {} assigned {} alerts to analyst {}", currentUser.getId(), updated, analyst.getId());
    }

    @Transactional
    public void unassignAlerts(List<UUID> alertUuids) {
        AppUser currentUser = getCurrentUser();
        String role = currentUser.getRole().getName().toUpperCase();
        if (!"MANAGER".equals(role) && !"ADMIN".equals(role)) {
            throw new IllegalStateException("Seul un manager peut désaffecter des alertes");
        }
        int updated = fraudAlertRepository.bulkUnassignAlerts(alertUuids);
        log.info("Manager {} unassigned {} alerts", currentUser.getId(), updated);
    }

    public List<Map<String, Object>> getMyAnalysts() {
        AppUser currentUser = getCurrentUser();
        String role = currentUser.getRole().getName().toUpperCase();
        if (!"MANAGER".equals(role) && !"ADMIN".equals(role)) {
            throw new IllegalStateException("Accès réservé aux managers");
        }
        List<AppUser> analysts = appUserRepository.findByManagerId(currentUser.getId());
        List<Map<String, Object>> result = new ArrayList<>();
        for (AppUser analyst : analysts) {
            Map<String, Object> info = new HashMap<>();
            info.put("id", analyst.getId());
            info.put("name", analyst.getFirstName() + " " + analyst.getLastName());
            info.put("email", analyst.getEmail());
            info.put("assignedCount", fraudAlertRepository.countByAssignedAnalystId(analyst.getId()));
            result.add(info);
        }
        return result;
    }

    public List<FraudLocationResponse> getFraudLocations(int days) {
        AppUser currentUser = getCurrentUser();
        Long managerId = getEffectiveManagerId(currentUser);
        LocalDateTime endDate = LocalDateTime.now();
        LocalDateTime startDate = endDate.minusDays(days);
        List<Object[]> results = fraudAlertRepository.getFraudLocationsByPostalCode(startDate, endDate, managerId);
        List<FraudLocationResponse> locations = new ArrayList<>();
        for (Object[] row : results) {
            String postalCode = (String) row[0];
            long count = ((Number) row[1]).longValue();
            double[] coords = coordinatesResolver.getCoordinates(postalCode);
            locations.add(new FraudLocationResponse(postalCode, count, coords[0], coords[1]));
        }
        return locations;
    }

    // ========== NEW: DAILY CRITICAL ALERTS SUMMARY (no type inference issues) ==========

    @Scheduled(cron = "0 0 8 * * *")
    public void sendDailyCriticalAlertsSummary() {
        log.info("Starting daily critical alerts summary email job");

        LocalDateTime startOfYesterday = LocalDateTime.now().minusDays(1).with(LocalTime.MIN);
        LocalDateTime endOfYesterday = LocalDateTime.now().minusDays(1).with(LocalTime.MAX);

        List<FraudAlert> criticalAlerts = fraudAlertRepository.findAll((root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("riskLevel"), "CRITICAL"));
            predicates.add(cb.between(root.get("createdAt"), startOfYesterday, endOfYesterday));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });

        if (criticalAlerts.isEmpty()) {
            log.info("No critical alerts found for yesterday, skipping email.");
            return;
        }

        // Build summary data with explicit loops (no ambiguous map calls)
        Map<String, Object> summaryData = new HashMap<>();
        summaryData.put("totalCritical", criticalAlerts.size());

        long distinctTransactions = 0;
        Set<Object> txIds = new HashSet<>();
        for (FraudAlert alert : criticalAlerts) {
            if (alert.getTransaction() != null) {
                txIds.add(alert.getTransaction().getId());
            }
        }
        distinctTransactions = txIds.size();
        summaryData.put("affectedTransactions", distinctTransactions);
        summaryData.put("topRiskLevel", "CRITICAL");

        List<Map<String, Object>> recentAlerts = new ArrayList<>();
        int limit = Math.min(10, criticalAlerts.size());
        for (int i = 0; i < limit; i++) {
            FraudAlert alert = criticalAlerts.get(i);
            Map<String, Object> alertInfo = new HashMap<>();
            alertInfo.put("alertUuid", alert.getAlertUuid().toString());
            alertInfo.put("fraudScore", alert.getFraudScore());
            alertInfo.put("createdAt", alert.getCreatedAt().toString());
            alertInfo.put("status", alert.getStatus());
            recentAlerts.add(alertInfo);
        }
        summaryData.put("recentAlerts", recentAlerts);

        List<AppUser> recipients = appUserRepository.findByRoleNames(List.of("ADMIN", "MANAGER"));
        for (AppUser user : recipients) {
            try {
                emailService.sendCriticalAlertsSummary(
                        user.getEmail(),
                        user.getFirstName() + " " + user.getLastName(),
                        summaryData
                );
            } catch (Exception e) {
                log.error("Failed to send critical alerts summary to {}: {}", user.getEmail(), e.getMessage());
            }
        }
    }
}
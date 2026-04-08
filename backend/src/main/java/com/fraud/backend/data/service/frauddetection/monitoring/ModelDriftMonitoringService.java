package com.fraud.backend.data.service.frauddetection.monitoring;

import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import com.fraud.backend.data.entity.frauddetection.ModelDeploymentHistory;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.repository.corebanking.TransactionRepository;
import com.fraud.backend.data.repository.frauddetection.FraudAlertRepository;
import com.fraud.backend.data.repository.frauddetection.ModelDeploymentHistoryRepository;
import com.fraud.backend.data.repository.frauddetection.ModelMLRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelDriftMonitoringService {

    private final ModelMLRepository modelMLRepository;
    private final ModelDeploymentHistoryRepository deploymentHistoryRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final TransactionRepository transactionRepository;

    private static final double PERFORMANCE_DEGRADATION_THRESHOLD = 0.08;
    private static final double CRITICAL_DEGRADATION_THRESHOLD = 0.10;
    private static final int MINIMUM_ALERTS_FOR_PERFORMANCE_CHECK = 5;
    private static final int MINIMUM_TRANSACTIONS_FOR_DRIFT_CHECK = 10;

    /**
     * Absolute drift threshold on fraud-score mean.
     */
    private static final double SCORE_DISTRIBUTION_WARNING_THRESHOLD = 0.08;
    private static final double SCORE_DISTRIBUTION_CRITICAL_THRESHOLD = 0.10;

    /**
     * Fallback only if model-specific threshold is missing.
     */
    private static final double DEFAULT_CLASSIFICATION_THRESHOLD = 0.5;

    @Scheduled(cron = "0 0 2 * * *")
    public void scheduledDriftCheck() {
        log.info("Starting scheduled model drift check...");
        checkModelDrift(null);
    }

    @Transactional
    public Map<String, Object> checkModelDrift(Long managerId) {
        Optional<ModelDeploymentHistory> deploymentOpt;
        if (managerId != null) {
            deploymentOpt = deploymentHistoryRepository.findFirstByStatusAndDeployedBy_IdOrderByDeploymentDateDesc("ACTIVE", managerId);
        } else {
            deploymentOpt = deploymentHistoryRepository.findFirstByStatusOrderByDeploymentDateDesc("ACTIVE");
        }

        if (deploymentOpt.isEmpty()) {
            log.warn("No active model deployment found");
            return Map.of("status", "NO_ACTIVE_MODEL");
        }

        ModelDeploymentHistory deployment = deploymentOpt.get();
        ModelML model = deployment.getModel();

        log.info("Checking drift for model: {} (deployed: {})",
                model.getModelName(), deployment.getDeploymentDate());

        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);

        long totalRecentTransactions = transactionRepository.countByModelAndCreatedAtAfter(model, weekAgo);

        if (totalRecentTransactions < MINIMUM_TRANSACTIONS_FOR_DRIFT_CHECK) {
            log.info("Not enough global transactions ({}) for drift analysis. Minimum required: {}",
                    totalRecentTransactions, MINIMUM_TRANSACTIONS_FOR_DRIFT_CHECK);

            return Map.of(
                    "status", "INSUFFICIENT_DATA",
                    "transactions_count", totalRecentTransactions,
                    "minimum_required", MINIMUM_TRANSACTIONS_FOR_DRIFT_CHECK);
        }

        List<FraudAlert> recentAlerts = fraudAlertRepository.findByModelUsedAndCreatedAtAfter(model, weekAgo);
        List<FraudAlert> resolvedAlerts = recentAlerts.stream()
                .filter(a -> a.getStatus() != null &&
                        ("CONFIRMED".equals(a.getStatus()) || "FALSE_POSITIVE".equals(a.getStatus())))
                .toList();

        double deployedThreshold = extractModelThreshold(model);

        Map<String, Double> currentMetrics;
        if (resolvedAlerts.size() >= MINIMUM_ALERTS_FOR_PERFORMANCE_CHECK) {
            currentMetrics = calculateCurrentPerformance(recentAlerts, deployedThreshold);
        } else {
            log.info("Not enough resolved alerts ({}/{} total) for performance analysis. Skipping supervised metrics.",
                    resolvedAlerts.size(), recentAlerts.size());
            currentMetrics = new HashMap<>();
        }

        Map<String, Object> baselineMetrics = deployment.getPerformanceAtDeployment();
        if (baselineMetrics == null) {
            baselineMetrics = new HashMap<>();
        }

        int validationSize = extractValidationSize(model);

        double globalAvgScore = transactionRepository
                .findRecentAverageFraudScoreByModelId(model.getId(), validationSize)
                .orElse(currentMetrics.getOrDefault("avg_fraud_score", 0.0));
        
        // If no alerts calculated avg_fraud_score yet, try to get it from all recent alerts if possible
        if (globalAvgScore == 0.0 && !recentAlerts.isEmpty()) {
            globalAvgScore = recentAlerts.stream()
                    .filter(a -> a.getFraudScore() != null)
                    .mapToDouble(FraudAlert::getFraudScore)
                    .average()
                    .orElse(0.0);
        }

        double baselineAvgScoreForLog = getDoubleFromMapFlexible(
                baselineMetrics, List.of("avg_fraud_score", "avgFraudScore"), 0.5);

        log.info(
                "Drift Calculation - Window Size: {}, Baseline Avg: {}, Global Production Avg: {}, Resolved Samples: {}, Total Samples: {}, Threshold: {}",
                validationSize, baselineAvgScoreForLog, globalAvgScore, resolvedAlerts.size(), recentAlerts.size(), deployedThreshold);

        currentMetrics.put("avg_fraud_score", globalAvgScore);

        Map<String, Object> driftAnalysis = analyzeDrift(
                baselineMetrics, currentMetrics, resolvedAlerts.size(), (int) totalRecentTransactions);

        updateModelDriftMetadata(model, driftAnalysis);

        String driftStatus = (String) driftAnalysis.get("drift_status");
        if ("CRITICAL_DRIFT".equals(driftStatus) || "WARNING".equals(driftStatus)) {
            log.warn("Model drift detected: {}. Consider retraining model.", driftStatus);
        } else {
            log.info("Model drift status is NORMAL");
        }

        return driftAnalysis;
    }

    private Map<String, Double> calculateCurrentPerformance(List<FraudAlert> alerts, double threshold) {
        List<FraudAlert> resolvedAlerts = alerts.stream()
                .filter(a -> a.getStatus() != null &&
                        ("CONFIRMED".equals(a.getStatus()) || "FALSE_POSITIVE".equals(a.getStatus())))
                .toList();

        if (resolvedAlerts.isEmpty()) {
            log.warn("No resolved alerts found, using fraud score statistics only");
            return calculateScoreStatistics(alerts);
        }

        long truePositives = resolvedAlerts.stream()
                .filter(a -> "CONFIRMED".equals(a.getStatus())
                        && a.getFraudScore() != null
                        && a.getFraudScore() >= threshold)
                .count();

        long falsePositives = resolvedAlerts.stream()
                .filter(a -> "FALSE_POSITIVE".equals(a.getStatus())
                        && a.getFraudScore() != null
                        && a.getFraudScore() >= threshold)
                .count();

        long trueNegatives = resolvedAlerts.stream()
                .filter(a -> "FALSE_POSITIVE".equals(a.getStatus())
                        && a.getFraudScore() != null
                        && a.getFraudScore() < threshold)
                .count();

        long falseNegatives = resolvedAlerts.stream()
                .filter(a -> "CONFIRMED".equals(a.getStatus())
                        && a.getFraudScore() != null
                        && a.getFraudScore() < threshold)
                .count();

        double precision = (truePositives + falsePositives) > 0
                ? (double) truePositives / (truePositives + falsePositives)
                : 0.0;

        double recall = (truePositives + falseNegatives) > 0
                ? (double) truePositives / (truePositives + falseNegatives)
                : 0.0;

        double f1Score = (precision + recall) > 0
                ? 2 * (precision * recall) / (precision + recall)
                : 0.0;

        double avgFraudScore = alerts.stream()
                .filter(a -> a.getFraudScore() != null)
                .mapToDouble(FraudAlert::getFraudScore)
                .average()
                .orElse(0.0);

        Map<String, Double> metrics = new HashMap<>();
        metrics.put("precision", precision);
        metrics.put("recall", recall);
        metrics.put("f1_score", f1Score);
        metrics.put("avg_fraud_score", avgFraudScore);
        metrics.put("true_positives", (double) truePositives);
        metrics.put("false_positives", (double) falsePositives);
        metrics.put("true_negatives", (double) trueNegatives);
        metrics.put("false_negatives", (double) falseNegatives);

        return metrics;
    }

    private Map<String, Double> calculateScoreStatistics(List<FraudAlert> alerts) {
        double avgFraudScore = alerts.stream()
                .filter(a -> a.getFraudScore() != null)
                .mapToDouble(FraudAlert::getFraudScore)
                .average()
                .orElse(0.0);

        double stdDevFraudScore = calculateStdDev(
                alerts.stream()
                        .filter(a -> a.getFraudScore() != null)
                        .mapToDouble(FraudAlert::getFraudScore)
                        .toArray());

        Map<String, Double> metrics = new HashMap<>();
        metrics.put("avg_fraud_score", avgFraudScore);
        metrics.put("std_dev_fraud_score", stdDevFraudScore);
        return metrics;
    }

    private Map<String, Object> analyzeDrift(Map<String, Object> baseline,
            Map<String, Double> current,
            int alertsCount,
            int transactionsCount) {

        Map<String, Object> analysis = new HashMap<>();

        double baselineF1 = getDoubleFromMapFlexible(baseline, List.of("f1_score", "f1Score"), 0.0);
        double currentF1 = current.getOrDefault("f1_score", -1.0);

        String performanceStatus;
        double f1Drift = 0.0;

        if (currentF1 >= 0) {
            f1Drift = computeRelativeDrift(baselineF1, currentF1);
            if (f1Drift <= -CRITICAL_DEGRADATION_THRESHOLD) {
                performanceStatus = "CRITICAL";
            } else if (f1Drift <= -PERFORMANCE_DEGRADATION_THRESHOLD) {
                performanceStatus = "WARNING";
            } else {
                performanceStatus = "NORMAL";
            }
        } else {
            performanceStatus = "INSUFFICIENT_DATA";
        }

        double baselineAvgScore = getDoubleFromMapFlexible(
                baseline, List.of("avg_fraud_score", "avgFraudScore"), 0.5);
        double currentAvgScore = current.getOrDefault("avg_fraud_score", baselineAvgScore);

        double scoreDrift = Math.abs(computeAbsoluteDrift(baselineAvgScore, currentAvgScore));
        String distributionStatus;
        if (scoreDrift >= SCORE_DISTRIBUTION_CRITICAL_THRESHOLD) {
            distributionStatus = "CRITICAL";
        } else if (scoreDrift >= SCORE_DISTRIBUTION_WARNING_THRESHOLD) {
            distributionStatus = "WARNING";
        } else {
            distributionStatus = "NORMAL";
        }

        String driftStatus;
        boolean needsRetraining;

        if ("CRITICAL".equals(performanceStatus) || "CRITICAL".equals(distributionStatus)) {
            driftStatus = "CRITICAL_DRIFT";
            needsRetraining = true;
        } else if ("WARNING".equals(performanceStatus) || "WARNING".equals(distributionStatus)) {
            driftStatus = "WARNING";
            needsRetraining = true;
        } else if ("NORMAL".equals(performanceStatus)) {
            driftStatus = "NORMAL";
            needsRetraining = false;
        } else if ("INSUFFICIENT_DATA".equals(performanceStatus)) {
            driftStatus = "NORMAL".equals(distributionStatus) ? "NORMAL" : "WARNING";
            needsRetraining = "WARNING".equals(distributionStatus) || "CRITICAL".equals(distributionStatus);
        } else {
            driftStatus = "NORMAL";
            needsRetraining = false;
        }

        analysis.put("drift_status", driftStatus);
        analysis.put("performance_status", performanceStatus);
        analysis.put("distribution_status", distributionStatus);
        analysis.put("overall_drift_percentage", round(Math.max(Math.max(0, -f1Drift), scoreDrift) * 100, 2));
        analysis.put("f1_drift", round(f1Drift * 100, 2));
        analysis.put("baseline_f1", round(baselineF1 * 100, 2));
        analysis.put("current_f1", currentF1 >= 0 ? round(currentF1 * 100, 2) : 0.0);
        analysis.put("score_distribution_drift", round(scoreDrift * 100, 2));
        analysis.put("baseline_avg_fraud_score", round(baselineAvgScore, 6));
        analysis.put("current_avg_fraud_score", round(currentAvgScore, 6));
        analysis.put("alerts_sample_size", alertsCount);
        analysis.put("distribution_sample_size", transactionsCount);
        analysis.put("checked_at", LocalDateTime.now().toString());
        analysis.put("needs_retraining", needsRetraining);
        analysis.put("status", "SUCCESS");

        return analysis;
    }

    private double computeRelativeDrift(double baseline, double current) {
        if (baseline == 0.0) {
            return 0.0;
        }
        return (current - baseline) / baseline;
    }

    private double computeAbsoluteDrift(double baseline, double current) {
        return current - baseline;
    }

    private void updateModelDriftMetadata(ModelML model, Map<String, Object> driftAnalysis) {
        Map<String, Object> hyperparameters = model.getHyperparameters();
        if (hyperparameters == null) {
            hyperparameters = new HashMap<>();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> driftHistory = (List<Map<String, Object>>) hyperparameters.get("_driftHistory");

        if (driftHistory == null) {
            driftHistory = new ArrayList<>();
        }

        driftHistory.add(new HashMap<>(driftAnalysis));

        if (driftHistory.size() > 30) {
            driftHistory = new ArrayList<>(driftHistory.subList(driftHistory.size() - 30, driftHistory.size()));
        }

        hyperparameters.put("_driftHistory", driftHistory);
        hyperparameters.put("_lastDriftCheck", LocalDateTime.now().toString());
        hyperparameters.put("_currentDriftStatus", driftAnalysis.get("drift_status"));
        hyperparameters.put("_needsRetraining", driftAnalysis.get("needs_retraining"));

        model.setHyperparameters(hyperparameters);
        model.setUpdatedAt(LocalDateTime.now());

        modelMLRepository.save(model);

        log.info("Updated model drift metadata. Status: {}", driftAnalysis.get("drift_status"));
    }

    public Map<String, Object> getDriftStatus(Long managerId) {
        Optional<ModelDeploymentHistory> deploymentOpt;
        if (managerId != null) {
            deploymentOpt = deploymentHistoryRepository.findFirstByStatusAndDeployedBy_IdOrderByDeploymentDateDesc("ACTIVE", managerId);
        } else {
            deploymentOpt = deploymentHistoryRepository.findFirstByStatusOrderByDeploymentDateDesc("ACTIVE");
        }

        if (deploymentOpt.isEmpty()) {
            return Map.of("status", "NO_ACTIVE_MODEL");
        }

        ModelML model = deploymentOpt.get().getModel();
        Map<String, Object> hyperparameters = model.getHyperparameters();

        if (hyperparameters == null || !hyperparameters.containsKey("_driftHistory")) {
            return Map.of(
                    "status", "NO_DRIFT_DATA",
                    "message", "No drift checks have been performed yet",
                    "model_name", model.getModelName(),
                    "last_check", hyperparameters != null
                            ? hyperparameters.getOrDefault("_deployedAt", LocalDateTime.now().toString())
                            : LocalDateTime.now().toString());
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> driftHistory = (List<Map<String, Object>>) hyperparameters.get("_driftHistory");

        Object lastCheck = hyperparameters.get("_lastDriftCheck");
        String lastCheckStr = (lastCheck == null || "Never".equals(lastCheck))
                ? hyperparameters.getOrDefault("_deployedAt", LocalDateTime.now().toString()).toString()
                : lastCheck.toString();

        return Map.of(
                "current_status", hyperparameters.getOrDefault("_currentDriftStatus", "UNKNOWN"),
                "needs_retraining", hyperparameters.getOrDefault("_needsRetraining", false),
                "last_check", lastCheckStr,
                "drift_history", driftHistory,
                "model_name", model.getModelName(),
                "model_version", model.getVersion());
    }

    private int extractValidationSize(ModelML model) {
        try {
            Map<String, Object> params = model.getHyperparameters();
            if (params != null && params.containsKey("_trainingResults")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> results = (Map<String, Object>) params.get("_trainingResults");

                if (results.containsKey("training_stats")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> stats = (Map<String, Object>) results.get("training_stats");
                    if (stats.containsKey("test_rows")) {
                        return ((Number) stats.get("test_rows")).intValue();
                    }
                }

                if (results.containsKey("training_info")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> info = (Map<String, Object>) results.get("training_info");
                    if (info.containsKey("test_rows")) {
                        return ((Number) info.get("test_rows")).intValue();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not extract validation size, using default 1000: {}", e.getMessage());
        }
        return 1000;
    }

    private double extractModelThreshold(ModelML model) {
        try {
            Map<String, Object> params = model.getHyperparameters();
            if (params != null && params.containsKey("_trainingResults")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> results = (Map<String, Object>) params.get("_trainingResults");
                if (results.containsKey("thresholds")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> thresholds = (Map<String, Object>) results.get("thresholds");
                    Object finalObj = thresholds.get("final");
                    if (finalObj instanceof Number number) {
                        return number.doubleValue();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not extract model threshold, using default {}: {}", DEFAULT_CLASSIFICATION_THRESHOLD,
                    e.getMessage());
        }
        return DEFAULT_CLASSIFICATION_THRESHOLD;
    }

    private double getDoubleFromMapFlexible(Map<String, Object> map, List<String> keys, double defaultValue) {
        if (map == null) {
            return defaultValue;
        }

        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
        }

        return defaultValue;
    }

    private double calculateStdDev(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }

        double mean = Arrays.stream(values).average().orElse(0.0);
        double variance = Arrays.stream(values)
                .map(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);

        return Math.sqrt(variance);
    }

    private double round(double value, int places) {
        return BigDecimal.valueOf(value)
                .setScale(places, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
package com.fraud.backend.data.service.frauddetection;

import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import com.fraud.backend.data.entity.frauddetection.ModelDeploymentHistory;
import com.fraud.backend.data.entity.frauddetection.ModelML;
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

/**
 * Service to monitor model drift and performance degradation
 * Tracks metrics and triggers alerts when model needs retraining
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelDriftMonitoringService {

    private final ModelMLRepository modelMLRepository;
    private final ModelDeploymentHistoryRepository deploymentHistoryRepository;
    private final FraudAlertRepository fraudAlertRepository;

    private static final double PERFORMANCE_DEGRADATION_THRESHOLD = 0.15; // 15% degradation
    private static final double CRITICAL_DEGRADATION_THRESHOLD = 0.25;    // 25% degradation
    private static final int MINIMUM_PREDICTIONS_FOR_DRIFT_CHECK = 100;
    private static final double DEFAULT_CLASSIFICATION_THRESHOLD = 0.5;

    /**
     * Check model drift every day at 2 AM
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void scheduledDriftCheck() {
        log.info("Starting scheduled model drift check...");
        checkModelDrift();
    }

    /**
     * Check if deployed model is experiencing drift
     */
    @Transactional
    public Map<String, Object> checkModelDrift() {
        Optional<ModelDeploymentHistory> deploymentOpt =
                deploymentHistoryRepository.findFirstByStatusOrderByDeploymentDateDesc("ACTIVE");

        if (deploymentOpt.isEmpty()) {
            log.warn("No active model deployment found");
            return Map.of("status", "NO_ACTIVE_MODEL");
        }

        ModelDeploymentHistory deployment = deploymentOpt.get();
        ModelML model = deployment.getModel();

        log.info("Checking drift for model: {} (deployed: {})",
                model.getModelName(), deployment.getDeploymentDate());

        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);

        List<FraudAlert> recentAlerts = fraudAlertRepository
                .findByModelUsedAndCreatedAtAfter(model, weekAgo);

        if (recentAlerts.size() < MINIMUM_PREDICTIONS_FOR_DRIFT_CHECK) {
            log.info("Not enough alerts ({}) for drift analysis. Minimum required: {}",
                    recentAlerts.size(), MINIMUM_PREDICTIONS_FOR_DRIFT_CHECK);

            return Map.of(
                    "status", "INSUFFICIENT_DATA",
                    "alerts_count", recentAlerts.size(),
                    "minimum_required", MINIMUM_PREDICTIONS_FOR_DRIFT_CHECK
            );
        }

        Map<String, Double> currentMetrics = calculateCurrentPerformance(recentAlerts, DEFAULT_CLASSIFICATION_THRESHOLD);

        Map<String, Object> baselineMetrics = deployment.getPerformanceAtDeployment();
        if (baselineMetrics == null) {
            baselineMetrics = new HashMap<>();
        }

        Map<String, Object> driftAnalysis = analyzeDrift(baselineMetrics, currentMetrics, recentAlerts.size());

        updateModelDriftMetadata(model, driftAnalysis);

        String driftStatus = (String) driftAnalysis.get("drift_status");
        if ("CRITICAL_DRIFT".equals(driftStatus) || "WARNING".equals(driftStatus)) {
            log.warn("Model drift detected: {}. Consider retraining model.", driftStatus);
        } else {
            log.info("Model drift status is NORMAL");
        }

        return driftAnalysis;
    }

    /**
     * Calculate current model performance from recent alerts
     */
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

    /**
     * Calculate score statistics when we don't have enough ground truth labels
     */
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
                        .toArray()
        );

        Map<String, Double> metrics = new HashMap<>();
        metrics.put("avg_fraud_score", avgFraudScore);
        metrics.put("std_dev_fraud_score", stdDevFraudScore);
        return metrics;
    }

    /**
     * Analyze drift between baseline and current metrics
     */
    private Map<String, Object> analyzeDrift(Map<String, Object> baseline,
                                             Map<String, Double> current,
                                             int sampleSize) {

        Map<String, Object> analysis = new HashMap<>();

        double baselinePrecision = getDoubleFromMapFlexible(baseline, List.of("precision"), 0.0);
        double baselineRecall = getDoubleFromMapFlexible(baseline, List.of("recall"), 0.0);
        double baselineF1 = getDoubleFromMapFlexible(baseline, List.of("f1_score", "f1Score"), 0.0);

        double currentPrecision = current.getOrDefault("precision", 0.0);
        double currentRecall = current.getOrDefault("recall", 0.0);
        double currentF1 = current.getOrDefault("f1_score", 0.0);

        double precisionDrift = calculateDrift(baselinePrecision, currentPrecision);
        double recallDrift = calculateDrift(baselineRecall, currentRecall);
        double f1Drift = calculateDrift(baselineF1, currentF1);

        double overallDrift = (precisionDrift * 0.3) + (recallDrift * 0.3) + (f1Drift * 0.4);

        String driftStatus;
        boolean needsRetraining;

        if (overallDrift <= -CRITICAL_DEGRADATION_THRESHOLD) {
            driftStatus = "CRITICAL_DRIFT";
            needsRetraining = true;
        } else if (overallDrift <= -PERFORMANCE_DEGRADATION_THRESHOLD) {
            driftStatus = "WARNING";
            needsRetraining = true;
        } else {
            driftStatus = "NORMAL";
            needsRetraining = false;
        }

        analysis.put("drift_status", driftStatus);
        analysis.put("overall_drift_percentage", round(overallDrift * 100, 2));
        analysis.put("precision_drift", round(precisionDrift * 100, 2));
        analysis.put("recall_drift", round(recallDrift * 100, 2));
        analysis.put("f1_drift", round(f1Drift * 100, 2));
        analysis.put("baseline_metrics", baseline);
        analysis.put("current_metrics", current);
        analysis.put("sample_size", sampleSize);
        analysis.put("checked_at", LocalDateTime.now().toString());
        analysis.put("needs_retraining", needsRetraining);

        return analysis;
    }

    /**
     * Calculate drift as a relative change
     */
    private double calculateDrift(double baseline, double current) {
        if (baseline == 0.0) {
            return 0.0;
        }
        return (current - baseline) / baseline;
    }

    /**
     * Update model metadata with drift information
     */
    private void updateModelDriftMetadata(ModelML model, Map<String, Object> driftAnalysis) {
        Map<String, Object> hyperparameters = model.getHyperparameters();
        if (hyperparameters == null) {
            hyperparameters = new HashMap<>();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> driftHistory =
                (List<Map<String, Object>>) hyperparameters.get("_driftHistory");

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

    /**
     * Get drift status for deployed model
     */
    public Map<String, Object> getDriftStatus() {
        Optional<ModelDeploymentHistory> deploymentOpt =
                deploymentHistoryRepository.findFirstByStatusOrderByDeploymentDateDesc("ACTIVE");

        if (deploymentOpt.isEmpty()) {
            return Map.of("status", "NO_ACTIVE_MODEL");
        }

        ModelML model = deploymentOpt.get().getModel();
        Map<String, Object> hyperparameters = model.getHyperparameters();

        if (hyperparameters == null || !hyperparameters.containsKey("_driftHistory")) {
            return Map.of(
                    "status", "NO_DRIFT_DATA",
                    "message", "No drift checks have been performed yet"
            );
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> driftHistory =
                (List<Map<String, Object>>) hyperparameters.get("_driftHistory");

        return Map.of(
                "current_status", hyperparameters.getOrDefault("_currentDriftStatus", "UNKNOWN"),
                "needs_retraining", hyperparameters.getOrDefault("_needsRetraining", false),
                "last_check", hyperparameters.getOrDefault("_lastDriftCheck", "Never"),
                "drift_history", driftHistory,
                "model_name", model.getModelName(),
                "model_version", model.getVersion()
        );
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
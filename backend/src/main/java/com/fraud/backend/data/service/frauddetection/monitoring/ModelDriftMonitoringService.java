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
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelDriftMonitoringService {

    private final ModelMLRepository modelMLRepository;
    private final ModelDeploymentHistoryRepository deploymentHistoryRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final TransactionRepository transactionRepository;

    // Base thresholds – used only as fallback when no history available
    private static final double DEFAULT_PERFORMANCE_WARNING_THRESHOLD = 0.08;
    private static final double DEFAULT_PERFORMANCE_CRITICAL_THRESHOLD = 0.10;
    private static final double DEFAULT_SCORE_WARNING_THRESHOLD = 0.08;
    private static final double DEFAULT_SCORE_CRITICAL_THRESHOLD = 0.10;
    private static final double DEFAULT_PSI_WARNING = 0.1;
    private static final double DEFAULT_PSI_CRITICAL = 0.25;

    private static final int MINIMUM_ALERTS_FOR_PERFORMANCE_CHECK = 5;
    private static final int MINIMUM_TRANSACTIONS_FOR_DRIFT_CHECK = 10;
    private static final double DEFAULT_CLASSIFICATION_THRESHOLD = 0.5;

    private static final int PREDICTION_HISTORY_WINDOW = 10;
    private static final int PREDICTION_HORIZON_CHECKS = 3;
    private static final double EXPONENTIAL_WEIGHT_ALPHA = 0.7;
    private static final int SMOOTHING_WINDOW = 3;

    @Scheduled(cron = "0 0 2 * * *")
    public void scheduledDriftCheck() {
        log.info("Starting scheduled model drift check...");
        checkModelDrift(null);
    }

    @Transactional
    public Map<String, Object> checkModelDrift(Long managerId) {
        Optional<ModelDeploymentHistory> deploymentOpt = getActiveDeployment(managerId);
        if (deploymentOpt.isEmpty()) {
            log.warn("No active model deployment found");
            return Map.of("status", "NO_ACTIVE_MODEL");
        }

        ModelDeploymentHistory deployment = deploymentOpt.get();
        ModelML model = deployment.getModel();

        log.info("Checking drift for model: {} (deployed: {})", model.getModelName(), deployment.getDeploymentDate());

        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        long totalRecentTransactions = transactionRepository.countByModelAndCreatedAtAfter(model, weekAgo);

        if (totalRecentTransactions < MINIMUM_TRANSACTIONS_FOR_DRIFT_CHECK) {
            return Map.of(
                    "status", "INSUFFICIENT_DATA",
                    "transactions_count", totalRecentTransactions,
                    "minimum_required", MINIMUM_TRANSACTIONS_FOR_DRIFT_CHECK);
        }

        // UNSUPERVISED: All recent alerts (no label required) – used for score distribution drift
        List<FraudAlert> recentAlerts = fraudAlertRepository.findByModelUsedAndCreatedAtAfter(model, weekAgo);
        List<Double> recentScores = recentAlerts.stream()
                .filter(a -> a.getFraudScore() != null)
                .map(FraudAlert::getFraudScore)
                .toList();

        // SUPERVISED: Only alerts with human feedback – used for performance (F1) drift
        List<FraudAlert> resolvedAlerts = recentAlerts.stream()
                .filter(a -> a.getStatus() != null &&
                        ("CONFIRMED".equals(a.getStatus()) || "FALSE_POSITIVE".equals(a.getStatus())))
                .toList();

        double deployedThreshold = extractModelThreshold(model);

        Map<String, Double> currentMetrics = new HashMap<>();

        // Performance metrics (supervised)
        if (resolvedAlerts.size() >= MINIMUM_ALERTS_FOR_PERFORMANCE_CHECK) {
            currentMetrics.putAll(calculateCurrentPerformance(resolvedAlerts, deployedThreshold));
        } else {
            log.info("Not enough resolved alerts ({}/{}). Performance drift will be marked INSUFFICIENT_DATA.",
                    resolvedAlerts.size(), recentAlerts.size());
        }

        // Score distribution metrics (unsupervised)
        Map<String, Double> scoreStats = calculateScoreStatistics(recentScores);
        currentMetrics.put("avg_fraud_score", scoreStats.get("avg_fraud_score"));
        currentMetrics.put("std_dev_fraud_score", scoreStats.get("std_dev_fraud_score"));

        Map<String, Object> baselineMetrics = deployment.getPerformanceAtDeployment() != null
                ? deployment.getPerformanceAtDeployment()
                : new HashMap<>();

        // PSI using empirical histogram (if scores available) or fallback normal approx
        double psi = calculatePSI(baselineMetrics, recentScores);
        currentMetrics.put("score_psi", psi);

        // Adaptive thresholds from historical drift
        double perfWarn = getDynamicThreshold(model, "performance", DEFAULT_PERFORMANCE_WARNING_THRESHOLD);
        double perfCrit = getDynamicThreshold(model, "performance_critical", DEFAULT_PERFORMANCE_CRITICAL_THRESHOLD);
        double scoreWarn = getDynamicThreshold(model, "score", DEFAULT_SCORE_WARNING_THRESHOLD);
        double scoreCrit = getDynamicThreshold(model, "score_critical", DEFAULT_SCORE_CRITICAL_THRESHOLD);
        double psiWarn = getDynamicThreshold(model, "psi", DEFAULT_PSI_WARNING);
        double psiCrit = getDynamicThreshold(model, "psi_critical", DEFAULT_PSI_CRITICAL);

        Map<String, Object> driftAnalysis = analyzeDrift(
                baselineMetrics, currentMetrics, resolvedAlerts.size(), (int) totalRecentTransactions,
                scoreWarn, scoreCrit, perfWarn, perfCrit, psiWarn, psiCrit);

        if (isAnomalousJump(model, psi)) {
            driftAnalysis.put("drift_status", "ANOMALY_DETECTED");
            driftAnalysis.put("needs_retraining", true);
        }

        updateModelDriftMetadata(model, driftAnalysis);

        String driftStatus = (String) driftAnalysis.get("drift_status");
        if (!"NORMAL".equals(driftStatus)) {
            log.warn("Model drift detected: {}. Consider retraining model.", driftStatus);
        }

        return driftAnalysis;
    }

    public Map<String, Object> predictModelDrift(UUID modelUuid) {
        ModelML model = modelMLRepository.findByModelUuid(modelUuid)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelUuid));

        List<Map<String, Object>> driftHistory = extractDriftHistory(model);
        if (driftHistory == null || driftHistory.size() < 3) {
            return Map.of("status", "INSUFFICIENT_DATA", "message", "Need at least 3 drift history entries");
        }

        driftHistory.sort((a, b) -> ((String) b.get("checked_at")).compareTo((String) a.get("checked_at")));
        List<Map<String, Object>> recent = driftHistory.subList(0, Math.min(PREDICTION_HISTORY_WINDOW, driftHistory.size()));
        Collections.reverse(recent);

        LocalDateTime first = LocalDateTime.parse((String) recent.get(0).get("checked_at"));
        LocalDateTime last = LocalDateTime.parse((String) recent.get(recent.size() - 1).get("checked_at"));
        long daysSpan = ChronoUnit.DAYS.between(first, last);
        String temporalQuality = daysSpan >= 3 ? "GOOD" : "POOR";

        double[] timeIdx = new double[recent.size()];
        double[] f1DriftRaw = new double[recent.size()];
        double[] scoreDriftRaw = new double[recent.size()];
        double[] psiRaw = new double[recent.size()];

        for (int i = 0; i < recent.size(); i++) {
            Map<String, Object> e = recent.get(i);
            timeIdx[i] = i;
            f1DriftRaw[i] = getDouble(e, "f1_drift");
            scoreDriftRaw[i] = getDouble(e, "score_distribution_drift");
            psiRaw[i] = getDouble(e, "score_psi");
        }

        double[] f1Drift = movingAverage(f1DriftRaw, SMOOTHING_WINDOW);
        double[] scoreDrift = movingAverage(scoreDriftRaw, SMOOTHING_WINDOW);
        double[] psiValues = movingAverage(psiRaw, SMOOTHING_WINDOW);

        WeightedRegression f1Reg = new WeightedRegression(EXPONENTIAL_WEIGHT_ALPHA);
        WeightedRegression scoreReg = new WeightedRegression(EXPONENTIAL_WEIGHT_ALPHA);
        WeightedRegression psiReg = new WeightedRegression(EXPONENTIAL_WEIGHT_ALPHA);

        for (int i = 0; i < timeIdx.length; i++) {
            f1Reg.add(timeIdx[i], f1Drift[i]);
            scoreReg.add(timeIdx[i], scoreDrift[i]);
            psiReg.add(timeIdx[i], psiValues[i]);
        }

        double lastIdx = recent.size() - 1;
        double futureIdx = lastIdx + PREDICTION_HORIZON_CHECKS;

        double predictedF1Drift = ensembleForecast(f1Reg, f1Drift, futureIdx);
        double predictedScoreDrift = ensembleForecast(scoreReg, scoreDrift, futureIdx);
        double predictedPsi = ensembleForecast(psiReg, psiValues, futureIdx);

        double f1Lower = f1Reg.predict(futureIdx) - 1.96 * f1Reg.getPredictionStdError(futureIdx);
        double f1Upper = f1Reg.predict(futureIdx) + 1.96 * f1Reg.getPredictionStdError(futureIdx);

        double baselineF1 = getDouble(recent.get(0), "baseline_f1");
        double currentF1 = getDouble(recent.get((int) lastIdx), "current_f1");
        double predictedF1 = baselineF1 * (1 + predictedF1Drift / 100.0);

        String currentState = classifyState(f1Drift[(int) lastIdx], scoreDrift[(int) lastIdx], psiValues[(int) lastIdx]);
        String predictedState = classifyState(predictedF1Drift, predictedScoreDrift, predictedPsi);

        // Velocity
        double f1Velocity = (lastIdx > 0) ? f1Drift[(int) lastIdx] - f1Drift[(int) lastIdx - 1] : 0.0;
        double scoreVelocity = (lastIdx > 0) ? scoreDrift[(int) lastIdx] - scoreDrift[(int) lastIdx - 1] : 0.0;
        double psiVelocity = (lastIdx > 0) ? psiValues[(int) lastIdx] - psiValues[(int) lastIdx - 1] : 0.0;

        // Health score with historical normalization
        double healthScore = computeHealthScore(model, predictedF1Drift, predictedScoreDrift, predictedPsi,
                f1Velocity, scoreVelocity, psiVelocity);

        String riskLevel = determineRiskLevel(predictedState, predictedPsi, f1Velocity, scoreVelocity, f1Reg.getSlope());

        Map<String, Object> prediction = new LinkedHashMap<>();
        prediction.put("model_uuid", modelUuid.toString());
        prediction.put("model_name", model.getModelName());
        prediction.put("current_state", currentState);
        prediction.put("predicted_state", predictedState);
        prediction.put("predicted_in_checks", PREDICTION_HORIZON_CHECKS);
        prediction.put("confidence", computeConfidence(f1Reg.getRSquare(), scoreReg.getRSquare(), temporalQuality));
        prediction.put("temporal_quality", temporalQuality);
        prediction.put("risk_level", riskLevel);
        prediction.put("health_score", round(healthScore, 1));

        prediction.put("velocity", Map.of(
                "f1_velocity", round(f1Velocity, 4),
                "score_velocity", round(scoreVelocity, 4),
                "psi_velocity", round(psiVelocity, 4)
        ));

        prediction.put("trend", Map.of(
                "f1_drift_slope", round(f1Reg.getSlope(), 4),
                "score_drift_slope", round(scoreReg.getSlope(), 4),
                "psi_slope", round(psiReg.getSlope(), 4)
        ));

        prediction.put("current", Map.of(
                "f1_score", round(currentF1, 2),
                "f1_drift_pct", round(f1Drift[(int) lastIdx], 2),
                "score_drift_pct", round(scoreDrift[(int) lastIdx], 2),
                "psi", round(psiValues[(int) lastIdx], 4)
        ));

        prediction.put("predicted", Map.of(
                "f1_score", round(predictedF1, 2),
                "f1_drift_pct", round(predictedF1Drift, 2),
                "f1_confidence_band", Map.of("lower", round(f1Lower, 2), "upper", round(f1Upper, 2)),
                "score_drift_pct", round(predictedScoreDrift, 2),
                "psi", round(predictedPsi, 4)
        ));

        prediction.put("recommendation", generateRecommendation(currentState, predictedState));
        prediction.put("generated_at", LocalDateTime.now().toString());

        return prediction;
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    private double[] movingAverage(double[] values, int window) {
        double[] smoothed = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            int start = Math.max(0, i - window + 1);
            double sum = 0;
            for (int j = start; j <= i; j++) sum += values[j];
            smoothed[i] = sum / (i - start + 1);
        }
        return smoothed;
    }

    private double ensembleForecast(WeightedRegression reg, double[] values, double futureIdx) {
        double linRegPred = reg.predict(futureIdx);
        double avgLast3 = 0;
        int count = 0;
        for (int i = Math.max(0, values.length - 3); i < values.length; i++) {
            avgLast3 += values[i];
            count++;
        }
        avgLast3 /= count;
        double naivePred = values[values.length - 1];
        return linRegPred * 0.5 + avgLast3 * 0.3 + naivePred * 0.2;
    }

    private boolean isAnomalousJump(ModelML model, double currentPsi) {
        List<Map<String, Object>> history = extractDriftHistory(model);
        if (history == null || history.size() < 5) return false;

        double[] psiHistory = history.stream()
                .mapToDouble(e -> getDouble(e, "score_psi"))
                .filter(v -> v > 0)
                .toArray();
        if (psiHistory.length < 5) return false;

        double mean = Arrays.stream(psiHistory).average().orElse(0);
        double std = calculateStdDev(psiHistory);
        return Math.abs(currentPsi - mean) > 3 * std;
    }

    private double getDynamicThreshold(ModelML model, String metric, double fallback) {
        List<Map<String, Object>> history = extractDriftHistory(model);
        if (history == null || history.size() < 5) return fallback;

        double[] values;
        if (metric.startsWith("performance")) {
            values = history.stream()
                    .mapToDouble(e -> Math.abs(getDouble(e, "f1_drift")))
                    .filter(v -> v > 0)
                    .toArray();
        } else if (metric.startsWith("score")) {
            values = history.stream()
                    .mapToDouble(e -> Math.abs(getDouble(e, "score_distribution_drift")))
                    .filter(v -> v > 0)
                    .toArray();
        } else { // psi
            values = history.stream()
                    .mapToDouble(e -> getDouble(e, "score_psi"))
                    .filter(v -> v > 0)
                    .toArray();
        }

        if (values.length < 5) return fallback;

        Arrays.sort(values);
        int idx = (int) Math.ceil(0.95 * values.length) - 1;
        double p95 = values[Math.max(0, idx)];

        if (metric.contains("critical")) {
            return p95 * 1.5;
        } else {
            return p95;
        }
    }

    /**
     * Weighted linear regression with exponential decay.
     * Weights: w_i = α^(n-1-i)  → most recent gets weight 1.
     */
    private static class WeightedRegression {
        private final double alpha;
        private final List<Double> xVals = new ArrayList<>();
        private final List<Double> yVals = new ArrayList<>();
        private double sumW, sumWX, sumWY, sumWXY, sumWXX;

        WeightedRegression(double alpha) { this.alpha = alpha; }

        void add(double x, double y) {
            xVals.add(x);
            yVals.add(y);
            recomputeWeights();
        }

        private void recomputeWeights() {
            int n = xVals.size();
            sumW = sumWX = sumWY = sumWXY = sumWXX = 0;
            for (int i = 0; i < n; i++) {
                double w = Math.pow(alpha, n - 1 - i);
                double xi = xVals.get(i);
                double yi = yVals.get(i);
                sumW += w;
                sumWX += w * xi;
                sumWY += w * yi;
                sumWXY += w * xi * yi;
                sumWXX += w * xi * xi;
            }
        }

        double getSlope() {
            double denominator = sumW * sumWXX - sumWX * sumWX;
            return denominator == 0 ? 0 : (sumW * sumWXY - sumWX * sumWY) / denominator;
        }

        double getIntercept() {
            double denominator = sumW * sumWXX - sumWX * sumWX;
            return denominator == 0 ? 0 : (sumWXX * sumWY - sumWX * sumWXY) / denominator;
        }

        double predict(double x) { return getIntercept() + getSlope() * x; }

        double getRSquare() {
            if (xVals.size() < 2) return 0;
            double meanY = sumWY / sumW;
            double ssTot = 0, ssRes = 0;
            for (int i = 0; i < xVals.size(); i++) {
                double yPred = predict(xVals.get(i));
                double w = Math.pow(alpha, xVals.size() - 1 - i);
                ssTot += w * Math.pow(yVals.get(i) - meanY, 2);
                ssRes += w * Math.pow(yVals.get(i) - yPred, 2);
            }
            return ssTot == 0 ? 0 : 1 - ssRes / ssTot;
        }

        double getPredictionStdError(double x) {
            int n = xVals.size();
            if (n < 3) return 0.1;
            double meanX = sumWX / sumW;
            double ssRes = 0;
            for (int i = 0; i < n; i++) {
                double w = Math.pow(alpha, n - 1 - i);
                ssRes += w * Math.pow(yVals.get(i) - predict(xVals.get(i)), 2);
            }
            double mse = ssRes / (n - 2);
            double variance = mse * (1 + 1.0 / n + Math.pow(x - meanX, 2) / (sumWXX - sumWX * sumWX / sumW));
            return Math.sqrt(Math.max(variance, 0.0001));
        }
    }

    // -------------------------------------------------------------------------
    // PSI calculation (empirical histogram + fallback normal)
    // -------------------------------------------------------------------------
    private double calculatePSI(Map<String, Object> baseline, List<Double> recentScores) {
        // If we have actual scores, compute empirical PSI
        if (!recentScores.isEmpty()) {
            // Baseline mean/std from deployment
            double baselineMean = getDoubleFromMapFlexible(baseline, List.of("avg_fraud_score", "avgFraudScore"), 0.5);
            double baselineStd = getDoubleFromMapFlexible(baseline, List.of("std_dev_fraud_score"), 0.1);
            // Create baseline distribution using normal assumption (since we don't store raw scores)
            // In production, you would store histograms at deployment.
            return computeEmpiricalPSI(baselineMean, baselineStd, recentScores);
        }
        // Fallback: normal approximation using current mean/std only (less accurate)
        return calculatePSINormal(baseline, Map.of("avg_fraud_score", recentScores.stream().mapToDouble(v -> v).average().orElse(0.0)));
    }

    private double computeEmpiricalPSI(double baselineMean, double baselineStd, List<Double> currentScores) {
        int bins = 10;
        double[] expected = new double[bins];
        double[] actual = new double[bins];

        // Expected: from normal distribution with baseline mean/std
        for (int i = 0; i < bins; i++) {
            double lower = i / (double) bins;
            double upper = (i + 1) / (double) bins;
            expected[i] = normalCDF(upper, baselineMean, baselineStd) - normalCDF(lower, baselineMean, baselineStd);
        }

        // Actual: histogram of current scores
        for (double score : currentScores) {
            int bin = (int) Math.floor(score * bins);
            if (bin >= bins) bin = bins - 1;
            if (bin < 0) bin = 0;
            actual[bin]++;
        }
        int total = currentScores.size();
        double psi = 0.0;
        for (int i = 0; i < bins; i++) {
            double exp = Math.max(expected[i], 0.0001);
            double act = actual[i] / (double) total;
            act = Math.max(act, 0.0001);
            psi += (act - exp) * Math.log(act / exp);
        }
        return psi;
    }

    private double calculatePSINormal(Map<String, Object> baseline, Map<String, Double> current) {
        double baselineMean = getDoubleFromMapFlexible(baseline, List.of("avg_fraud_score", "avgFraudScore"), 0.5);
        double currentMean = current.getOrDefault("avg_fraud_score", baselineMean);
        double baselineStd = getDoubleFromMapFlexible(baseline, List.of("std_dev_fraud_score"), 0.1);
        double currentStd = 0.1; // not used in this simplified version
        return computeEmpiricalPSI(baselineMean, baselineStd, List.of()); // fallback to empty
    }

    private double normalCDF(double x, double mean, double std) {
        return 0.5 * (1 + erf((x - mean) / (std * Math.sqrt(2))));
    }

    private double erf(double z) {
        double t = 1.0 / (1.0 + 0.5 * Math.abs(z));
        double ans = 1 - t * Math.exp(-z * z - 1.26551223 +
                t * (1.00002368 + t * (0.37409196 + t * (0.09678418 +
                        t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 +
                                t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        return z >= 0 ? ans : -ans;
    }

    // -------------------------------------------------------------------------
    // State classification (nuanced)
    // -------------------------------------------------------------------------
    private String classifyState(double f1Drift, double scoreDrift, double psi) {
        if (psi > 0.25) return "CRITICAL_DRIFT";
        if (psi > 0.1) return "DISTRIBUTION_SHIFT";
        if (f1Drift <= -8.0) return "F1_DROP";
        if (f1Drift <= -3.0) return "MILD_F1_DROP";
        if (Math.abs(scoreDrift) >= 8.0) return "SCORE_SHIFT";
        return "STABLE";
    }

    private String computeConfidence(double r2F1, double r2Score, String temporalQuality) {
        double avgR2 = (r2F1 + r2Score) / 2.0;
        if ("POOR".equals(temporalQuality)) return "LOW";
        if (avgR2 > 0.7) return "HIGH";
        if (avgR2 > 0.4) return "MEDIUM";
        return "LOW";
    }

    private double computeHealthScore(ModelML model, double f1Drift, double scoreDrift, double psi,
                                      double f1Vel, double scoreVel, double psiVel) {
        // Use historical 95th percentile for normalization
        double maxF1 = getDynamicThreshold(model, "performance", 15.0);
        double maxScore = getDynamicThreshold(model, "score", 15.0);
        double maxPsi = getDynamicThreshold(model, "psi", 0.3);
        double maxVel = 5.0; // velocity threshold could also be dynamic

        double f1Norm = Math.min(Math.abs(f1Drift) / maxF1, 1.0);
        double scoreNorm = Math.min(Math.abs(scoreDrift) / maxScore, 1.0);
        double psiNorm = Math.min(psi / maxPsi, 1.0);
        double velNorm = Math.min((Math.abs(f1Vel) + Math.abs(scoreVel) + Math.abs(psiVel)) / maxVel, 1.0);

        double penalty = 0.3 * f1Norm + 0.3 * scoreNorm + 0.3 * psiNorm + 0.1 * velNorm;
        return Math.max(0.0, 100.0 * (1.0 - penalty));
    }

    private String determineRiskLevel(String state, double psi, double f1Velocity, double scoreVelocity, double f1Slope) {
        if (state.contains("CRITICAL") || psi > 0.25) return "HIGH";
        if (state.contains("SHIFT") || state.contains("DROP") || psi > 0.1) return "MEDIUM";
        if (Math.abs(f1Velocity) > 3.0 || Math.abs(scoreVelocity) > 3.0) return "MEDIUM";
        if (f1Slope < -1.0) return "MEDIUM";
        return "LOW";
    }

    private String generateRecommendation(String current, String predicted) {
        if (current.contains("DRIFT") || predicted.contains("DRIFT") ||
                current.contains("SHIFT") || predicted.contains("SHIFT"))
            return "Retrain model with recent data or adjust classification threshold.";
        if (current.contains("F1_DROP") || predicted.contains("F1_DROP"))
            return "Investigate potential data/concept drift. Consider retraining.";
        if (current.contains("MILD_F1_DROP"))
            return "Monitor closely; slight performance degradation.";
        return "Model is stable. Continue regular monitoring.";
    }

    // -------------------------------------------------------------------------
    // Existing helper methods (slightly modified signatures)
    // -------------------------------------------------------------------------
    private Optional<ModelDeploymentHistory> getActiveDeployment(Long managerId) {
        if (managerId != null)
            return deploymentHistoryRepository.findFirstByStatusAndDeployedBy_IdOrderByDeploymentDateDesc("ACTIVE", managerId);
        return deploymentHistoryRepository.findFirstByStatusOrderByDeploymentDateDesc("ACTIVE");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractDriftHistory(ModelML model) {
        Map<String, Object> hp = model.getHyperparameters();
        if (hp == null) return Collections.emptyList();
        return (List<Map<String, Object>>) hp.getOrDefault("_driftHistory", Collections.emptyList());
    }

    private double getDouble(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return (val instanceof Number) ? ((Number) val).doubleValue() : 0.0;
    }

    private Map<String, Double> calculateCurrentPerformance(List<FraudAlert> resolvedAlerts, double threshold) {
        long truePositives = resolvedAlerts.stream()
                .filter(a -> "CONFIRMED".equals(a.getStatus()) && a.getFraudScore() != null && a.getFraudScore() >= threshold)
                .count();

        long falsePositives = resolvedAlerts.stream()
                .filter(a -> "FALSE_POSITIVE".equals(a.getStatus()) && a.getFraudScore() != null && a.getFraudScore() >= threshold)
                .count();

        long trueNegatives = resolvedAlerts.stream()
                .filter(a -> "FALSE_POSITIVE".equals(a.getStatus()) && a.getFraudScore() != null && a.getFraudScore() < threshold)
                .count();

        long falseNegatives = resolvedAlerts.stream()
                .filter(a -> "CONFIRMED".equals(a.getStatus()) && a.getFraudScore() != null && a.getFraudScore() < threshold)
                .count();

        double precision = (truePositives + falsePositives) > 0 ? (double) truePositives / (truePositives + falsePositives) : 0.0;
        double recall = (truePositives + falseNegatives) > 0 ? (double) truePositives / (truePositives + falseNegatives) : 0.0;
        double f1Score = (precision + recall) > 0 ? 2 * (precision * recall) / (precision + recall) : 0.0;

        Map<String, Double> metrics = new HashMap<>();
        metrics.put("precision", precision);
        metrics.put("recall", recall);
        metrics.put("f1_score", f1Score);
        metrics.put("true_positives", (double) truePositives);
        metrics.put("false_positives", (double) falsePositives);
        metrics.put("true_negatives", (double) trueNegatives);
        metrics.put("false_negatives", (double) falseNegatives);
        return metrics;
    }

    private Map<String, Double> calculateScoreStatistics(List<Double> scores) {
        double avg = scores.stream().mapToDouble(v -> v).average().orElse(0.0);
        double std = calculateStdDev(scores.stream().mapToDouble(v -> v).toArray());
        Map<String, Double> metrics = new HashMap<>();
        metrics.put("avg_fraud_score", avg);
        metrics.put("std_dev_fraud_score", std);
        return metrics;
    }

    private Map<String, Object> analyzeDrift(Map<String, Object> baseline,
                                             Map<String, Double> current,
                                             int alertsCount,
                                             int transactionsCount,
                                             double scoreWarn, double scoreCrit,
                                             double perfWarn, double perfCrit,
                                             double psiWarn, double psiCrit) {

        Map<String, Object> analysis = new HashMap<>();

        double baselineF1 = getDoubleFromMapFlexible(baseline, List.of("f1_score", "f1Score"), 0.0);
        double currentF1 = current.getOrDefault("f1_score", -1.0);

        String performanceStatus;
        double f1Drift = 0.0;
        if (currentF1 >= 0) {
            f1Drift = computeRelativeDrift(baselineF1, currentF1);
            if (f1Drift <= -perfCrit) {
                performanceStatus = "CRITICAL";
            } else if (f1Drift <= -perfWarn) {
                performanceStatus = "WARNING";
            } else {
                performanceStatus = "NORMAL";
            }
        } else {
            performanceStatus = "INSUFFICIENT_DATA";
        }

        double baselineAvgScore = getDoubleFromMapFlexible(baseline, List.of("avg_fraud_score", "avgFraudScore"), 0.5);
        double currentAvgScore = current.getOrDefault("avg_fraud_score", baselineAvgScore);
        double scoreDrift = Math.abs(computeAbsoluteDrift(baselineAvgScore, currentAvgScore));

        String distributionStatus;
        if (scoreDrift >= scoreCrit) {
            distributionStatus = "CRITICAL";
        } else if (scoreDrift >= scoreWarn) {
            distributionStatus = "WARNING";
        } else {
            distributionStatus = "NORMAL";
        }

        double psi = current.getOrDefault("score_psi", 0.0);
        String psiStatus;
        if (psi >= psiCrit) {
            psiStatus = "CRITICAL";
        } else if (psi >= psiWarn) {
            psiStatus = "WARNING";
        } else {
            psiStatus = "NORMAL";
        }

        // Overall drift status: worst of the three
        String driftStatus;
        boolean needsRetraining;
        if (performanceStatus.equals("CRITICAL") || distributionStatus.equals("CRITICAL") || psiStatus.equals("CRITICAL")) {
            driftStatus = "CRITICAL_DRIFT";
            needsRetraining = true;
        } else if (performanceStatus.equals("WARNING") || distributionStatus.equals("WARNING") || psiStatus.equals("WARNING")) {
            driftStatus = "WARNING";
            needsRetraining = true;
        } else if (performanceStatus.equals("NORMAL") && distributionStatus.equals("NORMAL") && psiStatus.equals("NORMAL")) {
            driftStatus = "NORMAL";
            needsRetraining = false;
        } else {
            driftStatus = "NORMAL";
            needsRetraining = false;
        }

        analysis.put("drift_status", driftStatus);
        analysis.put("performance_status", performanceStatus);
        analysis.put("distribution_status", distributionStatus);
        analysis.put("psi_status", psiStatus);
        analysis.put("overall_drift_percentage", round(Math.max(Math.max(Math.abs(f1Drift), scoreDrift) * 100, psi * 100), 2));
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
        analysis.put("score_psi", round(psi, 4));

        return analysis;
    }

    private double computeRelativeDrift(double baseline, double current) {
        if (baseline == 0.0) return 0.0;
        return (current - baseline) / baseline;
    }

    private double computeAbsoluteDrift(double baseline, double current) {
        return current - baseline;
    }

    private void updateModelDriftMetadata(ModelML model, Map<String, Object> driftAnalysis) {
        Map<String, Object> hyperparameters = model.getHyperparameters();
        if (hyperparameters == null) hyperparameters = new HashMap<>();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> driftHistory = (List<Map<String, Object>>) hyperparameters.get("_driftHistory");
        if (driftHistory == null) driftHistory = new ArrayList<>();

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
        Optional<ModelDeploymentHistory> deploymentOpt = getActiveDeployment(managerId);
        if (deploymentOpt.isEmpty()) return Map.of("status", "NO_ACTIVE_MODEL");

        ModelML model = deploymentOpt.get().getModel();
        Map<String, Object> hyperparameters = model.getHyperparameters();

        if (hyperparameters == null || !hyperparameters.containsKey("_driftHistory")) {
            return Map.of(
                    "status", "NO_DRIFT_DATA",
                    "message", "No drift checks have been performed yet",
                    "model_name", model.getModelName(),
                    "last_check", hyperparameters != null ? hyperparameters.getOrDefault("_deployedAt", LocalDateTime.now().toString()) : LocalDateTime.now().toString());
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
                    if (stats.containsKey("test_rows")) return ((Number) stats.get("test_rows")).intValue();
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
                    if (finalObj instanceof Number) return ((Number) finalObj).doubleValue();
                }
            }
        } catch (Exception e) {
            log.warn("Could not extract model threshold, using default {}: {}", DEFAULT_CLASSIFICATION_THRESHOLD, e.getMessage());
        }
        return DEFAULT_CLASSIFICATION_THRESHOLD;
    }

    private double getDoubleFromMapFlexible(Map<String, Object> map, List<String> keys, double defaultValue) {
        if (map == null) return defaultValue;
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Number) return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    private double calculateStdDev(double[] values) {
        if (values.length == 0) return 0.0;
        double mean = Arrays.stream(values).average().orElse(0.0);
        double variance = Arrays.stream(values).map(v -> Math.pow(v - mean, 2)).average().orElse(0.0);
        return Math.sqrt(variance);
    }

    private double round(double value, int places) {
        return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).doubleValue();
    }
}
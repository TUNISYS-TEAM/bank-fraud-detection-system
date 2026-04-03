package com.fraud.backend.api.dto.frauddetection.model.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class ModelMetricsResponse {
    
    private UUID modelUuid;
    private String modelName;
    private String modelType;
    private String version;
    private String status;
    private String validationComment;
    
    // Classification metrics
    private Double accuracy;
    private Double precision;
    private Double recall;
    private Double f1Score;
    private Double aucRoc;
    private Double averagePrecision;
    
    // Confusion matrix details
    private Map<String, Long> confusionMatrix;
    private Long trueNegatives;
    private Long falsePositives;
    private Long falseNegatives;
    private Long truePositives;
    
    // Derived metrics
    private Double specificity;
    private Double falsePositiveRate;
    private Double falseNegativeRate;
    private Double prevalence;
    
    // Feature importance
    private Map<String, Double> featureImportance;
    private List<String> topFeatures;
    private List<String> selectedFeatures;
    
    // Config
    private Map<String, Object> hyperparameters;
    
    // Model paths
    private String onnxPath;
    
    // Thresholds
    private Map<String, Object> thresholds;
    
    public void calculateDerivedMetrics() {
        if (trueNegatives != null && falsePositives != null && (trueNegatives + falsePositives) > 0) {
            this.specificity = (double) trueNegatives / (trueNegatives + falsePositives);
            this.falsePositiveRate = (double) falsePositives / (falsePositives + trueNegatives);
        }
        
        if (falseNegatives != null && truePositives != null && (truePositives + falseNegatives) > 0) {
            this.falseNegativeRate = (double) falseNegatives / (falseNegatives + truePositives);
        }
        
        if (truePositives != null && falseNegatives != null && trueNegatives != null && falsePositives != null) {
            long totalFraud = truePositives + falseNegatives;
            long total = truePositives + trueNegatives + falsePositives + falseNegatives;
            this.prevalence = total > 0 ? (double) totalFraud / total : 0;
        }
    }
}
package com.fraud.backend.api.dto.frauddetection.model.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class TrainingJobResponse {
    
    private UUID jobUuid; // This is just a tracking ID for this training run
    private String jobName;
    private String status; // COMPLETED, FAILED
    
    private UUID datasetUuid;
    private String datasetName;
    
    private UUID modelUuid;
    private String modelName;
    private String modelType;
    
    private Map<String, Object> hyperparameters;
    private Map<String, Object> metrics;
    private Map<String, Object> featureImportance;
    private Map<String, Object> confusionMatrix;
    
    private Integer trainingRows;
    private Integer testRows;
    private Double fraudRate;
    
    private String modelPath; // Path to saved model in MinIO
    
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Integer durationSeconds;
    
    private String errorMessage;
    
    // For UI display
    private Double accuracy;
    private Double precision;
    private Double recall;
    private Double f1Score;
    private Double aucRoc;
    
    public void extractMetrics() {
        if (metrics != null) {
            this.accuracy = getDoubleMetric("accuracy");
            this.precision = getDoubleMetric("precision");
            this.recall = getDoubleMetric("recall");
            this.f1Score = getDoubleMetric("f1_score");
            this.aucRoc = getDoubleMetric("auc_roc");
        }
    }
    
    private Double getDoubleMetric(String key) {
        Object value = metrics.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }
}
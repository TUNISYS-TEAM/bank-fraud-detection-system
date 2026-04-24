package com.fraud.backend.api.dto.frauddetection.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationResponse {
    private UUID simulationId;
    private UUID modelUuid;
    private String modelName;
    private String modelType;
    private String scenario;
    private Double threshold;

    // Métriques globales
    private Integer totalTransactions;
    private Integer totalFraudsInjected;
    private Integer totalDetected;
    private Integer truePositives;
    private Integer falsePositives;
    private Integer trueNegatives;
    private Integer falseNegatives;
    
    private Double detectionRate; // recall
    private Double precision;
    private Double f1Score;
    private Double falsePositiveRate;

    // Distribution des risques
    private Map<String, Integer> riskDistribution;

    // Statistiques des scores
    private Double avgFraudScore;
    private Double maxFraudScore;
    private Double minFraudScore;

    // Détails des transactions (top suspectes)
    private List<SimulatedTransactionResult> topSuspiciousTransactions;

    private Double durationSeconds;
    private LocalDateTime simulatedAt;
}
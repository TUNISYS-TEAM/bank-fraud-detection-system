package com.fraud.backend.data.service.frauddetection.simulation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.backend.api.dto.frauddetection.model.request.SimulationRequest;
import com.fraud.backend.api.dto.frauddetection.model.response.SimulatedTransactionResult;
import com.fraud.backend.api.dto.frauddetection.model.response.ModelSummaryResponse;
import com.fraud.backend.api.dto.frauddetection.model.response.SimulationResponse;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.repository.frauddetection.ModelMLRepository;
import com.fraud.backend.data.service.frauddetection.deployment.InferenceService;
import com.fraud.backend.data.service.frauddetection.training.ModelTrainingService;
import com.fraud.backend.data.service.accessmanagement.UserService;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SimulationService {

    private final ModelMLRepository modelMLRepository;
    private final InferenceService inferenceService;
    private final ModelTrainingService modelTrainingService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Value("${fraud.inference.container-name:fraud-ml-pipeline}")
    private String containerName;

    public SimulationResponse runSimulation(SimulationRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("Starting simulation for model: {} with scenario: {}", request.getModelUuid(), request.getScenario());

        ModelML model = modelMLRepository.findByModelUuid(request.getModelUuid())
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + request.getModelUuid()));

        try {
            // 1. Generate transactions using Python script
            List<Map<String, Object>> simulatedTransactions = generateTransactions(request);
            log.info("Generated {} simulated transactions", simulatedTransactions.size());

            // 2. Prepare items for batch inference
            // Note: The generate script already generates fields like account_id, ip_address, etc.
            // which match what PythonInferenceService expects via transactionToMap
            List<Map<String, Object>> batchItems = simulatedTransactions.stream().map(tx -> {
                Map<String, Object> item = new HashMap<>();
                item.put("transaction", tx);
                // Simulation doesn't use existing behavioral data for now to keep it simple
                // or we could mock it if needed.
                item.put("has_existing_behavior", false);
                return item;
            }).collect(Collectors.toList());

            // 3. Run Batch Prediction
            List<Map<String, Object>> predictions = inferenceService.predictBatch(model.getModelUuid(), batchItems);
            log.info("Received {} predictions from inference service", predictions.size());

            // 4. Aggregate results
            double threshold = inferenceService.getModelThreshold(model);
            return aggregateResults(model, request, simulatedTransactions, predictions, threshold, startTime);

        } catch (Exception e) {
            log.error("Simulation failed: {}", e.getMessage(), e);
            throw new RuntimeException("Simulation failed: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> generateTransactions(SimulationRequest request) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "docker", "exec", containerName,
                "python", "/app/scripts/generate_sample_data.py",
                "--output-format", "json",
                "--n-transactions", String.valueOf(request.getTransactionCount() != null ? request.getTransactionCount() : 1000),
                "--fraud-ratio", String.valueOf(request.getFraudRatio() != null ? request.getFraudRatio() : 0.05),
                "--scenario", request.getScenario() != null ? request.getScenario() : "mixed",
                "--time-distribution", request.getTimeDistribution() != null ? request.getTimeDistribution() : "realistic"
        ));

        if (request.getPeriodHours() != null) {
            command.add("--period-hours");
            command.add(String.valueOf(request.getPeriodHours()));
        }
        if (request.getRandomSeed() != null) {
            command.add("--seed");
            command.add(String.valueOf(request.getRandomSeed()));
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        StringBuilder stdout = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdout.append(line);
            }
        }

        StringBuilder stderr = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stderr.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Transaction generation timed out");
        }

        if (process.exitValue() != 0) {
            throw new RuntimeException("Transaction generation failed with exit code " + process.exitValue() + ". Error: " + stderr);
        }

        String jsonOutput = stdout.toString().trim();
        return objectMapper.readValue(jsonOutput, new TypeReference<List<Map<String, Object>>>() {});
    }

    private SimulationResponse aggregateResults(ModelML model, SimulationRequest request, 
                                                List<Map<String, Object>> transactions, 
                                                List<Map<String, Object>> predictions, 
                                                double threshold, long startTime) {
        
        int total = transactions.size();
        int injectedFrauds = 0;
        int detected = 0;
        int tp = 0, fp = 0, tn = 0, fn = 0;
        
        double sumScore = 0;
        double maxScore = 0;
        double minScore = 1.0;
        
        Map<String, Integer> riskDist = new HashMap<>();
        List<SimulatedTransactionResult> detailedResults = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            Map<String, Object> tx = transactions.get(i);
            Map<String, Object> pred = predictions.get(i);
            
            boolean wasInjected = ((Number) tx.get("fraud")).intValue() == 1;
            double score = ((Number) pred.get("fraud_score")).doubleValue();
            String riskLevel = (String) pred.get("risk_level");
            boolean isDetected = score >= threshold;
            
            if (wasInjected) injectedFrauds++;
            if (isDetected) detected++;
            
            if (isDetected && wasInjected) tp++;
            else if (isDetected && !wasInjected) fp++;
            else if (!isDetected && wasInjected) fn++;
            else tn++;
            
            sumScore += score;
            maxScore = Math.max(maxScore, score);
            minScore = Math.min(minScore, score);
            
            riskDist.put(riskLevel, riskDist.getOrDefault(riskLevel, 0) + 1);
            
            // Add to detailed results (limit to first 100 or top 100 suspectes)
            // Add to detailed results (all transactions for sampling)
            detailedResults.add(SimulatedTransactionResult.builder()
                    .transactionId((String) tx.get("transaction_id"))
                    .amount(((Number) tx.get("TX_Amount")).doubleValue())
                    .channel((String) tx.get("Channel"))
                    .category((String) tx.get("category"))
                    .region((String) tx.get("Region"))
                    .fraudScore(score)
                    .riskLevel(riskLevel)
                    .wasInjectedFraud(wasInjected)
                    .wasDetected(isDetected)
                    .alertType((String) pred.get("alert_type"))
                    .build());
        }

        // Sort detailed results by score descending
        detailedResults.sort((a, b) -> b.getFraudScore().compareTo(a.getFraudScore()));

        double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0;
        double recall = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0;
        double f1 = (precision + recall) > 0 ? 2 * (precision * recall) / (precision + recall) : 0;
        double fpr = (fp + tn) > 0 ? (double) fp / (fp + tn) : 0;

        return SimulationResponse.builder()
                .simulationId(UUID.randomUUID())
                .modelUuid(model.getModelUuid())
                .modelName(model.getModelName())
                .modelType(model.getModelType() != null ? model.getModelType() : "UNKNOWN")
                .scenario(request.getScenario())
                .totalTransactions(total)
                .totalFraudsInjected(injectedFrauds)
                .totalDetected(detected)
                .truePositives(tp)
                .falsePositives(fp)
                .trueNegatives(tn)
                .falseNegatives(fn)
                .detectionRate(recall)
                .precision(precision)
                .f1Score(f1)
                .falsePositiveRate(fpr)
                .riskDistribution(riskDist)
                .avgFraudScore(sumScore / total)
                .maxFraudScore(maxScore)
                .minFraudScore(minScore)
                .topSuspiciousTransactions(detailedResults)
                .durationSeconds((System.currentTimeMillis() - startTime) / 1000.0)
                .simulatedAt(LocalDateTime.now())
                .build();
    }

    @Transactional(readOnly = true)
    public List<ModelSummaryResponse> getAvailableModelsForSimulation() {
        AppUser currentUser = userService.getCurrentUser();
        if (currentUser == null) {
            throw new IllegalStateException("No authenticated user found");
        }

        List<String> allowedStatuses = Arrays.asList("Validated", "Deployed", "Draft");
        return modelMLRepository.findByCreatedByIdAndStatusInOrderByCreatedAtDesc(currentUser.getId(), allowedStatuses)
                .stream()
                .map(modelTrainingService::toSummaryResponse)
                .collect(Collectors.toList());
    }
}
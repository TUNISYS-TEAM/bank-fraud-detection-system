package com.fraud.backend.api.controller.frauddetection;

import com.fraud.backend.api.dto.frauddetection.model.request.TrainingJobRequest;
import com.fraud.backend.api.dto.frauddetection.model.response.ModelMetricsResponse;
import com.fraud.backend.api.dto.frauddetection.model.response.TrainingJobResponse;
import com.fraud.backend.data.service.frauddetection.ModelTrainingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/fraud-detection/models/training")
@RequiredArgsConstructor
public class ModelTrainingController {

    private final ModelTrainingService trainingService;

    @PostMapping("/random-forest")
    public ResponseEntity<TrainingJobResponse> trainRandomForest(
            @Valid @RequestBody TrainingJobRequest request) {
        log.info("Training Random Forest request received for dataset: {}", request.getDatasetUuid());
        try {
            TrainingJobResponse response = trainingService.trainRandomForest(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error training Random Forest: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to train model: " + e.getMessage());
        }
    }

    @GetMapping("/{modelUuid}/metrics")
    public ResponseEntity<ModelMetricsResponse> getModelMetrics(@PathVariable UUID modelUuid) {
        log.info("Getting metrics for model: {}", modelUuid);
        ModelMetricsResponse metrics = trainingService.getModelMetrics(modelUuid);
        return ResponseEntity.ok(metrics);
    }
}
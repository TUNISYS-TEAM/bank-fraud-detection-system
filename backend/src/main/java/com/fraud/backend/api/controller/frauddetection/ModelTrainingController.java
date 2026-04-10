package com.fraud.backend.api.controller.frauddetection;

import com.fraud.backend.api.dto.frauddetection.model.request.TrainingJobRequest;
import com.fraud.backend.api.dto.frauddetection.model.response.ModelMetricsResponse;
import com.fraud.backend.api.dto.frauddetection.model.response.TrainingJobResponse;
import com.fraud.backend.data.service.frauddetection.training.ModelTrainingService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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

    @PostMapping("/xgboost")
    public ResponseEntity<TrainingJobResponse> trainXGBoost(
            @Valid @RequestBody TrainingJobRequest request) {
        log.info("Training XGBoost request received for dataset: {}", request.getDatasetUuid());
        try {
            TrainingJobResponse response = trainingService.trainXGBoost(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error training XGBoost: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to train XGBoost model: " + e.getMessage());
        }
    }

    @PostMapping("/lightgbm")
    public ResponseEntity<TrainingJobResponse> trainLightGBM(
            @Valid @RequestBody TrainingJobRequest request) {
        log.info("Training LightGBM request received for dataset: {}", request.getDatasetUuid());
        try {
            TrainingJobResponse response = trainingService.trainLightGBM(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error training LightGBM: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to train LightGBM model: " + e.getMessage());
        }
    }

    @PostMapping("/balanced-random-forest")
    public ResponseEntity<TrainingJobResponse> trainBalancedRandomForest(
            @Valid @RequestBody TrainingJobRequest request) {
        log.info("Training Balanced Random Forest request received for dataset: {}", request.getDatasetUuid());
        try {
            TrainingJobResponse response = trainingService.trainBalancedRandomForest(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error training Balanced Random Forest: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to train Balanced Random Forest model: " + e.getMessage());
        }
    }

    @GetMapping("/{modelUuid}/metrics")
    public ResponseEntity<ModelMetricsResponse> getModelMetrics(@PathVariable UUID modelUuid) {
        log.info("Getting metrics for model: {}", modelUuid);
        ModelMetricsResponse metrics = trainingService.getModelMetrics(modelUuid);
        return ResponseEntity.ok(metrics);
    }

    /**
     * Get a specific model by its UUID
     */
    @GetMapping("/{modelUuid}")
    public ResponseEntity<TrainingJobResponse> getModelByUuid(@PathVariable UUID modelUuid) {
        log.info("Fetching model with UUID: {}", modelUuid);
        TrainingJobResponse response = trainingService.getModelByUuid(modelUuid);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all models created by the current user
     */
    @GetMapping("/my-models")
    public ResponseEntity<Page<TrainingJobResponse>> getMyModels(@PageableDefault(size = 5) Pageable pageable) {
        log.info("Fetching my models with pagination: {}", pageable);
        return ResponseEntity.ok(trainingService.getMyModels(pageable));
    }

    /**
     * Get all models (for admin users)
     */
    @GetMapping("/all-models")
    public ResponseEntity<Page<TrainingJobResponse>> getAllModels(@PageableDefault(size = 5) Pageable pageable) {
        log.info("Fetching ALL models with pagination: {}", pageable);
        return ResponseEntity.ok(trainingService.getAllModels(pageable));
    }

    @DeleteMapping("/{modelUuid}")
    public ResponseEntity<Void> deleteModel(@PathVariable UUID modelUuid) {
        log.info("Deleting model: {}", modelUuid);
        trainingService.deleteModel(modelUuid);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/finalize-test")
    public ResponseEntity<TrainingJobResponse> finalizeTest(
            @RequestBody TrainingJobResponse testResult) {
        log.info("Finalizing test training result for job: {}", testResult.getJobUuid());
        try {
            TrainingJobResponse response = trainingService.finalizeTestTraining(testResult);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error finalizing test training: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to save test result: " + e.getMessage());
        }
    }

    @PatchMapping("/{modelUuid}/name")
    public ResponseEntity<Void> updateModelName(
            @PathVariable UUID modelUuid,
            @RequestBody java.util.Map<String, String> body) {
        String newName = body.get("name");
        log.info("Updating name for model {}: {}", modelUuid, newName);
        trainingService.updateModelName(modelUuid, newName);
        return ResponseEntity.noContent().build();
    }
}
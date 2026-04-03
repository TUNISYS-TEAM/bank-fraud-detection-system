package com.fraud.backend.data.service.frauddetection.deployment;

import com.fraud.backend.api.dto.frauddetection.model.response.DeploymentResponse;
import com.fraud.backend.api.dto.frauddetection.model.response.ModelSummaryResponse;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.frauddetection.ModelDeploymentHistory;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.repository.corebanking.ClientBehaviorRepository;
import com.fraud.backend.data.repository.corebanking.TransactionRepository;
import com.fraud.backend.data.repository.frauddetection.FraudAlertRepository;
import com.fraud.backend.data.repository.frauddetection.ModelDeploymentHistoryRepository;
import com.fraud.backend.data.repository.frauddetection.ModelMLRepository;
import com.fraud.backend.data.service.frauddetection.scoring.BackfillScoringService;
import com.fraud.backend.data.service.frauddetection.training.ModelProvider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class ModelDeploymentService implements ModelProvider {

    private final ModelMLRepository modelMLRepository;
    private final ModelDeploymentHistoryRepository deploymentHistoryRepository;
    private final BackfillScoringService backfillScoringService;
    private final TransactionRepository transactionRepository;
    private final ClientBehaviorRepository clientBehaviorRepository;
    private final FraudAlertRepository fraudAlertRepository;

    // Self-injection to enable @Async and @Transactional proxy calls
    @Lazy
    @Autowired
    private ModelDeploymentService self;

    public ModelDeploymentService(
            ModelMLRepository modelMLRepository,
            ModelDeploymentHistoryRepository deploymentHistoryRepository,
            @Lazy BackfillScoringService backfillScoringService,
            TransactionRepository transactionRepository,
            ClientBehaviorRepository clientBehaviorRepository,
            FraudAlertRepository fraudAlertRepository) {
        this.modelMLRepository = modelMLRepository;
        this.deploymentHistoryRepository = deploymentHistoryRepository;
        this.backfillScoringService = backfillScoringService;
        this.transactionRepository = transactionRepository;
        this.clientBehaviorRepository = clientBehaviorRepository;
        this.fraudAlertRepository = fraudAlertRepository;
    }

    // Cache for deployed model
    private volatile ModelML cachedDeployedModel;
    private volatile LocalDateTime cachedModelTime;
    private static final long CACHE_TTL_MS = 300_000; // 5 minutes

    public List<ModelSummaryResponse> getValidatedModels() {
        return modelMLRepository.findByStatus("Validated").stream()
                .map(this::toModelSummaryResponse)
                .toList();
    }

    public List<DeploymentResponse> getDeploymentHistory() {
        return deploymentHistoryRepository.findAllByOrderByDeploymentDateDesc().stream()
                .map(this::toDeploymentResponse)
                .toList();
    }

    @Override
    public Optional<ModelML> getDeployedModelEntity() {
        if (cachedDeployedModel != null && cachedModelTime != null) {
            try {
                long currentTimeMillis = System.currentTimeMillis();
                long cachedTimeMillis = cachedModelTime.toEpochSecond(java.time.ZoneOffset.UTC) * 1000;
                if (currentTimeMillis - cachedTimeMillis < CACHE_TTL_MS) {
                    return Optional.of(cachedDeployedModel);
                }
            } catch (Exception e) {
                log.warn("Error checking cache, reloading from DB: {}", e.getMessage());
            }
        }

        Optional<ModelML> model = deploymentHistoryRepository
                .findFirstByStatusOrderByDeploymentDateDesc("ACTIVE")
                .map(ModelDeploymentHistory::getModel);

        model.ifPresent(m -> {
            cachedDeployedModel = m;
            cachedModelTime = LocalDateTime.now();
        });

        return model;
    }

    public Optional<ModelSummaryResponse> getDeployedModelSummary() {
        return getDeployedModelEntity().map(this::toModelSummaryResponse);
    }

    public Optional<DeploymentResponse> getDeployedModelResponse() {
        return deploymentHistoryRepository
                .findFirstByStatusOrderByDeploymentDateDesc("ACTIVE")
                .map(this::toDeploymentResponse);
    }

    @Transactional
    public DeploymentResponse deployModel(UUID modelUuid, String notes, AppUser deployedBy) {
        ModelML model = modelMLRepository.findByModelUuid(modelUuid)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelUuid));

        if (!"Validated".equalsIgnoreCase(model.getStatus())) {
            throw new IllegalStateException("Only validated models can be deployed. Current status: " + model.getStatus());
        }

        deactivateCurrentDeploymentIfExists();
        resetDriftMetadata(model);

        ModelDeploymentHistory deployment = ModelDeploymentHistory.builder()
                .model(model)
                .deployedBy(deployedBy)
                .deploymentDate(LocalDateTime.now())
                .status("ACTIVE")
                .performanceAtDeployment(extractPerformanceSnapshot(model))
                .notes(notes)
                .build();

        ModelDeploymentHistory savedDeployment = deploymentHistoryRepository.save(deployment);

        model.setStatus("Deployed");
        model.setUpdatedAt(LocalDateTime.now());
        modelMLRepository.save(model);

        // Invalidate cache
        cachedDeployedModel = null;
        cachedModelTime = null;

        log.info("Model {} deployed successfully by {}",
                model.getModelName(),
                deployedBy != null ? deployedBy.getEmail() : "system");

        // ⚠️ IMPORTANT: Call through self-proxy to enable @Async
        self.triggerBackfillAfterDeployment();

        return toDeploymentResponse(savedDeployment);
    }

    /**
     * Runs asynchronously in a separate thread pool.
     * Any exception here does NOT affect the deployment transaction.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CompletableFuture<Void> triggerBackfillAfterDeployment() {
        try {
            log.info("=== Triggering backfill after successful deployment (async) ===");
            backfillScoringService.stopBackfill();
            Thread.sleep(800); // grace period
            backfillScoringService.tryStartBackfillIfNeeded();
            log.info("Backfill successfully started after deployment");
        } catch (Exception e) {
            log.error("Failed to start backfill after deployment (deployment was successful)", e);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Transactional
    public void undeployModel(UUID modelUuid) {
        backfillScoringService.stopBackfillDueToUndeploy();

        ModelDeploymentHistory activeDeployment = deploymentHistoryRepository
                .findFirstByModel_ModelUuidAndStatusOrderByDeploymentDateDesc(modelUuid, "ACTIVE")
                .orElseThrow(() -> new IllegalArgumentException("No active deployment found for model: " + modelUuid));

        activeDeployment.setStatus("INACTIVE");
        activeDeployment.setUndeploymentDate(LocalDateTime.now());
        deploymentHistoryRepository.save(activeDeployment);

        ModelML model = activeDeployment.getModel();
        if (model != null) {
            model.setStatus("Validated");
            model.setUpdatedAt(LocalDateTime.now());
            modelMLRepository.save(model);

            cachedDeployedModel = null;
            cachedModelTime = null;

            log.info("Model {} undeployed successfully", model.getModelName());
        }
    }

    private void deactivateCurrentDeploymentIfExists() {
        deploymentHistoryRepository.findFirstByStatusOrderByDeploymentDateDesc("ACTIVE")
                .ifPresent(activeDeployment -> {
                    activeDeployment.setStatus("INACTIVE");
                    activeDeployment.setUndeploymentDate(LocalDateTime.now());
                    deploymentHistoryRepository.save(activeDeployment);

                    ModelML oldModel = activeDeployment.getModel();
                    if (oldModel != null) {
                        oldModel.setStatus("Validated");
                        oldModel.setUpdatedAt(LocalDateTime.now());
                        modelMLRepository.save(oldModel);

                        log.info("Previous active model {} undeployed", oldModel.getModelName());
                    }
                });
    }

    private void resetDriftMetadata(ModelML model) {
        Map<String, Object> hyperparameters = model.getHyperparameters();
        if (hyperparameters == null) {
            hyperparameters = new HashMap<>();
        }

        hyperparameters.put("_driftHistory", new ArrayList<>());
        hyperparameters.put("_lastDriftCheck", "Never");
        hyperparameters.put("_currentDriftStatus", "NORMAL");
        hyperparameters.put("_needsRetraining", false);
        hyperparameters.put("_deployedAt", LocalDateTime.now().toString());

        model.setHyperparameters(hyperparameters);
    }

    private Map<String, Object> extractPerformanceSnapshot(ModelML model) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("auc", model.getAuc());
        snapshot.put("precision", model.getPrecision());
        snapshot.put("recall", model.getRecall());
        snapshot.put("f1Score", model.getF1Score());

        double avgScore = 0.5;
        if (model.getHyperparameters() != null && model.getHyperparameters().containsKey("_trainingResults")) {
            Map<String, Object> trainingResults = (Map<String, Object>) model.getHyperparameters().get("_trainingResults");
            if (trainingResults.containsKey("metrics")) {
                Map<String, Object> metrics = (Map<String, Object>) trainingResults.get("metrics");
                if (metrics.containsKey("avg_fraud_score")) {
                    avgScore = ((Number) metrics.get("avg_fraud_score")).doubleValue();
                }
            }
        }
        snapshot.put("avg_fraud_score", avgScore);

        snapshot.put("version", model.getVersion());
        snapshot.put("modelName", model.getModelName());
        snapshot.put("modelType", model.getModelType());
        return snapshot;
    }

    private ModelSummaryResponse toModelSummaryResponse(ModelML model) {
        if (model == null) return null;

        return ModelSummaryResponse.builder()
                .modelUuid(model.getModelUuid())
                .modelName(model.getModelName())
                .modelType(model.getModelType())
                .version(model.getVersion())
                .status(model.getStatus())
                .createdAt(model.getCreatedAt())
                .createdByEmail(model.getCreatedBy() != null ? model.getCreatedBy().getEmail() : null)
                .createdByName(model.getCreatedBy() != null
                        ? model.getCreatedBy().getFirstName() + " " + model.getCreatedBy().getLastName()
                        : null)
                .auc(model.getAuc())
                .precision(model.getPrecision())
                .recall(model.getRecall())
                .f1Score(model.getF1Score())
                .build();
    }

    private DeploymentResponse toDeploymentResponse(ModelDeploymentHistory history) {
        if (history == null) return null;

        ModelML model = history.getModel();
        AppUser deployedBy = history.getDeployedBy();

        return DeploymentResponse.builder()
                .id(history.getId())
                .modelUuid(model != null ? model.getModelUuid() : null)
                .modelName(model != null ? model.getModelName() : null)
                .modelType(model != null ? model.getModelType() : null)
                .modelVersion(model != null ? model.getVersion() : null)
                .deployedByEmail(deployedBy != null ? deployedBy.getEmail() : null)
                .deployedByName(deployedBy != null
                        ? deployedBy.getFirstName() + " " + deployedBy.getLastName()
                        : null)
                .deploymentDate(history.getDeploymentDate())
                .undeploymentDate(history.getUndeploymentDate())
                .status(history.getStatus())
                .performanceAtDeployment(history.getPerformanceAtDeployment())
                .notes(history.getNotes())
                .build();
    }
}
package com.fraud.backend.data.service.frauddetection.deployment;

import com.fraud.backend.api.dto.frauddetection.model.response.DeploymentResponse;
import com.fraud.backend.api.dto.frauddetection.model.response.ModelSummaryResponse;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.frauddetection.ModelDeploymentHistory;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.repository.frauddetection.ModelDeploymentHistoryRepository;
import com.fraud.backend.data.repository.frauddetection.ModelMLRepository;
import com.fraud.backend.data.service.accessmanagement.UserService;
import com.fraud.backend.data.service.frauddetection.training.ModelProvider;
import com.fraud.backend.data.event.ModelDeploymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelDeploymentService implements ModelProvider {

    private final ModelMLRepository modelMLRepository;
    private final ModelDeploymentHistoryRepository deploymentHistoryRepository;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;

    @Lazy
    @Autowired
    private ModelDeploymentService self;

    // Per‑manager cache (no global cache)
    private final Map<Long, ModelML> perManagerCache = new ConcurrentHashMap<>();
    private final Map<Long, LocalDateTime> cacheTimestamps = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 300_000;

    // Number of bins for score histogram (used for PSI calculation)
    private static final int PSI_HISTOGRAM_BINS = 10;

    private Long getEffectiveManagerId() {
        try {
            AppUser user = userService.getCurrentUser();
            if (user == null) return null;
            String role = user.getRole().getName().toUpperCase();
            if ("ADMIN".equals(role)) return null;
            if ("MANAGER".equals(role)) return user.getId();
            if ("ANALYST".equals(role) && user.getManager() != null) return user.getManager().getId();
        } catch (Exception e) {
            log.warn("Could not determine effective manager: {}", e.getMessage());
        }
        return null;
    }

    public List<ModelSummaryResponse> getValidatedModels(Long managerId) {
        Long effectiveId = managerId != null ? managerId : getEffectiveManagerId();
        if (effectiveId != null) {
            return modelMLRepository.findByStatus("Validated").stream()
                    .filter(m -> m.getCreatedBy() != null &&
                            (m.getCreatedBy().getId().equals(effectiveId) ||
                             (m.getCreatedBy().getManager() != null && m.getCreatedBy().getManager().getId().equals(effectiveId))))
                    .map(this::toModelSummaryResponse)
                    .toList();
        }
        return modelMLRepository.findByStatus("Validated").stream()
                .map(this::toModelSummaryResponse)
                .toList();
    }

    public List<DeploymentResponse> getDeploymentHistory(Long managerId) {
        Long effectiveId = managerId != null ? managerId : getEffectiveManagerId();
        if (effectiveId != null) {
            return deploymentHistoryRepository.findAllByDeployedBy_IdOrderByDeploymentDateDesc(effectiveId).stream()
                    .map(this::toDeploymentResponse)
                    .toList();
        }
        return deploymentHistoryRepository.findAllByOrderByDeploymentDateDesc().stream()
                .map(this::toDeploymentResponse)
                .toList();
    }

    @Override
    public Optional<ModelML> getDeployedModelEntity() {
        return getDeployedModelEntity(null);
    }

    @Override
    public Optional<ModelML> getDeployedModelEntity(Long managerId) {
        Long effectiveId = managerId != null ? managerId : getEffectiveManagerId();
        if (effectiveId == null) {
            return Optional.empty();
        }

        // Check per‑manager cache
        LocalDateTime cachedTime = cacheTimestamps.get(effectiveId);
        ModelML cached = perManagerCache.get(effectiveId);
        if (cached != null && cachedTime != null &&
            Duration.between(cachedTime, LocalDateTime.now()).toMillis() < CACHE_TTL_MS) {
            return Optional.of(cached);
        }

        Optional<ModelML> model = deploymentHistoryRepository
                .findFirstByStatusAndDeployedBy_IdOrderByDeploymentDateDesc("ACTIVE", effectiveId)
                .map(ModelDeploymentHistory::getModel);

        if (model.isPresent()) {
            perManagerCache.put(effectiveId, model.get());
            cacheTimestamps.put(effectiveId, LocalDateTime.now());
        } else {
            perManagerCache.remove(effectiveId);
            cacheTimestamps.remove(effectiveId);
        }
        return model;
    }

    public void evictCacheForManager(Long managerId) {
        if (managerId != null) {
            perManagerCache.remove(managerId);
            cacheTimestamps.remove(managerId);
            log.debug("Cache evicted for manager {}", managerId);
        }
    }

    public Optional<ModelSummaryResponse> getDeployedModelSummary(Long managerId) {
        return getDeployedModelEntity(managerId).map(this::toModelSummaryResponse);
    }

    public Optional<DeploymentResponse> getDeployedModelResponse(Long managerId) {
        Long effectiveId = managerId != null ? managerId : getEffectiveManagerId();
        if (effectiveId != null) {
            return deploymentHistoryRepository
                    .findFirstByStatusAndDeployedBy_IdOrderByDeploymentDateDesc("ACTIVE", effectiveId)
                    .map(this::toDeploymentResponse);
        }
        return Optional.empty();
    }

    @Transactional
    public DeploymentResponse deployModel(UUID modelUuid, String notes, AppUser deployedBy) {
        ModelML model = modelMLRepository.findByModelUuid(modelUuid)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelUuid));

        if (!"Validated".equalsIgnoreCase(model.getStatus())) {
            throw new IllegalStateException("Only validated models can be deployed. Current status: " + model.getStatus());
        }

        Long managerId = deployedBy != null ? deployedBy.getId() : null;
        deactivateCurrentDeploymentIfExists(managerId);
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

        evictCacheForManager(managerId);

        log.info("Model {} deployed successfully by {}",
                model.getModelName(),
                deployedBy != null ? deployedBy.getEmail() : "system");

        eventPublisher.publishEvent(new ModelDeploymentEvent(modelUuid, ModelDeploymentEvent.Action.DEPLOYED, managerId));

        return toDeploymentResponse(savedDeployment);
    }

    @Transactional
    public void undeployModel(UUID modelUuid) {
        ModelDeploymentHistory activeDeployment = deploymentHistoryRepository
                .findFirstByModel_ModelUuidAndStatusOrderByDeploymentDateDesc(modelUuid, "ACTIVE")
                .orElseThrow(() -> new IllegalArgumentException("No active deployment found for model: " + modelUuid));

        Long managerId = activeDeployment.getDeployedBy() != null ? activeDeployment.getDeployedBy().getId() : null;

        // Evict cache immediately
        evictCacheForManager(managerId);

        // Publish event before changing status so backfill can stop gracefully
        eventPublisher.publishEvent(new ModelDeploymentEvent(modelUuid, ModelDeploymentEvent.Action.UNDEPLOYED, managerId));

        activeDeployment.setStatus("INACTIVE");
        activeDeployment.setUndeploymentDate(LocalDateTime.now());
        deploymentHistoryRepository.save(activeDeployment);

        ModelML model = activeDeployment.getModel();
        if (model != null) {
            log.info("Model {} undeployed – scoring data preserved for future backfill", model.getModelName());

            model.setStatus("Validated");
            model.setUpdatedAt(LocalDateTime.now());
            modelMLRepository.save(model);

            log.info("Model {} undeployed successfully", model.getModelName());
        }
    }

    private void deactivateCurrentDeploymentIfExists(Long managerId) {
        if (managerId == null) return;

        Optional<ModelDeploymentHistory> activeOpt = deploymentHistoryRepository
                .findFirstByStatusAndDeployedBy_IdOrderByDeploymentDateDesc("ACTIVE", managerId);

        activeOpt.ifPresent(activeDeployment -> {
            activeDeployment.setStatus("INACTIVE");
            activeDeployment.setUndeploymentDate(LocalDateTime.now());
            deploymentHistoryRepository.save(activeDeployment);

            ModelML oldModel = activeDeployment.getModel();
            if (oldModel != null) {
                oldModel.setStatus("Validated");
                oldModel.setUpdatedAt(LocalDateTime.now());
                modelMLRepository.save(oldModel);
                evictCacheForManager(managerId);
                log.info("Previous active model {} deactivated (scoring data preserved)", oldModel.getModelName());
            }
        });
    }

    private void resetDriftMetadata(ModelML model) {
        Map<String, Object> hyperparameters = model.getHyperparameters();
        if (hyperparameters == null) hyperparameters = new HashMap<>();

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
        List<Double> validationScores = null;
        if (model.getHyperparameters() != null && model.getHyperparameters().containsKey("_trainingResults")) {
            Map<String, Object> trainingResults = (Map<String, Object>) model.getHyperparameters().get("_trainingResults");
            if (trainingResults.containsKey("metrics")) {
                Map<String, Object> metrics = (Map<String, Object>) trainingResults.get("metrics");
                if (metrics.containsKey("avg_fraud_score")) {
                    avgScore = ((Number) metrics.get("avg_fraud_score")).doubleValue();
                }
            }
            // Try to retrieve validation scores for histogram
            if (trainingResults.containsKey("validation_scores")) {
                validationScores = (List<Double>) trainingResults.get("validation_scores");
            }
        }
        snapshot.put("avg_fraud_score", avgScore);
        snapshot.put("version", model.getVersion());
        snapshot.put("modelName", model.getModelName());
        snapshot.put("modelType", model.getModelType());

        // Build histogram for PSI calculation (10 bins)
        List<Double> histogram = buildScoreHistogram(validationScores, avgScore);
        snapshot.put("score_histogram", histogram);

        return snapshot;
    }

    /**
     * Build a normalized histogram (10 bins) from validation scores.
     * If no scores are available, fallback to a uniform distribution.
     */
    private List<Double> buildScoreHistogram(List<Double> scores, double defaultMean) {
        List<Double> histogram = new ArrayList<>(PSI_HISTOGRAM_BINS);
        for (int i = 0; i < PSI_HISTOGRAM_BINS; i++) {
            histogram.add(0.0);
        }

        if (scores == null || scores.isEmpty()) {
            // Fallback: uniform distribution
            double uniform = 1.0 / PSI_HISTOGRAM_BINS;
            for (int i = 0; i < PSI_HISTOGRAM_BINS; i++) {
                histogram.set(i, uniform);
            }
            return histogram;
        }

        int total = scores.size();
        for (double score : scores) {
            int bin = (int) Math.floor(score * PSI_HISTOGRAM_BINS);
            if (bin >= PSI_HISTOGRAM_BINS) bin = PSI_HISTOGRAM_BINS - 1;
            if (bin < 0) bin = 0;
            histogram.set(bin, histogram.get(bin) + 1.0);
        }

        // Normalize
        for (int i = 0; i < PSI_HISTOGRAM_BINS; i++) {
            histogram.set(i, histogram.get(i) / total);
        }
        return histogram;
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
                .createdByName(model.getCreatedBy() != null ? model.getCreatedBy().getFirstName() + " " + model.getCreatedBy().getLastName() : null)
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
                .deployedByName(deployedBy != null ? deployedBy.getFirstName() + " " + deployedBy.getLastName() : null)
                .deploymentDate(history.getDeploymentDate())
                .undeploymentDate(history.getUndeploymentDate())
                .status(history.getStatus())
                .performanceAtDeployment(history.getPerformanceAtDeployment())
                .notes(history.getNotes())
                .build();
    }
}
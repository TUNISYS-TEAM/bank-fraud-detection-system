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
import com.fraud.backend.data.repository.corebanking.ClientBehaviorRepository;
import com.fraud.backend.data.repository.corebanking.TransactionRepository;
import com.fraud.backend.data.repository.frauddetection.FraudAlertRepository;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelDeploymentService implements ModelProvider {

    private final ModelMLRepository modelMLRepository;
    private final ModelDeploymentHistoryRepository deploymentHistoryRepository;
    private final TransactionRepository transactionRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final ClientBehaviorRepository clientBehaviorRepository;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;

    // Self-injection to enable @Async and @Transactional proxy calls
    @Lazy
    @Autowired
    private ModelDeploymentService self;

    // Cache for deployed model
    private volatile ModelML cachedDeployedModel;
    private volatile LocalDateTime cachedModelTime;
    private static final long CACHE_TTL_MS = 300_000; // 5 minutes

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
        
        // Cache management only for current user context (when managerId is null)
        if (managerId == null && cachedDeployedModel != null && cachedModelTime != null) {
            if (Duration.between(cachedModelTime, LocalDateTime.now()).toMillis() < CACHE_TTL_MS) {
                return Optional.of(cachedDeployedModel);
            }
        }

        Optional<ModelML> model;
        if (effectiveId != null) {
            // Find for specific manager
            model = deploymentHistoryRepository.findFirstByStatusAndDeployedBy_IdOrderByDeploymentDateDesc("ACTIVE", effectiveId)
                    .map(ModelDeploymentHistory::getModel);
        } else {
            // Global find (potential for startup scenarios, though we are disabling StartupRunner)
            model = deploymentHistoryRepository.findFirstByStatusOrderByDeploymentDateDesc("ACTIVE")
                    .map(ModelDeploymentHistory::getModel);
        }
        
        // Update cache only if it's the current user's context
        if (managerId == null && model.isPresent()) {
            cachedDeployedModel = model.get();
            cachedModelTime = LocalDateTime.now();
        }

        return model;
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

        // Invalidate cache
        cachedDeployedModel = null;
        cachedModelTime = null;

        log.info("Model {} deployed successfully by {}",
                model.getModelName(),
                deployedBy != null ? deployedBy.getEmail() : "system");

        // Notify other services (like Backfill)
        eventPublisher.publishEvent(new ModelDeploymentEvent(modelUuid, ModelDeploymentEvent.Action.DEPLOYED, managerId));

        return toDeploymentResponse(savedDeployment);
    }



    @Transactional
    public void undeployModel(UUID modelUuid) {
        ModelDeploymentHistory activeDeployment = deploymentHistoryRepository
                .findFirstByModel_ModelUuidAndStatusOrderByDeploymentDateDesc(modelUuid, "ACTIVE")
                .orElseThrow(() -> new IllegalArgumentException("No active deployment found for model: " + modelUuid));

        Long managerId = activeDeployment.getDeployedBy() != null ? activeDeployment.getDeployedBy().getId() : null;

        // Notify other services (like Backfill)
        eventPublisher.publishEvent(new ModelDeploymentEvent(modelUuid, ModelDeploymentEvent.Action.UNDEPLOYED, managerId));

        activeDeployment.setStatus("INACTIVE");
        activeDeployment.setUndeploymentDate(LocalDateTime.now());
        deploymentHistoryRepository.save(activeDeployment);

        ModelML model = activeDeployment.getModel();
        if (model != null) {
            cleanupManagerMlData(model.getModelUuid(), managerId);
            
            model.setStatus("Validated");
            model.setUpdatedAt(LocalDateTime.now());
            modelMLRepository.save(model);

            cachedDeployedModel = null;
            cachedModelTime = null;

            log.info("Model {} undeployed successfully", model.getModelName());
        }
    }

    private void cleanupManagerMlData(UUID modelUuid, Long managerId) {
        log.info("Starting selective ML data cleanup for manager {} and model {}", managerId, modelUuid);
        try {
            fraudAlertRepository.deleteByModelUsed_ModelUuid(modelUuid);
            
            if (managerId != null) {
                // 1. Get all account IDs belonging to this manager's dataset
                List<Long> accountIds = transactionRepository.findAccountIdsByManagerId(managerId);
                
                // 2. Perform selective cleanup
                transactionRepository.resetScoringDataForManager(modelUuid, managerId);
                
                if (accountIds != null && !accountIds.isEmpty()) {
                    log.info("Cleanup: Found {} accounts to clear behavior for manager {}", accountIds.size(), managerId);
                    clientBehaviorRepository.deleteByAccountIdIn(accountIds);
                } else {
                    log.warn("Cleanup: No accounts found for manager {}, skipping behavior deletion", managerId);
                }
            } else {
                log.warn("Cleanup: No manager ID found, skipping transaction reset and client behavior deletion");
            }
            log.info("Selective ML data cleanup completed successfully");
        } catch (Exception e) {
            log.error("Error during ML data cleanup for model {}: {}", modelUuid, e.getMessage());
        }
    }

    private void deactivateCurrentDeploymentIfExists(Long managerId) {
        Optional<ModelDeploymentHistory> activeOpt = managerId != null
                ? deploymentHistoryRepository.findFirstByStatusAndDeployedBy_IdOrderByDeploymentDateDesc("ACTIVE", managerId)
                : deploymentHistoryRepository.findFirstByStatusOrderByDeploymentDateDesc("ACTIVE");

        activeOpt.ifPresent(activeDeployment -> {
            activeDeployment.setStatus("INACTIVE");
            activeDeployment.setUndeploymentDate(LocalDateTime.now());
            deploymentHistoryRepository.save(activeDeployment);

            ModelML oldModel = activeDeployment.getModel();
            if (oldModel != null) {
                // Perform cleanup for the old model being replaced
                cleanupManagerMlData(oldModel.getModelUuid(), managerId);

                oldModel.setStatus("Validated");
                oldModel.setUpdatedAt(LocalDateTime.now());
                modelMLRepository.save(oldModel);

                log.info("Previous active model {} undeployed and cleaned up for manager {}", oldModel.getModelName(), managerId);
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
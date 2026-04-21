package com.fraud.backend.data.service.frauddetection.training;

import com.fraud.backend.api.dto.frauddetection.model.request.TrainingJobRequest;
import com.fraud.backend.api.dto.frauddetection.model.response.ModelMetricsResponse;
import com.fraud.backend.api.dto.frauddetection.model.response.ModelSummaryResponse;
import com.fraud.backend.api.dto.frauddetection.model.response.TrainingJobResponse;
import com.fraud.backend.api.dto.messaging.request.ModelValidationActionDTO;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.accessmanagement.KeycloakMapping;
import com.fraud.backend.data.entity.frauddetection.Dataset;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.entity.frauddetection.Comment;
import com.fraud.backend.data.enums.datasets.DatasetStatus;
import com.fraud.backend.data.enums.InferenceEngine;
import com.fraud.backend.data.repository.frauddetection.CommentRepository;
import com.fraud.backend.data.repository.accessmanagement.KeycloakMappingRepository;
import com.fraud.backend.data.repository.frauddetection.DatasetRepository;
import com.fraud.backend.data.repository.frauddetection.ModelDeploymentHistoryRepository;
import com.fraud.backend.data.repository.frauddetection.ModelMLRepository;
import com.fraud.backend.data.service.storage.StorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelTrainingService {

    private final DatasetRepository datasetRepository;
    private final ModelMLRepository modelMLRepository;
    private final CommentRepository commentRepository;
    private final ModelDeploymentHistoryRepository modelDeploymentHistoryRepository;
    private final KeycloakMappingRepository keycloakMappingRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    @Value("${fraud.ml.onnx.container-name:fraud-ml-onnx}")
    private String onnxContainerName;

    @Value("${fraud.ml.hybrid.container-name:fraud-ml-hybrid}")
    private String hybridContainerName;

    private AppUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            log.error("No authentication found");
            throw new IllegalStateException("Utilisateur non authentifié");
        }

        Object principal = authentication.getPrincipal();
        String email = null;

        if (principal instanceof Jwt) {
            Jwt jwt = (Jwt) principal;
            email = jwt.getClaim("preferred_username");
            if (email == null)
                email = jwt.getClaim("email");
            if (email == null)
                email = jwt.getClaim("sub");
        } else if (principal instanceof org.springframework.security.core.userdetails.UserDetails) {
            email = ((org.springframework.security.core.userdetails.UserDetails) principal).getUsername();
        } else if (principal instanceof String) {
            email = (String) principal;
        }

        if (email == null || email.isEmpty()) {
            email = authentication.getName();
        }

        final String userEmail = email;
        if (userEmail == null || userEmail.isEmpty()) {
            throw new IllegalStateException("Impossible d'extraire l'email de l'authentification");
        }

        return keycloakMappingRepository.findByEmail(userEmail)
                .map(KeycloakMapping::getUser)
                .orElseThrow(() -> new IllegalStateException("Utilisateur non trouvé avec l'email: " + userEmail));
    }

    @SuppressWarnings("unchecked")
    private TrainingJobResponse toTrainingJobResponse(ModelML model) {
        if (model == null)
            return null;

        Map<String, Object> metrics = new HashMap<>();
        Map<String, Object> confusionMatrix = new HashMap<>();
        Integer trainingRows = null;
        Integer testRows = null;
        Double fraudRate = null;
        String modelPath = null;
        String pipelinePath = null;
        String onnxPath = null;
        Long durationSeconds = null;
        Map<String, Object> thresholds = new HashMap<>();

        if (model.getHyperparameters() != null && model.getHyperparameters().containsKey("_trainingResults")) {
            Map<String, Object> trainingResults = (Map<String, Object>) model.getHyperparameters()
                    .get("_trainingResults");

            if (trainingResults.containsKey("metrics"))
                metrics = (Map<String, Object>) trainingResults.get("metrics");
            if (trainingResults.containsKey("confusionMatrix"))
                confusionMatrix = (Map<String, Object>) trainingResults.get("confusionMatrix");
            if (trainingResults.containsKey("trainingRows"))
                trainingRows = (Integer) trainingResults.get("trainingRows");
            if (trainingResults.containsKey("testRows"))
                testRows = (Integer) trainingResults.get("testRows");
            if (trainingResults.containsKey("fraudRate"))
                fraudRate = (Double) trainingResults.get("fraudRate");
            if (trainingResults.containsKey("modelPath"))
                modelPath = (String) trainingResults.get("modelPath");
            if (trainingResults.containsKey("pipelinePath"))
                pipelinePath = (String) trainingResults.get("pipelinePath");
            if (trainingResults.containsKey("onnxPath"))
                onnxPath = (String) trainingResults.get("onnxPath");
            if (trainingResults.containsKey("thresholds"))
                thresholds = (Map<String, Object>) trainingResults.get("thresholds");
            if (trainingResults.containsKey("durationSeconds")) {
                Object durationObj = trainingResults.get("durationSeconds");
                if (durationObj instanceof Number number)
                    durationSeconds = number.longValue();
            }
        }

        Map<String, Object> validatedByInfo = null;
        if (model.getValidatedBy() != null) {
            validatedByInfo = Map.of(
                    "id", model.getValidatedBy().getId(),
                    "email", model.getValidatedBy().getEmail(),
                    "firstName", model.getValidatedBy().getFirstName(),
                    "lastName", model.getValidatedBy().getLastName());
        }

        if (durationSeconds == null && model.getCreatedAt() != null && model.getUpdatedAt() != null) {
            durationSeconds = java.time.Duration.between(model.getCreatedAt(), model.getUpdatedAt()).getSeconds();
        }

        TrainingJobResponse response = TrainingJobResponse.builder()
                .modelUuid(model.getModelUuid())
                .modelName(model.getModelName())
                .modelType(model.getModelType())
                .version(model.getVersion())
                .status(model.getStatus())
                .metrics(metrics)
                .confusionMatrix(confusionMatrix)
                .thresholds(thresholds)
                .hyperparameters(model.getHyperparameters())
                .featureImportance(model.getFeatureImportance())
                .selectedFeatures(model.getHyperparameters() != null
                        ? (List<String>) model.getHyperparameters().get("selected_original_features")
                        : null)
                .trainingRows(trainingRows)
                .testRows(testRows)
                .fraudRate(fraudRate)
                .modelPath(modelPath)
                .pipelinePath(pipelinePath)
                .onnxPath(onnxPath)
                .startedAt(model.getCreatedAt())
                .completedAt(model.getUpdatedAt())
                .durationSeconds(durationSeconds != null ? durationSeconds.intValue() : null)
                .validatedBy(validatedByInfo)
                .build();

        response.setAucRoc(model.getAuc());
        response.setPrecision(model.getPrecision());
        response.setRecall(model.getRecall());
        response.setF1Score(model.getF1Score());

        return response;
    }

    public ModelSummaryResponse toSummaryResponse(ModelML model) {
        if (model == null)
            return null;

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

    public List<TrainingJobResponse> getModelHistory() {
        return modelMLRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toTrainingJobResponse)
                .toList();
    }

    public Page<TrainingJobResponse> getModelHistory(Pageable pageable) {
        return modelMLRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(this::toTrainingJobResponse);
    }

    public TrainingJobResponse getModelByUuid(UUID modelUuid) {
        ModelML model = modelMLRepository.findByModelUuid(modelUuid)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelUuid));
        return toTrainingJobResponse(model);
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void deleteModel(UUID modelUuid) {
        ModelML model = modelMLRepository.findByModelUuid(modelUuid)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelUuid));

        String modelPath = null;
        String pipelinePath = null;
        String onnxPath = null;

        if (model.getHyperparameters() != null && model.getHyperparameters().containsKey("_trainingResults")) {
            Map<String, Object> trainingResults = (Map<String, Object>) model.getHyperparameters()
                    .get("_trainingResults");
            if (trainingResults.containsKey("modelPath"))
                modelPath = (String) trainingResults.get("modelPath");
            if (trainingResults.containsKey("pipelinePath"))
                pipelinePath = (String) trainingResults.get("pipelinePath");
            if (trainingResults.containsKey("onnxPath"))
                onnxPath = (String) trainingResults.get("onnxPath");
        }

        if (modelPath == null && model.getHyperparameters() != null
                && model.getHyperparameters().containsKey("modelPath")) {
            modelPath = (String) model.getHyperparameters().get("modelPath");
        }
        if (pipelinePath == null && model.getHyperparameters() != null
                && model.getHyperparameters().containsKey("pipelinePath")) {
            pipelinePath = (String) model.getHyperparameters().get("pipelinePath");
        }
        if (onnxPath == null && model.getHyperparameters() != null
                && model.getHyperparameters().containsKey("onnxPath")) {
            onnxPath = (String) model.getHyperparameters().get("onnxPath");
        }

        if (modelPath != null && !modelPath.isBlank()) {
            try {
                storageService.delete(modelPath);
                log.info("Deleted model file from MinIO: {}", modelPath);
            } catch (Exception e) {
                log.warn("Failed to delete model file from MinIO: {}", e.getMessage());
            }
        }

        if (pipelinePath != null && !pipelinePath.isBlank()) {
            try {
                storageService.delete(pipelinePath);
                log.info("Deleted pipeline file from MinIO: {}", pipelinePath);
            } catch (Exception e) {
                log.warn("Failed to delete pipeline file from MinIO: {}", e.getMessage());
            }
        }

        if (onnxPath != null && !onnxPath.isBlank()) {
            try {
                storageService.delete(onnxPath);
                log.info("Deleted ONNX file from MinIO: {}", onnxPath);
            } catch (Exception e) {
                log.warn("Failed to delete ONNX file from MinIO: {}", e.getMessage());
            }
        }

        // Supprimer d'abord l'historique de dérive et les commentaires pour éviter les
        // erreurs de clé étrangère (FK constraints)
        modelDeploymentHistoryRepository.deleteByModel(model);

        List<Comment> comments = commentRepository.findByModelId(model.getId());
        if (!comments.isEmpty()) {
            commentRepository.deleteAll(comments);
        }

        modelMLRepository.delete(model);
        log.info("Deleted model from database: {}", modelUuid);
    }

    @Transactional
    public void updateModelName(UUID modelUuid, String newName) {
        ModelML model = modelMLRepository.findByModelUuid(modelUuid)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelUuid));
        model.setModelName(newName);
        model.setUpdatedAt(LocalDateTime.now());
        modelMLRepository.save(model);
    }

    @Transactional
    public TrainingJobResponse trainRandomForest(TrainingJobRequest request) throws Exception {
        log.info("Starting Random Forest training for dataset: {}", request.getDatasetUuid());

        AppUser currentUser = getCurrentUser();
        log.info("Training initiated by user: {}", currentUser.getEmail());

        Dataset dataset = datasetRepository.findByDatasetUuid(request.getDatasetUuid())
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + request.getDatasetUuid()));

        if (dataset.getStatus() != DatasetStatus.PROCESSED) {
            throw new IllegalStateException(
                    "Dataset must be PROCESSED before training. Current status: " + dataset.getStatus());
        }

        Map<String, Object> hyperparameters = new HashMap<>();
        if (request.getHyperparameters() != null) {
            hyperparameters.putAll(request.getHyperparameters());
        }

        hyperparameters.put("random_state", request.getRandomSeed());

        Map<String, Object> trainingMetadata = new HashMap<>();
        trainingMetadata.put("datasetUuid", request.getDatasetUuid().toString());
        trainingMetadata.put("datasetName", dataset.getDatasetName());
        trainingMetadata.put("testSize", request.getTestSize());
        trainingMetadata.put("randomSeed", request.getRandomSeed());
        trainingMetadata.put("trainingDate", LocalDateTime.now().toString());
        trainingMetadata.put("createdBy", currentUser.getEmail());

        hyperparameters.put("_trainingMetadata", trainingMetadata);

        if (Boolean.TRUE.equals(request.getIsTest())) {
            return launchTraining(
                    "train-random-forest",
                    dataset.getFilePath(),
                    dataset.getDatasetUuid(),
                    UUID.randomUUID(),
                    hyperparameters,
                    request.getTestSize(),
                    request.getRandomSeed(),
                    true,
                    false);
        }

        ModelML model = getOrCreateModel(request, hyperparameters, currentUser);

        TrainingJobResponse response = launchTraining(
                "train-random-forest",
                dataset.getFilePath(),
                dataset.getDatasetUuid(),
                model.getModelUuid(),
                hyperparameters,
                request.getTestSize(),
                request.getRandomSeed(),
                false,
                false);

        updateModelWithResults(model, response);
        return response;
    }

    @Transactional
    public TrainingJobResponse trainHybridEnsemble(TrainingJobRequest request) throws Exception {
        log.info("Starting Hybrid Ensemble training for dataset: {}", request.getDatasetUuid());

        AppUser currentUser = getCurrentUser();
        Dataset dataset = datasetRepository.findByDatasetUuid(request.getDatasetUuid())
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + request.getDatasetUuid()));

        if (dataset.getStatus() != DatasetStatus.PROCESSED) {
            throw new IllegalStateException("Dataset must be PROCESSED before training.");
        }

        Map<String, Object> hyperparameters = new HashMap<>();
        if (request.getHyperparameters() != null) {
            hyperparameters.putAll(request.getHyperparameters());
        }
        hyperparameters.put("random_state", request.getRandomSeed());

        Map<String, Object> trainingMetadata = new HashMap<>();
        trainingMetadata.put("datasetUuid", request.getDatasetUuid().toString());
        trainingMetadata.put("datasetName", dataset.getDatasetName());
        trainingMetadata.put("testSize", request.getTestSize());
        trainingMetadata.put("randomSeed", request.getRandomSeed());
        trainingMetadata.put("trainingDate", LocalDateTime.now().toString());
        trainingMetadata.put("createdBy", currentUser.getEmail());
        hyperparameters.put("_trainingMetadata", trainingMetadata);

        if (Boolean.TRUE.equals(request.getIsTest())) {
            return launchTraining(
                    "train-hybrid-ensemble",
                    dataset.getFilePath(),
                    dataset.getDatasetUuid(),
                    UUID.randomUUID(),
                    hyperparameters,
                    request.getTestSize(),
                    request.getRandomSeed(),
                    true,
                    true);
        }

        ModelML model = getOrCreateModel(request, hyperparameters, currentUser, "HYBRID_ENSEMBLE");
        model.setInferenceEngine(InferenceEngine.HYBRID_SERVICE);

        TrainingJobResponse response = launchTraining(
                "train-hybrid-ensemble",
                dataset.getFilePath(),
                dataset.getDatasetUuid(),
                model.getModelUuid(),
                hyperparameters,
                request.getTestSize(),
                request.getRandomSeed(),
                false,
                true);

        updateModelWithResults(model, response);
        return response;
    }

    @Transactional
    public TrainingJobResponse trainAutoencoder(TrainingJobRequest request) throws Exception {
        return trainSpecializedModel(request, "train-autoencoder", "AUTOENCODER", "Autoencoder-");
    }

    @Transactional
    public TrainingJobResponse trainECOD(TrainingJobRequest request) throws Exception {
        return trainSpecializedModel(request, "train-ecod", "ECOD", "ECOD-");
    }

    @Transactional
    public TrainingJobResponse trainCOPOD(TrainingJobRequest request) throws Exception {
        return trainSpecializedModel(request, "train-copod", "COPOD", "COPOD-");
    }

    @Transactional
    protected TrainingJobResponse trainSpecializedModel(TrainingJobRequest request, String command, String modelType,
            String prefix) throws Exception {
        log.info("Starting {} training for dataset: {}", modelType, request.getDatasetUuid());

        AppUser currentUser = getCurrentUser();
        Dataset dataset = datasetRepository.findByDatasetUuid(request.getDatasetUuid())
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + request.getDatasetUuid()));

        if (dataset.getStatus() != DatasetStatus.PROCESSED) {
            throw new IllegalStateException("Dataset must be PROCESSED before training.");
        }

        Map<String, Object> hyperparameters = new HashMap<>();
        if (request.getHyperparameters() != null) {
            hyperparameters.putAll(request.getHyperparameters());
        }
        hyperparameters.put("random_state", request.getRandomSeed());

        Map<String, Object> trainingMetadata = new HashMap<>();
        trainingMetadata.put("datasetUuid", request.getDatasetUuid().toString());
        trainingMetadata.put("datasetName", dataset.getDatasetName());
        trainingMetadata.put("testSize", request.getTestSize());
        trainingMetadata.put("randomSeed", request.getRandomSeed());
        hyperparameters.put("_trainingMetadata", trainingMetadata);

        if (Boolean.TRUE.equals(request.getIsTest())) {
            return launchTraining(
                    command,
                    dataset.getFilePath(),
                    dataset.getDatasetUuid(),
                    UUID.randomUUID(),
                    hyperparameters,
                    request.getTestSize(),
                    request.getRandomSeed(),
                    true,
                    true);
        }

        ModelML model = getOrCreateModel(request, hyperparameters, currentUser, modelType);
        model.setInferenceEngine(InferenceEngine.HYBRID_SERVICE);

        TrainingJobResponse response = launchTraining(
                command,
                dataset.getFilePath(),
                dataset.getDatasetUuid(),
                model.getModelUuid(),
                hyperparameters,
                request.getTestSize(),
                request.getRandomSeed(),
                false,
                true);

        updateModelWithResults(model, response);
        return response;
    }

    @Transactional
    public TrainingJobResponse trainXGBoost(TrainingJobRequest request) throws Exception {
        log.info("Starting XGBoost training for dataset: {}", request.getDatasetUuid());

        AppUser currentUser = getCurrentUser();
        log.info("Training initiated by user: {}", currentUser.getEmail());

        Dataset dataset = datasetRepository.findByDatasetUuid(request.getDatasetUuid())
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + request.getDatasetUuid()));

        if (dataset.getStatus() != DatasetStatus.PROCESSED) {
            throw new IllegalStateException(
                    "Dataset must be PROCESSED before training. Current status: " + dataset.getStatus());
        }

        Map<String, Object> hyperparameters = new HashMap<>();
        if (request.getHyperparameters() != null) {
            hyperparameters.putAll(request.getHyperparameters());
        }
        hyperparameters.put("random_state", request.getRandomSeed());

        Map<String, Object> trainingMetadata = new HashMap<>();
        trainingMetadata.put("datasetUuid", request.getDatasetUuid().toString());
        trainingMetadata.put("datasetName", dataset.getDatasetName());
        trainingMetadata.put("testSize", request.getTestSize());
        trainingMetadata.put("randomSeed", request.getRandomSeed());
        trainingMetadata.put("trainingDate", LocalDateTime.now().toString());
        trainingMetadata.put("createdBy", currentUser.getEmail());
        hyperparameters.put("_trainingMetadata", trainingMetadata);

        if (Boolean.TRUE.equals(request.getIsTest())) {
            return launchTraining(
                    "train-xgboost",
                    dataset.getFilePath(),
                    dataset.getDatasetUuid(),
                    UUID.randomUUID(),
                    hyperparameters,
                    request.getTestSize(),
                    request.getRandomSeed(),
                    true,
                    false);
        }

        ModelML model = getOrCreateModel(request, hyperparameters, currentUser, "XGBOOST");

        TrainingJobResponse response = launchTraining(
                "train-xgboost",
                dataset.getFilePath(),
                dataset.getDatasetUuid(),
                model.getModelUuid(),
                hyperparameters,
                request.getTestSize(),
                request.getRandomSeed(),
                false,
                false);

        updateModelWithResults(model, response);
        return response;
    }

    @Transactional
    public TrainingJobResponse trainLightGBM(TrainingJobRequest request) throws Exception {
        log.info("Starting LightGBM training for dataset: {}", request.getDatasetUuid());

        AppUser currentUser = getCurrentUser();
        log.info("Training initiated by user: {}", currentUser.getEmail());

        Dataset dataset = datasetRepository.findByDatasetUuid(request.getDatasetUuid())
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + request.getDatasetUuid()));

        if (dataset.getStatus() != DatasetStatus.PROCESSED) {
            throw new IllegalStateException(
                    "Dataset must be PROCESSED before training. Current status: " + dataset.getStatus());
        }

        Map<String, Object> hyperparameters = new HashMap<>();
        if (request.getHyperparameters() != null) {
            hyperparameters.putAll(request.getHyperparameters());
        }
        hyperparameters.put("random_state", request.getRandomSeed());

        Map<String, Object> trainingMetadata = new HashMap<>();
        trainingMetadata.put("datasetUuid", request.getDatasetUuid().toString());
        trainingMetadata.put("datasetName", dataset.getDatasetName());
        trainingMetadata.put("testSize", request.getTestSize());
        trainingMetadata.put("randomSeed", request.getRandomSeed());
        trainingMetadata.put("trainingDate", LocalDateTime.now().toString());
        trainingMetadata.put("createdBy", currentUser.getEmail());
        hyperparameters.put("_trainingMetadata", trainingMetadata);

        if (Boolean.TRUE.equals(request.getIsTest())) {
            return launchTraining(
                    "train-lightgbm",
                    dataset.getFilePath(),
                    dataset.getDatasetUuid(),
                    UUID.randomUUID(),
                    hyperparameters,
                    request.getTestSize(),
                    request.getRandomSeed(),
                    true,
                    false);
        }

        ModelML model = getOrCreateModel(request, hyperparameters, currentUser, "LIGHTGBM");

        TrainingJobResponse response = launchTraining(
                "train-lightgbm",
                dataset.getFilePath(),
                dataset.getDatasetUuid(),
                model.getModelUuid(),
                hyperparameters,
                request.getTestSize(),
                request.getRandomSeed(),
                false,
                false);

        updateModelWithResults(model, response);
        return response;
    }

    @Transactional
    public TrainingJobResponse trainBalancedRandomForest(TrainingJobRequest request) throws Exception {
        log.info("Starting Balanced Random Forest training for dataset: {}", request.getDatasetUuid());

        AppUser currentUser = getCurrentUser();
        log.info("Training initiated by user: {}", currentUser.getEmail());

        Dataset dataset = datasetRepository.findByDatasetUuid(request.getDatasetUuid())
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + request.getDatasetUuid()));

        if (dataset.getStatus() != DatasetStatus.PROCESSED) {
            throw new IllegalStateException(
                    "Dataset must be PROCESSED before training. Current status: " + dataset.getStatus());
        }

        Map<String, Object> hyperparameters = new HashMap<>();
        if (request.getHyperparameters() != null) {
            hyperparameters.putAll(request.getHyperparameters());
        }

        hyperparameters.put("random_state", request.getRandomSeed());

        Map<String, Object> trainingMetadata = new HashMap<>();
        trainingMetadata.put("datasetUuid", request.getDatasetUuid().toString());
        trainingMetadata.put("datasetName", dataset.getDatasetName());
        trainingMetadata.put("testSize", request.getTestSize());
        trainingMetadata.put("randomSeed", request.getRandomSeed());
        trainingMetadata.put("trainingDate", LocalDateTime.now().toString());
        trainingMetadata.put("createdBy", currentUser.getEmail());

        hyperparameters.put("_trainingMetadata", trainingMetadata);

        if (Boolean.TRUE.equals(request.getIsTest())) {
            return launchTraining(
                    "train-balanced-random-forest",
                    dataset.getFilePath(),
                    dataset.getDatasetUuid(),
                    UUID.randomUUID(),
                    hyperparameters,
                    request.getTestSize(),
                    request.getRandomSeed(),
                    true,
                    false);
        }

        ModelML model = getOrCreateModel(request, hyperparameters, currentUser, "BALANCED_RANDOM_FOREST");

        TrainingJobResponse response = launchTraining(
                "train-balanced-random-forest",
                dataset.getFilePath(),
                dataset.getDatasetUuid(),
                model.getModelUuid(),
                hyperparameters,
                request.getTestSize(),
                request.getRandomSeed(),
                false,
                false);

        updateModelWithResults(model, response);
        return response;
    }

    private ModelML getOrCreateModel(TrainingJobRequest request, Map<String, Object> hyperparameters, AppUser creator) {
        return getOrCreateModel(request, hyperparameters, creator, "RANDOM_FOREST");
    }

    private ModelML getOrCreateModel(TrainingJobRequest request, Map<String, Object> hyperparameters, AppUser creator,
            String modelType) {
        if (request.getModelUuid() != null) {
            ModelML existingModel = modelMLRepository.findByModelUuid(request.getModelUuid())
                    .orElseThrow(() -> new IllegalArgumentException("Model not found: " + request.getModelUuid()));

            // Critical: Update model type and hyperparameters if they've changed since the
            // draft was created
            existingModel.setModelType(modelType);
            existingModel.setModelName(request.getJobName());
            existingModel.setHyperparameters(hyperparameters);

            return modelMLRepository.save(existingModel);
        }

        ModelML model = ModelML.builder()
                .modelUuid(UUID.randomUUID())
                .modelName(request.getJobName())
                .modelType(modelType)
                .version(generateVersion())
                .hyperparameters(hyperparameters)
                .status("Draft")
                .createdBy(creator)
                .createdAt(LocalDateTime.now())
                .build();

        return modelMLRepository.save(model);
    }

    private String generateVersion() {
        return "1.0." + System.currentTimeMillis();
    }

    private TrainingJobResponse launchTraining(
            String cliCommand,
            String datasetPath,
            UUID datasetId,
            UUID modelId,
            Map<String, Object> hyperparameters,
            double testSize,
            int randomSeed,
            boolean isTest,
            boolean isHybrid) throws Exception {
        log.info("Launching {} training via Docker", isHybrid ? "Hybrid" : "ONNX");
        String currentContainerName = isHybrid ? hybridContainerName : onnxContainerName;

        Path tempDataset = null;
        Path tempHyperparams = null;
        Path tempModel = null;
        Path tempPipeline = null;
        Path tempOnnx = null;
        String containerDatasetPath = null;
        String containerHyperparamsPath = null;
        String containerModelPath = null;
        String containerPipelinePath = null;
        String containerOnnxPath = null;

        try {
            tempDataset = Files.createTempFile("train_dataset_", ".parquet");
            try (var in = storageService.download(datasetPath);
                    var out = Files.newOutputStream(tempDataset)) {
                in.transferTo(out);
            }

            tempHyperparams = Files.createTempFile("hyperparams_", ".json");
            objectMapper.writeValue(tempHyperparams.toFile(), hyperparameters);

            containerDatasetPath = "/tmp/fraud-work/" + tempDataset.getFileName();
            containerHyperparamsPath = "/tmp/fraud-work/" + tempHyperparams.getFileName();

            // S'assurer que toute l'arborescence nécessaire existe dans le conteneur (utiliser /tmp pour éviter les fuites de volume)
            execDockerCommand(currentContainerName, "mkdir", "-p",
                    "/tmp/fraud-work",
                    "/tmp/fraud-work/artifacts/exported_models",
                    "/tmp/fraud-work/artifacts/exported_onnx",
                    "/tmp/fraud-work/artifacts/exported_parquet",
                    "/tmp/fraud-work/artifacts/reports",
                    "/tmp/fraud-work/artifacts/logs",
                    "/tmp/fraud-work/output");

            execDockerCopy(currentContainerName, tempDataset, containerDatasetPath);
            execDockerCopy(currentContainerName, tempHyperparams, containerHyperparamsPath);

            UUID jobUuid = UUID.randomUUID();
            String timestamp = String.valueOf(System.currentTimeMillis());

            List<String> commandList = new ArrayList<>(List.of("docker", "exec", currentContainerName, "python"));

            if (isHybrid) {
                // Command format: python scripts/train_hybrid_ensemble.py
                String scriptPath = "scripts/" + cliCommand.replace("-", "_") + ".py";
                commandList.add(scriptPath);
            } else {
                // Command format: python -m ml_onnx_service.cli.main train-random-forest
                commandList.add("-m");
                commandList.add("ml_onnx_service.cli.main");
                commandList.add(cliCommand);
            }

            commandList.addAll(List.of(
                    "--dataset-path", containerDatasetPath,
                    "--dataset-id", datasetId.toString(),
                    "--job-id", jobUuid.toString(),
                    "--timestamp", timestamp,
                    "--hyperparameters", containerHyperparamsPath,
                    "--test-size", String.valueOf(testSize),
                    "--random-seed", String.valueOf(randomSeed)));

            log.info("Executing command: {}", String.join(" ", commandList));

            ProcessBuilder pb = new ProcessBuilder(commandList);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();

            StringBuilder stdoutOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdoutOutput.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(30, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Training timed out after 30 minutes");
            }

            int exitCode = process.exitValue();
            String result = stdoutOutput.toString().trim();

            if (exitCode != 0) {
                log.error("Training script exited with code {}", exitCode);
                if (!result.isEmpty()) {
                    log.error("Training output: {}", result);
                    throw new RuntimeException("Training failed (code " + exitCode + "): " + result);
                }
                throw new RuntimeException("Training failed with exit code " + exitCode);
            }

            if (result.isEmpty()) {
                throw new RuntimeException("Training script produced no stdout output");
            }

            JsonNode root = extractJsonFromOutput(result);
            if (root == null) {
                log.error("Could not extract JSON from stdout: {}", result);
                throw new RuntimeException("No valid JSON output from training script");
            }

            TrainingJobResponse response = buildTrainingResponse(root, datasetId, modelId, jobUuid);

            JsonNode modelPathNode = root.get("model_path");
            if (modelPathNode == null || modelPathNode.isNull() || modelPathNode.asText().isBlank()) {
                throw new RuntimeException("Training JSON does not contain model_path");
            }

            containerModelPath = modelPathNode.asText();
            String baseFilename = "trained_model_" + timestamp;

            // Create explicit file paths (no random suffix)
            Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
            tempModel = tempDir.resolve(baseFilename + ".joblib");
            Files.deleteIfExists(tempModel); // Clean up if exists
            execDockerCopyFromContainer(currentContainerName, containerModelPath, tempModel);

            String tempFolder = isTest ? "models/temp/" + jobUuid + "/" : "models/trained/" + datasetId + "/";

            // Model upload
            String modelKey = tempFolder + tempModel.getFileName();
            try (var in = Files.newInputStream(tempModel)) {
                storageService.upload(modelKey, in, Files.size(tempModel), "application/octet-stream");
            }
            response.setModelPath(modelKey);
            log.info("{} uploaded to MinIO: {}", isTest ? "Test model" : "Model", modelKey);

            // Pipeline extraction and upload
            JsonNode pipelinePathNode = root.get("pipeline_path");
            if (pipelinePathNode != null && !pipelinePathNode.isNull() && !pipelinePathNode.asText().isBlank()) {
                containerPipelinePath = pipelinePathNode.asText();
                tempPipeline = Files.createTempFile("pipeline_", ".joblib");
                try {
                    execDockerCopyFromContainer(currentContainerName, containerPipelinePath, tempPipeline);
                    String pipelineKey = (isTest ? "models/temp/" + jobUuid + "/"
                            : "models/pipelines/" + datasetId + "/")
                            + tempPipeline.getFileName();
                    try (var in = Files.newInputStream(tempPipeline)) {
                        storageService.upload(pipelineKey, in, Files.size(tempPipeline), "application/octet-stream");
                    }
                    response.setPipelinePath(pipelineKey);
                    log.info("{} uploaded to MinIO: {}", isTest ? "Test pipeline" : "Pipeline", pipelineKey);
                } catch (Exception e) {
                    log.warn("Could not upload pipeline: {}", e.getMessage());
                }
            }

            // ONNX extraction and upload
            JsonNode onnxPathNode = root.get("onnx_path");
            if (onnxPathNode != null && !onnxPathNode.isNull() && !onnxPathNode.asText().isBlank()) {
                containerOnnxPath = onnxPathNode.asText();
                tempOnnx = tempDir.resolve(baseFilename + ".onnx");
                Files.deleteIfExists(tempOnnx);
                try {
                    execDockerCopyFromContainer(currentContainerName, containerOnnxPath, tempOnnx);
                    String onnxKey = (isTest ? "models/temp/" + jobUuid + "/" : "models/onnx/" + datasetId + "/")
                            + tempOnnx.getFileName();
                    try (var in = Files.newInputStream(tempOnnx)) {
                        storageService.upload(onnxKey, in, Files.size(tempOnnx), "application/octet-stream");
                    }
                    response.setOnnxPath(onnxKey);
                    log.info("{} uploaded to MinIO: {}", isTest ? "Test ONNX" : "ONNX model", onnxKey);
                } catch (Exception e) {
                    log.warn("Could not upload ONNX model (non-fatal): {}", e.getMessage());
                }
            }

            return response;

        } finally {
            // Clean up all temp files on host
            if (tempDataset != null)
                Files.deleteIfExists(tempDataset);
            if (tempHyperparams != null)
                Files.deleteIfExists(tempHyperparams);
            if (tempModel != null)
                Files.deleteIfExists(tempModel);
            if (tempPipeline != null)
                Files.deleteIfExists(tempPipeline);
            if (tempOnnx != null)
                Files.deleteIfExists(tempOnnx);

            // Clean up all files in container
            if (containerDatasetPath != null) {
                try {
                    execDockerCommand(currentContainerName, "rm", "-f", containerDatasetPath);
                } catch (Exception e) {
                }
            }
            if (containerHyperparamsPath != null) {
                try {
                    execDockerCommand(currentContainerName, "rm", "-f", containerHyperparamsPath);
                } catch (Exception e) {
                }
            }
            if (containerModelPath != null) {
                try {
                    execDockerCommand(currentContainerName, "rm", "-f", containerModelPath);
                    execDockerCommand(currentContainerName, "rm", "-f", containerModelPath + ".meta.json");
                } catch (Exception e) {
                }
            }
            if (containerPipelinePath != null) {
                try {
                    execDockerCommand(currentContainerName, "rm", "-f", containerPipelinePath);
                    execDockerCommand(currentContainerName, "rm", "-f", containerPipelinePath + ".meta.json");
                } catch (Exception e) {
                }
            }
            if (containerOnnxPath != null) {
                try {
                    execDockerCommand(currentContainerName, "rm", "-f", containerOnnxPath);
                } catch (Exception e) {
                }
            }
        }
    }

    private void execDockerCopyFromContainer(String containerName, String containerPath, Path localPath)
            throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "cp",
                containerName + ":" + containerPath,
                localPath.toAbsolutePath().toString());
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Docker copy from container failed");
        }
    }

    private String inferModelTypeFromJobName(String jobName) {
        if (jobName == null) return "UNKNOWN";
        String name = jobName.toUpperCase();
        if (name.contains("XGB")) return "XGBOOST";
        if (name.contains("LGBM") || name.contains("LIGHTGBM")) return "LIGHTGBM";
        if (name.contains("BRF") || name.contains("BALANCED")) return "BALANCED_RANDOM_FOREST";
        if (name.contains("RF") || name.contains("RANDOM")) return "RANDOM_FOREST";
        if (name.contains("ECOD")) return "ECOD";
        if (name.contains("COPOD")) return "COPOD";
        if (name.contains("AE ") || name.contains("AUTOENCODER")) return "AUTOENCODER";
        if (name.contains("ENS ") || name.contains("ENS V") || name.contains("HYBRID") || name.contains("HYB ")) return "HYBRID_ENSEMBLE";
        return "UNKNOWN";
    }

    private TrainingJobResponse buildTrainingResponse(JsonNode root, UUID datasetId, UUID modelId, UUID jobUuid) {
        JsonNode metrics = root.get("metrics");
        JsonNode confusionMatrix = root.get("confusion_matrix");
        JsonNode trainingStats = root.get("training_stats");
        JsonNode hyperparametersUsed = root.get("hyperparameters_used");
        JsonNode selectedFeatures = root.get("selected_original_features");
        JsonNode featureImportance = root.get("feature_importance");
        JsonNode thresholds = root.get("thresholds");

        Map<String, Object> metricsMap = new HashMap<>();
        if (metrics != null) {
            metricsMap = objectMapper.convertValue(metrics, new TypeReference<Map<String, Object>>() {
            });
        }

        Map<String, Object> confusionMap = new HashMap<>();
        if (confusionMatrix != null) {
            confusionMap = objectMapper.convertValue(confusionMatrix, new TypeReference<Map<String, Object>>() {
            });
        }

        Map<String, Object> hyperparamsMap = new HashMap<>();
        if (hyperparametersUsed != null) {
            hyperparamsMap = objectMapper.convertValue(hyperparametersUsed, new TypeReference<Map<String, Object>>() {
            });
        }

        List<String> selectedFeaturesList = new ArrayList<>();
        if (selectedFeatures != null && selectedFeatures.isArray()) {
            for (JsonNode feature : selectedFeatures) {
                selectedFeaturesList.add(feature.asText());
            }
        }

        Map<String, Object> featureImportanceMap = new HashMap<>();
        if (featureImportance != null) {
            featureImportanceMap = objectMapper.convertValue(featureImportance,
                    new TypeReference<Map<String, Object>>() {
                    });
        }

        Map<String, Object> thresholdsMap = new HashMap<>();
        if (thresholds != null) {
            thresholdsMap = objectMapper.convertValue(thresholds, new TypeReference<Map<String, Object>>() {
            });
        }

        String modelName = root.has("model_name") ? root.get("model_name").asText() : "Machine Learning Training";
        String modelType = inferModelTypeFromJobName(modelName);

        TrainingJobResponse response = TrainingJobResponse.builder()
                .jobUuid(jobUuid)
                .jobName(modelName)
                .modelType(modelType)
                .status(root.has("status") ? root.get("status").asText() : "UNKNOWN")
                .datasetUuid(datasetId)
                .modelUuid(modelId)
                .hyperparameters(hyperparamsMap)
                .metrics(metricsMap)
                .confusionMatrix(confusionMap)
                .thresholds(thresholdsMap)
                .featureImportance(featureImportanceMap)
                .selectedFeatures(selectedFeaturesList)
                .modelPath(
                        root.has("model_path") && !root.get("model_path").isNull() ? root.get("model_path").asText()
                                : null)
                .pipelinePath(root.has("pipeline_path") && !root.get("pipeline_path").isNull()
                        ? root.get("pipeline_path").asText()
                        : null)
                .onnxPath(
                        root.has("onnx_path") && !root.get("onnx_path").isNull() ? root.get("onnx_path").asText()
                                : null)
                .startedAt(root.has("start_time") ? LocalDateTime.parse(root.get("start_time").asText()) : null)
                .completedAt(root.has("end_time") ? LocalDateTime.parse(root.get("end_time").asText()) : null)
                .durationSeconds(
                        root.has("duration_seconds") ? (int) Math.round(root.get("duration_seconds").asDouble()) : null)
                .build();

        if (trainingStats != null) {
            if (trainingStats.has("train_rows"))
                response.setTrainingRows(trainingStats.get("train_rows").asInt());
            if (trainingStats.has("test_rows"))
                response.setTestRows(trainingStats.get("test_rows").asInt());
            if (trainingStats.has("fraud_rate_test"))
                response.setFraudRate(trainingStats.get("fraud_rate_test").asDouble());
        }

        response.extractMetrics();
        return response;
    }

    private void updateModelWithResults(ModelML model, TrainingJobResponse response) {
        // Update model type and name in case they were changed during training (e.g.
        // Draft RF -> Train XGB)
        if (response.getJobName() != null && !response.getJobName().isBlank()) {
            model.setModelName(response.getJobName());
        }

        // 1. Classification and Engine Assignment based on Model Name
        if (model.getModelName().startsWith("XGB")) {
            model.setModelType("XGBOOST");
        } else if (model.getModelName().startsWith("RF") || model.getModelName().contains("Random Forest")) {
            if (model.getModelName().contains("Balanced") || "BALANCED_RANDOM_FOREST".equals(model.getModelType())) {
                model.setModelType("BALANCED_RANDOM_FOREST");
            } else {
                model.setModelType("RANDOM_FOREST");
            }
        } else if (model.getModelName().startsWith("LGBM") || model.getModelName().contains("LightGBM")) {
            model.setModelType("LIGHTGBM");
        } else if (model.getModelName().startsWith("BRF")) {
            model.setModelType("BALANCED_RANDOM_FOREST");
        } else if (model.getModelName().startsWith("Hybrid") || model.getModelName().startsWith("ENS")) {
            model.setModelType("HYBRID_ENSEMBLE");
            model.setInferenceEngine(InferenceEngine.HYBRID_SERVICE);
        } else if (model.getModelName().startsWith("AUTOENCODER") || model.getModelName().startsWith("AE")) {
            model.setModelType("AUTOENCODER");
            model.setInferenceEngine(InferenceEngine.HYBRID_SERVICE);
        } else if (model.getModelName().startsWith("ECOD")) {
            model.setModelType("ECOD");
            model.setInferenceEngine(InferenceEngine.HYBRID_SERVICE);
        } else if (model.getModelName().startsWith("COPOD")) {
            model.setModelType("COPOD");
            model.setInferenceEngine(InferenceEngine.HYBRID_SERVICE);
        }

        if (model.getInferenceEngine() == null) {
            model.setInferenceEngine(InferenceEngine.ONNX_LOCAL);
        }

        if (response.getMetrics() != null) {
            model.setAuc(response.getAucRoc());
            model.setPrecision(response.getPrecision());
            model.setRecall(response.getRecall());
            model.setF1Score(response.getF1Score());

            if (response.getFeatureImportance() != null) {
                model.setFeatureImportance(response.getFeatureImportance());
            }
        }

        Map<String, Object> finalHyperparams = new HashMap<>();

        // Start with initial hyperparameters (preserves features, metadata, etc.)
        if (model.getHyperparameters() != null) {
            finalHyperparams.putAll(model.getHyperparameters());
        }

        // Python as source of truth for REAL hyperparameters used (overwrites initial
        // ones)
        if (response.getHyperparameters() != null) {
            finalHyperparams.putAll(response.getHyperparameters());
        }

        // Add training results summary
        Map<String, Object> trainingResults = new HashMap<>();
        trainingResults.put("jobUuid", response.getJobUuid().toString());
        trainingResults.put("completedAt", response.getCompletedAt() != null ? response.getCompletedAt().toString()
                : LocalDateTime.now().toString());
        trainingResults.put("durationSeconds", response.getDurationSeconds());
        trainingResults.put("metrics", response.getMetrics());
        trainingResults.put("confusionMatrix", response.getConfusionMatrix());
        trainingResults.put("thresholds", response.getThresholds());
        trainingResults.put("trainingRows", response.getTrainingRows());
        trainingResults.put("testRows", response.getTestRows());
        trainingResults.put("fraudRate", response.getFraudRate());
        trainingResults.put("modelPath", response.getModelPath());
        trainingResults.put("pipelinePath", response.getPipelinePath());
        trainingResults.put("onnxPath", response.getOnnxPath());

        finalHyperparams.put("_trainingResults", trainingResults);
        model.setHyperparameters(finalHyperparams);

        model.setUpdatedAt(LocalDateTime.now());
        modelMLRepository.save(model);
    }

    @SuppressWarnings("unchecked")
    public ModelMetricsResponse getModelMetrics(UUID modelUuid) {
        ModelML model = modelMLRepository.findByModelUuid(modelUuid)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelUuid));

        Map<String, Long> confusionMatrix = new HashMap<>();
        String onnxPath = null;
        Map<String, Object> thresholds = new HashMap<>();

        if (model.getHyperparameters() != null && model.getHyperparameters().containsKey("_trainingResults")) {
            Map<String, Object> trainingResults = (Map<String, Object>) model.getHyperparameters()
                    .get("_trainingResults");
            if (trainingResults.containsKey("confusionMatrix")) {
                confusionMatrix = objectMapper.convertValue(trainingResults.get("confusionMatrix"),
                        new TypeReference<Map<String, Long>>() {
                        });
            }
            if (trainingResults.containsKey("onnxPath")) {
                onnxPath = (String) trainingResults.get("onnxPath");
            }
            if (trainingResults.containsKey("thresholds")) {
                thresholds = (Map<String, Object>) trainingResults.get("thresholds");
            }
        }

        Map<String, Double> importance = new HashMap<>();
        if (model.getFeatureImportance() != null) {
            importance = objectMapper.convertValue(model.getFeatureImportance(),
                    new TypeReference<Map<String, Double>>() {
                    });
        }

        List<String> topFeatures = importance.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(10)
                .map(Map.Entry::getKey)
                .toList();

        ModelMetricsResponse response = ModelMetricsResponse.builder()
                .modelUuid(modelUuid)
                .modelName(model.getModelName())
                .modelType(model.getModelType())
                .version(model.getVersion())
                .status(model.getStatus())
                .accuracy(null)
                .precision(model.getPrecision())
                .recall(model.getRecall())
                .f1Score(model.getF1Score())
                .aucRoc(model.getAuc())
                .confusionMatrix(confusionMatrix)
                .trueNegatives(confusionMatrix.getOrDefault("true_negatives", 0L))
                .falsePositives(confusionMatrix.getOrDefault("false_positives", 0L))
                .falseNegatives(confusionMatrix.getOrDefault("false_negatives", 0L))
                .truePositives(confusionMatrix.getOrDefault("true_positives", 0L))
                .featureImportance(importance)
                .topFeatures(topFeatures)
                .selectedFeatures(model.getHyperparameters() != null
                        ? (List<String>) model.getHyperparameters().get("selected_original_features")
                        : null)
                .onnxPath(onnxPath)
                .thresholds(thresholds)
                .hyperparameters(model.getHyperparameters())
                .validationComment(commentRepository
                        .findFirstByModelModelUuidAndObjectOrderByCreatedAtDesc(modelUuid, "Model Validation")
                        .map(Comment::getContent)
                        .orElse(null))
                .build();

        response.calculateDerivedMetrics();
        return response;
    }

    private void execDockerCopy(String containerName, Path source, String destination) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("docker", "cp", source.toAbsolutePath().toString(),
                containerName + ":" + destination);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Docker copy failed");
        }
    }

    private void execDockerCommand(String containerName, String... commands) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker", "exec", containerName));
        cmd.addAll(Arrays.asList(commands));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Docker command failed");
        }
    }

    private JsonNode extractJsonFromOutput(String output) {
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("{")) {
                try {
                    return objectMapper.readTree(trimmed);
                } catch (Exception e) {
                    // Continue searching
                }
            }
        }
        int jsonStart = output.indexOf('{');
        int jsonEnd = output.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            try {
                return objectMapper.readTree(output.substring(jsonStart, jsonEnd + 1));
            } catch (Exception e) {
                log.error("Failed to parse JSON from output blob: {}", e.getMessage());
            }
        }
        return null;
    }

    @Transactional
    public void validateModel(ModelValidationActionDTO action, String managerKeycloakId) {
        ModelML model = modelMLRepository.findByModelUuid(action.getModelUuid())
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + action.getModelUuid()));

        AppUser manager = keycloakMappingRepository.findByKeycloakId(managerKeycloakId)
                .map(KeycloakMapping::getUser)
                .orElseThrow(() -> new IllegalArgumentException("Manager not found: " + managerKeycloakId));

        model.setStatus(action.getStatus());
        model.setValidatedBy(manager);
        model.setUpdatedAt(LocalDateTime.now());
        modelMLRepository.save(model);

        if (action.getComment() != null && !action.getComment().trim().isEmpty()) {
            Comment comment = Comment.builder()
                    .object("Model Validation")
                    .content(action.getComment())
                    .status(action.getStatus())
                    .author(manager)
                    .model(model)
                    .build();
            commentRepository.save(comment);
        }

        log.info("Model {} validated with status {} by manager {}", model.getModelUuid(), action.getStatus(),
                manager.getEmail());
    }

    public Page<TrainingJobResponse> getAllModels(Pageable pageable) {
        log.info("Fetching ALL models with pagination: {}", pageable);
        return modelMLRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(this::toTrainingJobResponse);
    }

    public Page<TrainingJobResponse> getMyModels(Pageable pageable) {
        AppUser currentUser = getCurrentUser();
        if (currentUser.getRole() != null && "MANAGER".equals(currentUser.getRole().getName())) {
            return modelMLRepository.findByValidatedByIdOrderByCreatedAtDesc(currentUser.getId(), pageable)
                    .map(this::toTrainingJobResponse);
        } else {
            return modelMLRepository.findByCreatedByIdOrderByCreatedAtDesc(currentUser.getId(), pageable)
                    .map(this::toTrainingJobResponse);
        }
    }

    @Transactional
    public TrainingJobResponse finalizeTestTraining(TrainingJobResponse testResult) {
        log.info("Finalizing test training for job: {}", testResult.getJobUuid());

        Dataset dataset = datasetRepository.findByDatasetUuid(testResult.getDatasetUuid())
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + testResult.getDatasetUuid()));

        AppUser currentUser = getCurrentUser();

        // 1. Define permanent paths
        String datasetId = dataset.getDatasetUuid().toString();
        // Extract filename from temp path
        String modelKey = testResult.getModelPath();
        String modelFilename = modelKey.substring(modelKey.lastIndexOf("/") + 1);
        String permanentModelPath = "models/trained/" + datasetId + "/" + modelFilename;

        // 2. Move files in MinIO (Copy then Delete)
        storageService.copy(modelKey, permanentModelPath);
        storageService.delete(modelKey);

        String permanentPipelinePath = null;
        if (testResult.getPipelinePath() != null) {
            String pipelineKey = testResult.getPipelinePath();
            String pipelineFilename = pipelineKey.substring(pipelineKey.lastIndexOf("/") + 1);
            permanentPipelinePath = "models/pipelines/" + datasetId + "/" + pipelineFilename;
            storageService.copy(pipelineKey, permanentPipelinePath);
            storageService.delete(pipelineKey);
        }

        String permanentOnnxPath = null;
        if (testResult.getOnnxPath() != null) {
            String onnxKey = testResult.getOnnxPath();
            String onnxFilename = onnxKey.substring(onnxKey.lastIndexOf("/") + 1);
            permanentOnnxPath = "models/onnx/" + datasetId + "/" + onnxFilename;
            storageService.copy(onnxKey, permanentOnnxPath);
            storageService.delete(onnxKey);
        }

        // 3. Prepare hyperparameters with training results (consistent with
        // updateModelWithResults)
        Map<String, Object> finalHyperparams = new HashMap<>();
        if (testResult.getHyperparameters() != null) {
            finalHyperparams.putAll(testResult.getHyperparameters());
        }

        Map<String, Object> trainingResults = new HashMap<>();
        trainingResults.put("jobUuid", testResult.getJobUuid().toString());
        trainingResults.put("completedAt", LocalDateTime.now().toString());
        trainingResults.put("durationSeconds", testResult.getDurationSeconds());
        trainingResults.put("metrics", testResult.getMetrics());
        trainingResults.put("confusionMatrix", testResult.getConfusionMatrix());
        trainingResults.put("thresholds", testResult.getThresholds());
        trainingResults.put("trainingRows", testResult.getTrainingRows());
        trainingResults.put("testRows", testResult.getTestRows());
        trainingResults.put("fraudRate", testResult.getFraudRate());
        trainingResults.put("modelPath", permanentModelPath);
        trainingResults.put("pipelinePath", permanentPipelinePath);
        trainingResults.put("onnxPath", permanentOnnxPath);
        trainingResults.put("datasetUuid", testResult.getDatasetUuid().toString());

        finalHyperparams.put("_trainingResults", trainingResults);

        // 4. Determine model type and inference engine
        String modelType = testResult.getModelType();
        if (modelType == null || "UNKNOWN".equals(modelType)) {
            modelType = inferModelTypeFromJobName(testResult.getJobName());
        }

        InferenceEngine engine = InferenceEngine.ONNX_LOCAL;
        if (List.of("ECOD", "COPOD", "AUTOENCODER", "HYBRID_ENSEMBLE").contains(modelType)) {
            engine = InferenceEngine.HYBRID_SERVICE;
        }

        // 5. Create ModelML entity
        ModelML model = ModelML.builder()
                .modelUuid(UUID.randomUUID())
                .modelName(testResult.getJobName())
                .modelType(modelType)
                .version("1.0") // Default for new models
                .status("Draft")
                .hyperparameters(finalHyperparams)
                .featureImportance(testResult.getFeatureImportance())
                .auc(testResult.getAucRoc())
                .precision(testResult.getPrecision())
                .recall(testResult.getRecall())
                .f1Score(testResult.getF1Score())
                .inferenceEngine(engine)
                .createdBy(currentUser)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        modelMLRepository.save(model);

        log.info("Test training finalized and saved as model: {}", model.getModelUuid());

        // Update response with permanent paths and new model UUID
        testResult.setModelUuid(model.getModelUuid());
        testResult.setModelPath(permanentModelPath);
        testResult.setPipelinePath(permanentPipelinePath);
        testResult.setOnnxPath(permanentOnnxPath);
        testResult.setStatus("COMPLETED");

        return testResult;
    }

}
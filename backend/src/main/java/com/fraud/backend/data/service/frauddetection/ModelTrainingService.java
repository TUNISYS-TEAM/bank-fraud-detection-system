package com.fraud.backend.data.service.frauddetection;

import com.fraud.backend.api.dto.accessmanagement.user.response.UserSummaryResponse;
import com.fraud.backend.api.dto.frauddetection.model.request.TrainingJobRequest;
import com.fraud.backend.api.dto.frauddetection.model.response.ModelMetricsResponse;
import com.fraud.backend.api.dto.frauddetection.model.response.TrainingJobResponse;
import com.fraud.backend.api.dto.messaging.request.ModelValidationActionDTO;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.accessmanagement.KeycloakMapping;
import com.fraud.backend.data.entity.frauddetection.Dataset;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.enums.datasets.DatasetStatus;
import com.fraud.backend.data.repository.frauddetection.CommentRepository;
import com.fraud.backend.data.repository.accessmanagement.KeycloakMappingRepository;
import com.fraud.backend.data.entity.frauddetection.Comment;
import com.fraud.backend.data.repository.frauddetection.DatasetRepository;
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
    private final KeycloakMappingRepository keycloakMappingRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    /**
     * Get the currently authenticated user
     */
    /**
     * Get the currently authenticated user from JWT token
     */
    private AppUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            log.error("No authentication found");
            throw new IllegalStateException("Utilisateur non authentifié");
        }

        Object principal = authentication.getPrincipal();
        log.debug("Principal class: {}", principal != null ? principal.getClass().getSimpleName() : "null");

        // Extract email from principal
        String email = null;

        // Handle JWT token principal (OAuth2)
        if (principal instanceof Jwt) {
            Jwt jwt = (Jwt) principal;
            log.debug("JWT claims: {}", jwt.getClaims());

            // Try different claim names where email might be stored
            email = jwt.getClaim("preferred_username");
            if (email == null) {
                email = jwt.getClaim("email");
            }
            if (email == null) {
                email = jwt.getClaim("sub");
            }
        }
        // Handle UserDetails principal (username/password)
        else if (principal instanceof org.springframework.security.core.userdetails.UserDetails) {
            email = ((org.springframework.security.core.userdetails.UserDetails) principal).getUsername();
        }
        // Handle String principal (sometimes the email is directly the principal)
        else if (principal instanceof String) {
            email = (String) principal;
        }

        // Fallback to authentication name
        if (email == null || email.isEmpty()) {
            email = authentication.getName();
        }

        // Final email variable for lambda usage
        final String userEmail = email;

        if (userEmail == null || userEmail.isEmpty()) {
            log.error("Could not extract email from authentication");
            throw new IllegalStateException("Impossible d'extraire l'email de l'authentification");
        }

        log.info("Looking for user with email: {}", userEmail);

        // Find user by email
        return keycloakMappingRepository.findByEmail(userEmail)
                .map(KeycloakMapping::getUser)
                .orElseThrow(() -> new IllegalStateException("Utilisateur non trouvé avec l'email: " + userEmail));
    }

    /**
     * Convert AppUser to UserSummaryResponse DTO
     */
    private UserSummaryResponse toUserSummaryResponse(AppUser user) {
        if (user == null)
            return null;

        return UserSummaryResponse.builder()
                .id(user.getId())
                .uuid(user.getUuid())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .roleId(user.getRole() != null ? user.getRole().getId() : null)
                .roleName(user.getRole() != null ? user.getRole().getName() : null)
                .managerId(user.getManager() != null ? user.getManager().getId() : null)
                .managerName(user.getManager() != null
                        ? user.getManager().getFirstName() + " " + user.getManager().getLastName()
                        : null)
                .isActive(user.getIsActive())
                .lastLogin(user.getLastLogin())
                .build();
    }

    /**
     * Convert ModelML entity to TrainingJobResponse DTO
     */

    private TrainingJobResponse toTrainingJobResponse(ModelML model) {
        if (model == null)
            return null;

        // Extract training results from hyperparameters
        Map<String, Object> metrics = new HashMap<>();
        Map<String, Object> confusionMatrix = new HashMap<>();
        Integer trainingRows = null;
        Integer testRows = null;
        Double fraudRate = null;
        String modelPath = null;
        String pipelinePath = null;
        String onnxPath = null;

        if (model.getHyperparameters() != null && model.getHyperparameters().containsKey("_trainingResults")) {
            Map<String, Object> trainingResults = (Map<String, Object>) model.getHyperparameters()
                    .get("_trainingResults");

            if (trainingResults.containsKey("metrics")) {
                metrics = (Map<String, Object>) trainingResults.get("metrics");
            }
            if (trainingResults.containsKey("confusionMatrix")) {
                confusionMatrix = (Map<String, Object>) trainingResults.get("confusionMatrix");
            }
            if (trainingResults.containsKey("trainingRows")) {
                trainingRows = (Integer) trainingResults.get("trainingRows");
            }
            if (trainingResults.containsKey("testRows")) {
                testRows = (Integer) trainingResults.get("testRows");
            }
            if (trainingResults.containsKey("fraudRate")) {
                fraudRate = (Double) trainingResults.get("fraudRate");
            }
            if (trainingResults.containsKey("modelPath")) {
                modelPath = (String) trainingResults.get("modelPath");
            }
            if (trainingResults.containsKey("pipelinePath")) {
                pipelinePath = (String) trainingResults.get("pipelinePath");
            }
            if (trainingResults.containsKey("onnxPath")) {
                onnxPath = (String) trainingResults.get("onnxPath");
            }
        }

        // Get training metadata
        Map<String, Object> trainingMetadata = null;
        String datasetName = null;
        if (model.getHyperparameters() != null && model.getHyperparameters().containsKey("_trainingMetadata")) {
            trainingMetadata = (Map<String, Object>) model.getHyperparameters().get("_trainingMetadata");
            if (trainingMetadata.containsKey("datasetName")) {
                datasetName = (String) trainingMetadata.get("datasetName");
            }
        }

        // Build validatedBy info
        Map<String, Object> validatedByInfo = null;
        if (model.getValidatedBy() != null) {
            validatedByInfo = Map.of(
                    "id", model.getValidatedBy().getId(),
                    "email", model.getValidatedBy().getEmail(),
                    "firstName", model.getValidatedBy().getFirstName(),
                    "lastName", model.getValidatedBy().getLastName());
        }

        TrainingJobResponse response = TrainingJobResponse.builder()
                .modelUuid(model.getModelUuid())
                .modelName(model.getModelName())
                .modelType(model.getModelType())
                .status(model.getStatus())
                .metrics(metrics)
                .confusionMatrix(confusionMatrix)
                .hyperparameters(model.getHyperparameters())
                .featureImportance(model.getFeatureImportance())
                .trainingRows(trainingRows)
                .testRows(testRows)
                .fraudRate(fraudRate)
                .modelPath(modelPath)
                .pipelinePath(pipelinePath)
                .onnxPath(onnxPath)
                .startedAt(model.getCreatedAt())
                .completedAt(model.getUpdatedAt())
                .durationSeconds(model.getUpdatedAt() != null && model.getCreatedAt() != null
                        ? (int) (model.getUpdatedAt().getSecond() - model.getCreatedAt().getSecond())
                        : null)
                .validatedBy(validatedByInfo)
                .build();

        // Set derived metrics
        response.setAucRoc(model.getAuc());
        response.setPrecision(model.getPrecision());
        response.setRecall(model.getRecall());
        response.setF1Score(model.getF1Score());

        return response;
    }

    /**
     * Get model history as DTOs
     */
    public List<TrainingJobResponse> getModelHistory() {
        return modelMLRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toTrainingJobResponse)
                .toList();
    }

    /**
     * Get model history as paginated DTOs
     */
    public Page<TrainingJobResponse> getModelHistory(Pageable pageable) {
        return modelMLRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(this::toTrainingJobResponse);
    }

    /**
     * Get model by UUID as DTO
     */
    public TrainingJobResponse getModelByUuid(UUID modelUuid) {
        ModelML model = modelMLRepository.findByModelUuid(modelUuid)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelUuid));
        return toTrainingJobResponse(model);
    }

    @Transactional
    public void deleteModel(UUID modelUuid) {
        ModelML model = modelMLRepository.findByModelUuid(modelUuid)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelUuid));

        // Get file paths from hyperparameters before deleting the model
        String modelPath = null;
        String pipelinePath = null;
        String onnxPath = null;

        if (model.getHyperparameters() != null && model.getHyperparameters().containsKey("_trainingResults")) {
            Map<String, Object> trainingResults = (Map<String, Object>) model.getHyperparameters()
                    .get("_trainingResults");

            if (trainingResults.containsKey("modelPath")) {
                modelPath = (String) trainingResults.get("modelPath");
            }
            if (trainingResults.containsKey("pipelinePath")) {
                pipelinePath = (String) trainingResults.get("pipelinePath");
            }
            if (trainingResults.containsKey("onnxPath")) {
                onnxPath = (String) trainingResults.get("onnxPath");
            }
        }

        // Also check root level if not found in trainingResults
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

        // Delete files from MinIO
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

        // Delete the model from database
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

    private static final Map<String, Object> DEFAULT_RANDOM_FOREST_PARAMS = Map.of(
            "n_estimators", 100,
            "max_depth", 10,
            "min_samples_split", 2,
            "min_samples_leaf", 1,
            "max_features", "sqrt",
            "bootstrap", true,
            "oob_score", false,
            "random_state", 42,
            "n_jobs", -1);

    @Transactional
    public TrainingJobResponse trainRandomForest(TrainingJobRequest request) throws Exception {
        log.info("Starting Random Forest training for dataset: {}", request.getDatasetUuid());

        // Get current user (the creator)
        AppUser currentUser = getCurrentUser();
        log.info("Training initiated by user: {}", currentUser.getEmail());

        // 1. Get dataset and validate
        Dataset dataset = datasetRepository.findByDatasetUuid(request.getDatasetUuid())
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + request.getDatasetUuid()));

        if (dataset.getStatus() != DatasetStatus.PROCESSED) {
            throw new IllegalStateException(
                    "Dataset must be PROCESSED before training. Current status: " + dataset.getStatus());
        }

        // 2. Prepare hyperparameters (merge defaults with user provided)
        Map<String, Object> hyperparameters = new HashMap<>(DEFAULT_RANDOM_FOREST_PARAMS);
        if (request.getHyperparameters() != null) {
            hyperparameters.putAll(request.getHyperparameters());
        }

        // Ensure random_state is set
        hyperparameters.put("random_state", request.getRandomSeed());

        // Add training metadata
        Map<String, Object> trainingMetadata = new HashMap<>();
        trainingMetadata.put("datasetUuid", request.getDatasetUuid().toString());
        trainingMetadata.put("datasetName", dataset.getDatasetName());
        trainingMetadata.put("testSize", request.getTestSize());
        trainingMetadata.put("randomSeed", request.getRandomSeed());
        trainingMetadata.put("trainingDate", LocalDateTime.now().toString());
        trainingMetadata.put("createdBy", currentUser.getEmail()); // Add creator email to metadata

        hyperparameters.put("_trainingMetadata", trainingMetadata);

        // 3. Create or get model
        ModelML model = getOrCreateModel(request, hyperparameters, currentUser);

        // 4. Launch training in Python container
        TrainingJobResponse response = launchTraining(
                dataset.getFilePath(),
                dataset.getDatasetUuid(),
                model.getModelUuid(),
                hyperparameters,
                request.getTestSize(),
                request.getRandomSeed());

        // 5. Update model with results
        updateModelWithResults(model, response);

        return response;
    }

    private ModelML getOrCreateModel(TrainingJobRequest request, Map<String, Object> hyperparameters, AppUser creator) {
        // If modelUuid is provided, use existing model
        if (request.getModelUuid() != null) {
            return modelMLRepository.findByModelUuid(request.getModelUuid())
                    .orElseThrow(() -> new IllegalArgumentException("Model not found: " + request.getModelUuid()));
        }

        // Create new model with creator
        ModelML model = ModelML.builder()
                .modelUuid(UUID.randomUUID())
                .modelName(request.getJobName())
                .modelType("RANDOM_FOREST")
                .version(generateVersion())
                .hyperparameters(hyperparameters)
                .status("Draft")
                .createdBy(creator) // Set the creator
                .createdAt(LocalDateTime.now())
                .build();

        return modelMLRepository.save(model);
    }

    private String generateVersion() {
        return "1.0." + System.currentTimeMillis();
    }

    private TrainingJobResponse launchTraining(
            String datasetPath,
            UUID datasetId,
            UUID modelId,
            Map<String, Object> hyperparameters,
            Double testSize,
            Integer randomSeed) throws Exception {

        Path tempDataset = null;
        Path tempHyperparams = null;
        Path tempModel = null;
        Path tempPipeline = null;
        String containerDatasetPath = null;
        String containerHyperparamsPath = null;
        String containerModelPath = null;
        String containerPipelinePath = null;

        try {
            // 1. Download dataset from MinIO to temp file
            tempDataset = Files.createTempFile("train_dataset_", ".parquet");
            try (var in = storageService.download(datasetPath);
                    var out = Files.newOutputStream(tempDataset)) {
                in.transferTo(out);
            }

            // 2. Create hyperparameters JSON file
            tempHyperparams = Files.createTempFile("hyperparams_", ".json");
            objectMapper.writeValue(tempHyperparams.toFile(), hyperparameters);

            // 3. Copy files to container
            containerDatasetPath = "/app/data/" + tempDataset.getFileName().toString();
            containerHyperparamsPath = "/app/data/" + tempHyperparams.getFileName().toString();

            execDockerCopy(tempDataset, containerDatasetPath);
            execDockerCopy(tempHyperparams, containerHyperparamsPath);

            // 4. Prepare command for training
            UUID jobUuid = UUID.randomUUID();
            List<String> command = new ArrayList<>(List.of(
                    "docker", "exec", "fraud-ml-pipeline",
                    "python", "/app/scripts/train_random_forest.py",
                    "--dataset-path", containerDatasetPath,
                    "--dataset-id", datasetId.toString(),
                    "--job-id", jobUuid.toString(),
                    "--hyperparameters", containerHyperparamsPath,
                    "--test-size", String.valueOf(testSize),
                    "--random-seed", String.valueOf(randomSeed)));

            log.info("Executing command: {}", String.join(" ", command));

            // 5. Run training.
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();

            // Read stdout only — this is where the Python script writes the JSON result
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
                    log.error("Training stdout: {}", result);
                }
                throw new RuntimeException("Training failed with exit code " + exitCode);
            }

            if (result.isEmpty()) {
                throw new RuntimeException("Training script produced no stdout output");
            }

            log.debug("Training stdout (first 500 chars): {}",
                    result.length() > 500 ? result.substring(0, 500) : result);

            // 6. Parse JSON result
            JsonNode root = extractJsonFromOutput(result);
            if (root == null) {
                log.error("Could not extract JSON from stdout: {}", result);
                throw new RuntimeException("No valid JSON output from training script");
            }

            // 7. Build response
            TrainingJobResponse response = buildTrainingResponse(root, datasetId, modelId, jobUuid);

            // 8. Upload trained model joblib to MinIO
            String modelFilename = "random_forest_" + jobUuid + ".joblib";
            String modelKey = "models/trained/" + datasetId + "/" + modelFilename;
            containerModelPath = "/app/output/models/" + modelFilename;
            tempModel = Files.createTempFile("trained_model_", ".joblib");

            execDockerCopyFromContainer(containerModelPath, tempModel);
            try (var in = Files.newInputStream(tempModel)) {
                storageService.upload(modelKey, in, Files.size(tempModel), "application/octet-stream");
            }
            response.setModelPath(modelKey);
            log.info("Model uploaded to MinIO: {}", modelKey);

            // 9. Upload pipeline joblib to MinIO
            String pipelineFilename = "pipeline_" + jobUuid + ".joblib";
            containerPipelinePath = "/app/output/pipelines/" + pipelineFilename;
            String pipelineKey = "models/pipelines/" + datasetId + "/" + pipelineFilename;
            tempPipeline = Files.createTempFile("pipeline_", ".joblib");
            try {
                execDockerCopyFromContainer(containerPipelinePath, tempPipeline);
                try (var in = Files.newInputStream(tempPipeline)) {
                    storageService.upload(pipelineKey, in, Files.size(tempPipeline), "application/octet-stream");
                }
                response.setPipelinePath(pipelineKey);
                log.info("Pipeline uploaded to MinIO: {}", pipelineKey);
            } catch (Exception e) {
                log.warn("Could not upload pipeline: {}", e.getMessage());
            }

            // 10. Upload ONNX model to MinIO
            JsonNode onnxPathNode = root.get("onnx_path");
            if (onnxPathNode != null && !onnxPathNode.isNull()) {
                String containerOnnxPath = onnxPathNode.asText();
                Path tempOnnx = Files.createTempFile("model_", ".onnx");
                try {
                    execDockerCopyFromContainer(containerOnnxPath, tempOnnx);
                    String onnxFilename = "random_forest_" + jobUuid + ".onnx";
                    String onnxKey = "models/onnx/" + datasetId + "/" + onnxFilename;
                    try (var in = Files.newInputStream(tempOnnx)) {
                        storageService.upload(onnxKey, in, Files.size(tempOnnx), "application/octet-stream");
                    }
                    response.setOnnxPath(onnxKey);
                    log.info("ONNX model uploaded to MinIO: {}", onnxKey);
                    try {
                        execDockerCommand("rm", "-f", containerOnnxPath);
                    } catch (Exception e) {
                        /* best effort */ }
                } catch (Exception e) {
                    log.warn("Could not upload ONNX model (non-fatal): {}", e.getMessage());
                } finally {
                    Files.deleteIfExists(tempOnnx);
                }
            } else {
                log.info("No ONNX output from training script");
            }

            return response;

        } finally {
            // Cleanup temp files
            if (tempDataset != null)
                Files.deleteIfExists(tempDataset);
            if (tempHyperparams != null)
                Files.deleteIfExists(tempHyperparams);
            if (tempModel != null)
                Files.deleteIfExists(tempModel);
            if (tempPipeline != null)
                Files.deleteIfExists(tempPipeline);

            if (containerDatasetPath != null) {
                try {
                    execDockerCommand("rm", "-f", containerDatasetPath);
                } catch (Exception e) {
                    /* best effort */ }
            }
            if (containerHyperparamsPath != null) {
                try {
                    execDockerCommand("rm", "-f", containerHyperparamsPath);
                } catch (Exception e) {
                    /* best effort */ }
            }
            if (containerModelPath != null) {
                try {
                    execDockerCommand("rm", "-f", containerModelPath);
                } catch (Exception e) {
                    /* best effort */ }
            }
            if (containerPipelinePath != null) {
                try {
                    execDockerCommand("rm", "-f", containerPipelinePath);
                } catch (Exception e) {
                    /* best effort */ }
            }
        }
    }

    private void execDockerCopyFromContainer(String containerPath, Path localPath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "cp",
                "fraud-ml-pipeline:" + containerPath,
                localPath.toAbsolutePath().toString());
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Docker copy from container failed");
        }
    }

    private TrainingJobResponse buildTrainingResponse(JsonNode root, UUID datasetId, UUID modelId, UUID jobUuid) {
        JsonNode metrics = root.get("metrics");
        JsonNode confusionMatrix = root.get("confusion_matrix");
        JsonNode trainingStats = root.get("training_stats");
        JsonNode thresholds = root.get("thresholds");
        JsonNode hyperparametersUsed = root.get("hyperparameters_used");
        JsonNode selectedFeatures = root.get("selected_original_features");
        JsonNode featureImportance = root.get("feature_importance");

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
            featureImportanceMap = objectMapper.convertValue(featureImportance, new TypeReference<Map<String, Object>>() {
            });
        }

        Double fbetaThreshold = null;
        Double fbetaScore = null;
        Double costThreshold = null;
        Double minCost = null;
        Double finalThreshold = null;

        if (thresholds != null) {
            if (thresholds.has("fbeta"))
                fbetaThreshold = thresholds.get("fbeta").asDouble();
            if (thresholds.has("fbeta_score"))
                fbetaScore = thresholds.get("fbeta_score").asDouble();
            if (thresholds.has("cost"))
                costThreshold = thresholds.get("cost").asDouble();
            if (thresholds.has("min_cost"))
                minCost = thresholds.get("min_cost").asDouble();
            if (thresholds.has("final"))
                finalThreshold = thresholds.get("final").asDouble();
        }

        TrainingJobResponse response = TrainingJobResponse.builder()
                .jobUuid(jobUuid)
                .jobName("Random Forest Training")
                .status(root.has("status") ? root.get("status").asText() : "UNKNOWN")
                .datasetUuid(datasetId)
                .modelUuid(modelId)
                .hyperparameters(hyperparamsMap)
                .metrics(metricsMap)
                .confusionMatrix(confusionMap)
                .featureImportance(featureImportanceMap)
                .selectedFeatures(selectedFeaturesList)
                .modelPath(root.has("model_path") ? root.get("model_path").asText() : null)
                .onnxPath(root.has("onnx_path") ? root.get("onnx_path").asText() : null)
                .startedAt(root.has("start_time") ? LocalDateTime.parse(root.get("start_time").asText()) : null)
                .completedAt(root.has("end_time") ? LocalDateTime.parse(root.get("end_time").asText()) : null)
                .durationSeconds(root.has("duration_seconds") ? root.get("duration_seconds").asInt() : null)
                .build();

        if (trainingStats != null) {
            if (trainingStats.has("train_rows"))
                response.setTrainingRows(trainingStats.get("train_rows").asInt());
            if (trainingStats.has("test_rows"))
                response.setTestRows(trainingStats.get("test_rows").asInt());
            if (trainingStats.has("fraud_rate_test"))
                response.setFraudRate(trainingStats.get("fraud_rate_test").asDouble());
        }

        // Extract metrics for easy access
        response.extractMetrics();

        return response;
    }

    private void updateModelWithResults(ModelML model, TrainingJobResponse response) {
        // Update model metrics
        if (response.getMetrics() != null) {
            model.setAuc(response.getAucRoc());
            model.setPrecision(response.getPrecision());
            model.setRecall(response.getRecall());
            model.setF1Score(response.getF1Score());

            if (response.getFeatureImportance() != null) {
                model.setFeatureImportance(response.getFeatureImportance());
            }
        }

        // Add training results to hyperparameters
        Map<String, Object> hyperparameters = new HashMap<>();
        if (model.getHyperparameters() != null) {
            hyperparameters.putAll(model.getHyperparameters());
        }

        Map<String, Object> trainingResults = new HashMap<>();
        trainingResults.put("jobUuid", response.getJobUuid().toString());
        trainingResults.put("completedAt", response.getCompletedAt().toString());
        trainingResults.put("durationSeconds", response.getDurationSeconds());
        trainingResults.put("metrics", response.getMetrics());
        trainingResults.put("confusionMatrix", response.getConfusionMatrix());
        trainingResults.put("trainingRows", response.getTrainingRows());
        trainingResults.put("testRows", response.getTestRows());
        trainingResults.put("fraudRate", response.getFraudRate());
        trainingResults.put("modelPath", response.getModelPath());
        trainingResults.put("pipelinePath", response.getPipelinePath());
        trainingResults.put("onnxPath", response.getOnnxPath());

        hyperparameters.put("_trainingResults", trainingResults);
        model.setHyperparameters(hyperparameters);

        model.setUpdatedAt(LocalDateTime.now());
        modelMLRepository.save(model);
    }

    public ModelMetricsResponse getModelMetrics(UUID modelUuid) {
        ModelML model = modelMLRepository.findByModelUuid(modelUuid)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelUuid));

        // Extract confusion matrix from hyperparameters
        Map<String, Long> confusionMatrix = new HashMap<>();
        String onnxPath = null;

        if (model.getHyperparameters() != null && model.getHyperparameters().containsKey("_trainingResults")) {
            Map<String, Object> trainingResults = (Map<String, Object>) model.getHyperparameters()
                    .get("_trainingResults");
            if (trainingResults.containsKey("confusionMatrix")) {
                confusionMatrix = objectMapper.convertValue(
                        trainingResults.get("confusionMatrix"),
                        new TypeReference<Map<String, Long>>() {
                        });
            }
            if (trainingResults.containsKey("onnxPath")) {
                onnxPath = (String) trainingResults.get("onnxPath");
            }
        }

        // Extract feature importance
        Map<String, Double> importance = new HashMap<>();
        if (model.getFeatureImportance() != null) {
            importance = objectMapper.convertValue(
                    model.getFeatureImportance(),
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
                .onnxPath(onnxPath)
                .validationComment(commentRepository
                        .findFirstByModelModelUuidAndObjectOrderByCreatedAtDesc(modelUuid, "Model Validation")
                        .map(Comment::getContent)
                        .orElse(null))
                .build();

        response.calculateDerivedMetrics();
        return response;
    }

    private void execDockerCopy(Path source, String destination) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "cp", source.toAbsolutePath().toString(),
                "fraud-ml-pipeline:" + destination);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Docker copy failed");
        }
    }

    private void execDockerCommand(String... commands) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker", "exec", "fraud-ml-pipeline"));
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
                    log.warn("Line looked like JSON but failed to parse: {}", e.getMessage());
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

    // ================================ Validation modèle
    // ========================================

    @Transactional
    public void validateModel(ModelValidationActionDTO action, String managerKeycloakId) {
        ModelML model = modelMLRepository.findByModelUuid(action.getModelUuid())
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + action.getModelUuid()));

        AppUser manager = keycloakMappingRepository.findByKeycloakId(managerKeycloakId)
                .map(KeycloakMapping::getUser)
                .orElseThrow(() -> new IllegalArgumentException("Manager not found: " + managerKeycloakId));

        // Update model status and validation details
        model.setStatus(action.getStatus());
        model.setValidatedBy(manager);
        model.setUpdatedAt(LocalDateTime.now());
        modelMLRepository.save(model);

        // Save optional comment
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

    /**
     * Get ALL models (for ADMIN users) with pagination
     */
    public Page<TrainingJobResponse> getAllModels(Pageable pageable) {
        log.info("Fetching ALL models with pagination: {}", pageable);
        return modelMLRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(this::toTrainingJobResponse);
    }

    /**
     * Get models created by the current user with pagination
     */
    /**
     * Get models for the current user
     * - For ANALYST: models created by them
     * - For MANAGER: models validated by them
     */
    public Page<TrainingJobResponse> getMyModels(Pageable pageable) {
        AppUser currentUser = getCurrentUser();
        log.info("Fetching models for user: {} (ID: {}) with role: {}",
                currentUser.getEmail(), currentUser.getId(),
                currentUser.getRole() != null ? currentUser.getRole().getName() : "UNKNOWN");

        // If user is manager, show models they validated
        if (currentUser.getRole() != null && "MANAGER".equals(currentUser.getRole().getName())) {
            log.info("User is MANAGER, showing models validated by them (ID: {})", currentUser.getId());
            return modelMLRepository.findByValidatedByIdOrderByCreatedAtDesc(currentUser.getId(), pageable)
                    .map(this::toTrainingJobResponse);
        } else {
            // Analyst: show only their own models
            log.info("User is ANALYST, showing models created by them (ID: {})", currentUser.getId());
            return modelMLRepository.findByCreatedByIdOrderByCreatedAtDesc(currentUser.getId(), pageable)
                    .map(this::toTrainingJobResponse);
        }
    }
}
package com.fraud.backend.data.service.frauddetection;

import com.fraud.backend.api.dto.frauddetection.model.request.TrainingJobRequest;
import com.fraud.backend.api.dto.frauddetection.model.response.ModelMetricsResponse;
import com.fraud.backend.api.dto.frauddetection.model.response.TrainingJobResponse;
import com.fraud.backend.data.entity.frauddetection.Dataset;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.enums.datasets.DatasetStatus;
import com.fraud.backend.data.repository.frauddetection.DatasetRepository;
import com.fraud.backend.data.repository.frauddetection.ModelMLRepository;
import com.fraud.backend.data.service.storage.StorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    private static final Map<String, Object> DEFAULT_RANDOM_FOREST_PARAMS = Map.of(
        "n_estimators", 100,
        "max_depth", 10,
        "min_samples_split", 2,
        "min_samples_leaf", 1,
        "max_features", "sqrt",
        "bootstrap", true,
        "oob_score", false,
        "random_state", 42,
        "n_jobs", -1
    );

    @Transactional
    public TrainingJobResponse trainRandomForest(TrainingJobRequest request) throws Exception {
        log.info("Starting Random Forest training for dataset: {}", request.getDatasetUuid());

        // 1. Get dataset and validate
        Dataset dataset = datasetRepository.findByDatasetUuid(request.getDatasetUuid())
            .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + request.getDatasetUuid()));

        if (dataset.getStatus() != DatasetStatus.PROCESSED) {
            throw new IllegalStateException("Dataset must be PROCESSED before training. Current status: " + dataset.getStatus());
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
        
        hyperparameters.put("_trainingMetadata", trainingMetadata);

        // 3. Create or get model
        ModelML model = getOrCreateModel(request, hyperparameters);

        // 4. Launch training in Python container
        TrainingJobResponse response = launchTraining(
            dataset.getFilePath(),
            dataset.getDatasetUuid(),
            model.getModelUuid(),
            hyperparameters,
            request.getTestSize(),
            request.getRandomSeed()
        );

        // 5. Update model with results
        updateModelWithResults(model, response);

        // 6. Export to ONNX if training was successful
        if ("COMPLETED".equals(response.getStatus())) {
            exportModelToOnnx(model, response.getJobUuid());
        }

        return response;
    }

    private ModelML getOrCreateModel(TrainingJobRequest request, Map<String, Object> hyperparameters) {
        // If modelUuid is provided, use existing model
        if (request.getModelUuid() != null) {
            return modelMLRepository.findByModelUuid(request.getModelUuid())
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + request.getModelUuid()));
        }

        // Create new model
        ModelML model = ModelML.builder()
            .modelUuid(UUID.randomUUID())
            .modelName(request.getJobName())
            .modelType("RANDOM_FOREST")
            .version(generateVersion())
            .hyperparameters(hyperparameters)
            .status("Draft")
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
        Integer randomSeed
    ) throws Exception {
        
        Path tempDataset = null;
        Path tempHyperparams = null;
        Path tempModel = null;
        String containerDatasetPath = null;
        String containerHyperparamsPath = null;
        String containerModelPath = null;

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
                "--random-seed", String.valueOf(randomSeed)
            ));

            log.info("Executing command: {}", String.join(" ", command));

            // 5. Run training
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.debug("[Python] {}", line);
                }
            }

            boolean finished = process.waitFor(30, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Training timed out after 30 minutes");
            }

            int exitCode = process.exitValue();
            String result = output.toString().trim();

            if (exitCode != 0) {
                log.error("Training failed with code {}: {}", exitCode, result);
                throw new RuntimeException("Training failed: " + result);
            }

            // 6. Parse JSON result
            JsonNode root = extractJsonFromOutput(result);
            if (root == null) {
                throw new RuntimeException("No valid JSON output from training script");
            }

            // 7. Build response
            TrainingJobResponse response = buildTrainingResponse(root, datasetId, modelId, jobUuid);
            
            // 8. Save the trained model file path
            String modelFilename = "random_forest_" + jobUuid + ".joblib";
            String modelKey = "models/trained/" + datasetId + "/" + modelFilename;
            
            // Copy model from container to MinIO
            containerModelPath = "/app/output/models/" + modelFilename;
            tempModel = Files.createTempFile("trained_model_", ".joblib");
            
            execDockerCopyFromContainer(containerModelPath, tempModel);
            try (var in = Files.newInputStream(tempModel)) {
                storageService.upload(modelKey, in, Files.size(tempModel), "application/octet-stream");
            }
            response.setModelPath(modelKey);

            return response;

        } finally {
            // Cleanup
            if (tempDataset != null) Files.deleteIfExists(tempDataset);
            if (tempHyperparams != null) Files.deleteIfExists(tempHyperparams);
            if (tempModel != null) Files.deleteIfExists(tempModel);
            
            if (containerDatasetPath != null) {
                try { execDockerCommand("rm", "-f", containerDatasetPath); } catch (Exception e) {}
            }
            if (containerHyperparamsPath != null) {
                try { execDockerCommand("rm", "-f", containerHyperparamsPath); } catch (Exception e) {}
            }
            if (containerModelPath != null) {
                try { execDockerCommand("rm", "-f", containerModelPath); } catch (Exception e) {}
            }
        }
    }

    private void exportModelToOnnx(ModelML model, UUID jobUuid) {
        Path tempModel = null;
        Path tempOnnx = null;
        String containerModelPath = null;
        String containerOnnxPath = null;
        
        try {
            log.info("Exporting model to ONNX for model: {}", model.getModelUuid());
            
            // Get model path from hyperparameters
            Map<String, Object> hyperparams = model.getHyperparameters();
            if (hyperparams == null || !hyperparams.containsKey("_trainingResults")) {
                log.warn("No training results found for model: {}", model.getModelUuid());
                return;
            }

            Map<String, Object> trainingResults = (Map<String, Object>) hyperparams.get("_trainingResults");
            String modelPath = (String) trainingResults.get("modelPath");
            
            if (modelPath == null) {
                log.warn("No model path found in training results");
                return;
            }

            // Download the model file
            tempModel = Files.createTempFile("model_", ".joblib");
            try (var in = storageService.download(modelPath);
                 var out = Files.newOutputStream(tempModel)) {
                in.transferTo(out);
            }

            // Copy to container
            containerModelPath = "/app/data/" + tempModel.getFileName().toString();
            execDockerCopy(tempModel, containerModelPath);

            // Run ONNX export
            List<String> command = new ArrayList<>(List.of(
                "docker", "exec", "fraud-ml-pipeline",
                "python", "/app/scripts/export_onnx.py",
                "--model-type", "random_forest",
                "--model-path", containerModelPath,
                "--output-dir", "/app/output/onnx"
            ));

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            
            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            
            if (!finished) {
                process.destroyForcibly();
                log.error("ONNX export timed out after 5 minutes");
                return;
            }
            
            int exitCode = process.exitValue();
            
            if (exitCode == 0) {
                // Copy ONNX file back from container
                String onnxFilename = "random_forest_" + jobUuid + ".onnx";
                containerOnnxPath = "/app/output/onnx/" + onnxFilename;
                
                tempOnnx = Files.createTempFile("model_", ".onnx");
                execDockerCopyFromContainer(containerOnnxPath, tempOnnx);
                
                // Upload to MinIO
                String onnxKey = "models/onnx/" + model.getModelUuid() + "/" + onnxFilename;
                try (var in = Files.newInputStream(tempOnnx)) {
                    storageService.upload(onnxKey, in, Files.size(tempOnnx), "application/octet-stream");
                }
                
                // Store ONNX path in training results
                trainingResults.put("onnxPath", onnxKey);
                hyperparams.put("_trainingResults", trainingResults);
                model.setHyperparameters(hyperparams);
                modelMLRepository.save(model);
                
                log.info("ONNX model exported and saved: {}", onnxKey);
            } else {
                log.error("ONNX export failed with exit code: {}", exitCode);
            }

        } catch (Exception e) {
            log.error("Failed to export model to ONNX: {}", e.getMessage(), e);
        } finally {
            // Cleanup
            if (tempModel != null) {
                try { Files.deleteIfExists(tempModel); } catch (Exception e) {}
            }
            if (tempOnnx != null) {
                try { Files.deleteIfExists(tempOnnx); } catch (Exception e) {}
            }
            if (containerModelPath != null) {
                try { execDockerCommand("rm", "-f", containerModelPath); } catch (Exception e) {}
            }
            if (containerOnnxPath != null) {
                try { execDockerCommand("rm", "-f", containerOnnxPath); } catch (Exception e) {}
            }
        }
    }

    private void execDockerCopyFromContainer(String containerPath, Path localPath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "docker", "cp",
            "fraud-ml-pipeline:" + containerPath,
            localPath.toAbsolutePath().toString()
        );
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Docker copy from container failed");
        }
    }

    private TrainingJobResponse buildTrainingResponse(JsonNode root, UUID datasetId, UUID modelId, UUID jobUuid) {
        JsonNode metrics = root.get("metrics");
        JsonNode confusionMatrix = root.get("confusion_matrix");
        JsonNode featureImportance = root.get("feature_importance");
        JsonNode trainingStats = root.get("training_stats");

        Map<String, Object> metricsMap = new HashMap<>();
        if (metrics != null) {
            metricsMap = objectMapper.convertValue(metrics, new TypeReference<Map<String, Object>>() {});
        }

        Map<String, Object> confusionMap = new HashMap<>();
        if (confusionMatrix != null) {
            confusionMap = objectMapper.convertValue(confusionMatrix, new TypeReference<Map<String, Object>>() {});
        }

        Map<String, Object> importanceMap = new HashMap<>();
        if (featureImportance != null) {
            importanceMap = objectMapper.convertValue(featureImportance, new TypeReference<Map<String, Object>>() {});
        }

        TrainingJobResponse response = TrainingJobResponse.builder()
            .jobUuid(jobUuid)
            .jobName("Random Forest Training")
            .status(root.get("status").asText())
            .datasetUuid(datasetId)
            .modelUuid(modelId)
            .hyperparameters(objectMapper.convertValue(root.get("hyperparameters_used"), new TypeReference<Map<String, Object>>() {}))
            .metrics(metricsMap)
            .featureImportance(importanceMap)
            .confusionMatrix(confusionMap)
            .startedAt(LocalDateTime.parse(root.get("start_time").asText()))
            .completedAt(LocalDateTime.parse(root.get("end_time").asText()))
            .durationSeconds(root.get("duration_seconds").asInt())
            .build();

        if (trainingStats != null) {
            response.setTrainingRows(trainingStats.get("train_rows").asInt());
            response.setTestRows(trainingStats.get("test_rows").asInt());
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
            Map<String, Object> trainingResults = (Map<String, Object>) model.getHyperparameters().get("_trainingResults");
            if (trainingResults.containsKey("confusionMatrix")) {
                confusionMatrix = objectMapper.convertValue(
                    trainingResults.get("confusionMatrix"),
                    new TypeReference<Map<String, Long>>() {}
                );
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
                new TypeReference<Map<String, Double>>() {}
            );
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
            .build();

        response.calculateDerivedMetrics();
        return response;
    }

    private void execDockerCopy(Path source, String destination) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "docker", "cp", source.toAbsolutePath().toString(),
            "fraud-ml-pipeline:" + destination
        );
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
        int jsonStart = output.indexOf('{');
        int jsonEnd = output.lastIndexOf('}');
        
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            try {
                String jsonStr = output.substring(jsonStart, jsonEnd + 1);
                return objectMapper.readTree(jsonStr);
            } catch (Exception e) {
                log.error("Failed to parse JSON: {}", e.getMessage());
            }
        }
        return null;
    }
}
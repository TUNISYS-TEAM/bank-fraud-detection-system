package com.fraud.backend.data.service.frauddetection.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.backend.data.entity.corebanking.ClientBehavior;
import com.fraud.backend.data.entity.corebanking.Transaction;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.repository.corebanking.ClientBehaviorRepository;
import com.fraud.backend.data.repository.corebanking.TransactionRepository;
import com.fraud.backend.data.repository.frauddetection.ModelMLRepository;
import com.fraud.backend.data.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PythonInferenceService {

    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final ModelMLRepository modelMLRepository;
    private final TransactionRepository transactionRepository;
    private final ClientBehaviorRepository clientBehaviorRepository;

    @Value("${fraud.inference.container-name:fraud-ml-pipeline}")
    private String containerName;

    @Value("${fraud.inference.use-mock:false}")
    private boolean useMock;

    @Value("${fraud.inference.timeout-seconds:120}")
    private int timeoutSeconds;

    @Value("${fraud.inference.max-concurrent:10}")
    private int maxConcurrent;

    private ExecutorService inferenceExecutor;

    private final Map<String, Path> modelFileCache = new ConcurrentHashMap<>();
    private final Map<String, Long> modelCacheTime = new ConcurrentHashMap<>();
    private static final long MODEL_CACHE_TTL_MS = 300_000;

    private static final String CONTAINER_TMP_MODELS_DIR = "/tmp/fraud-inference/models";
    private static final String CONTAINER_TMP_DATA_DIR = "/tmp/fraud-inference/data";

    @PostConstruct
    public void init() {
        inferenceExecutor = Executors.newFixedThreadPool(maxConcurrent);
        log.info("PythonInferenceService initialized with {} concurrent threads", maxConcurrent);
    }

    @PreDestroy
    public void destroy() {
        if (inferenceExecutor != null) {
            inferenceExecutor.shutdown();
            try {
                if (!inferenceExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    inferenceExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                inferenceExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        modelFileCache.values().forEach(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (Exception e) {
                log.warn("Failed to delete cached model file: {}", path);
            }
        });
    }

    public Map<String, Object> predict(UUID modelUuid, Long accountId, UUID transactionUuid) {
        if (useMock) {
            return mockPredict(modelUuid, accountId, transactionUuid);
        }

        Path tempModel = null;
        Path tempPipeline = null;
        Path tempInput = null;
        String containerModelPath = null;
        String containerPipelinePath = null;
        String containerInputPath = null;

        try {
            ModelML model = modelMLRepository.findByModelUuid(modelUuid)
                    .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelUuid));

            Transaction transaction = transactionRepository.findByTransactionUuid(transactionUuid)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionUuid));

            ClientBehavior existingBehavior = clientBehaviorRepository
                    .findByAccountId(accountId)
                    .orElse(null);

            Map<String, Object> inputData = new HashMap<>();
            inputData.put("model_uuid", modelUuid.toString());
            inputData.put("transaction", transactionToMap(transaction));
            inputData.put("has_existing_behavior", existingBehavior != null);

            if (existingBehavior != null) {
                inputData.put("existing_behavior", behaviorToMap(existingBehavior));
            }

            String modelStoragePath = getModelPath(model);
            String pipelineStoragePath = getPipelinePath(model);

            if (modelStoragePath == null || modelStoragePath.isBlank()) {
                throw new RuntimeException("Model path is null or empty for model: " + modelUuid);
            }
            if (pipelineStoragePath == null || pipelineStoragePath.isBlank()) {
                throw new RuntimeException("Pipeline path is null or empty for model: " + modelUuid);
            }

            tempModel = getOrDownloadModel(modelStoragePath);
            tempPipeline = getOrDownloadModel(pipelineStoragePath);
            tempInput = Files.createTempFile("inference_input_", ".json");
            objectMapper.writeValue(tempInput.toFile(), inputData);

            String originalModelFileName = Paths.get(modelStoragePath).getFileName().toString();
            String originalPipelineFileName = Paths.get(pipelineStoragePath).getFileName().toString();
            String inputFileName = tempInput.getFileName().toString();

            containerModelPath = CONTAINER_TMP_MODELS_DIR + "/" + originalModelFileName;
            containerPipelinePath = CONTAINER_TMP_MODELS_DIR + "/" + originalPipelineFileName;
            containerInputPath = CONTAINER_TMP_DATA_DIR + "/" + inputFileName;

            ensureContainerTempDirs();
            execDockerCopy(tempModel, containerModelPath);
            execDockerCopy(tempPipeline, containerPipelinePath);
            execDockerCopy(tempInput, containerInputPath);

            double threshold = getModelThreshold(model);
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "exec", containerName,
                    "python", "/app/scripts/inference_service.py",
                    "--model-path", containerModelPath,
                    "--pipeline-path", containerPipelinePath,
                    "--input", containerInputPath,
                    "--threshold", String.valueOf(threshold));

            Process process = pb.start();
            String outputStr = executeProcess(process);

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(outputStr, Map.class);
            return result;

        } catch (Exception e) {
            log.error("Prediction failed: {}", e.getMessage(), e);
            throw new RuntimeException("Prediction failed: " + e.getMessage(), e);
        } finally {
            cleanupTempFiles(tempInput);
            cleanupContainerFiles(containerInputPath);
        }
    }

    public List<Map<String, Object>> predictBatch(UUID modelUuid, List<Map<String, Object>> batchItems) {
        if (useMock) {
            return batchItems.stream()
                    .map(item -> mockPredict(modelUuid, null, null))
                    .toList();
        }

        Path tempModel = null;
        Path tempPipeline = null;
        Path tempOnnx = null;
        Path tempInput = null;
        String containerModelPath = null;
        String containerPipelinePath = null;
        String containerOnnxPath = null;
        String containerInputPath = null;

        try {
            ModelML model = modelMLRepository.findByModelUuid(modelUuid)
                    .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelUuid));

            Map<String, Object> inputData = new HashMap<>();
            inputData.put("model_uuid", modelUuid.toString());
            inputData.put("batch", batchItems);

            String modelStoragePath = getModelPath(model);
            String pipelineStoragePath = getPipelinePath(model);

            if (modelStoragePath == null || modelStoragePath.isBlank()) {
                throw new RuntimeException("Model path is null or empty for model: " + modelUuid);
            }
            if (pipelineStoragePath == null || pipelineStoragePath.isBlank()) {
                throw new RuntimeException("Pipeline path is null or empty for model: " + modelUuid);
            }

            // Download joblib model and pipeline
            tempModel = getOrDownloadModel(modelStoragePath);
            tempPipeline = getOrDownloadModel(pipelineStoragePath);

            // Build ONNX path: replace /trained/ with /onnx/ and change extension
            String onnxStoragePath = modelStoragePath.replace("/trained/", "/onnx/").replace(".joblib", ".onnx");
            boolean useOnnx = false;

            try {
                tempOnnx = getOrDownloadModel(onnxStoragePath);
                useOnnx = true;
                log.info("ONNX model found and downloaded: {}", onnxStoragePath);
            } catch (Exception e) {
                log.info("ONNX model not found at {}, using joblib", onnxStoragePath);
            }

            tempInput = Files.createTempFile("batch_inference_input_", ".json");
            objectMapper.writeValue(tempInput.toFile(), inputData);

            // Use original filenames
            String originalModelFileName = Paths.get(modelStoragePath).getFileName().toString();
            String originalPipelineFileName = Paths.get(pipelineStoragePath).getFileName().toString();
            String inputFileName = tempInput.getFileName().toString();

            containerModelPath = CONTAINER_TMP_MODELS_DIR + "/" + originalModelFileName;
            containerPipelinePath = CONTAINER_TMP_MODELS_DIR + "/" + originalPipelineFileName;
            containerInputPath = CONTAINER_TMP_DATA_DIR + "/" + inputFileName;

            ensureContainerTempDirs();
            execDockerCopy(tempModel, containerModelPath);
            execDockerCopy(tempPipeline, containerPipelinePath);
            execDockerCopy(tempInput, containerInputPath);

            // Copy ONNX if available
            if (useOnnx && tempOnnx != null) {
                String originalOnnxFileName = Paths.get(onnxStoragePath).getFileName().toString();
                containerOnnxPath = CONTAINER_TMP_MODELS_DIR + "/" + originalOnnxFileName;
                execDockerCopy(tempOnnx, containerOnnxPath);
                log.info("ONNX model copied to container: {}", containerOnnxPath);
            }

            double threshold = getModelThreshold(model);
            ProcessBuilder pb;
            List<String> command = new ArrayList<>(Arrays.asList(
                    "docker", "exec", containerName,
                    "python", "/app/scripts/inference_service.py",
                    "--pipeline-path", containerPipelinePath,
                    "--input", containerInputPath,
                    "--threshold", String.valueOf(threshold)
            ));
            
            if (useOnnx && containerOnnxPath != null) {
                log.info("Using ONNX model for inference (2-3x faster)");
                command.add("--model-path");
                command.add(containerOnnxPath);
            } else {
                log.info("Using joblib model for inference");
                command.add("--model-path");
                command.add(containerModelPath);
            }
            
            
            pb = new ProcessBuilder(command);

            Process process = pb.start();
            String outputStr = executeProcess(process);

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(outputStr, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
            return results;

        } catch (Exception e) {
            log.error("Batch prediction failed: {}", e.getMessage(), e);
            throw new RuntimeException("Batch prediction failed: " + e.getMessage(), e);
        } finally {
            cleanupTempFiles(tempInput);
            cleanupContainerFiles(containerInputPath);
        }
    }

    /**
     * Executes a process while consuming stdout and stderr concurrently to avoid deadlocks.
     */
    private String executeProcess(Process process) throws Exception {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        // Create threads to read both streams
        Thread stdoutThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                }
            } catch (IOException e) {
                log.error("Error reading stdout", e);
            }
        });

        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                    log.debug("Python stderr: {}", line);
                }
            } catch (IOException e) {
                log.error("Error reading stderr", e);
            }
        });

        stdoutThread.start();
        stderrThread.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            stdoutThread.interrupt();
            stderrThread.interrupt();
            throw new RuntimeException("Inference timed out after " + timeoutSeconds + " seconds");
        }

        // Wait for reader threads to finish (with timeout)
        stdoutThread.join(5000);
        stderrThread.join(5000);

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Inference failed with exit code " + exitCode + ". Stderr:\n" + stderr);
        }

        String output = stdout.toString().trim();
        if (output.isEmpty()) {
            throw new RuntimeException("Inference script produced no stdout output. Stderr:\n" + stderr);
        }

        return output;
    }

    private Path getOrDownloadModel(String minioPath) throws Exception {
        Path cachedPath = modelFileCache.get(minioPath);
        if (cachedPath != null && Files.exists(cachedPath)) {
            Long cacheTime = modelCacheTime.get(minioPath);
            if (cacheTime != null && System.currentTimeMillis() - cacheTime < MODEL_CACHE_TTL_MS) {
                log.debug("Using cached model: {}", minioPath);
                return cachedPath;
            }
        }

        String originalFilename = Paths.get(minioPath).getFileName().toString();
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        Path tempFile = tempDir.resolve(originalFilename);

        log.info("Downloading model from storage: {}", minioPath);
        try (var in = storageService.download(minioPath);
                var out = Files.newOutputStream(tempFile)) {
            in.transferTo(out);
        }

        modelFileCache.put(minioPath, tempFile);
        modelCacheTime.put(minioPath, System.currentTimeMillis());

        log.info("Model downloaded and cached as: {}", tempFile);
        return tempFile;
    }

    public CompletableFuture<Map<String, Object>> predictAsync(UUID modelUuid, Long accountId, UUID transactionUuid) {
        return CompletableFuture.supplyAsync(() -> predict(modelUuid, accountId, transactionUuid), inferenceExecutor);
    }

    private void ensureContainerTempDirs() throws Exception {
        execDockerCommand("mkdir", "-p", CONTAINER_TMP_MODELS_DIR, CONTAINER_TMP_DATA_DIR);
    }

    private void execDockerCommand(String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "docker";
        command[1] = "exec";
        command[2] = containerName;
        System.arraycopy(args, 0, command, 3, args.length);

        Process process = new ProcessBuilder(command).start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Docker command timed out");
        }

        if (process.exitValue() != 0) {
            throw new RuntimeException("Docker command failed: " + String.join(" ", command));
        }
    }

    private Map<String, Object> mockPredict(UUID modelUuid, Long accountId, UUID transactionUuid) {
        double fraudScore = Math.random() * 0.5 + 0.2;
        String riskLevel = fraudScore >= 0.8 ? "CRITICAL"
                : fraudScore >= 0.6 ? "HIGH" : fraudScore >= 0.4 ? "MEDIUM" : "LOW";

        Map<String, Object> result = new HashMap<>();
        result.put("fraud_score", fraudScore);
        result.put("risk_level", riskLevel);
        result.put("alert_type", riskLevel + "_RISK");
        result.put("supervised_score", fraudScore);
        result.put("anomaly_score", fraudScore * 0.8);
        result.put("updated_behavior", Map.of(
                "client_AVG_TX_Amount", 1000.0,
                "client_AVG_TX_PerDay", 100.0,
                "client_total_transactions", 1,
                "client_risk_score", fraudScore,
                "client_time_since_last_tx", 0L));
        result.put("is_first_transaction", true);
        return result;
    }

    public Map<String, Object> transactionToMap(Transaction tx) {
        Map<String, Object> map = new HashMap<>();
        map.put("TX_Amount", tx.getAmount() != null ? tx.getAmount().doubleValue() : 0.0);
        map.put("TX_Time", tx.getTransactionDate() != null ? tx.getTransactionDate().toString() : null);
        map.put("Channel", tx.getChannel() != null ? tx.getChannel().name() : null);
        map.put("category", tx.getMerchantCategory());
        map.put("postal_code_client", tx.getPostalCodeClient());
        map.put("postal_code_merchant", tx.getPostalCodeMerchant());
        map.put("device_id", tx.getDeviceId());
        map.put("ip_address", tx.getIpAddress());
        map.put("currency", tx.getCurrency());
        map.put("account_id", tx.getAccount() != null ? tx.getAccount().getAccountNumber() : null);
        return map;
    }

    public Map<String, Object> behaviorToMap(ClientBehavior behavior) {
        Map<String, Object> map = new HashMap<>();
        map.put("client_AVG_TX_Amount",
                behavior.getClientAvgTxAmount() != null ? behavior.getClientAvgTxAmount().doubleValue() : 0.0);
        map.put("client_AVG_TX_PerDay",
                behavior.getClientAvgTxPerDay() != null ? behavior.getClientAvgTxPerDay().doubleValue() : 0.0);
        map.put("client_amount_deviation",
                behavior.getClientAmountDeviation() != null ? behavior.getClientAmountDeviation().doubleValue() : 0.0);
        map.put("client_abs_amount_deviation",
                behavior.getClientAbsAmountDeviation() != null ? behavior.getClientAbsAmountDeviation().doubleValue()
                        : 0.0);
        map.put("client_amount_max_ratio",
                behavior.getClientAmountMaxRatio() != null ? behavior.getClientAmountMaxRatio().doubleValue() : 1.0);
        map.put("client_amount_z_score",
                behavior.getClientAmountZScore() != null ? behavior.getClientAmountZScore().doubleValue() : 0.0);
        map.put("client_is_new_max", Boolean.TRUE.equals(behavior.getClientIsNewMax()) ? 1 : 0);
        map.put("client_is_new_min", Boolean.TRUE.equals(behavior.getClientIsNewMin()) ? 1 : 0);
        map.put("client_total_transactions",
                behavior.getClientTotalTransactions() != null ? behavior.getClientTotalTransactions() : 0);
        map.put("client_time_since_last_tx",
                behavior.getClientTimeSinceLastTx() != null ? behavior.getClientTimeSinceLastTx() : 0L);
        map.put("client_hours_since_last_tx",
                behavior.getClientHoursSinceLastTx() != null ? behavior.getClientHoursSinceLastTx().doubleValue()
                        : 0.0);
        map.put("client_total_days_since_first",
                behavior.getClientTotalDaysSinceFirst() != null ? behavior.getClientTotalDaysSinceFirst() : 0);
        map.put("client_usual_region",
                behavior.getClientUsualRegion() != null ? behavior.getClientUsualRegion() : "Unknown");
        map.put("client_is_unusual_region", Boolean.TRUE.equals(behavior.getClientIsUnusualRegion()) ? 1 : 0);
        map.put("client_region_freq",
                behavior.getClientRegionFreq() != null ? behavior.getClientRegionFreq().doubleValue() : 0.0);
        map.put("client_usual_channel",
                behavior.getClientUsualChannel() != null ? behavior.getClientUsualChannel() : "Unknown");
        map.put("client_is_unusual_channel", Boolean.TRUE.equals(behavior.getClientIsUnusualChannel()) ? 1 : 0);
        map.put("client_channel_freq",
                behavior.getClientChannelFreq() != null ? behavior.getClientChannelFreq().doubleValue() : 0.0);
        map.put("client_usual_category",
                behavior.getClientUsualCategory() != null ? behavior.getClientUsualCategory() : "Unknown");
        map.put("client_is_unusual_category", Boolean.TRUE.equals(behavior.getClientIsUnusualCategory()) ? 1 : 0);
        map.put("client_category_freq",
                behavior.getClientCategoryFreq() != null ? behavior.getClientCategoryFreq().doubleValue() : 0.0);
        map.put("client_risk_score",
                behavior.getClientRiskScore() != null ? behavior.getClientRiskScore().doubleValue() : 0.0);
        return map;
    }

    @SuppressWarnings("unchecked")
    private String getModelPath(ModelML model) {
        if (model.getHyperparameters() != null &&
                model.getHyperparameters().containsKey("_trainingResults")) {
            Map<String, Object> trainingResults = (Map<String, Object>) model.getHyperparameters()
                    .get("_trainingResults");
            if (trainingResults.containsKey("modelPath")) {
                return (String) trainingResults.get("modelPath");
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String getPipelinePath(ModelML model) {
        if (model.getHyperparameters() != null &&
                model.getHyperparameters().containsKey("_trainingResults")) {
            Map<String, Object> trainingResults = (Map<String, Object>) model.getHyperparameters()
                    .get("_trainingResults");
            if (trainingResults.containsKey("pipelinePath")) {
                return (String) trainingResults.get("pipelinePath");
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public double getModelThreshold(ModelML model) {
        if (model.getHyperparameters() != null &&
                model.getHyperparameters().containsKey("_trainingResults")) {
            Map<String, Object> trainingResults = (Map<String, Object>) model.getHyperparameters()
                    .get("_trainingResults");

            if (trainingResults.containsKey("thresholds")) {
                Map<String, Object> thresholds = (Map<String, Object>) trainingResults.get("thresholds");
                Object finalObj = thresholds.get("final");
                if (finalObj instanceof Number) {
                    return ((Number) finalObj).doubleValue();
                }
            }
        }
        // Fallback to 0.75 if not specified (legacy or default)
        return 0.75;
    }

    private void execDockerCopy(Path source, String destination) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "cp",
                source.toAbsolutePath().toString(),
                containerName + ":" + destination);

        Process process = pb.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Docker copy timed out: " + source);
        }

        if (process.exitValue() != 0) {
            throw new RuntimeException("Docker copy failed: " + source + " -> " + destination);
        }
    }

    private void cleanupTempFiles(Path... files) {
        for (Path file : files) {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception e) {
                    log.warn("Failed to delete temp file: {}", file);
                }
            }
        }
    }

    private void cleanupContainerFiles(String... paths) {
        for (String path : paths) {
            if (path != null) {
                try {
                    execDockerCommand("rm", "-f", path);
                } catch (Exception e) {
                    log.warn("Failed to delete container file: {}", path);
                }
            }
        }
    }
}
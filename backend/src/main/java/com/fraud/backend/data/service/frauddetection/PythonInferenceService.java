package com.fraud.backend.data.service.frauddetection;

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
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PythonInferenceService {

    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final ModelMLRepository modelMLRepository;
    private final TransactionRepository transactionRepository;
    private final ClientBehaviorRepository clientBehaviorRepository;

    // Set to true for testing without Docker
    private static final boolean USE_MOCK = false;

    /**
     * Predict fraud score for a transaction and update client behavior
     */
    public Map<String, Object> predict(UUID modelUuid, Long accountId, UUID transactionUuid) {
        if (USE_MOCK) {
            return mockPredict(modelUuid, accountId, transactionUuid);
        }

        try {
            log.info("Predicting for model: {}, account: {}, transaction: {}", 
                     modelUuid, accountId, transactionUuid);
            
            // Get the model
            ModelML model = modelMLRepository.findByModelUuid(modelUuid)
                    .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelUuid));
            
            // Get the transaction
            Transaction transaction = transactionRepository.findByTransactionUuid(transactionUuid)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionUuid));
            
            // Get EXISTING client behavior from database
            ClientBehavior existingBehavior = clientBehaviorRepository
                    .findByAccountId(accountId)
                    .orElse(null);
            
            if (existingBehavior == null) {
                log.info("First transaction for account {}, using default behavior", accountId);
            } else {
                log.info("Found existing behavior for account {}, risk score: {}", 
                         accountId, existingBehavior.getClientRiskScore());
            }
            
            // Prepare input for Python script
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("model_uuid", modelUuid.toString());
            inputData.put("transaction", transactionToMap(transaction));
            inputData.put("has_existing_behavior", existingBehavior != null);
            if (existingBehavior != null) {
                inputData.put("existing_behavior", behaviorToMap(existingBehavior));
            }
            
            // Get model file path from storage
            String modelPath = getModelPath(model);
            String preprocessorPath = getPreprocessorPath(model);
            
            log.info("Model path: {}", modelPath);
            log.info("Preprocessor path: {}", preprocessorPath);
            
            if (modelPath == null || modelPath.isBlank()) {
                throw new RuntimeException("Model path is null or empty for model: " + modelUuid);
            }
            
            // Download model and preprocessor from MinIO
            Path tempModel = downloadModel(modelPath);
            Path tempPreprocessor = preprocessorPath != null ? downloadModel(preprocessorPath) : null;
            
            log.info("Model downloaded to: {}", tempModel.toAbsolutePath());
            if (tempPreprocessor != null) {
                log.info("Preprocessor downloaded to: {}", tempPreprocessor.toAbsolutePath());
            }
            
            // Create temp file for input data
            Path tempInput = Files.createTempFile("inference_input_", ".json");
            objectMapper.writeValue(tempInput.toFile(), inputData);
            log.info("Input file created: {}", tempInput.toAbsolutePath());
            
            // Copy files to Docker container (using same method as ModelTrainingService)
            String containerModelPath = "/app/models/" + tempModel.getFileName().toString();
            String containerInputPath = "/app/data/" + tempInput.getFileName().toString();
            
            log.info("Copying model to container: {} -> {}", tempModel, containerModelPath);
            execDockerCopy(tempModel, containerModelPath);
            
            if (tempPreprocessor != null) {
                String containerPreprocessorPath = "/app/models/" + tempPreprocessor.getFileName().toString();
                log.info("Copying preprocessor to container: {} -> {}", tempPreprocessor, containerPreprocessorPath);
                execDockerCopy(tempPreprocessor, containerPreprocessorPath);
            }
            
            log.info("Copying input to container: {} -> {}", tempInput, containerInputPath);
            execDockerCopy(tempInput, containerInputPath);
            
            // Build command to run Python inference
            List<String> command = new ArrayList<>();
            command.addAll(List.of("docker", "exec", "fraud-ml-pipeline", 
                    "python", "/app/scripts/inference_service.py"));
            command.add("--model-path"); command.add(containerModelPath);
            if (tempPreprocessor != null) {
                command.add("--preprocessor-path"); 
                command.add("/app/models/" + tempPreprocessor.getFileName().toString());
            }
            command.add("--input"); command.add(containerInputPath);
            
            log.info("Running command: {}", String.join(" ", command));
            
            // Run inference
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();
            
            // Read output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                    log.debug("Python stdout: {}", line);
                }
            }
            
            // Read stderr for debugging
            StringBuilder errorOutput = new StringBuilder();
            try (BufferedReader errorReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorOutput.append(line).append("\n");
                    log.debug("Python stderr: {}", line);
                }
            }
            
            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Inference timed out after 30 seconds");
            }
            
            if (process.exitValue() != 0) {
                log.error("Python script exited with code: {}", process.exitValue());
                log.error("Python stderr: {}", errorOutput.toString());
                throw new RuntimeException("Inference failed with exit code: " + process.exitValue() + 
                        ". Error: " + errorOutput.toString());
            }
            
            String outputStr = output.toString().trim();
            log.info("Python output: {}", outputStr);
            
            // Parse result
            Map<String, Object> result = objectMapper.readValue(outputStr, Map.class);
            
            // Clean up temp files
            try {
                Files.deleteIfExists(tempInput);
                Files.deleteIfExists(tempModel);
                if (tempPreprocessor != null) Files.deleteIfExists(tempPreprocessor);
            } catch (Exception e) {
                log.warn("Failed to delete temp files: {}", e.getMessage());
            }
            
            return result;
            
        } catch (Exception e) {
            log.error("Prediction failed: {}", e.getMessage(), e);
            throw new RuntimeException("Prediction failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Mock prediction for testing without Docker
     */
    private Map<String, Object> mockPredict(UUID modelUuid, Long accountId, UUID transactionUuid) {
        log.info("MOCK: Predicting for model: {}, account: {}, transaction: {}", 
                 modelUuid, accountId, transactionUuid);
        
        double fraudScore = Math.random() * 0.5 + 0.2;
        
        String riskLevel;
        String alertType;
        if (fraudScore >= 0.8) {
            riskLevel = "CRITICAL";
            alertType = "HIGH_RISK";
        } else if (fraudScore >= 0.6) {
            riskLevel = "HIGH";
            alertType = "HIGH_RISK";
        } else if (fraudScore >= 0.4) {
            riskLevel = "MEDIUM";
            alertType = "MEDIUM_RISK";
        } else if (fraudScore >= 0.2) {
            riskLevel = "LOW";
            alertType = "LOW_RISK";
        } else {
            riskLevel = "VERY_LOW";
            alertType = "LOW_RISK";
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("fraud_score", fraudScore);
        result.put("risk_level", riskLevel);
        result.put("alert_type", alertType);
        result.put("supervised_score", fraudScore);
        result.put("anomaly_score", fraudScore * 0.8);
        result.put("updated_behavior", Map.of(
            "client_AVG_TX_Amount", 1000.0,
            "client_AVG_TX_PerDay", 100.0,
            "client_total_transactions", 1,
            "client_risk_score", fraudScore,
            "client_time_since_last_tx", 0L
        ));
        
        return result;
    }
    
    /**
     * Save or update client behavior in database
     */
    private void saveOrUpdateClientBehavior(Long accountId, Transaction transaction, Map<String, Object> updatedBehavior) {
        try {
            ClientBehavior behavior = clientBehaviorRepository.findByAccountId(accountId)
                    .orElse(ClientBehavior.builder()
                            .account(transaction.getAccount())
                            .build());
            
            if (updatedBehavior.containsKey("client_AVG_TX_Amount")) {
                behavior.setClientAvgTxAmount(java.math.BigDecimal.valueOf(
                        ((Number) updatedBehavior.get("client_AVG_TX_Amount")).doubleValue()));
            }
            if (updatedBehavior.containsKey("client_AVG_TX_PerDay")) {
                behavior.setClientAvgTxPerDay(java.math.BigDecimal.valueOf(
                        ((Number) updatedBehavior.get("client_AVG_TX_PerDay")).doubleValue()));
            }
            if (updatedBehavior.containsKey("client_total_transactions")) {
                behavior.setClientTotalTransactions(
                        ((Number) updatedBehavior.get("client_total_transactions")).intValue());
            }
            if (updatedBehavior.containsKey("client_time_since_last_tx")) {
                behavior.setClientTimeSinceLastTx(
                        ((Number) updatedBehavior.get("client_time_since_last_tx")).longValue());
            }
            if (updatedBehavior.containsKey("client_risk_score")) {
                behavior.setClientRiskScore(java.math.BigDecimal.valueOf(
                        ((Number) updatedBehavior.get("client_risk_score")).doubleValue()));
            }
            
            behavior.setLastCalculatedAt(LocalDateTime.now());
            clientBehaviorRepository.save(behavior);
            log.info("Client behavior saved for account {}", accountId);
            
        } catch (Exception e) {
            log.error("Failed to save client behavior: {}", e.getMessage(), e);
        }
    }
    
    private Map<String, Object> transactionToMap(Transaction tx) {
        Map<String, Object> map = new HashMap<>();
        map.put("TX_Amount", tx.getAmount().doubleValue());
        map.put("TX_Time", tx.getTransactionDate().toString());
        map.put("Channel", tx.getChannel() != null ? tx.getChannel().name() : null);
        map.put("category", tx.getMerchantCategory());
        map.put("Customer_Postal_Code", tx.getPostalCodeClient());
        map.put("Merchant_Postal_Code", tx.getPostalCodeMerchant());
        map.put("Device_Id", tx.getDeviceId());
        map.put("ip_adress", tx.getIpAddress());
        map.put("currency", tx.getCurrency());
        map.put("Account_Id", tx.getAccount().getAccountNumber());
        return map;
    }
    
    private Map<String, Object> behaviorToMap(ClientBehavior behavior) {
        Map<String, Object> map = new HashMap<>();
        map.put("client_AVG_TX_Amount", behavior.getClientAvgTxAmount().doubleValue());
        map.put("client_AVG_TX_PerDay", behavior.getClientAvgTxPerDay().doubleValue());
        map.put("client_total_transactions", behavior.getClientTotalTransactions());
        map.put("client_time_since_last_tx", behavior.getClientTimeSinceLastTx());
        map.put("client_risk_score", behavior.getClientRiskScore().doubleValue());
        return map;
    }
    
    private String getModelPath(ModelML model) {
        if (model.getHyperparameters() != null && 
            model.getHyperparameters().containsKey("_trainingResults")) {
            Map<String, Object> trainingResults = (Map<String, Object>) 
                    model.getHyperparameters().get("_trainingResults");
            if (trainingResults.containsKey("modelPath")) {
                return (String) trainingResults.get("modelPath");
            }
        }
        return null;
    }
    
    private String getPreprocessorPath(ModelML model) {
        if (model.getHyperparameters() != null && 
            model.getHyperparameters().containsKey("_trainingResults")) {
            Map<String, Object> trainingResults = (Map<String, Object>) 
                    model.getHyperparameters().get("_trainingResults");
            if (trainingResults.containsKey("pipelinePath")) {
                return (String) trainingResults.get("pipelinePath");
            }
        }
        return null;
    }
    
    private Path downloadModel(String minioPath) throws Exception {
        log.info("Downloading model from MinIO: {}", minioPath);
        Path tempFile = Files.createTempFile("model_", ".joblib");
        try (var in = storageService.download(minioPath);
             var out = Files.newOutputStream(tempFile)) {
            in.transferTo(out);
        }
        log.info("Model downloaded to: {}, size: {} bytes", tempFile.toAbsolutePath(), Files.size(tempFile));
        
        if (!Files.exists(tempFile)) {
            throw new RuntimeException("Downloaded file does not exist: " + tempFile);
        }
        return tempFile;
    }
    
    private void execDockerCopy(Path source, String destination) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "cp", source.toAbsolutePath().toString(),
                "fraud-ml-pipeline:" + destination);
        
        log.info("Executing command: docker cp {} fraud-ml-pipeline:{}", source.toAbsolutePath(), destination);
        
        Process process = pb.start();
        
        // Read stderr to see what's happening
        StringBuilder errorOutput = new StringBuilder();
        try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = errorReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
                log.error("Docker copy stderr: {}", line);
            }
        }
        
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.error("Docker copy failed with exit code: {}", exitCode);
            log.error("Error output: {}", errorOutput.toString());
            
            // Try to create the directory if it doesn't exist
            String[] mkdirCmd = {"docker", "exec", "fraud-ml-pipeline", "mkdir", "-p", "/app/models", "/app/data"};
            ProcessBuilder mkdirPb = new ProcessBuilder(mkdirCmd);
            Process mkdirProcess = mkdirPb.start();
            mkdirProcess.waitFor();
            log.info("Created directories in container");
            
            // Retry the copy
            Process retryProcess = pb.start();
            int retryExitCode = retryProcess.waitFor();
            if (retryExitCode != 0) {
                throw new RuntimeException("Docker copy failed after creating directories: " + errorOutput.toString());
            }
        }
        
        log.info("Docker copy successful");
    }
}
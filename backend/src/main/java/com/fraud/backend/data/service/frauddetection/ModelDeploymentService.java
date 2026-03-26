package com.fraud.backend.data.service.frauddetection;

import com.fraud.backend.api.dto.frauddetection.model.response.DeploymentResponse;
import com.fraud.backend.api.dto.frauddetection.model.response.ModelSummaryResponse;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.corebanking.*;
import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import com.fraud.backend.data.entity.frauddetection.ModelDeploymentHistory;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.repository.corebanking.AccountRepository;
import com.fraud.backend.data.repository.corebanking.ClientBehaviorRepository;
import com.fraud.backend.data.repository.corebanking.TransactionRepository;
import com.fraud.backend.data.repository.frauddetection.FraudAlertRepository;
import com.fraud.backend.data.repository.frauddetection.ModelDeploymentHistoryRepository;
import com.fraud.backend.data.repository.frauddetection.ModelMLRepository;
import com.fraud.backend.data.service.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelDeploymentService {

    private final ModelMLRepository modelMLRepository;
    private final ModelDeploymentHistoryRepository deploymentHistoryRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final TransactionRepository transactionRepository;
    private final ClientBehaviorRepository clientBehaviorRepository;
    private final AccountRepository accountRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final PythonInferenceService pythonInferenceService;

    // Track active scoring jobs
    private final Map<UUID, ScoringJobStatus> activeScoringJobs = new ConcurrentHashMap<>();
    private final ExecutorService scoringExecutor = Executors.newFixedThreadPool(10);

    // Track if backfill is running
    private volatile boolean isBackfillRunning = false;
    private volatile UUID currentBackfillJobId = null;

    /**
     * Get all validated models (available for deployment) - returns DTOs
     */
    public List<ModelSummaryResponse> getValidatedModels() {
        return modelMLRepository.findByStatus("Validated").stream()
                .map(this::toModelSummaryResponse)
                .toList();
    }

    /**
     * Convert ModelML entity to ModelSummaryResponse DTO
     */
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

    /**
     * Get deployment history as DTOs
     */
    public List<DeploymentResponse> getDeploymentHistory() {
        return deploymentHistoryRepository.findAllByOrderByDeploymentDateDesc().stream()
                .map(this::toDeploymentResponse)
                .toList();
    }

    /**
     * Get currently deployed model entity (internal use only)
     */
    private Optional<ModelML> getDeployedModelEntity() {
        return deploymentHistoryRepository
                .findFirstByStatusOrderByDeploymentDateDesc("ACTIVE")
                .map(ModelDeploymentHistory::getModel);
    }

    /**
     * Get currently deployed model summary DTO
     */
    public Optional<ModelSummaryResponse> getDeployedModelSummary() {
        return deploymentHistoryRepository
                .findFirstByStatusOrderByDeploymentDateDesc("ACTIVE")
                .map(ModelDeploymentHistory::getModel)
                .map(this::toModelSummaryResponse);
    }

    /**
     * Get currently deployed model response (for backward compatibility)
     */
    public Optional<DeploymentResponse> getDeployedModelResponse() {
        return deploymentHistoryRepository
                .findFirstByStatusOrderByDeploymentDateDesc("ACTIVE")
                .map(this::toDeploymentResponse);
    }

    /**
     * Deploy a validated model and automatically start backfill
     */
    @Transactional
    public DeploymentResponse deployModel(UUID modelUuid, String notes, AppUser deployedBy) {
        // 1. Get the model to deploy
        ModelML model = modelMLRepository.findByModelUuid(modelUuid)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelUuid));

        // 2. Validate model status
        if (!"Validated".equals(model.getStatus())) {
            throw new IllegalStateException(
                    "Only validated models can be deployed. Current status: " + model.getStatus());
        }

        // 3. Undeploy current model if exists
        Optional<ModelDeploymentHistory> currentDeployment = deploymentHistoryRepository
                .findFirstByStatusOrderByDeploymentDateDesc("ACTIVE");

        if (currentDeployment.isPresent()) {
            ModelML currentModel = currentDeployment.get().getModel();
            log.info("Undeploying current model: {}", currentModel.getModelName());
            undeployModel(currentModel.getModelUuid());
        }

        // 4. Create new deployment record
        ModelDeploymentHistory deployment = ModelDeploymentHistory.builder()
                .model(model)
                .deployedBy(deployedBy)
                .deploymentDate(LocalDateTime.now())
                .status("ACTIVE")
                .performanceAtDeployment(Map.of(
                        "auc", model.getAuc() != null ? model.getAuc() : 0,
                        "precision", model.getPrecision() != null ? model.getPrecision() : 0,
                        "recall", model.getRecall() != null ? model.getRecall() : 0,
                        "f1Score", model.getF1Score() != null ? model.getF1Score() : 0,
                        "modelName", model.getModelName(),
                        "version", model.getVersion(),
                        "deployedAt", LocalDateTime.now().toString()))
                .notes(notes)
                .build();

        // 5. Update model status
        model.setStatus("Deployed");
        model.setUpdatedAt(LocalDateTime.now());
        modelMLRepository.save(model);

        // 6. Save deployment history
        ModelDeploymentHistory savedDeployment = deploymentHistoryRepository.save(deployment);

        log.info("Model {} (v{}) deployed by {}",
                model.getModelName(), model.getVersion(), deployedBy.getEmail());

        // 7. AUTO-START BACKFILL - Process all existing transactions with the new model
        log.info("Auto-starting backfill for model: {}", model.getModelName());
        processAllExistingTransactions();

        return toDeploymentResponse(savedDeployment);
    }

    /**
     * Undeploy current model
     */
    @Transactional
    public void undeployModel(UUID modelUuid) {
        Optional<ModelDeploymentHistory> deployment = deploymentHistoryRepository
                .findFirstByModel_ModelUuidAndStatusOrderByDeploymentDateDesc(modelUuid, "ACTIVE");

        if (deployment.isEmpty()) {
            log.warn("No active deployment found for model {}", modelUuid);
            return;
        }

        ModelDeploymentHistory activeDeployment = deployment.get();
        activeDeployment.setStatus("INACTIVE");
        activeDeployment.setUndeploymentDate(LocalDateTime.now());
        deploymentHistoryRepository.save(activeDeployment);

        ModelML model = activeDeployment.getModel();
        model.setStatus("Validated");
        model.setUpdatedAt(LocalDateTime.now());
        modelMLRepository.save(model);

        log.info("Model {} undeployed", model.getModelName());
    }

    /**
     * Convert ModelDeploymentHistory to DeploymentResponse DTO
     */
    private DeploymentResponse toDeploymentResponse(ModelDeploymentHistory history) {
        if (history == null)
            return null;

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
                .status(history.getStatus())
                .performanceAtDeployment(history.getPerformanceAtDeployment())
                .notes(history.getNotes())
                .build();
    }

    /**
     * Map fraud score to alert type based on database constraint
     * Allowed values: 'AMOUNT_ANOMALY', 'LOCATION_ANOMALY', 'VELOCITY', 'PATTERN', 'BEHAVIORAL'
     */
    private String mapToAlertType(double fraudScore) {
        if (fraudScore >= 0.8) {
            return "AMOUNT_ANOMALY";
        } else if (fraudScore >= 0.6) {
            return "PATTERN";
        } else if (fraudScore >= 0.4) {
            return "VELOCITY";
        } else if (fraudScore >= 0.2) {
            return "LOCATION_ANOMALY";
        } else {
            return "BEHAVIORAL";
        }
    }

    /**
     * Score a single transaction synchronously
     */
    @Transactional
    public FraudAlert scoreTransaction(UUID transactionUuid, UUID modelUuid) {
        Transaction transaction = transactionRepository.findByTransactionUuid(transactionUuid)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionUuid));

        ModelML model;
        if (modelUuid != null) {
            model = modelMLRepository.findByModelUuid(modelUuid)
                    .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelUuid));
        } else {
            model = getDeployedModelEntity()
                    .orElseThrow(() -> new IllegalStateException("No model deployed"));
        }

        return scoreTransactionWithModel(transaction, model);
    }

    /**
     * Score a transaction using a specific model
     */
    private FraudAlert scoreTransactionWithModel(Transaction transaction, ModelML model) {
        try {
            log.info("=== STARTING SCORING for Transaction ID: {}, UUID: {} ===",
                    transaction.getId(), transaction.getTransactionUuid());

            // Get client behavior
            ClientBehavior clientBehavior = clientBehaviorRepository.findByAccountId(transaction.getAccount().getId())
                    .orElse(null);

            log.info("Client behavior found: {}",
                    clientBehavior != null ? "YES (risk score: " + clientBehavior.getClientRiskScore() + ")"
                            : "NO (first transaction)");

            // Prepare features for scoring
            Map<String, Object> features = prepareFeatures(transaction, clientBehavior);
            log.info("Features prepared with {} entries", features.size());

            // Call Python inference service
            log.info("Calling Python inference service...");
            Map<String, Object> prediction = pythonInferenceService.predict(
                    model.getModelUuid(),
                    transaction.getAccount().getId(),
                    transaction.getTransactionUuid());

            Double fraudScore = (Double) prediction.get("fraud_score");
            String riskLevel = (String) prediction.get("risk_level");

            log.info("Python prediction result - fraud_score: {}, risk_level: {}", fraudScore, riskLevel);

            // Map fraud score to allowed alert type
            String alertType = mapToAlertType(fraudScore);
            log.info("Mapped alert_type: {}", alertType);

            // Create fraud alert
            FraudAlert alert = FraudAlert.builder()
                    .alertUuid(UUID.randomUUID())
                    .transaction(transaction)
                    .user(null)
                    .fraudScore(fraudScore)
                    .supervisedScore((Double) prediction.getOrDefault("supervised_score", fraudScore))
                    .anomalyScore((Double) prediction.getOrDefault("anomaly_score", 0.0))
                    .modelUsed(model)
                    .riskLevel(riskLevel)
                    .alertType(alertType)
                    .status("PENDING")
                    .featuresSnapshot(features)
                    .createdAt(LocalDateTime.now())
                    .build();

            // Update transaction with fraud score and is_fraud flag
            BigDecimal fraudScoreDecimal = BigDecimal.valueOf(fraudScore);
            transaction.setFraudScore(fraudScoreDecimal);
            transaction.setIsFraud(fraudScore > 0.75);
            transaction.setModel(model);

            log.info("Before saving transaction - ID: {}, Fraud Score: {}, Is Fraud: {}",
                    transaction.getId(), fraudScoreDecimal, transaction.getIsFraud());

            // Save transaction
            Transaction savedTransaction = transactionRepository.save(transaction);

            log.info("After saving transaction - ID: {}, Fraud Score: {}, Is Fraud: {}",
                    savedTransaction.getId(), savedTransaction.getFraudScore(), savedTransaction.getIsFraud());

            // Update client behavior after transaction (ONLY ONCE)
            if (clientBehavior != null) {
                log.info("Updating existing client behavior for account: {}", transaction.getAccount().getId());
                updateClientBehavior(clientBehavior, transaction);
                clientBehaviorRepository.save(clientBehavior);
            } else {
                log.info("Creating new client behavior for account: {}", transaction.getAccount().getId());
                ClientBehavior newBehavior = createClientBehavior(transaction.getAccount(), transaction, fraudScore);
                clientBehaviorRepository.save(newBehavior);
            }

            // Save fraud alert
            FraudAlert savedAlert = fraudAlertRepository.save(alert);

            log.info("Transaction {} scored successfully: score={}, risk={}, isFraud={}, alertType={}",
                    transaction.getTransactionUuid(), fraudScore, riskLevel, transaction.getIsFraud(), alertType);

            log.info("=== SCORING COMPLETED SUCCESSFULLY ===");

            return savedAlert;

        } catch (Exception e) {
            log.error("Error scoring transaction {}: {}", transaction.getTransactionUuid(), e.getMessage(), e);
            throw new RuntimeException("Scoring failed: " + e.getMessage());
        }
    }

    /**
     * Create new client behavior for a new account
     */
    private ClientBehavior createClientBehavior(Account account, Transaction transaction, Double fraudScore) {
        return ClientBehavior.builder()
                .account(account)
                .clientAvgTxAmount(transaction.getAmount())
                .clientAvgTxPerDay(transaction.getAmount())
                .clientTotalTransactions(1)
                .clientTotalDaysSinceFirst(0)
                .clientAmountDeviation(BigDecimal.ZERO)
                .clientAbsAmountDeviation(BigDecimal.ZERO)
                .clientAmountZScore(BigDecimal.ZERO)
                .clientAmountMaxRatio(BigDecimal.ONE)
                .clientIsNewMax(true)
                .clientIsNewMin(true)
                .clientTimeSinceLastTx(0L)
                .clientHoursSinceLastTx(BigDecimal.ZERO)
                .clientRiskScore(BigDecimal.valueOf(fraudScore))
                .clientUsualRegion("Unknown")
                .clientIsUnusualRegion(false)
                .clientRegionFreq(BigDecimal.ZERO)
                .clientUsualChannel("Unknown")
                .clientIsUnusualChannel(false)
                .clientChannelFreq(BigDecimal.ZERO)
                .clientUsualCategory("Unknown")
                .clientIsUnusualCategory(false)
                .clientCategoryFreq(BigDecimal.ZERO)
                .lastCalculatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Update existing client behavior
     */
    private void updateClientBehavior(ClientBehavior behavior, Transaction transaction) {
        int newTotalTransactions = behavior.getClientTotalTransactions() + 1;

        BigDecimal newAvg = behavior.getClientAvgTxAmount()
                .multiply(BigDecimal.valueOf(behavior.getClientTotalTransactions()))
                .add(transaction.getAmount())
                .divide(BigDecimal.valueOf(newTotalTransactions), 2, RoundingMode.HALF_UP);

        behavior.setClientTotalTransactions(newTotalTransactions);
        behavior.setClientAvgTxAmount(newAvg);
        behavior.setClientTimeSinceLastTx(System.currentTimeMillis() / 1000);
        behavior.setLastCalculatedAt(LocalDateTime.now());
    }

    /**
     * Score multiple transactions asynchronously
     */
    @Async
    public CompletableFuture<ScoringJobStatus> scoreTransactionsAsync(List<UUID> transactionUuids, UUID modelUuid,
            UUID jobId) {
        ScoringJobStatus jobStatus = new ScoringJobStatus();
        jobStatus.setJobId(jobId);
        jobStatus.setTotalTransactions(transactionUuids.size());
        jobStatus.setStatus("RUNNING");
        jobStatus.setStartTime(LocalDateTime.now());
        activeScoringJobs.put(jobId, jobStatus);

        CompletableFuture.runAsync(() -> {
            try {
                ModelML model;
                if (modelUuid != null) {
                    model = modelMLRepository.findByModelUuid(modelUuid)
                            .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelUuid));
                } else {
                    model = getDeployedModelEntity()
                            .orElseThrow(() -> new IllegalStateException("No model deployed"));
                }

                for (int i = 0; i < transactionUuids.size(); i++) {
                    try {
                        UUID txUuid = transactionUuids.get(i);
                        Transaction transaction = transactionRepository.findByTransactionUuid(txUuid)
                                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + txUuid));

                        FraudAlert alert = scoreTransactionWithModel(transaction, model);

                        jobStatus.setProcessedCount(i + 1);
                        jobStatus.addResult(alert);

                    } catch (Exception e) {
                        log.error("Failed to score transaction {}: {}", transactionUuids.get(i), e.getMessage());
                        jobStatus.addError(transactionUuids.get(i), e.getMessage());
                    }

                    jobStatus.setProgress((double) (i + 1) / transactionUuids.size() * 100);
                }

                jobStatus.setStatus("COMPLETED");
                jobStatus.setEndTime(LocalDateTime.now());

            } catch (Exception e) {
                log.error("Scoring job {} failed: {}", jobId, e.getMessage());
                jobStatus.setStatus("FAILED");
                jobStatus.setErrorMessage(e.getMessage());
                jobStatus.setEndTime(LocalDateTime.now());
            } finally {
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        activeScoringJobs.remove(jobId);
                    }
                }, 3600000);
            }
        }, scoringExecutor);

        return CompletableFuture.completedFuture(jobStatus);
    }

    /**
     * Get scoring job status
     */
    public ScoringJobStatus getScoringJobStatus(UUID jobId) {
        return activeScoringJobs.get(jobId);
    }

    /**
     * Process ALL existing transactions with the deployed model
     */
    @Async
    public void processAllExistingTransactions() {
        if (isBackfillRunning) {
            log.warn("Backfill is already running. Current job ID: {}", currentBackfillJobId);
            return;
        }

        isBackfillRunning = true;
        currentBackfillJobId = UUID.randomUUID();

        log.info("Starting to process all existing transactions. Job ID: {}", currentBackfillJobId);

        CompletableFuture.runAsync(() -> {
            try {
                ModelML deployedModel = getDeployedModelEntity()
                        .orElseThrow(
                                () -> new IllegalStateException("No model deployed. Please deploy a model first."));

                log.info("Processing with model: {}", deployedModel.getModelName());

                List<Account> accounts = accountRepository.findAll();
                log.info("Found {} accounts to process", accounts.size());

                AtomicInteger processedAccounts = new AtomicInteger(0);
                AtomicInteger processedTransactions = new AtomicInteger(0);
                AtomicInteger skippedTransactions = new AtomicInteger(0);

                for (Account account : accounts) {
                    try {
                        List<Transaction> accountTransactions = transactionRepository
                                .findByAccountIdOrderByTransactionDateAsc(account.getId());

                        if (accountTransactions.isEmpty()) {
                            processedAccounts.incrementAndGet();
                            continue;
                        }

                        ClientBehavior behavior = clientBehaviorRepository
                                .findByAccountId(account.getId())
                                .orElse(null);

                        for (Transaction transaction : accountTransactions) {
                            // Skip if already scored
                            if (transaction.getFraudScore() != null) {
                                skippedTransactions.incrementAndGet();
                                processedTransactions.incrementAndGet();
                                continue;
                            }

                            try {
                                Map<String, Object> features = prepareFeatures(transaction, behavior);

                                Map<String, Object> prediction = pythonInferenceService.predict(
                                        deployedModel.getModelUuid(),
                                        account.getId(),
                                        transaction.getTransactionUuid());

                                Double fraudScore = (Double) prediction.get("fraud_score");
                                String riskLevel = (String) prediction.get("risk_level");
                                String alertType = mapToAlertType(fraudScore);

                                log.info("Backfill - Transaction ID: {}, Score: {}, AlertType: {}",
                                        transaction.getId(), fraudScore, alertType);

                                // Create fraud alert
                                FraudAlert alert = FraudAlert.builder()
                                        .alertUuid(UUID.randomUUID())
                                        .transaction(transaction)
                                        .user(null)
                                        .fraudScore(fraudScore)
                                        .supervisedScore(
                                                (Double) prediction.getOrDefault("supervised_score", fraudScore))
                                        .anomalyScore((Double) prediction.getOrDefault("anomaly_score", 0.0))
                                        .modelUsed(deployedModel)
                                        .riskLevel(riskLevel)
                                        .alertType(alertType)
                                        .status("PENDING")
                                        .featuresSnapshot(features)
                                        .createdAt(LocalDateTime.now())
                                        .build();

                                // Update transaction
                                transaction.setFraudScore(BigDecimal.valueOf(fraudScore));
                                transaction.setIsFraud(fraudScore > 0.75);
                                transaction.setModel(deployedModel);

                                log.info("Backfill - Before saving transaction - ID: {}, Fraud Score: {}",
                                        transaction.getId(), fraudScore);

                                transactionRepository.save(transaction);

                                log.info("Backfill - After saving transaction - ID: {}, Fraud Score: {}",
                                        transaction.getId(), transaction.getFraudScore());

                                // Update behavior
                                if (behavior != null) {
                                    updateClientBehavior(behavior, transaction);
                                    clientBehaviorRepository.save(behavior);
                                } else {
                                    ClientBehavior newBehavior = createClientBehavior(account, transaction, fraudScore);
                                    behavior = clientBehaviorRepository.save(newBehavior);
                                }

                                fraudAlertRepository.save(alert);

                                processedTransactions.incrementAndGet();

                                if (processedTransactions.get() % 1000 == 0) {
                                    log.info("Progress: {} transactions processed, {} accounts done",
                                            processedTransactions.get(), processedAccounts.get());
                                }

                            } catch (Exception e) {
                                log.error("Failed to score transaction {}: {}", transaction.getTransactionUuid(),
                                        e.getMessage());
                            }
                        }

                        processedAccounts.incrementAndGet();

                    } catch (Exception e) {
                        log.error("Failed to process account {}: {}", account.getAccountNumber(), e.getMessage());
                    }
                }

                log.info("=== BACKFILL COMPLETED ===");
                log.info("Total transactions processed: {}", processedTransactions.get());
                log.info("Transactions already scored (skipped): {}", skippedTransactions.get());
                log.info("Accounts processed: {}", processedAccounts.get());
                log.info("Model used: {}", deployedModel.getModelName());

            } catch (Exception e) {
                log.error("Backfill failed: {}", e.getMessage(), e);
            } finally {
                isBackfillRunning = false;
                currentBackfillJobId = null;
            }
        });
    }

    /**
     * Check if backfill is running
     */
    public boolean isBackfillRunning() {
        return isBackfillRunning;
    }

    /**
     * Get current backfill job ID
     */
    public UUID getCurrentBackfillJobId() {
        return currentBackfillJobId;
    }

    /**
     * Prepare features for model prediction
     */
    private Map<String, Object> prepareFeatures(Transaction transaction, ClientBehavior clientBehavior) {
        Map<String, Object> features = new HashMap<>();

        features.put("TX_Amount", transaction.getAmount().doubleValue());
        features.put("TX_Time", transaction.getTransactionDate().toString());
        features.put("Channel", transaction.getChannel() != null ? transaction.getChannel().name() : null);
        features.put("category", transaction.getMerchantCategory());
        features.put("Customer_Postal_Code", transaction.getPostalCodeClient());
        features.put("Merchant_Postal_Code", transaction.getPostalCodeMerchant());
        features.put("Device_Id", transaction.getDeviceId());
        features.put("ip_adress", transaction.getIpAddress());
        features.put("currency", transaction.getCurrency());
        features.put("Account_Id", transaction.getAccount().getAccountNumber());

        if (clientBehavior != null) {
            features.put("client_AVG_TX_Amount", clientBehavior.getClientAvgTxAmount().doubleValue());
            features.put("client_AVG_TX_PerDay", clientBehavior.getClientAvgTxPerDay().doubleValue());
            features.put("client_amount_deviation", clientBehavior.getClientAmountDeviation().doubleValue());
            features.put("client_abs_amount_deviation", clientBehavior.getClientAbsAmountDeviation().doubleValue());
            features.put("client_amount_max_ratio", clientBehavior.getClientAmountMaxRatio().doubleValue());
            features.put("client_amount_z_score", clientBehavior.getClientAmountZScore().doubleValue());
            features.put("client_is_new_max", clientBehavior.getClientIsNewMax());
            features.put("client_is_new_min", clientBehavior.getClientIsNewMin());
            features.put("client_total_transactions", clientBehavior.getClientTotalTransactions());
            features.put("client_time_since_last_tx", clientBehavior.getClientTimeSinceLastTx());
            features.put("client_hours_since_last_tx", clientBehavior.getClientHoursSinceLastTx().doubleValue());
            features.put("client_total_days_since_first", clientBehavior.getClientTotalDaysSinceFirst());
            features.put("client_usual_region", clientBehavior.getClientUsualRegion());
            features.put("client_is_unusual_region", clientBehavior.getClientIsUnusualRegion());
            features.put("client_region_freq", clientBehavior.getClientRegionFreq().doubleValue());
            features.put("client_usual_channel", clientBehavior.getClientUsualChannel());
            features.put("client_is_unusual_channel", clientBehavior.getClientIsUnusualChannel());
            features.put("client_channel_freq", clientBehavior.getClientChannelFreq().doubleValue());
            features.put("client_usual_category", clientBehavior.getClientUsualCategory());
            features.put("client_is_unusual_category", clientBehavior.getClientIsUnusualCategory());
            features.put("client_category_freq", clientBehavior.getClientCategoryFreq().doubleValue());
            features.put("client_risk_score", clientBehavior.getClientRiskScore().doubleValue());
        }

        return features;
    }
}
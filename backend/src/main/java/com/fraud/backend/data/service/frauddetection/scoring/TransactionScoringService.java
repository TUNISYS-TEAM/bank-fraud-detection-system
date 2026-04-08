package com.fraud.backend.data.service.frauddetection.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.backend.data.entity.corebanking.Account;
import com.fraud.backend.data.entity.corebanking.ClientBehavior;
import com.fraud.backend.data.entity.corebanking.Transaction;
import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.repository.corebanking.ClientBehaviorRepository;
import com.fraud.backend.data.repository.corebanking.TransactionRepository;
import com.fraud.backend.data.repository.frauddetection.FraudAlertRepository;
import com.fraud.backend.data.repository.frauddetection.ModelMLRepository;
import com.fraud.backend.data.service.frauddetection.deployment.PythonInferenceService;
import com.fraud.backend.data.service.frauddetection.monitoring.ModelDriftMonitoringService;
import com.fraud.backend.data.service.frauddetection.training.ModelProvider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TransactionScoringService {

    private final TransactionRepository transactionRepository;
    private final ModelMLRepository modelMLRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final ClientBehaviorRepository clientBehaviorRepository;
    private final PythonInferenceService pythonInferenceService;
    private final ModelProvider modelProvider;
    private final ModelDriftMonitoringService modelDriftMonitoringService;
    private final ObjectMapper objectMapper;


    @Value("${fraud.scoring.batch-size:200}")
    private int batchSize;

    private final AtomicInteger transactionsSinceLastDriftCheck = new AtomicInteger(0);
    private static final int DRIFT_CHECK_INTERVAL = 500;


    private final ExecutorService driftExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "drift-check-thread");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService inferenceExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "python-inference-thread");
        t.setDaemon(true);
        return t;
    });

    public TransactionScoringService(
            TransactionRepository transactionRepository,
            ModelMLRepository modelMLRepository,
            FraudAlertRepository fraudAlertRepository,
            ClientBehaviorRepository clientBehaviorRepository,
            PythonInferenceService pythonInferenceService,
            ModelProvider modelProvider,
            ModelDriftMonitoringService modelDriftMonitoringService,
            ObjectMapper objectMapper) {
        this.transactionRepository = transactionRepository;
        this.modelMLRepository = modelMLRepository;
        this.fraudAlertRepository = fraudAlertRepository;
        this.clientBehaviorRepository = clientBehaviorRepository;
        this.pythonInferenceService = pythonInferenceService;
        this.modelProvider = modelProvider;
        this.modelDriftMonitoringService = modelDriftMonitoringService;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down TransactionScoringService executors...");
        driftExecutor.shutdown();
        inferenceExecutor.shutdown();
        try {
            if (!driftExecutor.awaitTermination(5, TimeUnit.SECONDS)) driftExecutor.shutdownNow();
            if (!inferenceExecutor.awaitTermination(60, TimeUnit.SECONDS)) inferenceExecutor.shutdownNow();
        } catch (InterruptedException e) {
            driftExecutor.shutdownNow();
            inferenceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static class ScoreResult {
        private final UUID transactionUuid;
        private final FraudAlert alert;

        public ScoreResult(UUID transactionUuid, FraudAlert alert) {
            this.transactionUuid = transactionUuid;
            this.alert = alert;
        }

        public UUID getTransactionUuid() { return transactionUuid; }
        public FraudAlert getAlert() { return alert; }
    }

    @Transactional
    public FraudAlert scoreTransaction(UUID transactionUuid, UUID modelUuid) {
        try {
            Transaction transaction = transactionRepository.findByTransactionUuid(transactionUuid)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionUuid));

            if (transaction.getFraudScore() != null) {
                log.debug("Transaction {} already scored", transactionUuid);
                return null;
            }

            ModelML model = (modelUuid != null)
                    ? modelMLRepository.findByModelUuid(modelUuid).orElseThrow()
                    : getCachedDeployedModel();

            return scoreTransactionWithModel(transaction, model);
        } catch (Exception e) {
            log.error("Error scoring transaction {}: {}", transactionUuid, e.getMessage(), e);
            throw new RuntimeException("Scoring failed: " + e.getMessage(), e);
        }
    }

    @Transactional
    public FraudAlert scoreTransactionWithModel(Transaction transaction, ModelML model) {
        try {
            log.debug("Scoring transaction {} with model {}", transaction.getTransactionUuid(), model.getModelName());

            Map<String, Object> prediction = pythonInferenceService.predict(
                    model.getModelUuid(),
                    transaction.getAccount().getId(),
                    transaction.getTransactionUuid());

            return processPredictionResult(transaction, model, prediction, null);
        } catch (Exception e) {
            log.error("Error scoring transaction {}: {}", transaction.getTransactionUuid(), e.getMessage(), e);
            throw new RuntimeException("Scoring failed: " + e.getMessage(), e);
        }
    }

    @Transactional
    public List<ScoreResult> scoreBatch(List<UUID> transactionUuids, UUID modelUuid) {
        if (transactionUuids == null || transactionUuids.isEmpty()) {
            return Collections.emptyList();
        }

        ModelML model = (modelUuid != null)
                ? modelMLRepository.findByModelUuid(modelUuid).orElseThrow()
                : getCachedDeployedModel();

        List<Transaction> transactions = transactionRepository.findAllByTransactionUuidIn(transactionUuids)
                .stream()
                .filter(tx -> tx.getFraudScore() == null)
                .collect(Collectors.toList());

        if (transactions.isEmpty()) {
            log.info("No unscored transactions found in batch");
            return Collections.emptyList();
        }

        // Sort by account + time for correct velocity calculation
        transactions.sort((t1, t2) -> {
            int acc = Long.compare(t1.getAccount().getId(), t2.getAccount().getId());
            if (acc != 0) return acc;
            if (t1.getTransactionDate() == null || t2.getTransactionDate() == null) return 0;
            return t1.getTransactionDate().compareTo(t2.getTransactionDate());
        });

        // Load existing behaviors
        Map<Long, ClientBehavior> behaviorMap = new HashMap<>();
        List<Long> accountIds = transactions.stream()
                .map(tx -> tx.getAccount().getId())
                .distinct()
                .collect(Collectors.toList());
        clientBehaviorRepository.findByAccountIdIn(accountIds)
                .forEach(b -> behaviorMap.put(b.getAccount().getId(), b));

        // Track last transaction date per account
        Map<Long, LocalDateTime> lastTxDatePerAccount = new HashMap<>();
        behaviorMap.values().forEach(b -> {
            if (b.getLastCalculatedAt() != null) {
                lastTxDatePerAccount.put(b.getAccount().getId(), b.getLastCalculatedAt());
            }
        });

        List<Map<String, Object>> batchItems = new ArrayList<>();
        List<Transaction> orderedTransactions = new ArrayList<>();

        for (Transaction tx : transactions) {
            Long accountId = tx.getAccount().getId();
            ClientBehavior behavior = behaviorMap.get(accountId);

            LocalDateTime prevDate = lastTxDatePerAccount.get(accountId);
            long secondsSinceLastTx = -1;
            if (prevDate != null && tx.getTransactionDate() != null) {
                secondsSinceLastTx = Duration.between(prevDate, tx.getTransactionDate()).getSeconds();
                if (secondsSinceLastTx < 0) secondsSinceLastTx = 0;
            }

            Map<String, Object> item = new HashMap<>();
            item.put("transaction", pythonInferenceService.transactionToMap(tx));

            if (behavior != null) {
                Map<String, Object> behaviorForPython = pythonInferenceService.behaviorToMap(behavior);
                behaviorForPython.put("client_time_since_last_tx", secondsSinceLastTx);
                item.put("existing_behavior", behaviorForPython);
            } else {
                Map<String, Object> emptyBehavior = new HashMap<>();
                emptyBehavior.put("client_total_transactions", 0);
                emptyBehavior.put("client_time_since_last_tx", -1);
                item.put("existing_behavior", emptyBehavior);
            }

            batchItems.add(item);
            orderedTransactions.add(tx);

            lastTxDatePerAccount.put(accountId, tx.getTransactionDate());
        }

        // Call Python inference with longer timeout
        List<Map<String, Object>> predictions;
        try {
            log.info("Calling Python batch inference for {} transactions", batchItems.size());
            predictions = CompletableFuture.supplyAsync(
                    () -> pythonInferenceService.predictBatch(model.getModelUuid(), batchItems),
                    inferenceExecutor
            ).orTimeout(90, TimeUnit.SECONDS).join();

            log.info("Python batch prediction completed successfully for {} transactions", predictions.size());
        } catch (Exception e) {
            log.error("Python batch prediction failed or timed out", e);
            return fallbackToSingleScoring(orderedTransactions, model);
        }

        if (predictions.size() != orderedTransactions.size()) {
            log.error("Prediction size mismatch: expected {}, got {}", orderedTransactions.size(), predictions.size());
            return fallbackToSingleScoring(orderedTransactions, model);
        }

        List<ScoreResult> results = new ArrayList<>();
        List<Transaction> transactionsToUpdate = new ArrayList<>();
        List<ClientBehavior> behaviorsToUpdate = new ArrayList<>();

        for (int i = 0; i < orderedTransactions.size(); i++) {
            Transaction tx = orderedTransactions.get(i);
            Map<String, Object> pred = predictions.get(i);
            Long accountId = tx.getAccount().getId();

            // CRITICAL: Use raw model fraud_score directly from Python
            Double rawFraudScore = ((Number) pred.getOrDefault("fraud_score", 0.5)).doubleValue();
            Double fraudScore = Math.max(0.0, Math.min(1.0, rawFraudScore));

            log.debug("Transaction {} - Raw model fraud_score from Python: {}", tx.getTransactionUuid(), fraudScore);

            Double supervisedScore = ((Number) pred.getOrDefault("supervised_score", fraudScore)).doubleValue();
            Double anomalyScore = ((Number) pred.getOrDefault("anomaly_score", fraudScore * 0.8)).doubleValue();
            String riskLevel = (String) pred.getOrDefault("risk_level", "MEDIUM");
            String alertType = (String) pred.getOrDefault("alert_type", "BEHAVIORAL");

            Object fraudReasonObj = pred.get("fraud_reason");
            JsonNode fraudReasonJson = (fraudReasonObj != null)
                    ? objectMapper.valueToTree(fraudReasonObj)
                    : objectMapper.createObjectNode()
                    .put("risk_level", riskLevel)
                    .put("fraud_score", fraudScore)
                    .put("summary", "Transaction with score " + fraudScore);

            @SuppressWarnings("unchecked")
            Map<String, Object> signals = (Map<String, Object>) pred.getOrDefault("behavioral_signals", Map.of());

            ClientBehavior behavior = behaviorMap.get(accountId);
            boolean isNew = false;
            if (behavior == null) {
                behavior = createClientBehavior(tx.getAccount(), tx, fraudScore);
                isNew = true;
            }

            updateClientBehavior(behavior, tx, fraudScore, signals);
            behavior.setLastCalculatedAt(tx.getTransactionDate());

            if (isNew) behaviorMap.put(accountId, behavior);
            behaviorsToUpdate.add(behavior);

            tx.setFraudScore(BigDecimal.valueOf(fraudScore));
            tx.setIsFraud(fraudScore >= pythonInferenceService.getModelThreshold(model));
            tx.setModel(model);
            tx.setUpdatedAt(LocalDateTime.now());
            tx.setFraudReason(fraudReasonJson);
            transactionsToUpdate.add(tx);

            FraudAlert alert = null;
            if (shouldCreateAlert(riskLevel)) {
                alert = FraudAlert.builder()
                        .alertUuid(UUID.randomUUID())
                        .transaction(tx)
                        .user(model.getCreatedBy())
                        .fraudScore(fraudScore)
                        .supervisedScore(supervisedScore)
                        .anomalyScore(anomalyScore)
                        .modelUsed(model)
                        .riskLevel(riskLevel)
                        .alertType(alertType)
                        .status("PENDING")
                        .featuresSnapshot(Collections.emptyMap())
                        .createdAt(LocalDateTime.now())
                        .build();
                alert = fraudAlertRepository.save(alert);
            }

            results.add(new ScoreResult(tx.getTransactionUuid(), alert));
        }

        transactionRepository.saveAll(transactionsToUpdate);
        clientBehaviorRepository.saveAll(behaviorsToUpdate);

        checkDriftPeriodically();
        return results;
    }

    private void updateClientBehavior(ClientBehavior behavior, Transaction transaction,
                                      Double fraudScore, Map<String, Object> signals) {
        int currentTotal = behavior.getClientTotalTransactions() != null ? behavior.getClientTotalTransactions() : 0;
        int newTotal = currentTotal + 1;

        BigDecimal currentAvg = behavior.getClientAvgTxAmount() != null ? behavior.getClientAvgTxAmount() : BigDecimal.ZERO;
        BigDecimal newAvg = currentAvg.multiply(BigDecimal.valueOf(currentTotal))
                .add(transaction.getAmount())
                .divide(BigDecimal.valueOf(newTotal), 2, RoundingMode.HALF_UP);

        behavior.setClientAvgTxAmount(newAvg);
        behavior.setClientAvgTxPerDay(newAvg.divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP));
        behavior.setClientTotalTransactions(newTotal);

        long secondsSince = 0L;
        Object secondsObj = signals.getOrDefault("seconds_since_last_tx", 0L);
        if (secondsObj instanceof Number) {
            long val = ((Number) secondsObj).longValue();
            secondsSince = (val >= 0) ? val : 0L;
        }
        behavior.setClientTimeSinceLastTx(secondsSince);

        BigDecimal currentRisk = behavior.getClientRiskScore() != null ? behavior.getClientRiskScore() : BigDecimal.ZERO;
        BigDecimal newRisk = currentRisk.multiply(BigDecimal.valueOf(0.7))
                .add(BigDecimal.valueOf(fraudScore * 0.3));
        behavior.setClientRiskScore(newRisk);

        // Update behavioral fields from Python signals
        if (signals.containsKey("client_usual_region")) {
            behavior.setClientUsualRegion((String) signals.get("client_usual_region"));
        }
        if (signals.containsKey("client_region_freq")) {
            behavior.setClientRegionFreq(BigDecimal.valueOf(((Number) signals.get("client_region_freq")).doubleValue()));
        }
        if (signals.containsKey("client_is_unusual_region")) {
            behavior.setClientIsUnusualRegion(Boolean.TRUE.equals(signals.get("client_is_unusual_region")));
        }

        if (signals.containsKey("client_usual_channel")) {
            behavior.setClientUsualChannel((String) signals.get("client_usual_channel"));
        }
        if (signals.containsKey("client_channel_freq")) {
            behavior.setClientChannelFreq(BigDecimal.valueOf(((Number) signals.get("client_channel_freq")).doubleValue()));
        }
        if (signals.containsKey("client_is_unusual_channel")) {
            behavior.setClientIsUnusualChannel(Boolean.TRUE.equals(signals.get("client_is_unusual_channel")));
        }

        if (signals.containsKey("client_usual_category")) {
            behavior.setClientUsualCategory((String) signals.get("client_usual_category"));
        }
        if (signals.containsKey("client_category_freq")) {
            behavior.setClientCategoryFreq(BigDecimal.valueOf(((Number) signals.get("client_category_freq")).doubleValue()));
        }
        if (signals.containsKey("client_is_unusual_category")) {
            behavior.setClientIsUnusualCategory(Boolean.TRUE.equals(signals.get("client_is_unusual_category")));
        }
    }

    private List<ScoreResult> fallbackToSingleScoring(List<Transaction> transactions, ModelML model) {
        log.warn("Falling back to single-transaction scoring for {} transactions (batch failed or timed out)", transactions.size());
        List<ScoreResult> results = new ArrayList<>();
        for (Transaction tx : transactions) {
            try {
                FraudAlert alert = scoreTransactionWithModel(tx, model);
                results.add(new ScoreResult(tx.getTransactionUuid(), alert));
            } catch (Exception e) {
                log.error("Fallback failed for transaction {}", tx.getTransactionUuid(), e);
                results.add(new ScoreResult(tx.getTransactionUuid(), null));
            }
        }
        return results;
    }

    private FraudAlert processPredictionResult(Transaction transaction, ModelML model,
            Map<String, Object> prediction, Map<String, Object> features) {
        Double fraudScore = ((Number) prediction.getOrDefault("fraud_score", 0.5)).doubleValue();
        fraudScore = Math.max(0.0, Math.min(1.0, fraudScore));

        Double supervisedScore = ((Number) prediction.getOrDefault("supervised_score", fraudScore)).doubleValue();
        Double anomalyScore = ((Number) prediction.getOrDefault("anomaly_score", fraudScore * 0.8)).doubleValue();
        String riskLevel = (String) prediction.getOrDefault("risk_level", "MEDIUM");
        String alertType = (String) prediction.getOrDefault("alert_type", "BEHAVIORAL");

        Object fraudReasonObj = prediction.get("fraud_reason");
        JsonNode fraudReasonJson = (fraudReasonObj != null) ? objectMapper.valueToTree(fraudReasonObj)
                : objectMapper.createObjectNode()
                .put("risk_level", riskLevel)
                .put("fraud_score", fraudScore)
                .put("summary", "Transaction with score " + fraudScore);

        transaction.setFraudScore(BigDecimal.valueOf(fraudScore));
        transaction.setIsFraud(fraudScore >= pythonInferenceService.getModelThreshold(model));
        transaction.setModel(model);
        transaction.setUpdatedAt(LocalDateTime.now());
        transaction.setFraudReason(fraudReasonJson);
        transactionRepository.save(transaction);

        ClientBehavior behavior = clientBehaviorRepository.findByAccountId(transaction.getAccount().getId()).orElse(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> signals = (Map<String, Object>) prediction.getOrDefault("behavioral_signals", Map.of());

        if (behavior != null) {
            updateClientBehavior(behavior, transaction, fraudScore, signals);
            behavior.setLastCalculatedAt(transaction.getTransactionDate());
            clientBehaviorRepository.save(behavior);
        } else {
            ClientBehavior newBehavior = createClientBehavior(transaction.getAccount(), transaction, fraudScore);
            updateClientBehavior(newBehavior, transaction, fraudScore, signals);
            newBehavior.setLastCalculatedAt(transaction.getTransactionDate());
            clientBehaviorRepository.save(newBehavior);
        }

        if (shouldCreateAlert(riskLevel)) {
            FraudAlert alert = FraudAlert.builder()
                    .alertUuid(UUID.randomUUID())
                    .transaction(transaction)
                    .user(model.getCreatedBy())
                    .fraudScore(fraudScore)
                    .supervisedScore(supervisedScore)
                    .anomalyScore(anomalyScore)
                    .modelUsed(model)
                    .riskLevel(riskLevel)
                    .alertType(alertType)
                    .status("PENDING")
                    .featuresSnapshot(Collections.emptyMap())
                    .createdAt(LocalDateTime.now())
                    .build();
            return fraudAlertRepository.save(alert);
        }
        return null;
    }

    private boolean shouldCreateAlert(String riskLevel) {
        return riskLevel != null && List.of("MEDIUM", "HIGH", "CRITICAL").contains(riskLevel.toUpperCase());
    }

    private ModelML getCachedDeployedModel() {
        return modelProvider.getDeployedModelEntity()
                .orElseThrow(() -> new IllegalStateException("Aucun modèle n'est déployé pour le moment"));
    }

    private void checkDriftPeriodically() {
        if (transactionsSinceLastDriftCheck.incrementAndGet() >= DRIFT_CHECK_INTERVAL) {
            transactionsSinceLastDriftCheck.set(0);
            driftExecutor.submit(() -> {
                try {
                    modelDriftMonitoringService.getDriftStatus(null);
                } catch (Exception e) {
                    log.warn("Drift check failed: {}", e.getMessage());
                }
            });
        }
    }

    private ClientBehavior createClientBehavior(Account account, Transaction transaction, Double fraudScore) {
        return ClientBehavior.builder()
                .account(account)
                .clientAvgTxAmount(transaction.getAmount())
                .clientAvgTxPerDay(transaction.getAmount().divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP))
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
                .clientUsualChannel(transaction.getChannel() != null ? transaction.getChannel().name() : "Unknown")
                .clientIsUnusualChannel(false)
                .clientChannelFreq(BigDecimal.ONE)
                .clientUsualCategory(transaction.getMerchantCategory() != null ? transaction.getMerchantCategory() : "Unknown")
                .clientIsUnusualCategory(false)
                .clientCategoryFreq(BigDecimal.ONE)
                .lastCalculatedAt(transaction.getTransactionDate())
                .build();
    }
}
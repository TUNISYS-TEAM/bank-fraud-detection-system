package com.fraud.backend.data.service.frauddetection.scoring;

import com.fraud.backend.api.dto.frauddetection.model.response.ScoringJobStatus;
import com.fraud.backend.data.entity.corebanking.Account;
import com.fraud.backend.data.entity.corebanking.Transaction;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.repository.corebanking.AccountRepository;
import com.fraud.backend.data.repository.corebanking.TransactionRepository;
import com.fraud.backend.data.service.frauddetection.training.ModelProvider;

import com.fraud.backend.data.event.ModelDeploymentEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class BackfillScoringService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final ModelProvider modelProvider;
    private final TransactionScoringService transactionScoringService;
    private final ScoringJobService scoringJobService;

    @Value("${fraud.backfill.batch-size:100}")
    private int batchSize;

    private final BackfillScoringService self;

    private final AtomicBoolean backfillRunning = new AtomicBoolean(false);
    private volatile UUID currentBackfillJobId;
    private final AtomicLong processedCount = new AtomicLong(0);

    private final AtomicReference<UUID> currentModelUuid = new AtomicReference<>(null);
    private volatile boolean shouldStopBackfill = false;

    public BackfillScoringService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            ModelProvider modelProvider,
            TransactionScoringService transactionScoringService,
            ScoringJobService scoringJobService,
            @Lazy BackfillScoringService self) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.modelProvider = modelProvider;
        this.transactionScoringService = transactionScoringService;
        this.scoringJobService = scoringJobService;
        this.self = self;
    }

    /**
     * Responds to model deployment events AFTER the transaction is committed.
     * Using AFTER_COMMIT ensures the model is already visible in the DB when backfill queries it.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onModelDeployment(ModelDeploymentEvent event) {
        log.info("Received ModelDeploymentEvent: {} for model {} for manager {} (after commit)", 
                event.getAction(), event.getModelUuid(), event.getManagerId());
        if (event.getAction() == ModelDeploymentEvent.Action.DEPLOYED) {
            stopBackfill();
            tryStartBackfillIfNeeded(event.getManagerId());
        } else if (event.getAction() == ModelDeploymentEvent.Action.UNDEPLOYED) {
            stopBackfillDueToUndeploy();
        }
    }

    /**
     * Called after model deployment or dataset import to check if backfill is needed.
     */
    public void tryStartBackfillIfNeeded(Long managerId) {
        log.info("=== Backfill auto-check triggered (managerId: {}) ===", managerId != null ? managerId : "system");

        if (backfillRunning.get()) {
            log.info("Backfill is already running.");
            return;
        }

        if (modelProvider.getDeployedModelEntity(managerId).isEmpty()) {
            log.info("No deployed model found for manager {}. Backfill will start when a model is deployed.", 
                    managerId != null ? managerId : "system");
            return;
        }

        long unscoredCount = managerId != null 
                ? transactionRepository.countUnscoredTransactionsByManagerId(managerId)
                : countTransactionsToBackfill(); // Global fallback

        if (unscoredCount > 0) {
            log.info("Found {} unscored transactions. Starting backfill...", unscoredCount);
            startBackfillForDeployedModel(managerId);
        } else {
            log.info("No unscored transactions found.");
        }
    }

    /**
     * Call this AFTER saving a new Transaction with fraudScore == null
     */
    public void triggerBackfillForNewTransaction(UUID transactionUuid, Long managerId) {
        if (transactionUuid == null) return;

        try {
            if (backfillRunning.get()) {
                log.debug("Backfill already running. New transaction {} will be processed in current batch.", transactionUuid);
                return;
            }

            if (modelProvider.getDeployedModelEntity(managerId).isPresent()) {
                log.info("New unscored transaction {} detected → triggering backfill", transactionUuid);
                startBackfillForDeployedModel(managerId);
            }
        } catch (Exception e) {
            log.warn("Could not trigger backfill for transaction {}: {}", transactionUuid, e.getMessage());
        }
    }

    public boolean isBackfillRunning() {
        return backfillRunning.get();
    }

    public UUID getCurrentBackfillJobId() {
        return currentBackfillJobId;
    }

    public ScoringJobStatus getBackfillJobStatus() {
        if (currentBackfillJobId == null) return null;
        ScoringJobStatus status = scoringJobService.getJob(currentBackfillJobId);
        if (status != null) {
            status.setProcessedCount((int) processedCount.get());
        }
        return status;
    }

    /**
     * Stop backfill when model is undeployed (graceful stop)
     */
    public void stopBackfillDueToUndeploy() {
        if (backfillRunning.get()) {
            log.info("Model undeployed → stopping backfill gracefully (already processed transactions are kept)");
            shouldStopBackfill = true;
        } else {
            log.info("No backfill running to stop.");
        }
    }

    /**
     * General stop (used when deploying a new model)
     */
    public void stopBackfill() {
        if (backfillRunning.get()) {
            log.info("Stopping current backfill job {} before starting new one...", currentBackfillJobId);
            shouldStopBackfill = true;
        }
    }

    private boolean isModelStillDeployed(UUID modelUuid, Long managerId) {
        return modelProvider.getDeployedModelEntity(managerId)
                .map(model -> model.getModelUuid().equals(modelUuid))
                .orElse(false);
    }

    public UUID startBackfillForDeployedModel(Long managerId) {
        if (!backfillRunning.compareAndSet(false, true)) {
            log.warn("Backfill is already running.");
            return currentBackfillJobId;
        }

        shouldStopBackfill = false;

        UUID jobId = UUID.randomUUID();
        currentBackfillJobId = jobId;
        processedCount.set(0);

        ModelML model = modelProvider.getDeployedModelEntity(managerId).orElseThrow();
        currentModelUuid.set(model.getModelUuid());

        CompletableFuture.supplyAsync(() -> managerId != null 
                ? (int) transactionRepository.countUnscoredTransactionsByManagerId(managerId)
                : countTransactionsToBackfill())
                .thenAccept(total -> {
                    scoringJobService.createJob(jobId, total);
                    log.info("Backfill job {} created for model '{}' ({} transactions to process) for manager {}", 
                            jobId, model.getModelName(), total, managerId);
                    self.runBackfillOptimized(jobId, managerId);
                });

        return jobId;
    }

    @Async
    @Transactional
    protected void runBackfillOptimized(UUID jobId, Long managerId) {
        List<UUID> failedTransactions = new ArrayList<>();

        try {
            ModelML model = modelProvider.getDeployedModelEntity(managerId)
                    .orElseThrow(() -> new IllegalStateException("No model deployed"));

            final UUID modelUuid = model.getModelUuid();
            final String modelName = model.getModelName();

            log.info("========================================");
            log.info("STARTING BACKFILL JOB {} | Manager: {} | Model: {}", jobId, managerId, modelName);
            log.info("========================================");

            log.info("Collecting unscored transactions for manager: {}...", managerId != null ? managerId : "global");
            List<UUID> unscoredUuids = transactionRepository.findUnscoredTransactionUuidsByManagerId(managerId);
            
            ConcurrentLinkedQueue<UUID> transactionQueue = new ConcurrentLinkedQueue<>(unscoredUuids);
            int totalUnscored = transactionQueue.size();
            
            // For logging purposes
            long totalTx = managerId != null ? transactionRepository.countAllByManagerId(managerId) : transactionRepository.countAll();
            long alreadyScoredCount = totalTx - totalUnscored;

            log.info("Found {} unscored transactions | {} already scored", totalUnscored, alreadyScoredCount);

            if (totalUnscored == 0) {
                log.info("No unscored transactions found. Backfill completed.");
                scoringJobService.markCompleted(jobId);
                return;
            }

            // Main batch processing
            List<UUID> batch = new ArrayList<>(batchSize);
            int batchNumber = 0;
            long totalProcessingStart = System.currentTimeMillis();

            while (!transactionQueue.isEmpty() && !shouldStopBackfill && isModelStillDeployed(modelUuid, managerId)) {
                batch.clear();
                for (int i = 0; i < batchSize && !transactionQueue.isEmpty(); i++) {
                    UUID uuid = transactionQueue.poll();
                    if (uuid != null) batch.add(uuid);
                }
                if (batch.isEmpty()) break;

                batchNumber++;
                long batchStartTime = System.currentTimeMillis();

                try {
                    log.info("Processing batch {}/{}: {} transactions", batchNumber,
                            (totalUnscored + batchSize - 1) / batchSize, batch.size());

                    List<TransactionScoringService.ScoreResult> results =
                            transactionScoringService.scoreBatch(batch, modelUuid);

                    long batchDuration = System.currentTimeMillis() - batchStartTime;
                    long alertsCreated = results.stream().filter(r -> r.getAlert() != null).count();

                    for (TransactionScoringService.ScoreResult result : results) {
                        if (result.getAlert() != null) {
                            scoringJobService.addResult(jobId, result.getAlert());
                        }
                        scoringJobService.incrementProcessed(jobId);
                        processedCount.incrementAndGet();
                    }

                    double tps = (batch.size() * 1000.0) / Math.max(1, batchDuration);
                    log.info("Batch {} completed in {}ms ({} txs, {} alerts, {:.1f} tx/s)",
                            batchNumber, batchDuration, batch.size(), alertsCreated, tps);

                } catch (Exception e) {
                    log.error("Batch {}/{} failed → will retry in second pass", batchNumber, totalUnscored / batchSize, e);
                    failedTransactions.addAll(batch);
                }
            }

            // Second attempt for failed transactions
            if (!failedTransactions.isEmpty() && !shouldStopBackfill && isModelStillDeployed(modelUuid, managerId)) {
                log.info("Starting SECOND ATTEMPT on {} failed transactions", failedTransactions.size());
                try {
                    List<TransactionScoringService.ScoreResult> results =
                            transactionScoringService.scoreBatch(failedTransactions, modelUuid);

                    for (TransactionScoringService.ScoreResult result : results) {
                        if (result.getAlert() != null) scoringJobService.addResult(jobId, result.getAlert());
                        scoringJobService.incrementProcessed(jobId);
                        processedCount.incrementAndGet();
                    }
                    log.info("Second attempt completed successfully");
                } catch (Exception e) {
                    log.error("Second attempt failed for {} transactions", failedTransactions.size(), e);
                    for (UUID uuid : failedTransactions) {
                        scoringJobService.addError(jobId, uuid, "Failed on first AND second attempt");
                        scoringJobService.incrementProcessed(jobId);
                        processedCount.incrementAndGet();
                    }
                }
            } else if (!failedTransactions.isEmpty()) {
                log.warn("Skipping second attempt (backfill stopped or model changed)");
                for (UUID uuid : failedTransactions) {
                    scoringJobService.addError(jobId, uuid, "Backfill stopped before second attempt");
                    scoringJobService.incrementProcessed(jobId);
                    processedCount.incrementAndGet();
                }
            }

            long totalDuration = System.currentTimeMillis() - totalProcessingStart;
            double avgTps = (processedCount.get() * 1000.0) / Math.max(1, totalDuration);

            if (shouldStopBackfill) {
                log.warn("Backfill job {} stopped (model changed or undeployed)", jobId);
                scoringJobService.markFailed(jobId, "Backfill stopped due to model change/undeploy");
            } else {
                scoringJobService.markCompleted(jobId);
                log.info("========================================");
                log.info("BACKFILL JOB {} COMPLETED SUCCESSFULLY!", jobId);
                log.info("Model: {} | Total processed: {} | Average speed: {:.1f} tx/s", modelName, processedCount.get(), avgTps);
                log.info("========================================");
            }

        } catch (Exception e) {
            log.error("Backfill job {} failed", jobId, e);
            scoringJobService.markFailed(jobId, e.getMessage());
        } finally {
            backfillRunning.set(false);
            currentBackfillJobId = null;
            processedCount.set(0);
            currentModelUuid.set(null);
            shouldStopBackfill = false;
        }
    }

    private int countTransactionsToBackfill() {
        int count = 0;
        List<Account> accounts = accountRepository.findAll();

        for (Account account : accounts) {
            List<Transaction> transactions = transactionRepository
                    .findByAccountIdOrderByTransactionDateAsc(account.getId());

            for (Transaction transaction : transactions) {
                if (transaction.getFraudScore() == null) {
                    count++;
                }
            }
        }
        return count;
    }
}
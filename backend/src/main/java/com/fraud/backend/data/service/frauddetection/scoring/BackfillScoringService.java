package com.fraud.backend.data.service.frauddetection.scoring;

import com.fraud.backend.api.dto.frauddetection.model.response.ScoringJobStatus;
import com.fraud.backend.data.entity.corebanking.Account;
import com.fraud.backend.data.entity.corebanking.Transaction;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.repository.corebanking.AccountRepository;
import com.fraud.backend.data.repository.corebanking.TransactionRepository;
import com.fraud.backend.data.service.frauddetection.training.ModelProvider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
    private volatile LocalDateTime backfillStartTime;
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
     * Called on Spring Boot startup (via ApplicationRunner) and after model deployment
     * This safely checks and starts backfill if needed.
     */
    public void tryStartBackfillIfNeeded() {
        log.info("=== Backfill auto-check triggered (startup or model deployment) ===");

        if (backfillRunning.get()) {
            log.info("Backfill is already running.");
            return;
        }

        if (modelProvider.getDeployedModelEntity().isEmpty()) {
            log.info("No deployed model found. Backfill will start when a model is deployed.");
            return;
        }

        int unscoredCount = countTransactionsToBackfill();
        if (unscoredCount > 0) {
            log.info("Found {} unscored transactions on startup/deployment. Starting backfill...", unscoredCount);
            startBackfillForDeployedModel();
        } else {
            log.info("No unscored transactions found.");
        }
    }

    /**
     * Call this AFTER saving a new Transaction with fraudScore == null
     */
    public void triggerBackfillForNewTransaction(UUID transactionUuid) {
        if (transactionUuid == null) return;

        try {
            if (backfillRunning.get()) {
                log.debug("Backfill already running. New transaction {} will be processed in current batch.", transactionUuid);
                return;
            }

            if (modelProvider.getDeployedModelEntity().isPresent()) {
                log.info("New unscored transaction {} detected → triggering backfill", transactionUuid);
                startBackfillForDeployedModel();
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

    private boolean isModelStillDeployed(UUID modelUuid) {
        return modelProvider.getDeployedModelEntity()
                .map(model -> model.getModelUuid().equals(modelUuid))
                .orElse(false);
    }

    public UUID startBackfillForDeployedModel() {
        if (!backfillRunning.compareAndSet(false, true)) {
            log.warn("Backfill is already running.");
            return currentBackfillJobId;
        }

        shouldStopBackfill = false;

        UUID jobId = UUID.randomUUID();
        currentBackfillJobId = jobId;
        backfillStartTime = LocalDateTime.now();
        processedCount.set(0);

        ModelML model = modelProvider.getDeployedModelEntity().orElseThrow();
        currentModelUuid.set(model.getModelUuid());

        CompletableFuture.supplyAsync(this::countTransactionsToBackfill)
                .thenAccept(total -> {
                    scoringJobService.createJob(jobId, total);
                    log.info("Backfill job {} created for model '{}' ({} transactions to process)", 
                            jobId, model.getModelName(), total);
                    self.runBackfillOptimized(jobId);
                });

        return jobId;
    }

    @Async
    protected void runBackfillOptimized(UUID jobId) {
        List<UUID> failedTransactions = new ArrayList<>();

        try {
            ModelML model = modelProvider.getDeployedModelEntity()
                    .orElseThrow(() -> new IllegalStateException("No model deployed"));

            final UUID modelUuid = model.getModelUuid();
            final String modelName = model.getModelName();

            log.info("========================================");
            log.info("STARTING BACKFILL JOB {} | Model: {} | Batch size: {}", jobId, modelName, batchSize);
            log.info("========================================");

            List<Account> accounts = accountRepository.findAll();
            ConcurrentLinkedQueue<UUID> transactionQueue = new ConcurrentLinkedQueue<>();
            int alreadyScoredCount = 0;

            // Collect unscored transactions
            for (Account account : accounts) {
                if (shouldStopBackfill || !isModelStillDeployed(modelUuid)) {
                    break;
                }

                List<Transaction> transactions = transactionRepository
                        .findByAccountIdOrderByTransactionDateAsc(account.getId());

                for (Transaction tx : transactions) {
                    if (tx.getFraudScore() == null) {
                        transactionQueue.add(tx.getTransactionUuid());
                    } else {
                        alreadyScoredCount++;
                    }
                }
            }

            int totalUnscored = transactionQueue.size();
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

            while (!transactionQueue.isEmpty() && !shouldStopBackfill && isModelStillDeployed(modelUuid)) {
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
            if (!failedTransactions.isEmpty() && !shouldStopBackfill && isModelStillDeployed(modelUuid)) {
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
            backfillStartTime = null;
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
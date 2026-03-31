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

    @Value("${fraud.backfill.parallelism:5}")
    private int parallelism;

    private final BackfillScoringService self;
    private final AtomicBoolean backfillRunning = new AtomicBoolean(false);
    private volatile UUID currentBackfillJobId;
    private volatile LocalDateTime backfillStartTime;
    private final AtomicLong processedCount = new AtomicLong(0);

    private final AtomicReference<UUID> backfillModelUuid = new AtomicReference<>(null);
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

    public boolean isBackfillRunning() {
        return backfillRunning.get();
    }

    public UUID getCurrentBackfillJobId() {
        return currentBackfillJobId;
    }

    public ScoringJobStatus getBackfillJobStatus() {
        if (currentBackfillJobId == null) {
            return null;
        }
        ScoringJobStatus status = scoringJobService.getJob(currentBackfillJobId);
        if (status != null) {
            status.setProcessedCount((int) processedCount.get());
        }
        return status;
    }

    public void stopBackfill() {
        if (backfillRunning.get()) {
            log.info("Stopping backfill job {}...", currentBackfillJobId);
            shouldStopBackfill = true;
        } else {
            log.info("No backfill job is currently running");
        }
    }

    private boolean isModelStillDeployed(UUID modelUuid) {
        return modelProvider.getDeployedModelEntity()
                .map(model -> model.getModelUuid().equals(modelUuid))
                .orElse(false);
    }

    public UUID startBackfillForDeployedModel() {
        if (!backfillRunning.compareAndSet(false, true)) {
            throw new IllegalStateException("Backfill is already running. Current job ID: " + currentBackfillJobId);
        }

        shouldStopBackfill = false;

        UUID jobId = UUID.randomUUID();
        currentBackfillJobId = jobId;
        backfillStartTime = LocalDateTime.now();
        processedCount.set(0);

        CompletableFuture.supplyAsync(() -> countTransactionsToBackfill())
                .thenAccept(total -> {
                    scoringJobService.createJob(jobId, total);
                    log.info("Backfill job {} created with {} transactions to process", jobId, total);
                    self.runBackfillOptimized(jobId);
                });

        return jobId;
    }

    @Async
    protected void runBackfillOptimized(UUID jobId) {
        try {
            ModelML deployedModel = modelProvider.getDeployedModelEntity()
                    .orElseThrow(() -> new IllegalStateException("No model deployed. Please deploy a model first."));

            backfillModelUuid.set(deployedModel.getModelUuid());
            final UUID modelUuid = deployedModel.getModelUuid();
            final String modelName = deployedModel.getModelName();

            log.info("========================================");
            log.info("Starting BACKFILL job {} using model: {} (UUID: {})", jobId, modelName, modelUuid);
            log.info("Batch size: {} transactions per Python call", batchSize);
            log.info("========================================");

            // Get all accounts
            List<Account> accounts = accountRepository.findAll();
            log.info("Scanning {} accounts for unscored transactions...", accounts.size());

            // Queue to collect all unscored transaction UUIDs
            ConcurrentLinkedQueue<UUID> transactionQueue = new ConcurrentLinkedQueue<>();
            int alreadyScoredCount = 0;

            // First pass: collect all unscored transaction UUIDs
            for (Account account : accounts) {
                if (shouldStopBackfill || !isModelStillDeployed(backfillModelUuid.get())) {
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
            log.info("Found {} unscored transactions to process", totalUnscored);
            log.info("Found {} already scored transactions (skipping)", alreadyScoredCount);

            if (totalUnscored == 0) {
                log.info("No unscored transactions found. Backfill completed.");
                scoringJobService.markCompleted(jobId);
                long duration = java.time.Duration.between(backfillStartTime, LocalDateTime.now()).getSeconds();
                log.info("Backfill job {} completed in {} seconds", jobId, duration);
                return;
            }

            // Process transactions in GLOBAL batches (across all accounts)
            List<UUID> batch = new ArrayList<>(batchSize);
            int batchNumber = 0;
            int totalBatches = (totalUnscored + batchSize - 1) / batchSize;
            long totalProcessingStart = System.currentTimeMillis();

            while (!transactionQueue.isEmpty() && !shouldStopBackfill && isModelStillDeployed(backfillModelUuid.get())) {
                batch.clear();
                
                for (int i = 0; i < batchSize && !transactionQueue.isEmpty(); i++) {
                    UUID uuid = transactionQueue.poll();
                    if (uuid != null) {
                        batch.add(uuid);
                    }
                }

                if (batch.isEmpty()) {
                    break;
                }

                batchNumber++;
                long batchStartTime = System.currentTimeMillis();

                try {
                    log.info("Processing batch {}/{}: {} transactions", batchNumber, totalBatches, batch.size());
                    
                    // Pass the model UUID to scoreBatch
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
                    log.info("Batch {} completed in {}ms ({} txs, {} alerts, {:.1f} txs/sec)", 
                            batchNumber, batchDuration, batch.size(), alertsCreated, tps);
                    
                } catch (Exception e) {
                    log.error("Batch {} failed: {}", batchNumber, e.getMessage());
                    for (UUID uuid : batch) {
                        scoringJobService.addError(jobId, uuid, e.getMessage());
                        scoringJobService.incrementProcessed(jobId);
                        processedCount.incrementAndGet();
                    }
                }
            }

            long totalDuration = System.currentTimeMillis() - totalProcessingStart;
            double avgTps = (processedCount.get() * 1000.0) / Math.max(1, totalDuration);

            if (shouldStopBackfill) {
                log.warn("Backfill job {} stopped by user request after processing {} transactions", 
                        jobId, processedCount.get());
                scoringJobService.markFailed(jobId, "Backfill stopped by user");
            } else if (!isModelStillDeployed(backfillModelUuid.get())) {
                log.warn("Backfill job {} stopped because model {} is no longer deployed", jobId, modelName);
                scoringJobService.markFailed(jobId, "Model was undeployed during backfill");
            } else {
                scoringJobService.markCompleted(jobId);
                long totalSeconds = java.time.Duration.between(backfillStartTime, LocalDateTime.now()).getSeconds();
                log.info("========================================");
                log.info("BACKFILL JOB {} COMPLETED SUCCESSFULLY!", jobId);
                log.info("Total time: {} seconds", totalSeconds);
                log.info("Total transactions processed: {}", processedCount.get());
                log.info("Average speed: {:.1f} transactions/second", avgTps);
                log.info("Total Python calls: {} ({} per batch)", batchNumber, batchSize);
                log.info("========================================");
            }

        } catch (Exception e) {
            log.error("Backfill job {} failed: {}", jobId, e.getMessage(), e);
            scoringJobService.markFailed(jobId, e.getMessage());
        } finally {
            backfillRunning.set(false);
            currentBackfillJobId = null;
            backfillStartTime = null;
            processedCount.set(0);
            backfillModelUuid.set(null);
            shouldStopBackfill = false;
        }
    }

    private void processAccountTransactions(Account account, UUID modelUuid, UUID jobId) {
        try {
            if (shouldStopBackfill || !isModelStillDeployed(backfillModelUuid.get())) {
                return;
            }

            ModelML model = modelProvider.getDeployedModelEntity()
                    .filter(m -> m.getModelUuid().equals(modelUuid))
                    .orElse(null);

            if (model == null) {
                log.warn("Model {} no longer deployed, stopping processing for account {}", modelUuid, account.getId());
                return;
            }

            List<Transaction> transactions = transactionRepository
                    .findByAccountIdOrderByTransactionDateAsc(account.getId());

            if (transactions.isEmpty()) {
                return;
            }

            List<UUID> unscoredUuids = transactions.stream()
                    .filter(tx -> tx.getFraudScore() == null)
                    .map(Transaction::getTransactionUuid)
                    .toList();

            if (unscoredUuids.isEmpty()) {
                for (Transaction tx : transactions) {
                    scoringJobService.incrementProcessed(jobId);
                    processedCount.incrementAndGet();
                }
                return;
            }

            for (int i = 0; i < unscoredUuids.size(); i += batchSize) {
                if (shouldStopBackfill || !isModelStillDeployed(backfillModelUuid.get())) {
                    return;
                }

                int end = Math.min(i + batchSize, unscoredUuids.size());
                List<UUID> batch = unscoredUuids.subList(i, end);

                try {
                    List<TransactionScoringService.ScoreResult> results = transactionScoringService.scoreBatch(batch, modelUuid);
                    for (TransactionScoringService.ScoreResult result : results) {
                        if (result.getAlert() != null) {
                            scoringJobService.addResult(jobId, result.getAlert());
                        }
                        scoringJobService.incrementProcessed(jobId);
                        processedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Batch scoring failed for account {} batch: {}", account.getId(), e.getMessage());
                    for (UUID uuid : batch) {
                        scoringJobService.addError(jobId, uuid, e.getMessage());
                        scoringJobService.incrementProcessed(jobId);
                        processedCount.incrementAndGet();
                    }
                }
            }

        } catch (Exception e) {
            log.error("Failed to process account {}: {}", account.getId(), e.getMessage(), e);
            scoringJobService.addError(jobId, null, "Account " + account.getId() + ": " + e.getMessage());
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
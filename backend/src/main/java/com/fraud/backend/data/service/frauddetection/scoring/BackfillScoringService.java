package com.fraud.backend.data.service.frauddetection.scoring;

import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.repository.corebanking.TransactionRepository;
import com.fraud.backend.data.repository.frauddetection.ModelDeploymentHistoryRepository;
import com.fraud.backend.data.service.accessmanagement.UserService;
import com.fraud.backend.data.service.frauddetection.training.ModelProvider;
import com.fraud.backend.data.event.ModelDeploymentEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Service
public class BackfillScoringService {

    private final TransactionRepository transactionRepository;
    private final ModelProvider modelProvider;
    private final TransactionScoringService transactionScoringService;
    private final ScoringJobService scoringJobService;
    private final ModelDeploymentHistoryRepository deploymentHistoryRepository;
    private final UserService userService;

    @Value("${fraud.backfill.batch-size:100}")
    private int batchSize;

    private final BackfillScoringService self;

    // Manager ID -> active job ID (only used to prevent concurrent runs)
    private final Map<Long, UUID> managerJobIds = new ConcurrentHashMap<>();

    public BackfillScoringService(
            TransactionRepository transactionRepository,
            ModelProvider modelProvider,
            TransactionScoringService transactionScoringService,
            ScoringJobService scoringJobService,
            ModelDeploymentHistoryRepository deploymentHistoryRepository,
            UserService userService,
            @Lazy BackfillScoringService self) {
        this.transactionRepository = transactionRepository;
        this.modelProvider = modelProvider;
        this.transactionScoringService = transactionScoringService;
        this.scoringJobService = scoringJobService;
        this.deploymentHistoryRepository = deploymentHistoryRepository;
        this.userService = userService;
        this.self = self;
    }

    private Long getCurrentManagerId() {
        try {
            var user = userService.getCurrentUser();
            if (user == null) return null;
            String role = user.getRole().getName().toUpperCase();
            if ("ADMIN".equals(role)) return null;
            if ("MANAGER".equals(role)) return user.getId();
            if ("ANALYST".equals(role) && user.getManager() != null) return user.getManager().getId();
        } catch (Exception e) {
            log.warn("Could not determine current manager ID: {}", e.getMessage());
        }
        return null;
    }

    // Controller‑compatible methods
    public boolean isBackfillRunning() {
        Long managerId = getCurrentManagerId();
        return managerId != null && managerJobIds.containsKey(managerId);
    }

    public UUID getCurrentBackfillJobId() {
        Long managerId = getCurrentManagerId();
        return managerId != null ? managerJobIds.get(managerId) : null;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onModelDeployment(ModelDeploymentEvent event) {
        log.info("Received ModelDeploymentEvent: {} for model {} for manager {} (after commit)",
                event.getAction(), event.getModelUuid(), event.getManagerId());
        if (event.getAction() == ModelDeploymentEvent.Action.DEPLOYED) {
            stopBackfillForManager(event.getManagerId());
            tryStartBackfillIfNeeded(event.getManagerId());
        } else if (event.getAction() == ModelDeploymentEvent.Action.UNDEPLOYED) {
            stopBackfillForManager(event.getManagerId());
        }
    }

    public void tryStartBackfillIfNeeded(Long managerId) {
        if (managerId == null) {
            log.info("No managerId provided – backfill requires a valid manager. Skipping.");
            return;
        }
        if (managerJobIds.containsKey(managerId)) {
            log.info("Backfill already running for manager {}", managerId);
            return;
        }
        if (modelProvider.getDeployedModelEntity(managerId).isEmpty()) {
            log.info("No deployed model found for manager {}. Backfill will start when a model is deployed.", managerId);
            return;
        }
        long unscoredCount = transactionRepository.countUnscoredTransactionsByManagerId(managerId);
        if (unscoredCount > 0) {
            log.info("Found {} unscored transactions for manager {}. Starting backfill...", unscoredCount, managerId);
            startBackfillForDeployedModel(managerId);
        } else {
            log.info("No unscored transactions found for manager {}.", managerId);
        }
    }

    public void stopBackfillForManager(Long managerId) {
        if (managerId == null) return;
        UUID jobId = managerJobIds.remove(managerId);
        if (jobId != null) {
            log.info("Removed backfill job {} for manager {} – will stop on next DB status check", jobId, managerId);
        } else {
            log.info("No backfill running for manager {}", managerId);
        }
    }

    public UUID startBackfillForDeployedModel(Long managerId) {
        if (managerId == null) throw new IllegalArgumentException("managerId is required");
        if (managerJobIds.containsKey(managerId)) return managerJobIds.get(managerId);

        ModelML model = modelProvider.getDeployedModelEntity(managerId)
                .orElseThrow(() -> new IllegalStateException("No model deployed for manager " + managerId));

        UUID jobId = UUID.randomUUID();
        managerJobIds.put(managerId, jobId);

        long totalUnscored = transactionRepository.countUnscoredTransactionsByManagerId(managerId);
        int totalUnscoredInt = Math.toIntExact(totalUnscored);
        scoringJobService.createJob(jobId, totalUnscoredInt);

        log.info("Backfill job {} created for model '{}' ({} transactions) for manager {}",
                jobId, model.getModelName(), totalUnscored, managerId);

        self.runBackfillOptimized(jobId, managerId);
        return jobId;
    }

    // Core backfill logic – no @Transactional, uses exact deployment ID
    @Async
    protected void runBackfillOptimized(UUID jobId, Long managerId) {
        try {
            // Get the active deployment ID at the start (this is the deployment we are backing)
            Long deploymentId = deploymentHistoryRepository
                    .findFirstByStatusAndDeployedBy_IdOrderByDeploymentDateDesc("ACTIVE", managerId)
                    .map(com.fraud.backend.data.entity.frauddetection.ModelDeploymentHistory::getId)
                    .orElse(null);
            if (deploymentId == null) {
                log.warn("No active deployment found for manager {} before start. Aborting.", managerId);
                scoringJobService.markFailed(jobId, "No active deployment at start");
                return;
            }

            ModelML model = modelProvider.getDeployedModelEntity(managerId)
                    .orElseThrow(() -> new IllegalStateException("No model deployed for manager " + managerId));

            log.info("========================================");
            log.info("STARTING BACKFILL JOB {} | Manager: {} | Model: {} | Deployment ID: {}",
                    jobId, managerId, model.getModelName(), deploymentId);
            log.info("========================================");

            List<UUID> unscoredUuids = transactionRepository.findUnscoredTransactionUuidsByManagerId(managerId);
            ConcurrentLinkedQueue<UUID> transactionQueue = new ConcurrentLinkedQueue<>(unscoredUuids);
            int totalUnscored = transactionQueue.size();
            if (totalUnscored == 0) {
                log.info("No unscored transactions found. Backfill completed.");
                scoringJobService.markCompleted(jobId);
                return;
            }

            List<UUID> failedTransactions = new ArrayList<>();
            List<UUID> batch = new ArrayList<>(batchSize);
            int batchNumber = 0;
            long totalProcessingStart = System.currentTimeMillis();

            // Helper to check if this specific deployment is still active
            java.util.function.BooleanSupplier deploymentActive = () ->
                deploymentHistoryRepository.findById(deploymentId)
                    .map(d -> "ACTIVE".equals(d.getStatus()))
                    .orElse(false);

            while (!transactionQueue.isEmpty() && deploymentActive.getAsBoolean()) {
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
                            transactionScoringService.scoreBatch(batch, model.getModelUuid());

                    long alertsCreated = 0;
                    int processedInBatch = 0;
                    for (TransactionScoringService.ScoreResult result : results) {
                        if (result.getAlert() != null) {
                            scoringJobService.addResult(jobId, result.getAlert());
                            alertsCreated++;
                        }
                        scoringJobService.incrementProcessed(jobId);
                        processedInBatch++;

                        // Mid‑batch check every 10 transactions
                        if (processedInBatch % 10 == 0 && !deploymentActive.getAsBoolean()) {
                            log.warn("Deployment {} became inactive mid-batch – stopping after {} transactions",
                                    deploymentId, processedInBatch);
                            break;
                        }
                    }

                    long batchDuration = System.currentTimeMillis() - batchStartTime;
                    double tps = (batch.size() * 1000.0) / Math.max(1, batchDuration);
                    log.info("Batch {} completed in {}ms ({} txs, {} alerts, {:.1f} tx/s)",
                            batchNumber, batchDuration, batch.size(), alertsCreated, tps);

                } catch (Exception e) {
                    log.error("Batch {} failed – will retry later", batchNumber, e);
                    failedTransactions.addAll(batch);
                }

                // After batch, re-check
                if (!deploymentActive.getAsBoolean()) {
                    log.warn("Deployment {} became inactive after batch {}. Stopping.", deploymentId, batchNumber);
                    break;
                }
            }

            // Second pass for failed transactions (only if still active)
            if (!failedTransactions.isEmpty() && deploymentActive.getAsBoolean()) {
                log.info("Retrying {} failed transactions", failedTransactions.size());
                try {
                    List<TransactionScoringService.ScoreResult> results =
                            transactionScoringService.scoreBatch(failedTransactions, model.getModelUuid());
                    for (TransactionScoringService.ScoreResult result : results) {
                        if (result.getAlert() != null) scoringJobService.addResult(jobId, result.getAlert());
                        scoringJobService.incrementProcessed(jobId);
                    }
                } catch (Exception e) {
                    log.error("Retry failed for {} transactions", failedTransactions.size(), e);
                    for (UUID uuid : failedTransactions) {
                        scoringJobService.addError(jobId, uuid, "Failed on retry");
                        scoringJobService.incrementProcessed(jobId);
                    }
                }
            }

            long totalDuration = System.currentTimeMillis() - totalProcessingStart;
            if (!deploymentActive.getAsBoolean()) {
                log.warn("Backfill job {} STOPPED because deployment {} is no longer active", jobId, deploymentId);
                scoringJobService.markFailed(jobId, "Stopped due to model undeployment");
            } else {
                scoringJobService.markCompleted(jobId);
                log.info("========================================");
                log.info("BACKFILL JOB {} COMPLETED SUCCESSFULLY!", jobId);
                log.info("Total processed: {} | Duration: {}ms",
                        scoringJobService.getJob(jobId).getProcessedCount(), totalDuration);
                log.info("========================================");
            }

        } catch (Exception e) {
            log.error("Backfill job {} failed", jobId, e);
            scoringJobService.markFailed(jobId, e.getMessage());
        } finally {
            managerJobIds.remove(managerId, jobId);
        }
    }
}
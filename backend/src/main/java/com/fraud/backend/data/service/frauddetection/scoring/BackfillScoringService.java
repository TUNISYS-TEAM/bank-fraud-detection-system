package com.fraud.backend.data.service.frauddetection.scoring;

import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.repository.corebanking.TransactionRepository;
import com.fraud.backend.data.repository.frauddetection.ModelDeploymentHistoryRepository;
import com.fraud.backend.data.service.accessmanagement.UserService;
import com.fraud.backend.data.service.frauddetection.training.ModelProvider;
import com.fraud.backend.data.event.ModelDeploymentEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class BackfillScoringService {

    private final TransactionRepository transactionRepository;
    private final ModelProvider modelProvider;
    private final TransactionScoringService transactionScoringService;
    private final ScoringJobService scoringJobService;
    private final ModelDeploymentHistoryRepository deploymentHistoryRepository;
    private final UserService userService;
    private final MeterRegistry meterRegistry;

    @Value("${fraud.backfill.batch-size:100}")
    private int batchSize;

    @Value("${fraud.backfill.timeout-hours:2}")
    private int timeoutHours;

    private final BackfillScoringService self;

    private final Map<Long, Object> managerLocks = new ConcurrentHashMap<>();
    private final Map<Long, UUID> managerToJobId = new ConcurrentHashMap<>();

    private final Timer batchTimer;
    private final AtomicLong activeBackfillsGauge;

    public BackfillScoringService(
            TransactionRepository transactionRepository,
            ModelProvider modelProvider,
            TransactionScoringService transactionScoringService,
            ScoringJobService scoringJobService,
            ModelDeploymentHistoryRepository deploymentHistoryRepository,
            UserService userService,
            MeterRegistry meterRegistry,
            @Lazy BackfillScoringService self) {
        this.transactionRepository = transactionRepository;
        this.modelProvider = modelProvider;
        this.transactionScoringService = transactionScoringService;
        this.scoringJobService = scoringJobService;
        this.deploymentHistoryRepository = deploymentHistoryRepository;
        this.userService = userService;
        this.meterRegistry = meterRegistry;
        this.self = self;

        this.batchTimer = Timer.builder("backfill.batch.processing")
                .description("Time to process a batch of transactions")
                .register(meterRegistry);
        AtomicLong gaugeValue = new AtomicLong(0);
        meterRegistry.gauge("backfill.active.count", gaugeValue);
        this.activeBackfillsGauge = gaugeValue;
    }

    // ------------------------------------------------------------------------
    // Crash recovery
    // ------------------------------------------------------------------------

    @PostConstruct
    public void recoverRunningJobs() {
        if (!managerToJobId.isEmpty()) {
            log.warn("Found {} stale job references after restart. Clearing them.", managerToJobId.size());
            managerToJobId.clear();
        }
        log.info("BackfillScoringService started.");
    }

    // ------------------------------------------------------------------------
    // Event listener
    // ------------------------------------------------------------------------

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onModelDeployment(ModelDeploymentEvent event) {
        Long managerId = event.getManagerId();
        if (managerId == null) {
            log.warn("Deployment event without managerId – ignoring");
            return;
        }

        AppUser manager;
        try {
            manager = userService.getById(managerId);
        } catch (Exception e) {
            log.warn("Deployment event for non-existent manager {} – ignoring", managerId);
            return;
        }

        if (!"MANAGER".equalsIgnoreCase(manager.getRole().getName())) {
            log.warn("Deployment event ignored for non-manager role: {}", manager.getRole().getName());
            return;
        }

        log.info("Received ModelDeploymentEvent: {} for model {} for manager {}",
                event.getAction(), event.getModelUuid(), managerId);

        if (event.getAction() == ModelDeploymentEvent.Action.DEPLOYED) {
            stopBackfillForManager(managerId);
            tryStartBackfillIfNeeded(managerId);
        } else if (event.getAction() == ModelDeploymentEvent.Action.UNDEPLOYED) {
            stopBackfillForManager(managerId);
        }
    }

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    public boolean isBackfillRunning() {
        Long managerId = getCurrentManagerId();
        if (managerId == null) return false;
        UUID jobId = managerToJobId.get(managerId);
        if (jobId == null) return false;
        var job = scoringJobService.getJob(jobId);
        return job != null && "RUNNING".equals(job.getStatus());
    }

    public UUID getCurrentBackfillJobId() {
        Long managerId = getCurrentManagerId();
        return managerId != null ? managerToJobId.get(managerId) : null;
    }

    public void tryStartBackfillIfNeeded(Long managerId) {
        if (managerId == null) {
            log.info("No managerId – backfill requires a valid manager. Skipping.");
            return;
        }

        Object lock = managerLocks.computeIfAbsent(managerId, k -> new Object());
        synchronized (lock) {
            if (managerToJobId.containsKey(managerId)) {
                log.info("Backfill already running for manager {}", managerId);
                return;
            }

            if (modelProvider.getDeployedModelEntity(managerId).isEmpty()) {
                log.info("No deployed model for manager {}.", managerId);
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
    }

    public void stopBackfillForManager(Long managerId) {
        if (managerId == null) return;
        UUID jobId = managerToJobId.remove(managerId);
        if (jobId != null) {
            scoringJobService.markFailed(jobId, "Stopped by user or system");
            log.info("Stopped backfill job {} for manager {}", jobId, managerId);
        }
        managerLocks.remove(managerId);
    }

    @Transactional
    public UUID startBackfillForDeployedModel(Long managerId) {
        if (managerId == null) throw new IllegalArgumentException("managerId is required");

        Object lock = managerLocks.computeIfAbsent(managerId, k -> new Object());
        synchronized (lock) {
            if (managerToJobId.containsKey(managerId)) {
                return managerToJobId.get(managerId);
            }

            ModelML model = modelProvider.getDeployedModelEntity(managerId)
                    .orElseThrow(() -> new IllegalStateException("No model deployed for manager " + managerId));

            long totalUnscored = transactionRepository.countUnscoredTransactionsByManagerId(managerId);
            UUID jobId = UUID.randomUUID();
            scoringJobService.createJob(jobId, Math.toIntExact(totalUnscored));
            managerToJobId.put(managerId, jobId);

            log.info("Backfill job {} created for model '{}' ({} transactions) for manager {}",
                    jobId, model.getModelName(), totalUnscored, managerId);

            activeBackfillsGauge.incrementAndGet();
            self.runBackfill(jobId, managerId);
            return jobId;
        }
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

    // ------------------------------------------------------------------------
    // Async core – batch processing with built-in fallback
    // ------------------------------------------------------------------------

    @Async
    protected void runBackfill(UUID jobId, Long managerId) {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = Duration.ofHours(timeoutHours).toMillis();

        try {
            if (!isJobValidAndRunning(jobId, managerId)) return;

            Long deploymentId = getActiveDeploymentId(managerId);
            if (deploymentId == null) {
                log.warn("No active deployment for manager {} at start. Aborting job {}", managerId, jobId);
                scoringJobService.markFailed(jobId, "No active deployment at start");
                managerToJobId.remove(managerId);
                return;
            }

            ModelML model = modelProvider.getDeployedModelEntity(managerId)
                    .orElseThrow(() -> new IllegalStateException("No model deployed for manager " + managerId));

            log.info("========================================");
            log.info("STARTING BACKFILL JOB {} | Manager: {} | Model: {} | Deployment ID: {}",
                    jobId, managerId, model.getModelName(), deploymentId);
            log.info("========================================");

            int totalProcessed = 0;
            int totalAlerts = 0;

            while (true) {
                // Timeout check
                if (System.currentTimeMillis() - startTime > timeoutMillis) {
                    log.warn("Backfill job {} timed out after {} hours", jobId, timeoutHours);
                    scoringJobService.markFailed(jobId, "Timeout");
                    break;
                }

                // Deployment still active?
                if (!isDeploymentActive(deploymentId)) {
                    log.warn("Deployment {} became inactive. Stopping job {}", deploymentId, jobId);
                    scoringJobService.markFailed(jobId, "Model undeployed");
                    break;
                }

                // Fetch next batch
                var txPage = transactionRepository.findUnscoredTransactionUuidsByManagerId(
                        managerId, PageRequest.of(0, batchSize));
                if (txPage.isEmpty()) {
                    break;
                }

                List<UUID> batch = txPage.getContent();
                Timer.Sample batchSample = Timer.start(meterRegistry);

                // scoreBatch gère déjà le fallback individuel en cas d'échec
                List<TransactionScoringService.ScoreResult> results =
                        transactionScoringService.scoreBatch(batch, model.getModelUuid());

                int batchProcessed = 0;
                int batchAlerts = 0;
                for (TransactionScoringService.ScoreResult result : results) {
                    if (result.getAlert() != null) {
                        scoringJobService.addResult(jobId, result.getAlert());
                        batchAlerts++;
                    }
                    scoringJobService.incrementProcessed(jobId);
                    batchProcessed++;
                }

                totalProcessed += batchProcessed;
                totalAlerts += batchAlerts;

                long batchDuration = batchSample.stop(batchTimer);
                log.info("batch_completed job={} txs={} processed={} alerts={} duration_ms={}",
                        jobId, batch.size(), batchProcessed, batchAlerts, batchDuration);
            }

            long totalDuration = System.currentTimeMillis() - startTime;
            var finalStatus = scoringJobService.getJob(jobId);
            if (finalStatus != null && "RUNNING".equals(finalStatus.getStatus())) {
                scoringJobService.markCompleted(jobId);
                log.info("backfill_completed job={} manager={} total_txs={} total_alerts={} duration_ms={}",
                        jobId, managerId, totalProcessed, totalAlerts, totalDuration);
            } else {
                log.info("backfill_stopped job={} manager={} reason={}",
                        jobId, managerId, finalStatus == null ? "job missing" : finalStatus.getErrorMessage());
            }

        } catch (Exception e) {
            log.error("backfill_failed job={} manager={}", jobId, managerId, e);
            scoringJobService.markFailed(jobId, "Unexpected error: " + e.getMessage());
        } finally {
            managerToJobId.remove(managerId, jobId);
            managerLocks.remove(managerId);
            activeBackfillsGauge.decrementAndGet();
        }
    }

    // ------------------------------------------------------------------------
    // Helper methods
    // ------------------------------------------------------------------------

    private boolean isJobValidAndRunning(UUID jobId, Long managerId) {
        var jobStatus = scoringJobService.getJob(jobId);
        if (jobStatus == null || !"RUNNING".equals(jobStatus.getStatus())) {
            log.warn("Job {} is not RUNNING (status={}), aborting", jobId,
                    jobStatus == null ? "null" : jobStatus.getStatus());
            managerToJobId.remove(managerId, jobId);
            return false;
        }

        AppUser manager;
        try {
            manager = userService.getById(managerId);
        } catch (Exception e) {
            log.warn("Manager {} no longer exists, aborting job {}", managerId, jobId);
            scoringJobService.markFailed(jobId, "Manager deleted");
            managerToJobId.remove(managerId);
            return false;
        }
        if (!"MANAGER".equalsIgnoreCase(manager.getRole().getName())) {
            log.warn("User {} is no longer a manager, aborting job {}", managerId, jobId);
            scoringJobService.markFailed(jobId, "Manager role revoked");
            managerToJobId.remove(managerId);
            return false;
        }
        return true;
    }

    private Long getActiveDeploymentId(Long managerId) {
        return deploymentHistoryRepository
                .findFirstByStatusAndDeployedBy_IdOrderByDeploymentDateDesc("ACTIVE", managerId)
                .map(com.fraud.backend.data.entity.frauddetection.ModelDeploymentHistory::getId)
                .orElse(null);
    }

    private boolean isDeploymentActive(Long deploymentId) {
        return deploymentHistoryRepository.findById(deploymentId)
                .map(d -> "ACTIVE".equals(d.getStatus()))
                .orElse(false);
    }
}
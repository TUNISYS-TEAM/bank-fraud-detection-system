package com.fraud.backend.api.controller.frauddetection;

import com.fraud.backend.api.dto.frauddetection.model.request.DeployModelRequest;
import com.fraud.backend.api.dto.frauddetection.model.response.DeploymentResponse;
import com.fraud.backend.api.dto.frauddetection.model.response.ModelSummaryResponse;
import com.fraud.backend.api.dto.frauddetection.model.response.ScoringJobStatus;
import com.fraud.backend.api.dto.frauddetection.model.response.ScoringJobStatusResponse;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.accessmanagement.KeycloakMapping;
import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import com.fraud.backend.data.repository.accessmanagement.KeycloakMappingRepository;
import com.fraud.backend.data.service.frauddetection.deployment.ModelDeploymentService;
import com.fraud.backend.data.service.frauddetection.monitoring.ModelDriftMonitoringService;
import com.fraud.backend.data.service.frauddetection.scoring.BackfillScoringService;
import com.fraud.backend.data.service.frauddetection.scoring.ScoringJobService;
import com.fraud.backend.data.service.frauddetection.scoring.TransactionScoringService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;


@Slf4j
@RestController
@RequestMapping("/api/v1/fraud-detection/deployment")
@RequiredArgsConstructor
public class ModelDeploymentController {

    private final ModelDeploymentService deploymentService;
    private final TransactionScoringService transactionScoringService;
    private final BackfillScoringService backfillScoringService;
    private final ScoringJobService scoringJobService;
    private final ModelDriftMonitoringService driftMonitoringService;
    private final KeycloakMappingRepository keycloakMappingRepository;

    private AppUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("User not authenticated");
        }

        Object principal = authentication.getPrincipal();
        String email = null;

        if (principal instanceof Jwt jwt) {
            email = jwt.getClaim("preferred_username");
            if (email == null) email = jwt.getClaim("email");
        } else if (principal instanceof org.springframework.security.core.userdetails.UserDetails userDetails) {
            email = userDetails.getUsername();
        } else if (principal instanceof String s) {
            email = s;
        }

        if (email == null) email = authentication.getName();

        return keycloakMappingRepository.findByEmail(email)
                .map(KeycloakMapping::getUser)
                .orElse(AppUser.builder()
                        .id(1L)
                        .email(email)
                        .firstName("Admin")
                        .lastName("User")
                        .build());
    }

    /**
     * Get all validated models (available for deployment) - Returns DTOs
     */
    @GetMapping("/validated-models")
    public ResponseEntity<List<ModelSummaryResponse>> getValidatedModels() {
        log.info("Getting validated models available for deployment");
        return ResponseEntity.ok(deploymentService.getValidatedModels());
    }

    /**
     * Get deployment history
     */
    @GetMapping("/history")
    public ResponseEntity<List<DeploymentResponse>> getDeploymentHistory() {
        log.info("Getting deployment history");
        return ResponseEntity.ok(deploymentService.getDeploymentHistory());
    }

    /**
     * Get currently deployed model summary
     */
    @GetMapping("/active/summary")
    public ResponseEntity<ModelSummaryResponse> getActiveDeploymentSummary() {
        log.info("Getting active deployment summary");
        return deploymentService.getDeployedModelSummary()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get currently deployed model with full details
     */
    @GetMapping("/active")
    public ResponseEntity<DeploymentResponse> getActiveDeployment() {
        log.info("Getting active deployment");
        return deploymentService.getDeployedModelResponse()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.ok().body(null)); 
    }

    /**
     * Deploy a validated model
     */
    @PostMapping("/deploy")
    public ResponseEntity<DeploymentResponse> deployModel(@RequestBody DeployModelRequest request) {
        log.info("Deploying model: {}", request.getModelUuid());
        var deployedBy = getCurrentUser();
        var deployment = deploymentService.deployModel(
                request.getModelUuid(),
                request.getNotes(),
                deployedBy);
        
        // Automatically trigger backfill for existing transactions
        try {
            log.info("Automatically triggering backfill for model: {}", request.getModelUuid());
            backfillScoringService.startBackfillForDeployedModel();
        } catch (Exception e) {
            log.warn("Auto-backfill failed to start: {}. User can manually trigger it.", e.getMessage());
        }
        
        return ResponseEntity.ok(deployment);
    }

    /**
     * Undeploy current model
     */
    @PostMapping("/undeploy")
    public ResponseEntity<Void> undeployModel(@RequestParam UUID modelUuid) {
        log.info("Undeploying model: {}", modelUuid);
        deploymentService.undeployModel(modelUuid);
        return ResponseEntity.ok().build();
    }

    /**
     * Score a single transaction
     */
    @PostMapping("/score/transaction/{transactionUuid}")
    public ResponseEntity<FraudAlert> scoreTransaction(
            @PathVariable UUID transactionUuid,
            @RequestParam(required = false) UUID modelUuid) {
        log.info("Scoring transaction: {}", transactionUuid);
        var alert = transactionScoringService.scoreTransaction(transactionUuid, modelUuid);
        return ResponseEntity.ok(alert);
    }

    /**
     * Score multiple transactions asynchronously
     */
    @PostMapping("/score/batch")
    public ResponseEntity<ScoringJobStatusResponse> scoreBatch(
            @RequestBody List<UUID> transactionUuids,
            @RequestParam(required = false) UUID modelUuid) {
        log.info("Starting batch scoring for {} transactions", transactionUuids.size());
        UUID jobId = UUID.randomUUID();
        
        // Manual async execution since we can't modify services
        scoringJobService.createJob(jobId, transactionUuids.size());
        
        CompletableFuture.runAsync(() -> {
            try {
                for (UUID txUuid : transactionUuids) {
                    try {
                        transactionScoringService.scoreTransaction(txUuid, modelUuid);
                        scoringJobService.incrementProcessed(jobId);
                    } catch (Exception e) {
                        log.error("Batch scoring failed for transaction {}: {}", txUuid, e.getMessage());
                        scoringJobService.addError(jobId, txUuid, e.getMessage());
                        scoringJobService.incrementProcessed(jobId);
                    }
                }
                scoringJobService.markCompleted(jobId);
            } catch (Exception e) {
                log.error("Batch scoring job {} failed: {}", jobId, e.getMessage());
                scoringJobService.markFailed(jobId, e.getMessage());
            }
        });

        var response = ScoringJobStatusResponse.builder()
                .jobId(jobId)
                .status("RUNNING")
                .message("Scoring job in progress")
                .build();
        return ResponseEntity.accepted().body(response);
    }

    /**
     * Get scoring job status
     */
    @GetMapping("/score/status/{jobId}")
    public ResponseEntity<ScoringJobStatus> getScoringStatus(@PathVariable UUID jobId) {
        log.info("Getting scoring job status: {}", jobId);
        var status = scoringJobService.getJob(jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    /**
     * Process all existing transactions with the deployed model
     */
    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> startBackfill() {
        if (backfillScoringService.isBackfillRunning()) {
            return ResponseEntity.ok(Map.of(
                    "status", "ALREADY_RUNNING",
                    "jobId", backfillScoringService.getCurrentBackfillJobId(),
                    "message", "Backfill is already in progress"));
        }

        UUID jobId = backfillScoringService.startBackfillForDeployedModel();

        return ResponseEntity.ok(Map.of(
                "status", "STARTED",
                "jobId", jobId,
                "message", "Backfill started. All existing transactions will be processed in the background."));
    }

    /**
     * Check if backfill is running
     */
    @GetMapping("/backfill/status")
    public ResponseEntity<Map<String, Object>> getBackfillStatus() {
        return ResponseEntity.ok(Map.of(
                "isRunning", backfillScoringService.isBackfillRunning(),
                "jobId", backfillScoringService.getCurrentBackfillJobId() != null ? 
                         backfillScoringService.getCurrentBackfillJobId() : "NONE"));
    }

    /**
     * Get model drift status
     */
    @GetMapping("/drift/status")
    public ResponseEntity<Map<String, Object>> getDriftStatus() {
        log.info("Getting model drift status");
        return ResponseEntity.ok(driftMonitoringService.getDriftStatus());
    }

    /**
     * Trigger manual drift check
     */
    @PostMapping("/drift/check")
    public ResponseEntity<Map<String, Object>> checkDrift() {
        log.info("Triggering manual drift check");
        return ResponseEntity.ok(driftMonitoringService.checkModelDrift());
    }
}
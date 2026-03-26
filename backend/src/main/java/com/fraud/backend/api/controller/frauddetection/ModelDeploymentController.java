package com.fraud.backend.api.controller.frauddetection;

import com.fraud.backend.api.dto.frauddetection.model.request.DeployModelRequest;
import com.fraud.backend.api.dto.frauddetection.model.response.DeploymentResponse;
import com.fraud.backend.api.dto.frauddetection.model.response.ModelSummaryResponse;
import com.fraud.backend.api.dto.frauddetection.model.response.ScoringJobStatusResponse;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import com.fraud.backend.data.service.frauddetection.ModelDeploymentService;
import com.fraud.backend.data.service.frauddetection.ScoringJobStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/fraud-detection/deployment")
@RequiredArgsConstructor
public class ModelDeploymentController {

    private final ModelDeploymentService deploymentService;

    private AppUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("User not authenticated");
        }
        // Replace with actual user retrieval from your service
        return AppUser.builder()
                .id(1L)
                .email("admin@fraud.com")
                .firstName("Admin")
                .lastName("User")
                .build();
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
        var alert = deploymentService.scoreTransaction(transactionUuid, modelUuid);
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
        deploymentService.scoreTransactionsAsync(transactionUuids, modelUuid, jobId);

        var response = ScoringJobStatusResponse.builder()
                .jobId(jobId)
                .status("SUBMITTED")
                .message("Scoring job submitted")
                .build();
        return ResponseEntity.accepted().body(response);
    }

    /**
     * Get scoring job status
     */
    @GetMapping("/score/status/{jobId}")
    public ResponseEntity<ScoringJobStatus> getScoringStatus(@PathVariable UUID jobId) {
        log.info("Getting scoring job status: {}", jobId);
        var status = deploymentService.getScoringJobStatus(jobId);
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
        if (deploymentService.isBackfillRunning()) {
            return ResponseEntity.ok(Map.of(
                    "status", "ALREADY_RUNNING",
                    "jobId", deploymentService.getCurrentBackfillJobId(),
                    "message", "Backfill is already in progress"));
        }

        deploymentService.processAllExistingTransactions();

        return ResponseEntity.ok(Map.of(
                "status", "STARTED",
                "jobId", deploymentService.getCurrentBackfillJobId(),
                "message", "Backfill started. All existing transactions will be processed in the background."));
    }

    /**
     * Check if backfill is running
     */
    @GetMapping("/backfill/status")
    public ResponseEntity<Map<String, Object>> getBackfillStatus() {
        return ResponseEntity.ok(Map.of(
                "isRunning", deploymentService.isBackfillRunning(),
                "jobId", deploymentService.getCurrentBackfillJobId()));
    }
}
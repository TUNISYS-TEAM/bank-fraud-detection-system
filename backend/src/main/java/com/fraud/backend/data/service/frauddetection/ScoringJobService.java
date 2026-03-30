package com.fraud.backend.data.service.frauddetection;

import com.fraud.backend.api.dto.frauddetection.model.response.ScoringJobStatus;
import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ScoringJobService {

    private final Map<UUID, ScoringJobStatus> activeJobs = new ConcurrentHashMap<>();

    public ScoringJobStatus createJob(UUID jobId, int totalTransactions) {
        ScoringJobStatus job = new ScoringJobStatus();
        job.setJobId(jobId);
        job.setStatus("RUNNING");
        job.setTotalTransactions(Math.max(totalTransactions, 0));
        job.setProcessedCount(0);
        job.setProgress(0.0);
        job.setStartTime(LocalDateTime.now());

        activeJobs.put(jobId, job);
        return job;
    }

    public ScoringJobStatus getJob(UUID jobId) {
        return activeJobs.get(jobId);
    }

    public boolean exists(UUID jobId) {
        return activeJobs.containsKey(jobId);
    }

    public void incrementProcessed(UUID jobId) {
        ScoringJobStatus job = activeJobs.get(jobId);
        if (job == null) {
            return;
        }

        int processed = Math.min(job.getProcessedCount() + 1, job.getTotalTransactions());
        job.setProcessedCount(processed);
        updateProgress(job);
    }

    public void addResult(UUID jobId, FraudAlert alert) {
        ScoringJobStatus job = activeJobs.get(jobId);
        if (job == null || alert == null) {
            return;
        }
        job.addResult(alert);
    }

    public void addError(UUID jobId, UUID transactionUuid, String error) {
        ScoringJobStatus job = activeJobs.get(jobId);
        if (job == null || transactionUuid == null || error == null) {
            return;
        }
        job.addError(transactionUuid, error);
    }

    public void markCompleted(UUID jobId) {
        ScoringJobStatus job = activeJobs.get(jobId);
        if (job == null) {
            return;
        }

        job.setProcessedCount(job.getTotalTransactions());
        job.setProgress(100.0);
        job.setStatus("COMPLETED");
        job.setEndTime(LocalDateTime.now());
    }

    public void markFailed(UUID jobId, String errorMessage) {
        ScoringJobStatus job = activeJobs.get(jobId);
        if (job == null) {
            return;
        }

        updateProgress(job);
        job.setStatus("FAILED");
        job.setErrorMessage(errorMessage);
        job.setEndTime(LocalDateTime.now());
    }

    public void removeJob(UUID jobId) {
        activeJobs.remove(jobId);
    }

    private void updateProgress(ScoringJobStatus job) {
        if (job.getTotalTransactions() <= 0) {
            job.setProgress(0.0);
            return;
        }

        job.setProgress((job.getProcessedCount() * 100.0) / job.getTotalTransactions());
    }
}
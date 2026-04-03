package com.fraud.backend.config;

import com.fraud.backend.data.service.frauddetection.scoring.BackfillScoringService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BackfillStartupRunner implements ApplicationRunner {

    private final BackfillScoringService backfillScoringService;

    public BackfillStartupRunner(BackfillScoringService backfillScoringService) {
        this.backfillScoringService = backfillScoringService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== Application fully started - Running Backfill Startup Check ===");

        // Small delay to ensure all beans (especially ModelProvider) are ready
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        backfillScoringService.tryStartBackfillIfNeeded();
    }
}
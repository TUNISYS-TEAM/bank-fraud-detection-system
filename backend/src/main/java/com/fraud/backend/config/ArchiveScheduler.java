package com.fraud.backend.config;

import com.fraud.backend.data.service.accessmanagement.archive.ArchiveJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveScheduler {

    private final ArchiveJobService archiveJobService;

    // Runs every day at 04:44:00 (UTC Server Time).
    @Scheduled(cron = "0 44 4 * * *")
    public void runScheduledArchive() {
        log.info("Starting scheduled archive job at 04:44 UTC...");
        try {
            archiveJobService.runAuditLogArchive();
            archiveJobService.runUserSessionArchive();
            log.info("Scheduled archive job completed successfully.");
        } catch (Exception e) {
            log.error("Error during scheduled archive job", e);
        }
    }
}
package com.fraud.backend.data.service.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.SystemSetting;
import com.fraud.backend.data.repository.accessmanagement.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmailSettingsService {

    private final SystemSettingRepository systemSettingRepository;

    /**
     * Check if email notifications are enabled (excluding OTP)
     */
    public boolean isEmailNotificationsEnabled() {
        return systemSettingRepository.findById("email_notifications_enabled")
                .map(s -> "true".equalsIgnoreCase(s.getValue()))
                .orElse(true);
    }

    /**
     * Check if critical alerts emails are enabled
     */
    public boolean isCriticalAlertsEnabled() {
        return systemSettingRepository.findById("critical_alerts")
                .map(s -> "true".equalsIgnoreCase(s.getValue()))
                .orElse(true);
    }

    /**
     * Check if model drift emails are enabled
     */
    public boolean isModelDriftEnabled() {
        return systemSettingRepository.findById("model_drift")
                .map(s -> "true".equalsIgnoreCase(s.getValue()))
                .orElse(true);
    }

    /**
     * Check if version update emails are enabled
     */
    public boolean isVersionUpdatesEnabled() {
        return systemSettingRepository.findById("version_updates")
                .map(s -> "true".equalsIgnoreCase(s.getValue()))
                .orElse(false);
    }

    /**
     * Update email notification setting
     */
    @Transactional
    public void updateSetting(String key, String value) {
        SystemSetting setting = systemSettingRepository.findById(key)
                .orElseGet(() -> createNewSetting(key, value));
        
        setting.setValue(value);
        setting.setUpdatedAt(LocalDateTime.now());
        systemSettingRepository.save(setting);
        log.info("Email setting updated: {} = {}", key, value);
    }

    private SystemSetting createNewSetting(String key, String value) {
        SystemSetting newSetting = SystemSetting.builder()
                .key(key)
                .value(value)
                .description(getDefaultDescription(key))
                .updatedAt(LocalDateTime.now())
                .build();
        return newSetting;
    }

    private String getDefaultDescription(String key) {
        return switch (key) {
            case "email_notifications_enabled" -> "Enable/disable all email notifications (OTP always active)";
            case "critical_alerts" -> "Send email for critical fraud alerts";
            case "model_drift" -> "Send email when model drift is detected";
            case "version_updates" -> "Send email for system version updates";
            default -> "Email notification setting";
        };
    }
}
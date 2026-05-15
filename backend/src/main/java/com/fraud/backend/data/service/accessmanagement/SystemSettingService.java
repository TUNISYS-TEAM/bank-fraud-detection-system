package com.fraud.backend.data.service.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.SystemSetting;
import com.fraud.backend.data.repository.accessmanagement.SystemSettingRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SystemSettingService {

    private final SystemSettingRepository repository;
    private final KeycloakService keycloakService;  // No circular dependency now

    public List<SystemSetting> getAll() {
        return repository.findAll();
    }

    public SystemSetting getByKey(String key) {
        return repository.findById(key)
                .orElseThrow(() -> new EntityNotFoundException("Setting not found: " + key));
    }

    public boolean isAuditLogEnabled() {
        return repository.findById("audit_log_enabled")
                .map(s -> "true".equalsIgnoreCase(s.getValue()))
                .orElse(true);
    }

    @Transactional
    public SystemSetting update(String key, String value) {
        SystemSetting setting = repository.findById(key)
                .orElseThrow(() -> new EntityNotFoundException("Setting not found: " + key));

        setting.setValue(value);
        setting.setUpdatedAt(LocalDateTime.now());
        SystemSetting saved = repository.save(setting);

        // Sync to Keycloak based on key
        syncToKeycloak(key, value);

        return saved;
    }

    @Transactional
    public void setAuditLogEnabled(boolean enabled) {
        update("audit_log_enabled", enabled ? "true" : "false");
    }

    private void syncToKeycloak(String key, String value) {
        try {
            switch (key) {
                case "session_timeout" ->
                    keycloakService.updateSessionTimeout(Integer.parseInt(value));
                case "max_login_attempts" ->
                    keycloakService.updateMaxLoginAttempts(Integer.parseInt(value));
                case "password_expiry_days" ->
                    keycloakService.updatePasswordExpiryDays(Integer.parseInt(value));
                default -> log.debug("No Keycloak sync needed for setting: {}", key);
            }
        } catch (Exception e) {
            log.error("Failed to sync setting '{}' to Keycloak: {}", key, e.getMessage());
        }
    }
}
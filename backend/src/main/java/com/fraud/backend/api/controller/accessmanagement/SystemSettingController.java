package com.fraud.backend.api.controller.accessmanagement;

import com.fraud.backend.api.dto.accessmanagement.settings.SystemSettingRequest;
import com.fraud.backend.api.dto.accessmanagement.settings.SystemSettingResponse;
import com.fraud.backend.data.entity.accessmanagement.SystemSetting;
import com.fraud.backend.data.service.accessmanagement.SystemSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SystemSettingController {

    private final SystemSettingService systemSettingService;

    // GET /api/settings
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<SystemSettingResponse>> getAll() {
        return ResponseEntity.ok(
                systemSettingService.getAll().stream()
                        .map(this::toResponse)
                        .toList());
    }

    // GET /api/settings/{key}
    @GetMapping("/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SystemSettingResponse> getByKey(@PathVariable String key) {
        return ResponseEntity.ok(toResponse(systemSettingService.getByKey(key)));
    }

    // PATCH /api/settings/{key}
    @PatchMapping("/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SystemSettingResponse> update(
            @PathVariable String key,
            @RequestBody SystemSettingRequest request) {
        return ResponseEntity.ok(toResponse(systemSettingService.update(key, request.getValue())));
    }

    // GET /api/settings/audit-log
    @GetMapping("/audit-log")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Boolean>> getAuditLogStatus() {
        return ResponseEntity.ok(Map.of("enabled", systemSettingService.isAuditLogEnabled()));
    }

    // PATCH /api/settings/audit-log
    @PatchMapping("/audit-log")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Boolean>> setAuditLogStatus(
            @RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        systemSettingService.setAuditLogEnabled(enabled);
        return ResponseEntity.ok(Map.of("enabled", enabled));
    }

    private SystemSettingResponse toResponse(SystemSetting s) {
        return SystemSettingResponse.builder()
                .key(s.getKey())
                .value(s.getValue())
                .description(s.getDescription())
                .updatedAt(s.getUpdatedAt())
                .build();
    }
}
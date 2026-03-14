package com.fraud.backend.api.controller.accessmanagement;

import com.fraud.backend.api.dto.accessmanagement.archive.ArchiveObjectMapper;
import com.fraud.backend.api.dto.accessmanagement.archive.request.ArchiveObjectCreateRequestDto;
import com.fraud.backend.api.dto.accessmanagement.archive.request.ArchiveObjectStatusUpdateDto;
import com.fraud.backend.api.dto.accessmanagement.archive.response.ArchiveObjectResponseDto;
import com.fraud.backend.data.entity.accessmanagement.ArchiveObject;
import com.fraud.backend.data.enums.archive.ArchiveStatus;
import com.fraud.backend.data.enums.archive.ArchiveType;
import com.fraud.backend.data.service.accessmanagement.archive.ArchiveJobService;
import com.fraud.backend.data.service.accessmanagement.archive.ArchiveObjectService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/archives")
@RequiredArgsConstructor
public class ArchiveObjectController {

    private final ArchiveObjectService archiveService;
    private final ArchiveJobService archiveJobService;

    @GetMapping
    public Page<ArchiveObjectResponseDto> list(
            @RequestParam(required = false) ArchiveType type,
            @RequestParam(required = false) ArchiveStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return archiveService.list(type, status, pageable).map(ArchiveObjectMapper::toDto);
    }

    @GetMapping("/{id}")
    public ArchiveObjectResponseDto get(@PathVariable Long id) {
        return ArchiveObjectMapper.toDto(archiveService.get(id));
    }

    @PostMapping
    public ArchiveObjectResponseDto create(@Valid @RequestBody ArchiveObjectCreateRequestDto req) {
        ArchiveObject entity = ArchiveObjectMapper.toEntity(req);
        ArchiveObject saved = archiveService.create(entity);
        return ArchiveObjectMapper.toDto(saved);
    }

    @PatchMapping("/{id}/status")
    public ArchiveObjectResponseDto updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody ArchiveObjectStatusUpdateDto req) {
        ArchiveObject updated = archiveService.updateStatus(id, req.getStatus());
        return ArchiveObjectMapper.toDto(updated);
    }

    @PostMapping("/run/audit-logs")
    public ArchiveObjectResponseDto runAuditLogs() {
        ArchiveObject ao = archiveJobService.runAuditLogArchive();

        if (ao == null) {
            throw new ResponseStatusException(
                    HttpStatus.NO_CONTENT,
                    "No audit logs to archive");
        }
        return ArchiveObjectMapper.toDto(ao);
    }

    @PostMapping("/run/user-sessions")
    public ArchiveObjectResponseDto runUserSessions() {
        ArchiveObject ao = archiveJobService.runUserSessionArchive();

        if (ao == null) {
            throw new ResponseStatusException(
                    HttpStatus.NO_CONTENT,
                    "No user sessions to archive");
        }
        return ArchiveObjectMapper.toDto(ao);
    }

    @GetMapping("/diagnostics")
    public java.util.Map<String, Object> diagnostics() {
        java.util.Map<String, Object> diag = new java.util.HashMap<>();
        diag.put("serverTimeLocal", java.time.LocalDateTime.now().toString());
        diag.put("serverTimeUTC", java.time.Instant.now().toString());
        diag.put("timezone", java.util.TimeZone.getDefault().getID());

        // Check retention settings
        Double retention = archiveJobService.getDoubleSetting("retention_days", 90.0);
        diag.put("retentionDays", retention);
        diag.put("retentionMinutes", retention * 1440.0);
        diag.put("archiveEnabled", archiveJobService.getBooleanSetting("archive_enabled", true));

        return diag;
    }

    @PostMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.OK)
    public void restore(@PathVariable Long id) {
        archiveJobService.restoreFromArchive(id);
    }

    @GetMapping("/{id}/preview")
    public java.util.Map<String, Object> preview(@PathVariable Long id) {
        return archiveJobService.getArchivePreview(id);
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAll() {
        archiveService.deleteAllArchives();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        archiveService.deleteArchive(id);
    }
}
package com.fraud.backend.data.service.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.AuditLog;
import com.fraud.backend.data.repository.accessmanagement.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public Page<AuditLog> getLogs(String actionType, String level, Pageable pageable) {
        if (actionType == null && level == null) {
            return auditLogRepository.findAllByOrderByActionTimestampDesc(pageable);
        }
        return auditLogRepository.findWithFilters(actionType, level, pageable);
    }

    public AuditLog getById(Long id) {
        return auditLogRepository.findById(id)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("AuditLog not found: " + id));
    }

    public List<AuditLog> getAllLogsForExport(String actionType, String level) {
        if (actionType == null && level == null) {
            return auditLogRepository.findAll();
        }
        return auditLogRepository.findWithFiltersNoPaging(actionType, level);
    }
}
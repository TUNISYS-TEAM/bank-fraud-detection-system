package com.fraud.backend.data.service.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.Permission;
import com.fraud.backend.data.repository.accessmanagement.PermissionRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PermissionService {

    private final PermissionRepository permissionRepository;

    public List<Permission> getAllPermissions() {
        return permissionRepository.findAll();
    }

    public Permission getPermissionById(Long id) {
        return permissionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Permission not found: " + id));
    }

    @Transactional
    public Permission createPermission(String name, String description, String action) {
        if (permissionRepository.existsByName(name)) {
            throw new IllegalArgumentException("Permission already exists: " + name);
        }

        Permission permission = Permission.builder()
                .name(name)
                .description(description)
                .action(action)
                .build();

        Permission savedPermission = permissionRepository.save(permission);
        log.info("Permission created successfully: {}", name);
        
        return savedPermission;
    }

    @Transactional
    public Permission updatePermission(Long id, String description, String action) {
        Permission permission = getPermissionById(id);

        permission.setDescription(description);
        permission.setAction(action);

        Permission updatedPermission = permissionRepository.save(permission);
        log.info("Permission updated successfully: {}", permission.getName());
        
        return updatedPermission;
    }

    @Transactional
    public void deletePermission(Long id) {
        Permission permission = getPermissionById(id);
        permissionRepository.delete(permission);
        log.info("Permission deleted: {}", permission.getName());
    }
}
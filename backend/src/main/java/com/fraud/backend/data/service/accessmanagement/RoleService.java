package com.fraud.backend.data.service.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.Permission;
import com.fraud.backend.data.entity.accessmanagement.Role;
import com.fraud.backend.data.entity.accessmanagement.RolePermission;
import com.fraud.backend.data.entity.accessmanagement.RolePermissionId;
import com.fraud.backend.data.repository.accessmanagement.PermissionRepository;
import com.fraud.backend.data.repository.accessmanagement.RolePermissionRepository;
import com.fraud.backend.data.repository.accessmanagement.RoleRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final KeycloakService keycloakService;

    public List<Role> getAllRoles() {
        return roleRepository.findAll();
    }

    public Role getRoleById(Long id) {
        return roleRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Role not found: " + id));
    }

    public Role getRoleByName(String name) {
        return roleRepository.findByName(name)
                .orElseThrow(() -> new EntityNotFoundException("Role not found: " + name));
    }

    /**
     * Récupère les permissions par ID de rôle depuis la table role_permission
     */
    public List<Permission> getPermissionsByRoleId(Long roleId) {
        return rolePermissionRepository.findPermissionsByRoleId(roleId);
    }

    /**
     * Récupère les IDs des permissions par ID de rôle
     */
    public List<Long> getPermissionIdsByRoleId(Long roleId) {
        return rolePermissionRepository.findPermissionIdsByRoleId(roleId);
    }

    @Transactional
    public Role createRole(String name, String description, List<Long> permissionIds) {
        
        if (roleRepository.existsByName(name)) {
            throw new IllegalArgumentException("Role already exists: " + name);
        }

        String keycloakRoleName = keycloakService.createRole(name, description);

        Role role = Role.builder()
                .name(name)
                .description(description)
                .keycloakRoleName(keycloakRoleName)
                .build();

        Role savedRole = roleRepository.save(role);
        
        if (permissionIds != null && !permissionIds.isEmpty()) {
            for (Long permissionId : permissionIds) {
                addPermissionToRole(savedRole.getId(), permissionId);
            }
        }

        log.info("Role created successfully: {} with Keycloak name: {}", name, keycloakRoleName);
        
        return savedRole;
    }

    @Transactional
    public Role updateRole(Long id, String description, List<Long> permissionIds) {
        Role role = getRoleById(id);

        keycloakService.updateRole(role.getKeycloakRoleName(), description);

        role.setDescription(description);
        roleRepository.save(role);
        
        if (permissionIds != null) {
            rolePermissionRepository.deleteByIdRoleId(id);
            for (Long permissionId : permissionIds) {
                addPermissionToRole(id, permissionId);
            }
        }

        log.info("Role updated successfully: {}", role.getName());
        
        return role;
    }

    @Transactional
    public void addPermissionToRole(Long roleId, Long permissionId) {
        if (rolePermissionRepository.existsByIdRoleIdAndIdPermissionId(roleId, permissionId)) {
            log.debug("Permission {} already exists for role {}", permissionId, roleId);
            return;
        }

        Role role = roleRepository.getReferenceById(roleId);
        Permission permission = permissionRepository.getReferenceById(permissionId);
        
        RolePermissionId id = new RolePermissionId(roleId, permissionId);
        RolePermission rolePermission = RolePermission.builder()
                .id(id)
                .role(role)
                .permission(permission)
                .build();
        
        rolePermissionRepository.save(rolePermission);
        log.info("Permission {} added to role {}", permissionId, roleId);
    }

    @Transactional
    public void removePermissionFromRole(Long roleId, Long permissionId) {
        RolePermissionId id = new RolePermissionId(roleId, permissionId);
        rolePermissionRepository.deleteById(id);
        log.info("Permission {} removed from role {}", permissionId, roleId);
    }

    @Transactional
    public void deleteRole(Long id) {
        Role role = getRoleById(id);

        if (Set.of("ADMIN", "MANAGER", "ANALYST").contains(role.getName())) {
            throw new IllegalArgumentException("Cannot delete system role: " + role.getName());
        }

        rolePermissionRepository.deleteByIdRoleId(id);
        keycloakService.deleteRole(role.getKeycloakRoleName());
        roleRepository.delete(role);
        log.info("Role deleted: {}", role.getName());
    }

    @Transactional
    public void syncRolesFromKeycloak() {
        List<RoleRepresentation> keycloakRoles = keycloakService.getAllRoles();
        
        for (RoleRepresentation keycloakRole : keycloakRoles) {
            roleRepository.findByKeycloakRoleName(keycloakRole.getName())
                    .ifPresentOrElse(
                        existingRole -> {
                            existingRole.setDescription(keycloakRole.getDescription());
                            roleRepository.save(existingRole);
                            log.debug("Updated role from Keycloak: {}", keycloakRole.getName());
                        },
                        () -> {
                            Role newRole = Role.builder()
                                    .name(keycloakRole.getName())
                                    .description(keycloakRole.getDescription())
                                    .keycloakRoleName(keycloakRole.getName())
                                    .build();
                            roleRepository.save(newRole);
                            log.info("Created new role from Keycloak: {}", keycloakRole.getName());
                        }
                    );
        }
        
        log.info("Role synchronization completed");
    }
}
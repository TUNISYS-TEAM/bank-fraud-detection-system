package com.fraud.backend.api.controller.accessmanagement;

import com.fraud.backend.api.dto.accessmanagement.permission.response.PermissionResponse;
import com.fraud.backend.api.dto.accessmanagement.permission.PermissionMapper;
import com.fraud.backend.api.dto.accessmanagement.role.request.CreateRoleRequest;
import com.fraud.backend.api.dto.accessmanagement.role.request.UpdateRoleRequest;
import com.fraud.backend.api.dto.accessmanagement.role.response.RoleResponse;
import com.fraud.backend.api.dto.accessmanagement.role.RoleMapper;
import com.fraud.backend.data.entity.accessmanagement.Permission;
import com.fraud.backend.data.entity.accessmanagement.Role;
import com.fraud.backend.data.service.accessmanagement.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;

    // GET /api/roles - Liste des rôles avec leurs permissions
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<RoleResponse>> getAllRoles() {
        log.info("Fetching all roles with permissions");
        
        List<Role> roles = roleService.getAllRoles();
        
        List<RoleResponse> responses = roles.stream()
                .map(role -> {
                    List<Permission> permissions = roleService.getPermissionsByRoleId(role.getId());
                    return roleMapper.toResponseWithPermissions(role, permissions);
                })
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(responses);
    }

    // GET /api/roles/{id} - Rôle spécifique avec ses permissions
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RoleResponse> getRoleById(@PathVariable Long id) {
        log.info("Fetching role with id: {}", id);
        
        Role role = roleService.getRoleById(id);
        List<Permission> permissions = roleService.getPermissionsByRoleId(id);
        
        return ResponseEntity.ok(roleMapper.toResponseWithPermissions(role, permissions));
    }

    // GET /api/roles/name/{name} - Rôle par nom avec ses permissions
    @GetMapping("/name/{name}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER') or hasRole('ANALYST')")
    public ResponseEntity<RoleResponse> getRoleByName(@PathVariable String name) {
        log.info("Fetching role with name: {}", name);
        
        Role role = roleService.getRoleByName(name);
        List<Permission> permissions = roleService.getPermissionsByRoleId(role.getId());
        
        return ResponseEntity.ok(roleMapper.toResponseWithPermissions(role, permissions));
    }

    // GET /api/roles/{roleId}/permissions - Permissions d'un rôle
    @GetMapping("/{roleId}/permissions")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER') or hasRole('ANALYST')")
    public ResponseEntity<List<PermissionResponse>> getPermissionsByRoleId(@PathVariable Long roleId) {
        log.info("Fetching permissions for role id: {}", roleId);
        
        List<PermissionResponse> permissions = roleService.getPermissionsByRoleId(roleId)
                .stream()
                .map(permissionMapper::toResponse)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(permissions);
    }

    // POST /api/roles - Créer un rôle
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RoleResponse> createRole(@Valid @RequestBody CreateRoleRequest request) {
        log.info("Creating new role: {}", request.getName());
        
        Role role = roleService.createRole(
                request.getName(),
                request.getDescription(),
                request.getPermissionIds());
        
        List<Permission> permissions = roleService.getPermissionsByRoleId(role.getId());
        
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(roleMapper.toResponseWithPermissions(role, permissions));
    }

    // PUT /api/roles/{id} - Mettre à jour un rôle
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RoleResponse> updateRole(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRoleRequest request) {
        log.info("Updating role id: {}", id);
        
        Role role = roleService.updateRole(
                id,
                request.getDescription(),
                request.getPermissionIds());
        
        List<Permission> permissions = roleService.getPermissionsByRoleId(role.getId());
        
        return ResponseEntity.ok(roleMapper.toResponseWithPermissions(role, permissions));
    }

    // POST /api/roles/{roleId}/permissions/{permissionId} - Ajouter une permission
    @PostMapping("/{roleId}/permissions/{permissionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addPermissionToRole(
            @PathVariable Long roleId,
            @PathVariable Long permissionId) {
        log.info("Adding permission {} to role {}", permissionId, roleId);
        
        roleService.addPermissionToRole(roleId, permissionId);
        return ResponseEntity.ok().build();
    }

    // DELETE /api/roles/{roleId}/permissions/{permissionId} - Retirer une permission
    @DeleteMapping("/{roleId}/permissions/{permissionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> removePermissionFromRole(
            @PathVariable Long roleId,
            @PathVariable Long permissionId) {
        log.info("Removing permission {} from role {}", permissionId, roleId);
        
        roleService.removePermissionFromRole(roleId, permissionId);
        return ResponseEntity.ok().build();
    }

    // DELETE /api/roles/{id} - Supprimer un rôle
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteRole(@PathVariable Long id) {
        log.info("Deleting role id: {}", id);
        
        roleService.deleteRole(id);
        return ResponseEntity.noContent().build();
    }

    // POST /api/roles/sync - Synchroniser avec Keycloak
    @PostMapping("/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> syncRolesFromKeycloak() {
        log.info("Syncing roles from Keycloak");
        
        roleService.syncRolesFromKeycloak();
        return ResponseEntity.ok().build();
    }
}
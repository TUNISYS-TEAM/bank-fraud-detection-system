package com.fraud.backend.api.controller;

import com.fraud.backend.api.dto.accessmanagement.permission.response.PermissionResponse;
import com.fraud.backend.api.dto.accessmanagement.permission.PermissionMapper;
import com.fraud.backend.api.dto.accessmanagement.role.request.CreateRoleRequest;
import com.fraud.backend.api.dto.accessmanagement.role.request.UpdateRoleRequest;
import com.fraud.backend.api.dto.accessmanagement.role.response.RoleResponse;
import com.fraud.backend.api.dto.accessmanagement.role.RoleMapper;
import com.fraud.backend.data.service.accessmanagement.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;

    // GET /api/roles
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<RoleResponse>> getAllRoles() {
        return ResponseEntity.ok(
                roleService.getAllRoles()
                        .stream()
                        .map(roleMapper::toResponse)
                        .collect(Collectors.toList()));
    }

    // GET /api/roles/{id}
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RoleResponse> getRoleById(@PathVariable Long id) {
        return ResponseEntity.ok(roleMapper.toResponse(roleService.getRoleById(id)));
    }

    // GET /api/roles/{roleId}/permissions
    @GetMapping("/{roleId}/permissions")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER') or hasRole('ANALYST')")
    public ResponseEntity<List<PermissionResponse>> getPermissionsByRoleId(@PathVariable Long roleId) {
        return ResponseEntity.ok(
                roleService.getPermissionsByRoleId(roleId)
                        .stream()
                        .map(permissionMapper::toResponse)
                        .collect(Collectors.toList()));
    }

    // POST /api/roles
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RoleResponse> createRole(@Valid @RequestBody CreateRoleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                roleMapper.toResponse(
                        roleService.createRole(
                                request.getName(),
                                request.getDescription(),
                                request.getPermissionIds())));
    }

    // PUT /api/roles/{id}
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RoleResponse> updateRole(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRoleRequest request) {
        return ResponseEntity.ok(
                roleMapper.toResponse(
                        roleService.updateRole(
                                id,
                                request.getDescription(),
                                request.getPermissionIds())));
    }

    // POST /api/roles/{roleId}/permissions/{permissionId}
    @PostMapping("/{roleId}/permissions/{permissionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addPermissionToRole(
            @PathVariable Long roleId,
            @PathVariable Long permissionId) {
        roleService.addPermissionToRole(roleId, permissionId);
        return ResponseEntity.ok().build();
    }

    // DELETE /api/roles/{roleId}/permissions/{permissionId}
    @DeleteMapping("/{roleId}/permissions/{permissionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> removePermissionFromRole(
            @PathVariable Long roleId,
            @PathVariable Long permissionId) {
        roleService.removePermissionFromRole(roleId, permissionId);
        return ResponseEntity.ok().build();
    }

    // DELETE /api/roles/{id}
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteRole(@PathVariable Long id) {
        roleService.deleteRole(id);
        return ResponseEntity.noContent().build();
    }

    // POST /api/roles/sync
    @PostMapping("/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> syncRolesFromKeycloak() {
        roleService.syncRolesFromKeycloak();
        return ResponseEntity.ok().build();
    }
}
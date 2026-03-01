package com.fraud.backend.api.dto.accessmanagement.role;

import com.fraud.backend.api.dto.accessmanagement.role.response.RoleResponse;
import com.fraud.backend.api.dto.accessmanagement.permission.PermissionMapper;
import com.fraud.backend.data.entity.accessmanagement.Role;
import com.fraud.backend.data.entity.accessmanagement.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RoleMapper {

    private final PermissionMapper permissionMapper;

    public RoleResponse toResponse(Role role) {
        return RoleResponse.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .keycloakRoleName(role.getKeycloakRoleName())
                .permissions(Set.of()) 
                .build();
    }

    public RoleResponse toResponseWithPermissions(Role role, List<Permission> permissions) {
        return RoleResponse.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .keycloakRoleName(role.getKeycloakRoleName())
                .permissions(permissions.stream()
                        .map(permissionMapper::toResponse)
                        .collect(Collectors.toSet()))
                .build();
    }
}
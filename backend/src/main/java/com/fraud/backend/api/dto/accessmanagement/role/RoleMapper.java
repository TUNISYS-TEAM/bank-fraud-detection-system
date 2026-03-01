package com.fraud.backend.api.dto.accessmanagement.role;

import com.fraud.backend.api.dto.accessmanagement.role.response.RoleResponse;
import com.fraud.backend.api.dto.accessmanagement.permission.PermissionMapper;
import com.fraud.backend.data.entity.accessmanagement.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
                .permissions(role.getPermissions().stream()
                        .map(permissionMapper::toResponse)
                        .collect(Collectors.toSet()))
                .build();
    }
}
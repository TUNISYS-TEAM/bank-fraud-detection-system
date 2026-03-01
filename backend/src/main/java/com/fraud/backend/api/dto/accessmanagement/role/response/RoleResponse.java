package com.fraud.backend.api.dto.accessmanagement.role.response;

import com.fraud.backend.api.dto.accessmanagement.permission.response.PermissionResponse;
import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class RoleResponse {
    private Long id;
    private String name;
    private String description;
    private String keycloakRoleName;
    private Set<PermissionResponse> permissions;
}
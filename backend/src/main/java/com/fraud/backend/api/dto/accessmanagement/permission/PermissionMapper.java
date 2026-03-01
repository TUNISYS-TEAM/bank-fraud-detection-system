package com.fraud.backend.api.dto.accessmanagement.permission;

import com.fraud.backend.api.dto.accessmanagement.permission.response.PermissionResponse;
import com.fraud.backend.data.entity.accessmanagement.Permission;
import org.springframework.stereotype.Component;

@Component
public class PermissionMapper {

    public PermissionResponse toResponse(Permission permission) {
        return PermissionResponse.builder()
                .id(permission.getId())
                .name(permission.getName())
                .description(permission.getDescription())
                .action(permission.getAction())
                .build();
    }
}
package com.fraud.backend.api.dto.accessmanagement.role.request;

import lombok.Data;

import java.util.List;

@Data
public class UpdateRoleRequest {
    private String description;
    private List<Long> permissionIds;
}
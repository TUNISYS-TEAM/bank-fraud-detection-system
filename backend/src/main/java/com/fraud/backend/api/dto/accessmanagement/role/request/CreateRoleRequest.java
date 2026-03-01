package com.fraud.backend.api.dto.accessmanagement.role.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class CreateRoleRequest {
    
    @NotBlank(message = "Role name is required")
    private String name;
    
    private String description;
    
    private List<Long> permissionIds;
}
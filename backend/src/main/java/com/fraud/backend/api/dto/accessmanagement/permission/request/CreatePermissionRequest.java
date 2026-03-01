package com.fraud.backend.api.dto.accessmanagement.permission.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreatePermissionRequest {
    
    @NotBlank(message = "Permission name is required")
    private String name;
    
    private String description;
    
    @NotBlank(message = "Action is required")
    private String action;
}
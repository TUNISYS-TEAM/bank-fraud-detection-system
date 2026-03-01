package com.fraud.backend.api.dto.accessmanagement.permission.request;

import lombok.Data;

@Data
public class UpdatePermissionRequest {
    
    private String description;
    private String action;
}
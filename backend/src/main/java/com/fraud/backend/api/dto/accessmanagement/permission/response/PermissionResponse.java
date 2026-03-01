package com.fraud.backend.api.dto.accessmanagement.permission.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PermissionResponse {
    private Long id;
    private String name;
    private String description;
    private String action;
}
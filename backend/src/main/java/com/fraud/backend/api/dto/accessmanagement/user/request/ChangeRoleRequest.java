package com.fraud.backend.api.dto.accessmanagement.user.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChangeRoleRequest {
    @NotBlank
    private String role;
}
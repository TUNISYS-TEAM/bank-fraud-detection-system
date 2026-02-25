package com.fraud.backend.api.dto.accessmanagement.user.response;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class UserSummaryResponse {
    private Long id;
    private UUID uuid;
    private String email;
    private String firstName;
    private String lastName;
    private String role;
    private Boolean isActive;
}
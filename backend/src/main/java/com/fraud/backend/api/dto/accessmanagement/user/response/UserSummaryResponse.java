package com.fraud.backend.api.dto.accessmanagement.user.response;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class UserSummaryResponse {
    private Long id;
    private UUID uuid;
    private String email;
    private String firstName;
    private String lastName;
    private Long roleId;      
    private Boolean isActive;
    private LocalDateTime lastLogin;
}
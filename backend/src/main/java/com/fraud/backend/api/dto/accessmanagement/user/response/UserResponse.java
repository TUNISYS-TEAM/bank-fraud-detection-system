package com.fraud.backend.api.dto.accessmanagement.user.response;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class UserResponse {
    private Long id;
    private UUID uuid;
    private String email;
    private String firstName;
    private String lastName;
    private String phoneNumber;
    private String address;
    private LocalDate dateOfBirth;
    private Long roleId;          
    private Boolean isActive;
    private LocalDateTime lastLogin;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
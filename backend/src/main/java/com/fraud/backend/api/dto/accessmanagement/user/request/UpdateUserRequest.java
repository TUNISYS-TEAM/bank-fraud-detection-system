package com.fraud.backend.api.dto.accessmanagement.user.request;

import lombok.Data;
import java.time.LocalDate;

@Data
public class UpdateUserRequest {
    private String firstName;
    private String lastName;
    private String phoneNumber;
    private String address;
    private LocalDate dateOfBirth;
    private String email;
    private Long roleId;
    private Boolean isActive;
    private Long managerId;
}
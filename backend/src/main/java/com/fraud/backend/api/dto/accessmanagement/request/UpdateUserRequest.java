package com.fraud.backend.api.dto.accessmanagement.request;

import lombok.Data;
import java.time.LocalDate;

@Data
public class UpdateUserRequest {
    private String firstName;
    private String lastName;
    private String phoneNumber;
    private String address;
    private LocalDate dateOfBirth;
}
package com.fraud.backend.api.dto.accessmanagement.user.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDate;

@Data
public class CreateUserRequest {
    
    @NotBlank
    @Email
    private String email;
    
    @NotBlank
    private String password;
    
    @NotBlank
    private String firstName;
    
    @NotBlank
    private String lastName;
    
    private String phoneNumber;
    
    private LocalDate dateOfBirth;
    
    private String address;
    
    @NotNull  
    private Long roleId;
}
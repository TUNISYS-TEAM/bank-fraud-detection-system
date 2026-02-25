package com.fraud.backend.api.dto.accessmanagement.auth;

import lombok.Data;

@Data
public class LoginRequest {
    private String email;
    private String password;
}
package com.fraud.backend.api.dto.accessmanagement.auth;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
public class LoginResponse {
    @JsonProperty("access_token")
    private String accessToken;
    
    @JsonProperty("refresh_token")
    private String refreshToken;
    
    @JsonProperty("expires_in")
    private int expiresIn;
    
    @JsonProperty("refresh_expires_in")
    private int refreshExpiresIn;
    
    @JsonProperty("token_type")
    private String tokenType;
    
    @JsonProperty("session_state")
    private String sessionState;
    
    @JsonProperty("scope")
    private String scope;
}
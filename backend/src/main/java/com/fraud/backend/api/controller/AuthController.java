package com.fraud.backend.api.controller;

import com.fraud.backend.api.dto.accessmanagement.auth.LoginRequest;
import com.fraud.backend.api.dto.accessmanagement.auth.LoginResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:4200")
public class AuthController {

    private final RestTemplate restTemplate;

    @Value("${keycloak.server-url}")
    private String keycloakUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        log.info("Login attempt for user: {}", request.getEmail());
        
        String tokenUrl = keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/token";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("grant_type", "password");
        body.add("username", request.getEmail());
        body.add("password", request.getPassword());
        
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);
        
        try {
            LoginResponse response = restTemplate.postForObject(tokenUrl, entity, LoginResponse.class);
            log.info("Login successful for user: {}", request.getEmail());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Login failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@RequestBody Map<String, String> request) {
        String refreshToken = request.get("refresh_token");
        log.info("Token refresh attempt");
        
        String tokenUrl = keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/token";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("grant_type", "refresh_token");
        body.add("refresh_token", refreshToken);
        
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);
        
        try {
            LoginResponse response = restTemplate.postForObject(tokenUrl, entity, LoginResponse.class);
            log.info("Token refreshed successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Token refresh failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> userInfo = Map.of(
            "sub", jwt.getSubject(),
            "email", jwt.getClaimAsString("email"),
            "name", jwt.getClaimAsString("name"),
            "preferred_username", jwt.getClaimAsString("preferred_username"),
            "roles", jwt.getClaimAsMap("realm_access")
        );
        return ResponseEntity.ok(userInfo);
    }
}
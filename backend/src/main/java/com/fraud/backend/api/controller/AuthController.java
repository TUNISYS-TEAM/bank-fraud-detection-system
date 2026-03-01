package com.fraud.backend.api.controller;

import com.fraud.backend.api.dto.accessmanagement.auth.LoginRequest;
import com.fraud.backend.api.dto.accessmanagement.auth.LoginResponse;
import com.fraud.backend.data.service.accessmanagement.UserSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
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

import java.util.Base64;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:4200")
public class AuthController {

    private final RestTemplate restTemplate;
    private final UserSessionService userSessionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${keycloak.server-url}")
    private String keycloakUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
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

            if (response != null) {
                log.info("Login successful for user: {}", request.getEmail());

                // Extraire le session_state du token
                String sessionState = extractSessionState(response.getAccessToken());

                if (sessionState != null) {
                    try {
                        // Créer la session dans la base de données
                        userSessionService.createSession(request.getEmail(), sessionState, httpRequest);
                        log.info("Session created in DB for user: {} with session state: {}",
                                request.getEmail(), sessionState);
                    } catch (Exception e) {
                        log.error("Failed to create session in DB for user: {}", request.getEmail(), e);
                        // Ne pas bloquer le login si la création de session échoue
                    }
                } else {
                    log.warn("No session_state found in token for user: {}", request.getEmail());
                }
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Login failed for user: {}", request.getEmail(), e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    private String extractSessionState(String accessToken) {
        try {
            // JWT est format: header.payload.signature
            String[] parts = accessToken.split("\\.");
            if (parts.length >= 2) {
                String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
                log.debug("Token payload: {}", payload);

                @SuppressWarnings("unchecked")
                Map<String, Object> claims = objectMapper.readValue(payload, Map.class);

                // Le session_state est souvent dans les claims
                Object sessionState = claims.get("session_state");
                if (sessionState != null) {
                    return sessionState.toString();
                }

                // Essayer d'autres noms de claim possibles
                sessionState = claims.get("sid");
                if (sessionState != null) {
                    return sessionState.toString();
                }
            }
        } catch (Exception e) {
            log.error("Failed to extract session state from token", e);
        }
        return null;
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

            // Optionnellement, mettre à jour la session ici aussi
            if (response != null && response.getSessionState() != null) {
                log.info("New session state from refresh: {}", response.getSessionState());
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Token refresh failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody Map<String, String> request) {
        String refreshToken = request.get("refresh_token");
        log.info("Logout attempt with refresh token");

        try {
            // 1. Extraire le session_state du refresh token (oui, le refresh token contient
            // aussi les claims)
            String sessionState = extractSessionStateFromRefreshToken(refreshToken);

            if (sessionState != null) {
                // Terminer la session dans votre DB
                userSessionService.endSessionByKeycloakId(sessionState);
                log.info("Session ended via Keycloak ID: {}", sessionState);
            } else {
                log.warn("Could not extract session state from refresh token");
            }

            // 2. Déconnecter de Keycloak
            try {
                String logoutUrl = keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/logout";

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

                MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
                body.add("client_id", clientId);
                body.add("client_secret", clientSecret);
                body.add("refresh_token", refreshToken);

                HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);
                restTemplate.postForEntity(logoutUrl, entity, String.class);
                log.info("Keycloak logout successful");
            } catch (Exception e) {
                log.warn("Keycloak logout failed: {}", e.getMessage());
            }

            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("Logout failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private String extractSessionStateFromRefreshToken(String refreshToken) {
        try {
            // Le refresh token est aussi un JWT
            String[] parts = refreshToken.split("\\.");
            if (parts.length >= 2) {
                String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
                log.debug("Refresh token payload: {}", payload);

                @SuppressWarnings("unchecked")
                Map<String, Object> claims = objectMapper.readValue(payload, Map.class);

                // Chercher le session_state dans le refresh token
                Object sessionState = claims.get("session_state");
                if (sessionState != null) {
                    return sessionState.toString();
                }

                // Alternative
                sessionState = claims.get("sid");
                if (sessionState != null) {
                    return sessionState.toString();
                }
            }
        } catch (Exception e) {
            log.error("Failed to extract session state from refresh token", e);
        }
        return null;
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> userInfo = Map.of(
                "sub", jwt.getSubject(),
                "email", jwt.getClaimAsString("email"),
                "name", jwt.getClaimAsString("name"),
                "preferred_username", jwt.getClaimAsString("preferred_username"),
                "roles", jwt.getClaimAsMap("realm_access"));
        return ResponseEntity.ok(userInfo);
    }
}
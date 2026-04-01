package com.fraud.backend.api.controller.accessmanagement;

import com.fraud.backend.api.dto.accessmanagement.auth.LoginRequest;
import com.fraud.backend.config.security.SessionCacheService;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.accessmanagement.KeycloakMapping;
import com.fraud.backend.data.repository.accessmanagement.KeycloakMappingRepository;
import com.fraud.backend.data.service.accessmanagement.KeycloakService;
import com.fraud.backend.data.service.accessmanagement.UserService;
import com.fraud.backend.data.service.accessmanagement.UserSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RestTemplate restTemplate;
    private final UserSessionService userSessionService;
    private final SessionCacheService sessionCacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UserService userService;
    private final KeycloakMappingRepository keycloakMappingRepository;
    private final KeycloakService keycloakService;

    @Value("${keycloak.server-url}")
    private String keycloakUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    @Value("${app.cookie.secure:false}")
    private boolean secureCookie;

    @RequestMapping(method = RequestMethod.OPTIONS, path = "/**")
    public ResponseEntity<?> handleOptions(HttpServletResponse response) {
        addCorsHeaders(response);
        return ResponseEntity.ok().build();
    }

    private void addCorsHeaders(HttpServletResponse response) {
        response.setHeader("Access-Control-Allow-Origin", "http://localhost:4200");
        response.setHeader("Access-Control-Allow-Credentials", "true");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH");
        response.setHeader("Access-Control-Allow-Headers",
                "authorization, content-type, x-xsrf-token, x-requested-with, accept, origin, x-csrf-token, X-XSRF-TOKEN");
        response.setHeader("Access-Control-Expose-Headers", "Set-Cookie, XSRF-TOKEN, X-CSRF-TOKEN");
        response.setHeader("Access-Control-Max-Age", "3600");
    }

    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> csrf(HttpServletResponse response) {
        addCorsHeaders(response);
        clearOldCsrfCookies(response);
        return ResponseEntity.ok(Map.of("message", "CSRF cookie set by Spring Security"));
    }

    // ==================== OTP EMAIL VERIFICATION ENDPOINTS ====================

    /**
     * Request OTP code to be sent to user's email
     */
    @PostMapping("/request-otp")
    public ResponseEntity<Map<String, String>> requestOTP(@RequestBody Map<String, String> request) {
        String email = request.get("email");

        try {
            // Check if user exists
            AppUser user = userService.getByEmail(email);

            // Check if email is already verified
            KeycloakMapping mapping = keycloakMappingRepository.findByUserId(user.getId())
                    .orElseThrow(() -> new EntityNotFoundException("User not found"));

            if (keycloakService.isEmailVerified(mapping.getKeycloakId())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "EMAIL_ALREADY_VERIFIED"));
            }

            // Generate and send OTP
            String otp = keycloakService.generateAndSendOTP(email, user.getFirstName());

            log.info("OTP requested for: {}", email);
            return ResponseEntity.ok(Map.of(
                    "message", "Un code de vérification a été envoyé à votre email",
                    "email", email));

        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "USER_NOT_FOUND"));
        } catch (Exception e) {
            log.error("Failed to send OTP for: {}", email, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to send OTP"));
        }
    }

    /**
     * Verify OTP code entered by user
     */
    @PostMapping("/verify-otp")
    public ResponseEntity<Map<String, Object>> verifyOTP(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        String code = request.get("code");

        try {
            // Verify OTP
            if (!keycloakService.verifyOTP(email, code)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "INVALID_OTP", "message", "Code invalide ou expiré"));
            }

            // Get user and mark email as verified
            AppUser user = userService.getByEmail(email);
            KeycloakMapping mapping = keycloakMappingRepository.findByUserId(user.getId())
                    .orElseThrow(() -> new EntityNotFoundException("User not found"));

            // Mark email as verified in Keycloak
            keycloakService.markEmailAsVerified(mapping.getKeycloakId(), email, user.getFirstName());

            log.info("Email verified successfully via OTP for: {}", email);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Email vérifié avec succès");
            response.put("verified", true);

            return ResponseEntity.ok(response);

        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "USER_NOT_FOUND"));
        } catch (Exception e) {
            log.error("OTP verification failed for: {}", email, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Verification failed"));
        }
    }

    /**
     * Resend verification OTP
     */
    @PostMapping("/resend-otp")
    public ResponseEntity<Map<String, String>> resendOTP(@RequestBody Map<String, String> request) {
        String email = request.get("email");

        try {
            AppUser user = userService.getByEmail(email);
            KeycloakMapping mapping = keycloakMappingRepository.findByUserId(user.getId())
                    .orElseThrow(() -> new EntityNotFoundException("User not found"));

            if (keycloakService.isEmailVerified(mapping.getKeycloakId())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "EMAIL_ALREADY_VERIFIED"));
            }

            // Generate and send new OTP
            String otp = keycloakService.generateAndSendOTP(email, user.getFirstName());

            return ResponseEntity.ok(Map.of(
                    "message", "Un nouveau code de vérification a été envoyé à votre email",
                    "email", email));

        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "USER_NOT_FOUND"));
        } catch (Exception e) {
            log.error("Failed to resend OTP for: {}", email, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to resend OTP"));
        }
    }

    // ==================== LOGIN ENDPOINT ====================

    /**
     * LOGIN with Email Verification Check
     */
    // Update the login method - remove the automatic OTP sending
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

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
            @SuppressWarnings("unchecked")
            Map<String, Object> keycloakResponse = restTemplate.postForObject(tokenUrl, entity, Map.class);

            if (keycloakResponse == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            String accessToken = (String) keycloakResponse.get("access_token");
            String refreshToken = (String) keycloakResponse.get("refresh_token");
            Integer expiresIn = (Integer) keycloakResponse.get("expires_in");

            String sessionState = extractSessionState(accessToken);
            String keycloakId = extractKeycloakId(accessToken);

            if (sessionState == null || keycloakId == null) {
                log.error("Failed to extract session_state or keycloakId from access token");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Session initialization failed"));
            }

            // Get user and check email verification status
            AppUser user = userService.getByEmail(request.getEmail());

            // IMPORTANT: If email is not verified → return error WITHOUT sending OTP
            // Frontend will call /request-otp separately
            if (!isEmailVerified(user)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of(
                                "error", "EMAIL_NOT_VERIFIED",
                                "message", "Votre email n'est pas encore vérifié. Veuillez demander un code OTP."));
            }

            // === EMAIL IS VERIFIED → Proceed with normal login ===

            // Ensure KeycloakMapping exists
            try {
                Optional<KeycloakMapping> existingMapping = keycloakMappingRepository.findByKeycloakId(keycloakId);
                if (existingMapping.isEmpty()) {
                    KeycloakMapping mapping = KeycloakMapping.builder()
                            .keycloakId(keycloakId)
                            .user(user)
                            .username(request.getEmail())
                            .email(request.getEmail())
                            .lastSync(LocalDateTime.now())
                            .build();
                    keycloakMappingRepository.save(mapping);
                    log.info("Created KeycloakMapping for user: {}", request.getEmail());
                } else {
                    KeycloakMapping mapping = existingMapping.get();
                    mapping.setLastSync(LocalDateTime.now());
                    mapping.setEmail(request.getEmail());
                    mapping.setUsername(request.getEmail());
                    keycloakMappingRepository.save(mapping);
                }
            } catch (Exception e) {
                log.error("Failed to create/update KeycloakMapping: {}", e.getMessage());
            }

            // Store tokens in cache
            sessionCacheService.createSession(sessionState, accessToken, refreshToken, request.getEmail());
            log.info("Tokens stored in cache for session: {} (user: {})", sessionState, request.getEmail());

            // Clear old cookies and set new session_id
            clearAllCookies(httpResponse);
            clearOldCsrfCookies(httpResponse);
            setSessionIdCookie(httpResponse, sessionState, 30 * 60);

            // Create session in database
            try {
                userSessionService.createSession(request.getEmail(), sessionState, httpRequest);
                log.info("Session created in DB for user: {}", request.getEmail());
            } catch (Exception e) {
                log.error("Failed to create session in DB", e);
            }

            return ResponseEntity.ok(Map.of(
                    "username", request.getEmail(),
                    "expires_in", expiresIn != null ? expiresIn : 300));

        } catch (Exception e) {
            log.error("Login failed for user: {}", request.getEmail(), e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Email ou mot de passe incorrect"));
        }
    }

    // ==================== OTHER AUTH ENDPOINTS ====================

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        addCorsHeaders(httpResponse);

        String sessionId = getCookieValue(httpRequest, "session_id");

        if (sessionId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "No active session"));
        }

        String refreshToken = sessionCacheService.getRefreshToken(sessionId);

        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Session expired"));
        }

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
            @SuppressWarnings("unchecked")
            Map<String, Object> keycloakResponse = restTemplate.postForObject(tokenUrl, entity, Map.class);

            if (keycloakResponse == null) {
                sessionCacheService.endSession(sessionId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            String newAccessToken = (String) keycloakResponse.get("access_token");
            String newRefreshToken = (String) keycloakResponse.get("refresh_token");
            Integer expiresIn = (Integer) keycloakResponse.get("expires_in");

            sessionCacheService.refreshSession(sessionId, newAccessToken, newRefreshToken);

            clearAllCookies(httpResponse);
            setSessionIdCookie(httpResponse, sessionId, 30 * 60);

            return ResponseEntity.ok(Map.of("expires_in", expiresIn != null ? expiresIn : 300));

        } catch (Exception e) {
            log.error("Token refresh failed for session {}: {}", sessionId, e.getMessage());
            sessionCacheService.endSession(sessionId);
            clearAllCookies(httpResponse);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Refresh token expired or invalid"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        addCorsHeaders(httpResponse);

        try {
            String sessionId = getCookieValue(httpRequest, "session_id");

            if (sessionId != null) {
                String refreshToken = sessionCacheService.getRefreshToken(sessionId);
                sessionCacheService.endSession(sessionId);

                if (refreshToken != null) {
                    String logoutUrl = keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/logout";
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                    MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
                    body.add("client_id", clientId);
                    body.add("client_secret", clientSecret);
                    body.add("refresh_token", refreshToken);
                    HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);
                    try {
                        restTemplate.postForEntity(logoutUrl, entity, String.class);
                    } catch (Exception e) {
                        log.error("Keycloak logout failed: {}", e.getMessage());
                    }
                }

                try {
                    userSessionService.endSessionByKeycloakId(sessionId);
                } catch (Exception e) {
                    log.error("Failed to end session in database: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Error during logout process: {}", e.getMessage());
        } finally {
            clearAllCookies(httpResponse);
        }

        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> getCurrentUser(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletResponse httpResponse) {

        addCorsHeaders(httpResponse);

        Map<String, Object> userInfo = Map.of(
                "sub", jwt.getSubject(),
                "email", jwt.getClaimAsString("email"),
                "name", jwt.getClaimAsString("name"),
                "preferred_username", jwt.getClaimAsString("preferred_username"),
                "realm_access", jwt.getClaimAsMap("realm_access"));
        return ResponseEntity.ok(userInfo);
    }

    // ==================== PRIVATE METHODS ====================

    private boolean isEmailVerified(AppUser user) {
        try {
            KeycloakMapping mapping = keycloakMappingRepository.findByUserId(user.getId())
                    .orElseThrow(() -> new EntityNotFoundException("Keycloak mapping not found"));
            return keycloakService.isEmailVerified(mapping.getKeycloakId());
        } catch (Exception e) {
            log.error("Failed to check email verification status for user {}: {}", user.getEmail(), e.getMessage());
            return false;
        }
    }

    private void setSessionIdCookie(HttpServletResponse response, String sessionId, int maxAge) {
        ResponseCookie cookie = ResponseCookie.from("session_id", sessionId)
                .path("/")
                .maxAge(maxAge)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private void clearCookie(HttpServletResponse response, String name, String path) {
        ResponseCookie cookie = ResponseCookie.from(name, "")
                .path(path)
                .maxAge(0)
                .httpOnly(name.contains("token") || name.equals("session_id"))
                .secure(secureCookie)
                .sameSite("Strict")
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private void clearOldCsrfCookies(HttpServletResponse response) {
        ResponseCookie oldCookie1 = ResponseCookie.from("XSRF-TOKEN", "")
                .path("/api").maxAge(0).httpOnly(false).secure(false).sameSite("Lax").build();
        response.addHeader("Set-Cookie", oldCookie1.toString());

        ResponseCookie oldCookie2 = ResponseCookie.from("XSRF-TOKEN", "")
                .path("/api/").maxAge(0).httpOnly(false).secure(false).sameSite("Lax").build();
        response.addHeader("Set-Cookie", oldCookie2.toString());

        ResponseCookie oldCookie3 = ResponseCookie.from("XSRF-TOKEN", "")
                .path("/").maxAge(0).httpOnly(true).secure(false).sameSite("Lax").build();
        response.addHeader("Set-Cookie", oldCookie3.toString());
    }

    private void clearAllCookies(HttpServletResponse response) {
        clearCookie(response, "session_id", "/");
        clearCookie(response, "session_id", "/api");
        clearCookie(response, "access_token", "/");
        clearCookie(response, "access_token", "/api");
        clearCookie(response, "refresh_token", "/");
        clearCookie(response, "refresh_token", "/api");
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null)
            return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private String extractSessionState(String accessToken) {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length >= 2) {
                String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
                @SuppressWarnings("unchecked")
                Map<String, Object> claims = objectMapper.readValue(payload, Map.class);
                Object sessionState = claims.get("session_state");
                if (sessionState != null)
                    return sessionState.toString();
                sessionState = claims.get("sid");
                if (sessionState != null)
                    return sessionState.toString();
            }
        } catch (Exception e) {
            log.error("Failed to extract session state from token", e);
        }
        return null;
    }

    private String extractKeycloakId(String accessToken) {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length >= 2) {
                String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
                @SuppressWarnings("unchecked")
                Map<String, Object> claims = objectMapper.readValue(payload, Map.class);
                Object sub = claims.get("sub");
                if (sub != null)
                    return sub.toString();
            }
        } catch (Exception e) {
            log.error("Failed to extract keycloak ID from token", e);
        }
        return null;
    }
}
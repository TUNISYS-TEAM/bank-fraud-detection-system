package com.fraud.backend.api.controller.accessmanagement;

import com.fraud.backend.api.dto.accessmanagement.auth.LoginRequest;
import com.fraud.backend.data.service.accessmanagement.UserSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
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
                "authorization, content-type, x-xsrf-token, x-requested-with, accept, origin");
        response.setHeader("Access-Control-Expose-Headers", "Set-Cookie, XSRF-TOKEN");
        response.setHeader("Access-Control-Max-Age", "3600");
    }

    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> csrf(HttpServletResponse response) {
        addCorsHeaders(response);

        String csrfToken = UUID.randomUUID().toString();

        // Clear any existing XSRF-TOKEN cookies
        clearCookie(response, "XSRF-TOKEN", "/");
        clearCookie(response, "XSRF-TOKEN", "/api");

        // Set new cookie with /api path
        Cookie cookie = new Cookie("XSRF-TOKEN", csrfToken);
        cookie.setPath("/api");
        cookie.setMaxAge(3600);
        cookie.setHttpOnly(false);
        cookie.setSecure(secureCookie);
        response.addCookie(cookie);

        return ResponseEntity.ok(Map.of("csrfToken", csrfToken));
    }

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
            Integer refreshExp = (Integer) keycloakResponse.get("refresh_expires_in");

            // Clear any existing cookies first
            clearCookie(httpResponse, "access_token", "/");
            clearCookie(httpResponse, "access_token", "/api");
            clearCookie(httpResponse, "refresh_token", "/");
            clearCookie(httpResponse, "refresh_token", "/api");
            clearCookie(httpResponse, "XSRF-TOKEN", "/");
            clearCookie(httpResponse, "XSRF-TOKEN", "/api");

            // Set new cookies with consistent /api path
            setTokenCookie(httpResponse, "access_token", accessToken, expiresIn != null ? expiresIn : 300);
            setTokenCookie(httpResponse, "refresh_token", refreshToken, refreshExp != null ? refreshExp : 1800);

            String csrfToken = UUID.randomUUID().toString();
            setXsrfCookie(httpResponse, csrfToken, expiresIn != null ? expiresIn : 300);

            // Track session in database
            String sessionState = extractSessionState(accessToken);
            if (sessionState != null) {
                try {
                    userSessionService.createSession(request.getEmail(), sessionState, httpRequest);
                } catch (Exception e) {
                    log.error("Failed to create session in DB for user: {}", request.getEmail(), e);
                }
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

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        addCorsHeaders(httpResponse);

        String refreshToken = getCookieValue(httpRequest, "refresh_token");
        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "No refresh token"));
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
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            String newAccessToken = (String) keycloakResponse.get("access_token");
            String newRefreshToken = (String) keycloakResponse.get("refresh_token");
            Integer expiresIn = (Integer) keycloakResponse.get("expires_in");
            Integer refreshExp = (Integer) keycloakResponse.get("refresh_expires_in");

            // Clear old cookies and set new ones
            clearCookie(httpResponse, "access_token", "/");
            clearCookie(httpResponse, "access_token", "/api");
            clearCookie(httpResponse, "refresh_token", "/");
            clearCookie(httpResponse, "refresh_token", "/api");
            clearCookie(httpResponse, "XSRF-TOKEN", "/");
            clearCookie(httpResponse, "XSRF-TOKEN", "/api");

            setTokenCookie(httpResponse, "access_token", newAccessToken, expiresIn != null ? expiresIn : 300);
            setTokenCookie(httpResponse, "refresh_token", newRefreshToken, refreshExp != null ? refreshExp : 1800);

            String csrfToken = UUID.randomUUID().toString();
            setXsrfCookie(httpResponse, csrfToken, expiresIn != null ? expiresIn : 300);

            return ResponseEntity.ok(Map.of("expires_in", expiresIn != null ? expiresIn : 300));

        } catch (Exception e) {
            log.error("Token refresh failed: {}", e.getMessage());
            clearTokenCookies(httpResponse);
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
            // Get the refresh token from cookie
            String refreshToken = getCookieValue(httpRequest, "refresh_token");

            // Get the access token to extract session state
            String accessToken = getCookieValue(httpRequest, "access_token");

            if (refreshToken != null) {
                // Call Keycloak logout endpoint to invalidate the session
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

            // Extract session state from access token and end session in database
            if (accessToken != null) {
                String sessionState = extractSessionState(accessToken);
                if (sessionState != null) {
                    try {
                        userSessionService.endSessionByKeycloakId(sessionState);
                    } catch (Exception e) {
                        log.error("Failed to end session in database: {}", e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error during logout process: {}", e.getMessage());
        } finally {
            // Always clear cookies, even if something fails
            clearTokenCookies(httpResponse);
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

    private void setTokenCookie(HttpServletResponse response, String name, String value, int maxAge) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .path("/api")
                .maxAge(maxAge)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private void setXsrfCookie(HttpServletResponse response, String value, int maxAge) {
        ResponseCookie cookie = ResponseCookie.from("XSRF-TOKEN", value)
                .path("/api")
                .maxAge(maxAge)
                .httpOnly(false)
                .secure(true)
                .sameSite("None")
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private void clearCookie(HttpServletResponse response, String name, String path) {
        ResponseCookie cookie = ResponseCookie.from(name, "")
                .path(path)
                .maxAge(0)
                .httpOnly(name.contains("token"))
                .secure(true)
                .sameSite("None")
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private void clearTokenCookies(HttpServletResponse response) {
        String[] cookies = { "access_token", "refresh_token", "XSRF-TOKEN" };
        for (String name : cookies) {
            clearCookie(response, name, "/");
            clearCookie(response, name, "/api");
        }
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
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
}
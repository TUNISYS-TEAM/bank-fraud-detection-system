package com.fraud.backend.config.security;

import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.accessmanagement.KeycloakMapping;
import com.fraud.backend.data.repository.accessmanagement.KeycloakMappingRepository;
import com.fraud.backend.data.service.accessmanagement.UserService;
import com.fraud.backend.data.service.accessmanagement.UserSessionService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final UserSessionService userSessionService;
    private final SessionCacheService sessionCacheService;
    private final KeycloakMappingRepository keycloakMappingRepository;
    private final UserService userService;

    @Value("${app.cookie.secure:false}")
    private boolean secureCookie;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {

        String email = null;
        String sessionId = null;
        String accessToken = null;
        String refreshToken = null;
        String keycloakId = null;

        if (authentication.getPrincipal() instanceof DefaultOidcUser oidcUser) {
            email = oidcUser.getEmail();
            sessionId = oidcUser.getIdToken().getClaim("session_state");
            keycloakId = oidcUser.getIdToken().getClaim("sub");
        }

        // Extract real tokens
        if (authentication instanceof OAuth2LoginAuthenticationToken loginToken) {
            OAuth2AccessToken at = loginToken.getAccessToken();
            OAuth2RefreshToken rt = loginToken.getRefreshToken();
            if (at != null) accessToken = at.getTokenValue();
            if (rt != null) refreshToken = rt.getTokenValue();
        }

        final String finalEmail = email;
        final String finalSessionId = sessionId;
        final String finalAccessToken = accessToken;
        final String finalRefreshToken = refreshToken;
        final String finalKeycloakId = keycloakId;

        if (finalEmail != null && finalSessionId != null && finalAccessToken != null && finalKeycloakId != null) {
            try {
                // 1. Find local user
                AppUser user = userService.getByEmail(finalEmail);

                // 2. Ensure KeycloakMapping exists
                KeycloakMapping mapping = keycloakMappingRepository
                        .findByKeycloakId(finalKeycloakId)
                        .orElseGet(() -> {
                            KeycloakMapping newMapping = KeycloakMapping.builder()
                                    .keycloakId(finalKeycloakId)
                                    .user(user)
                                    .username(finalEmail)
                                    .email(finalEmail)
                                    .lastSync(LocalDateTime.now())
                                    .build();
                            return keycloakMappingRepository.save(newMapping);
                        });

                log.info("KeycloakMapping ensured for user: {} with ID: {}", finalEmail, finalKeycloakId);

                // 3. Keep your existing DB tracking
                userSessionService.createSession(finalEmail, finalSessionId, request);

                // 4. Store tokens server-side only (Caffeine)
                sessionCacheService.createSession(finalSessionId, finalAccessToken, finalRefreshToken, finalEmail);

                // 5. Set session_id cookie
                Cookie sessionCookie = new Cookie("session_id", finalSessionId);
                sessionCookie.setHttpOnly(true);
                sessionCookie.setSecure(secureCookie);
                sessionCookie.setPath("/");
                sessionCookie.setMaxAge(30 * 60);           
                sessionCookie.setAttribute("SameSite", "Lax"); 
                response.addCookie(sessionCookie);

                log.info("Session created successfully for user: {}", finalEmail);

            } catch (Exception e) {
                log.error("Failed to create session for user: {}", finalEmail, e);
            }
        } else {
            log.warn("Missing required data: email={}, sessionId={}, accessToken={}, keycloakId={}", 
                     finalEmail, finalSessionId, finalAccessToken != null, finalKeycloakId);
        }

        // Continue with default redirect
        response.sendRedirect("/");
    }
}
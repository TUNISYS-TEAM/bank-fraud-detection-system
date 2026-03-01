package com.fraud.backend.config.security;

import com.fraud.backend.data.service.accessmanagement.UserSessionService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final UserSessionService userSessionService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, 
                                       HttpServletResponse response,
                                       Authentication authentication) throws IOException, ServletException {
        
        if (authentication.getPrincipal() instanceof DefaultOidcUser oidcUser) {
            String email = oidcUser.getEmail();
            String sessionId = oidcUser.getIdToken().getClaim("session_state");
            
            try {
                userSessionService.createSession(email, sessionId, request);
                log.info("Session tracking created for user: {}", email);
            } catch (Exception e) {
                log.error("Failed to create session tracking for user: {}", email, e);
            }
        }
        
        // Continue with the default behavior (redirect to default success URL)
        response.sendRedirect("/");
    }
}
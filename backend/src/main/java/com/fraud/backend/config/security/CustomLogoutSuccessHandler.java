package com.fraud.backend.config.security;

import com.fraud.backend.data.service.accessmanagement.UserSessionService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomLogoutSuccessHandler implements LogoutSuccessHandler {

    private final UserSessionService userSessionService;

    @Override
    public void onLogoutSuccess(HttpServletRequest request,
                               HttpServletResponse response,
                               Authentication authentication) throws IOException, ServletException {
        
        if (authentication != null && authentication.getPrincipal() instanceof DefaultOidcUser oidcUser) {
            String sessionId = oidcUser.getIdToken().getClaim("session_state");
            
            try {
                userSessionService.endSessionByKeycloakId(sessionId);
                log.info("Session ended for user: {}", oidcUser.getEmail());
            } catch (Exception e) {
                log.error("Failed to end session for user: {}", oidcUser.getEmail(), e);
            }
        }
        
        // Redirect to logout page or home
        response.sendRedirect("/logout-success");
    }
}
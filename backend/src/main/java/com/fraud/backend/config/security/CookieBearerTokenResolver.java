package com.fraud.backend.config.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class CookieBearerTokenResolver implements BearerTokenResolver {

    private final DefaultBearerTokenResolver defaultResolver = new DefaultBearerTokenResolver();
    private final SessionCacheService sessionCacheService;

    public CookieBearerTokenResolver(SessionCacheService sessionCacheService) {
        this.sessionCacheService = sessionCacheService;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        // 1. Try standard Authorization header first
        String token = defaultResolver.resolve(request);
        if (token != null) {
            return token;
        }

        // 2. Try to get token from session_id cookie
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            String sessionId = Arrays.stream(cookies)
                    .filter(c -> "session_id".equals(c.getName()))
                    .map(Cookie::getValue)
                    .findFirst()
                    .orElse(null);

            if (sessionId != null) {
                String accessToken = sessionCacheService.getAccessToken(sessionId);
                if (accessToken != null) {
                    return accessToken;
                }
            }
        }

        // 3. Fallback: try access_token cookie (backward compatibility)
        if (cookies != null) {
            return Arrays.stream(cookies)
                    .filter(c -> "access_token".equals(c.getName()))
                    .map(Cookie::getValue)
                    .findFirst()
                    .orElse(null);
        }

        return null;
    }
}
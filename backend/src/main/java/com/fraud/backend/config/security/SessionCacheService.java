package com.fraud.backend.config.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SessionCacheService {

    private final Cache<String, TokenPair> cache;

    public SessionCacheService() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(100_000)
                .recordStats()
                .build();
    }

    public void createSession(String sessionId, String accessToken, String refreshToken, String email) {
        cache.put(sessionId, new TokenPair(accessToken, refreshToken, email));
        log.info("Token pair cached for session: {} (user: {})", sessionId, email);
    }

    public String getAccessToken(String sessionId) {
        TokenPair pair = cache.getIfPresent(sessionId);
        return pair != null ? pair.accessToken() : null;
    }

    public String getRefreshToken(String sessionId) {
        TokenPair pair = cache.getIfPresent(sessionId);
        return pair != null ? pair.refreshToken() : null;
    }

    /**
     * IMPORTANT: Used by /auth/refresh to update tokens in memory only
     */
    public void refreshSession(String sessionId, String newAccessToken, String newRefreshToken) {
        TokenPair old = cache.getIfPresent(sessionId);
        if (old != null) {
            cache.put(sessionId, new TokenPair(newAccessToken, newRefreshToken, old.email()));
        } else {
            log.warn("Session not found in cache during refresh: {}", sessionId);
        }
    }

    public void endSession(String sessionId) {
        cache.invalidate(sessionId);
    }

    private record TokenPair(String accessToken, String refreshToken, String email) {}
}
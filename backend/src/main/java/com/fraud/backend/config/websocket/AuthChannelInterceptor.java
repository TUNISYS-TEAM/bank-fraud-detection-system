package com.fraud.backend.config.websocket;

import com.fraud.backend.config.security.SessionCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AuthChannelInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;
    private final SessionCacheService sessionCacheService; 

    public AuthChannelInterceptor(JwtDecoder jwtDecoder, SessionCacheService sessionCacheService) {
        this.jwtDecoder = jwtDecoder;
        this.sessionCacheService = sessionCacheService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String token = null;
            String tokenSource = null;

            // 1. Get sessionId from handshake attributes
            Object sessionIdObj = accessor.getSessionAttributes() != null
                    ? accessor.getSessionAttributes().get("sessionId")
                    : null;

            if (sessionIdObj instanceof String sessionId && !sessionId.isBlank()) {
                token = sessionCacheService.getAccessToken(sessionId);
                tokenSource = "session_id (Caffeine cache)";
            }

            // 2. Fallback: Authorization header in STOMP CONNECT frame
            if (token == null) {
                String authHeader = accessor.getFirstNativeHeader("Authorization");
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    token = authHeader.substring(7);
                    tokenSource = "Authorization header";
                }
            }

            if (token != null) {
                try {
                    Jwt jwt = jwtDecoder.decode(token);
                    final String subject = jwt.getSubject();
                    accessor.setUser(() -> subject);
                    log.info("WebSocket CONNECT authenticated via {} for subject: {}", tokenSource, subject);
                } catch (JwtException e) {
                    log.error("WebSocket CONNECT rejected: invalid token from {}", tokenSource, e);
                    throw new IllegalArgumentException("Invalid token");
                }
            } else {
                log.error("WebSocket CONNECT rejected: no token found in session_id or Authorization header");
                throw new IllegalArgumentException("No token found");
            }
        }
        return message;
    }
}
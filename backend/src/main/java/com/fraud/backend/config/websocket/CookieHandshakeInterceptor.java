package com.fraud.backend.config.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.servlet.http.Cookie;
import java.util.Map;

@Slf4j
public class CookieHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            Cookie[] cookies = servletRequest.getServletRequest().getCookies();

            if (cookies == null || cookies.length == 0) {
                log.warn("WebSocket handshake: NO cookies received at all");
                return true;
            }

            // Log all cookie names so you can identify the correct one
            for (Cookie cookie : cookies) {
                log.debug("WebSocket handshake cookie found: name='{}' (length={})",
                        cookie.getName(), cookie.getValue() != null ? cookie.getValue().length() : 0);

                // Check common token cookie names — add yours if different
                if ("access_token".equals(cookie.getName())
                        || "token".equals(cookie.getName())
                        || "AUTH_TOKEN".equals(cookie.getName())
                        || "KEYCLOAK_TOKEN".equals(cookie.getName())) {
                    attributes.put("token", cookie.getValue());
                    log.info("WebSocket handshake: token found in cookie '{}'", cookie.getName());
                    break;
                }
            }

            if (!attributes.containsKey("token")) {
                log.warn("WebSocket handshake: cookies present but none matched a known token cookie name. " +
                        "Cookie names: {}", java.util.Arrays.stream(cookies)
                        .map(Cookie::getName)
                        .toList());
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {}
}
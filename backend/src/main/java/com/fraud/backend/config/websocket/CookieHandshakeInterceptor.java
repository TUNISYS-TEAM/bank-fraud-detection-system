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

            // Log all cookies for debugging
            for (Cookie cookie : cookies) {
                log.debug("WebSocket handshake cookie found: name='{}' value.length={}",
                        cookie.getName(), cookie.getValue() != null ? cookie.getValue().length() : 0);

                if ("session_id".equals(cookie.getName())) {
                    attributes.put("sessionId", cookie.getValue());
                    log.info("WebSocket handshake: session_id found and stored in attributes");
                    return true;
                }
            }

            log.warn("WebSocket handshake: cookies present but no session_id found. Cookie names: {}",
                    java.util.Arrays.stream(cookies)
                            .map(Cookie::getName)
                            .toList());
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
    }
}
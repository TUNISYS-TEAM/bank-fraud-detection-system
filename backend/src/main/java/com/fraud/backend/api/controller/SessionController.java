package com.fraud.backend.api.controller;

import com.fraud.backend.api.dto.accessmanagement.session.response.SessionResponse;
import com.fraud.backend.api.dto.accessmanagement.session.SessionMapper;
import com.fraud.backend.data.service.accessmanagement.UserSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final UserSessionService userSessionService;
    private final SessionMapper sessionMapper;

    // GET /api/sessions/user/{userId}
    @GetMapping("/user/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<SessionResponse>> getUserSessions(@PathVariable Long userId) {
        return ResponseEntity.ok(
                userSessionService.getActiveSessionsForUser(userId)
                        .stream()
                        .map(sessionMapper::toResponse)
                        .collect(Collectors.toList())
        );
    }

    // DELETE /api/sessions/{sessionId}
    @DeleteMapping("/{sessionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> endSession(@PathVariable String sessionId) {
        userSessionService.endSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    // DELETE /api/sessions/user/{userId}/all
    @DeleteMapping("/user/{userId}/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> terminateAllUserSessions(@PathVariable Long userId) {
        userSessionService.terminateAllUserSessions(userId);
        return ResponseEntity.noContent().build();
    }
}
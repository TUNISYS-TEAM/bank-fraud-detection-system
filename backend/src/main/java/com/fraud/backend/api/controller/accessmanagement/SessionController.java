package com.fraud.backend.api.controller.accessmanagement;

import com.fraud.backend.api.dto.accessmanagement.session.response.SessionResponse;
import com.fraud.backend.api.dto.accessmanagement.session.SessionMapper;
import com.fraud.backend.data.entity.accessmanagement.UserSession;
import com.fraud.backend.data.service.accessmanagement.UserSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final UserSessionService userSessionService;
    private final SessionMapper sessionMapper;

    // GET /api/sessions - Get all sessions with pagination
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<SessionResponse>> getAllSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean activeOnly) {
        
        List<UserSession> allSessions = userSessionService.getAllSessions();
        
        // Filter by active status if requested
        if (activeOnly != null) {
            allSessions = allSessions.stream()
                    .filter(session -> session.getIsActive().equals(activeOnly))
                    .collect(Collectors.toList());
        }
        
        // Convert to responses
        List<SessionResponse> sessionResponses = allSessions.stream()
                .map(sessionMapper::toResponse)
                .collect(Collectors.toList());
        
        // Manual pagination
        int start = Math.min(page * size, sessionResponses.size());
        int end = Math.min(start + size, sessionResponses.size());
        
        List<SessionResponse> pageContent = sessionResponses.subList(start, end);
        Page<SessionResponse> sessionPage = new PageImpl<>(
                pageContent,
                PageRequest.of(page, size),
                sessionResponses.size()
        );
        
        return ResponseEntity.ok(sessionPage);
    }

    // GET /api/sessions/statistics - Get session statistics
    @GetMapping("/statistics")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getSessionStatistics() {
        return ResponseEntity.ok(userSessionService.getSessionStatistics());
    }

    // GET /api/sessions/recent - Get recent sessions
    @GetMapping("/recent")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<SessionResponse>> getRecentSessions(
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(
                userSessionService.getRecentSessions(limit)
                        .stream()
                        .map(sessionMapper::toResponse)
                        .collect(Collectors.toList())
        );
    }

    // GET /api/sessions/user/{userId} - Get sessions for specific user
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

    // GET /api/sessions/user/{userId}/history - Get session history for user
    @GetMapping("/user/{userId}/history")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<SessionResponse>> getUserSessionHistory(@PathVariable Long userId) {
        return ResponseEntity.ok(
                userSessionService.getUserSessionHistory(userId)
                        .stream()
                        .map(sessionMapper::toResponse)
                        .collect(Collectors.toList())
        );
    }

    // GET /api/sessions/{sessionId}/validate - Validate a session
    @GetMapping("/{sessionId}/validate")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER') or hasRole('ANALYST')")
    public ResponseEntity<Map<String, Boolean>> validateSession(@PathVariable String sessionId) {
        boolean isValid = userSessionService.isSessionValid(sessionId);
        return ResponseEntity.ok(Map.of("valid", isValid));
    }

    // DELETE /api/sessions/{sessionId} - End a specific session
    @DeleteMapping("/{sessionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> endSession(@PathVariable String sessionId) {
        userSessionService.endSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    // DELETE /api/sessions/user/{userId}/all - Terminate all user sessions
    @DeleteMapping("/user/{userId}/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> terminateAllUserSessions(@PathVariable Long userId) {
        userSessionService.terminateAllUserSessions(userId);
        return ResponseEntity.noContent().build();
    }

    // DELETE /api/sessions/user/{userId}/inactive - Clean up inactive sessions
    @DeleteMapping("/user/{userId}/inactive")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> cleanupInactiveSessions(@PathVariable Long userId) {
        userSessionService.cleanupInactiveSessions(userId);
        return ResponseEntity.noContent().build();
    }

    // POST /api/sessions/sync - Manually trigger sync with Keycloak
    @PostMapping("/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> syncSessionsWithKeycloak() {
        userSessionService.syncSessionsWithKeycloak();
        return ResponseEntity.ok().build();
    }
}
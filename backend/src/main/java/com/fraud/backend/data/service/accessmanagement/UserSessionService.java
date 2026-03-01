package com.fraud.backend.data.service.accessmanagement;

import com.fraud.backend.config.KeycloakProperties;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.accessmanagement.UserSession;
import com.fraud.backend.data.repository.accessmanagement.KeycloakMappingRepository;
import com.fraud.backend.data.repository.accessmanagement.UserSessionRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserSessionRepresentation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserSessionService {

    private final UserSessionRepository userSessionRepository;
    private final UserService userService;
    private final KeycloakMappingRepository keycloakMappingRepository;
    private final Keycloak keycloak;
    private final KeycloakProperties keycloakProperties;

    @Transactional
    public UserSession createSession(String email, String keycloakSessionId,
            HttpServletRequest request) {
        AppUser user = userService.getByEmail(email);

        String keycloakUserId = keycloakMappingRepository.findByUserId(user.getId())
                .map(mapping -> mapping.getKeycloakId())
                .orElseThrow(() -> new IllegalStateException("No Keycloak mapping found for user: " + email));

        // Vérifier que la session existe dans Keycloak 
        verifyKeycloakSession(keycloakUserId, keycloakSessionId);

        String sessionId = keycloakSessionId; 

        String ipAddress = getClientIpAddress(request);
        String userAgent = request.getHeader("User-Agent");

        UserSession session = UserSession.builder()
                .user(user)
                .sessionId(sessionId) 
                .keycloakSessionId(keycloakSessionId)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .loginTime(LocalDateTime.now())
                .lastActivity(LocalDateTime.now())
                .isActive(true)
                .build();

        UserSession savedSession = userSessionRepository.save(session);

        user.setLastLogin(LocalDateTime.now());

        log.info("Session created for user: {} with session ID: {} (Keycloak session: {})",
                email, sessionId, keycloakSessionId);
        return savedSession;
    }

    private void verifyKeycloakSession(String keycloakUserId, String keycloakSessionId) {
        try {
            List<UserSessionRepresentation> keycloakSessions = keycloak
                    .realm(keycloakProperties.getRealm())
                    .users()
                    .get(keycloakUserId)
                    .getUserSessions();

            boolean exists = keycloakSessions.stream()
                    .anyMatch(s -> s.getId().equals(keycloakSessionId));

            if (exists) {
                log.debug("Keycloak session verified: {}", keycloakSessionId);
            } else {
                log.warn("Keycloak session not found: {}", keycloakSessionId);
            }

        } catch (Exception e) {
            log.error("Failed to verify Keycloak session: {}", keycloakSessionId, e);
        }
    }

    @Transactional
    public void updateSessionActivity(String sessionId) {
        UserSession session = userSessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Session not found: " + sessionId));

        session.setLastActivity(LocalDateTime.now());
        userSessionRepository.save(session);
    }

    @Transactional
    public void endSession(String sessionId) {
        UserSession session = userSessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Session not found: " + sessionId));

        session.setLogoutTime(LocalDateTime.now());
        session.setIsActive(false);
        userSessionRepository.save(session);

        log.info("Session ended for user: {} at {}",
                session.getUser().getEmail(), session.getLogoutTime());
    }

    @Transactional
    public void endSessionByKeycloakId(String keycloakSessionId) {
        userSessionRepository.findByKeycloakSessionId(keycloakSessionId)
                .ifPresent(session -> {
                    session.setLogoutTime(LocalDateTime.now());
                    session.setIsActive(false);
                    userSessionRepository.save(session);
                    log.info("Session ended via Keycloak for user: {}",
                            session.getUser().getEmail());
                });
    }

    @Transactional(readOnly = true)
    public List<UserSession> getActiveSessionsForUser(Long userId) {
        return userSessionRepository.findActiveSessionsByUserId(userId);
    }

    @Transactional
    public void terminateAllUserSessions(Long userId) {
        List<UserSession> activeSessions = userSessionRepository.findByUserIdAndIsActiveTrue(userId);

        if (activeSessions.isEmpty()) {
            log.info("No active sessions found for user ID: {}", userId);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        activeSessions.forEach(session -> {
            session.setLogoutTime(now);
            session.setIsActive(false);
        });

        userSessionRepository.saveAll(activeSessions);
        log.info("Terminated {} active sessions for user ID: {}", activeSessions.size(), userId);
    }

    @Transactional
    public void cleanupInactiveSessions(Long userId) {
        userSessionRepository.deleteByUserIdAndIsActiveFalse(userId);
        log.info("Cleaned up inactive sessions for user ID: {}", userId);
    }

    @Scheduled(fixedDelay = 300000) // Toutes les 5 minutes
    @Transactional
    public void syncSessionsWithKeycloak() {
        log.info("Starting session synchronization with Keycloak");

        List<UserSession> activeSessions = userSessionRepository.findAll().stream()
                .filter(UserSession::getIsActive)
                .collect(Collectors.toList());

        if (activeSessions.isEmpty()) {
            log.debug("No active sessions to synchronize");
            return;
        }

        List<UserSession> sessionsToTerminate = new ArrayList<>();
        int totalProcessed = 0;
        int totalTerminated = 0;

        for (UserSession session : activeSessions) {
            totalProcessed++;

            try {
                String keycloakUserId = keycloakMappingRepository.findByUserId(session.getUser().getId())
                        .map(mapping -> mapping.getKeycloakId())
                        .orElse(null);

                if (keycloakUserId == null) {
                    log.warn("No Keycloak mapping found for user: {}", session.getUser().getEmail());
                    continue;
                }

                List<UserSessionRepresentation> keycloakSessions = keycloak
                        .realm(keycloakProperties.getRealm())
                        .users()
                        .get(keycloakUserId)
                        .getUserSessions();

                boolean existsInKeycloak = keycloakSessions.stream()
                        .anyMatch(s -> s.getId().equals(session.getKeycloakSessionId()));

                if (!existsInKeycloak) {
                    session.setLogoutTime(LocalDateTime.now());
                    session.setIsActive(false);
                    sessionsToTerminate.add(session);
                    totalTerminated++;
                    log.info("Session {} terminated - not found in Keycloak", session.getSessionId());
                }

            } catch (Exception e) {
                log.error("Failed to sync session {} with Keycloak", session.getSessionId(), e);
            }
        }

        if (!sessionsToTerminate.isEmpty()) {
            userSessionRepository.saveAll(sessionsToTerminate);
        }

        log.info("Session synchronization completed: processed {} sessions, terminated {}",
                totalProcessed, totalTerminated);
    }

    @Scheduled(cron = "0 0 * * * *") // Toutes les heures
    @Transactional
    public void cleanupStaleSessions() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusHours(6);
        List<UserSession> staleSessions = userSessionRepository.findStaleActiveSessions(cutoffTime);

        if (staleSessions.isEmpty()) {
            log.debug("No stale sessions to clean up");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        staleSessions.forEach(session -> {
            session.setLogoutTime(now);
            session.setIsActive(false);
            log.info("Cleaning up stale session: {} (no activity since {})",
                    session.getSessionId(), session.getLastActivity());
        });

        userSessionRepository.saveAll(staleSessions);
        log.info("Cleaned up {} stale sessions", staleSessions.size());
    }

    @Transactional(readOnly = true)
    public boolean isSessionValid(String sessionId) {
        return userSessionRepository.findBySessionId(sessionId)
                .map(session -> {
                    if (!session.getIsActive()) {
                        return false;
                    }
                    if (session.getLastActivity().isBefore(LocalDateTime.now().minusHours(6))) {
                        return false;
                    }
                    return true;
                })
                .orElse(false);
    }

    // Méthodes pour le dashboard
    @Transactional(readOnly = true)
    public List<UserSession> getAllSessions() {
        return userSessionRepository.findAllByOrderByLoginTimeDesc();
    }

    @Transactional(readOnly = true)
    public List<UserSession> getUserSessionHistory(Long userId) {
        return userSessionRepository.findByUserIdOrderByLoginTimeDesc(userId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSessionStatistics() {
        List<UserSession> allSessions = userSessionRepository.findAll();

        long totalActiveSessions = allSessions.stream()
                .filter(UserSession::getIsActive)
                .count();

        long totalSessions = allSessions.size();

        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        long activeLastHour = allSessions.stream()
                .filter(s -> Boolean.TRUE.equals(s.getIsActive()) &&
                        s.getLastActivity().isAfter(oneHourAgo))
                .count();

        LocalDateTime today = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        long sessionsToday = allSessions.stream()
                .filter(s -> s.getLoginTime().isAfter(today))
                .count();

        Map<String, Long> sessionsPerUser = allSessions.stream()
                .filter(UserSession::getIsActive)
                .collect(Collectors.groupingBy(
                        s -> s.getUser().getEmail(),
                        Collectors.counting()));

        return Map.of(
                "totalActiveSessions", totalActiveSessions,
                "totalSessions", totalSessions,
                "activeLastHour", activeLastHour,
                "sessionsToday", sessionsToday,
                "sessionsPerUser", sessionsPerUser,
                "timestamp", LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public List<UserSession> getRecentSessions(int limit) {
        return userSessionRepository.findAllByOrderByLoginTimeDesc()
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    private String getClientIpAddress(HttpServletRequest request) {
        String[] headers = {
                "X-Forwarded-For",
                "Proxy-Client-IP",
                "WL-Proxy-Client-IP",
                "HTTP_X_FORWARDED_FOR",
                "HTTP_X_FORWARDED",
                "HTTP_X_CLUSTER_CLIENT_IP",
                "HTTP_CLIENT_IP",
                "HTTP_FORWARDED_FOR",
                "HTTP_FORWARDED",
                "HTTP_VIA",
                "REMOTE_ADDR"
        };

        for (String header : headers) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                return ip.split(",")[0].trim();
            }
        }

        return request.getRemoteAddr();
    }
}
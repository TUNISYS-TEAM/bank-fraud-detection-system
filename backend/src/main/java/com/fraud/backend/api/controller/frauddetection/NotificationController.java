package com.fraud.backend.api.controller.frauddetection;

import com.fraud.backend.api.dto.frauddetection.notification.NotificationResponse;
import com.fraud.backend.data.service.notification.NotificationService;
import com.fraud.backend.data.repository.accessmanagement.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final AppUserRepository appUserRepository;

    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> getNotifications(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Long userId = getUserIdFromJwt(jwt);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        
        Page<com.fraud.backend.data.entity.frauddetection.Notification> notifications = 
            notificationService.getUserNotifications(userId, pageable);
        
        Page<NotificationResponse> response = notifications.map(notification -> 
            NotificationResponse.builder()
                .id(notification.getId())
                .notificationUuid(notification.getNotificationUuid())
                .title(notification.getTitle())
                .message(notification.getMessage())
                .type(notification.getType())
                .createdAt(notification.getCreatedAt())
                .isRead(notification.isRead())
                .readAt(notification.getReadAt())
                .build()
        );
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/unread/count")
    public ResponseEntity<Long> getUnreadCount(@AuthenticationPrincipal Jwt jwt) {
        Long userId = getUserIdFromJwt(jwt);
        return ResponseEntity.ok(notificationService.getUnreadCount(userId));
    }

    @PutMapping("/{notificationUuid}/read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable UUID notificationUuid,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = getUserIdFromJwt(jwt);
        notificationService.markAsRead(notificationUuid, userId);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(@AuthenticationPrincipal Jwt jwt) {
        Long userId = getUserIdFromJwt(jwt);
        notificationService.markAllAsRead(userId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{notificationUuid}")
    public ResponseEntity<Void> deleteNotification(
            @PathVariable UUID notificationUuid,
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = getUserIdFromJwt(jwt);
        notificationService.deleteNotification(notificationUuid, userId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/delete-all")
    public ResponseEntity<Void> deleteAllNotifications(@AuthenticationPrincipal Jwt jwt) {
        Long userId = getUserIdFromJwt(jwt);
        notificationService.deleteAllNotifications(userId);
        return ResponseEntity.ok().build();
    }

    private Long getUserIdFromJwt(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (email == null) {
            email = jwt.getClaimAsString("preferred_username");
        }
        if (email == null) {
            email = jwt.getClaimAsString("sub");
        }

        final String finalEmail = email;
        log.debug("Extracting user from JWT with email: {}", finalEmail);

        return appUserRepository.findByEmail(finalEmail)
                .orElseThrow(() -> new RuntimeException("User not found for email: " + finalEmail))
                .getId();
    }
}
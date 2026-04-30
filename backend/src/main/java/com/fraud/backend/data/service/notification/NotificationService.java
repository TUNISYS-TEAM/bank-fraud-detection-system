package com.fraud.backend.data.service.notification;

import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.frauddetection.Notification;
import com.fraud.backend.data.repository.accessmanagement.AppUserRepository;
import com.fraud.backend.data.repository.frauddetection.NotificationRepository;
import com.fraud.backend.data.event.NotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final AppUserRepository appUserRepository;

    @Transactional
    public void saveNotification(NotificationEvent event) {
        try {
            AppUser user = null;
            if (event.getUserId() != null) {
                user = appUserRepository.findById(event.getUserId()).orElse(null);
            }
            if (user == null && event.getUserEmail() != null) {
                user = appUserRepository.findByEmail(event.getUserEmail()).orElse(null);
            }

            if (user == null) {
                log.warn("User not found for notification: userId={}, email={}",
                        event.getUserId(), event.getUserEmail());
                return;
            }

            Notification notification = Notification.builder()
                    .notificationUuid(UUID.randomUUID())
                    .user(user)
                    .title(getTitleForEvent(event))
                    .message(getMessageForEvent(event))
                    .type(event.getEventType())
                    .isRead(false)
                    .readAt(null)
                    .createdAt(LocalDateTime.now())
                    .build();

            notificationRepository.save(notification);
            log.info("Notification saved to DB for user: {}", user.getEmail());

        } catch (Exception e) {
            log.error("Failed to save notification to DB", e);
        }
    }

    @Transactional
    public void markAsRead(UUID notificationUuid, Long userId) {
        notificationRepository.findByNotificationUuid(notificationUuid).ifPresent(notification -> {
            if (notification.getUser().getId().equals(userId)) {
                notification.setRead(true);
                notification.setReadAt(LocalDateTime.now());
                notificationRepository.save(notification);
                log.info("Notification {} marked as read for user {}", notificationUuid, userId);
            } else {
                log.warn("User {} tried to mark notification {} which belongs to user {}",
                        userId, notificationUuid, notification.getUser().getId());
            }
        });
    }

    @Transactional
    public void markAllAsRead(Long userId) {
        Page<Notification> unreadNotifications = notificationRepository
                .findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId, Pageable.unpaged());

        List<Notification> notifications = unreadNotifications.getContent();
        notifications.forEach(notification -> {
            notification.setRead(true);
            notification.setReadAt(LocalDateTime.now());
        });
        notificationRepository.saveAll(notifications);
        log.info("All notifications marked as read for user: {}", userId);
    }

    @Transactional
    public void deleteNotification(UUID notificationUuid, Long userId) {
        notificationRepository.findByNotificationUuid(notificationUuid).ifPresent(notification -> {
            if (notification.getUser().getId().equals(userId)) {
                notificationRepository.delete(notification);
                log.info("Notification {} deleted for user {}", notificationUuid, userId);
            } else {
                log.warn("User {} tried to delete notification {} which belongs to user {}",
                        userId, notificationUuid, notification.getUser().getId());
            }
        });
    }

    @Transactional
    public void deleteAllNotifications(Long userId) {
        Page<Notification> userNotifications = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, Pageable.unpaged());

        List<Notification> notifications = userNotifications.getContent();
        notificationRepository.deleteAll(notifications);
        log.info("All notifications deleted for user: {}", userId);
    }

    public Page<Notification> getUserNotifications(Long userId, Pageable pageable) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    public long getUnreadCount(Long userId) {
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }

    private String getTitleForEvent(NotificationEvent event) {
        switch (event.getEventType()) {
            case "NEW_MESSAGE":
                return "Nouveau message";
            case "VALIDATION_REQUEST":
                return "Demande de validation";
            case "MODEL_VALIDATED":
                return "Modèle validé";
            case "MODEL_REJECTED":
                return "Modèle rejeté";
            case "NEW_REPORT":
                return "Nouveau rapport";
            default:
                return "Notification";
        }
    }

    private String getMessageForEvent(NotificationEvent event) {
        var payload = event.getPayload();
        switch (event.getEventType()) {
            case "NEW_MESSAGE":
                return String.format("De: %s\nSujet: %s",
                        payload.get("senderName"), payload.get("subject"));
            case "VALIDATION_REQUEST":
                return String.format("Modèle: %s",
                        payload.getOrDefault("modelName", payload.get("modelUuid")));
            case "MODEL_VALIDATED":
                return String.format("Modèle %s validé", payload.get("modelUuid"));
            case "MODEL_REJECTED":
                return String.format("Modèle %s rejeté", payload.get("modelUuid"));
            case "NEW_REPORT":
                return String.format("%s\nDe: %s",
                        payload.get("title"), payload.get("generatedByName"));
            default:
                return "Nouvelle notification";
        }
    }
}
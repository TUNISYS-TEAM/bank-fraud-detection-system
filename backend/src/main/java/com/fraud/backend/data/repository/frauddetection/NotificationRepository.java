package com.fraud.backend.data.repository.frauddetection;

import com.fraud.backend.data.entity.frauddetection.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByNotificationUuid(UUID notificationUuid);

    /**
     * Notifications d’un utilisateur (boîte)
     */
    Page<Notification> findByUserIdOrderByCreatedAtDesc(
            Long userId,
            Pageable pageable
    );

    /**
     * Filtrage par type
     */
    Page<Notification> findByUserIdAndTypeOrderByCreatedAtDesc(
            Long userId,
            String type,
            Pageable pageable
    );

    /**
     * (Optionnel) compteur total des notifications d’un utilisateur
     */
    long countByUserId(Long userId);
}
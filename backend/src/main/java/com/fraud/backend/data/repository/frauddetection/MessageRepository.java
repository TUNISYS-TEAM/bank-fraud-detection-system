package com.fraud.backend.data.repository.frauddetection;

import com.fraud.backend.data.entity.frauddetection.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /**
     * Recherche par UUID
     */
    Optional<Message> findByMessageUuid(UUID messageUuid);

    /**
     * Boîte de réception (paginée)
     */
    Page<Message> findByReceiverIdOrderByCreatedAtDesc(
            Long receiverId,
            Pageable pageable
    );

    /**
     * Messages envoyés (paginé)
     */
    Page<Message> findBySenderIdOrderByCreatedAtDesc(
            Long senderId,
            Pageable pageable
    );

    /**
     * Messages non lus
     */
    Page<Message> findByReceiverIdAndIsReadFalse(
            Long receiverId,
            Pageable pageable
    );

    /**
     * Réponses d’un thread
     */
    Page<Message> findByParentMessageId(
            Long parentMessageId,
            Pageable pageable
    );
}
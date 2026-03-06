package com.fraud.backend.data.repository.messaging;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.fraud.backend.data.entity.messaging.Message;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {
    
    Optional<Message> findByMessageUuid(UUID messageUuid);
    
    // Messages reçus par un utilisateur
    Page<Message> findByReceiverIdOrderByCreatedAtDesc(Long receiverId, Pageable pageable);
    
    // Messages envoyés par un utilisateur
    Page<Message> findBySenderIdOrderByCreatedAtDesc(Long senderId, Pageable pageable);
    
    // Conversation entre deux utilisateurs
    @Query("SELECT m FROM Message m WHERE (m.sender.id = :userId1 AND m.receiver.id = :userId2) " +
           "OR (m.sender.id = :userId2 AND m.receiver.id = :userId1) " +
           "ORDER BY m.createdAt DESC")
    Page<Message> findConversation(@Param("userId1") Long userId1, 
                                   @Param("userId2") Long userId2, 
                                   Pageable pageable);
    
    // Messages non lus d'un utilisateur
    @Query("SELECT COUNT(m) FROM Message m WHERE m.receiver.id = :receiverId AND m.isRead = false")
    long countUnreadMessages(@Param("receiverId") Long receiverId);
    
    // Marquer comme lu
    @Modifying
    @Query("UPDATE Message m SET m.isRead = true, m.readAt = :readAt WHERE m.receiver.id = :receiverId AND m.isRead = false")
    int markAllAsRead(@Param("receiverId") Long receiverId, @Param("readAt") LocalDateTime readAt);
    
    @Modifying
    @Query("UPDATE Message m SET m.isRead = true, m.readAt = :readAt WHERE m.id = :messageId AND m.receiver.id = :receiverId")
    int markAsRead(@Param("messageId") Long messageId, 
                   @Param("receiverId") Long receiverId, 
                   @Param("readAt") LocalDateTime readAt);
}
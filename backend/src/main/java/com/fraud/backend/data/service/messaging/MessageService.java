package com.fraud.backend.data.service.messaging;

import com.fraud.backend.api.dto.messaging.request.SendMessageRequest;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.accessmanagement.KeycloakMapping;
import com.fraud.backend.data.entity.messaging.Message;
import com.fraud.backend.data.event.NotificationEvent;
import com.fraud.backend.data.repository.accessmanagement.AppUserRepository;
import com.fraud.backend.data.repository.accessmanagement.KeycloakMappingRepository;
import com.fraud.backend.data.repository.messaging.MessageRepository;
import com.fraud.backend.data.service.notification.NotificationEventProducer;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MessageService {

    private final MessageRepository messageRepository;
    private final AppUserRepository appUserRepository;
    private final KeycloakMappingRepository keycloakMappingRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationEventProducer notificationEventProducer; 

    private AppUser findUserByKeycloakId(String keycloakId) {
        return keycloakMappingRepository.findByKeycloakId(keycloakId)
                .map(KeycloakMapping::getUser)
                .orElseThrow(() -> new EntityNotFoundException("User not found with Keycloak ID: " + keycloakId));
    }

    private String getKeycloakIdForUser(AppUser user) {
        return keycloakMappingRepository.findByUserId(user.getId())
                .map(KeycloakMapping::getKeycloakId)
                .orElseThrow(() -> new EntityNotFoundException("Keycloak mapping not found for user: " + user.getId()));
    }

    @Transactional
    public Message sendMessage(String senderKeycloakId, SendMessageRequest request) {
        AppUser sender = findUserByKeycloakId(senderKeycloakId);

        AppUser receiver = appUserRepository.findById(request.getReceiverId())
                .orElseThrow(
                        () -> new EntityNotFoundException("Receiver not found with ID: " + request.getReceiverId()));

        Message parentMessage = null;
        if (request.getParentMessageUuid() != null) {
            parentMessage = messageRepository.findByMessageUuid(request.getParentMessageUuid())
                    .orElse(null);
        }

        Message message = Message.builder()
                .sender(sender)
                .receiver(receiver)
                .subject(request.getSubject())
                .content(request.getContent())
                .messageType(request.getMessageType() != null ? request.getMessageType() : "GENERAL")
                .parentMessage(parentMessage)
                .isRead(false)
                .build();

        Message savedMessage = messageRepository.save(message);

        // 1. Send immediate WebSocket notification (existing)
        notifyNewMessage(savedMessage);

        // 2. Send Kafka event for decoupled processing (new)
        emitKafkaNotification(savedMessage);

        return savedMessage;
    }

    private void notifyNewMessage(Message message) {
        try {
            String receiverKeycloakId = getKeycloakIdForUser(message.getReceiver());

            messagingTemplate.convertAndSendToUser(
                    receiverKeycloakId,
                    "/queue/messages",
                    message.getMessageUuid());

            long unreadCount = messageRepository.countUnreadMessages(message.getReceiver().getId());
            messagingTemplate.convertAndSendToUser(
                    receiverKeycloakId,
                    "/queue/unread",
                    unreadCount);
        } catch (Exception e) {
            log.error("Failed to send WebSocket notification", e);
        }
    }

    /**
     * Emit a Kafka NotificationEvent for the new message.
     * The event type depends on messageType: "VALIDATION" or "NEW_MESSAGE".
     */
    private void emitKafkaNotification(Message message) {
        try {
            String eventType = "VALIDATION".equals(message.getMessageType())
                    ? "VALIDATION_REQUEST"
                    : "NEW_MESSAGE";

            String principalName = message.getReceiver().getEmail() != null
                    ? message.getReceiver().getEmail()
                    : String.valueOf(message.getReceiver().getId());

            NotificationEvent event = NotificationEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType(eventType)
                    .userId(message.getReceiver().getId())
                    .userEmail(message.getReceiver().getEmail())
                    .payload(Map.of(
                            "messageUuid", message.getMessageUuid().toString(),
                            "subject", message.getSubject(),
                            "senderName", message.getSender().getFirstName() + " " + message.getSender().getLastName(),
                            "senderEmail", message.getSender().getEmail(),
                            "messageType", message.getMessageType()))
                    .timestamp(Instant.now())
                    .build();

            notificationEventProducer.sendNotification(event);
        } catch (Exception e) {
            log.error("FAILED TO SEND KAFKA NOTIFICATION", e);
        }
    }

    public Page<Message> getInbox(String userKeycloakId, int page, int size) {
        AppUser user = findUserByKeycloakId(userKeycloakId);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return messageRepository.findByReceiverIdAndIsArchivedFalseOrderByCreatedAtDesc(user.getId(), pageable);
    }

    public Page<Message> getValidationMessages(String userKeycloakId, int page, int size) {
        AppUser user = findUserByKeycloakId(userKeycloakId);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return messageRepository.findByReceiverIdAndMessageTypeAndIsArchivedFalseOrderByCreatedAtDesc(user.getId(),
                "VALIDATION", pageable);
    }

    public Page<Message> getSent(String userKeycloakId, int page, int size) {
        AppUser user = findUserByKeycloakId(userKeycloakId);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return messageRepository.findBySenderIdAndIsArchivedFalseOrderByCreatedAtDesc(user.getId(), pageable);
    }

    public Page<Message> getArchived(String userKeycloakId, int page, int size) {
        AppUser user = findUserByKeycloakId(userKeycloakId);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return messageRepository.findByReceiverIdAndIsArchivedTrueOrderByCreatedAtDesc(user.getId(), pageable);
    }

    @Transactional
    public void archiveMessages(String userKeycloakId, List<UUID> messageUuids) {
        AppUser user = findUserByKeycloakId(userKeycloakId);
        messageRepository.archiveMessages(messageUuids, user.getId());
    }

    @Transactional
    public void restoreMessages(String userKeycloakId, List<UUID> messageUuids) {
        AppUser user = findUserByKeycloakId(userKeycloakId);
        messageRepository.restoreMessages(messageUuids, user.getId());
    }

    public Page<Message> getConversation(String user1KeycloakId, String user2KeycloakId, int page, int size) {
        AppUser user1 = findUserByKeycloakId(user1KeycloakId);
        AppUser user2 = findUserByKeycloakId(user2KeycloakId);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return messageRepository.findConversation(user1.getId(), user2.getId(), pageable);
    }

    public long getUnreadCount(String userKeycloakId) {
        AppUser user = findUserByKeycloakId(userKeycloakId);
        return messageRepository.countUnreadMessages(user.getId());
    }

    @Transactional
    public int markAsRead(Long messageId, String receiverKeycloakId) {
        AppUser receiver = findUserByKeycloakId(receiverKeycloakId);
        int updated = messageRepository.markAsRead(messageId, receiver.getId(), LocalDateTime.now());
        if (updated > 0) {
            long unreadCount = messageRepository.countUnreadMessages(receiver.getId());
            messagingTemplate.convertAndSendToUser(
                    receiverKeycloakId,
                    "/queue/unread",
                    unreadCount);
        }
        return updated;
    }

    @Transactional
    public int markAsReadByUuid(UUID messageUuid, String receiverKeycloakId) {
        Message message = getMessage(messageUuid);
        return markAsRead(message.getId(), receiverKeycloakId);
    }

    @Transactional
    public int markAllAsRead(String receiverKeycloakId) {
        AppUser receiver = findUserByKeycloakId(receiverKeycloakId);
        int updated = messageRepository.markAllAsRead(receiver.getId(), LocalDateTime.now());
        if (updated > 0) {
            messagingTemplate.convertAndSendToUser(
                    receiverKeycloakId,
                    "/queue/unread",
                    0L);
        }
        return updated;
    }

    public Message getMessage(UUID messageUuid) {
        return messageRepository.findByMessageUuid(messageUuid)
                .orElseThrow(() -> new EntityNotFoundException("Message not found with UUID: " + messageUuid));
    }
}
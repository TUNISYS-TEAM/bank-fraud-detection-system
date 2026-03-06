package com.fraud.backend.data.service.messaging;

import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.messaging.Message;
import com.fraud.backend.data.repository.accessmanagement.AppUserRepository;
import com.fraud.backend.data.repository.messaging.MessageRepository;
import com.fraud.backend.api.dto.messaging.request.SendMessageRequest;
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

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MessageService {

    private final MessageRepository messageRepository;
    private final AppUserRepository appUserRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public Message sendMessage(Long senderId, SendMessageRequest request) {
        AppUser sender = appUserRepository.findById(senderId)
                .orElseThrow(() -> new EntityNotFoundException("Sender not found"));
        
        AppUser receiver = appUserRepository.findById(request.getReceiverId())
                .orElseThrow(() -> new EntityNotFoundException("Receiver not found"));
        
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
                .parentMessage(parentMessage)
                .isRead(false)
                .build();

        Message savedMessage = messageRepository.save(message);
        log.info("Message sent from {} to {}", sender.getEmail(), receiver.getEmail());

        notifyNewMessage(savedMessage);

        return savedMessage;
    }

    private void notifyNewMessage(Message message) {
        try {
            messagingTemplate.convertAndSendToUser(
                    message.getReceiver().getId().toString(),
                    "/queue/messages",
                    message.getMessageUuid()
            );
            
            long unreadCount = messageRepository.countUnreadMessages(message.getReceiver().getId());
            messagingTemplate.convertAndSendToUser(
                    message.getReceiver().getId().toString(),
                    "/queue/unread",
                    unreadCount
            );
        } catch (Exception e) {
            log.error("Failed to send WebSocket notification", e);
        }
    }

    public Page<Message> getInbox(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return messageRepository.findByReceiverIdOrderByCreatedAtDesc(userId, pageable);
    }

    public Page<Message> getSent(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return messageRepository.findBySenderIdOrderByCreatedAtDesc(userId, pageable);
    }

    public Page<Message> getConversation(Long userId1, Long userId2, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return messageRepository.findConversation(userId1, userId2, pageable);
    }

    public long getUnreadCount(Long userId) {
        return messageRepository.countUnreadMessages(userId);
    }

    @Transactional
    public int markAsRead(Long messageId, Long receiverId) {
        int updated = messageRepository.markAsRead(messageId, receiverId, LocalDateTime.now());
        
        if (updated > 0) {
            long unreadCount = messageRepository.countUnreadMessages(receiverId);
            messagingTemplate.convertAndSendToUser(
                    receiverId.toString(),
                    "/queue/unread",
                    unreadCount
            );
        }
        
        return updated;
    }

    @Transactional
    public int markAllAsRead(Long receiverId) {
        int updated = messageRepository.markAllAsRead(receiverId, LocalDateTime.now());
        
        if (updated > 0) {
            messagingTemplate.convertAndSendToUser(
                    receiverId.toString(),
                    "/queue/unread",
                    0L
            );
        }
        
        return updated;
    }

    public Message getMessage(UUID messageUuid) {
        return messageRepository.findByMessageUuid(messageUuid)
                .orElseThrow(() -> new EntityNotFoundException("Message not found"));
    }
}
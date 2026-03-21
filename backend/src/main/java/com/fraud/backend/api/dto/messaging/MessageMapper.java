package com.fraud.backend.api.dto.messaging;

import com.fraud.backend.data.entity.messaging.Message;
import org.springframework.stereotype.Component;

@Component
public class MessageMapper {
    
    public MessageDTO toDto(Message message) {
        if (message == null) return null;
        
        return MessageDTO.builder()
                .messageUuid(message.getMessageUuid())
                .senderId(message.getSender().getId())
                .senderEmail(message.getSender().getEmail())
                .senderName(message.getSender().getFirstName() + " " + message.getSender().getLastName())
                .receiverId(message.getReceiver().getId())
                .receiverEmail(message.getReceiver().getEmail())
                .receiverName(message.getReceiver().getFirstName() + " " + message.getReceiver().getLastName())
                .subject(message.getSubject())
                .content(message.getContent())
                .messageType(message.getMessageType())
                .isRead(Boolean.TRUE.equals(message.getIsRead()))
                .isArchived(Boolean.TRUE.equals(message.getIsArchived()))
                .readAt(message.getReadAt())
                .createdAt(message.getCreatedAt())
                .parentMessageUuid(message.getParentMessage() != null ? 
                                   message.getParentMessage().getMessageUuid() : null)
                .build();
    }
}